/**
 * 共享的阅读页与归档弹窗（新漫画页 / 未归档页 / 已归档页 单本共用，文档 4.4 / 4.8 / 4.9）。
 *
 * 三页唯一的不同是「数据来源与写端点」，都通过 opts 传进来：
 *   - 数据刷新：reload()/rescan() 重扫页面、list() 供导航
 *   - 写操作：ops.rename/score/tags/remove 的 HTTP 调用
 *   - 归档：archive.label/title/confirm/run —— run 由页面自决同步还是异步任务；
 *     已归档页 archive 为 null（没有「归档」动作，只有「保存」）
 *   - 单本统一入口：edit(manga) —— 阅读页底栏的「修改/归档」按钮回调，打开归档弹窗
 *
 * 图片端点两页一致（/api/manga/new/images 与 /image，受管根已含未归档根），写死在这里。
 * 「前端只画不判」：改名预演走后端 check-name，归档闸门读后端算好的字段。
 * 评分不再「打分即移动」：弹窗里只做本地选中，归档/保存时随请求提交。
 */
const MangaReader = (() => {

    /** 词典类型候选缓存：补录弹窗的类型下拉共用，一次拉取 */
    let dictTypes = null;

    /**
     * 阅读页正文图片的 src。先按用户要求试过 file:/// 直读本地路径，
     * 但 Chrome/Firefox 拦 http 页里的 file:// 资源（Not allowed to load local
     * resource），所以改走字节端点：folderPath + 相对路径，由后端读盘返回。
     */
    /** 绝对路径 → 目录内相对路径（含子目录分卷）。字节端点按它拼 fileName */
    function relName(absPath, folderPath) {
        return absPath.startsWith(folderPath)
            ? absPath.slice(folderPath.length).replace(/^[\\/]+/, '')
            : absPath.split(/[\\/]/).pop();
    }

    function imgUrl(absPath, folderPath) {
        return 'api/manga/new/image?folderPath=' + encodeURIComponent(folderPath)
            + '&fileName=' + encodeURIComponent(relName(absPath, folderPath));
    }

    /**
     * 改名输入的实时预演：新名字是否匹配规则、有没有未识别展会/原作。
     * 判定走后端 check-name（与扫描同一套解析与词典），前端只画。
     * 阅读页底部条与「归档」弹窗共用。
     */
    function bindNameCheck(input, box, folderPath) {
        let timer = null;
        const run = async () => {
            const name = input.value.trim();
            if (!name) {
                box.innerHTML = '';
                return;
            }
            let r;
            try {
                r = await Api.get('/api/manga/new/check-name?folderPath='
                    + encodeURIComponent(folderPath)
                    + '&newFolderName=' + encodeURIComponent(name));
            } catch (e) {
                box.innerHTML = '<span class="tag tag-err">' + UI.esc(e.message) + '</span>';
                return;
            }
            let html = '';
            if (r.matchedRule) {
                html += '<span class="tag tag-ok">规则' + r.matchedRule + '</span>';
            } else {
                html += '<span class="tag tag-err">不规范</span>'
                    + (r.irregularReason ? '<span class="small muted">'
                        + UI.esc(r.irregularReason) + '</span>' : '');
            }
            if (r.extraExhibit) {
                html += '<span class="tag tag-warn">未识别展会：' + UI.esc(r.extraExhibit) + '</span>';
            }
            if (r.extraParody) {
                html += '<span class="tag tag-warn">未识别原作：' + UI.esc(r.extraParody) + '</span>';
            }
            box.innerHTML = html || '<span class="muted small">匹配规则，无未识别展会/原作</span>';
        };
        input.oninput = () => {
            clearTimeout(timer);
            timer = setTimeout(run, 300);
        };
        run(); // 打开时先显示当前名的判定
    }

    /**
     * 统一的「归档 / 修改」弹窗（新漫画 / 未归档 / 已归档 单本共用）。
     *
     * 评分不再「打分即移动」—— 这里只做本地选中（selectedScore），随「修改后归档」提交、
     * 或已归档「保存」时落库（scoreManga 只落库不动目录）。所以弹窗里点评分不触发重扫。
     *
     * @param opts.archive  存在表示有「归档」动作（新漫画/未归档），null 表示已归档（只保存）
     * @param opts.archive.needTags 已废弃：标签不再必填，归档时从 eh 拉取（并集）
     * @param opts.archive.run(path, score, tags) 归档动作
     * @param opts.ops.rename/score/tags/remove 写端点；tags 只已归档用（编辑单本独立标签）
     * @param opts.reload 重扫页面数据
     */
    /**
     * 「已归档」那组 opts 的基线：没有归档动作（已归档的只剩改名/评分/独立标签/删除），
     * 写端点全在 /api/manga/archive/mangas 下。
     *
     * 放在这里而不是各页各写一份 —— 它是 openArchiveModal 的入参形状，与那个弹窗是一件事的
     * 两面；归档页与合并冲突页都要用它，分头写就会在改端点时漏掉一处。
     * 调用方拿到后照旧可以往上加自己的字段（reload / onDone / list / rescan / edit）。
     */
    function archivedOpts() {
        return {
            scoreOptions: [9, 7, 5, 3],
            ops: {
                rename: (path, name) => Api.put('/api/manga/archive/mangas/folder-name',
                    {folderPath: path, newFolderName: name}),
                score: (path, score) => Api.put('/api/manga/archive/mangas/score',
                    {folderPath: path, score: score}),
                tags: (mangaId, tags) => Api.put('/api/manga/tags/manga/' + mangaId,
                    {tags: tags}),
                remove: (path) => Api.del('/api/manga/archive/mangas?folderPath='
                    + encodeURIComponent(path))
            },
            archive: null
        };
    }

    function openArchiveModal(host, manga, opts) {
        const archived = !opts.archive;          // 已归档：没有「归档」动作，只保存
        // 标签不再必填：新漫画/未归档都显示标签选择器（可留空，归档时从 eh 拉取并集）；
        // 已归档只编辑独立标签，没扫过漫画（mangaId 为空）的没法编辑
        const showTags = archived ? (manga.mangaId != null) : true;
        // 标签继承：没有独立标签就预填父级标签（作者相关标签），灰态区分；
        // 用户一动手就按主动填写看待，去掉灰态。
        // 独立标签用 ownTags（带命名空间的 TagItem），回显标签用 manga.tags（后端注入的 String，别混）
        const ownTags = manga.ownTags || [];
        const inheritedTags = !ownTags.length;
        const initTags = inheritedTags ? (manga.parentTags || []) : ownTags;

        // eh 拉取结果的简单显示：状态 + 时间（状态 → 中文）。有 mangaId 才有「从 eh 拉取」按钮
        const EH_STATUS_TEXT = {
            SUCCESS: '拉取成功', NOT_FOUND: '未找到', NO_MATCH: '未匹配', FAILED: '拉取失败'
        };
        const ehText = (r) => {
            if (!r) {
                return '未拉取';
            }
            const label = EH_STATUS_TEXT[r.status] || r.status;
            const time = r.scannedAt ? String(r.scannedAt).replace('T', ' ').slice(0, 19) : '';
            return label + (time ? ' · ' + time : '');
        };
        const ehRow = () => manga.mangaId == null
            ? '<div class="row" style="margin-top:12px"><span class="small muted">eh 标签</span>'
                + '<span class="small muted">归档时自动从 eh 拉取，拉取失败需手动补</span></div>'
            : '<div class="row" style="margin-top:12px;align-items:center">'
                + '<span class="small muted">eh 标签</span>'
                + '<span id="am-eh" class="small muted">加载中…</span>'
                + '<button type="button" class="btn-plain" id="am-eh-pull">从 eh 拉取</button>'
                + '</div>';

        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>' + (archived ? '修改：' : '归档：')
            + UI.esc(manga.folderName) + '</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<div class="row" style="align-items:flex-start">'
            + '<label class="small muted">改名'
            + '<input type="text" id="am-name" style="width:300px" value="'
            + UI.esc(manga.folderName) + '"></label>'
            // 命名规则预演只对新漫画/未归档做：已归档是自由改名，check-name 也不认归档路径
            + (archived ? '' : '<div id="am-check" class="r-name-check" style="padding-top:18px"></div>')
            + '</div>'
            + '<div class="row" style="margin-top:12px"><span class="small muted">评分</span>'
            + '<span id="am-scores"></span></div>'
            + (showTags
                ? '<div class="row" style="margin-top:12px;align-items:flex-start">'
                + '<span class="small muted">标签</span>'
                + '<div id="am-tags" style="flex:1;min-width:300px"></div></div>'
                : '')
            + ehRow()
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" data-a="close">关闭</button>'
            + '<button type="button" id="am-save">' + (archived ? '保存' : '仅修改') + '</button>'
            + (archived ? '' : '<button type="button" class="btn-primary" id="am-archive">'
                + '修改后归档</button>')
            + '</div></div>');

        const nameInput = m.box.querySelector('#am-name');
        const checkBox = m.box.querySelector('#am-check');
        const scoreHost = m.box.querySelector('#am-scores');
        const archiveBtn = m.box.querySelector('#am-archive');
        const saveBtn = m.box.querySelector('#am-save');
        const tagHost = m.box.querySelector('#am-tags');
        const tagPick = tagHost ? MangaTagPicker.create(tagHost, {
            initTags,
            inherited: inheritedTags,
            onChange: updateGate
        }) : null;

        // 本地选中的评分：打分不再移动目录，只记这里，归档/保存时随请求提交
        let selectedScore = manga.score;

        const ehStatus = m.box.querySelector('#am-eh');
        const ehPullBtn = m.box.querySelector('#am-eh-pull');
        const renderEhStatus = (r) => {
            if (ehStatus) {
                ehStatus.textContent = ehText(r);
            }
        };
        const loadEhStatus = async () => {
            try {
                const r = await Api.get('/api/manga/' + manga.mangaId + '/eh-scan');
                renderEhStatus(r);
                return r;
            } catch (e) {
                renderEhStatus(null);
                return null;
            }
        };

        function tagNames() {
            return tagPick ? tagPick.names() : [];
        }

        function updateGate() {
            if (!archiveBtn) {
                return;
            }
            const gate = selectedScore != null && manga.matchedRule !== 0
                && !manga.extraExhibit && !manga.extraParody;
            archiveBtn.disabled = !gate;
            archiveBtn.title = gate ? ''
                : '归档需要：已评分、命名符合规则、没有未识别的新展会/原作'
                + '（标签留空则归档时自动从 eh 拉取，失败需手动补）';
        }

        function renderScores() {
            scoreHost.innerHTML = opts.scoreOptions.map(s => '<button class="score-btn am-score'
                + (selectedScore === s ? ' on' : '') + '" data-score="' + s + '">' + s
                + '</button>').join('')
                + (selectedScore != null && !archived
                    ? '<button class="btn-plain am-unscore">撤销</button>' : '');
            scoreHost.querySelectorAll('.am-score').forEach(b => b.onclick = () => {
                selectedScore = Number(b.dataset.score);
                renderScores();
                updateGate();
            });
            const un = scoreHost.querySelector('.am-unscore');
            if (un) {
                un.onclick = () => {
                    selectedScore = null;
                    renderScores();
                    updateGate();
                };
            }
        }

        if (archived) {
            checkBox && (checkBox.innerHTML = '');
        } else {
            bindNameCheck(nameInput, checkBox, manga.folderPath);
        }
        renderScores();
        updateGate();

        // 打开即查最近一次 eh 拉取结果，显示状态 + 时间；按钮触发只读重扫并把建议标签并入选择器
        if (ehStatus) {
            loadEhStatus();
        }
        if (ehPullBtn) {
            ehPullBtn.onclick = async (ev) => {
                await UI.withBusy(ev.target, '提交中…', async () => {
                    try {
                        const taskId = await Api.post('/api/manga/' + manga.mangaId + '/eh-scan',
                            {autoApply: false});
                        TaskPoll.wait(taskId, {
                            onResult: (r) => {
                                renderEhStatus(r);
                                if (r && r.suggestedTags && r.suggestedTags.length && tagPick) {
                                    tagPick.addExternal(r.suggestedTags);
                                    UI.ok('已拉取，建议标签已并入选择器');
                                } else if (r && r.status === 'SUCCESS') {
                                    UI.ok('已拉取，暂无建议标签');
                                } else {
                                    UI.err('拉取未成功：' + (r && r.errorMessage ? r.errorMessage : ehText(r)));
                                }
                                return true;
                            },
                            onFail: (error) => UI.err(error)
                        });
                    } catch (e) {
                        UI.err(e.message);
                    }
                });
            };
        }

        m.box.querySelector('.modal-close').onclick = m.close;
        m.box.querySelector('[data-a="close"]').onclick = m.close;

        // 「仅修改」（已归档叫「保存」）：改名；已入库（有 mangaId）的再落盘独立标签，已归档再落盘评分
        saveBtn.onclick = async (ev) => {
            const name = nameInput.value.trim();
            const renameNeeded = name && name !== manga.folderName;
            await UI.withBusy(ev.target, archived ? '保存中…' : '改名中…', async () => {
                try {
                    let newPath = manga.folderPath;
                    if (renameNeeded) {
                        newPath = await opts.ops.rename(manga.folderPath, name);
                    }
                    if (archived) {
                        // 已归档没有「归档」动作，评分只能在这里落盘（标签走下方通用保存）
                        if (opts.ops.score && selectedScore != null && selectedScore !== manga.score) {
                            await opts.ops.score(newPath, selectedScore);
                        }
                    }
                    // 标签是库字段，漫画已入库（有 mangaId：未归档/已归档）就能独立保存；
                    // 新漫画还没入库（mangaId 为空）没有独立标签可存，标签仍随归档提交
                    if (opts.ops.tags && tagPick && manga.mangaId != null) {
                        await opts.ops.tags(manga.mangaId, tagPick.items());
                    }
                    UI.ok(archived ? '已保存' : '已改名');
                    m.close();
                    await opts.reload();
                    opts.onDone && opts.onDone({archived: false, renamed: renameNeeded, folderName: name});
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };

        if (archiveBtn) {
            archiveBtn.onclick = async (ev) => {
                const msg = opts.archive.confirm ? opts.archive.confirm(manga) : null;
                if (msg && !await UI.confirm(msg,
                    {title: opts.archive.title, okText: opts.archive.label})) {
                    return;
                }
                try {
                    const path = manga.folderPath;
                    m.close();
                    await opts.archive.run(path, selectedScore, tagNames());
                    opts.onDone && opts.onDone({archived: true, path});
                } catch (e) {
                    UI.err(e.message);
                }
            };
        }
    }

    // ------------------------------------------------------------------
    // 「未识别展会/原作」补录弹窗（文档 4.2.1）。新漫画页与归档作者页共用。
    // ------------------------------------------------------------------

    /**
     * 打开补录弹窗：带封面缩略图的目录列表 + 类型/值/匹配方式/备注 + 补录预检。
     *
     * @param opts.type     词典类型（EXHIBIT / PARODY，可选其它）
     * @param opts.value    未识别的值
     * @param opts.folders  出现在哪些目录（全路径），逐张带封面缩略图列出
     * @param opts.onSaved  保存成功后回调（页面自决怎么刷新）
     */
    async function openDictDialog(opts) {
        const {type: initType, value, folders, onSaved} = opts;
        if (!dictTypes) {
            try {
                dictTypes = await Api.get('/api/manga/dict/types');
            } catch (e) {
                dictTypes = [];
            }
        }
        // 词典类型候选从所有种类里选（不限于展会/原作），默认值是点开的图标类型
        const TYPES = (dictTypes && dictTypes.length) ? dictTypes : [
            {dictType: 'EXHIBIT', label: '展会', defaultMatchMode: 'REGEX'},
            {dictType: 'PARODY', label: '原作', defaultMatchMode: 'EXACT'}
        ];
        const MODES = ['EXACT', 'PREFIX', 'SUFFIX', 'CONTAINS', 'REGEX'];

        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span id="dl"></span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<p class="small muted">出现在 ' + folders.length + ' 个目录：</p>'
            // 每个目录一张封面缩略图，一眼认出是哪本
            + '<div class="dict-folders">'
            + folders.map(f => '<div class="dict-folder">'
                + '<img class="dict-thumb" loading="lazy" alt="" src="'
                + UI.esc(MangaCard.coverUrl(f))
                + '" onerror="this.outerHTML=\'<div class=&quot;manga-cover-none&quot;>无封面</div>\'">'
                + '<span class="small mono" title="' + UI.esc(f) + '">'
                + UI.esc(f.split('\\').pop()) + '</span></div>').join('')
            + '</div>'
            + '<label class="small muted" style="display:block;margin-top:10px">词典类型'
            + '<select id="dt" style="width:100%">'
            + TYPES.map(t => '<option value="' + t.dictType + '"' + (t.dictType === initType ? ' selected' : '')
                + '>' + UI.esc(t.label || t.dictType) + '</option>').join('')
            + '</select></label>'
            + '<label class="small muted" style="display:block;margin-top:10px">值'
            + '<input type="text" id="dv" style="width:100%" value="' + UI.esc(value) + '"></label>'
            + '<label class="small muted" style="display:block;margin-top:10px">匹配方式'
            + '<select id="dm" style="width:100%">'
            + MODES.map(mode => '<option value="' + mode + '">' + mode + '</option>').join('')
            + '</select></label>'
            + '<label class="small muted" style="display:block;margin-top:10px">备注'
            + '<input type="text" id="dr" style="width:100%" placeholder="可记来源"></label>'
            + '<div id="dc" style="margin-top:10px"></div>'
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" data-act="cancel">取消</button>'
            + '<button type="button" class="btn-primary" data-act="ok">保存</button>'
            + '</div></div>');

        const title = m.box.querySelector('#dl');
        const typeSelect = m.box.querySelector('#dt');
        const valueInput = m.box.querySelector('#dv');
        const modeSelect = m.box.querySelector('#dm');
        const checkBox = m.box.querySelector('#dc');

        let currentType = initType;
        const metaOf = (t) => TYPES.find(x => x.dictType === t) || {};
        const check = () => runDictCheck(valueInput.value.trim(), currentType, checkBox);
        // 切换类型：标题跟着换，匹配方式取该类型的默认值，预检按新类型重跑
        const syncMeta = () => {
            const meta = metaOf(currentType);
            title.textContent = '补录词典：' + (meta.label || currentType);
            modeSelect.value = MODES.includes(meta.defaultMatchMode)
                ? meta.defaultMatchMode : 'EXACT';
            check();
        };

        typeSelect.onchange = () => {
            currentType = typeSelect.value;
            syncMeta();
        };
        valueInput.oninput = check;
        modeSelect.onchange = check;
        syncMeta();

        m.box.querySelector('.modal-close').onclick = m.close;
        m.box.querySelector('[data-act="cancel"]').onclick = m.close;
        m.box.querySelector('[data-act="ok"]').onclick = async (ev) => {
            await UI.withBusy(ev.target, '保存中…', async () => {
                try {
                    await Api.post('/api/manga/dict', {
                        dictType: currentType,
                        dictValue: valueInput.value.trim(),
                        matchMode: modeSelect.value,
                        ignoreCase: true,
                        remark: m.box.querySelector('#dr').value.trim()
                    });
                    UI.ok('已加入词典');
                    m.close();
                    onSaved && onSaved();
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };
    }

    /** 补录前预检：相似变体与「已经能匹配」的既有词条，照搬词典页 runCheck 的样式 */
    async function runDictCheck(value, type, box) {
        if (!value) {
            box.innerHTML = '';
            return;
        }
        let res;
        try {
            res = await Api.get('/api/manga/dict/check?type=' + type
                + '&value=' + encodeURIComponent(value));
        } catch (e) {
            box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        let html = '';
        if (res.overlapping.length) {
            // 多半是漏了 reload 而不是缺词条，这条提示能省掉一次无谓的新增
            html += '<div class="hint"><strong>已有 ' + res.overlapping.length
                + ' 条能匹配这个值，可能不必新增</strong><div class="small mono">'
                + res.overlapping.map(e => UI.esc(e.dictValue + '  ('
                    + (e.matchMode || 'EXACT') + ')')).join('<br>') + '</div></div>';
        }
        if (res.similar.length) {
            html += '<div class="hint">库中已有相似词条（去掉 <span class="mono">/ 空格 ・ -</span> '
                + '等分隔符后同名）：<div class="small mono">'
                + res.similar.map(e => UI.esc(e.dictValue + '  (id=' + e.id + ')')).join('<br>')
                + '</div>确认要作为新变体加进去再保存。</div>';
        }
        box.innerHTML = html || '<p class="small"><span class="tag tag-ok">没有相似或重叠的词条</span></p>';
    }

    /**
     * 打开阅读页：全屏纵向滚动看正文图片，底部操作条，两侧悬浮切上一本下一本。
     *
     * 单本（新漫画/未归档/已归档）通过 opts.edit 提供「修改/归档」弹窗入口，底栏只放
     * 一个按钮；新漫画合集没有 opts.edit，保留内联改名 + 「压缩并规范化」。
     *
     * @param opts.edit   单本统一入口：点「修改/归档」时回调，页面自决打开哪个弹窗
     * @param opts.list   当前可导航的列表 = **列表页此刻显示的那一份**（可能带筛选），
     *                    重扫后由页面刷新其取值来源。翻页照它走，所以与列表页的顺序一致
     * @param opts.listAll 可不传。不带筛选的完整列表，只给 reload 兜底：改名后这本若不再
     *                    命中当前筛选，退回它继续，而不是把阅读页关掉
     * @param opts.rescan 重扫页面（页面自决走哪个数据源）
     */
    function openReader(host, manga, opts) {
        const overlay = document.createElement('div');
        overlay.className = 'reader';
        overlay.innerHTML = '<div class="reader-imgs">' + UI.spinner('加载图片…') + '</div>'
            + '<button class="reader-prev" title="上一个">‹</button>'
            + '<button class="reader-next" title="下一个">›</button>'
            + '<div class="reader-bar"></div>';
        document.body.appendChild(overlay);

        const imgs = overlay.querySelector('.reader-imgs');
        const bar = overlay.querySelector('.reader-bar');

        // 图片宽度：竖图 50%、横图 80%（相对阅读面板宽），滑块按同一比例缩放，最大 100%
        const W_PORTRAIT = 0.5, W_LANDSCAPE = 0.8;
        let imgScale = 1;
        const pctOf = (base) => Math.min(100, Math.round(base * imgScale * 100));
        function applyWidths() {
            imgs.querySelectorAll('img').forEach(img => {
                const landscape = img.dataset.orient === 'landscape';
                img.style.width = pctOf(landscape ? W_LANDSCAPE : W_PORTRAIT) + '%';
            });
        }

        // 导航列表。正常情况下就是页面显示的那一份；改名把当前这本赶出了那份视图时
        // 退回完整列表（见 reload），否则「下一个」会停在一份不含当前这本的列表上
        let useListAll = false;
        const list = () => ((useListAll && opts.listAll) ? (opts.listAll() || [])
            : (opts.list() || []));
        // 只读模式：归档作者页的合集弹窗没有单本的改名/评分/删除/归档端点，
        // 底部条只留关闭，翻上一本/下一本与页跳转照常
        const readOnly = !!opts.readOnly;
        let idx = Math.max(0, list().findIndex(m => m.folderPath === manga.folderPath));
        let current = list()[idx] || manga;
        let imageCount = 0;   // 本本的图片总数，页跳转用
        let currentImage = 0; // 可视区顶部当前那张图（0-based）

        // 阅读页里的归档标签输入（新漫画单本需要先打标签才能归档）
        function tagNames() {
            const el = bar.querySelector('.r-tags');
            return el ? el.value.trim().split(/[\s、，,]+/).filter(Boolean) : [];
        }

        function close() {
            window.removeEventListener('hashchange', close);
            document.removeEventListener('keydown', onKey);
            overlay.remove();
        }

        async function loadImages() {
            imgs.innerHTML = UI.spinner('加载图片…');
            let paths;
            try {
                paths = await Api.get('/api/manga/new/images?folderPath='
                    + encodeURIComponent(current.folderPath));
            } catch (e) {
                imgs.innerHTML = '<div class="empty">' + UI.esc(e.message) + '</div>';
                return;
            }
            if (!paths.length) {
                imgs.innerHTML = '<div class="empty">没有图片</div>';
                return;
            }
            imageCount = paths.length;
            currentImage = 0;
            imgs.innerHTML = paths.map(p => '<img loading="lazy" src="'
                + UI.esc(imgUrl(p, current.folderPath)) + '">').join('');
            // 加载后按朝向定宽（竖/横），再按当前滑块比例缩放
            imgs.querySelectorAll('img').forEach(img => {
                img.onload = () => {
                    img.dataset.orient = img.naturalWidth > img.naturalHeight
                        ? 'landscape' : 'portrait';
                    applyWidths();
                };
            });
            applyWidths();
            syncPageControls();
            imgs.scrollTop = 0;
        }

        function renderBar() {
            // 只读模式（合集弹窗）只留关闭按钮；
            // unified：单本（新漫画/未归档/已归档）统一走「修改/归档」弹窗，底栏只放一个入口按钮；
            // 其余（新漫画合集）保留内联改名 + 「压缩并规范化」
            const unified = !readOnly && !!opts.edit;
            const hasArchive = !readOnly && !!opts.archive;
            let row1;
            if (readOnly) {
                row1 = '<button class="btn-plain r-open-folder">打开目录</button>'
                    + '<button class="btn-plain r-close">关闭页面</button>';
            } else if (unified) {
                row1 = '<button class="btn-plain r-open-folder">打开目录</button>'
                    + '<button class="btn-plain r-close">关闭页面</button>'
                    + '<button class="btn-danger r-delete">删除</button>'
                    + '<button class="btn-primary r-edit">'
                    + (opts.archive ? '修改/归档' : '修改') + '</button>';
            } else {
                row1 = '<input type="text" class="r-name" style="width:300px" value="'
                    + UI.esc(current.folderName) + '">'
                    + '<button class="r-rename">改名</button>'
                    // 命名规则预演放在重命名框右侧
                    + '<div class="r-name-check"></div>'
                    + '<button class="btn-plain r-open-folder">打开目录</button>'
                    + '<button class="btn-plain r-close">关闭页面</button>'
                    + '<button class="btn-danger r-delete">删除</button>'
                    + (hasArchive ? '<button class="btn-primary r-archive">'
                        + opts.archive.label + '</button>' : '');
            }

            // 标签回显：后端注入的回显标签（独立优先、父级兜底），切页时随 current 刷新
            const tagRow = ((current.tags && current.tags.length)
                ? '<div class="reader-bar-row">'
                    + '<span class="muted small">标签</span>'
                    + current.tags.map(t => '<span class="tag">'
                        + UI.esc(typeof t === 'string' ? t : t.tagName) + '</span>').join(' ')
                    + '</div>'
                : '');

            // 同一行：左边图片宽度控制，右边提交内容；命名规则预演已内联在改名框右侧
            bar.innerHTML = '<div class="reader-bar-row">'
                + '<span class="muted small">图片宽度</span>'
                + '<input type="range" class="r-width" min="50" max="125" step="5" value="'
                + Math.round(imgScale * 100) + '">'
                + '<span class="muted small r-width-label"></span>'
                // 底部栏中间：页跳转（拖动滑块或输数字直接滚到对应图）
                + '<span style="flex:1;display:flex;justify-content:center;align-items:center;gap:8px;min-width:220px">'
                + '<span class="muted small">页</span>'
                + '<input type="range" class="r-page" min="1" max="1" value="1">'
                + '<input type="number" class="r-page-num" min="1" max="1" value="1"'
                + ' style="width:56px" title="输入页码回车跳转">'
                + '<span class="muted small r-page-total"></span>'
                + '</span>'
                + row1
                + '</div>'
                + tagRow;

            // 归档闸门只对合集内联的「压缩并规范化」按钮生效（要求命名合规）。
            // 单本的归档闸门已收进统一弹窗，底栏只剩「修改/归档」入口，不在这里判
            const archive = hasArchive && !unified ? bar.querySelector('.r-archive') : null;
            const applyGate = () => {
                if (!archive) {
                    return;
                }
                const gate = current.matchedRule !== 0 && !!current.normalizedName;
                archive.disabled = !gate;
                archive.title = gate ? '' : '需要目录名符合命名规则';
            };
            applyGate();

            // 图片宽度滑块：改一个数，竖/横两个基准宽按同一比例缩放，最大 100%
            const widthSlider = bar.querySelector('.r-width');
            const widthLabel = bar.querySelector('.r-width-label');
            widthLabel.textContent = '竖 ' + pctOf(W_PORTRAIT) + '% 横 ' + pctOf(W_LANDSCAPE) + '%';
            widthSlider.oninput = () => {
                imgScale = Number(widthSlider.value) / 100;
                widthLabel.textContent = '竖 ' + pctOf(W_PORTRAIT) + '% 横 ' + pctOf(W_LANDSCAPE) + '%';
                applyWidths();
            };

            bar.querySelector('.r-close').onclick = close;
            bar.querySelector('.r-open-folder').onclick = () => doOpenFolder();
            if (unified) {
                bar.querySelector('.r-delete').onclick = () => doDelete();
                bar.querySelector('.r-edit').onclick = () => {
                    const m = current;
                    // 弹窗叠加在阅读页上打开，不关阅读页；操作完成后按结果同步（改名重载 / 归档跳下一本）
                    opts.edit(m, syncAfterEdit);
                };
            } else if (!readOnly) {
                // 合集：改名规则预演 + 改名 + 删除 + 压缩并规范化
                if (hasArchive) {
                    bindNameCheck(bar.querySelector('.r-name'), bar.querySelector('.r-name-check'),
                        current.folderPath);
                }
                bar.querySelector('.r-delete').onclick = () => doDelete();
                bar.querySelector('.r-rename').onclick = () => doRename();
                if (archive) {
                    archive.onclick = () => doArchive();
                }
            }

            // 页跳转：拖动滑块或输数字直接滚到对应图
            const pageSlider = bar.querySelector('.r-page');
            pageSlider.oninput = () => jumpToPage(Number(pageSlider.value) - 1);
            const pageNum = bar.querySelector('.r-page-num');
            pageNum.onchange = () => {
                const v = Math.min(Math.max(1, Number(pageNum.value) || 1),
                    Math.max(1, imageCount));
                pageNum.value = String(v);
                jumpToPage(v - 1);
            };
            syncPageControls();
        }

        /** 重扫后按 folderName 找回这本（改名后名字变了，所以传新名字） */
        async function reload(folderName) {
            let found = list().find(m => m.folderName === folderName);
            if (!found && opts.listAll) {
                // 改名后不再命中当前筛选（例如开着「不规范」筛选、改成了规范名）：
                // 退回完整列表继续，还找不到才算真的没了
                found = (opts.listAll() || []).find(m => m.folderName === folderName);
                useListAll = !!found;
            }
            if (!found) {
                UI.err('在扫描结果里找不到这本了，可能已移走');
                close();
                return;
            }
            current = found;
            idx = list().indexOf(found);
            await loadImages();
            renderBar();
        }

        async function rescan() {
            await opts.rescan();
        }

        /**
         * 修改/归档弹窗操作成功后的同步（阅读页保持打开）：
         * 归档把漫画搬走 → 跳到下一本；改名 → 按新目录名重载当前这本。
         */
        function syncAfterEdit(result) {
            if (!result) {
                return;
            }
            if (result.archived) {
                advance(result.path);
            } else if (result.renamed) {
                reload(result.folderName);
            }
        }

        async function doRename() {
            const name = bar.querySelector('.r-name').value.trim();
            if (!name || name === current.folderName) {
                return;
            }
            try {
                await opts.ops.rename(current.folderPath, name);
                await rescan();
                await reload(name);
            } catch (e) {
                UI.err(e.message);
            }
        }

        async function doDelete() {
            if (!await UI.confirm('删除这本？\n\n' + current.folderPath
                + '\n\n目录会送进 Windows 回收站，删错了可以从那里还原。')) {
                return;
            }
            try {
                const path = current.folderPath;
                await opts.ops.remove(path);
                await rescan();
                advance(path);
            } catch (e) {
                UI.err(e.message);
            }
        }

        /** 打开漫画目录所在的文件资源管理器（系统 Explorer） */
        async function doOpenFolder() {
            try {
                await Api.post('/api/manga/new/open-folder?folderPath='
                    + encodeURIComponent(current.folderPath));
            } catch (e) {
                UI.err(e.message);
            }
        }

        async function doArchive() {
            const msg = opts.archive.confirm ? opts.archive.confirm(current) : null;
            if (msg && !await UI.confirm(msg,
                {title: opts.archive.title, okText: opts.archive.label})) {
                return;
            }
            try {
                const path = current.folderPath;
                await opts.archive.run(path, tagNames());
                // run 已刷新页面数据（未归档页）或还在原列表（新漫画页异步任务跑着）
                advance(path);
            } catch (e) {
                UI.err(e.message);
            }
        }

        /**
         * 删除/归档后跳到下一本。调用前页面数据应已刷新：
         * doDelete 先 rescan，doArchive 靠页面自己的 run 刷新。
         * - 当前这本还在列表里（新漫画页归档是异步任务，本还在）→ 下一个是它后面那本
         * - 已被移除（删除 / 未归档页归档搬走）→ 后面的顶上来占了 idx 位，取 l[idx]
         * 位置现按 folderPath 查，不用存着的 idx —— 中途重扫过（归档任务跑完后列表整体
         * 前移一位）那个值就过期了，照着它跳会漏掉一本。
         * 没有下一个就退出阅读页。
         */
        function advance(path) {
            const l = list();
            if (!l.length) {
                close();
                return;
            }
            const at = l.findIndex(m => m.folderPath === path);
            const nextIdx = at >= 0 ? at + 1 : idx;
            if (nextIdx >= l.length) {
                close();
                return;
            }
            idx = nextIdx;
            current = l[idx];
            loadImages();
            renderBar();
        }

        /** 页跳转：把第 index 张图滚到可视区顶部（index 0-based） */
        function jumpToPage(index) {
            const els = imgs.querySelectorAll('img');
            if (!els[index]) {
                return;
            }
            const target = els[index];
            imgs.scrollTop = target.getBoundingClientRect().top
                - imgs.getBoundingClientRect().top + imgs.scrollTop;
            currentImage = index;
            syncPageControls();
        }

        /** 滚动时算当前顶部那张图，同步到页跳转控件 */
        function updateCurrentImage() {
            const els = imgs.querySelectorAll('img');
            const viewTop = imgs.getBoundingClientRect().top;
            let cur = 0;
            for (let i = 0; i < els.length; i++) {
                if (els[i].getBoundingClientRect().top - viewTop > 60) {
                    break;
                }
                cur = i;
            }
            currentImage = cur;
            syncPageControls();
        }

        /** 把图片总数与当前页码写进底部栏的滑块和数字框 */
        function syncPageControls() {
            const slider = bar.querySelector('.r-page');
            const num = bar.querySelector('.r-page-num');
            const total = bar.querySelector('.r-page-total');
            if (!slider) {
                return;
            }
            const n = Math.max(1, imageCount);
            slider.max = String(n);
            num.max = String(n);
            const shown = Math.min(currentImage + 1, n);
            slider.value = String(shown);
            num.value = String(shown);
            total.textContent = ' / ' + n;
        }

        function goto(delta) {
            const l = list();
            if (!l.length) {
                return;
            }
            // 阅读页不随重扫关闭，存着的 idx 可能是重扫前的位置（归档任务跑完后列表整体
            // 前移一位），照着它跳就会漏掉一本：先按 folderPath 把当前这本重新定位
            const at = l.findIndex(m => m.folderPath === current.folderPath);
            if (at >= 0) {
                idx = at;
            }
            idx = (idx + delta + l.length) % l.length;
            current = l[idx];
            loadImages();
            renderBar();
        }

        const onKey = (e) => {
            if (e.key === 'Escape') {
                close();
            }
        };
        document.addEventListener('keydown', onKey);
        // 左侧导航切页会触发 hashchange，阅读页浮在 body 上不会随 body 一起清掉，这里主动关
        window.addEventListener('hashchange', close);
        overlay.querySelector('.reader-prev').onclick = () => goto(-1);
        overlay.querySelector('.reader-next').onclick = () => goto(1);

        // 操作条默认隐藏在面板下缘外：图片拉到底或鼠标移到下缘才滑出来
        let hideTimer = null;
        const showBar = () => {
            clearTimeout(hideTimer);
            bar.classList.add('reader-bar-visible');
        };
        const scheduleHide = () => {
            // 正在操作条里输入/点按钮就不收起，免得改名字打到一半条没了
            if (bar.contains(document.activeElement)) {
                showBar();
                return;
            }
            clearTimeout(hideTimer);
            hideTimer = setTimeout(() => bar.classList.remove('reader-bar-visible'), 1500);
        };
        imgs.addEventListener('scroll', () => {
            const nearBottom = imgs.scrollHeight - imgs.scrollTop - imgs.clientHeight < 60;
            nearBottom ? showBar() : scheduleHide();
            updateCurrentImage();
        });
        overlay.addEventListener('mousemove', (e) => {
            const nearBottom = e.clientY > overlay.getBoundingClientRect().bottom - 140;
            nearBottom ? showBar() : scheduleHide();
        });

        loadImages();
        renderBar();
    }

    // ------------------------------------------------------------------
    // 压缩失败二次确认弹窗（文档 4.10）。新漫画页单本与合集存储共用。
    // 「前端只画不判」：这里只列出后端任务结果里 pendingConfirmations 的
    // 成功比例与失败明细，归档/跳过都走页面传进来的 commit（同步端点）。
    // ------------------------------------------------------------------

    /** 一本压缩失败的漫画：成功 X/Y + 失败文件与错误。比例按 filesBefore - failures 现算，
     *  因为 PendingArchive 的 succeeded()/total() 是方法不是 record 组件，不会序列化过来 */
    function pendingRow(item) {
        const c = item.compress || {};
        const total = c.filesBefore || 0;
        const fails = c.failures || [];
        const okCount = total - fails.length;
        const failHtml = fails.length
            ? '<div class="small mono" style="margin-top:4px">'
                + fails.map(f => '· ' + UI.esc(f.fileName)
                    + (f.error ? ' —— ' + UI.esc(f.error) : '')).join('<br>')
                + '</div>'
            : '';
        const unknowns = c.unknownImages || [];
        const unknownHtml = unknowns.length
            ? '<div class="small" style="margin-top:4px;color:var(--warn)">'
                + '⚠ 未识别图片格式（未压缩，原样保留）：' + unknowns.map(UI.esc).join('、')
                + '</div>'
            : '';
        const path = item.preparedPath || item.originalPath || '';
        return '<div class="pending-item" style="padding:8px 0;border-bottom:1px solid #eee">'
            + '<div><strong>' + UI.esc(item.folderName) + '</strong>'
            + ' <span class="muted small">成功 ' + okCount + '/' + total + '</span></div>'
            + (path ? '<div class="small mono muted" title="' + UI.esc(path) + '">'
                + UI.esc(path) + '</div>' : '')
            + failHtml + unknownHtml + '</div>';
    }

    /**
     * 批量列出压缩失败的漫画，逐个确认是否仍归档（单本存储）。
     * <p>点「归档」逐本调 {@code opts.commit(preparedPath)}（同步），成功标「已归档」；
     * 点「跳过」放弃这本。全部处理完或关闭弹窗时 resolve —— 页面随后自己重扫。
     *
     * @param items 后端 {@code StoreBatchResult.pendingConfirmations}
     * @param opts.commit 单本确认归档，入参是 preparedPath
     */
    function confirmPending(items, opts) {
        return new Promise((resolve) => {
            const m = UI.modal('<div class="modal">'
                + '<div class="modal-head"><span>' + items.length
                + ' 本压缩有失败，仍要归档吗？</span>'
                + '<button class="modal-close" type="button">×</button></div>'
                + '<div class="modal-body" style="max-height:70vh;overflow:auto">'
                + '<p class="muted small">这些本里有文件没压成，<strong>没有</strong>随这批归档。'
                + '下面逐本确认：归档（保留已压好的，没压成的原文件也一起归档）还是跳过（留在原地）。</p>'
                + items.map((item, i) => '<div data-pi="' + i + '">'
                    + pendingRow(item)
                    + '<div class="row" data-actions="' + i + '" style="margin-top:6px">'
                    + '<button class="btn-primary pp-commit" data-i="' + i + '">归档</button>'
                    + '<button class="btn-plain pp-skip" data-i="' + i + '">跳过</button>'
                    + '</div></div>').join('')
                + '</div>'
                + '<div class="modal-foot"><button type="button" class="btn-plain" data-a="close">'
                + '关闭（未处理的留在原地）</button></div></div>');

            let remaining = items.length;
            let closed = false;
            const finish = () => {
                if (closed) {
                    return;
                }
                closed = true;
                m.close();
                resolve();
            };
            const done = () => {
                if (--remaining <= 0) {
                    finish();
                }
            };

            items.forEach((item, i) => {
                const actions = m.box.querySelector('[data-actions="' + i + '"]');
                actions.querySelector('.pp-commit').onclick = async (ev) => {
                    await UI.withBusy(ev.target, '归档中…', async () => {
                        try {
                            await opts.commit(item.preparedPath, item.score);
                            actions.innerHTML = '<span class="tag tag-ok">已归档</span>';
                            done();
                        } catch (e) {
                            UI.err(e.message);
                        }
                    });
                };
                actions.querySelector('.pp-skip').onclick = () => {
                    actions.innerHTML = '<span class="tag">已跳过</span>';
                    done();
                };
            });

            m.box.querySelector('.modal-close').onclick = finish;
            m.box.querySelector('[data-a="close"]').onclick = finish;
        });
    }

    /**
     * 合集存储：批量列出压缩失败的漫画（只读展示），全部确认后整体搬迁。
     * <p>底部「确认全部并迁移」/「取消」。确认后调 {@code opts.commit()}（整体搬迁），
     * 关闭/迁移后 resolve —— 页面随后自己重扫。
     *
     * @param items 后端 {@code CollectionStoreResult.pendingConfirmations}
     * @param opts.commit 整体迁移，无入参
     */
    function confirmCollectionMove(items, opts) {
        return new Promise((resolve) => {
            const m = UI.modal('<div class="modal">'
                + '<div class="modal-head"><span>合集里有 ' + items.length
                + ' 本压缩有失败</span>'
                + '<button class="modal-close" type="button">×</button></div>'
                + '<div class="modal-body" style="max-height:70vh;overflow:auto">'
                + '<p class="muted small">合集是整体搬迁：下面这些本有文件没压成，'
                + '确认没问题后才会把整个合集搬进归档根。</p>'
                + items.map(pendingRow).join('')
                + '</div>'
                + '<div class="modal-foot">'
                + '<button type="button" class="btn-plain" data-a="cancel">取消</button>'
                + '<button type="button" class="btn-primary" id="cc-move">确认全部并迁移</button>'
                + '</div></div>');

            m.box.querySelector('.modal-close').onclick = () => {
                m.close();
                resolve();
            };
            m.box.querySelector('[data-a="cancel"]').onclick = () => {
                m.close();
                resolve();
            };
            m.box.querySelector('#cc-move').onclick = async (ev) => {
                await UI.withBusy(ev.target, '迁移中…', async () => {
                    try {
                        await opts.commit();
                        UI.ok('合集已归档');
                        m.close();
                        resolve();
                    } catch (e) {
                        UI.err(e.message);
                    }
                });
            };
        });
    }

    /**
     * 归档成功但 eh 标签拉取失败时的补标签弹窗：用标签选择器手动补独立标签。
     * 保存走 PUT /api/manga/tags/manga/{mangaId}；跳过/关闭则保持继承（resolve false）。
     * @return {Promise<boolean>} true=已补，false=跳过
     */
    function promptMissingTags(mangaId, folderName) {
        return new Promise((resolve) => {
            const m = UI.modal('<div class="modal">'
                + '<div class="modal-head"><span>手动补标签</span>'
                + '<button class="modal-close" type="button">×</button></div>'
                + '<div class="modal-body">'
                + '<p class="small muted">「' + UI.esc(folderName) + '」归档成功，但从 eh 拉取标签失败。'
                + '可以直接补独立标签，或跳过保持继承归档目录标签。</p>'
                + '<div id="pm-tags" style="margin-top:8px"></div>'
                + '</div>'
                + '<div class="modal-foot">'
                + '<button type="button" class="btn-plain" data-a="skip">跳过</button>'
                + '<button type="button" class="btn-primary" data-a="save">保存标签</button>'
                + '</div></div>');

            const tagHost = m.box.querySelector('#pm-tags');
            const pick = MangaTagPicker.create(tagHost, {initTags: [], onChange: () => {}});

            const close = (saved) => {
                m.close();
                resolve(saved);
            };
            m.box.querySelector('.modal-close').onclick = () => close(false);
            m.box.querySelector('[data-a="skip"]').onclick = () => close(false);
            m.box.querySelector('[data-a="save"]').onclick = async (ev) => {
                await UI.withBusy(ev.target, '保存中…', async () => {
                    try {
                        await Api.put('/api/manga/tags/manga/' + mangaId, {tags: pick.items()});
                        UI.ok('已保存标签');
                        close(true);
                    } catch (e) {
                        UI.err(e.message);
                    }
                });
            };
        });
    }

    return {bindNameCheck, archivedOpts, openArchiveModal, openReader, openDictDialog,
        confirmPending, confirmCollectionMove, promptMissingTags};
})();
