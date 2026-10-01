/**
 * 配置页：本机路径与常用开关。
 *
 * 入口是左下角「系统工具」那一栏里的齿轮按钮（见 index.html / app.js），没有左侧导航项 ——
 * 它不是业务模块，是「换台机器要改的那几行」的落脚处。
 *
 * 这一页**只画不判**：标签、说明、类型、范围、当前值、哪几项改过、哪几项要重启，
 * 全是后端 `/api/settings` 下发的。前端只做三件本地的事：把值画成合适的控件、
 * 收集改动、把预演结果摆给人看。
 *
 * 「浏览…」与「保存并重启后端」只在桌面壳里出现 —— 判据是 `window.NyaEntworksShell` 存不存在，
 * 那是能力探测（壳注入的 bridge），不是业务判定。纯浏览器打开时就退回手输路径 + 手工重启。
 */
const SettingsPage = (() => {

    /** 上一次加载到的 view，供「当前值」比对与重新渲染 */
    let current = null;

    /** 点了「恢复出厂」但还没保存的键：保存时提交 null，后端把这一项从文件里删掉 */
    const pendingRemovals = new Set();

    /** 壳里的能力。壳没起 preload 时这里是 undefined，两个按钮就不画 */
    const shell = () => window.NyaEntworksShell;

    // ------------------------------------------------------------------
    // 画
    // ------------------------------------------------------------------

    async function render(host) {
        host.innerHTML = UI.spinner('读取配置…');
        await load(host);
    }

    async function load(host) {
        try {
            current = await Api.get('/api/settings');
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        host.innerHTML = noticesCard() + headCard() + body();
        bind(host);
    }

    /**
     * 顶部提示条：现在缺了什么、因此哪些功能是关着的。
     *
     * <p>画在配置文件那张卡片<b>之前</b> —— 它说的是「这台机器上什么用不了」，
     * 比「文件在哪」要紧。没有提示时整段不出现。
     *
     * <p>内容全是后端算好的（{@code SettingsNotices}），前端只画：判定要与实际行为
     * 用同一个口径，拿到前端来判就会长出第二套说法。
     *
     * <p>样式复用页面上既有的 {@code .hint} 系（黄边=提醒但能继续，灰边=只是说一声），
     * 不另造一套 —— 这一页里「缺了什么」的条子已经够多了。
     */
    function noticesCard() {
        const list = current.notices || [];
        if (!list.length) {
            return '';
        }
        return list.map(n => `
        <div class="hint${n.level === 'INFO' ? ' hint-info' : ''}">
            <div><b>${UI.esc(n.title)}</b></div>
            <div>${UI.esc(n.text)}</div>
        </div>`).join('');
    }

    /**
     * 页面主体：三个模块各一段（大标题 + 总开关 + 自己的卡片），通用组垫在最后。
     * 顺序由后端的 modules 数组决定 —— 前端不自己排。
     */
    function body() {
        let out = current.modules.map(moduleSection).join('');
        const generic = current.groups.filter(g => !g.module);
        if (generic.length) {
            out += '<div class="set-generic-head">通用</div>' + generic.map(groupCard).join('');
        }
        return out + secretsCard();
    }

    /**
     * 底部的密钥卡：**只看不改**。
     *
     * <p>这几项密钥在另一个文件里（`config/application-secret.yaml`），不属于这一页写的那份覆盖层，
     * 所以这里既没有输入框也没有 `data-key` —— 收进来就会变成「页面上改了、重启后没生效」
     * 那种最隐蔽的坑（后端 `SettingsCatalogTest#specs_onlyTheEhCookieIsEditable` 也拦着）。
     *
     * <p>**e-hentai 的 cookie 已经不在这张卡上**（2026-09-25 口径 a）：它必须与站点根成对换，
     * 所以搬去上面「e-hentai 扫描」的一组里当普通设置项了。这里剩下的三个仍是只读。
     *
     * <p>但「配没配」得看得见：以前这些 cookie 在界面上一点痕迹都没有，出了
     * 「VIP 直链渠道不见了」只能挨个翻文件猜。所以这里只画四列
     * 只读文字 —— 用途 / 配置键（告诉人去文件的哪一行改）/ 配没配 / 没配会少什么。
     *
     * <p>判定与文案全来自后端（`SettingsCatalog#secretStatus`），前端只画。
     */
    function secretsCard() {
        const list = current.secrets || [];
        if (!list.length) {
            return '';
        }
        const rows = list.map(s => `
            <tr>
                <td>${UI.esc(s.label)}</td>
                <td class="mono small">${UI.esc(s.key)}</td>
                <td>${s.configured
                    ? '<span class="set-secret-on">已配置</span>'
                    : '<span class="muted">没配</span>'}</td>
                <td class="small muted">${UI.esc(s.where)}</td>
            </tr>`).join('');
        return `
        <div class="set-generic-head">密钥</div>
        <div class="card">
            <div class="muted small">
                这几项含密钥，<b>不在本页改</b> —— 它们放在
                <span class="mono">${UI.esc(current.secretFilePath || 'config/application-secret.yaml')}</span>
                （不进仓库）。这里只显示配没配；要改就直接编辑那个文件，改完重启后端才生效。
                e-hentai 的 cookie 不在这张表里 —— 它要与站点根成对换，在上面「e-hentai 扫描」一组里直接改。
            </div>
            <table>
                <thead><tr><th>用途</th><th>配置键</th><th>状态</th><th>没配会怎样</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
        </div>`;
    }

    /**
     * 一个模块：大标题那一行（标题 + 总开关 + 说明），底下是它的卡片。
     *
     * <p>关掉时卡片整段换成一句「为什么这里是空的」—— **大标题与开关本身始终在**，
     * 否则关掉之后就再也打不开了（这是这一页唯一的自救入口）。
     */
    function moduleSection(m) {
        const on = m.toggle.value === 'true';
        return `
        <div class="set-mod-head">
            <h2>${UI.esc(m.title)}</h2>
            ${toggleBtn(m.toggle, false)}
            <span class="small muted">${UI.esc(m.desc)}</span>
        </div>
        <div data-module="${UI.esc(m.id)}"${on ? '' : ' hidden'}>
            ${current.groups.filter(g => g.module === m.id).map(groupCard).join('')}
        </div>
        <div class="set-mod-off" data-module-off="${UI.esc(m.id)}"${on ? ' hidden' : ''}>
            这个模块是关的 —— 它的页面入口不出现在左侧导航里，接口也不响应。
            打开上面的开关、保存并重启后端之后它才会回来。
        </div>`;
    }

    function headCard() {
        const inShell = !!shell();
        return `
        <div class="card">
            <div class="row" style="justify-content:space-between">
                <h2 style="margin:0">配置文件</h2>
                <div class="row">
                    <button type="button" class="btn-plain" id="set-reload">重新读取</button>
                    <button type="button" class="btn-primary" id="set-save">保存</button>
                    ${inShell
                        ? '<button type="button" class="btn-primary" id="set-save-restart">保存并重启后端</button>'
                        : ''}
                </div>
            </div>
            <div class="small muted mono">${UI.esc(current.filePath)}
                ${current.fileExists ? '（已创建）' : '（尚未创建，现在用的是 application.yaml 里的值）'}</div>
            ${current.backupExists
                ? '<div class="small muted">上一次保存前的备份：<span class="mono">'
                    + UI.esc(current.backupPath) + '</span></div>' : ''}
            <div class="small muted">这个文件是<b>覆盖层</b>：写了的键压过 application.yaml 的同名键，
                没写的键原地不动。所以「恢复出厂」＝把那一项从文件里删掉。
                密钥里只有 e-hentai 的 cookie 在这一页（它要与站点根成对换），
                网易云 cookie 与在线 AI 的 API key 仍在 application-secret.yaml。</div>
            ${current.pendingCount
                ? '<div class="hint">有 ' + current.pendingCount
                    + ' 项改动还没生效 —— 这些值在后端<b>启动时</b>就绑定好了，'
                    + (inShell ? '点「保存并重启后端」' : '重启后端（或双击 desktop\\启动.cmd）')
                    + '之后才会用上。</div>'
                : ''}
            ${inShell ? '' : '<div class="hint">当前不在桌面壳里：路径要手输（或粘贴），'
                + '改完请手工重启后端。双击 desktop\\启动.cmd 打开就能用原生「浏览…」对话框。</div>'}
        </div>`;
    }

    function groupCard(group) {
        return `
        <div class="card">
            <h2>${UI.esc(group.title)}</h2>
            <table class="set-table">
                <tbody>${group.fields.map(fieldRow).join('')}</tbody>
            </table>
        </div>`;
    }

    /**
     * 一行的徽章。抽出来是因为「恢复出厂 / 取消恢复」要**就地**换掉它
     * —— 见 {@link toggleRemoval}。两张状态表必须是同一份，否则点了按钮
     * 徽章跟不上去（症状：行压暗了、徽章还写着「待重启」）。
     */
    function badgesHtml(f, removal) {
        const badges = [];
        if (removal) {
            badges.push('<span class="set-badge set-badge-warn">保存后恢复出厂</span>');
        } else {
            if (f.overridden) {
                badges.push('<span class="set-badge">已改</span>');
            }
            if (f.pending) {
                badges.push('<span class="set-badge set-badge-warn">待重启</span>');
            }
            if (f.restartRequired) {
                badges.push('<span class="set-badge set-badge-quiet">改后需重启</span>');
            }
        }
        return badges.join('');
    }

    function fieldRow(f) {
        const removal = pendingRemovals.has(f.key);
        const canBrowse = !!shell() && (f.type === 'PATH_DIR' || f.type === 'PATH_FILE');
        return `
        <tr data-row="${UI.esc(f.key)}" class="${removal ? 'set-removed' : ''}">
            <td class="set-label">
                <div>${UI.esc(f.label)}</div>
                <div class="small muted mono">${UI.esc(f.key)}</div>
            </td>
            <td class="set-value">
                <div class="set-ctl">${control(f, removal)}${badgesHtml(f, removal)}</div>
            </td>
            <td class="set-act">
                ${canBrowse
                    ? '<button type="button" class="btn-plain" data-browse="' + UI.esc(f.key) + '"'
                        + (removal ? ' hidden' : '') + '>浏览…</button>'
                    : ''}
                ${f.overridden
                    ? '<button type="button" class="btn-plain" data-restore="' + UI.esc(f.key) + '">'
                        + (removal ? '取消恢复' : '恢复出厂') + '</button>'
                    : ''}
            </td>
        </tr>
        <tr data-desc="${UI.esc(f.key)}" class="${removal ? 'set-removed' : ''}">
            <td></td>
            <td colspan="2" class="small muted">
                ${UI.esc(f.desc)}
                ${f.onMissing ? '<br>缺了会怎样：' + UI.esc(f.onMissing) : ''}
                ${f.relativeAllowed ? '<br>可以填相对项目根的路径。' : ''}
            </td>
        </tr>`;
    }

    /** 按后端下发的类型画控件 —— 类型是数据，前端不自己猜 */
    function control(f, disabled) {
        const key = UI.esc(f.key);
        const attr = disabled ? ' disabled' : '';
        if (f.type === 'BOOL') {
            return toggleBtn(f, disabled);
        }
        if (f.options && f.options.length) {
            return '<select data-key="' + key + '"' + attr + '>'
                + f.options.map(o => '<option value="' + UI.esc(o) + '"'
                    + (o === f.value ? ' selected' : '') + '>' + UI.esc(o) + '</option>').join('')
                + '</select>';
        }
        if (f.type === 'INT') {
            return '<input type="number" class="set-num" data-key="' + key + '" value="'
                + UI.esc(f.value) + '"'
                + (f.min !== null && f.min !== undefined ? ' min="' + f.min + '"' : '')
                + (f.max !== null && f.max !== undefined ? ' max="' + f.max + '"' : '')
                + attr + '>';
        }
        return '<input type="text" class="set-text" data-key="' + key + '" value="'
            + UI.esc(f.value) + '"' + attr + '>';
    }

    /**
     * 开关。仿 iOS 的 UISwitch（轨道 + 滑动的圆钮，见 app.css 的 `.set-toggle`），
     * 代替更早的「原生复选框」和「写着已开启/已关闭的实心块」。
     *
     * <p>钮上**不带文字**，所以这里不给 {@code textContent} 留位置：状态全在
     * {@code .on} 这个类和 {@code data-bool} 上。点的时候只改这两样 + aria，
     * 千万别用 {@code textContent}（会把圆钮 {@code <span>} 整个抹掉，开关变成个空胶囊）。
     * 读屏与鼠标悬停各有一份文字：{@code aria-label} / {@code title}。
     *
     * <p>值不放在 DOM 的 {@code value} 上（{@code <button>} 本来也没有），而是 {@code data-bool}：
     * {@link collect} 按它取值，取值前先看 {@code data-bool} 存不存在。
     */
    function toggleBtn(f, disabled) {
        const on = f.value === 'true';
        return '<button type="button" class="set-toggle' + (on ? ' on' : '') + '"'
            + ' data-key="' + UI.esc(f.key) + '"'
            + ' data-bool="' + (on ? 'true' : 'false') + '"'
            // data-label 只给点击时重写 aria-label 用 —— 那时手上只有这个 button，没有 f
            + ' data-label="' + UI.esc(f.label) + '"'
            + ' role="switch"'
            + ' aria-checked="' + on + '"'
            + ' aria-label="' + UI.esc(f.label) + '：' + (on ? '开' : '关') + '"'
            + ' title="' + (on ? '已开启，点击关闭' : '已关闭，点击开启') + '"'
            + (disabled ? ' disabled' : '')
            + '><span class="set-toggle-knob"></span></button>';
    }

    /** 按键找一项的元数据（组里的和模块开关都在）。找不到返回 undefined */
    function fieldOf(key) {
        const inGroup = current.groups.flatMap(g => g.fields).find(f => f.key === key);
        if (inGroup) {
            return inGroup;
        }
        const m = current.modules.find(m => m.toggle.key === key);
        return m ? m.toggle : undefined;
    }

    // ------------------------------------------------------------------
    // 收值
    // ------------------------------------------------------------------

    /**
     * 提交的「键 → 值」。被标成恢复出厂的项给 null，后端据此把这一项从文件里删掉。
     *
     * <p>读的是整个 DOM，**不看 hidden** —— 模块关掉时它的卡片是被藏起来的，
     * 但里面那些输入框的值仍然照常提交，所以「先关模块再保存」不会把它的路径抹掉。
     */
    function collect(host) {
        const values = {};
        host.querySelectorAll('[data-key]').forEach(el => {
            const key = el.dataset.key;
            values[key] = el.dataset.bool !== undefined ? el.dataset.bool : el.value;
        });
        for (const key of pendingRemovals) {
            values[key] = null;
        }
        return values;
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    function bind(host) {
        host.querySelector('#set-reload').onclick = () => {
            pendingRemovals.clear();
            render(host);
        };
        host.querySelector('#set-save').onclick = (e) => save(host, e.currentTarget);
        const restartBtn = host.querySelector('#set-save-restart');
        if (restartBtn) {
            restartBtn.onclick = () => saveAndRestart(host, restartBtn);
        }
        // 开关：就地切换 + 刷新显隐，**不重画整页** —— 重画会冲掉别的输入框里刚敲的内容
        host.querySelectorAll('.set-toggle').forEach(btn => {
            btn.onclick = () => {
                const on = btn.dataset.bool !== 'true';
                btn.dataset.bool = String(on);
                btn.classList.toggle('on', on);
                btn.setAttribute('aria-checked', String(on));
                // 只改这两样。**别碰 textContent** —— 圆钮是里面那个 <span>，抹掉就成空胶囊了
                btn.setAttribute('aria-label', btn.dataset.label + '：' + (on ? '开' : '关'));
                btn.title = on ? '已开启，点击关闭' : '已关闭，点击开启';
                refresh(host);
            };
        });
        host.querySelectorAll('[data-browse]').forEach(btn => {
            btn.onclick = () => browse(host, btn.dataset.browse);
        });
        host.querySelectorAll('[data-restore]').forEach(btn => {
            btn.onclick = () => toggleRemoval(host, btn.dataset.restore);
        });
    }

    /**
     * 「恢复出厂」/「取消恢复」：**只改这一行的样子**，不重画整页。
     *
     * <p>原先这一处是 `load(host)` 重画 + `scrollTo` 找回滚动位置（2026-09-25 前），
     * 两个毛病：① 整页闪一下（用户 2026-09-25 报「表现很奇怪」）；② 更要紧的是
     * `load()` 会拿后端数据重画整页，**把别的行里刚敲进去、还没保存的值全冲掉**
     * —— 在一行上点「恢复出厂」，另一行填了一半的路径就没了，而且静默。
     * 就地改这一行两样都不占：值一个不动，也不闪。
     *
     * <p>被标记的项在提交时给 null（见 {@link collect}），
     * 落盘发生在「保存」—— 徽章那句「保存后恢复出厂」就是这句话。
     */
    function toggleRemoval(host, key) {
        const f = fieldOf(key);
        if (!f) {
            return;
        }
        const on = !pendingRemovals.has(key);
        if (on) {
            pendingRemovals.add(key);
        } else {
            pendingRemovals.delete(key);
        }
        const row = host.querySelector('tr[data-row="' + key + '"]');
        const desc = host.querySelector('tr[data-desc="' + key + '"]');
        if (desc) {
            desc.classList.toggle('set-removed', on);
        }
        if (!row) {
            return;
        }
        row.classList.toggle('set-removed', on);
        const ctl = row.querySelector('.set-ctl');
        if (ctl) {
            const el = ctl.querySelector('[data-key]');
            if (el) {
                el.disabled = on;
            }
            ctl.querySelectorAll('.set-badge').forEach(b => b.remove());
            ctl.insertAdjacentHTML('beforeend', badgesHtml(f, on));
        }
        const browse = row.querySelector('[data-browse]');
        if (browse) {
            browse.hidden = on;
        }
        const restore = row.querySelector('[data-restore]');
        if (restore) {
            restore.textContent = on ? '取消恢复' : '恢复出厂';
        }
    }

    /**
     * 按各开关的**当前**状态刷新显隐，不重新加载。
     *
     * <p>刻意不重画整页：用户很可能刚在某个输入框里敲了半截路径，再去点开关 ——
     * 重画会把那半截冲掉。这里只改 `hidden`，输入框里的内容一个都不动。
     * 被藏起来的行里的值仍然照常提交（{@link collect} 读的是整个 DOM，不看 hidden）。
     *
     * <p>两处显隐都由后端下发决定，前端不自己判：模块级的是
     * {@code Field.moduleSwitch}，单项的是 {@code Field.dependsOn}。
     */
    function refresh(host) {
        const bools = {};
        host.querySelectorAll('[data-bool]').forEach(el => {
            bools[el.dataset.key] = el.dataset.bool;
        });
        // 模块开关：关掉则「卡片」与「一句说明」二选一
        for (const m of current.modules) {
            const on = bools[m.toggle.key] === 'true';
            host.querySelectorAll('[data-module="' + m.id + '"]').forEach(el => {
                el.hidden = !on;
            });
            host.querySelectorAll('[data-module-off="' + m.id + '"]').forEach(el => {
                el.hidden = on;
            });
        }
        // 单项依赖：开关关掉则本行与它下面那行说明一起隐藏
        host.querySelectorAll('[data-row]').forEach(row => {
            const f = fieldOf(row.dataset.row);
            if (!f || !f.dependsOn) {
                return;
            }
            const on = bools[f.dependsOn] === 'true';
            row.hidden = !on;
            const desc = host.querySelector('[data-desc="' + row.dataset.row + '"]');
            if (desc) {
                desc.hidden = !on;
            }
        });
    }

    /** 原生选目录/选文件对话框（只有桌面壳给得出这个能力） */
    async function browse(host, key) {
        const input = host.querySelector('[data-key="' + key + '"]');
        // 选目录还是选文件，看后端下发的类型 —— 不按键名猜
        const field = fieldOf(key);
        const kind = field && field.type === 'PATH_FILE' ? 'file' : 'directory';
        const r = await shell().pickPath({kind, current: input ? input.value : ''});
        if (!r || !r.ok) {
            // 取消也走这条路：不当错误弹
            if (r && r.message && r.message !== '已取消') {
                UI.err(r.message);
            }
            return;
        }
        if (input) {
            input.value = r.path;
        }
    }

    async function save(host, btn) {
        const values = collect(host);
        try {
            const plan = await UI.withBusy(btn, '预演中…',
                () => Api.post('/api/settings/plan', {values}));
            // 有错就只摆错误，别让「要改 10 项」的清单把真正的毛病淹掉
            if (plan.errors.length) {
                await showPlan(plan, true);
                return false;
            }
            // real = 真的会改到生效值的那些；剩下的 cleanup 只是把文件里「值等于出厂值」的
            // 旧条目去掉（老版本每次保存都整份重写攒下的），一个生效值都不动 ——
            // 为它弹一次「保存前确认」是白让人点一下，所以只在有真改动时弹
            const real = plan.changes.filter(c => !c.cleanup);
            if (!real.length && !plan.changes.length) {
                UI.ok('没有要改的项');
                return true;
            }
            if (real.length && !await showPlan(plan, false)) {
                return false;
            }
            const result = await UI.withBusy(btn, '保存中…', () => Api.put('/api/settings', {values}));
            pendingRemovals.clear();
            await load(host);
            const parts = ['已保存 ' + result.changed + ' 项'];
            if (result.cleaned) {
                parts.push('另外删掉文件里 ' + result.cleaned + ' 项与出厂值相同的'
                    + '（生效值一个没变，只是不再显示「已改」）');
            }
            if (result.pendingCount) {
                parts.push('其中 ' + result.pendingCount + ' 项要重启后端才生效');
            }
            UI.ok(parts.join('；'));
            return true;
        } catch (e) {
            // 后端把「哪一项填得不对」写在 message 里，原样给人看
            UI.err(e.message);
            return false;
        }
    }

    /**
     * 预演结果：改了哪几项（旧 → 新）+ 提醒 + 错误。
     * 用 UI.modal 而不是 UI.confirm —— 这张表要能滚动、要能同时摆三样东西，
     * 一句 message 装不下。
     *
     * <p>`cleanup` 那一档（文件里「值等于出厂值」的旧条目，老版本整份重写攒下的）
     * **不进那张表**，只在下面摆一句 —— 它们不改任何生效的值，混进「会改动哪几项」里
     * 会让一次「就改了一行」的保存看起来像动了三十处（用户 2026-09-25 报的正是这个观感）。
     */
    function showPlan(plan, errorOnly) {
        return new Promise((resolve) => {
            const real = plan.changes.filter(c => !c.cleanup);
            const cleaned = plan.changes.length - real.length;
            const rows = real.map(c => '<tr>'
                + '<td>' + UI.esc(c.label) + '</td>'
                + '<td class="mono small">' + (c.from === null ? '（未覆盖）' : UI.esc(c.from)) + '</td>'
                + '<td class="mono small">' + (c.to === null ? '（恢复出厂）' : UI.esc(c.to)) + '</td>'
                + '</tr>').join('');
            // cls 只在要覆盖 .hint 的默认色时才给（提醒用 .hint 本身就是 warn 色）
            const list = (items, cls) => items.length
                ? '<div class="hint' + (cls ? ' ' + cls : '') + '">' + items.map(m =>
                    '<div><b>' + UI.esc(m.label) + '</b>：' + UI.esc(m.text) + '</div>').join('')
                    + '</div>' : '';

            const m = UI.modal(`
                <div class="modal">
                    <div class="modal-head">
                        <span>${errorOnly ? '这些项填得不对' : '保存前确认'}</span>
                        <button class="modal-close" type="button">×</button>
                    </div>
                    <div class="modal-body">
                        ${real.length ? `
                            <p class="muted small">会改动 ${real.length} 项：</p>
                            <table>
                                <thead><tr><th>项目</th><th>现在文件里</th><th>改成</th></tr></thead>
                                <tbody>${rows}</tbody>
                            </table>` : ''}
                        ${cleaned ? `<p class="muted small">另外文件里有 ${cleaned} 项与出厂值相同的条目，
                            会顺手删掉（生效值一个都不变）—— 它们是老版本每次保存整份重写攒下的，
                            留着只会让每一行都显示「已改」。</p>` : ''}
                        ${list(plan.errors, 'hint-err')}
                        ${list(plan.warnings, '')}
                        ${errorOnly ? '' : '<p class="muted small">改完这些值还要重启后端才生效。</p>'}
                    </div>
                    <div class="modal-foot">
                        <button type="button" class="btn-plain" data-act="cancel">
                            ${errorOnly ? '知道了' : '取消'}</button>
                        ${errorOnly ? '' :
                            '<button type="button" class="btn-primary" data-act="ok">保存</button>'}
                    </div>
                </div>`, () => finish(false));   // 点遮罩空白处 = 取消，不能让 promise 悬着
            const finish = (val) => {
                document.removeEventListener('keydown', onKey);
                m.close();
                resolve(val);
            };
            // Esc = 取消。与 UI.confirm 同一套手感
            const onKey = (e) => {
                if (e.key === 'Escape') {
                    finish(false);
                }
            };
            document.addEventListener('keydown', onKey);
            m.box.querySelector('.modal-close').onclick = () => finish(false);
            m.box.querySelector('[data-act="cancel"]').onclick = () => finish(false);
            const okBtn = m.box.querySelector('[data-act="ok"]');
            if (okBtn) {
                okBtn.onclick = () => finish(true);
            }
        });
    }

    /**
     * 保存 → 重启。语义就是按钮名：先落盘，再重启。
     * 落盘失败（或用户取消）就中止 —— 不做「重启成旧配置」这种怪事。
     */
    async function saveAndRestart(host, btn) {
        if (!await save(host, btn)) {
            return;
        }
        const go = await UI.confirm('将重启后端进程（约 10~30 秒，期间页面会短暂打不开）。继续？',
            {title: '保存并重启后端', okText: '重启', danger: false});
        if (!go) {
            return;
        }
        UI.ok('正在重启后端，稍后页面会自己回来…');
        // 壳**收到请求**就回话（真正重启要几十秒，完成时这个页面会被导航走）——
        // 所以这里等的是「它收下了没有」，不是「重启完了没有」。
        // 它**拒绝**时（不是本应用的页面、或上一次还没重启完）必须说出来：
        // 丢掉回包的话用户会以为重启过了，然后对着「改了没生效」发懵
        // （2026-09-25 报的「删了本地 eh 库、保存并重启后端后没有任何提示」就是这条链）。
        const r = await shell().restartBackend();
        if (r && !r.ok) {
            UI.err('重启没发起：' + (r.message || '原因未知')
                + '。改动已经落盘，重启后端才会生效 —— 关掉壳、再双击 desktop\\启动.cmd 打开即可。');
        }
    }

    return {render};
})();
