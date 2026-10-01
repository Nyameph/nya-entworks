/**
 * 填词页（填词工具设计 4 / 7 / 10）。
 *
 * 从原曲页模板列的「svp」标记进来，先看到**这首歌的填词项目列表**
 * （`song_lyric_fill`，一首原曲可以有多份，多对一），点每行末的「编辑」打开那一份：
 * 固化骨架（来自 svp）→ 分句 → 每句填新词 → 导出。
 *
 * **每句两行、一一对应**：
 * - **原词行**（上）：永远显示原词，不随填写变化。一格一个槽位，按所属轨着色；
 *   `-` 是延音，点它会把光标送到填写行对应的格子。
 * - **填写行**（下）：一格一个输入框，空着时灰显这一格的原词（占位符），敲进去的字
 *   就盖在原词的位置上。输完自动前进到下一格、**跳过 `-`**；一次敲进来多字（IME 整词 /
 *   粘贴）时，第一个留在本格，其余往后摊到后面的格子里。
 *
 * 三条与后端共用的约定（改一处必须改另一处）：
 * - **槽位**：`-` 是延音；`br` 是换气；`0` 是静音占位 —— 三者都画但样式独立（不自动填写，
 *   点进手填的值只进回填文本、不进 lrc）。填了字的延音 / 静音占位槽位，保存时 `slotType`
 *   就地改成 `HANZI`、`original` 仍是 `-` / `0` —— 这样「需填字数」算它、原词里也不会
 *   多一个 `-` / `0`。
 * - **喉塞音**：svp 里写 `'曾`（前缀撇号）的音符，后端解析时撇号剥掉、槽位带 `glottal: true`，
 *   原词 / 匹配 / 填写都按核心字走；导出回填文本时后端把 `'` 前缀拼回去（填「啊」→ `'啊`），
 *   lrc 歌词不含撇号。前端只负责显示标记。
 * - **填词存储**：`filled[i]` 是第 i 句的一行槽位值，与 `lines[i].slots` **等长**、
 *   下标一一对应，空串 = 没填。后端按这个下标把词回指到音符，不再二次切分。
 * - **分句**：后端优先按歌词文本对齐（歌词逐字 / 按词跟音符原词对上），对不上才按时间戳，
 *   没有歌词则按 `br` + 大空隙粗分。页面上的「断开 / 合并」只改槽位在句间的划分，
 *   保存时后端按槽位重算派生字段。
 *
 * 字数不符只是软提示（黄字），不拦保存 —— 填词本来就是先凑字再改。
 */
