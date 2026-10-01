/**
 * 使用说明页（#guide）的渲染。**这里一个字文案都没有** —— 正文是一章一个 md 文件
 * （static/guide/<章 id>.md，文件名是约定，见 guide-data.js 文件头），固定文案在
 * guide-data.js，**节的结构从 md 里推**（章还是从 modules.js 推 —— 章＝模块）。
 * 这一层只回答「怎么画」。
 *
 * <b>正文为什么走 md</b>：文案原先以 JS 字面量存着，改一句话要动代码、还要分辨哪段是正文
 * 哪段是结构字段。换成 md 文件之后，写说明就是写文档；渲染交给 md.js（零依赖，
 * 默认转义 HTML）。于是这一层只剩三件事：拉文件、算章节、画。
 *
 * <b>结构以 md 为准</b>：一份 md ＝一章，章里按 `## <标题>` 分块，**一章里的每一块就是
 * 一节**，顺序也照 md。左侧目录遍历的就是这份切出来的节 —— 「目录里有哪些条目、按什么
 * 顺序、叫什么」三件事全部由 md 说了算（2026-09-30 用户定：原先目录来自注册表、块与节
 * **按位置**配对，于是作者在 md 中间插一节就整体错位一格，标题写着甲而正文是乙）。
 *
 * <b>块怎么拿到 id 与按钮</b> ＝ **按标题对名**：块的标题与本章某个页面的标题**逐字相同**时，
 * 用那一页的 id（锚点 / 目录链接 /「打开这一页」的目标）、定位句与按钮；对不上任何页面的块
 * 照画成一节（有目录项、有锚点），但**没有定位句也没有按钮**，id 现场合成 `<章 id>-b<序号>`
 * （序号从 1 数，按它在本章里的位置）。**代价**：在 md 里改一个页面的标题，那一页就失去锚点
 * 与按钮 —— 由 `tools/guide-check.js` 第 1 组**判红点名**拦住，绝不静默。反向也判红：
 * md 里没写那一块＝那一页在说明页上不出现。**注册表只管「有哪些页面」**（id / 定位句 / needs），
 * 不再是目录的来源。
 *
 * <b>为什么还是不许写一级标题</b>：章的标题与左侧导航同名，那一份在 modules.js（md 里
 * `# 章名` 会变成第二个权威）。页内的小节用 `###`。
 *
 * <b>模块三态</b>（见 guide-data.js 的 ui.moduleOff）：只有 `GET /api/settings` 的
 * `modules` 数组能分清「关着」与「被裁」—— `/api/modules` 两种都返回 false：
 * <ul>
 *   <li>数组里有该 id 且 toggle.value === 'true' → 整章照常；</li>
 *   <li>数组里有该 id 且 toggle.value === 'false' → 整章照常，章首挂一条提醒 +「去配置」；</li>
 *   <li>数组里<b>没有</b>该 id（本次构建没有这个模块）→ 整章不画，连目录项一起。</li>
 * </ul>
 * 读不到（后端没起来）时**不猜**：按「都在」画，页顶一条 INFO 说明为什么没提醒。
 *
 * <b>本页不写 needs</b>（modules.js 里那一段有理由）：它不扫任何磁盘根，而且遮上就是死锁 ——
 * 用户正是要看着这一页去补配置。
 */
