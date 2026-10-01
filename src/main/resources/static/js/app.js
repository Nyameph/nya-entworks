/**
 * 框架：导航渲染、hash 路由、环境自检抽屉。
 * 模块内容一概不在这里，见 modules.js 的注册表。
 */
(() => {

    // ---------- 模块开关 ----------
    /**
     * 三个业务模块各自的开关，后端下发（见 ModuleController）。关掉的模块
     * <b>它那一侧的 Controller 根本没注册</b>（@ConditionalOnManga 等），所以前端也要把
     * 入口一起收起来 —— 否则点进去每个请求都是 404，看起来像坏了、不像「关掉了」。
     *
     * <p>取不到时当作全开：后端没起的时候由左下角自检的红点报错，
     * 不在这里猜成「全关」把整个左导航画空。
     */
    let ModuleFlags = null;

    /**
     * 缺了磁盘路径根的那些项（全键 → {label, group, reason}），后端下发
     * （`/api/settings/missing-paths`，判据见 PageGates）。**只列缺了的**，
     * 不缺的键根本不出现 —— 查表查不到就是不遮。
     *
     * <p>它判的是**进程此刻的值**，所以「刚填好、还没重启后端」时这一页仍然遮着，
     * 卡片上那句「保存后要重启后端才生效」就是为这一刻写的。
     *
     * <p>取不到时当作**没有缺项**：后端没起的时候由左下角自检的红点报错，
     * 不在这里猜成「全缺」把每一页都遮上（与上面的 ModuleFlags 同一个取舍）。
     */
    let PageGaps = null;

    /**
     * 页面 id → 它属于哪个模块。子页跟父项，**顶层项自己也登记**（漏掉它的话
     * 整组的大标题与子项会一起躲过隐藏，只有导航之外的判定是对的）。
     * 任务/配置不是业务模块，登记了也不在开关里，等于永远可见。
     */
    const PageModule = (() => {
        const map = {};
        for (const mod of Modules) {
            map[mod.id] = mod.id;
            for (const child of mod.children || []) {
                map[child.id] = mod.id;
            }
        }
        return map;
    })();

    function moduleOn(pageId) {
        if (!ModuleFlags) {
            return true;
        }
        const moduleId = PageModule[pageId];
        return moduleId === undefined || ModuleFlags[moduleId] !== false;
    }

    // ---------- 导航 ----------
    function renderNav() {
        const nav = document.getElementById('nav');
        const foot = document.getElementById('nav-foot');
        let html = '';
        let footHtml = '';
        for (const mod of Modules) {
            if (mod.hidden) {
                // 有路由、没导航项：系统配置页的入口是左下角「系统工具」里的齿轮，不占导航列表
                continue;
            }
            if (!moduleOn(mod.id)) {
                // 模块关掉了：整组（大标题 + 子项）都不画
                continue;
            }
            if (mod.foot) {
                // 钉到左下角「系统工具」那一栏的项：图示 + 标题，右端留给未完成数量徽章
                // （数量由 refreshTaskBadge 刷）。图示与那一栏三个按钮的 .ico 同一个样式，
                // 所以「图示 + 文字」这个包裹层也用同一个形状（.nav-foot-main）
                footHtml += '<li><a data-id="' + mod.id + '" href="#' + mod.id
                    + '" class="nav-foot-link"><span class="nav-foot-main">'
                    + '<span class="ico">' + UI.esc(mod.icon || '') + '</span>'
                    + '<span>' + UI.esc(mod.title) + '</span></span>'
                    + '<span class="nav-count" data-count="' + mod.id + '" hidden>0</span>'
                    + '</a></li>';
                continue;
            }
            if (mod.children) {
                html += '<li class="nav-group">' + UI.esc(mod.title) + '</li>';
                for (const child of mod.children) {
                    html += '<li class="nav-sub"><a data-id="' + child.id + '" href="#' + child.id + '">'
                        + UI.esc(child.title)
                        + (child.todo ? ' <span class="nav-todo small">·未做</span>' : '')
                        + '</a></li>';
                }
            } else {
                html += '<li><a data-id="' + mod.id + '" href="#' + mod.id + '">'
                    + UI.esc(mod.title) + '</a></li>';
            }
        }
        nav.innerHTML = html;
        if (foot) {
            foot.innerHTML = footHtml;
        }
    }

    function markActive(id) {
        document.querySelectorAll('#nav a, #nav-foot a').forEach(a =>
            a.classList.toggle('active', a.dataset.id === id));
    }

    // ---------- 路由 ----------
    /** 默认落在第一个能渲染的页面上：顶层项可能只是分组，本身没有内容；关掉的模块跳过 */
    function defaultId() {
        for (const mod of Modules) {
            if (mod.hidden || !moduleOn(mod.id)) {
                continue;
            }
            if (!mod.children) {
                if (mod.render) {
                    return mod.id;
                }
                continue;
            }
            const first = mod.children.find(child => moduleOn(child.id));
            if (first) {
                return first.id;
            }
        }
        // 三个模块全关：只剩左下角那一栏，落回第一个注册项，别白屏
        return Modules[0].id;
    }

    function route() {
        // 先清掉上一页留下的遮罩与 inert —— 必须在这一函数的最前面：
        // 下面「未知页 / 模块已关闭」那两个分支会提前 return，放在它们之后就漏了
        clearGate();
        // 支持 #id?k=v：统计页点「已归档/待打分」跳转时带筛选参数，目标页在
        // render(host, params) 里读取并应用。消费后清掉查询串（replaceState 不触发
        // hashchange，页面已持有 params，刷新也不会重复应用）
        const raw = location.hash.replace(/^#/, '') || defaultId();
        const qIndex = raw.indexOf('?');
        const id = qIndex < 0 ? raw : raw.slice(0, qIndex);
        const params = qIndex < 0 ? {} : Object.fromEntries(new URLSearchParams(raw.slice(qIndex + 1)));

        const page = ModuleIndex[id];
        const title = document.getElementById('page-title');
        const desc = document.getElementById('page-desc');
        const body = document.getElementById('page-body');

        if (!page) {
            title.textContent = '没有这一页';
            desc.textContent = '';
            body.innerHTML = UI.empty('未知的模块：' + id);
            return;
        }
        if (!moduleOn(id)) {
            // 手敲 hash / 收藏夹里存着旧地址：老实说「关掉了、去哪开」，
            // 而不是放进去让人看一屏 404
            title.textContent = page.title;
            desc.textContent = '';
            body.innerHTML = UI.empty('「' + page.title + '」所在的模块已关闭：'
                + '在左下角「系统配置」里打开它那一栏的开关，保存后重启后端。');
            return;
        }
        markActive(id);
        title.textContent = page.title;
        desc.textContent = page.desc || '';

        if (page.render) {
            body.innerHTML = '';
            page.render(body, params);
            if (qIndex >= 0) {
                history.replaceState(null, '', '#' + id);
            }
        } else {
            // 占位页老实说明「为什么还没有」，而不是画一个空壳假装能用
            body.innerHTML = '<div class="card"><h2>还没做</h2><p class="muted">'
                + UI.esc(page.todo || '这一页尚未实现。') + '</p></div>';
        }
        // 遮罩排在 render 之后：这一页缺根的时候，画出来的空列表与「这里确实没有内容」
        // 长得一模一样，得在上面盖一层说清「先去配这一项」
        applyGate(page);
    }

    // ---------- 按页遮罩 ----------
    // 「这一页跑起来非有不可的磁盘路径」写在 modules.js 各页的 needs 里，这里只管
    // 拿后端算好的缺失集去查表、画卡片 —— 判定仍旧全在后端（前端只画不判）。

    /** 去掉当前这一页的遮罩，并把背后那半边解冻 */
    function clearGate() {
        document.querySelectorAll('.page-gate').forEach(el => el.remove());
        const body = document.getElementById('page-body');
        if (body) {
            body.inert = false;
        }
    }

    /**
     * 这一页缺了哪几项。needs 里字符串是「必须有」，数组是「这几个里有一个在就行」
     * （`every` 命中＝全缺）。
     */
    function gateMisses(page) {
        if (!PageGaps || !page.needs) {
            return [];
        }
        const missed = [];
        for (const need of page.needs) {
            const keys = Array.isArray(need) ? need : [need];
            if (keys.length && keys.every(k => PageGaps[k])) {
                missed.push(...keys.map(k => PageGaps[k]));
            }
        }
        return missed;
    }

    function applyGate(page) {
        const missed = gateMisses(page);
        if (missed.length) {
            gateOverlay(page, missed);
        }
    }

    /**
     * 挂遮罩：盖住内容区（**左侧导航照常能点**，见 app.css 的 .page-gate），
     * 居中一张卡片写明缺哪几项、去哪儿配。**点不掉** —— 不画关闭钮、不接空白点击。
     *
     * <p>挂在 document.body 上、不是 #page-body 里：页面 render 是异步的，内部会重画
     * host.innerHTML，挂在里面会被紧随其后的那次重画冲掉（症状是遮罩一闪就没了）。
     *
     * <p>同时给 #page-body 置 inert：fixed 遮罩只挡鼠标，背后那些按钮仍能被 Tab 到、
     * 回车触发 —— 「无法进行操作」得算上键盘那一半。
     */
    function gateOverlay(page, gaps) {
        let items = '';
        for (const gap of gaps) {
            items += '<li><div class="gate-label">' + UI.esc(gap.label) + '</div>'
                + '<div class="small muted">配置页 → ' + UI.esc(gap.group) + '</div>'
                + '<div class="small">' + UI.esc(gap.reason) + '</div></li>';
        }
        const el = document.createElement('div');
        el.className = 'page-gate';
        el.innerHTML = '<div class="page-gate-card">'
            + '<h2>「' + UI.esc(page.title) + '」还差配置</h2>'
            + '<p>这一页要用到的磁盘目录还没配好，先补上才能用：</p>'
            + '<ul class="gate-list">' + items + '</ul>'
            + '<p class="small muted">在左下角齿轮「系统配置」里填好 —— '
            + '<strong>保存后要重启后端</strong>才生效。</p>'
            + '<div class="gate-actions">'
            + '<button type="button" class="btn-primary" data-act="config">去系统配置</button>'
            + '<button type="button" data-act="recheck">重新检查</button>'
            + '</div>';
        el.querySelector('[data-act="config"]').onclick = () => navigate('settings');
        el.querySelector('[data-act="recheck"]').onclick = reloadGaps;
        document.body.appendChild(el);
        document.getElementById('page-body').inert = true;
    }

    /** 「重新检查」：配置填完、后端重启过后，回这一页点一下就行，不必按 F5 */
    async function reloadGaps() {
        try {
            PageGaps = await Api.get('/api/settings/missing-paths');
        } catch {
            PageGaps = null;
        }
        route();
    }

    /** 带筛选参数跳到另一页。统计页点「已归档/待打分」数字用它 */
    function navigate(id, params) {
        let hash = '#' + id;
        if (params) {
            const parts = Object.entries(params)
                .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v));
            if (parts.length) {
                hash += '?' + parts.join('&');
            }
        }
        location.hash = hash;
    }

    // ---------- 环境自检 ----------
    async function refreshEnvDot() {
        const dot = document.getElementById('env-dot');
        try {
            const report = await Api.get('/api/env/check');
            dot.className = 'dot ' + (report.errors > 0 ? 'dot-err'
                : report.warns > 0 ? 'dot-warn' : 'dot-ok');
            return report;
        } catch {
            dot.className = 'dot dot-err';
            return null;
        }
    }

    // ---------- 任务徽章 ----------
    /** 左下角「任务记录」项上的未完成（排队 + 进行中）数量，10 秒刷一次 */
    async function refreshTaskBadge() {
        const el = document.querySelector('.nav-count[data-count="tasks"]');
        if (!el) {
            return;
        }
        try {
            const stats = await Api.get('/api/tasks/summary');
            const total = stats.reduce((sum, s) => sum + (s.pending || 0) + (s.running || 0), 0);
            el.textContent = total;
            el.hidden = total === 0;
        } catch {
            // 后端没起 / 接口失败：藏起来，别在导航上留个坏数字
            el.hidden = true;
        }
    }

    // ---------- 版本号 ----------
    /**
     * 品牌区那个版本号（`呜啊娱乐工坊 v1.0.1`）。真源是 `pom.xml` 的 `<version>`，
     * 后端经 build-info 下发（见 VersionController）—— **前端不写死第二份**，
     * 否则每次发版都要记得改这里。
     *
     * <p>与自检红点、任务徽章同一个取舍：取不到就静默留空，不 toast、不挡启动
     * （后端没起的时候红点已经在报错了，这里再弹一次只是噪音）。
     */
    async function refreshVersion() {
        const el = document.getElementById('app-version');
        if (!el) {
            return;
        }
        try {
            const info = await Api.get('/api/version');
            el.textContent = info && info.version ? ' v' + info.version : '';
        } catch {
            el.textContent = '';
        }
    }

    async function openEnvPanel() {
        const panel = document.getElementById('env-panel');
        const body = document.getElementById('env-body');
        panel.hidden = false;
        body.innerHTML = UI.spinner('检查中…');
        try {
            const report = await Api.get('/api/env/check');
            let html = '<p class="muted small">' + report.items.length + ' 项检查，'
                + report.errors + ' 个错误、' + report.warns + ' 个告警。</p>';
            for (const item of report.items) {
                const cls = item.level === 'ERROR' ? 'dot-err'
                    : item.level === 'WARN' ? 'dot-warn' : 'dot-ok';
                html += '<div class="check">'
                    + '<div class="check-name"><span class="dot ' + cls + '"></span>'
                    + '<strong>' + UI.esc(item.name) + '</strong></div>'
                    + '<div class="small muted mono">' + UI.esc(item.detail) + '</div>'
                    + (item.hint ? '<div class="small">↳ ' + UI.esc(item.hint) + '</div>' : '')
                    + '</div>';
            }
            body.innerHTML = html;
            await refreshEnvDot();
        } catch (e) {
            body.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
        }
    }

    // ---------- 启动 ----------
    /** 先问模块开关再画导航：关掉的模块连入口都不该出现（路由同理） */
    async function boot() {
        try {
            ModuleFlags = await Api.get('/api/modules');
        } catch {
            ModuleFlags = null;
        }
        try {
            // 与模块开关并排拉一次：遮罩要在第一次 route 之前就备好，
            // 否则首屏是「先画出来、再被盖住」
            PageGaps = await Api.get('/api/settings/missing-paths');
        } catch {
            PageGaps = null;
        }
        renderNav();
        route();
        // 徽章挂在导航项上，得等 renderNav 画完再找它
        refreshTaskBadge();
        // 版本号填的是品牌区那个静态 span，与 renderNav 无关，不 await
        refreshVersion();
    }

    boot();
    window.addEventListener('hashchange', route);
    document.getElementById('env-btn').onclick = openEnvPanel;
    document.getElementById('env-close').onclick =
        () => document.getElementById('env-panel').hidden = true;
    document.getElementById('config-btn').onclick = () => {
        // 走 hash 路由而不是另画浮层：标题栏、说明、前进后退全都跟着一起对
        location.hash = '#settings';
        document.getElementById('env-panel').hidden = true;
    };
    document.getElementById('guide-btn').onclick = () => {
        // 同齿轮那条路。说明页没有 needs，route() 里的 applyGate 在它身上什么也不做
        location.hash = '#guide';
        document.getElementById('env-panel').hidden = true;
    };
    refreshEnvDot();
    setInterval(refreshTaskBadge, 10000);

    // 统计页跳转用（原曲页/作者页 → 歌曲页/未归档页，带筛选参数）。
    // gateMisses 一并挂出来：说明页要逐节说明「这一页还缺哪个根」，读的就是这一份
    // （PageGaps 上面已经取回来了，它再调一次不新增请求）—— 两处各写一遍判据必然漂移
    window.App = {navigate, gateMisses};
})();