const SongFillPage = (() => {

    /** 轨色：按骨架里 tracks 的顺序取一圈固定色（图例在轨选择条上） */
    const PALETTE = ['#5b9dff', '#3bb26c', '#d6bc43', '#c96a4a', '#b37feb', '#4ac9c9'];

    const BLICK_PER_SECOND = 1411200000;

    /**
     * 「同一个音符」的 onset 容差（blick，0.06 秒）：合唱 / 双声部两组之间跨组联动、
     * 括号声部判组都用它。与后端 LyricFillAligner.UNISON_TOLERANCE_BLICK 同一口径。
     */
    const UNISON_TOLERANCE = Math.round(0.06 * BLICK_PER_SECOND);

    let originalId = null;
    let originalTitle = '';
    let pageHost = null;        // 当前页面根元素：对话框回调里 syncLine / markDirty 要用
    let tpl = null;             // resolve 返回的整个对象
    let selected = new Set();   // 勾选的轨序号
    let lines = [];             // 分句（前端可断开 / 合并 / 点开延音）
    let filled = [];            // 每句一行槽位值，与 lines[i].slots 等长
    let defaults = [];          // 每句一行默认词占位符（拼音模板匹配出的汉字），与 filled 等长
    let gaps = [];              // 每句一行视觉空位（歌词句内空格），true = 该格后画一个空位，与 filled 等长
    let brackets = [];          // 每句一行括号声部标记，true = 该格唱的是原歌词行尾括号里的那句，与 filled 等长
    let pinyin = false;         // 后端算好：默认词是拿 demo 歌词配出来的（文本对齐成功 + 模板里有拼音音符）—— 决定「没匹配到默认词的拼音格」要不要标红
    let polyIndex = { readings: {}, rare: {}, suggest: {} };  // 醒目/弱多音字 → 读音音节 + 音节 → 建议换字（软提示）
    let offsetInput = '';       // 手填偏移（秒）的输入框值，空 = 自动
    let nameDraft = '';         // 填词名输入框的草稿（draw 会重画页面，得自己存着）
    let noteMap = new Map();    // "轨:音符下标" → note，断开 / 合并后重算句首 onset 用
    let dirty = false;
    let composing = false;      // IME 组合期间不提交、不挪焦点
    let loadSeq = 0;            // 竞态：只认最后一次 load 的结果

    /**
     * 多音字的导出替换：「轨:音符」→ 单音常用字。点候选只记录在这里 ——
     * **歌词本体不动**，导出回填模板文本时才把该音符的字换成单音字。
     */
    let polySwap = {};
    let polyHideTimer = null;
    let autoTimer = null;       // 5 秒自动保存的定时器（编辑器一画好就起，回列表 / 换页后自然空转）
    let autoSaving = false;     // 自动保存进行中：不重入
    let rev = 0;                // 改动版本号：自动保存回来的那一版还对不对得上（改过就不清脏）
    let aiStatus = null;        // /api/song/fill/ai/status 的缓存（enabled 决定按钮显隐）

    /** 上次手动保存的存档点（localStorage，键按填词 id）。自动保存不留存档点，手动保存才留。 */
    const MANUAL_KEY = 'songfill:manual:';

    // ==================== 列表 ====================

    async function render(host, params) {
        pageHost = host;
        originalId = params && params.originalId ? params.originalId : null;
        originalTitle = (params && params.originalTitle) || '';
        // fillId 要配 originalId 才解析得了（resolve 按原曲定位模板）；缺一个就当没给，
        // 落到列表页，别拿半截参数去撞后端的必填校验
        const wantFill = params && params.fillId && originalId ? Number(params.fillId) : null;
        if (wantFill) {
            await load(host, {fillId: wantFill});
            return;
        }
        await listView(host);
    }

    /**
     * 填词项目列表：点每行末的「编辑」打开那一份。
     *
     * <p>{@code originalId} 有值就只列这一首原曲的（从原曲页 svp 点进来），没有就列全部
     * —— 左侧导航「填词」直接点进来是这条路，列表自己立得住。
     */
    async function listView(host) {
        const seq = ++loadSeq;
        tpl = null;
        pinyin = false;
        dirty = false;
        // 回到列表就把地址栏清干净：带着 fillId 刷新会直接又跳进编辑器，回不到列表
        history.replaceState(null, '', '#song-fill');
        host.innerHTML = UI.spinner('读取填词项目…');
        let list;
        try {
            list = await Api.get('/api/song/fill/list'
                + (originalId ? '?originalId=' + encodeURIComponent(originalId) : ''));
        } catch (e) {
            if (seq === loadSeq) {
                host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            }
            return;
        }
        if (seq !== loadSeq) {
            return;
        }
        host.innerHTML = listHtml(Array.isArray(list) ? list : []);
        bindList(host);
    }

    function listHtml(fills) {
        const scoped = !!originalId;
        const title = scoped
            ? UI.esc(originalTitle || (fills[0] && fills[0].originalName) || '填词')
            : '全部填词';
        const html = '<div class="card">'
            + '<div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">' + title
            + ' <span class="muted small">' + fills.length + ' 份</span></h2>'
            + '<div class="row">'
            + (scoped ? '<button id="l-back" class="btn-plain">← 返回全部填词</button>' : '')
            + (scoped ? '<button id="l-new" class="btn-primary">新建填词</button>' : '')
            + '<button id="l-reload" class="btn-plain">刷新</button>'
            + '</div></div>'
            + '<div class="muted small" style="margin-top:6px">'
            + (scoped
                ? '一首原曲可以有多份填词，各存各的骨架与填词。点「编辑」打开。'
                : '每一行是一份填词。点「编辑」打开；想新建就先去「原曲」页那一行的模板列点 '
                    + '<span class="mono">svp</span>（新建要读那一首的模板）。')
            + '</div>'
            + '</div>';
        if (!fills.length) {
            return html + '<div class="card">' + UI.empty(scoped
                ? '这首歌还没有填词项目，点右上角「新建填词」。'
                : '还没有任何填词项目。去「原曲」页点模板列里的 svp 标记开一份。') + '</div>';
        }
        const rows = fills.map(f => '<tr class="fill-row" data-id="' + f.id
            + '" data-oid="' + f.originalId + '"'
            + ' data-title="' + UI.esc(f.originalName || '') + '"'
            + ' data-name="' + UI.esc(f.name) + '">'
            + (scoped ? '' : '<td>' + UI.esc(f.originalName || '—') + '</td>')
            + '<td>' + UI.esc(f.name) + '</td>'
            + '<td class="mono">' + f.lineCount + ' 句</td>'
            + '<td class="mono">' + (f.filledCount
                ? f.filledCount + ' 句' : '<span class="muted">—</span>') + '</td>'
            + '<td class="muted small">' + UI.esc(fmtTime(f.updateTime)) + '</td>'
            + '<td><button type="button" class="btn-plain act-edit">编辑</button>'
            + '<button type="button" class="btn-plain act-del">删除</button></td>'
            + '</tr>').join('');
        return html + '<div class="card"><table class="fill-list"><thead><tr>'
            + (scoped ? '' : '<th>原曲</th>')
            + '<th>名称</th><th>句数</th><th>已填</th><th>更新时间</th><th></th>'
            + '</tr></thead><tbody>' + rows + '</tbody></table></div>';
    }

    function bindList(host) {
        const back = host.querySelector('#l-back');
        if (back) {
            // 从原曲页点进来时只列这一首；「返回」摘掉限定回填词主页面（列表还在，只是不限这一首）
            back.onclick = () => {
                originalId = null;
                originalTitle = '';
                listView(host);
            };
        }
        const fresh = host.querySelector('#l-new');
        if (fresh) {
            fresh.onclick = (ev) => createFill(host, ev.target);
        }
        host.querySelector('#l-reload').onclick = () => listView(host);
        host.querySelectorAll('.fill-row').forEach(tr => {
            const id = Number(tr.dataset.id);
            const oid = Number(tr.dataset.oid);
            const title = tr.dataset.title;
            // 行本身不再可点（2026-09-30：「编辑」独立成按钮，免得误点进编辑器）
            tr.querySelector('.act-edit').onclick = () => openFill(id, oid, title);
            tr.querySelector('.act-del').onclick = (ev) => {
                deleteFill(host, ev.target, id, tr.dataset.name);
            };
        });
    }

    /** 打开一份填词 = 换一次 hash，让 render 走编辑器分支 */
    function openFill(fillId, oid, title) {
        App.navigate('song-fill', {originalId: oid,
            originalTitle: title, fillId: fillId});
    }

    async function createFill(host, btn) {
        await UI.withBusy(btn, '新建中…', async () => {
            try {
                const data = await Api.post('/api/song/fill/create',
                    {originalId: Number(originalId)});
                UI.ok('已新建「' + data.fillName + '」');
                openFill(data.fillId, originalId, originalTitle);
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    async function deleteFill(host, btn, fillId, name) {
        const ok = await UI.confirm('删除填词项目「' + (name || '') + '」？'
            + '只删这一份的填词，磁盘上的 svp / 歌词不动。', {
            title: '删除填词', okText: '删除', danger: true
        });
        if (!ok) {
            return;
        }
        await UI.withBusy(btn, '删除中…', async () => {
            try {
                await Api.post('/api/song/fill/delete', {fillId});
                UI.ok('已删除');
                await backToFillList(host);
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    /**
     * 删除后回到该在的列表：这首歌还有别的填词就留在它的限定列表，
     * 一首都不剩了就摘掉原曲限定、直接回填词主页面 —— 空的限定列表是条死路。
     */
    async function backToFillList(host) {
        if (originalId) {
            try {
                const rest = await Api.get('/api/song/fill/list?originalId='
                    + encodeURIComponent(originalId));
                if (Array.isArray(rest) && !rest.length) {
                    originalId = null;
                    originalTitle = '';
                }
            } catch (e) {
                // 查询失败就按「还有填词」处理，原地刷新限定列表
            }
        }
        await listView(host);
    }

    // ==================== 编辑器 ====================

    /**
     * 拉骨架。opts.fillId 换项目，opts.trackIndices 换勾选，opts.offsetSeconds 手填偏移，
     * opts.realign 强制自动重算；都不传 = 直接用库里已固化的分句。
     */
    async function load(host, opts) {
        const seq = ++loadSeq;
        // 重新解析 / 换声部期间别让自动保存插进来：它带的是**旧骨架的**填写，
        // 与这次请求撞在一起谁先落库就说不准了。收尾的 draw() 会把定时器重新起上
        clearInterval(autoTimer);
        host.innerHTML = UI.spinner('解析 svp…');
        const query = new URLSearchParams({originalId: String(originalId)});
        const wantFill = opts.fillId === undefined ? tpl && tpl.fillId : opts.fillId;
        if (wantFill) {
            query.set('fillId', String(wantFill));
        }
        if (opts.trackIndices && opts.trackIndices.length) {
            query.set('trackIndices', opts.trackIndices.join(','));
        }
        if (opts.offsetSeconds !== undefined && opts.offsetSeconds !== null) {
            query.set('offsetSeconds', String(opts.offsetSeconds));
        }
        if (opts.realign) {
            query.set('realign', 'true');
        }
        let data;
        try {
            // AI 开关状态与骨架并行拉：enabled 决定右栏 AI tab 有没有内容（拿不到不挡填词）
            const [resolved] = await Promise.all([
                Api.get('/api/song/fill/resolve?' + query.toString()),
                Api.get('/api/song/fill/ai/status').then(
                    s => { aiStatus = s; }, () => { aiStatus = null; })
            ]);
            data = resolved;
        } catch (e) {
            if (seq === loadSeq) {
                host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            }
            return;
        }
        if (seq !== loadSeq) {
            return;
        }
        // 装骨架 + 画页面，任何一步炸了都把原因写出来。原先这里裸着：只在 console 里
        // 报一句 Uncaught，页面上留一个转不完的「解析 svp…」，看着像后端卡住了
        try {
            adopt(data);
            draw(host);
        } catch (e) {
            clearInterval(autoTimer);
            host.innerHTML = '<div class="hint hint-err">填词页渲染失败：' + UI.esc(e.message)
                + '（刷新重试；还不行就把这句话发我）</div>';
            return;
        }
        // 把上下文写回地址栏：app.js 的 route() 渲染完会清掉查询串，这里补回来，
        // 刷新才停在这一份填词上（replaceState 不触发 hashchange，不会重进 render）
        history.replaceState(null, '', '#song-fill?originalId='
            + encodeURIComponent(originalId) + '&fillId=' + encodeURIComponent(tpl.fillId));
    }

    /** 把后端返回的骨架装进页面状态（页面改的是副本，点「保存」才回传） */
    function adopt(data) {
        tpl = data;
        selected = new Set(data.trackIndices || []);
        pinyin = !!data.pinyin;
        lines = (data.lines || []).map(line => ({...line, slots: line.slots.slice()}));
        filled = (data.filled || []).map(row => (row || []).slice());
        while (filled.length < lines.length) {
            filled.push([]);
        }
        filled = filled.map((row, i) => padRow(row, lines[i].slots.length));
        defaults = (data.defaults || []).map(row => (row || []).slice());
        while (defaults.length < lines.length) {
            defaults.push([]);
        }
        defaults = defaults.map((row, i) => padRow(row, lines[i].slots.length));
        gaps = (data.gaps || []).map(row => (row || []).slice());
        while (gaps.length < lines.length) {
            gaps.push([]);
        }
        gaps = gaps.map((row, i) => padRow(row, lines[i].slots.length));
        brackets = (data.brackets || []).map(row => (row || []).slice());
        while (brackets.length < lines.length) {
            brackets.push([]);
        }
        brackets = brackets.map((row, i) => padRow(row, lines[i].slots.length));
        polyIndex = data.polyphoneIndex || { readings: {}, rare: {}, suggest: {} };
        polySwap = {};
        offsetInput = data.lrcOffset == null ? '' : String(round2(data.lrcOffset));
        nameDraft = data.fillName || '';
        noteMap = new Map();
        for (const track of data.tracks || []) {
            track.notes.forEach((note, i) => noteMap.set(key(track.trackIndex, i), note));
        }
        dirty = false;
    }

    function draw(host) {
        // 重画前把两个自由输入框的草稿收起来：断开 / 合并 / 换声部都会重画，
        // 不存的话用户刚敲的名字和偏移会被打回去
        const nameBox = host.querySelector('#f-name');
        if (nameBox) {
            nameDraft = nameBox.value;
        }
        const offsetBox = host.querySelector('#f-offset');
        if (offsetBox) {
            offsetInput = offsetBox.value;
        }
        // 右栏（韵脚词典 / AI）是这次重画的一部分，DOM 被换掉之后两个 tab 的内容都得重建；
        // 折叠状态、当前 tab、AI 方案与已生成结果都留在模块变量里，重建后照原样摆回去
        rhymeRendered = false;
        rhymeStale = false;
        aiRendered = false;
        aiPlanSeq++;   // 在飞的方案请求作废（挂的是旧 DOM）
        lastCell = null;
        host.innerHTML = '<div class="fill-page"><div class="fill-main">'
            + headerHtml() + tracksHtml() + linesHtml()
            + '</div>' + sideHtml() + '</div>';
        bind(host);
        bindSide(host);
        applySide(host);
        // 每 5 秒把改动写库（有改动才发）；手动保存留存档点，见 save / restoreManual
        startAutoSave(host);
    }

    function headerHtml() {
        const lyric = tpl.lyricFileName
            ? UI.esc(tpl.lyricFileName)
            : '<span class="muted">没有可用歌词，按 br + 空隙粗分</span>';
        return '<div class="card">'
            + '<div class="row" style="justify-content:space-between">'
            + '<div class="row">'
            + '<button id="f-close" class="btn-plain">关闭</button>'
            + '<h2 style="margin:0">' + UI.esc(tpl.originalName || '')
            + (tpl.artist ? ' <span class="muted small">' + UI.esc(tpl.artist) + '</span>' : '')
            + '</h2>'
            + '</div>'
            + '<div class="row">'
            + '<button id="f-save" class="btn-primary">保存</button>'
            + '<button id="f-restore" class="btn-plain" hidden>恢复上次手动保存</button>'
            + '<button id="f-export">导出</button>'
            + '<button id="f-reparse" class="btn-plain">重新解析 svp</button>'
            + '<button id="f-delete" class="btn-danger">删除</button>'
            + '</div></div>'
            + '<div class="muted small mono fill-path">' + UI.esc(tpl.svpPath || '') + '</div>'
            + '<div class="row fill-projects">'
            + '<label class="fill-name-label"><span class="muted small">填词名</span>'
            + '<input type="text" id="f-name" class="fill-name-title" placeholder="填词名"'
            + ' value="' + UI.esc(nameDraft) + '"></label>'
            + '<span class="muted small mono">#' + UI.esc(tpl.fillId) + '</span>'
            + '</div>'
            + '<div class="row fill-align">'
            + '<span class="muted small">分句歌词：' + lyric + '</span>'
            + (tpl.lyricNotice
                ? '<span class="small fill-notice" title="分句质量提示：这一句由后端按参照歌词与模板'
                    + '文本的对齐情况算好，只提示、不改分句">⚠ ' + UI.esc(tpl.lyricNotice) + '</span>'
                : '')
            + '<button id="f-realign" class="btn-plain">重新分句</button>'
            + '<button id="f-batch" class="btn-plain">批量填词</button>'
            + '<button id="f-clear" class="btn-plain">清空填词</button>'
            + '<label class="small">偏移(秒) <input type="number" id="f-offset" step="0.01"'
            + ' value="' + UI.esc(offsetInput) + '" placeholder="自动" style="width:90px"></label>'
            + '<button id="f-time" class="btn-plain">按时间戳分句</button>'
            + '<span class="muted small" id="f-status"></span>'
            + '</div>'
            + helpHtml()

            + '</div>';
    }

    /**
     * 使用说明：**默认收起**（一次看完就记住的东西，天天摊在屏幕上只是占地方），点标题展开。
     * 一条一句，别写成一大段 —— 找「退格删到上一句」这种细节时按行扫过去就行。
     */
    function helpHtml() {
        const items = [
            '<b>更详细的一版（带界面截图与标注）在左下角「使用说明」里</b> —— '
                + '按页面讲，断句 / 加空格 / 多音字那三件不显眼的设计在那儿有图；'
                + '这里是一句话速查。',
            '每个声部是「<b>原词行 + 填写行</b>」上下两排、逐格一一对应：'
                + '上排是<b>灰底、没有框</b>的参考行（永远显示原词，格下沿那条细色线是轨号），'
                + '下排是<b>能写字的输入框</b>（你填进去的字是蓝底蓝框）——两排颜色不同、列却严格对齐。'
                + '一格一个字（英文一个词）、<b>限字数</b>，输完自动跳下一格。',
            '<b>按住鼠标在一行填写格上拖动 = 选中一段字</b>（像选中一行文本）：选中后直接打字整段替换、'
                + 'Ctrl+C 复制、退格整段清空 —— 选区只在<b>这一行（声部）</b>里，不会把别的声部的格一起圈进来。',
            '<b>退格</b>在空格上跳到上一格连续删，行首再退跳到上一句行尾；'
                + '<b>回车跳到下一句</b>（Shift+回车上一句）。',
            '<b>合唱 / 和声轨</b>：音符相同的几轨并在同一句、显示成几组，只填一组另一组自动抄值，'
                + '导出只算一份；重叠但唱词不同的轨各自成句。'
                + '原歌词写成「A（B）」双声部时也合成一句，括号声部那行的行头标 <span class="mono">（轨 #N）</span>、'
                + '句头按 <span class="mono">A（B）</span> 显示，导出 lrc 同样写回括号。',
            '填了<b>多音字</b>：格子悬浮（或点进去）时浮出建议单音字（每个读音一个），点选 = 只改'
                + '<b>导出回填模板</b>用哪个字，歌词本体不动；选中后那个字常显在格子下方，再点一次取消。',
            '整段替换用「<b>批量填词</b>」（可勾「重新分行」：先把现有分句整段合并、旧视觉空位清空，'
                + '再完全按文本重排 —— 换行 = 句界、行内空格 = 视觉空位）。不勾时<b>一句一行</b>，'
                + '某行字数正好等于后面连续几句的格子数之和就拆进那几句、连续几行正好等于一句的格子数'
                + '就并进这一句（差一个字不拆不并）。批量填词后，合唱副本那一行会<b>整行对齐</b>成主行'
                + '的样子（上次填的残留一并盖掉）。',
            '要<b>整首重填</b>：点「<b>清空填词</b>」把这一份的全部格子清空（先确认；只清页面上的内容，'
                + '点「保存」才落库，没保存就刷新页面能撤回）。',
            '分句优先按歌词文本对齐（不受时间戳漂移影响），对不上 / 缺歌词时按时间戳或 br + 空隙分；'
                + '参照歌词与模板文本明显对不上时，句头那行会挂一句提示（<b>只提示、不改分句</b>）——'
                + '多半是这一原曲的「样例歌词」配错了文件，去原曲页核对。',
            '<b>空位（句内空格）</b>：原词行每个格子<b>上方那条 10px 的带子</b>就是加空位的地方 ——'
                + '鼠标停在两个字中间，浮出 <span class="mono">+</span> 点一下就插一个空格；'
                + '已经有的空位常显 <span class="mono">␣</span>，再点一下取消（点空列本身也行）。'
                + '<b>首字之前 / 末字之后没有</b>，且延音格（<span class="mono">-</span>）前面点不出空位 ——'
                + '空位一律记在延音链之后（与导出 lrc 的空格位置同一个口径）。'
                + '加过的空位跟着自动保存写进库，导出 lrc 时还原成空格；点「重新分句」会回到后端算出的那一份。',
            '模板是拼音时，按 demo 歌词匹配出的汉字做成灰显默认词；没匹配到的拼音格标红提示，'
                + '导出时没填到的音符优先回填匹配出的汉字。',
            '<b>每 5 秒自动保存一次</b>（有改动才写库，不打断输入）；「保存」是<b>存档点</b>，'
                + '手动保存过之后右上角会出现「恢复上次手动保存」，随时能退回那一版。',
            '<b>右栏（韵脚词典 / AI 填词）</b>：默认<b>收起</b>，点右边缘那条竖排的'
                + '「韵脚词典 / AI」打开，里面两个页签。<b>切页签、收起展开都不会动填写区</b>'
                + '（不会丢焦点、不会跳滚动）。',
            '<b>词典页签</b>：点任意填词格就以<b>这格的字</b>为锚查同韵的字 / 词候选'
                + '（没填过就用这格的原词），换格子就换锚重查；锚框也能自己改（汉字 / 韵母 / 韵部名），'
                + '改过之后点格子不再跟随，<b>把锚框清空</b>就回到跟随。'
                + '点候选 = 填进<b>最后点过的那个填词格</b>（一次多字按格摊开、超出会拦下来）；'
                + '还没点过格子时会复制到剪贴板并提示先点一个填词格。',
            ...(aiStatus && aiStatus.enabled ? [
                '<b>AI 填词页签</b>：「韵」列逐句可改（默认取该句的默认韵），'
                    + '三个「…改为」批量改（勾选项 / 主韵 / 全篇统一），主韵按后端算出的'
                    + '「本曲占多数的韵」——没有多数时那个下拉是灰的，别自己数。',
                '<b>AI 生成的是草稿</b>：结果写进「填词」列，并标出「韵脚」「字数匹配」'
                    + '（✓ 合格 / ⚠ 警告 / ✗ 不合格，鼠标停上去看原因），'
                    + '点「应用」才进格子、只应用勾选的行 —— 走的是批量填词同一条通道，'
                    + '自动保存照常带走。',
                '第一次点「生成」要等几十秒：本机模型冷启动（Ollama 闲置 5 分钟会卸载模型），'
                    + '不是卡死；想常驻可设环境变量 <span class="mono">OLLAMA_KEEP_ALIVE=30m</span>。'
            ] : [])
        ];
        return '<details class="hint fill-help"><summary>使用说明（点开看）</summary>'
            + '<ul>' + items.map(t => '<li>' + t + '</li>').join('') + '</ul></details>';
    }

    function tracksHtml() {
        const items = (tpl.tracks || []).map(track => {
            const on = selected.has(track.trackIndex);
            const fillable = track.notes.filter(isFillable).length;
            return '<label class="fill-track' + (on ? ' on' : '') + '">'
                + '<input type="checkbox" class="f-track" value="' + track.trackIndex + '"'
                + (on ? ' checked' : '') + '>'
                + '<span class="fill-swatch" style="background:' + colorOf(track.trackIndex) + '"></span>'
                + '<span>#' + track.trackIndex + ' ' + UI.esc(track.trackName || '未命名') + '</span>'
                + '<span class="muted small">' + track.notes.length + ' 音符 / '
                + fillable + ' 可填</span></label>';
        }).join('');
        return '<div class="card">'
            + '<div class="row fill-tracks">' + (items || '<span class="muted small">没有歌唱轨</span>')
            + '</div>'
            + '<div class="muted small" style="margin-top:6px">勾选要一起填的声部（同唱 / 和声都勾上，'
            + '它们按时间合并成一条时间线）。换声部会按新时间线重跑分句 —— 先保存再换，'
            + '不然页面上未保存的改动会丢。</div>'
            + '</div>';
    }

    function linesHtml() {
        if (!lines.length) {
            return '<div class="card">' + UI.empty('勾选的轨上没有音符。') + '</div>';
        }
        return '<div class="card"><div class="fill-lines">'
            + lines.map(lineHtml).join('')
            + '</div></div>';
    }


    /**
     * 一句：每个声部组渲染「原词格子行 + 整排填写格」，两组上下同列严格对位；
     * 合唱只填一组，另一组空格自动抄值（syncVoiceCopies），导出去重只算一份。
     * 句头：句号 + 时间 + <b>小节号</b>（4/4，按模板 bpm）+ 已填 / 需填 + 并入按钮。
     */
    function lineHtml(line, i) {
        const row = filled[i] || [];
        const groups = lineGroups(line);
        const done = countDone(line, row);
        const voices = groups.map((slots, g) => voiceHtml(line, i, slots, g, row, groups.length));
        return '<div class="fill-line" data-i="' + i + '">'
            + '<div class="fill-line-head">'
            + '<span class="muted small">第 ' + (i + 1) + ' 句</span>'
            + '<span class="mono small">' + timeLabel(line.startOnset) + '</span>'
            + '<span class="mono small muted">' + barLabel(line.startOnset) + '</span>'
            + '<span class="fill-line-lyric">' + UI.esc(lineLyric(i)) + '</span>'
            + '<span class="fill-count' + (done !== line.needCount ? ' fill-bad' : '') + '">'
            + done + ' / ' + line.needCount + '</span>'
            + (i > 0 ? '<button type="button" class="btn-plain act-merge"'
                + ' title="把这一句并到上一句">↑ 并入上一句</button>' : '')
            + '</div>'
            + voices.join('')
            + '</div>';
    }

    /**
     * 句内声部组：groups 字段（后端按合唱 / 和声分好组）→ 每组的槽位下标数组；旧数据整句一组。
     *
     * <p>组的先后 = <b>工程里的轨序</b>（组里最小的轨号，后端 {@code groupIdsByTrack} /
     * {@code groupIndexes} 同一口径）：声部行的上下顺序、括号标签里的轨号、导出的文本顺序
     * 三处一致 —— 不能按「组号首现序」，补进来的 br / 延音会让它跳（实测《栖凰》「谯鼓响」
     * 那句的标签排成 11 / 7 / 12）。
     */
    function lineGroups(line) {
        const ids = line.groups && line.groups.length === line.slots.length
            ? line.groups : line.slots.map(() => 0);
        const map = new Map();
        ids.forEach((g, si) => {
            if (!map.has(g)) map.set(g, []);
            map.get(g).push(si);
        });
        const minTrack = slots => slots.reduce(
            (min, si) => Math.min(min, line.slots[si].trackIndex), Infinity);
        return [...map.entries()]
            .sort((a, b) => minTrack(a[1]) - minTrack(b[1]) || a[0] - b[0])
            .map(entry => entry[1]);
    }

    /** 一组（声部）：轨标签 + 原词格子行 + 填写格子行，两排同列严格对位。 */
    function voiceHtml(line, i, slots, g, row, groupCount) {
        const gapRow = gaps[i] || [];
        const cols = [];
        slots.forEach((si, at) => {
            cols.push({kind: 'slot', cell: {slot: line.slots[si], si}, first: at === 0, band: ''});
            if (gapRow[si]) {
                cols.push({kind: 'gap', si, band: ''});
            }
        });
        // 空位带（格子行上方那 10px）：列与列之间那条**缝**就是一个「可加空位」的地方，
        // 热区挂在缝右侧那一列里（left:-5px 正好压在缝上）。两端的缝（首格之前、末格之后）
        // 没有右邻 / 没有左邻，自然没有热区 —— 空位只在字与字之间。
        // 左边是空格列时不给「+」（那个空位已经有了，消除点就在它自己身上）。
        for (let p = 1; p < cols.length; p++) {
            const si = cols[p - 1].cell ? cols[p - 1].cell.si : -1;
            if (si < 0) {
                continue;
            }
            if (cols[p].kind === 'gap') {
                cols[p].band = gapHitHtml(si, true);
            } else if (chainTail(line, si) === si) {
                cols[p].band = gapHitHtml(si, false);
            }
        }
        const template = cols.map(c => c.kind === 'gap' ? '1ch' : 'minmax(2ch,max-content)').join(' ');
        const bracket = bracketGroup(i, slots);
        const tag = groupCount > 1
            ? '<div class="fill-voice-head"><span class="fill-voice-tag">'
                + UI.esc(voiceTag(line, slots, bracket))
                + '</span><span class="muted small">'
                + (bracket ? '原歌词括号里的声部' : '没填的格自动抄另一组') + '</span></div>'
            : '';
        return '<div class="fill-voice' + (bracketGroup(i, slots) ? ' bracket' : '') + '" data-g="' + g + '">'
            + tag
            + '<div class="fill-grid" style="grid-template-columns:' + template + '">'
            + cols.map(c => c.kind === 'slot'
                ? slotHtml(c.cell, c.first, row, c.band) : gapHtml(c.band, c.si)).join('')
            + cols.map(c => c.kind === 'slot'
                ? cellHtml(c.cell, row, i) : gapHtml('', null)).join('')
            + '</div>'
            + '</div>';
    }

    /** 组标签：这组涉及的轨（合唱 = 多轨并在一行），轨号升序 = 工程里的顺序；括号声部加括号。 */
    function voiceTag(line, slots, bracket) {
        const seen = [];
        slots.forEach(si => {
            const t = line.slots[si].trackIndex;
            if (!seen.includes(t)) seen.push(t);
        });
        const text = '轨 ' + seen.sort((a, b) => a - b).map(t => '#' + t).join('+');
        return bracket ? '（' + text + '）' : text;
    }

    /** 这一组是不是括号声部（原歌词「A（B）」里括号那句）：组内任一格被标了就算。 */
    function bracketGroup(i, slots) {
        const row = brackets[i] || [];
        return slots.some(si => !!row[si]);
    }

    /**
     * 视觉空位：歌词句内的空格，不占槽位、不能填、不断句，只是一个空列。
     *
     * <p>{@code band} 是空位带上属于这一列的热区（见 {@link gapHitHtml}）；{@code si} 非空时
     * **原词行这一半**本身也可点（点一下消除这个空位）—— 带子上的 {@code ␣} 只有 10px，
     * 空列本身是更好按的靶子。填写行那一半（{@code si} 传 null）不接事件：它不是「字」，
     * 别在打字时误碰。
     *
     * <p><b>点位必须带上真的下标</b>：点了格的 `[data-gap-si]` 一律走
     * {@code Number(dataset.gapSi)}，写个空属性就成了 0 —— 点空白处会去动第 0 格。
     */
    function gapHtml(band, si) {
        const click = si !== null && si !== undefined;
        return '<span class="fill-gap' + (click ? ' click' : '') + '"'
            + (click ? ' data-gap-si="' + si + '"' : '')
            + ' aria-hidden="true">' + (band || '') + '</span>';
    }

    /**
     * 空位带上的一个热区：{@code on} 为真 = 这里已经有一个空位（常驻 {@code ␣}，点一下消除），
     * 否则是「可以在这里加一个空位」（平时透明，悬停才显 {@code +}）。两者都通到
     * {@link toggleGap}，{@code si} 是**边界左侧那一格**的下标。
     */
    function gapHitHtml(si, on) {
        return '<button type="button" class="fill-gap-hit' + (on ? ' on' : '') + '"'
            + ' data-gap-si="' + si + '" tabindex="-1"'
            + ' title="' + (on ? '点一下删掉这个空位' : '点一下在这里加一个空位（导出 lrc 会多一个空格）')
            + '">' + (on ? '␣' : '+') + '</button>';
    }

    /** 原词行的一格：永远显示原词。`-` / `br` / `0` 可点（光标落到填写行对应格子），格间竖条可断开 */
    function slotHtml(cell, isFirst, row, band) {
        const slot = cell.slot;
        const dash = isDash(slot);
        const breath = isBreath(slot);
        const zero = isZero(slot);
        const split = !dash && !isFirst
            ? '<button type="button" class="fill-split" data-si="' + cell.si
            + '" title="在这里断开成两句">|</button>'
            : '';
        let title = '轨 #' + slot.trackIndex + ' · 原词 ' + (slot.original || '');
        if (breath) {
            title += ' · 换气：不自动填写，可点进手填（只进回填文本，不进歌词）';
        } else if (zero) {
            title += ' · 静音占位：不自动填写，可点进手填（只进回填文本，不进歌词）';
        } else if (slot.glottal) {
            title += " · 喉塞音：导出回填文本自动带 ' 前缀，歌词不带";
        }
        return '<span class="fill-slot' + (dash ? ' dash' : '') + (breath ? ' breath' : '')
            + (zero ? ' zero' : '')
            + (slot.glottal ? ' glottal' : '')
            + ((row[cell.si] || '').trim() ? ' filled' : '')
            + '" data-si="' + cell.si + '" style="--c:' + colorOf(slot.trackIndex) + '"'
            + ' title="' + UI.esc(title) + '">'
            + split + (band || '') + UI.esc(slot.original || '') + '</span>';
    }

    /**
     * 填写行的一格：一格一字（英文词），与原词行同列严格对位，限制字数。
     * 空值灰显默认词（拼音模板 = 匹配出的汉字，否则原词）。
     * 拼音模板下「可填但没匹配到汉字」的格标 unmatched（填词后消除）；
     * 填了多音字时候选单音字常显在字下，点选只记导出替换、歌词不动。
     */
    function cellHtml(cell, row, i) {
        const slot = cell.slot;
        const value = row[cell.si] || '';
        const dash = isDash(slot);
        const def = (defaults[i] && defaults[i][cell.si]) || '';
        const hint = def || slot.original || '';
        const poly = polyMark(slot, value);
        const swap = polySwapped(slot);
        const unmatched = isUnmatchedPinyin(slot, def, value);
        const input = '<input type="text" class="fill-cell' + (dash && !value ? ' dash' : '')
            + (isBreath(slot) && !value ? ' breath' : '')
            + (isZero(slot) && !value ? ' zero' : '')
            + (slot.glottal ? ' glottal' : '')
            + (value.trim() ? ' on' : '') + (poly ? (poly.weak ? ' poly-weak' : ' poly') : '')
            + (swap ? ' swapped' : '')
            + (unmatched ? ' unmatched' : '')
            + '" data-si="' + cell.si + '" size="1"'
            + ' style="--c:' + colorOf(slot.trackIndex) + '"'
            + ' value="' + UI.esc(value) + '"'
            + ' placeholder="' + UI.esc(hint) + '"'
            + ' title="' + UI.esc(cellTip(slot, def, value, poly, swap)) + '"'
            + ' autocomplete="off" spellcheck="false">';
        // 已选回填替换：字下只常显选中的那一个字；未选时不占字下空间（候选走悬浮窗）
        const sug = swap
            ? '<b class="cell-poly-chip on" data-si="' + cell.si + '" data-char="' + UI.esc(swap)
                + '" title="导出回填用「' + UI.esc(swap) + '」，歌词不变；再点取消">'
                + UI.esc(swap) + '</b>'
            : '';
        return '<span class="fill-cell-wrap' + (slot.glottal ? ' glottal' : '') + '">' + input
            + '<span class="cell-poly-suggest">' + sug + '</span></span>';
    }

    function polyBox() {
        let box = document.getElementById('f-poly-chips');
        if (!box) {
            box = document.createElement('div');
            box.id = 'f-poly-chips';
            document.body.appendChild(box);
        }
        return box;
    }

    function hidePolyChips() {
        const box = document.getElementById('f-poly-chips');
        if (box) {
            box.style.display = 'none';
        }
    }

    /**
     * 格子下方浮出多音字提醒与建议换字条（**选中前**看候选的地方：悬浮或聚焦这一格就开）。
     * 候选 = 与本格音符读音相同、且只有这一个读音的单音常用字（后端 SUGGEST 字典下发），
     * 每个读音只给一个。点候选 = 记录导出回填替换（歌词不动），选中后字下常显那一个字、
     * 本浮条高亮并可「还原」。
     *
     * <p>弱多音字（{@code hint.weak}）没有候选，浮条自然不弹 —— 它只是「这个字另有生僻音」，
     * 没有该换的字，弹一个空框反而吵。
     */
    function updatePolyChips(cell, slot) {
        const value = (cell.value || '').trim();
        const hint = polyMark(slot, value);
        const swapKey = swapKeyOf(slot);
        const swapped = polySwap[swapKey];
        const chars = hint ? polySuggestChars(value, hint) : [];
        if (!chars.length && !swapped) {
            hidePolyChips();
            return;
        }
        const box = polyBox();
        // 没候选时不画「建议换成：」那句空话（弱多音字 + 已选过替换字才会走到）
        let html = (chars.length
            ? '<span class="poly-tip">多音字'
                + (hint.anchor ? '（本格读 ' + UI.esc(hint.anchor) + '）' : '')
                + '，回填建议换成：</span>'
                + chars.map(c => '<button type="button" class="poly-chip'
                    + (swapped === c.ch ? ' on' : '')
                    + '" data-char="' + UI.esc(c.ch) + '"'
                    + (c.reading ? ' title="读 ' + UI.esc(c.reading) + '，只有一个读音"' : '')
                    + '>' + UI.esc(c.ch) + '</button>').join('')
            : '');
        if (swapped) {
            html += '<span class="poly-tip poly-note">回填用「' + UI.esc(swapped) + '」，歌词不变</span>'
                + '<button type="button" class="poly-chip poly-undo" data-char="">还原</button>';
        }
        box.innerHTML = html;
        box.style.display = 'flex';
        keepPolyBoxOpen();
        const r = cell.getBoundingClientRect();
        box.style.left = Math.max(4, Math.round(r.left + window.scrollX)) + 'px';
        box.style.top = Math.round(r.bottom + window.scrollY + 3) + 'px';
        // mousedown + preventDefault：不让格子失焦（focusout 的延迟收起就轮不到触发）。
        // 点候选 = 记录导出替换（歌词不动）；点「还原」= 撤掉替换。之后刷新浮条与格子标记
        box.onmousedown = (ev) => {
            const chip = ev.target.closest('.poly-chip');
            if (!chip) {
                return;
            }
            ev.preventDefault();
            const lineEl = cell.closest('.fill-line');
            toggleSwap(pageHost, Number(lineEl.dataset.i), Number(cell.dataset.si),
                chip.dataset.char);
        };
    }

    /** 鼠标在浮条上 = 别收（从格子挪到浮条上时，格子的延迟收起要被打断）。 */
    function keepPolyBoxOpen() {
        const box = document.getElementById('f-poly-chips');
        if (!box || box.dataset.keep) {
            return;
        }
        box.dataset.keep = '1';
        box.addEventListener('mouseenter', () => clearTimeout(polyHideTimer));
        box.addEventListener('mouseleave', () => {
            clearTimeout(polyHideTimer);
            polyHideTimer = setTimeout(hidePolyChips, 160);
        });
    }

    /**
     * 填的字是多音字时返回提示（否则 null）：{readings: [音节…], anchor: 音节|null, weak: bool}。
     * **以填入字为准**：候选读音 = 填入字的全部读音（无声调）；锚 = 读音与音符原词拼音
     * 的交集（能消歧就只建议那一组），对不上（原词「尖」填了「重」）就以填入字自己的
     * 全部读音分组给建议。
     *
     * <p>分两级（都是后端 {@code polyphoneIndex} 下发好的，前端不判常用度）：
     * <ul>
     *   <li>`polyIndex.readings` 命中 = **醒目**：有两个以上真会读到的读音（如「重」zhòng/chóng），
     *       标醒目虚线 + 给建议换字。</li>
     *   <li>`polyIndex.rare` 命中 = **弱**：只有一个常用读音，`readings` 里是它的古音 / 冷门音
     *       （如「他」tuó、「单」chán/shàn），几乎读不错 —— 只标一道更暗的虚线，`anchor` 恒为
     *       null（没有可锚的常用音，也就没有该换的字）。</li>
     * </ul>
     */
    function polyMark(slot, value) {
        if (!value) {
            return null;
        }
        const strong = (polyIndex.readings || {})[value];
        if (strong && strong.length) {
            const sound = anchorSyllable(slot);
            const anchor = sound && strong.indexOf(sound) >= 0 ? sound : null;
            return {readings: strong, anchor: anchor, weak: false};
        }
        const weak = (polyIndex.rare || {})[value];
        if (weak && weak.length) {
            return {readings: weak, anchor: null, weak: true};
        }
        return null;
    }

    /** 槽位的锚音节：原词是纯拼音（含 ü）就归一化返回，其余（汉字 / 延音 / 换气）返回 null */
    function anchorSyllable(slot) {
        const orig = slot.original || '';
        if (!/^[a-zA-ZüÜ]+$/.test(orig)) {
            return null;
        }
        return orig.toLowerCase().replace(/ü/g, 'v').replace(/[^a-z]/g, '');
    }

    /** 多音字的建议换字文本：有锚只给锚音节一组，没锚按各读音分组；不含已填的字。弱多音字不给建议 */
    function polySuggest(value, hint) {
        if (!hint || hint.weak) {
            return '';
        }
        const sugs = polyIndex.suggest || {};
        const keys = (hint.anchor && sugs[hint.anchor]) ? [hint.anchor] : (hint.readings || []);
        return keys
            .map(s => (sugs[s] || []).filter(ch => ch !== value))
            .map((chars, i) => chars.length ? (hint.anchor ? '' : keys[i] + '：') + chars.join('、') : '')
            .filter(s => s)
            .join('；');
    }

    /**
     * 建议换字候选 [{ch, reading}]：有锚只给锚音节一组，没锚按各读音平铺。
     * **每个读音只给一个**（字典里同音字列得长，铺出来一长串反而挑不动），也不含已填的字。
     *
     * <p>弱多音字直接返回空 —— 拿它的生僻音去建议换字是反的（会把「他」建议成「拖」）。
     */
    function polySuggestChars(value, hint) {
        if (!hint || hint.weak) {
            return [];
        }
        const sugs = polyIndex.suggest || {};
        const keys = (hint.anchor && sugs[hint.anchor]) ? [hint.anchor] : (hint.readings || []);
        const chars = [];
        const used = new Set([value]);
        for (const s of keys) {
            const ch = (sugs[s] || []).find(c => !used.has(c));
            if (!ch) {
                continue;
            }
            used.add(ch);
            chars.push({ch: ch, reading: hint.anchor ? '' : s});
        }
        return chars;
    }

    /**
     * blick → 第几小节（拍号按 4/4；曲速取模板的 bpm，没有就不显示）。
     * 一小节 = 4 拍 × 60/bpm 秒 = 240/bpm 秒；小节号从 1 起。
     */
    function barLabel(onset) {
        const bpm = tpl && Number(tpl.bpm) > 0 ? Number(tpl.bpm) : 120;
        if (onset == null) {
            return '';
        }
        const sec = Math.max(0, onset / (BLICK_PER_SECOND * bpm / 120));
        return '第 ' + (Math.floor(sec / (240 / bpm)) + 1) + ' 小节';
    }



    /** 多音字替换的键：轨号:音符下标。 */
    function swapKeyOf(slot) {
        return slot.trackIndex + ':' + slot.noteIndex;
    }

    /** 该槽的导出替换字（没设过返回 ''）。 */
    function polySwapped(slot) {
        return polySwap[swapKeyOf(slot)] || '';
    }

    // ==================== 交互 ====================


    // ==================== 跨格拖选（像选中一行文本的多个字）====================

    /** 当前拖选：{句号, 组号, from, to（槽位下标，含两端）, live=按住中}；null = 无选区 */
    let sel = null;

    /**
     * 选区盖住的槽位下标：只取**这一组自己那一行**里 from..to 之间的格（含两端），按组内顺序。
     *
     * <p>不能照平坦下标扫：多声部句的槽位在平坦数组里是**按 onset 逐拍交错**的
     * （groups = [0,1,0,1,…]），平坦区间会把另一条轨夹在中间的格子一起圈进来 —— 症状是
     * 「框选第一行、两行都高亮」，而把那段交错着取出来的字粘回去，顺序整个乱掉
     * （2026-09-14 气泡少女第 44 句踩过）。from / to 都在本组里；有一个不在就返回空。
     */
    function selSlots(line, g, from, to) {
        if (!line) {
            return [];
        }
        const row = lineGroups(line)[g] || [];
        const p = row.indexOf(from);
        const q = row.indexOf(to);
        return p < 0 || q < 0 ? [] : row.slice(Math.min(p, q), Math.max(p, q) + 1);
    }

    /** 这一格属于哪一行（声部组号，句内局部号）；不在声部行里返回 -1。 */
    function groupOf(cell) {
        const voice = cell && cell.closest ? cell.closest('.fill-voice') : null;
        return voice ? Number(voice.dataset.g) : -1;
    }

    /** 重画选区高亮：清掉全部 .sel，再给本行 [from..to] 的格加 .sel。 */
    function paintSel(host) {
        host.querySelectorAll('.fill-cell.sel').forEach(el => el.classList.remove('sel'));
        if (!sel) {
            return;
        }
        selSlots(lines[sel.i], sel.g, sel.from, sel.to).forEach(si => {
            const el = cellAt(host, sel.i, si);
            if (el) {
                el.classList.add('sel');
            }
        });
    }

    /** 清除选区（高亮与状态一起收）。 */
    function clearSel(host) {
        sel = null;
        if (host) {
            host.querySelectorAll('.fill-cell.sel').forEach(el => el.classList.remove('sel'));
        }
    }

    function bind(host) {
        const list = host.querySelector('.fill-lines');
        if (list) {
            // 输入：一格一个字。IME 组合中不处理（等组合结束那次 input），
            // 也绝不重画整行 DOM —— 换了 input 元素，中文输入法的组合会被打断
            list.addEventListener('input', (ev) => {
                const cell = ev.target.closest('.fill-cell');
                if (!cell || composing || ev.isComposing) {
                    return;
                }
                const i = Number(cell.closest('.fill-line').dataset.i);
                commit(host, i, Number(cell.dataset.si), cell);
                // commit 可能已把焦点挪到下一格（那边 focusin 自己会刷建议条）；
                // 还停在本格（多字粘贴）才就地刷新
                if (document.activeElement === cell) {
                    updatePolyChips(cell, lines[i].slots[Number(cell.dataset.si)]);
                }
            });

            // 多音字建议条：格子拿到焦点就按当前值浮出 / 收起
            list.addEventListener('focusin', (ev) => {
                const cell = ev.target.closest('.fill-cell');
                if (!cell) {
                    return;
                }
                clearTimeout(polyHideTimer);
                const i = Number(cell.closest('.fill-line').dataset.i);
                updatePolyChips(cell, lines[i].slots[Number(cell.dataset.si)]);
                // 记下「最后碰过的填词格」给右栏用：**只记下标**（i / si），不存 DOM 引用 ——
                // 页面一重画元素就换了，存引用等于指着已经不在文档里的节点填词
                lastCell = {i, si: Number(cell.dataset.si)};
                followAnchor(lastCell.i, lastCell.si);
            });

            // 多音字提醒条：选中前把鼠标停在格子上也能打开（不用先点进去），
            // 移开就延后收起 —— 挪到浮条上（浮条自己也是延后收起）接着点候选
            list.addEventListener('mouseover', (ev) => {
                const cell = ev.target.closest('.fill-cell');
                if (!cell) {
                    return;
                }
                const i = Number(cell.closest('.fill-line').dataset.i);
                const slot = lines[i] && lines[i].slots[Number(cell.dataset.si)];
                if (slot && (cell.value || '').trim() && polyMark(slot, cell.value.trim())) {
                    clearTimeout(polyHideTimer);
                    updatePolyChips(cell, slot);
                }
            });
            list.addEventListener('mouseout', (ev) => {
                if (!ev.target.closest('.fill-cell')) {
                    return;
                }
                clearTimeout(polyHideTimer);
                polyHideTimer = setTimeout(hidePolyChips, 160);
            });

            // 跨格拖选：按住鼠标在一行的填写格上扫过 = 选中一段字（像选中一行文本）。
            // 选中的段可以整体替换 / 整段删除 —— 「单击定位、拖选多字」的文本体验。
            // mousedown 必须 preventDefault：不然浏览器把后续鼠标事件捕获在起点 input 里，
            // 其它格永远收不到 hover，选区就动不了；焦点在松开后手动还给选区起点格
            list.addEventListener('mousedown', (ev) => {
                // 点字下那个替换字：不挪焦点、不起拖选（点完浮条还要留着）
                if (ev.target.closest('.cell-poly-chip')) {
                    ev.preventDefault();
                    clearTimeout(polyHideTimer);
                    return;
                }
                const cell = ev.target.closest('.fill-cell');
                if (!cell || ev.button !== 0) {
                    clearSel(host);
                    return;
                }
                ev.preventDefault();
                const lineEl = cell.closest('.fill-line');
                const voiceEl = cell.closest('.fill-voice');
                sel = {i: Number(lineEl.dataset.i),
                       g: Number(voiceEl.dataset.g),
                       from: Number(cell.dataset.si), to: Number(cell.dataset.si),
                       live: true};
                paintSel(host);
                // 再点已聚焦的格不会有 focusin —— 多音字提醒条在这里一并刷新
            });
            document.addEventListener('mousemove', (ev) => {
                if (!sel || !sel.live) {
                    return;
                }
                const el = document.elementFromPoint(ev.clientX, ev.clientY);
                const cell = el && el.closest ? el.closest('.fill-cell') : null;
                if (!cell) {
                    return;
                }
                const lineEl = cell.closest('.fill-line');
                const voiceEl = cell.closest('.fill-voice');
                // 选区限制在同一句同一组内（跨组的格不属于同一行歌词）
                if (!lineEl || Number(lineEl.dataset.i) !== sel.i
                        || Number(voiceEl.dataset.g) !== sel.g) {
                    return;
                }
                if (sel.to !== Number(cell.dataset.si)) {
                    sel.to = Number(cell.dataset.si);
                    paintSel(host);
                }
            });
            document.addEventListener('mouseup', (ev) => {
                if (sel && sel.live) {
                    sel.live = false;
                    // 焦点还给选区起点格：后续打字 / 删除走该格的 keydown（整段替换 / 清空）
                    focusCell(host, sel.i, Math.min(sel.from, sel.to));
                }
            });

            list.addEventListener('compositionstart', () => composing = true);
            // 组合结束时补提交一次：Chrome 在 compositionend **之后**才补发 input（下面的
            // 监听会重复提交一次同样的值，无害），Firefox 则在 compositionend 之前发
            // input（那时 isComposing 为真被跳过）—— 两种顺序都得有人收尾，否则最后一个字丢掉
            list.addEventListener('compositionend', (ev) => {
                composing = false;
                const cell = ev.target.closest && ev.target.closest('.fill-cell');
                if (cell) {
                    commit(host, Number(cell.closest('.fill-line').dataset.i),
                        Number(cell.dataset.si), cell);
                }
            });

            // 失焦：① 合唱副本那行的空格这时才抄（见 copyTwinOnBlur）；② 延迟收起建议条
            //（等可能的焦点切换，focusin 会取消收起）
            list.addEventListener('focusout', (ev) => {
                const cell = ev.target.closest && ev.target.closest('.fill-cell');
                if (!cell) {
                    return;
                }
                const lineEl = cell.closest('.fill-line');
                if (lineEl && !composing) {
                    const i = Number(lineEl.dataset.i);
                    const copied = copyTwinOnBlur(i, Number(cell.dataset.si));
                    if (copied.length) {
                        // 格子的值不走 syncLine（它只改计数与配色），这里自己写进 DOM
                        copied.forEach(si => {
                            const box = cellAt(host, i, si);
                            if (box) {
                                box.value = (filled[i] || [])[si] || '';
                            }
                        });
                        markDirty(host);
                        syncLine(host, i);
                    }
                }
                clearTimeout(polyHideTimer);
                polyHideTimer = setTimeout(hidePolyChips, 120);
            });

            list.addEventListener('keydown', (ev) => {
                if (ev.key === 'Escape') {
                    clearSel(host);
                    return;
                }
                const cell = ev.target.closest('.fill-cell');
                if (!cell) {
                    return;
                }
                const i = Number(cell.closest('.fill-line').dataset.i);
                const si = Number(cell.dataset.si);
                // 选区生效范围：同一句**同一行**（声部组）里的 from..to（>1 格才算整段；单格走格子
                // 自己的逻辑）。焦点格不在选区那一行时选区不生效 —— 否则在副本行敲字会把主行选中的
                // 那一段清掉（「框选第一行，动的是两行」的反面）
                const slots = (sel && sel.i === i && sel.g === groupOf(cell))
                    ? selSlots(lines[i], sel.g, sel.from, sel.to) : [];
                const span = slots.length > 1 ? slots.length - 1 : 0;
                const wipe = () => {
                    slots.forEach(k => {
                        // 清这一格，连同副本声部同音符那一格 —— 否则下面的联动立刻把它抄回来
                        [k].concat(clearCellEverywhere(i, k)).forEach(si => {
                            const box = cellAt(host, i, si);
                            if (box) {
                                box.value = '';
                            }
                        });
                    });
                    syncVoiceCopies(i);
                    markDirty(host);
                    syncLine(host, i);
                    clearSel(host);
                };

                if ((ev.key === 'c' || ev.key === 'C') && (ev.ctrlKey || ev.metaKey)
                        && !ev.altKey && sel) {
                    // 拖选的 Ctrl/Cmd+C：把选中的格复制成一个整词（含单格）。句号取选区自己的
                    // （焦点掉到别处也照选中的复制）；没有选区时不拦，让浏览器的原生复制照常走
                    ev.preventDefault();
                    copyText(groupLyric(sel.i, selSlots(lines[sel.i], sel.g, sel.from, sel.to)));
                    return;
                }
                if (ev.key === 'Backspace' && span > 0 && !composing && !ev.isComposing) {
                    // 多格选区：退格 = 整段清空
                    ev.preventDefault();
                    wipe();
                    return;
                }
                if (ev.key.length === 1 && span > 0 && !ev.ctrlKey && !ev.metaKey
                        && !ev.altKey && !composing && !ev.isComposing) {
                    // 多格选区：直接打字 = 整段替换（第一个字落在选区起点，往后摊）
                    ev.preventDefault();
                    wipe();
                    cell.value = ev.key;
                    commit(host, i, si, cell);
                    return;
                }

                // 左右键跨格移动：走到本行两端就跨到相邻句（落点与退格 / 回车同一套）。
                // 只在光标已顶到该格文本两端时才接手 —— 格内的光标移动、Shift 拖选都让给 input；
                // IME 组合中的方向键在选候选字，也不能抢。
                if ((ev.key === 'ArrowLeft' || ev.key === 'ArrowRight')
                        && !ev.shiftKey && !ev.ctrlKey && !ev.metaKey && !ev.altKey
                        && !composing && !ev.isComposing) {
                    const left = ev.key === 'ArrowLeft';
                    const edge = left
                        ? cell.selectionStart === 0 && cell.selectionEnd === 0
                        : cell.selectionStart === cell.value.length
                            && cell.selectionEnd === cell.value.length;
                    if (edge) {
                        ev.preventDefault();
                        clearSel(host);
                        const same = stepInLine(i, si, left ? -1 : 1, true);
                        if (same >= 0) {
                            focusCellCaret(host, i, same, left);
                        } else if (left) {
                            const back = prevLineTarget(i, groupOf(cell));
                            if (back >= 0) {
                                focusCellCaret(host, i - 1, back, true);
                            }
                        } else {
                            const next = nextLineTarget(i);
                            if (next >= 0) {
                                focusCellCaret(host, i + 1, next, false);
                            }
                        }
                    }
                }

                // 退格：格已空 → 光标挪到上一格连续删；本行行首 → 挪到上一句行尾继续删。
                // 格里有字就让 input 自己删（第一次删字，第二次才挪格）
                if (ev.key === 'Backspace' && !cell.value && !composing && !ev.isComposing) {
                    ev.preventDefault();
                    const g = groupOf(cell);
                    const prev = stepInLine(i, si, -1, true);
                    if (prev >= 0) {
                        // 同样连副本声部那一格一起清（见 clearCellEverywhere）
                        [prev].concat(clearCellEverywhere(i, prev)).forEach(k => {
                            const box = cellAt(host, i, k);
                            if (box) {
                                box.value = '';
                            }
                        });
                        syncVoiceCopies(i);
                        markDirty(host);
                        syncLine(host, i);
                        focusCell(host, i, prev);
                        return;
                    }
                    if (i > 0) {
                        // 本行行首 → 上一句同组（没有同组就取最长组）的最后一个非换气格
                        const back = prevLineTarget(i, g);
                        if (back >= 0) {
                            focusCell(host, i - 1, back);
                        }
                    }
                }

                // 回车跳句：下一句（Shift+回车 = 上一句）第一个可填格。
                // IME 组合中的回车是在选候选字，不能抢
                if (ev.key === 'Enter' && !composing && !ev.isComposing) {
                    ev.preventDefault();
                    const at = i + (ev.shiftKey ? -1 : 1);
                    const to = lines[at];
                    if (!to) {
                        return;
                    }
                    const first = to.slots.findIndex(isFillable);
                    if (first >= 0) {
                        focusCell(host, at, first);
                    }
                }
            });

            // 点原词行的格子（尤其 `-`）→ 光标落到填写行对应的格子。
            // 断句竖条 = 在这句的该槽位前断开；「并入上一句」在句头
            list.addEventListener('click', (ev) => {
                const lineEl = ev.target.closest('.fill-line');
                if (!lineEl) {
                    return;
                }
                const i = Number(lineEl.dataset.i);
                const split = ev.target.closest('.fill-split');
                if (split) {
                    splitLine(host, i, Number(split.dataset.si));
                    return;
                }
                // 视觉空位：空位带上的热区（+ / ␣）与空位列本身都是同一个开关
                const gapHit = ev.target.closest('[data-gap-si]');
                if (gapHit) {
                    toggleGap(host, i, Number(gapHit.dataset.gapSi));
                    return;
                }
                if (ev.target.closest('.act-merge')) {
                    mergeLine(host, i);
                    return;
                }
                // 字下那个替换字：再点一次 = 取消（歌词本来就没动，撤的只是导出回填）
                const swapChip = ev.target.closest('.cell-poly-chip');
                if (swapChip) {
                    toggleSwap(host, i, Number(swapChip.dataset.si), '');
                    return;
                }
                const slotEl = ev.target.closest('.fill-slot');
                if (slotEl) {
                    focusCell(host, i, Number(slotEl.dataset.si));
                }
                // 点填词格 = 记下目标格 + 把右栏词典的锚跟过去。再点一次同一个格不会触发
                // focusin（焦点没变），所以这里必须自己跟一次，否则「换个格子看候选」会失灵
                const cell = ev.target.closest('.fill-cell');
                if (cell) {
                    lastCell = {i, si: Number(cell.dataset.si)};
                    followAnchor(lastCell.i, lastCell.si);
                }
            });

        }

        host.querySelectorAll('.f-track').forEach(box => box.onchange = async () => {
            const next = [...host.querySelectorAll('.f-track')]
                .filter(b => b.checked).map(b => Number(b.value));
            if (!next.length) {
                UI.err('至少留一个声部');
                box.checked = true;
                return;
            }
            // 换声部 = 让后端按新时间线重跑分句，回读的是**库里**的填词（按句序号保留），
            // 页面上未保存的改动会丢 —— 所以先问一句
            if (dirty && !(await UI.confirm('换声部会按新时间线重跑分句，'
                + '页面上未保存的改动会丢（库里的填词按句序号保留）。继续？',
                {title: '换声部', danger: false}))) {
                box.checked = selected.has(Number(box.value));
                return;
            }
            await load(host, {trackIndices: next});
        });

        host.querySelector('#f-close').onclick = async () => {
            if (dirty && !(await UI.confirm('有未保存的改动，关闭会丢掉。继续？',
                {title: '关闭'}))) {
                return;
            }
            await listView(host);
        };
        // 「恢复上次手动保存」：只有手动保存过、且没换过声部（那时句界对不上）才露出来
        const restore = host.querySelector('#f-restore');
        const snap = manualSnapshot();
        if (restore && snap) {
            restore.hidden = false;
            restore.title = '回到 ' + fmtTime(new Date(snap.time).toISOString())
                + ' 手动保存的那一版（自动保存不留存档点）';
            restore.onclick = () => restoreManual(host);
        }
        host.querySelector('#f-delete').onclick = async (ev) => {
            if (!(await UI.confirm('删除填词项目「' + (nameDraft || tpl.fillName || '') + '」？'
                    + '只删这一份的填词，磁盘上的 svp / 歌词不动。', {
                title: '删除填词', okText: '删除', danger: true
            }))) {
                return;
            }
            await UI.withBusy(ev.target, '删除中…', async () => {
                try {
                    await Api.post('/api/song/fill/delete', {fillId: tpl.fillId});
                    UI.ok('已删除');
                    await backToFillList(host);
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };
        // 改名也算改动：不标脏的话，改完直接切走会悄无声息地丢掉
        host.querySelector('#f-name').addEventListener('input', () => markDirty(host));

        host.querySelector('#f-save').onclick = (ev) => save(host, ev.target);
        host.querySelector('#f-export').onclick = () => openExport();
        host.querySelector('#f-batch').onclick = () => openBatch();
        // 清空填词：破坏性但可撤回（只清页面上的内容，落库仍走「保存」），所以先问一句
        host.querySelector('#f-clear').onclick = async () => {
            if (!(await UI.confirm('清空这一份填词的全部格子？'
                    + '只清页面上的内容 —— 点「保存」才会落库，没保存就刷新页面能撤回。', {
                title: '清空填词', okText: '清空', danger: true
            }))) {
                return;
            }
            clearFill(host);
        };
        host.querySelector('#f-reparse').onclick = (ev) => reparse(host, ev.target);

        // 重新分句 = 走自动路径（先文本对齐，对不上再时间戳），也用来撤掉手动断开 / 合并
        host.querySelector('#f-realign').onclick = async (ev) => {
            await UI.withBusy(ev.target, '分句中…',
                () => load(host, {realign: true, trackIndices: [...selected]}));
        };
        // 手填偏移 = 明确要求按时间戳分（文本对不上时的退路）
        host.querySelector('#f-time').onclick = async (ev) => {
            const raw = host.querySelector('#f-offset').value.trim();
            if (raw === '') {
                UI.err('先填个偏移秒数，再点「按时间戳分句」');
                return;
            }
            await UI.withBusy(ev.target, '分句中…',
                () => load(host, {offsetSeconds: Number(raw), trackIndices: [...selected]}));
        };
    }




    /**
     * 句内跨组联动（需求 2 的「一行为空自动抄另一行」）：同音符（onset 容差 + 同原词）
     * 的槽位之间有值 → 空双向抄。前端输入时实时做，后端保存 / 导出还会兜一道。
     */
    function syncVoiceCopies(i) {
        const line = lines[i];
        const groups = lineGroups(line);
        if (groups.length < 2) {
            return;
        }
        for (let x = 0; x < groups.length; x++) {
            for (let y = x + 1; y < groups.length; y++) {
                for (const sa of groups[x]) {
                    const sb = twinSlot(line, groups[y], sa);
                    if (sb < 0) {
                        continue;
                    }
                    const va = (filled[i][sa] || '').trim();
                    const vb = (filled[i][sb] || '').trim();
                    if (va && !vb) {
                        setValue(i, sb, va);
                    } else if (!va && vb) {
                        setValue(i, sa, vb);
                    }
                }
            }
        }
    }

    /**
     * 批量填词之后：把「被另一组完全包含的副本声部」整行对齐到包含它的那一组 —— 同音符
     * （onset 容差 + 同原词）的格子**按源覆盖**（源有字就抄字，源空着就把副本行的残留清掉）。
     * 手动填字那条路不走这里（见 {@link copyTwinOnBlur}）。
     *
     * <p><b>为什么必须覆盖、不能只补空</b>：批量填词是「整句重填」的语义，而副本行里的旧值
     * 不只来自这一次填词 —— 后端 {@code carry} 按（轨, 音符）回指保留、重新解析、换声部之后
     * 都会留下上一次填的内容（实测气泡少女第 44 句起：副本行的字是上一句的，一句一行地错位）。
     * 只补空的 {@link syncVoiceCopies} 碰上「源有值、副本也有值」就跳过，那些残留于是永远盖不掉，
     * 症状正是「同一句的不同音轨内容不一样」。副本组按定义与源组同音符同原词（lrc 导出去重时
     * 只算一份、回填文本按轨各一份），所以「副本行 = 源行的忠实拷贝」是不会写坏的不变量。
     *
     * <p>组身份按 {@link lyricGroups} 的同一套判据认（被包含的那份是副本；两组互相包含时留组号
     * 小的那份当源）。找不到孪生格的槽位（副本行那条轨本来就与源对不上）保持原样。
     */
    function mirrorCopies(i) {
        const line = lines[i];
        const row = filled[i] || [];
        if (!line) {
            return;
        }
        const groups = lineGroups(line);
        if (groups.length < 2) {
            return;
        }
        groups.forEach((group, g) => {
            const src = groups.findIndex((other, o) => o !== g
                && groupCovered(line, group, other)
                && !(groupCovered(line, other, group) && o > g));
            if (src < 0) {
                return;   // 自己就是吃字的那一组（两组各唱各的）→ 不动
            }
            group.forEach(si => {
                const twin = twinSlot(line, groups[src], si);
                const value = twin >= 0 ? (row[twin] || '') : null;
                if (value === null || (row[si] || '') === value) {
                    return;
                }
                setValue(i, si, value);
            });
        });
    }

    /**
     * 光标离开这一格：把它有值的字抄给同音符（onset 容差 + 同原词）的其他组空格 —— **只补空格、
     * 不覆盖**，源格自己空着也不反向回填（用户要求「填其中一个、另一个空着，光标离开时自动填写
     * 另一个」）。返回被补上的槽位下标（DOM 里格子的值由调用方写；核对脚本直接调它测）。
     *
     * <p>与 {@link syncVoiceCopies} 的区别只在**方向与时机**：那个是「有值 ↔ 空」双向、批量填词
     * 与整段清空当场做；手动填写时用这个，免得边填边抄、分不清哪个字是自己填的。
     */
    function copyTwinOnBlur(i, si) {
        const line = lines[i];
        const value = ((filled[i] || [])[si] || '').trim();
        if (!line || !value) {
            return [];
        }
        const copied = [];
        const rows = lineGroups(line);
        const own = rows.findIndex(row => row.indexOf(si) >= 0);   // 本格自己那一行不抄（一行 = 一条轨）
        rows.forEach((group, g) => {
            if (g === own) {
                return;
            }
            const twin = twinSlot(line, group, si);
            if (twin >= 0 && !((filled[i][twin] || '').trim())) {
                setValue(i, twin, value);
                copied.push(twin);
            }
        });
        return copied;
    }

    /**
     * 清掉这一格，连同同音符（onset 容差 + 同原词）的**其他声部那一格**；返回被一起清掉的槽位下标。
     *
     * <p>不这么清就清不掉：只清一行，紧接着的 {@link syncVoiceCopies}「有值 ↔ 空只补空」会把
     * 副本行的同一个字当场抄回来（原来这里调的就是它，选区整段清空于是等同白按）。一份歌词两行
     * 写，格子里是同一个字，清就得一起清 —— 副本行与主行的对应关系见 {@link twinSlot}，找不到
     * 孪生格的槽位（两条轨本来对不上）只清自己这一格。
     */
    function clearCellEverywhere(i, si) {
        setValue(i, si, '');
        const line = lines[i];
        if (!line) {
            return [];
        }
        const also = [];
        lineGroups(line).forEach(group => {
            if (group.indexOf(si) >= 0) {
                return;   // 自己那一行就是这一格，别自抄
            }
            const twin = twinSlot(line, group, si);
            if (twin >= 0 && (filled[i][twin] || '')) {
                setValue(i, twin, '');
                also.push(twin);
            }
        });
        return also;
    }

    /**
     * 空位锚点：延音格（{@code -}）必须紧跟在它前面的有效音符后面，所以「字与它的 {@code -}
     * 之间」与「延音链之后」是**同一个空位**，一律记在链尾那一格之后 —— 从某一格往后吃掉
     * 同轨连续（{@code noteIndex} 逐个 +1）的延音，返回链尾下标。
     *
     * <p>后端 {@code LyricFillAligner#prolongationEnd} 是同一口径（导出 lrc 按链尾补空格），
     * 所以页面上加的空位与导出的空格永远落在同一个位置上。
     */
    function chainTail(line, si) {
        const slots = line.slots;
        let at = si;
        while (at + 1 < slots.length) {
            const cur = slots[at];
            const next = slots[at + 1];
            if (!isDash(next) || next.trackIndex !== cur.trackIndex
                || next.noteIndex !== cur.noteIndex + 1) {
                return at;
            }
            at++;
        }
        return at;
    }

    /**
     * 点一下空位带 / 空位列：有就消除、没有就加上。空位记在**边界左侧那一格之后**
     * （与后端 gaps 语义、与 {@link splitLine} / {@link mergeLine} 的 splice 约定一致），
     * 边界取 {@link chainTail} 的锚点 —— 于是「字与它的延音之间」点不出空位，
     * 在那里点到的也是同一个（链尾的）空位。
     *
     * <p>加 / 删都展开到同音符的其它声部：合唱两行唱同一句词，空位得两行都画
     * （与 {@code copyTwins} / {@code clearTwins} 同口径，自己那一行不自抄）。
     */
    function toggleGap(host, i, si) {
        const line = lines[i];
        if (!line) {
            return;
        }
        const at = chainTail(line, si);
        // padRow 补的是空串（它对槽位值那一列是「没填」），这里一律归成布尔再存
        const row = padRow((gaps[i] || []).slice(), line.slots.length).map(v => !!v);
        row[at] = !row[at];
        const groups = lineGroups(line);
        if (groups.length > 1) {
            groups.forEach(group => {
                if (group.indexOf(at) >= 0) {
                    return;   // 自己那一行就是这一格，别自抄
                }
                const twin = twinSlot(line, group, at);
                if (twin >= 0) {
                    // 另一声部也记在**它自己**的延音链之后：那一格后面也有延音时，
                    // 标记停在字上会在导出 lrc 里插到字与它的「-」之间（就是这次修的那个 bug）
                    row[chainTail(line, twin)] = row[at];
                }
            });
        }
        gaps.splice(i, 1, row);
        markDirty(host);
        draw(host);
    }

    /** 组内与 src 同音符（onset 容差 + 同原词）的槽位下标；没有则 -1。 */
    function twinSlot(line, group, src) {
        const slot = line.slots[src];
        const onset = noteOnset(line, src);
        if (onset == null) {
            return -1;
        }
        for (const si of group) {
            const s = line.slots[si];
            if ((s.original || '') !== (slot.original || '')) {
                continue;
            }
            const o = noteOnset(line, si);
            if (o != null && Math.abs(o - onset) <= UNISON_TOLERANCE) {
                return si;
            }
        }
        return -1;
    }

    /** 槽位对应音符的 onset（骨架回查）；找不到音符返回 null。 */
    function noteOnset(line, si) {
        const slot = line.slots[si];
        const note = noteMap.get(key(slot.trackIndex, slot.noteIndex));
        return note ? note.onset : null;
    }




    /** 写一格的值：延音格填了字就把它变成可填槽位（需填字数跟着 +1），清空则还原 */
    /**
     * 一格写入：值 + 「回填换字」撤销 + 延音激活（填了字的 `-` 变成真音）。
     * {@code row} 是任一行值数组（不一定是 {@code filled[i]}`）——批量重排要在**合并后的**
     * 临时行上先写、最后整体落地，两处共用这一处口径。
     *
     * @return 该槽的 {@code slotType} 是否被改过（是则调用方要重造这一句）
     */
    function writeCell(row, si, slot, value) {
        row[si] = value || '';
        if (!row[si] || !polyMark(slot, row[si])) {
            // 字删了 / 换成一个压根不是多音字的字，这一格哪还有「回填换成哪个单音字」可言
            // —— 换字跟着字一起撤掉，不然重新填一个字会把上一个字的替换捡回来
            delete polySwap[swapKeyOf(slot)];
        }
        // 记号格（延音 / 静音占位）填了字就激活成真音，清空就还原成它本来的记号
        const idle = isDash(slot) ? 'DASH' : isZero(slot) ? 'ZERO' : null;
        if (!idle) {
            return false;
        }
        const want = value ? 'HANZI' : idle;
        if (slot.slotType === want) {
            return false;
        }
        slot.slotType = want;
        return true;
    }

    function setValue(i, si, value) {
        const row = filled[i];
        const slot = lines[i] && lines[i].slots[si];
        if (!row || !slot) {
            return;
        }
        if (writeCell(row, si, slot, value)) {
            lines[i] = mkLine(lines[i].slots, lines[i].groups);
        }
    }

    /** 只更新这一句的计数与格子配色（不动 DOM 结构，IME 才不会断） */
    function syncLine(host, i) {
        const box = host.querySelector('.fill-line[data-i="' + i + '"]');
        if (!box) {
            return;
        }
        const line = lines[i];
        const row = filled[i] || [];
        const done = countDone(line, row);
        const bad = done !== line.needCount;
        const count = box.querySelector('.fill-count');
        count.textContent = done + ' / ' + line.needCount;
        count.classList.toggle('fill-bad', bad);
        const lyricEl = box.querySelector('.fill-line-lyric');
        if (lyricEl) {
            lyricEl.textContent = lineLyric(i);
        }
        box.querySelectorAll('.fill-cell').forEach(el => {
            const si = Number(el.dataset.si);
            const slot = line.slots[si];
            const value = (row[si] || '').trim();
            el.classList.toggle('on', !!value);
            el.classList.toggle('dash', isDash(slot) && !value);
            el.classList.toggle('breath', isBreath(slot) && !value);
            el.classList.toggle('zero', isZero(slot) && !value);
            el.classList.toggle('unmatched',
                isUnmatchedPinyin(slot, (defaults[i] || [])[si], value));
            const poly = polyMark(slot, value);
            const swap = polySwapped(slot);
            el.classList.toggle('poly', !!poly && !poly.weak);
            el.classList.toggle('poly-weak', !!poly && poly.weak);
            el.classList.toggle('swapped', !!swap);
            el.title = cellTip(slot, (defaults[i] && defaults[i][si]) || '', value, poly, swap);
            ensureSwapChip(el, swap);
        });
    }

    /**
     * 已选回填替换字时，把它常显在填写行这一格的正下方（**只有这一个字**，不占列宽）；
     * 没选（换字随字一起被删掉、或本来就没选）就把字下那行清空 —— 包装始终留着。
     *
     * <p>候选本身不在字下铺 —— 那是「选中前」的事，走悬浮窗（{@link updatePolyChips}）；
     * 字下只留结果，一眼能看出这一格导出时会被换成哪个字。动态挂载是因为填字后只走
     * {@link syncLine} 不重画 DOM。
     */
    function ensureSwapChip(el, swap) {
        // 已经包过的格：el.parentElement 是包装而不是 grid，得先看包装那层
        const wrap = el.closest('.fill-cell-wrap');
        const grid = wrap ? wrap.parentElement : el.parentElement;
        if (!grid || !grid.classList.contains('fill-grid')) {
            return;
        }
        if (!swap) {
            // 只清空字下那一行，**包装留着**：格子是 grid 的直接子项，拆掉包装会让它
            // 变成 grid item、列宽随之跳一下；而且后面若再选中换字还得重新包回去
            const sug = wrap && wrap.querySelector('.cell-poly-suggest');
            if (sug) {
                sug.innerHTML = '';
            }
            return;
        }
        let box = wrap;
        if (!box) {
            box = document.createElement('span');
            box.className = 'fill-cell-wrap';
            grid.insertBefore(box, el);
            box.appendChild(el);
        }
        let sug = box.querySelector('.cell-poly-suggest');
        if (!sug) {
            sug = document.createElement('span');
            sug.className = 'cell-poly-suggest';
            box.appendChild(sug);
        }
        const html = '<b class="cell-poly-chip on" data-si="' + (el.dataset.si || '')
            + '" data-char="' + UI.esc(swap) + '" title="导出回填用「' + UI.esc(swap)
            + '」，歌词不变；再点取消">' + UI.esc(swap) + '</b>';
        if (sug.innerHTML !== html) {
            sug.innerHTML = html;
        }
    }

    /**
     * 点候选 / 点字下那个字：记录导出回填替换（歌词不动），再点同一个字 = 取消。
     * 替换是页面级状态，不落库。
     */
    function toggleSwap(host, i, si, ch) {
        const slot = lines[i] && lines[i].slots[si];
        if (!slot) {
            return;
        }
        const swapKey = swapKeyOf(slot);
        if (!ch || polySwap[swapKey] === ch) {
            delete polySwap[swapKey];
        } else {
            polySwap[swapKey] = ch;
        }
        const cell = cellAt(host, i, si);
        syncLine(host, i);
        if (cell) {
            updatePolyChips(cell, slot);
        }
    }


    /**
     * 一格填完：把格里的字收进状态，自动前进到下一格（跳过 `-`）——**先走完本声部那一行，
     * 再进下一行**（见 {@link stepInLine}）。
     *
     * <p>一次敲进来多字（IME 整词、粘贴）时第一个留在本格、其余往后摊 —— 所以这里按
     * tokenize 切（英文单词整体算一个），与后端的切分规则同一套。摊不下的丢弃并提示
     * （这一格限一个字 / 词，多出的就是超了）。
     *
     * <p>合唱副本那一行的联动抄写**不在这里**：光标离开这一格时才抄（{@link copyTwinOnBlur}，
     * 挂 `focusout`）—— 边填边抄会让人分不清哪个字是自己填的。
     */
    function commit(host, i, si, cell) {
        const units = tokenize(cell.value);
        const first = units.length ? units[0] : '';
        cell.value = first;
        setValue(i, si, first);
        let at = si;
        let dropped = 0;
        for (let k = 1; k < units.length; k++) {
            at = stepInLine(i, at, 1, true);
            if (at < 0) {
                dropped = units.length - k;
                break;
            }
            setValue(i, at, units[k]);
            const box = cellAt(host, i, at);
            if (box) {
                box.value = units[k];
            }
        }
        markDirty(host);
        syncLine(host, i);
        if (dropped) {
            flashStatus(host, '超出 ' + dropped + ' 个字（每格一个字 / 词，本句格不够放）');
        }
        if (!first) {
            return;
        }
        focusCell(host, i, stepInLine(i, at, 1, true));
    }

    /** 行头状态位闪一条提示（1.5 秒后清掉；不打断输入）。 */
    function flashStatus(host, text) {
        const status = host.querySelector('#f-status');
        if (!status) {
            return;
        }
        status.textContent = text;
        clearTimeout(flashStatus.timer);
        flashStatus.timer = setTimeout(() => {
            status.textContent = dirty ? '有未保存的改动' : '';
        }, 1500);
    }

    function cellAt(host, i, si) {
        return host.querySelector('.fill-line[data-i="' + i + '"] .fill-cell[data-si="' + si + '"]');
    }

    function focusCell(host, i, si) {
        if (si == null || si < 0) {
            return;
        }
        const cell = cellAt(host, i, si);
        if (cell) {
            cell.focus();
            cell.select();
        }
    }

    /**
     * 同 {@link focusCell}，但把光标落在文本开头 / 末尾，**不全选**。
     * <p>左右键跨格移动用它：全选的话，接着打字会替换掉刚跳过来那格的内容 ——
     * 而方向键的语义是「挪过去接着改」。
     */
    function focusCellCaret(host, i, si, atEnd) {
        if (si == null || si < 0) {
            return;
        }
        const cell = cellAt(host, i, si);
        if (!cell) {
            return;
        }
        cell.focus();
        try {
            const at = atEnd ? cell.value.length : 0;
            cell.setSelectionRange(at, at);
        } catch (e) {
            cell.select();
        }
    }

    /**
     * 一格之后的落点：**先在本声部那一行里走，走到行尾再进下一行**（自动前进 / 退格回退 / 多字摊开
     * 都用它），行序与批量填词的 {@link lineTargets} 同一口径。
     *
     * <p>不能按平坦下标走相邻格：一句里有多个轨时，槽位在平坦数组里是**按 onset 逐拍交错**的
     * （后端 {@code LyricFillAligner.line} 按代表音符时刻展开成员，实测 groups = [0,1,0,1,…]，
     * 快照 `target/corpus-fix7.txt` 同为 `1:H:0 2:H:1 1:D:0 …`），照相邻下标走会一步踩进
     * 另一条轨的格子。
     *
     * <p>吃字行 = {@link lineTargets}（被另一组完全包含的合唱副本行**不参与**，它的字靠
     * {@link copyTwinOnBlur} 抄过去，不然那一行会被塞进本不属于它的字）；当前格不在吃字行里时
     * （点进了副本行、或整组都是 br / 延音取不出字）退回全行序 {@link lineGroups}，至少本行能走。
     *
     * <p>跳过规则照旧：br / 0 恒跳过，skipDash 时连延音也跳过；整句走完返回 -1。
     */
    function stepInLine(i, si, step, skipDash) {
        const line = lines[i];
        if (!line) {
            return -1;
        }
        const targets = lineTargets(i);
        const rows = targets.some(row => row.indexOf(si) >= 0) ? targets : lineGroups(line);
        let at = rows.findIndex(row => row.indexOf(si) >= 0);
        if (at < 0) {
            return -1;
        }
        for (; at >= 0 && at < rows.length; at += step) {
            const row = rows[at];
            // 本行从当前格起步；换到下一行后从行的两端起步（后退取行尾）
            const from = row.indexOf(si);
            const start = from >= 0 ? from : (step > 0 ? -1 : row.length);
            for (let p = start + step; p >= 0 && p < row.length; p += step) {
                const slot = line.slots[row[p]];
                if (autoSkip(slot) || (skipDash && isDash(slot))) {
                    continue;
                }
                return row[p];
            }
        }
        return -1;
    }

    /**
     * **上一句**的落点：同声部组（{@code g}）优先，该组没有可落格时退到最长组，
     * 取该组最后一个非记号格（换气 / 静音占位都跳过）；没有上一句 / 整组都是记号时返回 -1。
     * <p>退格从行首往回挪、左键从行首继续往左，都是它。
     */
    function prevLineTarget(i, g) {
        if (i <= 0) {
            return -1;
        }
        const groups = lineGroups(lines[i - 1]);
        let row = groups[g];
        if (!row || !row.length) {
            row = groups.reduce((best, cur) => (cur.length > best.length ? cur : best), groups[0]);
        }
        if (!row) {
            return -1;
        }
        for (let k = row.length - 1; k >= 0; k--) {
            if (!autoSkip(lines[i - 1].slots[row[k]])) {
                return row[k];
            }
        }
        return -1;
    }

    /** **下一句**的第一个可填格（汉字 / 英文）；没有下一句或整句没可填格时返回 -1。右键跨句用它 */
    function nextLineTarget(i) {
        const to = lines[i + 1];
        return to ? to.slots.findIndex(isFillable) : -1;
    }

    /** 一格悬浮提示：轨 + 原词 + 匹配 + 多音字提醒（弱提醒只说明另有生僻音、不建议换字）+ 回填替换 */
    function cellTip(slot, def, value, hint, swap) {
        let t = '轨 #' + slot.trackIndex + ' · 原词 ' + (slot.original || '');
        if (isBreath(slot)) {
            t += ' · 换气：只进回填文本，不进歌词';
        } else if (isZero(slot)) {
            t += ' · 静音占位：只进回填文本，不进歌词';
        } else if (slot.glottal) {
            t += " · 喉塞音：回填自动带 ' 前缀";
        }
        if (def) {
            t += ' · 匹配 ' + def;
        }
        if (hint && hint.weak) {
            // 弱多音字：这些是它的古音 / 冷门音，正常唱不会用到 —— 不报警、不建议换字
            t += ' · 「' + value + '」另有读音 ' + (hint.readings || []).join(' / ') + '，正常唱用不到';
        } else if (hint) {
            t += ' · ⚠「' + value + '」是多音字';
            if (hint.anchor) {
                t += '，本格音符读 ' + hint.anchor;
            } else {
                t += '（' + (hint.readings || []).join(' / ') + '）';
            }
            const sug = polySuggest(value, hint);
            if (sug) {
                t += '，建议换成：' + sug;
            }
        }
        if (swap) {
            t += ' · 回填用「' + swap + '」（歌词不变）';
        }
        return t;
    }

    /**
     * 记一笔改动：标脏 + 版本号 +1（{@link autoSave} 靠版本号判断「这次改的还是不是我刚写库的那版」，
     * 免得用户在我们发请求那几毫秒里敲的字被当成存过了）。
     */
    function markDirty(host) {
        dirty = true;
        rev++;
        const status = host && host.querySelector('#f-status');
        if (status) {
            status.textContent = '有未保存的改动';
        }
    }

    /** 状态位写一行提示（自动保存 / 恢复存档点用，不打断输入） */
    function setStatus(host, text) {
        const status = host && host.querySelector('#f-status');
        if (status) {
            status.textContent = text || '';
        }
    }

    /** `HH:MM:SS`，自动保存的时间戳（给人看「刚才是几点存的」）。 */
    function clockNow() {
        const t = new Date();
        return [t.getHours(), t.getMinutes(), t.getSeconds()]
            .map(n => String(n).padStart(2, '0')).join(':');
    }




    /**
     * 在第 i 句的 slots[si] 之前断开。填词按槽位切片，直接对半开。
     * 延音格只留在句尾：断点落在延音格上时往后挪到第一个非延音槽（一路到句尾就没了可拆的）。
     * {@code redraw=false} 供批量重排循环用，拆完统一重画。
     */
    function splitLine(host, i, si, redraw) {
        const line = lines[i];
        const slots = line.slots;
        while (si < slots.length && isDash(slots[si])) {
            si++;
        }
        const before = slots.slice(0, si);
        const after = slots.slice(si);
        if (!before.length || !after.length) {
            return;
        }
        const row = filled[i] || [];
        const defs = defaults[i] || [];
        const gapRow = gaps[i] || [];
        const bracketRow = brackets[i] || [];
        const ids = line.groups || [];
        lines.splice(i, 1, mkLine(before, ids.slice(0, si)), mkLine(after, ids.slice(si)));
        filled.splice(i, 1, padRow(row.slice(0, si), before.length),
            padRow(row.slice(si), after.length));
        defaults.splice(i, 1, padRow(defs.slice(0, si), before.length),
            padRow(defs.slice(si), after.length));
        gaps.splice(i, 1, padRow(gapRow.slice(0, si), before.length),
            padRow(gapRow.slice(si), after.length));
        brackets.splice(i, 1, padRow(bracketRow.slice(0, si), before.length),
            padRow(bracketRow.slice(si), after.length));
        dirty = true;
        rev++;
        if (redraw !== false) {
            draw(host);
        }
    }

    /**
     * 把第 i 句并到上一句（槽位拼接、组号**按轨**重编）；redraw=false 供批量重排循环用。
     *
     * <p>组身份是**轨**：搬来的槽位归上一句里自己那条轨的组，上一句没有这条轨就新开一组
     * （于是这一句出现第二行）；上一句有这条轨则并进它那一行 —— 与后端
     * {@code LyricFillAligner.mergeInto} 同一口径。**不能按组号拼**：组号是句内局部的
     * （首现序），同一个号在两句里往往不是同一条轨，拼一起会把两个轨糊成一行（《栖凰》
     * 「谯鼓响」那句后端修的就是这个坑，见 docs/填词工具设计.md §13 第 51 条）。
     */
    function mergeLine(host, i, redraw) {
        if (i <= 0) {
            return;
        }
        const prev = lines[i - 1];
        const moved = lines[i];
        // 上一句的「轨 → 组号」（缺 groups / 长度不齐的老数据 = 整句一组）
        const prevIds = prev.groups && prev.groups.length === prev.slots.length
            ? prev.groups : prev.slots.map(() => 0);
        const groupOfTrack = new Map();
        prev.slots.forEach((slot, k) => {
            if (!groupOfTrack.has(slot.trackIndex)) {
                groupOfTrack.set(slot.trackIndex, prevIds[k]);
            }
        });
        let fresh = prevIds.reduce((max, g) => Math.max(max, g), -1) + 1;
        const ids = moved.slots.map(slot => {
            if (!groupOfTrack.has(slot.trackIndex)) {
                groupOfTrack.set(slot.trackIndex, fresh++);
            }
            return groupOfTrack.get(slot.trackIndex);
        });
        const slots = prev.slots.concat(moved.slots);
        const row = (filled[i - 1] || []).concat(filled[i] || []);
        const defs = (defaults[i - 1] || []).concat(defaults[i] || []);
        const gapRow = (gaps[i - 1] || []).concat(gaps[i] || []);
        const bracketRow = (brackets[i - 1] || []).concat(brackets[i] || []);
        lines.splice(i - 1, 2, mkLine(slots, prevIds.concat(ids)));
        filled.splice(i - 1, 2, padRow(row, slots.length));
        defaults.splice(i - 1, 2, padRow(defs, slots.length));
        gaps.splice(i - 1, 2, padRow(gapRow, slots.length));
        brackets.splice(i - 1, 2, padRow(bracketRow, slots.length));
        dirty = true;
        rev++;
        if (redraw !== false) {
            draw(host);
        }
    }

    /**
     * 由槽位造一句：派生字段与后端 recompute 一致，句首 onset 从骨架里回查。
     * groups 缺失或长度不齐时整句一组；断开 / 合并后组号按首现序重编号。
     */
    function mkLine(slots, groups) {
        const fillable = slots.filter(isFillable);
        const first = slots[0];
        const note = first ? noteMap.get(key(first.trackIndex, first.noteIndex)) : null;
        const ids = groups && groups.length === slots.length ? renumber(groups) : slots.map(() => 0);
        return {
            slots,
            // 填了字的延音 / 静音占位 original 还是 `-` / `0`，不算进原词
            //（与后端 originalTextOf 同规则）
            originalText: fillable.filter(s => !isDash(s) && !isZero(s))
                .map(s => s.original || '').join(''),
            needCount: fillable.length,
            startOnset: note ? note.onset : 0,
            groups: ids
        };
    }

    /** 组号按首现序重编号（断开 / 合并后保持紧凑）。 */
    function renumber(groups) {
        const map = new Map();
        let next = 0;
        return groups.map(g => {
            if (!map.has(g)) map.set(g, next++);
            return map.get(g);
        });
    }

    // ==================== 右栏（韵脚词典 / AI 填词）====================
    //
    // 旧版是挂在 body 上、随聚焦格出现的悬浮词典；现在收进填词页自己的右栏
    // （draw 里 .fill-page 的右半边）：折叠时只剩右边缘一条竖排开关（#f-side-toggle），
    // 展开后两个页签 —— 「韵脚词典」整块交给共用组件 RhymeQuery（js/rhymequery.js，与
    // 词典页是同一个），「AI 填词」是原来那个抽屉搬进来的（见下面的 AI 页签一节）。
    //
    // **收起 / 展开 / 切页签都不重画整页**（重画会丢焦点与滚动位置），只动右栏自己的 DOM：
    //   ① 折叠 / 展开与切页签只切 class（applySide），两块内容按需画一次、画过就不重画；
    //   ② 折叠时调 RhymeQuery.clear()，展开且该页签在前台才 render / refresh（折叠 = 不查）；
    //   ③ AI 页签的轮询进度只改那一行文案（setAiProgress），不重画面板。
    // 词典页签的锚跟随「最后点过的填词格」：点格子就查这个字押什么韵（§4.7 的反查口径），
    // 点候选则填回那个格子 —— 走本格 commit 那条路，一次多字照样按格摊开。

    let rhymeMeta = null;        // /api/song/rhyme/meta 的缓存（十八韵清单，AI 页签的「韵」列也用）

    let sideOpen = false;        // 默认折叠
    let sideTab = 'rhyme';       // 'rhyme' | 'ai'
    let rhymeRendered = false;   // 词典页签的 DOM 是否已画好（draw 重画整页后置 false）
    let rhymeStale = false;      // 折叠过 → 展开时补一次 refresh（clear 只清了结果区）
    let lastCell = null;         // 最后碰过的填词格 {i, si}：**只记下标**，重画就换元素了
    let anchorManual = false;    // 用户手改过锚框 → 点格子不再跟随；清空锚框回到跟随

    function sideHtml() {
        return '<aside class="fill-side' + (sideOpen ? ' on' : '') + '" id="f-side">'
            + '<button type="button" class="fill-side-toggle" id="f-side-toggle"'
            + ' title="' + (sideOpen ? '收起右栏' : '展开右栏（韵脚词典 / AI 填词）') + '">'
            + '韵脚词典 / AI</button>'
            + '<div class="fill-side-body">'
            + '<div class="fill-side-tabs">'
            + '<button type="button" class="fill-side-tab' + (sideTab === 'rhyme' ? ' on' : '')
            + '" id="f-side-tab-rhyme">韵脚词典</button>'
            + '<button type="button" class="fill-side-tab' + (sideTab === 'ai' ? ' on' : '')
            + '" id="f-side-tab-ai">AI 填词</button>'
            + '</div>'
            + '<div class="fill-side-pane" id="f-side-pane-rhyme"></div>'
            + '<div class="fill-side-pane" id="f-side-pane-ai"></div>'
            + '</div></aside>';
    }

    /** 右栏自己的点击绑定（与 bind 分开：右栏是一块独立组件，收起展开不走整页重画）。 */
    function bindSide(host) {
        const side = host.querySelector('#f-side');
        if (!side) {
            return;
        }
        host.querySelector('#f-side-toggle').onclick = () => {
            sideOpen = !sideOpen;
            if (!sideOpen) {
                // 折叠 = 不查：清掉结果区（在途查询一并作废），展开时 refresh 补一次
                RhymeQuery.clear();
                rhymeStale = rhymeRendered;
            }
            applySide(host);
        };
        host.querySelector('#f-side-tab-rhyme').onclick = () => switchSideTab(host, 'rhyme');
        host.querySelector('#f-side-tab-ai').onclick = () => switchSideTab(host, 'ai');
    }

    function switchSideTab(host, tab) {
        if (sideTab === tab) {
            return;
        }
        sideTab = tab;
        applySide(host);   // 只切 class：不重画整页（那会丢焦点、跳滚动）
    }

    /**
     * 按 sideOpen / sideTab 摆右栏：切 class + 按需画一次两块内容。
     * **绝不重画整页** —— 这是右栏改造的头号约束（重画会丢焦点与滚动位置）。
     */
    function applySide(host) {
        const side = host.querySelector('#f-side');
        if (!side) {
            return;
        }
        side.classList.toggle('on', sideOpen);
        host.querySelector('#f-side-toggle').title =
            sideOpen ? '收起右栏' : '展开右栏（韵脚词典 / AI 填词）';
        host.querySelector('#f-side-tab-rhyme').classList.toggle('on', sideTab === 'rhyme');
        host.querySelector('#f-side-tab-ai').classList.toggle('on', sideTab === 'ai');
        const rhymePane = host.querySelector('#f-side-pane-rhyme');
        const aiPane = host.querySelector('#f-side-pane-ai');
        rhymePane.classList.toggle('on', sideOpen && sideTab === 'rhyme');
        aiPane.classList.toggle('on', sideOpen && sideTab === 'ai');
        if (!sideOpen) {
            return;   // 折叠时不查（结果区已被 clear 过，不挂着旧结果）
        }
        if (sideTab === 'rhyme') {
            if (!rhymeRendered) {
                rhymeRendered = true;
                rhymeStale = false;
                // pickOnly：右栏只挑不改，不画「新增 / 删除」；
                // compact：控件折两行，喂窄栏；onPick：单击直接把词填进最后点过的填词格
                //（不走词典页那套勾选态）。三个参数都是 RhymeQuery 现成的契约。
                RhymeQuery.render(rhymePane, {pickOnly: true, compact: true, onPick: onRhymePick})
                    .then(() => bindRhymePane(rhymePane), () => {});
            } else if (rhymeStale) {
                rhymeStale = false;
                RhymeQuery.refresh();
            }
        } else {
            void ensureAiPane(aiPane);
        }
    }

    /**
     * 「手动锚优先」只在这里认：用户打字 / 清空会触发 input，而 RhymeQuery.setAnchor 是
     * 直接改 input.value 的**程序化赋值，不触发 input** —— 所以 input 一到就是人手改的。
     * 清空 = 回到跟随模式（再点格子又跟着走）。
     */
    function bindRhymePane(pane) {
        const input = pane.querySelector('#rh-anchor');
        if (!input) {
            return;   // meta 拿不到时 RhymeQuery 不画骨架，右栏留白就行，不报错
        }
        input.addEventListener('input', () => {
            anchorManual = !!input.value.trim();
        });
    }

    /**
     * 点填词格 → 右栏词典的锚跟过去。**手动优先**：用户改过锚框且框里非空时不动它
     *（手改的就是他要查的），清空锚框即回到跟随。取不出汉字锚（空格 / 英文格）就保持原锚 ——
     * 别把「hello」当锚发出去，那只会换回一句「认不出这个锚」。
     */
    function followAnchor(i, si) {
        if (!sideOpen || !rhymeRendered) {
            return;
        }
        const input = pageHost && pageHost.querySelector('#rh-anchor');
        if (anchorManual && input && input.value.trim()) {
            return;
        }
        const anchor = rhymeAnchorOf(i, si);
        if (!anchor || anchor.kind !== 'hanzi') {
            return;
        }
        if (input && input.value.trim() === anchor.text) {
            return;   // 同一个锚：别为「格子里挪来挪去」白跑一次查询
        }
        RhymeQuery.setAnchor(anchor.text);
    }

    /**
     * 点右栏候选 = 填进最后点过的那个填词格：走本格 commit 那条路（多字按 tokenize 摊开、
     * 格不够放会拦下、填完自动前进），不另写一套落格逻辑 —— 与原悬浮面板的行为一致。
     * 没有目标格（还没点过格子，或那一格已被重画换掉）时退化为复制到剪贴板 + 提示。
     */
    function onRhymePick(item) {
        const cell = lastCell ? cellAt(pageHost, lastCell.i, lastCell.si) : null;
        if (!cell) {
            if (item && item.text && navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(item.text).catch(() => {});
            }
            UI.toast('先点一个填词格，再点候选');
            return;
        }
        cell.value = item.text;
        commit(pageHost, lastCell.i, lastCell.si, cell);
    }

    /**
     * 格子的锚：填入字优先，没填用槽位原词。英文格返回 {@code {kind:'english'}} ——
     * 英文格不判韵（§10.1），调用方据此跳过、不发查询。
     * 拼音模板的原词是拼音串，后端锚解析直接吃（PinyinSyllable.parse），这里原样传。
     */
    function rhymeAnchorOf(i, si) {
        const value = ((filled[i] || [])[si] || '').trim();
        const slot = lines[i] && lines[i].slots[si];
        const source = value || (slot ? (slot.original || '') : '');
        const units = tokenize(source);
        if (!units.length) {
            return {kind: 'empty'};
        }
        const last = units[units.length - 1];
        if (/[a-z]/i.test(last)) { // 含 ASCII 字母的单元 = 英文词（与 tokenize 同口径）
            return {kind: 'english', text: last};
        }
        return {kind: 'hanzi', text: last};
    }

    /** 十八韵清单（懒加载一次；AI 页签的「韵」列要靠它把 rhymeBody 翻成十八韵名）。 */
    async function ensureRhymeMeta() {
        if (!rhymeMeta) {
            try {
                rhymeMeta = await Api.get('/api/song/rhyme/meta');
            } catch (e) {
                // 拿不到就只给「不限」，不挡填词
                return null;
            }
        }
        return rhymeMeta;
    }

    // ==================== 保存 / 自动保存 / 导出 / 重解析 ====================

    /** 填词名输入框的当前值（输入框不在页面上时回落草稿）。 */
    function nameValue(host) {
        const input = host && host.querySelector('#f-name');
        return input ? input.value.trim() : nameDraft;
    }

    /** 一次保存请求的载荷（手动保存与自动保存同一份，口径不会跑偏）。 */
    function savePayload(host) {
        return {
            fillId: tpl.fillId,
            name: nameValue(host),
            trackIndices: [...selected].join(','),
            lines,
            filled,
            // 视觉空位（句内空格）：后端按形状校验后落 gaps_json，形状对不上就退回现算
            gaps
        };
    }

    /**
     * 手动保存 = **存档点**：写库 + 把这一版记进本地（{@link MANUAL_KEY}），
     * 之后随便改（自动保存会一直替你写库），想退回来还能「恢复上次手动保存」。
     */
    async function save(host, btn) {
        await UI.withBusy(btn, '保存中…', async () => {
            try {
                const data = await Api.post('/api/song/fill/save', savePayload(host));
                rememberManual(host);
                adopt(data);
                keepSwaps();
                draw(host);
                UI.ok('已保存 ' + lines.length + ' 句');
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    /**
     * 每 5 秒自动保存：有改动才发，**不重画 DOM**（重画会打断正在敲的字 / 收起输入法），
     * 也不碰本地存档点 —— 存档点只由手动保存推进。
     */
    function startAutoSave(host) {
        clearInterval(autoTimer);
        autoTimer = setInterval(() => { autoSave(host); }, 5000);
    }

    async function autoSave(host) {
        // 编辑器已经不在页面上（切了模块）就空转：host 是 #page-body，模块切换时会被清空
        if (!dirty || !tpl || autoSaving || !host || !host.querySelector('.fill-lines')) {
            return;
        }
        autoSaving = true;
        const myRev = rev;
        try {
            await Api.post('/api/song/fill/save', savePayload(host));
            if (rev === myRev) {
                dirty = false;
                setStatus(host, '已自动保存 ' + clockNow());
            }
        } catch (e) {
            setStatus(host, '自动保存失败：' + e.message);
        } finally {
            autoSaving = false;
        }
    }

    /** adopt 会清空多音字替换（骨架可能换了）—— 保存前后骨架没变，把还指得着的键收回来。 */
    function keepSwaps() {
        const kept = {};
        for (const k of Object.keys(polySwap)) {
            if (noteMap.has(k)) {
                kept[k] = polySwap[k];
            }
        }
        polySwap = kept;
    }

    /** 把当前这一版记成「上次手动保存」的存档点（隐私模式 / 超配额存不下就算了，不影响保存本身）。 */
    function rememberManual(host) {
        if (!tpl || !tpl.fillId) {
            return;
        }
        try {
            localStorage.setItem(MANUAL_KEY + tpl.fillId, JSON.stringify({
                time: Date.now(),
                name: nameValue(host),
                trackIndices: [...selected].join(','),
                lines, filled, defaults, gaps, brackets
            }));
        } catch (e) {
            /* 存不下就不存 */
        }
    }

    /** 上次手动保存的存档点；声部换过（勾选轨不同）就不再算数，返回 null。 */
    function manualSnapshot() {
        if (!tpl || !tpl.fillId) {
            return null;
        }
        try {
            const snap = JSON.parse(localStorage.getItem(MANUAL_KEY + tpl.fillId) || 'null');
            return snap && snap.trackIndices === [...selected].join(',') ? snap : null;
        } catch (e) {
            return null;
        }
    }

    /** 恢复回上次手动保存的那一版（只改页面状态，写库还要用户自己点保存）。 */
    async function restoreManual(host) {
        const snap = manualSnapshot();
        if (!snap) {
            return;
        }
        if (!(await UI.confirm('恢复回 ' + fmtTime(new Date(snap.time).toISOString())
            + ' 手动保存的那一版？当前页面上的填写会被覆盖（库里的还在，可以先保存再恢复）。',
            {title: '恢复上次手动保存'}))) {
            return;
        }
        lines = (snap.lines || []).map(line => ({...line, slots: line.slots.slice()}));
        filled = (snap.filled || []).map(row => (row || []).slice());
        defaults = (snap.defaults || []).map(row => (row || []).slice());
        gaps = (snap.gaps || []).map(row => (row || []).slice());
        brackets = (snap.brackets || []).map(row => (row || []).slice());
        filled = lines.map((line, i) => padRow(filled[i] || [], line.slots.length));
        defaults = lines.map((line, i) => padRow(defaults[i] || [], line.slots.length));
        gaps = lines.map((line, i) => padRow(gaps[i] || [], line.slots.length));
        brackets = lines.map((line, i) => padRow(brackets[i] || [], line.slots.length));
        nameDraft = snap.name || '';
        polySwap = {};
        dirty = true;
        rev++;
        draw(host);
        UI.ok('已恢复回上次手动保存的那一版，确认无误再点「保存」');
    }

    async function reparse(host, btn) {
        if (dirty && !(await UI.confirm('重新解析会按磁盘上的 svp 重算骨架与分句，'
            + '未保存的改动会丢。继续？', {title: '重新解析 svp'}))) {
            return;
        }
        // 同 load：解析期间挂起自动保存（收尾的 draw() 会重新起上）
        clearInterval(autoTimer);
        await UI.withBusy(btn, '解析中…', async () => {
            try {
                adopt(await Api.post('/api/song/fill/reparse', {fillId: tpl.fillId}));
                draw(host);
                UI.ok('已按磁盘上的 svp 重解析');
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    /** 导出浮层：回填模板文本（按轨各一份）/ lrc（只生成文本，不写磁盘） */
    // ==================== 批量填词 ====================

    /**
     * 对话框默认内容里一句的歌词：填的词 → 匹配的汉字（defaults）→ 原词，与 lrc 导出
     * 同一口径（- / br 不算歌词）。合唱句里完全被另一组包含的组不重复出现（需求 3 同口径）。
     */
    function lineLyric(i) {
        const groups = lineGroups(lines[i]);
        if (groups.length < 2) {
            // 单组：没有「主流 / 括号」两段可分，括号标记就当没看见（原样一行）
            return groupLyric(i, groups[0]);
        }
        // 括号声部（原歌词行尾括号里那句）单拎出来写进括号，其余是主流
        let main = '';
        let sub = '';
        lyricGroups(i).forEach(slots => {
            const text = groupLyric(i, slots);
            if (bracketGroup(i, slots)) {
                sub += (sub ? ' ' : '') + text;
            } else {
                main += (main ? ' ' : '') + text;
            }
        });
        if (!sub) {
            return main;
        }
        return main ? main + '（' + sub + '）' : '（' + sub + '）';
    }

    /**
     * 这一句里「算歌词的组」，顺序 = {@link lineLyric} 的文本顺序：主流声部在前、括号声部在后。
     *
     * <p>被另一组完全包含的组不重复出现（合唱整轨复制的那份），两组完全相同（互相包含）只留
     * 组号小的那份 —— 与 lrc 导出的「一组一句」同一口径。只有 br / 延音的组取不出字，不算歌词。
     */
    function lyricGroups(i) {
        const line = lines[i];
        const groups = lineGroups(line);
        const kept = groups.length < 2
            ? [groups[0]]
            : groups
                .map((slots, gi) => ({slots, gi}))
                .filter(({slots, gi}) =>
                    !groups.some((other, oi) => oi !== gi && groupCovered(line, slots, other)
                        // 两组完全相同（互相包含）只丢后出现的那份：全丢会让这句歌词成空串
                        && !(groupCovered(line, other, slots) && gi < oi)))
                .map(({slots}) => slots);
        const main = [];
        const sub = [];
        kept.forEach(slots => {
            if (!groupLyric(i, slots)) {
                return;
            }
            (bracketGroup(i, slots) ? sub : main).push(slots);
        });
        return main.concat(sub);
    }

    /**
     * 批量填词时一句要吃字的组：与对话框里那一行文本的声部顺序一一对应（见 {@link lyricGroups}）。
     * 整句取不出字（只有 br / 延音）时兜回最长的那组，免得一个字都没地方落。
     */
    function lineTargets(i) {
        const targets = lyricGroups(i);
        return targets.length ? targets : [longestGroup(lines[i])];
    }

    /** 组 g 的每个槽位是否都在组 other 里有同音符（onset 容差 + 同原词）配对 —— 完全被包含。 */
    function groupCovered(line, g, other) {
        return g.every(si => twinSlot(line, other, si) >= 0);
    }

    /**
     * 一格写出来的字（填的词 → 匹配汉字 → 原词；- / br / 0 没有字）。
     *
     * 导出 / 回填（groupLyric）与 Ctrl+C 复制都走这一处：同一格在两处必须是同一个字，
     * 否则「屏幕上看着是甲、复制出去是乙」。
     */
    function cellText(i, si) {
        const slot = lineAt(i).slots[si];
        if (!slot || slot.slotType === 'BREATH') {
            return '';
        }
        const v = ((filled[i] || [])[si] || '').trim();
        if (v) {
            return v;
        }
        const def = ((defaults[i] || [])[si] || '').trim();
        if (def) {
            return def;
        }
        // 记号格（延音 / 静音占位）本身取不出字；点开填了字的走上面的 v 分支
        if (isDash(slot) || isZero(slot)) {
            return '';
        }
        return slot.original || '';
    }

    /** 一组槽位的歌词文本（每格走 cellText）。 */
    function groupLyric(i, slots) {
        return slots.map(si => cellText(i, si)).join('');
    }

    // 拖选的 Ctrl+C 复制走 groupLyric（与导出同一套取字规则：首尾的 `-` / br 取不出字，
    // 正好把「选宽了」的边角抹掉）；它拿到的槽位是 selSlots 挑出来的**同一行**那些格 ——
    // 交错排布的多声部句里按平坦区间取字会把另一条轨的字夹进来，粘回去顺序就乱了。

    /** 写剪贴板；失败回落临时 textarea + execCommand（http 非 localhost 访问时没有 clipboard API）。 */
    async function copyText(text) {
        if (!text) {
            UI.err('选中的格都是空的');
            return;
        }
        try {
            await navigator.clipboard.writeText(text);
        } catch {
            const area = document.createElement('textarea');
            area.value = text;
            area.style.position = 'fixed';
            area.style.opacity = '0';
            document.body.appendChild(area);
            area.select();
            const done = document.execCommand('copy');
            area.remove();
            if (!done) {
                UI.err('复制失败');
                return;
            }
        }
        UI.ok('已复制 ' + text);
    }

    function lineAt(i) {
        return lines[i];
    }

    /**
     * 批量文本 → 填写单元：连续字母 / 数字 / 撇号算一个英文词，一个汉字一个单元；
     * 空白与其他标点跳过。「-」也算一个单元，与汉字**完全同权**——按序占一个可填格、
     * 把 `-` 写进去（格子里看得见），不特殊认领原词是 `-` 的延音格。
     *
     * <p>于是「-」可以当**占位符**用：占住一个格子让后面的字对得上位，但不产生歌词 ——
     * 导出 lrc 时它会被剔掉（见 {@code LyricFillService.lineText}）。
     */
    function batchUnits(text) {
        const units = [];
        let word = '';
        for (const ch of String(text || '')) {
            if (/[A-Za-z0-9']/.test(ch)) {
                word += ch;
                continue;
            }
            if (word) {
                units.push(word);
                word = '';
            }
            if (ch === '-' || /\p{Script=Han}/u.test(ch)) {
                units.push(ch);
            }
        }
        if (word) {
            units.push(word);
        }
        return units;
    }

    function openBatch() {
        const m = UI.modal(`
            <div class="modal modal-wide">
                <div class="modal-head"><span>批量填词 · ${UI.esc(tpl.originalName || '')}</span>
                    <button class="modal-close" type="button">×</button></div>
                <div class="modal-body">
                    <div class="hint" style="margin-top:0"><b>一句一行</b>（每行对应一句歌词）。应用时
                        <b>忽略标点符号</b>（<b>「-」除外</b>）：连续英文字母算一个词、汉字逐字、
                        <b>「-」各占一格</b>，依次填进该句的格子里（这一句若合并了多个轨 / 声部，
                        就按声部顺序接着往下填），「-」和汉字一样填进去、格子里看得见，
                        但<b>导出 lrc 时会把它去掉</b>，所以它能当占位符：占住一格让后面的字对齐，
                        又不进歌词。<br>
                        <b>一句被拆开 / 几句被合并</b>时不用自己拆行：某行的字数正好等于<b>后面连续几句</b>
                        的格子数之和，就把这一行拆开填进那几句；连续几行的字数之和正好等于<b>这一句</b>的
                        格子数，就把那几行并起来填进这一句（原词 5 + 2 字 → 填一行 7 字；
                        原词 7 字 → 填 5 + 2 两行）。<b>字数必须正好合上</b>，差一个字就不拆不并。
                        格子比字多时保留原值，字比格子多时丢弃并提示。
                        <label class="small" style="margin-left:12px"><input type="checkbox"
                            id="b-skip-dash" checked> 忽略延音（-）</label>
                        <label class="small" style="margin-left:12px"><input type="checkbox"
                            id="b-reflow"> 重新分行：<b>现有分句整段作废</b>（先合并、旧视觉空位清空），
                            再按文本从头重排 —— <b>换行 = 新句子、行内空格 = 视觉空位</b></label></div>
                    <textarea id="b-text" rows="16" spellcheck="false"
                        style="width:100%;margin-top:8px;line-height:1.8"></textarea>
                </div>
                <div class="modal-foot">
                    <button type="button" class="btn-primary" data-act="apply">应用到填写行</button>
                    <button type="button" class="btn-plain" data-act="close">取消</button>
                </div>
            </div>`);
        m.box.querySelector('#b-text').value = lines.map((_, i) => lineLyric(i)).join('\n');
        m.box.querySelector('.modal-close').onclick = m.close;
        m.box.querySelector('[data-act="close"]').onclick = m.close;
        m.box.querySelector('[data-act="apply"]').onclick = (ev) => applyBatch(m, ev.target);
    }

    /** 按对话框内容填写：默认逐句对位（字数对不上时按字数拆 / 并，见 applyByLine）；
     *  勾「重新分行」则整段依字序灌入、文本的换行与空格重排句界与视觉空位（见 applyReflow）。 */
    function applyBatch(m, btn) {
        const rows = String(m.box.querySelector('#b-text').value || '')
            .replace(/\r\n?/g, '\n').split('\n');
        const skipDash = m.box.querySelector('#b-skip-dash').checked;
        const reflow = m.box.querySelector('#b-reflow').checked;
        let extra;
        let note = '';
        if (reflow) {
            extra = applyReflow(rows, skipDash);
            note = '，并按文本重排了分句与视觉空位';
        } else {
            extra = applyByLine(rows, skipDash);
            note = lastAlignNote;
        }
        markDirty(pageHost);
        m.close();
        clearSel(pageHost);   // 重新分行会换掉整批句子的下标，旧选区（i / si）不再指向原格
        draw(pageHost);   // 重画后填写格的值从状态重读 —— 否则格子里不显示新词
        UI.ok('已填写'
            + (extra ? '，' + extra + ' 个字放不下被忽略' : '')
            + note);
    }

    /** 句内最长的组（代表轨那行；并列取先出现的）。 */
    function longestGroup(line) {
        return lineGroups(line).reduce((best, cur) => cur.length > best.length ? cur : best);
    }

    /**
     * 这一句能吃掉几个填写单元：句内各组（{@link lineTargets}）里除 br、以及勾了「忽略延音」
     * 时要跳过的延音格 —— 就是 {@link fillLine} 会写进去的那几格。
     *
     * <p>文本行与句子的对位（{@link alignByLine}）拿它当长度，所以这里**必须**与 fillLine 的
     * 填写口径一致：两处对不上，拆 / 并的判据就是错的。
     */
    function capacity(i, skipDash) {
        let n = 0;
        lineTargets(i).forEach((slots) => {
            slots.forEach((si) => {
                const slot = lines[i].slots[si];
                if (autoSkip(slot) || (skipDash && isDash(slot))) {
                    return;
                }
                n++;
            });
        });
        return n;
    }

    /** 把 units[from..] 依序写进第 i 句的格子（br 与「忽略延音」时跳过的延音格占位不占字）。 */
    function fillLine(i, units, from, skipDash) {
        let u = from;
        lineTargets(i).forEach((slots) => {
            slots.forEach((si) => {
                const slot = lines[i].slots[si];
                if (autoSkip(slot)) {
                    return;
                }
                // 延音格默认跳过（它的 - 只是上个字的拉长）；不勾「忽略延音」才把字填进去（激活成真音）
                if (skipDash && isDash(slot)) {
                    return;
                }
                if (u < units.length) {
                    setValue(i, si, units[u++]);
                }
            });
        });
        syncVoiceCopies(i);   // 各唱的声部之间只补空（不抢人家自己的字）
        mirrorCopies(i);      // 副本声部整行对齐到吃字的那一行（盖掉上一次填的残留）
        return u;
    }

    /**
     * 清空这一份填词的**所有**格子（多音字回填替换字一并清掉）。只改内存并标未保存 —— 落库
     * 仍旧走「保存」，所以点错了刷新页面就能撤回（与页面上其它改动同一套语义）；清完延音格
     * 会跟着还原成 `-`（走 {@link setValue}，与手动清一格同一口径）。
     */
    function clearFill(host) {
        let n = 0;
        lines.forEach((_, i) => {
            (filled[i] || []).forEach((v, si) => {
                if (v) {
                    n++;
                }
                setValue(i, si, '');
            });
        });
        polySwap = {};
        clearSel(host);
        markDirty(host);
        draw(host);
        UI.ok(n ? '已清空 ' + n + ' 个字（记得保存）' : '本来就是空的（记得保存才会落库）');
    }

    /** 丢一个单元的代价权重：远大于「拆并次数」，于是对位**先求少丢字、再求少折腾**。 */
    const DROP_COST = 100000;

    /** 上一次按句对位的拆 / 并摘要（空串 = 逐句一一对应），只给提示语用。 */
    let lastAlignNote = '';

    /**
     * 文本行 ↔ 原句的对位（批量填词「按句模式」）：默认一行对一句，但**允许一句被拆、几句被并**。
     *
     * <p>判据是**可填格数**（不是韵脚）：某一行文本的单元数正好等于后面连续几句的容量和
     * → 这一行拆开填进那几句；连续几行的单元数正好等于这一句的容量 → 那几行并起来填这一句。
     * 「正好」是硬条件，差一个字都不拆不并 —— 用户报的场景就是 5 + 2 = 7、7 = 5 + 2 这种整数关系，
     * 放宽成「大约够」会让本来对得好好的文本被乱拆。逐句对位（一字不差或字多出来被丢）永远是
     * 候选之一，代价函数保证**只要逐句对位不丢字就仍然走逐句对位**（丢字优先少、其次少折腾）——
     * 所以「一行一句、字数也对得上」的常规用法与从前逐字一致。
     *
     * <p>动态规划：{@code dp[i][j]} = 从句 i / 文本行 j 起的最小代价，终点是「句子用完」或
     * 「文本行用完」——句子剩下不填（保留原值）不花代价，文本行剩下整行丢弃（按单元数计费）。
     * 返回的是一串操作（`{line, row, lines, rows}`，lines / rows ≥ 1），按序执行即可。
     */
    function alignByLine(caps, counts) {
        const L = caps.length;
        const R = counts.length;
        // 前缀和：判「连续几句正好装下这一行」与「连续几行正好填满这一句」都靠它
        const capSum = [0];
        caps.forEach(c => capSum.push(capSum[capSum.length - 1] + c));
        const rowSum = [0];
        counts.forEach(c => rowSum.push(rowSum[rowSum.length - 1] + c));

        const dp = [];
        const choice = [];
        for (let i = 0; i <= L; i++) {
            dp.push(new Array(R + 1).fill(0));
            choice.push(new Array(R + 1).fill(null));
        }
        for (let j = 0; j < R; j++) {
            dp[L][j] = (rowSum[R] - rowSum[j]) * DROP_COST;   // 句子用完了，剩下的行整行丢
        }
        for (let i = L - 1; i >= 0; i--) {
            for (let j = R - 1; j >= 0; j--) {
                // ① 逐句对位：多出来的字丢掉；格子比字多则保留原值（不花代价）
                let best = Math.max(0, counts[j] - caps[i]) * DROP_COST + dp[i + 1][j + 1];
                let pick = {lines: 1, rows: 1};
                // ② 这一行拆到后面连续几句：字数正好等于那几句的容量和（差一个字都不拆）
                for (let m = 2; i + m <= L; m++) {
                    const sum = capSum[i + m] - capSum[i];
                    if (sum > counts[j]) {
                        break;
                    }
                    if (sum < counts[j]) {
                        continue;                                 // 后面还有容量为 0 的句子，继续找
                    }
                    const cand = 1 + dp[i + m][j + 1];            // 拆 = 一次「折腾」，不丢字
                    if (cand < best) {
                        best = cand;
                        pick = {lines: m, rows: 1};
                    }
                }
                // ③ 后面连续几行并进这一句：字数正好等于这一句的容量（差一个字都不并）
                for (let k = 2; j + k <= R; k++) {
                    const sum = rowSum[j + k] - rowSum[j];
                    if (sum > caps[i]) {
                        break;
                    }
                    if (sum < caps[i]) {
                        continue;                                 // 中间夹着空行也照样能并
                    }
                    const cand = 1 + dp[i + 1][j + k];
                    if (cand < best) {
                        best = cand;
                        pick = {lines: 1, rows: k};
                    }
                }
                dp[i][j] = best;
                choice[i][j] = pick;
            }
        }

        const ops = [];
        let i = 0;
        let j = 0;
        while (i < L && j < R) {
            const pick = choice[i][j];
            ops.push({line: i, row: j, lines: pick.lines, rows: pick.rows});
            i += pick.lines;
            j += pick.rows;
        }
        return ops;
    }

    /**
     * 默认模式：一句一行。单元按这一句的声部顺序依次落进**每个**声部（见 {@link lineTargets}）——
     * 一句的词是一整行，合并成一句的几个轨（声部）各自吃掉属于自己的那一段；整轨复制的那份
     * 不在文本里，仍靠联动复制（syncVoiceCopies）跟上。
     *
     * <p>行与句不再死绑下标：字数与格子数对不上时按 {@link alignByLine} 拆 / 并
     * （原词 5+2 字填一行 7 字 → 拆；原词 7 字填 5+2 两行 → 并）。返回放不下的单元数，
     * 拆并次数写在 {@link lastAlignNote} 里给提示语用。
     */
    function applyByLine(rows, skipDash) {
        const unitsOf = rows.map(row => batchUnits(row));
        const ops = alignByLine(lines.map((_, i) => capacity(i, skipDash)),
            unitsOf.map(units => units.length));
        let extra = 0;
        let split = 0;
        let merge = 0;
        ops.forEach(op => {
            const units = [];
            for (let j = op.row; j < op.row + op.rows; j++) {
                units.push(...unitsOf[j]);
            }
            if (op.lines > 1) {
                split++;
            }
            if (op.rows > 1) {
                merge++;
            }
            let u = 0;
            for (let i = op.line; i < op.line + op.lines; i++) {
                u = fillLine(i, units, u, skipDash);
            }
            extra += Math.max(0, units.length - u);
        });
        // 没被任何一「句」吃到的文本行（比句数多出来的行）：整行放不下，字数计入「放不下」
        let used = 0;
        ops.forEach(op => {
            used += op.rows;
        });
        for (let j = used; j < rows.length; j++) {
            extra += unitsOf[j].length;
        }
        lastAlignNote = (split || merge)
            ? (split ? '，' + split + ' 行按字数拆到相邻几句' : '')
                + (merge ? '，' + merge + ' 处把相邻几行并进一句' : '')
            : '';
        return extra;
    }

    /**
     * 「重新分行」：**现有分句整段作废**——所有句的槽位按句序拼成一条、旧视觉空位全清掉，
     * 再完全按对话框里的文本重建：文本的换行 = 句界、行内空格 = 视觉空位（空格不占音符，
     * 空位标在它前面那一格之后）。
     *
     * <p>不能「在现有分句上打补丁」：补丁法按旧句下标记切点、再调 splitLine / mergeLine，
     * 而这两下都会 splice 数组，切完后面的句下标全挪了 —— 再拿旧下标去看「相邻两句是不是
     * 同一文本行」就全是错位判断，该并的并不掉。这里中间过程全在本地数组上做，
     * 只在最后整体落地一次（下标自始至终是合并序列的）。
     *
     * <p>单元比槽多时丢弃（返回丢弃数）；文本没覆盖到的尾部槽位 / 原始句一律原样保留 ——
     * 一个字都没落下时整个函数是空操作。
     */
    function applyReflow(rows, skipDash) {
        // 1) 文本流：每个单元记「前面有没有空格」与「属于哪一文本行」
        const stream = [];
        rows.forEach((line, li) => {
            const re = /\S+/g;
            let hit;
            while ((hit = re.exec(line)) !== null) {
                // 行内空格才是视觉空位；行首的前置空白是换行本身，不算
                const hasSpace = hit.index > 0;
                // 只有词的首单元继承前置空格（词中间的字不各开空位）
                batchUnits(hit[0]).forEach((u, ui) =>
                    stream.push({u: u, space: hasSpace && ui === 0, line: li}));
            }
        });

        // 2) 整段合并：槽位 + 组号 + 值 + 默认词 + 括号标记按句序拼成一条，旧视觉空位一律作废。
        //    组号是**句内局部**的（后端按句内首现序 0 递增，见 FillLine.groups），跨句直接拼
        //    会撞号（轨1 在 A 句是 1、在 B 句可能是 0）—— 拿「这组用到的轨号集合」当身份重新
        //    编号，跨句同轨才算同一组，合唱的另一组才会被当成同一行声部
        const slots = [];
        const groups = [];
        const values = [];
        const defs = [];
        const bracketsRow = [];
        const offset = [];                     // 每句在合并序列里的起点
        const groupIdOf = new Map();           // 轨号集合 → 全局组号
        let nextGroup = 0;
        lines.forEach((line, i) => {
            offset.push(slots.length);
            const ids = line.groups && line.groups.length === line.slots.length
                ? line.groups : line.slots.map(() => 0);
            const members = new Map();          // 旧组号 → 槽位下标
            ids.forEach((g, si) => {
                if (!members.has(g)) {
                    members.set(g, []);
                }
                members.get(g).push(si);
            });
            const renamed = new Map();
            members.forEach((sis, g) => {
                const tracks = [...new Set(sis.map(si => line.slots[si].trackIndex))];
                tracks.sort((a, b) => a - b);
                const key = tracks.join(',');
                if (!groupIdOf.has(key)) {
                    groupIdOf.set(key, nextGroup++);
                }
                renamed.set(g, groupIdOf.get(key));
            });
            line.slots.forEach((slot, si) => {
                slots.push(slot);
                groups.push(renamed.get(ids[si]));
                values.push((filled[i] || [])[si] || '');
                defs.push((defaults[i] || [])[si] || '');
                bracketsRow.push(!!(brackets[i] || [])[si]);
            });
        });

        // 3) 依字序填入各句可填槽 —— 一句里的几个声部按 lineTargets 的顺序接着吃字（与对话框
        //    那一行的文本顺序一致；整轨复制的那份不在文本里，靠联动复制跟上）。记下每格的文本行号；
        //    prev = 上一个填入的格（合并序列下标）—— 行内空格要标在它后面
        const marks = [];                      // 合并下标 → {line, space, prev}
        let cursor = 0;
        let prev = -1;
        let covered = -1;                      // 最后一个吃到字的原始句
        lines.forEach((line, i) => {
            lineTargets(i).forEach((slots) => {
                slots.forEach(si => {
                    const slot = line.slots[si];
                    if (cursor >= stream.length) {
                        return;
                    }
                    if (autoSkip(slot) || (skipDash && isDash(slot))) {
                        return;
                    }
                    const k = offset[i] + si;
                    const item = stream[cursor++];
                    writeCell(values, k, slot, item.u);
                    marks[k] = {line: item.line, space: item.space, prev: prev};
                    prev = k;
                    covered = i;
                });
            });
        });
        if (covered < 0) {
            // 一个字都没落下去（文本空 / 全是放不下的标点）：不该顺手把整首并成一句
            return Math.max(0, stream.length - cursor);
        }

        // 4) 每格的文本行号：没填到的格跟随前一个有字的格；段首那几格（br / 被跳过的延音）
        //    前面没有有字的格，回填成「首个有字的格」那一行 —— 否则它们会被当成一行单独切出去
        const runs = [];
        let cur = null;
        let first = null;
        for (let k = 0; k < slots.length; k++) {
            if (marks[k]) {
                cur = marks[k].line;
                if (first == null) {
                    first = cur;
                }
            }
            runs.push(cur);
        }
        for (let k = 0; k < runs.length; k++) {
            if (runs[k] == null) {
                runs[k] = first;
            }
        }

        // 5) 切点：文本行号变化处 = 新句界；再补一刀在「最后一个吃到字的原始句」之后，
        //    让文本没覆盖到的尾部保留原来的分句（不然它们会全被粘到最后一句上，而它们
        //    本来就是按原歌词分好的）。延音只留句尾：切点落在延音上就往后挪，与手动分行同规则。
        const cuts = [];
        for (let k = 1; k < slots.length; k++) {
            if (runs[k] != null && runs[k] !== runs[k - 1]) {
                cuts.push(k);
            }
        }
        const coveredEnd = offset[covered] + lines[covered].slots.length;
        if (coveredEnd < slots.length) {
            cuts.push(coveredEnd);
            // 尾部这些原始句自己的句界也要留住
            for (let i = 0; i < lines.length; i++) {
                if (offset[i] > coveredEnd) {
                    cuts.push(offset[i]);
                }
            }
        }
        const bounds = [0];
        cuts.forEach(k => {
            let at = k;
            while (at < slots.length && isDash(slots[at])) {
                at++;
            }
            if (at < slots.length && at > bounds[bounds.length - 1]) {
                bounds.push(at);
            }
        });
        bounds.push(slots.length);

        // 6) 按新句界整体重建：值 / 默认词 / 括号标记跟着槽位切，视觉空位清零后按文本重标
        const segAt = new Array(slots.length);
        const newLines = [];
        const newFilled = [];
        const newDefaults = [];
        const newBrackets = [];
        const newGaps = [];
        for (let p = 0; p + 1 < bounds.length; p++) {
            const a = bounds[p];
            const b = bounds[p + 1];
            for (let k = a; k < b; k++) {
                segAt[k] = p;
            }
            newLines.push(mkLine(slots.slice(a, b), groups.slice(a, b)));
            newFilled.push(values.slice(a, b));
            newDefaults.push(defs.slice(a, b));
            newBrackets.push(bracketsRow.slice(a, b));
            newGaps.push(new Array(b - a).fill(false));
        }
        marks.forEach((mark, k) => {
            // 空位标在「它前面那一格」之后，所以要有前一格、且它是**同一文本行**里的
            // 前一个词 —— 行首缩进那点空白（前一格还在上一行）不算视觉空位
            if (!mark || !mark.space || mark.prev < 0) {
                return;
            }
            const before = marks[mark.prev];
            if (!before || before.line !== mark.line) {
                return;
            }
            const p = segAt[mark.prev];
            // 与后端 prolongationEnd / chainTail 同口径：空位一律落在延音链之后，
            // 字与它的「-」之间不许插空格（整段重排后同样成立）
            newGaps[p][chainTail(newLines[p], mark.prev - bounds[p])] = true;
        });

        // 空位展开到同音符的其它声部（后端 gaps 也是「展开到组的全部可填成员」）——
        // 合唱两行唱同一句词，空位得两行都画
        newGaps.forEach((row, p) => {
            const voices = lineGroups(newLines[p]);
            if (voices.length < 2) {
                return;
            }
            row.forEach((on, si) => {
                if (!on) {
                    return;
                }
                voices.forEach(v => {
                    const twin = twinSlot(newLines[p], v, si);
                    if (twin >= 0) {
                        // 同上：另一声部也记在**它自己**的延音链之后
                        row[chainTail(newLines[p], twin)] = true;
                    }
                });
            });
        });

        lines = newLines;
        filled = newFilled;
        defaults = newDefaults;
        brackets = newBrackets;
        gaps = newGaps;
        // 合唱的另一组跟着复制（与默认模式同口径；对不上「同音符」的两组不复制）—— 副本声部
        // 整行对齐到源（连带清掉残留），整轨复制的那份与源一字不差
        for (let i = 0; i < lines.length; i++) {
            syncVoiceCopies(i);
            mirrorCopies(i);
        }
        return Math.max(0, stream.length - cursor);
    }

    /** 当前浮层里显示的是哪一种导出（'' = 还没生成）；勾「导出拼音」时用它决定要不要重算 */
    let exportShown = '';

    function openExport() {
        const m = UI.modal(`
            <div class="modal modal-wide">
                <div class="modal-head"><span>导出 · ${UI.esc(tpl.originalName || '')}</span>
                    <button class="modal-close" type="button">×</button></div>
                <div class="modal-body">
                    <div class="row">
                        <button type="button" class="btn-plain" data-act="text">回填模板文本</button>
                        <button type="button" class="btn-plain" data-act="lrc">lrc 歌词</button>
                        <label class="small">lrc 头 offset(ms)
                            <input type="number" id="x-offset" step="10" value="0" style="width:90px"></label>
                        <label class="small"><input type="checkbox" id="x-pinyin"> 导出拼音</label>
                    </div>
                    <div class="hint" style="margin-top:8px">
                    <ul>
                        <li>回填模板文本按轨各一份，<b>空格分隔</b>每个音符（整轨一行）</li>
                        <li><code>-</code> 延音、<code>br</code> 换气、<code>‘</code> 喉塞音原样保留，其余是填的词（没填到的音符回落原词；填了字的延音写回填的那个字）。</li>
                        <li><b>导出拼音</b>只改回填模板文本：汉字逐个转成无声调拼音（ü 写作 v，如「女」→ nv），
                        多音字取一个<b>常用读音</b>——已在格子下点选过回填替换的按替换字读，其余任取一个常用音。
                        <code>-</code> / <code>br</code> / 英文词原样不动，lrc 不受影响。勾选后已生成的内容会跟着重算。</li>
                        <li>复制后整段粘到 SynthV 那一轨的第一个音符上，注意<b>不要勾选“按字符隔开”</b></li>
                        <li>lrc 的时间戳取句首音符，两句之间超过 1 秒没人唱（上一句最后一个字唱完到下一句起唱）时补一个<b>只有时间戳的空行</b>，
                        结尾（最后一句唱完到全曲结束）超过 1 秒也补一个，间奏 / 尾奏期间不让上一句一直挂着。</li>
                        <li>末尾<b>恒定再补一行空歌词</b>，时间是<b>最后一个音符结束 + 1 秒</b> —— 播放器读到它把最后一句清掉，
                        末尾拖长音时不会一直挂着。</li>
                        <li>lrc <b>开头</b>会写上曲名、填词名和署名（署名在配置页里填，<b>没填就不写这一行</b>）。</li>
                    </ul></div>
                    <div id="x-body" class="modal-tab"></div>
                </div>
                <div class="modal-foot">
                    <button type="button" class="btn-plain" data-act="close">关闭</button>
                </div>
            </div>`);
        exportShown = '';
        m.box.querySelector('.modal-close').onclick = m.close;
        m.box.querySelector('[data-act="close"]').onclick = m.close;
        const text = m.box.querySelector('[data-act="text"]');
        const lrc = m.box.querySelector('[data-act="lrc"]');
        text.onclick = () => doExport(m, text, 'text');
        lrc.onclick = () => doExport(m, lrc, 'lrc');
        // 拼音开关只对回填模板文本有意义：lrc 是给人看的歌词，换拼音没意义
        const pinyinBox = m.box.querySelector('#x-pinyin');
        pinyinBox.onchange = () => {
            if (exportShown === 'text') {
                doExport(m, text, 'text');
            }
        };
        // lrc 打开时把开关置灰：省得用户以为它能改 lrc
        lrc.addEventListener('click', () => {
            pinyinBox.disabled = true;
        });
        text.addEventListener('click', () => {
            pinyinBox.disabled = false;
        });
    }

    async function doExport(m, btn, type) {
        const body = m.box.querySelector('#x-body');
        await UI.withBusy(btn, '生成中…', async () => {
            body.innerHTML = UI.spinner('生成中…');
            try {
                const data = await Api.post('/api/song/fill/export', {
                    fillId: tpl.fillId,
                    type,
                    offsetMs: type === 'lrc'
                        ? Number(m.box.querySelector('#x-offset').value || 0) : null,
                    lines,
                    filled,
                    // 视觉空位：与 lines 同形状；导出 lrc 按它还原句内空格（没传就按库里的）
                    gaps,
                    // 多音字的回填替换：只影响回填模板文本，不影响 lrc 歌词
                    swaps: type === 'text' ? Object.assign({}, polySwap) : {},
                    // 导出拼音：同样只对回填模板文本有意义（lrc 是给人看的歌词）
                    pinyin: type === 'text' && m.box.querySelector('#x-pinyin').checked
                });
                exportShown = type;
                body.innerHTML = type === 'lrc'
                    ? exportBlock('lrc', data.text, (tpl.originalName || 'lyrics') + '.lrc', '行')
                    : (data.tracks || []).map(t =>
                        exportBlock('轨 #' + t.trackIndex + ' ' + (t.trackName || ''),
                            t.text, null, '个音符')).join('');
                bindCopy(m.box);
            } catch (e) {
                body.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            }
        });
    }

    /** unit = 复制提示里的量词：回填模板文本按空格数音符，lrc 按行 */
    function exportBlock(label, text, downloadName, unit) {
        return '<div class="fill-export" data-unit="' + UI.esc(unit || '行') + '"'
            + (downloadName ? ' data-name="' + UI.esc(downloadName) + '"' : '') + '>'
            + '<div class="row" style="justify-content:space-between">'
            + '<strong class="small">' + UI.esc(label) + '</strong>'
            + '<div class="row"><button type="button" class="btn-plain act-copy">复制</button>'
            + (downloadName
                ? '<button type="button" class="btn-plain act-download">下载</button>' : '')
            + '</div></div>'
            + '<textarea class="fill-textarea" readonly rows="8">' + UI.esc(text) + '</textarea>'
            + '</div>';
    }

    function bindCopy(box) {
        box.querySelectorAll('.fill-export').forEach(block => {
            const area = block.querySelector('.fill-textarea');
            block.querySelector('.act-copy').onclick = async () => {
                try {
                    await navigator.clipboard.writeText(area.value);
                } catch {
                    // 非 https / 无剪贴板权限时回落（localhost 一般走不到这里）
                    area.select();
                    document.execCommand('copy');
                }
                const unit = block.dataset.unit || '行';
                const count = unit === '行'
                    ? area.value.split('\n').filter(s => s !== '').length
                    : (area.value.trim() ? area.value.trim().split(/\s+/).length : 0);
                UI.ok('已复制 ' + count + ' ' + unit);
            };
            const download = block.querySelector('.act-download');
            if (download) {
                download.onclick = () => {
                    const url = URL.createObjectURL(
                        new Blob([area.value], {type: 'text/plain;charset=utf-8'}));
                    const a = document.createElement('a');
                    a.href = url;
                    a.download = block.dataset.name || 'lyrics.lrc';
                    a.click();
                    URL.revokeObjectURL(url);
                };
            }
        });
    }

    // ==================== 小工具 ====================

    /**
     * 与后端 LyricFillAligner.tokenize 同规则（见文件头注释）：按空白 split、含 ASCII 字母的
     * token 整体算一个单元、其余逐码点拆。
     *
     * 空白集合刻意写成 `[^\t\n\x0B\f\r ]`（= Java 默认 `\s` 的补集）而不是 JS 的 `\S` ——
     * 后者把全角空格 U+3000 也算空白，后端不算，同一段文本两边会切出不同的单元数。
     */
    function tokenize(text) {
        const units = [];
        if (!text) {
            return units;
        }
        const re = /[^\t\n\x0B\f\r ]+/g;
        let match;
        while ((match = re.exec(text)) !== null) {
            const token = match[0];
            if (/[A-Za-z]/.test(token)) {
                units.push(token);
                continue;
            }
            for (const ch of token) {   // for...of 按码点迭代，代理对不会被拆开
                units.push(ch);
            }
        }
        return units;
    }

    /** 一行槽位值补齐 / 截断到 size（换分句后与新的槽位对齐） */
    function padRow(row, size) {
        const out = (row || []).slice(0, size);
        while (out.length < size) {
            out.push('');
        }
        return out;
    }

    /** 这一句填了几格（有值就算，含填了字的延音；合唱重复组每格单独计）。 */
    function countDone(line, row) {
        return line.slots.filter((slot, si) => !isBreath(slot) && (row[si] || '').trim()).length;
    }

    const isFillable = (slot) => slot.slotType === 'HANZI' || slot.slotType === 'ENGLISH';

    const isBreath = (slot) => slot.slotType === 'BREATH';

    /** 延音槽位：不管点没点开，original 都是 `-` */
    const isDash = (slot) => slot.original === '-';

    /**
     * 静音占位槽位（`0`）：与延音 / 换气一样不自动填词、不进 lrc，但**回填模板文本要原样写回 `0`**。
     * 同 {@link isDash}，按 `original` 判 —— 点开填了字之后 `slotType` 会变成 `HANZI`，
     * 只有 `original` 一直留着 `0`。分句上它不站队（既不必跟前面也不必跟后面），
     * 所以它是独立一类、不复用 isDash / isBreath。
     */
    const isZero = (slot) => slot.original === '0';

    /** 自动前进 / 批量填词恒跳过的记号格：换气 / 静音占位（延音另有「忽略延音」开关，见 stepInLine） */
    const autoSkip = (slot) => isBreath(slot) || isZero(slot);

    /**
     * 这一格是不是「拼音没匹配到汉字」（标红）：后端下发的 pinyin（这次的默认词是拿 demo
     * 歌词配出来的）、这一格本身是拼音音符（slotType 是 ENGLISH，音符写着拼音）、没匹配出
     * 默认词、也还没填。
     *
     * **判据必须是模板级的**：某一句整句一个汉字都没配上时那一行的 defaults 整行为空，
     * 用「这行有没有默认词」当闸门会让错得最狠的整句全空反而不标红（用户实测报的丢提示）。
     * 汉字音符不标：作者直接写汉字的那几格，歌词就在音符里，没有「没配上」这回事 ——
     * 汉字模板（整份都不写拼音）也一样，它的拼音格标、汉字格不标。
     */
    const isUnmatchedPinyin = (slot, def, value) =>
        pinyin && slot.slotType === 'ENGLISH' && !def && !(value || '').trim();

    const key = (trackIndex, noteIndex) => trackIndex + ':' + noteIndex;

    /** 轨色：按骨架里 tracks 的顺序取一圈固定色。轨选择条与两行格子共用它，即图例 */
    function colorOf(trackIndex) {
        const at = (tpl.tracks || []).findIndex(t => t.trackIndex === trackIndex);
        return PALETTE[(at < 0 ? 0 : at) % PALETTE.length];
    }

    /** blick → mm:ss.xx（与后端 lrc 时间戳同一套换算：1 秒 = 1411200000 blick） */
    function timeLabel(onset) {
        if (onset == null) {
            return '—';
        }
        const bpm = tpl && Number(tpl.bpm) > 0 ? Number(tpl.bpm) : 120;
        const sec = Math.max(0, onset / (BLICK_PER_SECOND * bpm / 120));
        const minutes = Math.floor(sec / 60);
        const rest = sec - minutes * 60;
        return String(minutes).padStart(2, '0') + ':'
            + (rest < 10 ? '0' : '') + rest.toFixed(2);
    }

    /** ISO 时间 → `YYYY-MM-DD HH:mm`（与任务页同一口径） */
    function fmtTime(iso) {
        return iso ? String(iso).replace('T', ' ').slice(0, 16) : '—';
    }

    const round2 = (value) => Math.round(value * 100) / 100;

    // 未保存就刷新 / 关页面时提醒（切模块不触发 beforeunload，所以只在填词页生效）
    window.addEventListener('beforeunload', (ev) => {
        if (dirty && location.hash.startsWith('#song-fill')) {
            ev.preventDefault();
            ev.returnValue = '';
        }
    });

    // ==================== 右栏 · AI 填词页签（§6.1 / §6.4） ====================
    //
    // 页签 = 模式三选一 + 主题 + 韵脚方案表 + 三个批量「…改为」 + 高级折叠。
    // 表格列：选 | 句 | 原句 | 字数 | 韵 | 填词（生成后再追加 韵脚 | 字数匹配）。
    // **句子长度一律按导出 lrc 的口径**：原句 = PlanLine.lrcText、字数 = PlanLine.lrcCount
    //（不是可填槽位数 needCount —— 那个只在回填对齐时用），所以一句里多轨合并后只算一行。
    // 生成走异步任务（song.fill.generate，本地模型分钟级），TaskPoll 轮询；**成功后**才把
    // 结果写进「填词」列（失败不覆盖用户已经填/改过的内容），并在其后追加两列，等级
    // ✓/⚠/✗ 与原因全部来自 /ai/preview（前端只画不判）。点「应用」把勾选行的「填词」列组装成
    // draft → applyByLine —— 与批量填词同一条写入通道（填词工具设计 §13 第 32 条③），
    // 不另写落格逻辑。在线模式提交前弹完整 prompt 预览确认框（原词可能含成人内容，
    // 必须让人看见要外发什么）。
    //
    // 表格塞进 320~560px 的窄栏：字体小一号、原句单行省略（全文挂 title）、
    // 外面套一层 overflow:auto 兜底（真放不下就横向滚，别把面板撑破）。

    let aiPlan = null;          // /ai/plan 的结果
    let aiPlanFillId = null;    // aiPlan 是哪一份填词的（换歌要重拉，不能拿旧的凑）
    let aiRows = null;          // 句下标 → {rhyme, rhymeTouched, text, pick, mark}
    let aiForm = null;          // 表单状态（模式 / 主题 / 高级三项）：重画右栏不丢用户输入
    let aiHasResult = false;    // 生成过 → 「填词」列后面多出「韵脚 / 字数匹配」两列
    let aiBusy = false;         // 生成 / 轮询中：生成按钮置灰
    let aiNote = '';            // 按钮旁边那行提示（提交中 / 失败原因）
    let aiProgress = '';        // 轮询进度文案（任务快照的 message）
    let aiPlanSeq = 0;          // 竞态：整页重画 / 换歌后作废在飞的方案请求

    const AI_BULK_RESET = '__reset__';   // 「还原」= 回到后端给该句的默认韵

    function aiPane() {
        return pageHost ? pageHost.querySelector('#f-side-pane-ai') : null;
    }

    /** 画 AI 页签（没画过才画）。拉方案要与 meta 并行 —— 「韵」列的下拉要十八韵名。 */
    async function ensureAiPane(pane) {
        if (aiRendered) {
            return;
        }
        if (!(aiStatus && aiStatus.enabled)) {
            // 不可用的原因由后端给（Status.reason）：可能是总开关关着，也可能是
            // 端点/模型没配全 —— 前端不自己猜，否则「没配端点」会被说成「开关关着」，
            // 用户去翻那个开关，发现它明明是开的
            pane.innerHTML = '<div class="hint">AI 填词不可用'
                + (aiStatus && aiStatus.reason
                    ? '：' + UI.esc(aiStatus.reason)
                    : '（配置 nya-entworks.song.fill-ai.enabled 后重启）。')
                + '</div>';
            aiRendered = true;
            return;
        }
        if (aiPlan && aiPlanFillId === tpl.fillId) {
            // 整页重画过 / 生成中重画了这一块：按状态原样摆回来（含轮询进度），别丢掉
            aiRendered = true;
            renderAiPane();
            return;
        }
        pane.innerHTML = UI.spinner('生成韵脚方案…');
        const seq = ++aiPlanSeq;
        let plan;
        try {
            [plan] = await Promise.all([
                Api.post('/api/song/fill/ai/plan', {fillId: tpl.fillId}),
                ensureRhymeMeta()
            ]);
        } catch (e) {
            if (seq === aiPlanSeq && pageHost && pageHost.contains(pane)) {
                pane.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            }
            return;
        }
        if (seq !== aiPlanSeq) {
            return;   // 期间又重画 / 换歌了，这一次的结果作废
        }
        adoptAiPlan(plan);
        aiRendered = true;
        renderAiPane();
    }

    /**
     * 装方案。**换了一份填词（或第一次拉）才重建每行状态**：「填词」列默认值 = 后端给的
     * {@code lrcFilled}（该句当前已填内容，整句没填过是空串），别拿模型结果当真。
     * 「韵」列预选后端算的 {@code rhymeBody} —— 不预选就等于把默认韵全改成「不限」，
     * 押韵校验会被悄悄关掉（§6.1）。
     */
    function adoptAiPlan(plan) {
        if (!aiPlan || aiPlanFillId !== tpl.fillId) {
            aiForm = null;
            aiHasResult = false;
            aiNote = '';
            aiProgress = '';
        }
        aiPlan = plan;
        aiPlanFillId = tpl.fillId;
        if (!aiForm) {
            aiForm = {mode: 'full', theme: '', temperature: '0.85', batchSize: '8', fewshot: true};
        }
        aiRows = {};
        (plan.lines || []).forEach(l => {
            aiRows[l.index] = {
                rhyme: l.rhymeBody || '',
                rhymeTouched: false,
                text: l.lrcFilled || '',
                pick: true,
                mark: null
            };
        });
    }

    /** 十八韵的 `<option>`：文本 = 「十六唐 ang」，value = 韵身（后端只认 rhymeBody）。 */
    function aiRhymeOptionsHtml(cur) {
        return ((rhymeMeta && rhymeMeta.rhymes) || []).map(r =>
            '<option value="' + UI.esc(r.body) + '"'
            + (cur === r.body ? ' selected' : '') + '>'
            + UI.esc(r.yun18 + ' ' + r.body) + '</option>').join('');
    }

    /** 逐句的「韵」列：第一个选项是「不限」（value 空 = 该句只控字数）。 */
    function aiRhymeSelect(index, cur) {
        return '<select class="ai-rhyme" data-i="' + index + '">'
            + '<option value=""' + (cur ? '' : ' selected') + '>不限</option>'
            + aiRhymeOptionsHtml(cur)
            + '</select>';
    }

    /** 标记格：符号进格、原因挂 title（等级是后端算的，前端只画不判）。 */
    function aiMarkCell(mark, kind) {
        if (!mark) {
            return '<td class="fill-ai-mark">—</td>';
        }
        const level = kind === 'len' ? mark.lengthMark : mark.rhymeMark;
        const title = kind === 'len'
            ? '字数 ' + mark.unitCount + ' / ' + mark.lrcCount
            : (mark.rhymeMark === 'off' ? '该句不限韵' : '押韵：' + (mark.rhymeBody || '—'));
        return '<td class="fill-ai-mark" title="'
            + UI.esc(title + (mark.message ? ' · ' + mark.message : '')) + '">'
            + (level === 'ok' ? '✓' : level === 'warn' ? '⚠' : level === 'fail' ? '✗' : '—')
            + '</td>';
    }

    /** 按状态重画整个 AI 页签（结构性变化才走这里：方案 / 模式 / 批量改）。 */
    function renderAiPane() {
        const pane = aiPane();
        if (!pane || !aiPlan || !aiForm) {
            return;
        }
        const rows = (aiPlan.lines || []).map(l => {
            const r = aiRows[l.index] || {};
            return '<tr data-i="' + l.index + '">'
                + '<td><input type="checkbox" class="ai-line-pick" data-i="' + l.index + '"'
                + (r.pick ? ' checked' : '') + ' title="勾上：参与「重填选中句」与「应用」"></td>'
                + '<td class="mono">' + (l.index + 1) + '</td>'
                // 原句 = lrc 合并后的原词（长度口径也是它）；单行省略，全文挂 title
                + '<td class="fill-ai-src" title="' + UI.esc(l.lrcText || '') + '">'
                + UI.esc(l.lrcText || '') + '</td>'
                // 尾词 = 句尾那个决定押韵的词（后端 tailOriginal，默认韵就是按它算的）
                + '<td class="mono fill-ai-tail" title="' + UI.esc(l.tailOriginal || '') + '">'
                + UI.esc(l.tailOriginal || '') + '</td>'
                + '<td class="mono">' + UI.esc(String(l.lrcCount == null ? '' : l.lrcCount)) + '</td>'
                + '<td>' + aiRhymeSelect(l.index, r.rhyme || '') + '</td>'
                + '<td class="fill-ai-text"><input type="text" class="ai-text" size="1"'
                + ' data-i="' + l.index + '" value="' + UI.esc(r.text || '') + '"></td>'
                + (aiHasResult ? aiMarkCell(r.mark, 'rhyme') + aiMarkCell(r.mark, 'len') : '')
                + '</tr>';
        }).join('');
        // 三个批量「…改为」共用同一份选项：不限 + 十八韵 + 还原（回到该句后端默认韵）
        const bulk = '<option value="">不限</option>' + aiRhymeOptionsHtml(null)
            + '<option value="' + AI_BULK_RESET + '">还原</option>';
        pane.innerHTML =
            '<div class="row fill-ai-modes">'
            + [['full', '生成全篇'], ['continue', '续写'], ['refill', '重填选中句']].map(([v, t]) =>
                '<label class="small"><input type="radio" name="ai-mode" value="' + v + '"'
                + (aiForm.mode === v ? ' checked' : '') + '> ' + t + '</label>').join('')
            + '</div>'
            + '<label class="small fill-ai-theme">主题 / 大纲'
            + '<textarea id="ai-theme" rows="3" class="fill-textarea"'
            + ' placeholder="如：都市夜店邂逅，第一人称，后半段转折成失恋">'
            + UI.esc(aiForm.theme) + '</textarea></label>'
            + '<div class="muted small">「韵」列选「不限」= 该句只控字数；默认韵取句尾原词的锚解析'
            + '（拼音串原词也能解析）。</div>'
            + '<div class="fill-ai-tablewrap"><table class="fill-ai-table"><thead><tr>'
            + '<th title="勾上：参与「重填选中句」与「应用」">选</th><th>句</th><th>原句</th>'
            + '<th title="句尾那个决定押韵的词——默认韵就是按它解析出来的">尾词</th>'
            + '<th>字数</th><th>韵</th><th>填词</th>'
            + (aiHasResult
                ? '<th title="生成结果的句尾韵部押没押中">韵脚</th>'
                    + '<th title="生成结果的字数合上没有">字数匹配</th>'
                : '')
            + '</tr></thead><tbody>' + rows + '</tbody></table></div>'
            + '<div class="fill-ai-bulk">'
            + '<label class="small">勾选项改为 <select class="ai-bulk" data-scope="picked">'
            + bulk + '</select></label>'
            + '<label class="small">主韵改为 <select class="ai-bulk" data-scope="main"'
            + (aiPlan.mainRhymeBody ? '' : ' disabled') + '>' + bulk + '</select></label>'
            + '<label class="small">全篇统一改为 <select class="ai-bulk" data-scope="all">'
            + bulk + '</select></label>'
            + (aiPlan.mainRhymeBody
                ? '<span class="muted small">主韵：' + UI.esc(aiPlan.mainRhymeLabel || '') + '</span>'
                : '<span class="muted small">本曲没有占多数的韵</span>')
            + '</div>'
            + '<details class="fill-ai-adv" id="ai-adv">'
            + '<summary class="muted small">高级</summary>'
            + '<div class="fill-ai-adv-grid">'
            + '<label class="small">温度 <input type="number" id="ai-temp" step="0.05" min="0" max="2"'
            + ' value="' + UI.esc(aiForm.temperature) + '"></label>'
            + '<label class="small">每批句数 <input type="number" id="ai-batch" step="1" min="1"'
            + ' max="32" value="' + UI.esc(aiForm.batchSize) + '"></label>'
            + '<label class="small"><input type="checkbox" id="ai-fewshot"'
            + (aiForm.fewshot ? ' checked' : '') + '> 注入 few-shot'
            + (aiPlan.useOnline ? '（在线模式恒不注入）' : '') + '</label>'
            + '</div></details>'
            + '<div class="row fill-ai-actions">'
            + '<button id="ai-go" class="btn-primary"' + (aiBusy ? ' disabled' : '') + '>生成</button>'
            + (aiHasResult ? '<button id="ai-apply" class="btn-primary">应用到填写行</button>' : '')
            + '<span class="muted small" id="ai-note">' + UI.esc(aiNote) + '</span>'
            + '</div>'
            + '<div class="muted small" id="ai-progress">' + UI.esc(aiProgress) + '</div>'
            + '<div class="muted small fill-ai-hint">'
            + (aiPlan.useOnline
                ? '当前是<b>在线模式</b>：语料与例句绝不外发，但原词与主题会发给远端模型'
                    + '（提交前弹完整 prompt 确认框）。'
                : '本地 Ollama 模式；第一次生成要等模型冷启动几十秒。')
            + '</div>';
        bindAiPane(pane);
    }

    /**
     * 页签内的事件绑定。输入类**只更新状态、不重画面板**（重画会打断中文输入法的组合、
     * 把光标挪走）；只有结构性变化（换模式重拉方案、批量改）才 renderAiPane。
     */
    function bindAiPane(pane) {
        pane.querySelectorAll('input[name="ai-mode"]').forEach(el => {
            el.onchange = () => {
                aiForm.mode = el.value;
                void reloadAiPlan();   // 参与生成的句变了：主韵要后端按新模式重算
            };
        });
        const theme = pane.querySelector('#ai-theme');
        theme.oninput = () => { aiForm.theme = theme.value; };
        const temp = pane.querySelector('#ai-temp');
        temp.oninput = () => { aiForm.temperature = temp.value; };
        const batch = pane.querySelector('#ai-batch');
        batch.oninput = () => { aiForm.batchSize = batch.value; };
        const few = pane.querySelector('#ai-fewshot');
        few.onchange = () => { aiForm.fewshot = few.checked; };
        pane.querySelectorAll('.ai-rhyme').forEach(sel => {
            sel.onchange = () => {
                const r = aiRows[Number(sel.dataset.i)];
                if (r) {
                    r.rhyme = sel.value;
                    r.rhymeTouched = true;   // 手改过：重拉方案时别用后端默认覆盖掉
                }
            };
        });
        pane.querySelectorAll('.ai-text').forEach(inp => {
            inp.oninput = () => {
                const r = aiRows[Number(inp.dataset.i)];
                if (r) {
                    r.text = inp.value;
                }
            };
        });
        pane.querySelectorAll('.ai-line-pick').forEach(cb => {
            cb.onchange = () => {
                const r = aiRows[Number(cb.dataset.i)];
                if (r) {
                    r.pick = cb.checked;
                }
                if (aiForm.mode === 'refill') {
                    void reloadAiPlan();   // 重填模式：主韵只按勾选的句算
                }
            };
        });
        pane.querySelectorAll('.ai-bulk').forEach(sel => {
            sel.onchange = () => applyBulk(sel.dataset.scope, sel.value);
        });
        pane.querySelector('#ai-go').onclick = () => void submitAiGenerate();
        const apply = pane.querySelector('#ai-apply');
        if (apply) {
            apply.onclick = () => applyAiDraft();
        }
    }

    /**
     * 三个批量「…改为」：勾选项 / 主韵 / 全篇统一。「还原」= 回到该句的后端默认韵，
     * 且**不算手改**（rhymeTouched = false）。
     * 主韵是后端算的（本曲没有占多数时给 null，那个下拉是灰的）—— 前端只画不判，
     * 不自己数众数。改完只更新受影响的那些行下拉，不重画面板（重画会把刚选的批量值弹回去）。
     */
    function applyBulk(scope, value) {
        if (!value || !aiPlan) {
            return;
        }
        const main = aiPlan.mainRhymeBody;
        (aiPlan.lines || []).forEach(l => {
            const r = aiRows[l.index];
            if (!r) {
                return;
            }
            const hit = scope === 'all'
                || (scope === 'main' && !!main && r.rhyme === main)
                || (scope === 'picked' && r.pick);
            if (!hit) {
                return;
            }
            r.rhyme = value === AI_BULK_RESET ? (l.rhymeBody || '') : value;
            r.rhymeTouched = value !== AI_BULK_RESET;
            const box = aiPane() && aiPane().querySelector('.ai-rhyme[data-i="' + l.index + '"]');
            if (box) {
                box.value = r.rhyme;
            }
        });
    }

    /** 勾上的句下标（refill 模式用；也是「应用」的作用范围）。 */
    function checkedLines() {
        return ((aiPlan && aiPlan.lines) || [])
            .filter(l => aiRows[l.index] && aiRows[l.index].pick)
            .map(l => l.index);
    }

    /**
     * 换模式 / 改勾选后重拉方案：**主韵按「当前模式下参与生成的句」由后端重算**（只画不判）。
     * 用户手改过的「韵」保留（rhymeTouched），没改过的跟后端默认走；「填词」列只补空的，
     * 不覆盖已经填好的内容。
     */
    async function reloadAiPlan() {
        if (!aiPlan || !aiForm) {
            return;
        }
        const seq = ++aiPlanSeq;
        let plan;
        try {
            plan = await Api.post('/api/song/fill/ai/plan', {
                fillId: tpl.fillId,
                mode: aiForm.mode,
                onlyLines: aiForm.mode === 'refill' ? checkedLines() : null
            });
        } catch (e) {
            aiNote = '方案刷新失败：' + e.message;
            setAiNote();
            return;
        }
        if (seq !== aiPlanSeq || !aiRows) {
            return;
        }
        const prev = aiRows;
        aiPlan = plan;
        aiPlanFillId = tpl.fillId;
        aiRows = {};
        (plan.lines || []).forEach(l => {
            const old = prev[l.index];
            aiRows[l.index] = {
                rhyme: old && old.rhymeTouched ? old.rhyme : (l.rhymeBody || ''),
                rhymeTouched: !!(old && old.rhymeTouched),
                text: old ? old.text : (l.lrcFilled || ''),
                pick: old ? old.pick : true,
                mark: old ? old.mark : null
            };
        });
        renderAiPane();
    }

    async function submitAiGenerate() {
        if (aiBusy || !aiPlan || !aiForm) {
            return;
        }
        const onlyLines = aiForm.mode === 'refill' ? checkedLines() : null;
        if (aiForm.mode === 'refill' && !onlyLines.length) {
            UI.err('重填模式：先在表格里勾要重写的句（首列）');
            return;
        }
        const params = {
            fillId: tpl.fillId,
            mode: aiForm.mode,
            theme: aiForm.theme.trim(),
            // PlanLineInput 照后端收的口径：{index, needCount, rhymeBody}（别多塞字段）
            plan: (aiPlan.lines || []).map(l => ({
                index: l.index,
                needCount: l.needCount,
                rhymeBody: aiRows[l.index] ? (aiRows[l.index].rhyme || null) : null
            })),
            onlyLines,
            temperature: parseFloat(aiForm.temperature),
            batchSize: parseInt(aiForm.batchSize, 10),
            useFewshot: aiForm.fewshot,
            useOnline: !!(aiStatus && aiStatus.useOnline)
        };
        // 先把按钮置灰，防连点；后面所有状态更新只改文案、不重画面板（轮询期间也一样）
        aiBusy = true;
        aiNote = '正在准备…';
        setAiNote();
        try {
            if (params.useOnline) {
                // §9.3：在线模式每次生成前弹确认框，给完整 prompt 预览（不是摘要）。
                // 预览要按同一份 plan / onlyLines / batchSize 算，否则批次分界与实际发出去的对不上
                const previewPlan = await Api.post('/api/song/fill/ai/plan', {
                    fillId: params.fillId, mode: params.mode, theme: params.theme,
                    useOnline: true, plan: params.plan, onlyLines: params.onlyLines,
                    batchSize: params.batchSize
                });
                if (!previewPlan.promptPreview) {
                    UI.err('拿不到 prompt 预览（在线配置不完整？）');
                    aiBusy = false;
                    aiNote = '';
                    setAiNote();
                    return;
                }
                const host = (aiStatus && aiStatus.onlineHost) || '远端模型';
                if (!await confirmOnlineSend(previewPlan.promptPreview, host)) {
                    aiBusy = false;
                    aiNote = '';
                    setAiNote();
                    return;
                }
            }
            const taskId = await Api.post('/api/song/fill/ai/generate', params);
            const total = onlyLines ? onlyLines.length : (aiPlan.lines || []).length;
            aiNote = '任务 #' + taskId + ' 生成中（约 ' + total + ' 句）…去「任务记录」可看日志';
            setAiNote();
            TaskPoll.wait(taskId, {
                onTick: (task) => {
                    aiProgress = task && task.message ? task.message : '';
                    setAiProgress();
                },
                onResult: () => {
                    void finishAiGenerate();
                    return true;   // 自己收尾（写进表格），不要默认的「处理完成」提示
                },
                onFail: (err) => {
                    aiBusy = false;
                    aiNote = '生成失败：' + err;
                    setAiNote();
                }
            });
        } catch (e) {
            aiBusy = false;
            aiNote = '提交失败：' + e.message;
            setAiNote();
        }
    }

    /** 状态行 / 进度行只改文案、只切按钮的 disabled —— 不重画面板（重画会丢焦点与滚动）。 */
    function setAiNote() {
        const note = pageHost && pageHost.querySelector('#ai-note');
        if (note) {
            note.textContent = aiNote;
        }
        const go = pageHost && pageHost.querySelector('#ai-go');
        if (go) {
            go.disabled = aiBusy;
        }
    }

    function setAiProgress() {
        const el = pageHost && pageHost.querySelector('#ai-progress');
        if (el) {
            el.textContent = aiProgress;
        }
    }

    /**
     * 生成成功后的收尾：把 /ai/preview 的结果写进「填词」列（**只有成功才写**，
     * 失败退回上面 onFail 那条路，不动用户已经填/改过的内容），并让「韵脚 / 字数匹配」
     * 两列出现。等级与原因全部来自后端，前端只画。
     */
    async function finishAiGenerate() {
        let preview;
        try {
            preview = await Api.get('/api/song/fill/ai/preview?fillId=' + tpl.fillId);
        } catch (e) {
            aiBusy = false;
            aiNote = '取生成结果失败：' + e.message;
            setAiNote();
            return;
        }
        aiHasResult = true;
        (preview.lines || []).forEach(l => {
            const r = aiRows[l.index];
            if (!r) {
                return;
            }
            r.text = l.text || '';
            r.mark = {
                unitCount: l.unitCount,
                lrcCount: l.lrcCount,
                lengthMark: l.lengthMark,
                rhymeMark: l.rhymeMark,
                rhymeBody: l.rhymeBody,
                message: l.message || ''
            };
        });
        aiBusy = false;
        aiProgress = '';
        aiNote = '生成完成，共 ' + preview.total + ' 句：改完「填词」列再点「应用」'
            + '（没勾的句保持原样）。';
        renderAiPane();
    }

    /**
     * 「应用」：勾选行的「填词」列组装成 draft → applyByLine —— **与批量填词同一条写入通道**
     *（填词工具设计 §13 第 32 条③），不另写一套落格逻辑。没勾的行传空串 = 保持原样。
     */
    function applyAiDraft() {
        const draft = new Array(lines.length).fill('');
        let picked = 0;
        (aiPlan.lines || []).forEach(l => {
            const r = aiRows[l.index];
            if (!r || !r.pick || !String(r.text || '').trim()) {
                return;
            }
            draft[l.index] = r.text;
            picked++;
        });
        if (!picked) {
            UI.err('勾选的行里没有内容可应用');
            return;
        }
        const extra = applyByLine(draft, /* skipDash = */ true);
        markDirty(pageHost);
        clearSel(pageHost);
        draw(pageHost);   // 重画后填写格的值从状态重读 —— 否则格子里不显示新词
        UI.ok('已应用 ' + picked + ' 句' + (extra ? '，' + extra + ' 个字放不下被忽略' : '')
            + lastAlignNote + '（自动保存稍后带走；记得过目再导出）');
    }

    /** 在线确认框：system 与 user 两个只读 textarea 原文 + 「我确认发送到 host」。 */
    function confirmOnlineSend(promptPreview, host) {
        return new Promise((resolve) => {
            const m = UI.modal('<div class="modal modal-wide">'
                + '<div class="modal-head"><span>确认发送到 ' + UI.esc(host)
                + '（在线模式：语料 / 例句不外发，但下面的原词与主题会离开本机）</span>'
                + '<button class="modal-close" type="button">×</button></div>'
                + '<div class="modal-body">'
                + '<div class="muted small">system</div>'
                + '<textarea readonly rows="6" class="fill-textarea">'
                + UI.esc(promptPreview.system) + '</textarea>'
                + '<div class="muted small" style="margin-top:6px">user（首批判次内容）</div>'
                + '<textarea readonly rows="12" class="fill-textarea">'
                + UI.esc(promptPreview.user) + '</textarea>'
                + '</div><div class="modal-foot">'
                + '<button type="button" class="btn-plain" data-act="cancel">取消</button>'
                + '<button type="button" class="btn-danger" data-act="ok">我确认发送到 '
                + UI.esc(host) + '</button></div></div>');
            const finish = (val) => {
                document.removeEventListener('keydown', onKey);
                m.close();
                resolve(val);
            };
            const onKey = (e) => { if (e.key === 'Escape') finish(false); };
            document.addEventListener('keydown', onKey);
            m.box.querySelector('.modal-close').onclick = () => finish(false);
            m.box.querySelector('[data-act="cancel"]').onclick = () => finish(false);
            m.box.querySelector('[data-act="ok"]').onclick = () => finish(true);
        });
    }

    // 建议条按格子的视口坐标定位，滚动 / 缩放后位置就错了，直接收起（capture 抓容器内滚动）

    // _tokenize 只是给控制台 / 单测核对切分规则用的钩子：前端与后端各有一份实现
    // （见文件头注释），改了任一处都可以在控制台跑 SongFillPage._tokenize('hello 天涯')
    // 和 LyricFillAlignerTest 的期望值对一下。
    return {render, _tokenize: tokenize};
})();
