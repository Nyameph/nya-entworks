/**
 * 韵脚查询组件 —— 韵脚词典页「查询」页签与填词页右栏「韵脚词典」tab 共用的**唯一一份**查询实现
 * （填词助手设计 §4.6 / §4.7，填词工具设计 §10.1）。
 *
 * 对外只有四个口子：
 *   render(host, opts)   opts = {onPick, pickOnly, compact}
 *   setAnchor(text)      外部改锚（填词页点格子 / 词典页点韵部行）并立即查一次
 *   refresh()            按当前状态重查
 *   clear()              只清结果区，不查（判定在调用方，见下）
 *
 * opts 语义（三者互不耦合，调用方按需组合）：
 *   onPick   给了 = 单击只调 onPick(item)，**不进勾选态**（调用方接管点击，填词页用它把词填进格子）。
 *            没给 = 词典页：单击勾选 / 取消勾选，双击复制 item.text 到剪贴板。
 *   pickOnly = 只读挑选模式（填词页右栏）：不画「新增 / 改词性 / 删除」（右栏只挑不改）。
 *            与 onPick 是两个维度：pickOnly 管按钮出不出，onPick 管点击怎么走；填词页两个都给。
 *   compact  = 控件折成两行，给窄的右栏用。**不再由 pickOnly 隐含**，要窄栏就显式传。
 *
 * 「锚」= 查询起点：一个汉字（后端展开它的全部读音）或韵母 / 韵部名（ang / v / 十六唐）。
 * 锚解析在后端（RhymeService.anchorOf）；认不出时后端在 data.message 里给一句具体的话，前端**原样展示**，
 * 绝不另编一句笼统的「认不出这个锚」——「这个韵下 0 条」和「认不出锚」必须长得不一样。
 * 锚为空时按 'a' 兜底查询（只在请求参数里兜底，**不写回输入框**）。
 *
 * 结果区分两级：一级按查询粒度分块（yun18 按韵身 / finals 按韵母），<b>横向排成一行 tab</b>，
 * 只画选中的那一块，块头写全韵母 / 韵身 / 十八韵；
 * 二级按词性分块（NULL → 「未分类」，永远排最后），<b>每块每页 30 个方块、带翻页条</b>（2026-09-15
 * 由「显示更多 30 个」改成翻页 —— 单个词性上千条时「显示更多」要点几十次）。
 *
 * 数据红线（填词助手设计 §9.4）：词条可能来自用户自建词表 / 语料，本文件不写任何日志（复制也不写）。
 *
 * 样式在 app.css 末尾的 `.rh-*` 块里；选择器一律写全（`.rh-groups .rh-chip` 这种），
 * 见填词工具设计 §13 第 38 条：同优先级比先后，状态类被基类盖掉时界面上一点反馈都没有。
 */
