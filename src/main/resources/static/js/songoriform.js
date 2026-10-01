/**
 * 原曲页那一套表单：**「新增原曲」与每行「编辑」共用同一份**（2026-09-25 合并，原先
 * 行内还有一个「导入」按钮 + 一个七类下拉的「编辑」弹窗，两者合成了这一个）。
 *
 * 触发点两处（都在 songstat.js 里，各自只做「打开」一件事）：
 *   ① 工具条「新增原曲」→ isNew = true，八个文件框全空，文件由各框的「选择」挑；
 *   ② 每行「编辑」→ isNew = false，库里已有的八类文件各就各位，还能指派 / 移入冗余 / 改名。
 *
 * **八个文件框，一类一个**（用户 2026-09-25 定）：顺序、标签取后端下发的 `types`，
 * 前端不写第二份类别表。每个框里有四件事：选文件、取消选中、（修改时）移入冗余、
 * 以及下方那行小字「改名为：…」。
 *
 * **三组、两种播放按钮**（用户 2026-09-25 第二轮定，规则在后端 `TypeOption` 里）：
 *   ① 原曲 / 歌词 —— 点哪一格都是「原曲音频 + 歌词」；
 *   ② 伴奏 / 人声 / 样例 / 样例歌词 —— 点哪一格都是「这一格的音频 + 样例歌词」；
 *   ③ mid / 工程 —— 没有播放按钮。
 * 所以按钮画不画、点了放什么，全看后端下发的 `playAudio` / `playLyric` / `group`，
 * 前端只把那一句话画成一行说明、把两个格子的东西拼给播放浮层。
 *
 * **「已确认」在文件那一行**：三个位（原曲 / 歌词 / 工程）就画在各自那一格的框里。
 * 新增那一路**只有手动加进来的文件才画、且默认勾着** —— 名字是人手打的、文件是人手挑的，
 * 本来就没什么好「再确认」的；编辑那一路照库里的值画，一格不落。
 *
 * **数据一律后端下发**（types / extRule / files / candidates / renameAllowed /
 * duplicateReason / blockedReason / bpm / playUrl / lyricPreview），前端不写第二份扩展名表、
 * 也不写第二份「名字里不能有哪些字符」的字符表 —— 所以 `open` 自己去调 /import-expand 拿那一整包。
 *
 * **判定只为实时，认定仍在后端**：本组件里的闸门（evaluate）只负责把按钮置灰、
 * 把原因写在 title 与提示区，且**原因那句话也是后端给的**（每个文件行的 blockedReason）；
 * 真正的拦截是 /import-plan（一条 blockedReason 整批不执行）与 apply 那一刻的二次查重。
 * 按钮可点不代表一定过。
 *
 * **改名预览是本地镜像**（照 songform.js 的既有做法）：只为实时 —— 落盘名字以 plan 回传的
 * `moves[].toFileName` 为准，这里拼错只会让预览不好看，不会改坏磁盘。
 * 拼法与后端 {@code SongOriginalNaming.build} 逐字对应（[demo] / [伴奏] / [人声] / [BPM=…] / [工程]）。
 *
 * **三条路径分得清**（这是合并后最容易混的地方）：
 *   - 选盘外的文件 → 走 /import（plan → 确认 → 异步任务），会**搬家**；
 *   - 「从原曲目录选」那个下拉 → 同一个 /import，但源就在原曲目录里，于是不搬、只改库
 *     （勾着改名时顺手改名）；
 *   - 「移入冗余」→ /retire-file，**它不删任何东西**，只是把文件从原曲目录挪去冗余根。
 */