const GuidePage = (() => {

    /**
     * 每次 render 自增。`Api.get` / `Util.text` 回来时若已经翻到别的页就整段放弃 ——
     * `#page-body` 自始至终是同一个元素、app.js 只是把它清空，接着往里画
     * 就是画到别人页面上（本页是异步渲染，别的页多半是同步的，只有这里会撞上）。
     */
    let renderToken = 0;

    // ---------- 从注册表推章节（不另写「页面 → 模块」映射表） ----------

    /**
     * 每个模块的三态。见文件头。返回 null = 读不到 —— 调用方按「都在」画。
     */
    function statesOf(settings) {
        if (!settings || !Array.isArray(settings.modules)) {
            return null;
        }
        const map = {};
        for (const m of settings.modules) {
            map[m.id] = (m.toggle && m.toggle.value === 'true') ? 'on' : 'off';
        }
        return map;
    }

    /**
     * 章 = 模块（通用章在前、通用页面章在最后）。每章带一份**候选页面** `pages`
     * ——「这一章里有哪些页面」的唯一来源，节则由 md 的块派生（见 {@link sectionsOf}）。
     * 正文文件名一律按约定推（`<章 id>.md`，章 id 就是下面这些 id），所以没有第二张映射表。
     * `hidden: true` 的页面（配置 / 本页）**照样进 pages** —— hidden 只管左侧导航，
     * 而这一页要讲的是全部可路由页面。于是「每个页面都讲到」这条守门规则不需要豁免清单。
     *
     * <p>每个候选页面：`{id, title, desc, open, page}`。`page` 是注册表那一项本身
     * （`needs` 在里面，取缺失根时要用）；`open` = 这一页此刻能不能打开（模块关着就不给
     * 按钮：点进去只会看到「所在的模块已关闭」，不如不给）。intro 那两节不属任何页面，
     * 所以 `page` 是 null、`open` 恒 false。
     */
    function chapters(states) {
        const out = [];

        out.push({
            id: 'intro',
            title: GuideData.ui.introChapter.title,
            desc: GuideData.ui.introChapter.desc,
            file: 'intro.md',
            off: false,
            pages: GuideData.intro.map(s => ({
                id: 'intro-' + s.id,
                title: s.title,
                desc: '',
                open: false,
                page: null
            }))
        });

        for (const mod of Modules) {
            if (!mod.children) {
                continue;
            }
            // 数组里没有这个 id = 本次构建里没有它（不是「关着」）—— 整章不画
            if (states && !(mod.id in states)) {
                continue;
            }
            const off = !!states && states[mod.id] === 'off';
            out.push({
                id: mod.id,
                title: mod.title,
                desc: '',
                file: mod.id + '.md',
                off: off,
                pages: mod.children.map(child => ({
                    id: child.id,
                    title: child.title,
                    desc: child.desc || '',
                    open: !off,
                    page: child
                }))
            });
        }

        // 不属任何模块的顶层页（文件记录 / 任务记录 / 系统配置 / 本页），注册顺序照旧
        const plain = Modules.filter(m => !m.children && m.render);
        if (plain.length) {
            out.push({
                id: 'standalone',
                title: GuideData.ui.standaloneChapter.title,
                desc: GuideData.ui.standaloneChapter.desc,
                file: 'standalone.md',
                off: false,
                pages: plain.map(m => ({
                    id: m.id,
                    title: m.title,
                    desc: m.desc || '',
                    open: true,
                    page: m
                }))
            });
        }
        return out;
    }

    // ---------- 正文 ----------

    /**
     * 把一份章的 md 切成「章首导言 + 若干块」。块的边界是行首的 `## `，
     * 代码围栏里的 `##` 不算（那是正文举例）。
     *
     * <p><b>按 `\r?\n` 切</b>，不是按 `\n`：JS 里 `.` 与 `$` **都不认 `\r`**，
     * 于是 CRLF 的 md 上 `/^##\s+(.*)$/` 一个都匹配不上 —— 症状是那一章**一块都切不出来**
     * （节全没了，只剩章首那段导言，页面上不报错）。编辑器换行符一改就中招，
     * 所以在这里挡掉（2026-09-30 在守门的 blocksOf 上真踩到过一次）。
     *
     * @return {lead, blocks:[{title, body}]}；正文本身没读出来时返回 null
     */
    function splitChapter(text) {
        if (text == null) {
            return null;
        }
        const lead = [];
        const blocks = [];
        let cur = null;
        let fence = false;
        for (const line of text.split(/\r?\n/)) {
            if (/^\s*```/.test(line)) {
                fence = !fence;
            }
            const head = !fence && /^##\s+(.*)$/.exec(line);
            if (head) {
                cur = {title: head[1].trim(), lines: []};
                blocks.push(cur);
            } else if (cur) {
                cur.lines.push(line);
            } else {
                lead.push(line);
            }
        }
        return {
            lead: lead.join('\n').trim(),
            blocks: blocks.map(b => ({title: b.title, body: b.lines.join('\n').trim()}))
        };
    }

    /**
     * 一节 ＝ md 里的一个块，**顺序完全跟 md**。块与页面**按标题对名**（逐字相同，只在本章
     * 的候选页面里找）：
     * <ul>
     *   <li>对得上 → 拿那一页的 id（锚点 / 目录链接 / 按钮的目标）、定位句与按钮；</li>
     *   <li>对不上 → 照画成一节（有目录项、有锚点），但没有定位句、没有按钮，
     *       id 现场合成 `<章 id>-b<序号>`（序号从 1 数，按它在本章里的位置）。</li>
     * </ul>
     * 没写进 md 的页面在这一章里**不出现**（「每个页面都讲到」由 `tools/guide-check.js`
     * 第 1 组判红 —— 少讲一页是静默的，只有它看得见）。
     *
     * <p>整章的 md 读不出来（`parts` 为 null）时退回 {@link chapters} 那份候选页面：
     * 标题取注册表、正文空，章首另挂一条 `ui.loadFailed` —— 节一个不少，别让整章凭空消失。
     *
     * <p>`body` 为 null / 空串的那一块由 sectionHtml 画成「还没有内容」。
     *
     * @return [{id, title, desc, open, page, body}]
     */
    function sectionsOf(ch, parts) {
        if (!parts) {
            return ch.pages.map(p => ({
                id: p.id, title: p.title, desc: p.desc, open: p.open, page: p.page, body: null
            }));
        }
        return parts.blocks.map((block, i) => {
            const page = ch.pages.find(p => p.title === block.title);
            return {
                id: page ? page.id : ch.id + '-b' + (i + 1),
                title: block.title,
                desc: page ? page.desc : '',
                open: !!page && page.open,
                page: page ? page.page : null,
                body: block.body
            };
        });
    }

    /**
     * 这一页还缺哪个磁盘根 —— 复用 app.js 算好的那一份（`App.gateMisses`，读的是
     * `/api/settings/missing-paths` 的下发结果，**不新增请求**）。逐项的「叫什么、在哪一组、
     * 为什么算缺」三者**原样透传后端给的那一份**，与那张遮罩说的永远是同一句话。
     *
     * <p>为什么要在这儿再说一遍：这一页正是让人「看着说明去补配置」的地方，缺根时页面
     * 会被遮罩盖住（用户此刻看不见按钮在哪），所以得先把「待会儿会被挡在门外」讲清楚。
     * <p>拿不到（老页面 / 接口挂了）就**什么都不说** —— 不瞎报。
     */
    function gateHtml(sec) {
        if (!sec.page) {
            return '';
        }
        const app = window.App || {};
        if (typeof app.gateMisses !== 'function') {
            return '';
        }
        const missed = app.gateMisses(sec.page) || [];
        if (!missed.length) {
            return '';
        }
        let items = '';
        for (const g of missed) {
            items += '<li>' + UI.esc(g.label) + ' —— ' + UI.esc(GuideData.ui.gateWhere)
                + UI.esc(g.group) + '：' + UI.esc(g.reason) + '</li>';
        }
        return '<div class="hint guide-gate">' + UI.esc(GuideData.ui.gateMiss)
            + '<ul>' + items + '</ul></div>';
    }

    /**
     * 一节。标题与定位句都来自 {@link sectionsOf}；正文由 md.js 画，块与块之间不共享状态。
     *
     * @param chapterMissing 整章的 md 都没读出来 —— 章首已经挂了一条，这里不逐节再喊一遍
     */
    function sectionHtml(sec, chapterMissing) {
        let out = '<section class="guide-page" data-sec="' + UI.esc(sec.id) + '">'
            + '<h3 class="guide-page-title">' + UI.esc(sec.title);
        if (sec.desc) {
            out += ' <span class="guide-page-desc">' + UI.esc(sec.desc) + '</span>';
        }
        out += '</h3>';

        out += gateHtml(sec);

        if (chapterMissing) {
            // 章首那条「没读出来」已经说过了
        } else if (!sec.body) {
            // 块里是空的：画出来，别留一节空壳
            out += '<div class="hint">' + UI.esc(GuideData.ui.noContent) + '</div>';
        } else {
            out += '<div class="md-body">' + Md.render(sec.body) + '</div>';
        }

        if (sec.open) {
            out += '<div class="row" style="margin-top:10px">'
                + '<button type="button" class="btn-plain" data-act="page"'
                + ' data-page="' + UI.esc(sec.id) + '">' + UI.esc(GuideData.ui.openPage)
                + '</button></div>';
        }
        return out + '</section>';
    }

    function chapterHtml(ch, parts) {
        let out = '<section class="guide-chapter" data-chap="' + UI.esc(ch.id) + '">'
            + '<div class="guide-chapter-head"><h2>' + UI.esc(ch.title) + '</h2>';
        if (ch.desc) {
            out += '<p class="muted small" style="margin:4px 0 0">' + UI.esc(ch.desc) + '</p>';
        }
        out += '</div>';

        if (ch.off) {
            // 关着：说明照常给（用户正需要看它），只在章首挂一条 —— 措辞与
            // settings.js 的 set-mod-off / app.js 路由里那句同一套
            out += '<div class="hint guide-mod-off">' + GuideData.ui.moduleOff
                + '<div class="row"><button type="button" class="btn-plain" data-act="config">'
                + UI.esc(GuideData.ui.goConfig) + '</button></div></div>';
        }

        if (!parts) {
            // 整章的 md 没读出来（缺文件 / 后端没起）：章还是画、节由 sectionsOf 退回注册表
            // 那一份，章首说一句「说明哪去了」
            out += '<div class="hint">' + UI.esc(GuideData.ui.loadFailed) + '</div>';
        } else if (parts.lead) {
            out += '<div class="md-body">' + Md.render(parts.lead) + '</div>';
        }

        for (const sec of ch.sections) {
            out += sectionHtml(sec, !parts);
        }
        return out + '</section>';
    }

    /**
     * 图还没截时画占位框：md 里的图片渲染成 `<img>` + 一句说明（见 md.js 的 image），
     * 这里盯住加载结果，给外面那个 span 加 .is-loaded / .is-missing —— CSS 据此决定
     * 显示图还是显示虚线占位框。**等哪天真把图放进 static/img/guide/，一个字都不用改。**
     *
     * 那段说明就是 md 里 `![...]` 里写的那句话，占位框上再补一行「图还没截」——
     * 不补的话，一个虚框加一句话看着像正文，而不像「这里本该有张图」。
     */
    function wireImages(root) {
        for (const wrap of root.querySelectorAll('.md-imgwrap')) {
            const img = wrap.querySelector('img');
            if (!img) {
                continue;
            }
            const missed = () => {
                wrap.classList.add('is-missing');
                wrap.classList.remove('is-loaded');
                const ph = wrap.querySelector('.md-imgph');
                if (ph && !ph.querySelector('.md-imgph-tag')) {
                    ph.insertAdjacentHTML('afterbegin',
                        '<span class="md-imgph-tag">' + UI.esc(GuideData.ui.imgPending) + '</span>');
                }
            };
            const loaded = () => {
                wrap.classList.add('is-loaded');
                wrap.classList.remove('is-missing');
            };
            // 已经在缓存里的图不会再发 load / error，自己判一次（含「早就失败过」那种）
            if (img.complete) {
                if (img.naturalWidth) {
                    loaded();
                } else {
                    missed();
                }
                continue;
            }
            img.addEventListener('error', missed);
            img.addEventListener('load', loaded);
        }
    }

    // ---------- 目录 ----------

    /**
     * 左侧目录。**条目就是 `ch.sections`** —— 也就是 md 里切出来的那些块，所以「目录里
     * 有哪些条目、按什么顺序、叫什么」全部由 md 说了算（作者要的就是这一条）。
     * `data-sec` 是给滚动联动用的（见 {@link wireSpy}）。
     */
    function tocHtml(list, current) {
        let html = '';
        for (const ch of list) {
            html += '<div class="toc-chap">' + UI.esc(ch.title) + '</div>';
            for (const sec of ch.sections) {
                const on = sec.id === current ? ' class="toc-sub on"' : ' class="toc-sub"';
                // 走现成的 hash 路由（与统计页「已归档/待打分」带参数跳转同一套约定），
                // 不自己 scrollIntoView —— 这样前进后退、刷新都对得上
                html += '<a' + on + ' href="#guide?sec=' + encodeURIComponent(sec.id) + '"'
                    + ' data-sec="' + UI.esc(sec.id) + '">' + UI.esc(sec.title) + '</a>';
            }
        }
        return '<nav class="guide-toc">' + html + '</nav>';
    }

    /** 上一轮那个滚动观察器。同一时刻只该有一个（每次 render 先 disconnect 掉旧的） */
    let spy = null;

    /**
     * 目录滚动联动：滚到哪一节，左边那一行就高亮。
     *
     * <p><b>两条硬约束</b>，两条都是被守门第 10 组那套最小假 DOM 逼出来的（那儿既没有
     * `document` 也没有 `IntersectionObserver`，多写一句就把它跑崩）：
     * <ol>
     *   <li>元素**只从 `wrap` 上取**，不许碰 `document`；</li>
     *   <li>`IntersectionObserver` 先问 `typeof`（拿不到就只剩「按 `?sec=` 高亮」这一条，
     *       老浏览器不报错，页面照样能看）。</li>
     * </ol>
     */
    function wireSpy(wrap) {
        if (spy) {
            spy.disconnect();
            spy = null;
        }
        if (typeof IntersectionObserver !== 'function') {
            return;
        }
        const mark = id => {
            for (const a of wrap.querySelectorAll('.guide-toc a')) {
                a.classList.toggle('on', a.dataset.sec === id);
            }
        };
        const obs = new IntersectionObserver(entries => {
            for (const e of entries) {
                // 判「正在看的」是最后一条进来的 —— 交错的那几节里它最靠下
                if (e.isIntersecting) {
                    mark(e.target.dataset.sec);
                }
            }
        }, {rootMargin: '0px 0px -70% 0px'});
        for (const sec of wrap.querySelectorAll('.guide-page')) {
            obs.observe(sec);
        }
        spy = obs;
    }

    // ---------- 渲染 ----------

    async function render(host, params) {
        const token = ++renderToken;

        let settings = null;
        try {
            settings = await Api.get('/api/settings');
        } catch {
            settings = null;
        }
        if (token !== renderToken) {
            return;
        }
        const states = statesOf(settings);
        const list = chapters(states);

        // 本页不做懒加载（项目里没有先例）：一次把全部 md 拉回来，读不到的记 null
        const files = list.map(ch => ch.file);
        const texts = await Promise.all(
            files.map(f => Util.text(GuideData.dir + f).catch(() => null)));
        if (token !== renderToken) {
            return;
        }

        // 切块 → 派生节。**顺序、标题、条目全部以 md 为准**，注册表那一份只在 md 读不到时兜底
        const parts = {};
        list.forEach((ch, i) => {
            const p = splitChapter(texts[i]);
            parts[ch.id] = p;
            ch.sections = sectionsOf(ch, p);
        });

        const sec = params && /^[a-z0-9-]+$/.test(params.sec || '') ? params.sec : '';

        let body = '';
        for (const ch of list) {
            body += chapterHtml(ch, parts[ch.id]);
        }

        let preface = '';
        if (!states) {
            // 后端没起来（或接口挂了）：章全画，但说清楚为什么一条提醒都没有
            preface = '<div class="hint hint-info">' + UI.esc(GuideData.ui.settingsFailed)
                + '</div>';
        }

        host.innerHTML = '<div class="guide-wrap">'
            + tocHtml(list, sec)
            + '<div class="guide-body">' + preface + body + '</div>'
            + '</div>';

        const wrap = host.firstElementChild;
        wireImages(wrap);
        wireSpy(wrap);

        // 「去配置」/「打开这一页」：说明页只讲不替它做，给入口就够了。
        // 委派在 wrap 上，将来正文里多出别的入口不用另绑一次
        wrap.addEventListener('click', e => {
            const btn = e.target.closest('[data-act]');
            if (!btn) {
                return;
            }
            if (btn.dataset.act === 'config') {
                window.App.navigate('settings');
            } else if (btn.dataset.act === 'page') {
                window.App.navigate(btn.dataset.page);
            }
        });

        if (sec) {
            // 滚到那一节。app.js 渲染完就把 hash 换回 #guide（replaceState 不触发滚动），
            // 所以这里必须自己滚一次。
            //
            // **必须在正文里找**：左边的目录项也带 `data-sec`（{@link wireSpy} 要按它配对），
            // 而目录在 wrap 里排在正文**前面** —— 光写 `[data-sec="…"]` 命中的是那条
            // **目录链接**，`scrollIntoView` 就把那一个小小的高亮条目滚进视野（等于原地
            // 挪几百像素），正文纹丝不动。2026-09-30 加的滚动联动把它带进来的：在这之前
            // 只有正文那一份带 `data-sec`，选择器还能碰巧命中。症状是「点目录不跳」，
            // 而**目录高亮、hash、参数全都正常** —— 守门第 10 组因此改成真判「滚的是谁」。
            const el = wrap.querySelector('.guide-body [data-sec="' + sec + '"]');
            if (el) {
                el.scrollIntoView({block: 'start'});
            }
        }
    }

    return {render};
})();