const RhymeQuery = (() => {

    // /entries 的 limit：**必须与后端 RhymeService.MAX_LIMIT 一致**，两边不一致就是静默截断
    // （2026-09-14 由 200 放宽到 2000，随后开源词表入库、单韵身最多约 1.3 万条，再放宽到 20000）。
    const LIMIT = 20000;
    const PAGE_SIZE = 30; // 每个二级块（词性）每页多少个方块；分页而不是「显示更多 30 个」
    const DEFAULT_ANCHOR = 'a'; // 锚为空时的兜底（用户要求：没有查询时默认显示 a 的结果）
    // 单击勾选的延后：双击的前一个 click 先到，等这么久没等到 dblclick 才当单击办。
    // 不能用「点两下 = 反转两次」，那样双击之后选中态是错的。
    const CLICK_DELAY = 220;

    let hostEl = null;
    let opts = {onPick: null, pickOnly: false, compact: false};

    // ---- 查询状态（跨 render 保留：切页签回来还是上次那个锚） ----
    let anchor = '';
    let granularity = 'yun18';
    let sources = [];   // 来源多选：空数组 = 不限（勾满五个与不勾结果一致，后端也不筛选）
    let keyword = '';

    // ---- meta 缓存（粒度 / 来源清单）：并发只发一次请求 ----
    let metaCache = null;
    let metaInflight = null;

    // ---- 竞态：只认最后一次查询的结果 ----
    let seq = 0;

    // ---- 渲染态 ----
    let selected = new Set();  // 勾选的词条 id → DELETE /entries 的 ids
    let itemById = new Map();  // id → item；方块上只放 id，点击时回头取
    let pages = new Map();     // 分页：`块序号:块键|词性` → 当前页（0 起）。每个二级块各翻各的
    let activeBlock = null;    // 选中的一级块（韵身 / 韵母）；null = 落到第一块。换查询时重置
    let lastData = null;       // 上次查询结果，供翻页 / 换一级块就地重画（不重新请求）
    let lastAnchor = '';       // 上次实际用的锚（用于「认不出」时的那句回落文案）
    let srcDocHandler = null;  // 来源面板「点别处就收」的 document 监听（开着时才有值）

    function ensureMeta(force) {
        if (force) {
            metaCache = null;
            metaInflight = null;
        }
        if (metaCache) {
            return Promise.resolve(metaCache);
        }
        if (!metaInflight) {
            metaInflight = Api.get('/api/song/rhyme/meta').then(m => {
                metaCache = m;
                metaInflight = null;
                return m;
            }, e => {
                metaInflight = null;
                throw e;
            });
        }
        return metaInflight;
    }

    // 紧凑只看 compact：pickOnly 不再隐含布局（填词页要窄栏就两个都传）
    const isCompact = () => !!opts.compact;

    // ==================== 对外四口 ====================

    /**
     * 在 host 里画控件 + 结果区并查一次。可重复调用（切页签回来）：
     * 锚 / 粒度 / 来源 / 关键字沿用上次状态，勾选与展开量重置。
     */
    async function render(host, o) {
        hostEl = host;
        opts = Object.assign({onPick: null, pickOnly: false, compact: false}, o || {});
        closeSourcePanel(); // 上一次来源面板的「点别处就收」监听先摘掉，别越挂越多
        try {
            await ensureMeta(true);
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        host.innerHTML = shellHtml();
        bindControls();
        runQuery();
    }

    /** 外部改锚并立即查一次（不等防抖） */
    function setAnchor(text) {
        anchor = (text === null || text === undefined) ? '' : String(text).trim();
        const input = hostEl && hostEl.querySelector('#rh-anchor');
        if (input && input.value !== anchor) {
            input.value = anchor;
        }
        runQuery();
    }

    /** 按当前状态重查 */
    function refresh() {
        runQuery();
    }

    /** 清空结果区（不查）：作废在途查询，勾选一并丢掉 */
    function clear() {
        seq++;
        selected.clear();
        pages.clear();
        activeBlock = null;
        itemById = new Map();
        lastData = null;
        const box = hostEl && hostEl.querySelector('#rh-result');
        if (box) {
            box.innerHTML = '';
        }
        updateSelButtons();
    }

    // ==================== 控件 ====================

    function shellHtml() {
        const srcs = (metaCache && metaCache.sources) || [];
        const grans = (metaCache && metaCache.granularities) || [];
        const line1 = '<div class="rh-bar-line">'
            + '<label class="small">锚 <input type="text" id="rh-anchor" '
            + 'placeholder="汉字或韵母 / 韵部名，如 心 / ang / 十六唐"></label>'
            + '<label class="small">粒度 <select id="rh-gran">'
            + grans.map(g => '<option value="' + UI.esc(g.key) + '"'
                + (g.key === granularity ? ' selected' : '') + '>' + UI.esc(g.label) + '</option>').join('')
            + '</select></label>'
            + '</div>';
        const line2 = '<div class="rh-bar-line">'
            + sourceMultiHtml(srcs)
            + '<label class="small">关键字 <input type="text" id="rh-q"></label>'
            + (opts.pickOnly ? '' : '<span class="rh-spacer"></span>'
                + '<button type="button" id="rh-add" class="btn-plain">新增</button>'
                + '<button type="button" id="rh-wc" class="btn-plain" disabled>改词性</button>'
                + '<button type="button" id="rh-del" class="btn-danger" disabled>删除</button>')
            // compact（填词页右栏）窄，外链就留在这一行里跟着关键字排
            + (isCompact() ? linksHtml() : '')
            + '</div>';
        // 提示文案跟着「单击归谁管」走：onPick 模式（填词页右栏）单击是填进格子，不是勾选
        const note = opts.onPick
            ? '<div class="muted small rh-note">锚 = 汉字 / 韵母 / 韵部名；'
                + '点一个填词格，再点候选就填进去；双击复制。</div>'
            : (isCompact()
                ? '<div class="muted small rh-note">锚 = 汉字 / 韵母 / 韵部名；单击勾选、双击复制。</div>'
                : '<div class="muted small rh-note">锚是单个汉字时按它的全部读音查（多音字每个读音的韵部都算命中）；'
                    + '认不出会明确报错，不会静默给 0 条。单击勾选、双击复制；'
                    + '「字表」来源的不可选（后端拒删）。</div>');
        return '<div class="rh-bar' + (isCompact() ? ' rh-bar-compact' : '') + '">'
            + line1 + line2
            // 宽工具条上外链是 `.rh-bar` 的直接子项，靠 `margin-left:auto` 顶到最右边
            // （放在 line2 里顶不过去：`.rh-bar-line` 是内容宽度，行内的 `.rh-spacer` 没有
            // 可分的空间，实测 1920 屏上外链停在行中间、离右边缘还有 600px）。
            + (isCompact() ? '' : linksHtml())
            + '</div>'
            + note
            + '<div id="rh-result" class="rh-groups"></div>';
    }

    /**
     * 两个外链。放组件里（不放在词典页的工具条上）：词典页与填词页右栏共用同一份，
     * 两处都看得到、又不会各自长一套。**只跳转、不抓站**（原因见设计文档 §12）。
     *
     * <p>位置由调用处决定（宽条上顶到最右、compact 里跟在关键字后面），样式见
     * app.css 的 `.rh-bar > .rh-links`。
     */
    function linksHtml() {
        return '<span class="rh-links">'
            + '<a class="rh-link" href="https://yayunla.com/" target="_blank" rel="noopener"'
            + ' title="在线押韵词典（参考）：只跳转、不抓站">押韵啦</a>'
            + '<a class="rh-link" href="https://www.wanmeiyunjiao.com/" target="_blank" rel="noopener"'
            + ' title="在线押韵词典（参考）：只跳转、不抓站">完美韵脚</a>'
            + '</span>';
    }

    /**
     * 来源多选（2026-09-14 取代原来的「词性」下拉，用户要求「改为多选的 source」）。
     *
     * <p>五个来源平铺成一行复选框在填词页右栏（compact）会撑破，所以收成一个按钮 + 下拉面板：
     * 按钮上写当前选择（不限 / 单个来源名 / N 项）。勾选即查，没有「确定」按钮。
     * 面板的关闭靠 document 冒泡阶段的监听（面板内部一律 stopPropagation），
     * 不用 capture —— capture 比内部处理器先跑，点复选框就会把面板关掉。
     */
    function sourceMultiHtml(srcs) {
        return '<span class="small rh-field">来源 '
            + '<span class="rh-multi">'
            + '<button type="button" id="rh-src-btn" class="btn-plain">'
            + UI.esc(srcLabel()) + '</button>'
            + '<div class="rh-multi-panel" id="rh-src-panel" hidden>'
            + srcs.map(s => '<label class="small rh-multi-item">'
                + '<input type="checkbox" class="rh-src" value="' + UI.esc(s) + '"'
                + (sources.indexOf(s) >= 0 ? ' checked' : '') + '> '
                + UI.esc(sourceLabel(s)) + '</label>').join('')
            + '<button type="button" id="rh-src-clear" class="btn-plain">清空</button>'
            + '</div></span></span>';
    }

    /** 多选按钮上的文案 */
    function srcLabel() {
        if (sources.length === 0) {
            return '不限';
        }
        return sources.length === 1 ? sourceLabel(sources[0]) : sources.length + ' 项';
    }

    /** 开面板：挂上「点别处就收」的监听（重复开不重复挂） */
    function openSourcePanel() {
        if (srcDocHandler) {
            return;
        }
        srcDocHandler = () => closeSourcePanel();
        document.addEventListener('click', srcDocHandler);
    }

    /** 收面板：摘监听 + 隐藏（幂等，render 换掉 DOM 后调它也不会出错） */
    function closeSourcePanel() {
        if (srcDocHandler) {
            document.removeEventListener('click', srcDocHandler);
            srcDocHandler = null;
        }
        const panel = hostEl && hostEl.querySelector('#rh-src-panel');
        if (panel) {
            panel.hidden = true;
        }
    }

    function bindControls() {
        const anchorInput = hostEl.querySelector('#rh-anchor');
        const qInput = hostEl.querySelector('#rh-q');
        const granSel = hostEl.querySelector('#rh-gran');
        const srcBtn = hostEl.querySelector('#rh-src-btn');
        const srcPanel = hostEl.querySelector('#rh-src-panel');
        anchorInput.value = anchor;
        qInput.value = keyword;
        if (granSel) {
            granSel.value = granularity;
        }
        // 边打边查：锚 / 关键字防抖 300ms；下拉变化立即查
        let deb = null;
        const fire = () => {
            clearTimeout(deb);
            anchor = anchorInput.value.trim();
            keyword = qInput.value.trim();
            runQuery();
        };
        const debounced = () => {
            clearTimeout(deb);
            deb = setTimeout(fire, 300);
        };
        anchorInput.oninput = debounced;
        qInput.oninput = debounced;
        for (const el of [anchorInput, qInput]) {
            el.onkeydown = (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    fire();
                }
            };
        }
        if (granSel) {
            granSel.onchange = () => {
                granularity = granSel.value;
                runQuery();
            };
        }
        if (srcBtn) {
            srcBtn.onclick = (e) => {
                e.stopPropagation(); // 别让这次点击冒泡到 document 把面板又收掉
                srcPanel.hidden = !srcPanel.hidden;
                if (srcPanel.hidden) {
                    closeSourcePanel();
                } else {
                    openSourcePanel();
                }
            };
            srcPanel.onclick = (e) => e.stopPropagation();
            srcPanel.querySelectorAll('.rh-src').forEach(box => {
                box.onchange = () => {
                    sources = [...srcPanel.querySelectorAll('.rh-src')]
                        .filter(b => b.checked).map(b => b.value);
                    srcBtn.textContent = srcLabel();
                    runQuery();
                };
            });
            hostEl.querySelector('#rh-src-clear').onclick = () => {
                srcPanel.querySelectorAll('.rh-src').forEach(b => {
                    b.checked = false;
                });
                sources = [];
                srcBtn.textContent = srcLabel();
                runQuery();
            };
        }
        if (!opts.pickOnly) {
            hostEl.querySelector('#rh-add').onclick = () => openAddModal();
            hostEl.querySelector('#rh-wc').onclick = () => openWordClassModal();
            hostEl.querySelector('#rh-del').onclick = () => deleteSelected();
            anchorInput.focus(); // 填词页右栏（pickOnly）别把焦点从填写格抢走
        }
    }

    // ==================== 查询 ====================

    async function runQuery() {
        if (!hostEl) {
            return;
        }
        const result = hostEl.querySelector('#rh-result');
        if (!result) {
            return;
        }
        const mySeq = ++seq;
        selected.clear();
        updateSelButtons();
        // 空锚兜底成 a：只进请求参数，不写回输入框
        const used = anchor || DEFAULT_ANCHOR;
        const params = new URLSearchParams({anchor: used});
        params.set('granularity', granularity);
        // 来源多选：同名参数重复出现，后端收成 List<String> 做 IN
        for (const s of sources) {
            params.append('source', s);
        }
        if (keyword) {
            params.set('q', keyword);
        }
        params.set('limit', String(LIMIT));
        let data;
        try {
            // 不传 type：类型这一维已从 UI 去掉（后端仍接受，但页面不再有入口）
            data = await Api.get('/api/song/rhyme/entries?' + params.toString());
        } catch (e) {
            if (mySeq === seq) {
                result.innerHTML = '<div class="card"><div class="hint hint-err">'
                    + UI.esc(e.message) + '</div></div>';
            }
            return;
        }
        if (mySeq !== seq) {
            return; // 已有更新的查询在途，丢掉这份旧结果
        }
        lastData = data;
        lastAnchor = used;
        // 换查询 = 换了一批块，分页码与选中的一级块都得重置（就地重画那条路不清）
        pages.clear();
        activeBlock = null;
        paintResult(data, used);
    }

    /** 用 lastData 就地重画（翻页 / 换一级块用，不重新请求） */
    function repaint() {
        if (lastData) {
            paintResult(lastData, lastAnchor);
        }
    }

    function paintResult(data, used) {
        const result = hostEl && hostEl.querySelector('#rh-result');
        if (!result) {
            return;
        }
        if (!data) {
            result.innerHTML = '';
            return;
        }
        const items = data.items || [];
        // 锚认不出：优先用后端那句（「锚不能为空」/「认不出这个锚：X」/「「哟」的读音都拆不出韵部」），
        // 后端没给才回落本页文案。这与「认出了但这个韵没有词条」必须长得不一样。
        const msg = data.message || ((!data.rhymeBodies || !data.rhymeBodies.length)
            ? '认不出这个锚：' + used + '。可以填一个汉字、一个韵母（ang / v / -i …）或十八韵名（十六唐）。'
            : '');
        if (msg) {
            result.innerHTML = '<div class="card"><div class="hint hint-err">'
                + UI.esc(msg) + '</div></div>';
            return;
        }
        const names = (data.yun18Names || []).map(UI.esc).join(' / ');
        let html = '<div class="card">'
            + '<div class="rh-summary">'
            + '<span class="small">共 ' + UI.esc(data.total) + ' 条'
            + (names ? '（韵部：' + names + '）' : '') + '</span>'
            + '<span class="muted small">'
            + (opts.onPick ? '点方块选词' : '单击勾选、双击复制')
            + '</span></div>';
        if (!items.length) {
            html += UI.empty('这个韵下还没有词条');
        } else {
            html += blocksHtml(items);
            if (items.length < Number(data.total)) {
                html += '<div class="muted small rh-note">只显示前 ' + items.length
                    + ' 条（上限 ' + LIMIT + '），用关键字或来源缩一小集合</div>';
            }
        }
        result.innerHTML = html + '</div>';
        bindResult();
    }

    // ==================== 分块 ====================

    /**
     * 一级块 = 查询粒度：finals 按韵母分，yun18（默认）按韵身分。
     *
     * <p>一级块<b>横向排成一列 tab</b>（用户 2026-09-15 要求「最顶层的韵母等内容改为横向列出的 tab
     * 标签页」），只画选中的那一块 —— 一个锚往往命中好几个韵身 / 韵母，全部摊开时下面几块要滚很远
     * 才够得着。选中的那一块的表头照旧写全三样（韵母 / 韵身 / 十八韵）。
     */
    function blocksHtml(items) {
        const byFinals = granularity === 'finals';
        itemById = new Map();
        const blocks = [];
        const seen = new Map();
        for (const it of items) {
            itemById.set(it.id, it);
            const key = (byFinals ? it.finals : it.rhymeBody) || '';
            let b = seen.get(key);
            if (!b) {
                b = {key: key, items: []};
                seen.set(key, b);
                blocks.push(b);
            }
            b.items.push(it);
        }
        // 选中的块被换查询换掉了（或还没选过）就落到第一块
        let cur = 0;
        if (activeBlock !== null) {
            const at = blocks.findIndex(b => b.key === activeBlock);
            if (at >= 0) {
                cur = at;
            }
        }
        activeBlock = blocks[cur].key;
        return tabsHtml(blocks, cur, byFinals) + blockHtml(blocks[cur], cur, byFinals);
    }

    /**
     * 一级块的 tab 条：横着排，窄栏自动换行。tab 上只写「块键 + 十八韵 + 条数」，
     * 三样写全留给下面那块选中块的表头（那里有地方）。
     */
    function tabsHtml(blocks, cur, byFinals) {
        return '<div class="rh-tabs">' + blocks.map((b, i) => {
            const head = b.items[0] || {};
            const sub = [head.yun18, b.items.length + ' 条'].filter(Boolean).join(' · ');
            return '<button type="button" class="rh-tab' + (i === cur ? ' on' : '') + '"'
                + ' data-key="' + UI.esc(b.key) + '"'
                + ' title="' + UI.esc((byFinals ? '韵母 ' + b.key : '韵身 ' + b.key)
                    + (head.yun18 ? ' · ' + head.yun18 : '')) + '">'
                + '<span class="rh-tab-key mono">' + UI.esc(b.key || '—') + '</span>'
                + '<span class="rh-tab-sub">' + UI.esc(sub) + '</span>'
                + '</button>';
        }).join('') + '</div>';
    }

    /** 块头写全三样：韵母 / 韵身 / 十八韵（用户要求「在列表上方显示韵母、韵身、十八韵」） */
    function blockHtml(b, i, byFinals) {
        const head = b.items[0] || {};
        const finalsText = byFinals ? (head.finals || '') : finalsOfBody(head.rhymeBody, b.items);
        return '<div class="rh-block">'
            + '<div class="rh-block-head">'
            + '<span class="rh-block-title">'
            + '<span class="rh-key">韵母</span> <b>' + UI.esc(finalsText || '—') + '</b>'
            + ' · <span class="rh-key">韵身</span> <b>' + UI.esc(head.rhymeBody || '—') + '</b>'
            + ' · <span class="rh-key">十八韵</span> <b>' + UI.esc(head.yun18 || '—') + '</b>'
            + '</span>'
            + '<span class="muted small">共 ' + b.items.length + ' 条</span>'
            + '</div>'
            + subBlocksHtml(b, i)
            + '</div>';
    }

    /**
     * 按韵身分块时，「韵母」= **本块内实际出现的**韵母去重（不是韵身的全集：uang 一条都没有
     * 却写出来是误导）。顺序按 /meta 里该韵身 finals 的次序，meta 里没有的（或 meta 缺）
     * 按字典序排在后面 —— 「前端只画不判」，这里只汇总后端已经算好的字段。
     * 只有一个韵母时就与「韵身」长一样，这是对的，不做特例。
     */
    function finalsOfBody(body, items) {
        const present = [];
        for (const it of items) {
            if (it.finals && present.indexOf(it.finals) < 0) {
                present.push(it.finals);
            }
        }
        const rows = (metaCache && metaCache.rhymes) || [];
        const hit = rows.find(r => r.body === body);
        const order = (hit && hit.finals) || [];
        const rank = (f) => {
            const i = order.indexOf(f);
            return i < 0 ? order.length : i;
        };
        return present.slice().sort((x, y) => rank(x) - rank(y) || x.localeCompare(y)).join(' / ');
    }

    /** 二级块 = 词性：块序按 /meta 的 wordClasses，「未分类」永远最后 */
    function subBlocksHtml(b, bi) {
        const order = (metaCache && metaCache.wordClasses) || [];
        const buckets = new Map();
        for (const it of b.items) {
            const k = it.wordClass || '';
            if (!buckets.has(k)) {
                buckets.set(k, []);
            }
            buckets.get(k).push(it);
        }
        // sort 稳定：meta 里没列出的写法（900）按出现先后排，未分类（1000）压最后
        const keys = [...buckets.keys()].sort((x, y) => wcRank(x, order) - wcRank(y, order));
        // 页码的键必须带上词性：同一韵下的 名词 / 动词 / 未分类 共用一个块键，
        // 少了词性就会「翻一个块，三个块一起翻页」
        return keys.map(k => subBlockHtml(bi + ':' + b.key + '|' + k, k, buckets.get(k))).join('');
    }

    function wcRank(wc, order) {
        if (!wc) {
            return 1000;
        }
        const i = order.indexOf(wc);
        return i < 0 ? 900 : i;
    }

    /**
     * 一个词性块：表头 + 当页方块 + 翻页条。
     *
     * <p>翻页而不是「显示更多 30 个」（用户 2026-09-15 要求）：一个韵身下单个词性可能上千条，
     * 「显示更多」要连点几十次才到头，而且没有「我在哪儿」的感觉。
     */
    function subBlockHtml(key, wc, list) {
        const totalPages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
        let page = pages.get(key) || 0;
        if (page > totalPages - 1) {
            page = totalPages - 1; // 换查询后条数变少：别停在一个不存在的页上
        }
        pages.set(key, page);
        const visible = list.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE);
        return '<div class="rh-sub">'
            + '<div class="rh-sub-head">'
            + '<span class="rh-wc">' + UI.esc(wc || '未分类') + '</span>'
            + '<span class="muted small">' + list.length + ' 条</span>'
            + '</div>'
            + '<div class="rh-chips">' + visible.map(chipHtml).join('') + '</div>'
            + pagerHtml(key, page, totalPages, list.length)
            + '</div>';
    }

    /** 翻页条：只有一页就整个不画（省得每个块下面都挂一排灰按钮） */
    function pagerHtml(key, page, totalPages, total) {
        if (totalPages <= 1) {
            return '';
        }
        const from = page * PAGE_SIZE + 1;
        const to = Math.min(total, (page + 1) * PAGE_SIZE);
        const btn = (delta, label, disabled) => '<button type="button" class="btn-plain rh-page"'
            + ' data-key="' + UI.esc(key) + '" data-delta="' + delta + '"'
            + (disabled ? ' disabled' : '') + '>' + label + '</button>';
        return '<div class="rh-pager">'
            + btn(-1, '‹ 上一页', page <= 0)
            + '<span class="muted small">第 ' + (page + 1) + ' / ' + totalPages + ' 页'
            + '（' + from + '–' + to + '，共 ' + total + ' 条）</span>'
            + btn(1, '下一页 ›', page >= totalPages - 1)
            + '</div>';
    }

    /** 方块正面只有 字/词 + 频次；读音与来源在 title 里（hover 才出），别画到正面 */
    function chipHtml(it) {
        const fixed = it.source === 'MODERN';
        const parts = [];
        if (it.pinyin) {
            parts.push(it.pinyin);
        }
        parts.push(sourceLabel(it.source));
        if (fixed) {
            parts.push('现代规范字表，不能删');
        }
        return '<span class="rh-chip' + (fixed ? ' rh-fixed' : '')
            + (selected.has(it.id) ? ' on' : '') + '"'
            + ' data-id="' + UI.esc(it.id) + '"'
            + ' title="' + UI.esc(parts.join(' · ')) + '">'
            + UI.esc(it.text)
            + (it.freq > 0 ? '<span class="muted small">' + UI.esc(it.freq) + '</span>' : '')
            + '</span>';
    }

    // 表外的值原样透出（后端加了新来源而这里没跟上时，至少不会显示成空白）
    function sourceLabel(source) {
        return {MODERN: '字表', XLSX: '词表', MANUAL: '手动', CORPUS: '语料',
                OPEN: '开源'}[source] || source || '';
    }

    function bindResult() {
        hostEl.querySelectorAll('#rh-result .rh-chip').forEach(chip => {
            // 双选手势：单击是延迟 CLICK_DELAY 才办的勾选，一个 dblclick 到就把那个单击作废、只复制。
            // 不做「点两下 = 反转两次」——那样双击之后选中态是错的人还看不出来。
            let clickTimer = null;
            chip.onclick = () => {
                const it = itemById.get(Number(chip.dataset.id));
                if (!it) {
                    return;
                }
                if (opts.onPick) {
                    opts.onPick(it); // 调用方接管点击（填词页把它填进格子），不进勾选态
                    return;
                }
                // MODERN 是种子字表，后端拒删 —— 前端不让它进勾选态（判定仍在后端）
                if (it.source === 'MODERN') {
                    return;
                }
                if (clickTimer) {
                    // 双击的第二个 click 先到：连点不该累加，等 dblclick 收尾
                    clearTimeout(clickTimer);
                    clickTimer = null;
                    return;
                }
                clickTimer = setTimeout(() => {
                    clickTimer = null;
                    toggleSelect(chip, it);
                }, CLICK_DELAY);
            };
            chip.ondblclick = () => {
                if (opts.onPick) {
                    return; // 挑选模式里双击没有额外含义
                }
                const it = itemById.get(Number(chip.dataset.id));
                if (!it) {
                    return;
                }
                clearTimeout(clickTimer);
                clickTimer = null;
                copyText(it);
            };
        });
        // 一级块 tab：只换选中的块，就地重画
        hostEl.querySelectorAll('#rh-result .rh-tab').forEach(btn => {
            btn.onclick = () => {
                activeBlock = btn.dataset.key;
                repaint();
            };
        });
        // 翻页：只动这个二级块自己的页码，其余块的页不动
        hostEl.querySelectorAll('#rh-result .rh-page').forEach(btn => {
            btn.onclick = () => {
                const key = btn.dataset.key;
                const next = (pages.get(key) || 0) + Number(btn.dataset.delta);
                pages.set(key, Math.max(0, next));
                repaint();
            };
        });
    }

    /** 双击方块 = 复制到剪贴板。复制不写日志（数据红线 §9.4） */
    function copyText(it) {
        navigator.clipboard.writeText(it.text).then(
            () => UI.ok('已复制「' + it.text + '」'),
            () => UI.err('复制失败'));
    }

    function toggleSelect(chip, it) {
        if (selected.has(it.id)) {
            selected.delete(it.id);
            chip.classList.remove('on');
        } else {
            selected.add(it.id);
            chip.classList.add('on');
        }
        updateSelButtons();
    }

    /** 勾选数变化时刷新那两个对勾选生效的按钮（删除 / 改词性） */
    function updateSelButtons() {
        const del = hostEl && hostEl.querySelector('#rh-del');
        if (del) {
            del.disabled = selected.size === 0;
            del.textContent = selected.size ? '删除（' + selected.size + '）' : '删除';
        }
        const wc = hostEl && hostEl.querySelector('#rh-wc');
        if (wc) {
            wc.disabled = selected.size === 0;
            wc.textContent = selected.size ? '改词性（' + selected.size + '）' : '改词性';
        }
    }

    // ==================== 删除 / 新增 ====================

    async function deleteSelected() {
        if (!selected.size) {
            return;
        }
        const ids = [...selected];
        if (!await UI.confirm('删除选中的 ' + ids.length + ' 条？（不可撤销）', {okText: '删除'})) {
            return;
        }
        const btn = hostEl.querySelector('#rh-del');
        let n;
        try {
            n = await UI.withBusy(btn, '删除中…',
                () => Api.del('/api/song/rhyme/entries', {ids: ids}));
        } catch (e) {
            // MODERN 的拒绝文案就在这里（后端 message），原样弹出来
            UI.err(e.message);
            return;
        }
        UI.ok(typeof n === 'number' ? '已删除 ' + n + ' 条' : '已删除');
        selected.clear();
        refresh();
    }

    /**
     * 改词性：把勾选的（可能横跨多个词性块与多页）一次改成同一个词性。
     *
     * <p>勾选跨块跨页是有意的 —— 这个功能就是给「语料词里这一类都该归到 X」准备的，
     * 而它们按当前词性散在好几个块里。所以按钮在结果区顶部的工具条上，不在块头。
     *
     * <p>下拉里「不填词性」= <b>清空</b>（后端把 word_class 置 NULL，回到「未分类」块），
     * 与新增弹窗里那一项同名同义。
     */
    function openWordClassModal() {
        const ids = [...selected];
        if (!ids.length) {
            UI.err('先勾选要改的词条');
            return;
        }
        const classes = (metaCache && metaCache.wordClasses) || [];
        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>改词性</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<div class="muted small">把勾选的 ' + ids.length + ' 条一次改成同一个词性；'
            + '选「不填词性」= 清空它们的词性（回到「未分类」）。</div>'
            + '<label class="small">词性 <select id="rh-wc-sel"><option value="">不填词性</option>'
            + classes.map(c => '<option>' + UI.esc(c) + '</option>').join('')
            + '</select></label>'
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" id="rh-wc-cancel">取消</button>'
            + '<button type="button" class="btn-primary" id="rh-wc-submit">提交</button>'
            + '</div></div>');
        const submitBtn = m.box.querySelector('#rh-wc-submit');
        m.box.querySelector('.modal-close').onclick = () => m.close();
        m.box.querySelector('#rh-wc-cancel').onclick = () => m.close();
        submitBtn.onclick = () => UI.withBusy(submitBtn, '提交中…', async () => {
            const wordClass = m.box.querySelector('#rh-wc-sel').value;
            let n;
            try {
                n = await Api.put('/api/song/rhyme/entries/word-class',
                    {ids: ids, wordClass: wordClass});
            } catch (e) {
                UI.err(e.message); // 枚举外的写法 / 空勾选都是后端那句话，原样弹
                return;
            }
            UI.ok((wordClass ? '已改成「' + wordClass + '」' : '已清空词性')
                + '：' + (typeof n === 'number' ? n + ' 条' : ''));
            m.close();
            selected.clear();
            refresh();
        });
    }

    /** 新增：一行一个，词后可以跟拼音；失败行**必须**列出来，不静默丢 */
    function openAddModal() {
        const classes = (metaCache && metaCache.wordClasses) || [];
        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>新增词条</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<div class="muted small">一行一个，词后可以跟拼音（空格分隔）。'
            + '同一批填的词性一致；失败行会列在下面，不会静默丢。'
            + '<b>库里已经有这个词时不会重复添加，只把它标成「手动」</b>'
            + '（写了拼音就只认这个读音，没写拼音则同字词的全部读音都算）。'
            + '「现代规范字表」的行不动。</div>'
            + '<textarea id="rh-add-text" class="rh-add-text" rows="8" '
            + 'placeholder="碎星 suìxīng&#10;心&#10;…"></textarea>'
            + '<label class="small">词性 <select id="rh-add-wc"><option value="">不填词性</option>'
            + classes.map(c => '<option>' + UI.esc(c) + '</option>').join('')
            + '</select></label>'
            + '<div id="rh-add-result"></div>'
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" id="rh-add-cancel">取消</button>'
            + '<button type="button" class="btn-primary" id="rh-add-submit">提交</button>'
            + '</div></div>');
        const submitBtn = m.box.querySelector('#rh-add-submit');
        m.box.querySelector('.modal-close').onclick = () => m.close();
        m.box.querySelector('#rh-add-cancel').onclick = () => m.close();
        m.box.querySelector('#rh-add-text').focus();
        submitBtn.onclick = () => UI.withBusy(submitBtn, '提交中…', async () => {
            const text = m.box.querySelector('#rh-add-text').value;
            const box = m.box.querySelector('#rh-add-result');
            if (!text.trim()) {
                UI.err('先粘要新增的词');
                return;
            }
            let data;
            try {
                data = await Api.post('/api/song/rhyme/entries/batch', {
                    text: text,
                    wordClass: m.box.querySelector('#rh-add-wc').value
                });
            } catch (e) {
                box.innerHTML = '<div class="hint hint-err">新增失败：' + UI.esc(e.message) + '</div>';
                return;
            }
            const failed = data.failed || [];
            // 「提升」= 库里已有、这次只把 source 改成 MANUAL 的行数（一行文本可能提升多行）
            let html = '<div class="hint">解析 ' + UI.esc(data.total) + ' 行，新增 '
                + UI.esc(data.inserted) + ' 条，提升为人工 ' + UI.esc(data.promoted || 0)
                + ' 行，跳过 ' + UI.esc(data.skipped) + ' 条。</div>';
            if (failed.length) {
                html += '<table><thead><tr><th>行</th><th>内容</th><th>原因</th></tr></thead><tbody>'
                    + failed.map(f => '<tr><td>' + UI.esc(f.line) + '</td>'
                        + '<td>' + UI.esc(f.text) + '</td>'
                        + '<td>' + UI.esc(f.reason) + '</td></tr>').join('')
                    + '</tbody></table>';
            }
            box.innerHTML = html;
            if (failed.length) {
                return; // 有失败行就留着弹窗，让人看得见
            }
            UI.ok('已新增 ' + data.inserted + ' 条，提升为人工 ' + (data.promoted || 0) + ' 行');
            m.close();
            await ensureMeta(true);
            refresh();
        });
    }

    return {render, setAnchor, refresh, clear};
})();