const SongOriForm = (() => {

    /** 类别 → 前缀里那段固定写法。与后端 SongOriginalNaming.build 一一对应，改一个要改另一个 */
    const PREFIX = {
        demo: '[demo] ',
        demoLrc: '[demo] ',
        accompaniment: '[伴奏] ',
        vocals: '[人声] ',
        svp: '[工程] '
    };

    /**
     * 曲速的写法：与后端 SongOriginalNaming.bpmText 同口径
     * （{@code stripTrailingZeros().toPlainString()} —— 120.50 要写成 120.5，120.0000 写成 120）。
     * 非数字原样返回（后端 plan 会拦），空 → null。
     */
    function bpmTextOf(value) {
        const t = (value == null ? '' : String(value)).trim();
        if (!t) {
            return null;
        }
        const n = Number(t);
        return Number.isFinite(n) ? String(n) : t;
    }

    /** 拼一个文件的主名（不含扩展名）。只为实时预览；不认识的类别返回 null */
    function buildName(type, rawName, artist, bpmText) {
        const raw = (rawName || '').trim();
        const art = (artist || '').trim();
        if (type === 'original' || type === 'lyric') {
            return art ? art + ' - ' + raw : raw;
        }
        if (type === 'mid') {
            return '[BPM=' + (bpmText || '？') + '] ' + raw;
        }
        return PREFIX[type] ? PREFIX[type] + raw : null;
    }

    /** 后缀（含点、保留原文大小写）：口径同 GroupFileOps#planMoves，用它拼预览 */
    function suffixOf(row) {
        const ext = row.ext || '';
        return ext ? row.fileName.slice(row.fileName.length - ext.length) : '';
    }

    /** 这次到底改不改名：勾了 + 后端说可以（§0.2 第 7 条：要 artist_check = 1） */
    function effectiveRename(state) {
        return !!(state.rename && state.renameAllowed);
    }

    /** 这个文件这次的目标全名；不勾改名时为 null（＝照原名放着） */
    function previewOf(state, row) {
        if (!effectiveRename(state) || !row.type) {
            return null;
        }
        const main = buildName(row.type, state.rawName, state.artist, bpmTextOf(state.bpm));
        return main == null ? null : main + suffixOf(row);
    }

    /**
     * 文件框下那行小字的**内容**（已转义）。不真改名、或原曲名还没填时给空串 ——
     * 那时名字根本没法定下来，写一个小字预告只会让人以为「就剩这半截名字」。
     */
    function previewText(state, row) {
        if (!effectiveRename(state) || !state.rawName.trim()) {
            return '';
        }
        const p = previewOf(state, row);
        return p == null ? '' : '改名为：' + UI.esc(p);
    }

    /**
     * 哪几类带「已确认」位：勾上 = 扫描模板 / 搜资源不再覆盖这一项。
     *
     * <p>三个位各管一个字段，所以画在各自那一格的**文件那一行**上（用户 2026-09-25 定）。
     * **这是原有的能力、不是新增的** —— 原先它们只在「编辑」那个七类下拉弹窗里，
     * 两个弹窗合并时要是忘了搬，「歌词已确认」这类位就再没有地方能勾了
     * （用户清单里没提，删掉却是静默的功能缺失）。歌手那个位不一样：它在抬头，两个窗口都画。
     */
    const CONFIRM_FIELD = {original: 'originalCheck', lyric: 'lyricCheck', svp: 'svpCheck'};

    /**
     * 这一格「已确认」此刻的值 —— **画的与提交的都用它，不许有第二种算法**。
     *
     * <p>**新增那一路的默认值是「这一格有手动加进来的文件就勾上」**（用户 2026-09-25 定）：
     * 名字是人手打的、文件是人手挑的，落库时就该写成已确认。但**空格子必须是不勾**：
     * 那个位在库里是「**哪怕空、也是有意留空**」的意思（搜资源拿它挡「别去补全歌词 / 原曲」，
     * 见 `SongResourceSearchService#searchOne`），凭空勾上会让一条新建的原曲**再也补不全** ——
     * 症状是「搜资源说什么都不补」，静默，也看不出跟这个勾有关系。
     *
     * <p>用户自己动过的（`state.confirmTouched`）一律照用户的 —— 否则「我把它取消了」会在
     * 下一次重画（原曲名一改就重画）时被默认值顶回来。编辑那一路**保持原样**：照库里的值，
     * 没有「默认值」这回事。
     */
    function confirmValue(state, t) {
        const key = CONFIRM_FIELD[t];
        if (!key || state.confirmTouched[key] || !state.isNew) {
            return !!state[key];
        }
        const box = boxesOf(state).find(b => b.type === t);
        return !!(box && box.slot);
    }

    // ------------------------------------------------------------------
    // 八格模型：把后端那份「一行行文件」摊成「一类一格」
    // ------------------------------------------------------------------

    /**
     * 八个框 + 每格里放什么。顺序与标签都取后端下发的 types（新增那一路也是八类，
     * 只是没有「已指派」的那些行）。
     *
     * <p>「未归类」（认不出类别、用户也没指派过的）不进框，落到下面的例外区 —— 那里保留
     * 老式下拉，粘贴路径那条路才不会断。
     */
    function boxesOf(state) {
        return (state.types || []).map(t => {
            const mine = state.files.filter(f => f.type === t.type && f.kind !== 'NEED_TYPE');
            const existing = mine.find(f => f.existing) || null;
            const fresh = mine.filter(f => !f.existing);
            return {
                type: t.type,
                label: t.label,
                slot: existing || fresh[0] || null,
                // 同类多出来的那些（后端会标 DUPLICATE）：画在同一格里，让人就地取消
                extras: existing ? fresh : fresh.slice(1)
            };
        });
    }

    function looseRowsOf(state) {
        return state.files.filter(f => f.kind === 'NEED_TYPE' || !f.type);
    }

    /** 这一格是否已被「取消选中」（只有库里已有的那些格才有这回事） */
    function excludedOf(state, type) {
        return (state.excludeExisting || []).indexOf(type) >= 0;
    }

    // ------------------------------------------------------------------
    // 闸门：evaluate(state, need) → {ok, reasons[]}
    // ------------------------------------------------------------------

    /** 编辑那一路：什么都没变就不让保存（口径同 SongForm 的 'changed'） */
    function changedOf(state) {
        if (state.files.some(f => !f.existing)) {
            return true;    // 加了文件（含「在原曲目录里指派一个」）
        }
        if (state.excludeExisting.length) {
            return true;    // 取消选中过某一格
        }
        if (state.rawName.trim() !== state.initial.rawName
            || state.artist.trim() !== state.initial.artist
            || state.artistCheck !== state.initial.artistCheck
            // 三个「已确认」也算变化：只把「歌词已确认」勾上、别的一个字没动，
            // 那也是一次真提交（它挡的是扫描 / 搜资源别覆盖这一项）。漏判的话
            // 保存按钮灰着，用户勾了半天没反应，也看不出为什么
            || state.originalCheck !== state.initial.originalCheck
            || state.lyricCheck !== state.initial.lyricCheck
            || state.svpCheck !== state.initial.svpCheck) {
            return true;
        }
        // 勾着改名 = 已有文件会被改成规范名；名字本来就对的那几个不算变化
        return effectiveRename(state)
            && state.files.some(f => f.existing && previewOf(state, f) !== f.fileName);
    }

    function evaluate(state, need) {
        const reasons = [];
        // 后端下发的整批拦截（原曲名 / 歌手里的非法字符、新增查重、库里找不到这一条…）。
        // 前端**不重写**这份判据：哪些字符不能进文件名、结尾的点与空格为什么不行，
        // 只在后端一处（GroupFileOps#requireMainName）说了算 —— 这里只把它画成
        // 「按钮灰着 + 原因写着」，所以也就不用在这里维护第二份字符表
        for (const line of state.blocked || []) {
            reasons.push(line);
        }
        // 逐行的红字（扩展名不符 / 盘上没了 / 类别撞了 / 还没选类别）：句子来自后端，
        // 一行都不许漏 —— planOf 对**每一行**都要求 READY（含库里已有那些），有一条不是
        // 就整批拒，所以这里把已有那些行的原因也算进闸门，否则按钮亮着、点下去白挨一次拒
        for (const f of state.files) {
            if (f.existing && excludedOf(state, f.type)) {
                // 这次不提交它（plan / apply 那边也会整个跳过这一类）。**必须跳过**：
                // 被 [×] 掉的若是「库里指着、盘上没了」的那个文件，expand 照样把它当
                // ABSENT 报出来（expand 的入参里没有「取消选中」这一项），不跳的话
                // 原因区永远挂着一条、保存永远灰着，而用户明明只是想把歌手改一个字
                continue;
            }
            if (f.kind === 'READY') {
                continue;
            }
            reasons.push(f.blockedReason || ('「' + f.fileName + '」还没选类别'));
        }
        if (need === 'ori-changed') {
            if (!changedOf(state)) {
                reasons.push('没有要保存的');
            }
        } else {
            if (!state.rawName.trim()) {
                reasons.push('原曲名要填');
            }
            if (!state.files.length) {
                reasons.push('还没有选文件');
            }
            // 新增查重（裁决 9）：整句用后端下发的，里面已经写着「请用『编辑』」
            if (state.duplicateReason) {
                reasons.push(state.duplicateReason);
            }
        }
        // 后端那句与前端自己那句可能一字不差（原曲名没填时两边都报「原曲名要填」），
        // 去重后原因区才不会把同一句话印两遍
        return {ok: reasons.length === 0, reasons: reasons.filter((r, i) => reasons.indexOf(r) === i)};
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * @param opts.title       标题（缺省按 isNew 给）
     * @param opts.isNew       true = 新增原曲
     * @param opts.originalId  编辑那一路的原曲 id（新增传 null）
     * @param opts.rawName     原曲名（新增空白起步）
     * @param opts.artist      歌手（编辑是现成值）
     * @param opts.artistCheck / originalCheck / lyricCheck / svpCheck
     *                         编辑那一路的四个确认位（原值照回，不勾就是不给覆盖）
     * @param opts.rename      是否改名（初值 true，裁决 3）
     * @param opts.buttons     [{id, label, need, primary?}]，need 见 evaluate
     * @param opts.onDone      (result) => void，提交成功后（svp 提醒已经显示过）
     */
    function open(opts) {
        const state = {
            isNew: !!opts.isNew,
            originalId: opts.originalId == null ? null : opts.originalId,
            rawName: opts.rawName || '',
            artist: opts.artist || '',
            artistCheck: !!opts.artistCheck,
            originalCheck: !!opts.originalCheck,
            lyricCheck: !!opts.lyricCheck,
            svpCheck: !!opts.svpCheck,
            // 用户手动动过哪几个「已确认」勾。**默认值只在没动过时才顶用** ——
            // 否则「我把它取消了」会在下一次重画（原曲名一改就重画）时被默认值顶回来
            confirmTouched: {},
            rename: opts.rename !== false,
            // 本次导入的源路径。每次 import-expand 回来后按响应刷成「绝对路径真值」，
            // 这样取消选中能按路径精确摘除、重发也不会因为大小写 / 相对路径而错位
            paths: [],
            typeByFile: {},          // 路径 → 类别，键与 state.paths 同一份真值，重发时带上
            excludeExisting: [],     // 取消选中的已有类别：这次不参与改名
            bpm: '',
            files: [],
            types: [],
            extRule: {},
            candidates: [],          // 原曲目录里还没指派的文件（每格的「从原曲目录选」）
            detail: null,            // 编辑那一路的 detail：只为了「最近搜索」那一行
            renameAllowed: false,
            duplicateReason: null,
            blocked: [],
            svpWarnings: [],
            loaded: false,
            adding: false,
            initial: {
                rawName: (opts.rawName || '').trim(),
                artist: (opts.artist || '').trim(),
                artistCheck: !!opts.artistCheck,
                originalCheck: !!opts.originalCheck,
                lyricCheck: !!opts.lyricCheck,
                svpCheck: !!opts.svpCheck
            }
        };

        const m = UI.modal('<div class="modal modal-wide">'
            + '<div class="modal-head"><span>' + UI.esc(opts.title
                || (state.isNew ? '新增原曲' : '修改原曲')) + '</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body"></div>'
            + '<div class="modal-foot" id="sof-foot"></div>'
            + '</div>');
        m.box.querySelector('.modal-close').onclick = () => m.close();

        // ---- foot：按钮 + 关闭 + 原因区。**先建按钮再 renderForm** —— 反过来的话
        // 第一次 refresh() 找不到按钮，禁用被跳过（症状：刚打开时不合格的按钮也能点）----
        const foot = m.box.querySelector('#sof-foot');
        foot.style.flexWrap = 'wrap';
        const reasonsEl = document.createElement('div');
        reasonsEl.style.cssText = 'flex-basis:100%;margin-top:4px';
        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'btn-plain';
        closeBtn.textContent = '关闭';
        closeBtn.onclick = () => m.close();
        foot.appendChild(closeBtn);
        for (const b of opts.buttons || []) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = b.primary ? 'btn-primary' : '';
            btn.textContent = b.label;
            btn.dataset.btn = b.id;
            btn.onclick = (ev) => runButton(b, ev.currentTarget);
            foot.insertBefore(btn, closeBtn);
        }
        foot.appendChild(reasonsEl);

        renderForm();

        // 初次拉数据：这一趟把类型清单、扩展名规则（对话框过滤要用）、八个框里的已有文件、
        // 可指派的候选、目标目录，以及编辑那一路的「最近搜索」全带回来。
        // **不在这里自动弹文件对话框** —— 先弹表单、由用户决定要不要选文件
        refreshFromServer().then(renderForm);
        if (!state.isNew && state.originalId != null) {
            loadDetail();
        }

        function requestBody() {
            return {
                rawName: state.rawName,
                artist: state.artist,
                originalId: state.originalId,
                isNew: state.isNew,
                paths: state.paths.slice(),
                rename: state.rename,
                typeByFile: Object.assign({}, state.typeByFile),
                bpm: state.bpm || null,
                artistCheck: state.artistCheck
            };
        }

        /** 编辑那一路顺手取一次 detail：只为「搜资源」那一行的最近搜索时间（只读） */
        async function loadDetail() {
            try {
                state.detail = await Api.get('/api/song/template/detail?originalId='
                    + encodeURIComponent(state.originalId));
                renderForm();
            } catch (e) {
                // 取不到不致命：只是那一行少一句「最近搜索」
            }
        }

        /** 拉一次预览（只读磁盘）：八个框的文件 / 候选 / 重名 / svp 提醒全在这一包里 */
        async function refreshFromServer() {
            try {
                const exp = await Api.post('/api/song/template/import-expand', requestBody());
                state.types = exp.types || [];
                state.extRule = exp.extRule || {};
                state.files = exp.files || [];
                state.candidates = exp.candidates || [];
                state.renameAllowed = !!exp.renameAllowed;
                state.duplicateReason = exp.duplicateReason || null;
                state.blocked = exp.blockedReason ? [exp.blockedReason] : [];
                state.svpWarnings = exp.svpWarnings || [];
                state.targetDir = exp.targetDir || '';
                state.loaded = true;
                // 曲速：用户没填过就用后端点定的那个（同目录 svp ＞ midi 内容 ＞ 库 ＞ 旧 mid 文件名）
                if (!state.bpm && exp.bpm) {
                    state.bpm = exp.bpm;
                }
                // 路径与类别表一起换成响应里的真值（去重 + 绝对路径都在这一趟做完了）。
                // **两张表必须同一个键空间**：类别表若还按用户选文件时那个串当键，重发时
                // 后端按真路径查不到它，那一格会变成「认不出类别」—— 用户的选择就这么静默掉了
                state.paths = state.files.filter(f => !f.existing && f.path).map(f => f.path);
                const rekeyed = {};
                for (const f of state.files) {
                    // NEED_TYPE 那一行的 type 是**后端猜的**（认不出类别时按扩展名兜底），
                    // 搬进这张表等于替用户确认了：下一趟它就是 READY，那个文件会真的
                    // 按这一类导进来 —— 而屏幕上那个下拉还写着「（选类别）」，
                    // 人根本不知道自己的文件已经被定了类
                    if (!f.existing && f.path && f.type && f.kind !== 'NEED_TYPE') {
                        rekeyed[f.path] = f.type;
                    }
                }
                state.typeByFile = rekeyed;
            } catch (e) {
                UI.err(e.message);
            }
        }

        // ---- 表单区 ----

        function renderForm() {
            foot.querySelectorAll('[data-confirm]').forEach(el => el.remove());
            const body = m.box.querySelector('.modal-body');
            if (!state.loaded) {
                body.innerHTML = UI.spinner('读取磁盘…');
                refresh();
                return;
            }
            body.innerHTML = headHtml() + boxesHtml() + looseHtml();
            bindForm();
        }

        /** 抬头：原曲名 / 歌手 / 两个确认位 / 曲速 / 改名 / 目标目录 / 整批红字 */
        function headHtml() {
            let html = '<div style="display:grid;grid-template-columns:1fr 1fr;gap:6px">'
                + '<span class="muted small">原曲名</span>'
                + '<span class="muted small">歌手（改名的名字里要带它）</span>'
                + '<input type="text" id="sof-rawname" placeholder="原曲名" value="'
                + UI.esc(state.rawName) + '">'
                + '<input type="text" id="sof-artist" placeholder="歌手" value="'
                + UI.esc(state.artist) + '">'
                + '</div>';

            // 「歌手已确认」两个窗口都画。新增那一路后端口径是「填了歌手就算已确认」
            // （§0.2 第 7 条），所以那一格只是照实展示、勾不动 —— 画一个不存在的开关
            // 比画一个灰着的开关更糟（人会以为勾了有用）
            const locked = state.isNew;
            const checked = locked ? !!state.artist.trim() : state.artistCheck;
            html += '<div class="row" style="align-items:center;gap:14px;margin-top:8px;flex-wrap:wrap">'
                + '<label class="tpl-check" title="' + (state.renameAllowed
                    ? '把文件改成规范名（会动磁盘上的文件名）'
                    : '填了歌手、并且歌手已确认之后才能改名') + '">'
                + '<input type="checkbox" id="sof-rename"'
                + (state.rename ? ' checked' : '') + (state.renameAllowed ? '' : ' disabled')
                + '> 按规范改名</label>'
                + '<label class="tpl-check" title="' + (locked
                    ? '新增时填了歌手就算已确认'
                    : '勾选后，扫描模板 / 搜资源不再覆盖这一项') + '">'
                + '<input type="checkbox" id="sof-artistcheck"' + (checked ? ' checked' : '')
                + (locked ? ' disabled' : '') + '> 歌手已确认</label>'
                + '<label class="tpl-check">曲速（mid 用）'
                + '<input type="text" id="sof-bpm" style="width:88px" value="'
                + UI.esc(state.bpm) + '"></label>'
                + '</div>';

            if (!state.renameAllowed) {
                html += '<p class="muted small">先填歌手'
                    + (state.isNew ? '' : '并勾「歌手已确认」')
                    + '才能改名（名字里要带歌手）。不改名也能导入：文件按原名放进去。</p>';
            }
            if (state.targetDir) {
                html += '<p class="muted small mono">目标目录：' + UI.esc(state.targetDir) + '</p>';
            }
            for (const line of state.blocked) {
                html += '<div class="hint hint-err">' + UI.esc(line) + '</div>';
            }
            if (state.duplicateReason) {
                html += '<div class="hint hint-err">' + UI.esc(state.duplicateReason) + '</div>';
            }
            if (!state.isNew) {
                html += '<div class="row" style="gap:8px;align-items:center;margin-top:4px">'
                    + '<button type="button" class="btn-plain" id="sof-search-res" '
                    + 'title="从网络搜索并补全歌手 / 原曲 / 歌词（只认未加密文件）">搜资源</button>'
                    + '<span class="muted small">最近搜索：'
                    + (state.detail && state.detail.lastSearchTime
                        ? UI.esc(state.detail.lastSearchTime) : '还没有搜过')
                    + '</span>'
                    + '</div>';
            }
            return html;
        }

        /**
         * 八个文件框，按后端给的 `group` 分三组，每组头上那句说明也是后端给的
         * （见 TypeOption 的 groupLabel）—— 前端只在标签变了的时候画那一行，
         * 所以「同组必须挨着」这条约束在后端的顺序里，不在这里。
         */
        function boxesHtml() {
            let html = '<div style="margin-top:10px">';
            let group = null;
            for (const box of boxesOf(state)) {
                const opt = optionOf(box.type);
                if (opt.group && opt.group !== group) {
                    group = opt.group;
                    html += '<div class="sof-group">' + UI.esc(opt.groupLabel || '') + '</div>';
                }
                html += boxHtml(box);
            }
            return html + '</div>' + excludedHint();
        }

        function boxHtml(box) {
            const t = box.type;
            const f = box.slot;
            const excluded = excludedOf(state, t);
            let head = '<div class="sof-box-head">'
                + '<span class="sof-box-label">' + UI.esc(box.label) + '</span>'
                + '<span class="mono small" style="flex:1;word-break:break-all" title="'
                + UI.esc(f ? (f.path || f.fileName) : '') + '">'
                + (f ? UI.esc(f.fileName) : '<span class="muted">（没选文件）</span>')
                + (f && f.existing ? ' <span class="tag" title="库里已指派，这一格改的是它在盘上的名字">库里</span>' : '')
                + (excluded ? ' <span class="tag" title="这次不改它">已取消选中</span>' : '')
                + '</span>'
                + confirmHtml(t, f)
                + '<span class="sof-acts">' + actsHtml(t, f) + '</span>'
                + '</div>';

            // 「改名后的文件名以较小字体显示在文件选择框下」（用户要求）。**容器始终画出来**：
            // 原曲名是边打边变的，下面那个 refresh() 要往这个元素里写新名字，
            // 元素不存在就写不进去（症状：原曲名打完了，小字还是旧的，非得点一下别处才变）
            let sub = f ? '<div class="muted small mono" data-prev="' + t + '">'
                + previewText(state, f) + '</div>' : '';
            // 红字也只在「它还在本次提交里」时画：取消选中之后它就是别人的问题了，
            // 还挂着一条红字会让人以为非解决它不可（比如「盘上找不到」，而那件事
            // 属于「重新扫描」，不属于这次保存）
            if (f && f.blockedReason && !excluded) {
                sub += '<div class="small" style="color:#c0392b">' + UI.esc(f.blockedReason) + '</div>';
            }
            for (const extra of box.extras) {
                sub += '<div class="small" style="color:#c0392b">'
                    + UI.esc(extra.fileName + '：' + (extra.blockedReason || '这一格只能放一个文件'))
                    + ' <button type="button" class="btn-plain sof-cancel-new" data-path="'
                    + UI.esc(extra.path || '') + '">取消选中</button></div>';
            }
            return '<div class="sof-box">' + head + sub + candHtml(t, f) + '</div>';
        }

        /**
         * 「已确认」勾选框 —— 就在文件名后面那一行上（用户 2026-09-25 定：别单占一行）。
         *
         * <p>新增那一路**只有手动加进来的文件才画**（空格子不画：没有东西要保护，画一个不勾的
         * 勾选框只是噪声）；编辑那一路一格不落，照库里的值画。
         */
        function confirmHtml(t, f) {
            if (!CONFIRM_FIELD[t] || (state.isNew && !f)) {
                return '';
            }
            return '<label class="tpl-check" title="勾选后，扫描模板 / 搜资源不再覆盖这一项">'
                + '<input type="checkbox" class="sof-check" data-type="' + t + '"'
                + (confirmValue(state, t) ? ' checked' : '') + '> 已确认</label>';
        }

        /** 一格右边那几个按钮：选择 / 取消选中 / 播放（只有六格有）/ 移入冗余（只有修改那一路有） */
        function actsHtml(t, f) {
            const out = [];
            const excluded = excludedOf(state, t);
            // 「选择」在库里已经有这一格时禁用：一个类别只能放一个文件，想换就先把
            // 原来那个移入冗余（那句话由后端给，见服务的 3.5 闸门）—— 让人先点一下
            // 再被后端拒，不如一开始就灰着并说清为什么
            const occupied = !!(f && f.existing);
            out.push('<button type="button" class="btn-plain sof-pick" data-type="' + t + '"'
                + (occupied ? ' disabled title="这一格库里已经有一个了：先「移入冗余」把它挪走，才能换新的"'
                    : '') + '>选择…</button>');
            if (f) {
                // 已有文件被取消选中之后，这个按钮反过来变成「恢复选中」——
                // 后端不知道「取消选中」这件事（expand 的入参里没有这一项），
                // 所以那一行照样会出现在响应里、框也照样画着它，只有这里能把它接回来
                out.push(excluded
                    ? '<button type="button" class="btn-plain sof-restore" data-type="' + t
                        + '" title="把它重新算进本次提交">恢复选中</button>'
                    : '<button type="button" class="btn-plain sof-cancel" data-type="' + t
                        + '" title="' + UI.esc(f.existing
                            ? '从本次提交里移除：这次不改它的名（文件留盘上，库里也不动）'
                            : '取消选中：只从本次列表移除，不动磁盘上的文件')
                        + '">取消选中</button>');
            }
            out.push(playButtonHtml(t, f));
            if (f && f.existing && !state.isNew) {
                out.push('<button type="button" class="btn-danger sof-retire" data-type="' + t + '"'
                    + (f.path ? '' : ' disabled')
                    + ' title="把这一格的文件从原曲目录挪去冗余目录（不删，随时能捞回来）">移入冗余</button>');
            }
            return out.filter(Boolean).join('');
        }

        /**
         * 「▶ 播放」—— 三组里只有前两组有，且**六格长得一模一样、点下去也是一样的东西**
         * （用户 2026-09-25 定）：
         *   原曲 / 歌词 → 原曲音频 + 歌词；伴奏 / 人声 / 样例 / 样例歌词 → 本格音频 + 样例歌词。
         * 放什么由后端的 playAudio / playLyric 说了算（见 TypeOption），这里只画按钮。
         *
         * <p>可点与否看**这一格自己有没有能放的东西**：音频看 `playUrl`，歌词看
         * `lyricPreview`（后端算好的解析结果）。搭档那一格空着不算错 —— 浮层会只放有的一半。
         */
        function playButtonHtml(t, f) {
            const opt = optionOf(t);
            if (!f || (!opt.playAudio && !opt.playLyric)) {
                // 空格子没有播放按钮；mid / 工程那一组本来就不画（第三组）
                return '';
            }
            const can = !!(f.playUrl || f.lyricPreview);
            // 放不了的原因也是后端给的：这一列只画，不自己判「为什么放不了」
            return '<button type="button" class="btn-plain sof-play" data-type="' + t + '"'
                + (can ? '' : ' disabled title="' + UI.esc(f.playBlockedReason || '放不了这个文件') + '"')
                + '>▶ 播放</button>';
        }

        /** 这一格空着时，给一个「从原曲目录里现有的文件挑」的下拉（修改那一路才有候选） */
        function candHtml(t, f) {
            if (f || !state.candidates.length) {
                return '';
            }
            const mine = state.candidates.filter(c => (c.types || []).indexOf(t) >= 0);
            if (!mine.length) {
                return '';
            }
            return '<div class="row" style="align-items:center;gap:6px;margin-top:2px">'
                + '<span class="muted small">从原曲目录选</span>'
                + '<select class="sof-cand" data-type="' + t + '" style="flex:1">'
                + '<option value="">（不选）</option>'
                + mine.map(c => '<option value="' + UI.esc(c.path) + '">'
                    + UI.esc(c.fileName) + '</option>').join('')
                + '</select></div>';
        }

        /** 取消选中是一去不回的（重开弹窗即恢复）：说清这次不改它们，免得以为文件被删了 */
        function excludedHint() {
            if (!state.excludeExisting.length) {
                return '';
            }
            const labels = state.excludeExisting.map(t => {
                const hit = (state.types || []).find(x => x.type === t);
                return hit ? hit.label : t;
            });
            return '<p class="muted small">已从本次提交移除：' + UI.esc(labels.join('、'))
                + '（这次不改它们；文件留盘上不动，关掉重开这个弹窗可以恢复）</p>';
        }

        /** 认不出类别的那几行：保留老式下拉（粘贴路径这条路就靠它） */
        function looseHtml() {
            const rows = looseRowsOf(state);
            if (!rows.length) {
                return '';
            }
            const body = rows.map(f => {
                // 后端按文件名猜出来的那个类别（PickedFile.type 里带着，kind 仍是 NEED_TYPE）
                // **不预选**：预选了的话，屏幕上看着像「已经定好了」，而按钮其实是灰的
                // （提交时它以 NEED_TYPE 送上去，plan 整批拒），人只会一头雾水地反复点保存。
                // 留成一项「（猜的）」让人主动挑一下，才算真确认
                const opts = ['<option value="">（选类别）</option>']
                    .concat((state.types || []).filter(t =>
                        ((state.extRule || {})[t.type] || []).indexOf(f.ext) >= 0)
                        .map(t => '<option value="' + UI.esc(t.type) + '">' + UI.esc(t.label)
                            + (t.type === f.type ? '（猜的）' : '') + '</option>'))
                    .join('');
                return '<div style="margin-top:4px"><span class="mono small">' + UI.esc(f.fileName)
                    + '</span> <select class="sof-type" data-path="' + UI.esc(f.path || '') + '">'
                    + opts + '</select> <button type="button" class="btn-plain sof-cancel-new" data-path="'
                    + UI.esc(f.path || '') + '">取消选中</button>'
                    + (f.blockedReason ? ' <span class="small" style="color:#c0392b">'
                        + UI.esc(f.blockedReason) + '</span>' : '')
                    + '</div>';
            }).join('');
            return '<div class="sof-box" style="border-color:#c0392b">'
                + '<div class="sof-box-head"><span class="sof-box-label">未归类</span>'
                + '<span class="muted small" style="flex:1">这几行认不出是哪一类，选一个才能提交'
                + '（.zip / .txt 以外的不认识的格式，八类里都没有它的位置）</span></div>'
                + body + '</div>';
        }

        function bindForm() {
            const body = m.box.querySelector('.modal-body');
            body.querySelector('#sof-rawname').oninput = (e) => {
                state.rawName = e.target.value;
                refresh();      // 预览随原曲名实时变（不重发请求：类别与状态不受它影响）
            };
            // 原曲名改完（失焦 / 回车）重拉一次：**名字合不合法由后端判**（非法字符、
            // 结尾的点与空格），这一趟把它那句话带回来，画成红字 + 按钮灰着
            body.querySelector('#sof-rawname').onchange = () => refreshFromServer().then(renderForm);
            body.querySelector('#sof-artist').oninput = (e) => {
                state.artist = e.target.value;
                refresh();
            };
            // 歌手改完（失焦 / 回车）重拉一次：renameAllowed 与 svp 提醒里的名字都跟着它变
            body.querySelector('#sof-artist').onchange = () => refreshFromServer().then(renderForm);
            body.querySelector('#sof-bpm').oninput = (e) => {
                state.bpm = e.target.value;
                refresh();
            };
            body.querySelector('#sof-rename').onchange = (e) => {
                state.rename = e.target.checked;
                renderForm();   // 小字那一行画 / 不画，得重画整页
            };
            const acEl = body.querySelector('#sof-artistcheck');
            if (acEl) {
                acEl.onchange = (e) => {
                    state.artistCheck = e.target.checked;
                    refreshFromServer().then(renderForm);
                };
            }
            // 三个按类别的确认位：它们不参与 expand（预览不受影响），所以只重画按钮的
            // 可用性 —— 保存按钮该不该亮，由 changedOf 看这几个值
            body.querySelectorAll('.sof-check').forEach(cb => cb.onchange = (e) => {
                const key = CONFIRM_FIELD[cb.dataset.type];
                state[key] = e.target.checked;
                state.confirmTouched[key] = true;   // 动过之后默认值不再顶事（见 confirmValue）
                refresh();
            });
            const searchBtn = body.querySelector('#sof-search-res');
            if (searchBtn) {
                searchBtn.onclick = (ev) => searchResources(ev.currentTarget);
            }

            body.querySelectorAll('.sof-pick').forEach(b => b.onclick = () => pickFor(b.dataset.type));
            body.querySelectorAll('.sof-cancel').forEach(b => b.onclick = () => cancelBox(b.dataset.type));
            body.querySelectorAll('.sof-restore').forEach(b => b.onclick = () => restoreBox(b.dataset.type));
            body.querySelectorAll('.sof-cancel-new').forEach(b => b.onclick = () => cancelPath(b.dataset.path));
            body.querySelectorAll('.sof-play').forEach(b => b.onclick = () => playPair(b.dataset.type));
            body.querySelectorAll('.sof-retire').forEach(b => b.onclick = (ev) => retire(b.dataset.type, ev.currentTarget));
            body.querySelectorAll('.sof-cand').forEach(sel => {
                sel.onchange = () => chooseCandidate(sel.dataset.type, sel.value);
            });
            body.querySelectorAll('.sof-type').forEach(sel => {
                sel.onchange = () => {
                    // 未归类那几行的类别：键用响应里的真路径（与 state.paths 同一份）
                    if (sel.value) {
                        state.typeByFile[sel.dataset.path] = sel.value;
                    } else {
                        delete state.typeByFile[sel.dataset.path];
                    }
                    // 重算：定了类别就可能撞名（DUPLICATE）、可能扩展名不符（BAD_EXT），
                    // 这些判定都在后端，前端只把行画成红的
                    refreshFromServer().then(renderForm);
                };
            });
            refresh();
        }

        /** 取一批源路径（壳多选 / 粘贴），取消返回 null */
        async function pickPaths(type) {
            const label = labelOf(type);
            if (window.NyaEntworksShell && typeof window.NyaEntworksShell.pickFiles === 'function') {
                // **不传 defaultPath**：壳会回落到上次选文件的目录并在选完后写回，
                // 于是几个文件框天然共享「上次那个地址」（用户 2026-09-25 的要求）。
                // 传了 defaultPath 就等于每次从头开始。
                const picked = await window.NyaEntworksShell.pickFiles({
                    title: '选择「' + label + '」这一类要放进来的文件',
                    filters: shellFilters(type)
                });
                if (!picked.ok) {
                    // 取消不算错误（ok:false + 「已取消」）
                    if (picked.message && picked.message !== '已取消') {
                        UI.err(picked.message);
                    }
                    return null;
                }
                return picked.paths;
            }
            return await pastePathsModal(type, label);
        }

        /** 壳的对话框过滤：按这一次要挑的那一类给（唯一一份扩展名表在后端，这里只是转发） */
        function shellFilters(type) {
            const exts = ((state.extRule || {})[type] || []);
            return exts.length ? [{name: labelOf(type), extensions: exts}] : undefined;
        }

        /** 后端下发的这一类的元数据（标签、分组、播放计划）；认不出时给个空壳，字段都 undefined */
        function optionOf(type) {
            return (state.types || []).find(t => t.type === type) || {};
        }

        function labelOf(type) {
            return optionOf(type).label || type;
        }

        /** 无壳时的路径录入框：每行一个绝对路径（资源管理器 Shift+右键「复制文件地址」粘进来） */
        function pastePathsModal(type, label) {
            return new Promise((resolve) => {
                const pm = UI.modal('<div class="modal">'
                    + '<div class="modal-head"><span>粘贴路径 · ' + UI.esc(label) + '</span>'
                    + '<button class="modal-close" type="button">×</button></div>'
                    + '<div class="modal-body">'
                    + '<p class="muted small">每行一个文件的绝对路径，都会算作「' + UI.esc(label)
                    + '」这一类。在资源管理器里 Shift+右键文件 →「复制文件地址」，一行粘一个。</p>'
                    + '<textarea id="sof-paste" rows="8" style="width:100%" '
                    + 'placeholder="F:\\歌曲\\某首歌.mp4"></textarea>'
                    + '</div>'
                    + '<div class="modal-foot">'
                    + '<button type="button" class="btn-plain" id="sof-paste-cancel">取消</button>'
                    + '<button type="button" class="btn-primary" id="sof-paste-ok">添加</button>'
                    + '</div></div>');
                const finish = (val) => {
                    pm.close();
                    resolve(val);
                };
                pm.box.querySelector('.modal-close').onclick = () => finish(null);
                pm.box.querySelector('#sof-paste-cancel').onclick = () => finish(null);
                pm.box.querySelector('#sof-paste-ok').onclick = () => {
                    const paths = pm.box.querySelector('#sof-paste').value.split('\n')
                        .map(s => s.trim().replace(/^"+|"+$/g, ''))
                        .filter(Boolean);
                    finish(paths.length ? paths : null);
                };
            });
        }

        /** 一格的「选择…」：选路径 → 追加 → 重算（后端的闸门与猜测都在这一趟里） */
        async function pickFor(type) {
            if (state.adding) {
                return;
            }
            state.adding = true;
            try {
                const paths = await pickPaths(type);
                if (!paths) {
                    return;
                }
                addPaths(paths, type);
                await refreshFromServer();
                renderForm();
            } catch (e) {
                UI.err(e.message);
            } finally {
                state.adding = false;
            }
        }

        /** 一格的下拉：原曲目录里已有的某个文件 → 指派给它（不搬家，最多按规范改个名） */
        async function chooseCandidate(type, path) {
            if (!path) {
                return;
            }
            addPaths([path], type);
            await refreshFromServer();
            renderForm();
        }

        function addPaths(paths, type) {
            for (const p of paths) {
                if (state.paths.indexOf(p) < 0) {
                    state.paths.push(p);
                }
                // 类别按「点了哪一格」定，不再靠后端从文件名猜（这是这一版的主要变化）
                state.typeByFile[p] = type;
            }
        }

        /** 一格的「取消选中」：已有文件＝这次不参与改名；本次选的＝从提交里摘掉 */
        async function cancelBox(type) {
            const box = boxesOf(state).find(b => b.type === type);
            if (!box || !box.slot) {
                return;
            }
            if (box.slot.existing) {
                // 同一类重复按只会让 excludeExisting 里多一个重复项（后端按集合看，不影响
                // 结果，但那份「已从本次提交移除」的清单会把这一个类别印两遍）
                if (!excludedOf(state, type)) {
                    state.excludeExisting.push(type);
                }
            } else {
                removePath(box.slot.path);
            }
            for (const extra of box.extras) {
                removePath(extra.path);
            }
            await refreshFromServer();
            renderForm();
        }

        /** 「恢复选中」：把这一格重新算进本次提交（接回来的还包括它的改名） */
        async function restoreBox(type) {
            const at = (state.excludeExisting || []).indexOf(type);
            if (at >= 0) {
                state.excludeExisting.splice(at, 1);
            }
            await refreshFromServer();
            renderForm();
        }

        /** 未归类那几行 / 同类多出来的那些：按路径摘掉 */
        async function cancelPath(path) {
            if (!path) {
                return;
            }
            removePath(path);
            await refreshFromServer();
            renderForm();
        }

        function removePath(path) {
            const at = state.paths.indexOf(path);
            if (at >= 0) {
                state.paths.splice(at, 1);
            }
            if (path) {
                delete state.typeByFile[path];
            }
        }

        /**
         * 某一格的「▶ 播放」：按后端给这一格定的计划，把**两格**的东西拼起来交给播放浮层 ——
         * 原曲 / 歌词 → 原曲音频 + 歌词；伴奏 / 人声 / 样例 / 样例歌词 → 本格音频 + 样例歌词。
         *
         * <p>两个来源都取自本次预览的那一份数据（`state.files`），所以**两个窗口一套行为**：
         * 新增那一路也有 originalId 之外的来路 —— 本次刚选的文件自己带着临时预览令牌
         * （`playUrl`），歌词是后端随预览一起解析好的（`lyricPreview`），都不依赖库里那条记录。
         * 搭档那一格空着就只放有的一半。
         *
         * <p>浮层只有 songstat.js 里那一个实现（`playPair`），观感与本页模板列那个「原/伴/声/词」
         * 完全一样。
         */
        function playPair(type) {
            const opt = optionOf(type);
            const boxes = {};
            for (const b of boxesOf(state)) {
                boxes[b.type] = b;
            }
            const audioBox = opt.playAudio ? boxes[opt.playAudio] : null;
            const lyricBox = opt.playLyric ? boxes[opt.playLyric] : null;
            const audio = audioBox && audioBox.slot;
            const lyric = lyricBox && lyricBox.slot;
            // `const` 定义的顶层名字不进 window（typeof 守卫是唯一安全的判法）
            if (typeof SongStatPage === 'undefined' || !SongStatPage.playPair) {
                return;
            }
            SongStatPage.playPair(state.rawName.trim() || '（还没填原曲名）',
                audio ? audio.playUrl : null,
                audioBox ? audioBox.label : null,
                lyric ? lyric.lyricPreview : null);
        }

        /** 一格的「移入冗余」：文件搬到冗余根（不删），库里那一列若指着它就清空 */
        async function retire(type, btn) {
            const box = boxesOf(state).find(b => b.type === type);
            const f = box && box.slot;
            if (!f || !f.path) {
                return;
            }
            const ok = await UI.confirm('把「' + f.fileName + '」移入冗余目录？\n'
                + '文件不会丢，只是从原曲目录挪到冗余目录，随时能在资源管理器里捞回来。', {
                title: '移入冗余', okText: '移入冗余', danger: true
            });
            if (!ok) {
                return;
            }
            await UI.withBusy(btn, '移动中…', async () => {
                try {
                    const msg = await Api.post('/api/song/template/retire-file',
                        {originalId: state.originalId, fileName: f.fileName, type: type});
                    UI.ok(msg);
                    await refreshFromServer();
                    renderForm();
                } catch (e) {
                    UI.err(e.message);
                }
            });
        }

        /**
         * 「搜资源」：从网络补全歌手 / 原曲 / 歌词（异步任务、要联网，慢起来几十秒）。
         * 同 execute：用 Promise 接住终态，按钮才不会被 withBusy 提前放开 ——
         * 「起轮询就返回」那件事见 tasks.js 的 {@code wait}，连点会提交两个任务。
         */
        async function searchResources(btn) {
            await UI.withBusy(btn, '搜索中…', () => new Promise((resolve) => {
                const settled = () => resolve();
                Promise.resolve()
                    .then(() => Api.post('/api/song/template/search',
                        {originalTitle: state.rawName, artist: state.artist}))
                    .then((taskId) => {
                        TaskPoll.wait(taskId, {
                            onResult: async (r) => {
                                try {
                                    UI.ok(r && r.message ? r.message : '搜索完成');
                                    await refreshFromServer();
                                    await loadDetail();
                                    renderForm();
                                    return true;
                                } finally {
                                    settled();
                                }
                            },
                            onFail: (error) => {
                                UI.err(error);
                                settled();
                            }
                        });
                    })
                    .catch((e) => {
                        UI.err(e.message);
                        settled();
                    });
            })).catch(() => {});
        }

        /** 刷新预览列与按钮可用性（不改请求、不重画 DOM 结构） */
        function refresh() {
            const body = m.box.querySelector('.modal-body');
            let lines = '';
            for (const b of opts.buttons || []) {
                const gate = evaluate(state, b.need);
                const btnEl = foot.querySelector('[data-btn="' + b.id + '"]');
                if (btnEl) {
                    btnEl.disabled = !gate.ok;
                    btnEl.title = gate.reasons.join('\n');
                }
                // 每行一个按钮的原因，最多三行不刷屏
                if (!gate.ok && lines.split('<').length <= 3) {
                    lines += '<div class="muted small">［' + UI.esc(b.label) + '］'
                        + UI.esc(gate.reasons[0] || '') + '</div>';
                }
            }
            reasonsEl.innerHTML = lines;
            // 小字那一行随原曲名 / 歌手 / 曲速实时变，不重画整页（重画会让输入框失焦，
            // 边打字边失焦就没法输入了）。走 previewText 让它与首次渲染**同一份口径**
            if (body) {
                for (const box of boxesOf(state)) {
                    const el = body.querySelector('[data-prev="' + box.type + '"]');
                    if (el && box.slot) {
                        el.innerHTML = previewText(state, box.slot);
                    }
                }
            }
        }

        /** 提交给 plan / import 的快照 */
        function snapshot() {
            return {
                rawName: state.rawName.trim(),
                artist: state.artist.trim(),
                originalId: state.originalId,
                isNew: state.isNew,
                // 本次导入的行全带上（**包括画成红的那些**）：漏掉它们，
                // 「一个 .mp4 指成了伴奏」会被 plan 静默丢掉，用户以为导进去了
                files: state.files.filter(f => !f.existing).map(f => ({
                    path: f.path || f.fileName,
                    // NEED_TYPE 的 type 是后端猜的：送回去等于替用户确认了
                    type: f.kind === 'NEED_TYPE' ? null : (f.type || null)
                })),
                excludeExisting: state.excludeExisting.slice(),
                rename: state.rename && state.renameAllowed,
                bpm: state.bpm || null,
                artistCheck: state.artistCheck,
                // 三个确认位走同一个算法（新增那一路的默认值是「有文件才勾」）——
                // 送上去的必须与画在屏幕上的那个勾一模一样
                originalCheck: confirmValue(state, 'original'),
                lyricCheck: confirmValue(state, 'lyric'),
                svpCheck: confirmValue(state, 'svp')
            };
        }

        // ---- 预演 → 确认页 → 执行 → 轮询 ----

        async function runButton(b, btnEl) {
            const values = snapshot();
            let planResp;
            try {
                await UI.withBusy(btnEl, '预演中…', async () => {
                    planResp = await Api.post('/api/song/template/import-plan', values);
                });
            } catch (e) {
                UI.err(e.message);
                return;
            }
            if (!planResp) {
                return;
            }
            drawConfirm(b, planResp, values);
        }

        /** 确认页：旧名 → 新名、源目录 → 目标目录、跨卷标记、blockedReason 红字 + svp 提醒 */
        function drawConfirm(b, planResp, values) {
            const body = m.box.querySelector('.modal-body');
            const plan = planResp.plan || {};
            const cross = planResp.crossVolume || [];
            const warnings = planResp.svpWarnings || [];
            let html = '<p class="mono small modal-plan-title">'
                + UI.esc(plan.mainName || values.rawName)
                + (plan.toMainName ? ' <span class="muted">→</span> ' + UI.esc(plan.toMainName) : '')
                + (planResp.isNewOriginal ? ' <span class="tag tag-warn">新建这条原曲</span>' : '')
                + '</p>';

            if (plan.blockedReason) {
                html += '<div class="hint hint-err">' + UI.esc(plan.blockedReason) + '</div>';
            }
            const moves = plan.moves || [];
            const blockedMoves = moves.filter(mv => mv.blockedReason);
            if (moves.length) {
                const parentOf = p => String(p || '').replace(/[\\/][^\\/]*$/, '');
                html += '<p class="muted small mono">' + UI.esc(parentOf(moves[0].fromPath))
                    + ' <span class="muted">→</span> ' + UI.esc(parentOf(moves[0].toPath)) + '</p>';
                html += '<table class="small"><thead><tr><th style="width:44%">文件</th>'
                    + '<th style="width:34%">会变成</th><th></th></tr></thead><tbody>';
                for (const mv of moves) {
                    html += '<tr><td class="mono">' + UI.esc(mv.fileName) + '</td><td class="mono">'
                        + (mv.blockedReason
                            ? '<span class="tag tag-err">' + UI.esc(mv.blockedReason) + '</span>'
                            : UI.esc(mv.toFileName));
                    if (!mv.blockedReason && cross.includes(mv.fileName)) {
                        html += '</td><td><span class="tag tag-warn">跨卷，需拷贝，可能较慢</span></td></tr>';
                    } else {
                        html += '</td><td></td></tr>';
                    }
                }
                html += '</tbody></table>';
            }
            if (blockedMoves.length) {
                html += '<div class="hint hint-err">有 ' + blockedMoves.length + ' 个文件动不了，'
                    + '整批不执行 —— 只搬一部分的话，这条原曲会裂成两半散在两个地方。</div>';
            }
            if (!plan.blockedReason && !moves.length) {
                // 0 条搬动是合法输入（名字与位置都没变，最典型的就是只改了个歌手，
                // 或者「从原曲目录选」指派了一个文件但没勾改名）
                html += '<div class="hint">没有文件要搬动（名字与位置都没变），这次只写库。</div>';
            }
            html += svpBlock(warnings);
            body.innerHTML = html;

            // 旧的确认按钮先摘掉：连点两次会重画这一页，留着上一轮那个按钮，
            // 它的 label 与闭包里那份 values 都是上一轮的（屏幕上显示的与点下去执行的不是一件事）
            foot.querySelectorAll('[data-confirm]').forEach(el => el.remove());
            if (!plan.blockedReason && !blockedMoves.length) {
                const runBtn = document.createElement('button');
                runBtn.type = 'button';
                runBtn.className = 'btn-primary';
                runBtn.textContent = '确认' + b.label;
                runBtn.dataset.confirm = b.id;
                foot.insertBefore(runBtn, foot.firstChild);
                runBtn.onclick = (ev) => execute(b, values, ev.currentTarget);
            }
        }

        /** svp 提醒那块黄底清单（逐条列后端下发的整句，前端只画） */
        function svpBlock(warnings) {
            if (!warnings || !warnings.length) {
                return '';
            }
            let html = '<div class="hint" style="background:#fff8e1;border-color:#f0c36d">'
                + '<p class="small" style="font-weight:600">svp 里的音频引用会因为这次改名 / 搬动失效</p>';
            for (const w of warnings) {
                html += '<div class="small mono">' + UI.esc(w.message) + '</div>';
            }
            return html + '</div>';
        }

        function execute(b, values, btnEl) {
            const body = m.box.querySelector('.modal-body');
            // 提交 → 轮询到终态期间全程禁用按钮。TaskPoll.wait 是「起轮询就返回 stop()」、
            // 不是 Promise，直接调用会让 withBusy 立刻恢复按钮 —— 任务还在跑、用户再点一次
            // 就重复提交（同一批文件动两遍）。用 Promise 接住终态，按钮保持禁用到底。
            // （照抄 songaction.js runPlan 里那段，含这段注释。）
            // 除了被点的那一个，**整条 foot 上的动作按钮一起锁**：withBusy 只管得住 btnEl，
            // 而任务是异步的（可能跑几分钟），期间点另一个动作按钮就会又提交一个任务、
            // 同一批文件动两遍。失败回退时 renderForm() 会按闸门重新算 disabled。
            // 「关闭」不动 —— 任务在服务端跑，关掉弹窗只是不再看轮询
            foot.querySelectorAll('[data-btn], [data-confirm]').forEach(el => { el.disabled = true; });
            UI.withBusy(btnEl, '提交中…', () => new Promise((resolve) => {
                const settled = () => resolve();
                Promise.resolve()
                    .then(() => Api.post('/api/song/template/import', values))
                    .then((taskId) => {
                        body.innerHTML = UI.spinner('执行中…');
                        TaskPoll.wait(taskId, {
                            onResult: async (result) => {
                                try {
                                    UI.ok('完成');
                                    m.close();
                                    // apply 之后再把 svp 提醒显示一遍 —— 用户可能没细看确认页
                                    if (result && result.svpWarnings && result.svpWarnings.length) {
                                        await showSvpWarnings(result.svpWarnings);
                                    }
                                    if (opts.onDone) {
                                        await opts.onDone(result, values, b);
                                    }
                                    return true;
                                } finally {
                                    settled();
                                }
                            },
                            onFail: (error) => {
                                UI.err(error);
                                settled();
                                renderForm();   // 回到表单让人改完再试
                            }
                        });
                    })
                    .catch((e) => {
                        UI.err(e.message);
                        settled();
                        renderForm();
                    });
            })).catch(() => {});   // withBusy 只透传提交的同步异常，上面已 UI.err
        }

        /** 落盘后的 svp 提醒（自己一个弹窗，按道理用户会去 SynthV 里改） */
        function showSvpWarnings(warnings) {
            return new Promise((resolve) => {
                const wm = UI.modal('<div class="modal">'
                    + '<div class="modal-head"><span>还没完：svp 里的音频引用要手动改</span>'
                    + '<button class="modal-close" type="button">×</button></div>'
                    + '<div class="modal-body">'
                    + '<p class="muted small">文件已经搬好、改名了。下面这几条 svp 引用的音频'
                    + '本次被改名 / 搬动过，请在 SynthV 里重新指定音轨的文件 —— 我们不会替你改 svp。</p>'
                    + svpBlock(warnings)
                    + '</div>'
                    + '<div class="modal-foot"><button type="button" class="btn-primary" '
                    + 'id="sof-svp-ok">知道了</button></div></div>');
                const finish = () => {
                    wm.close();
                    resolve();
                };
                wm.box.querySelector('.modal-close').onclick = finish;
                wm.box.querySelector('#sof-svp-ok').onclick = finish;
            });
        }
    }

    return {open};
})();
