/**
 * 新漫画页（文档 4.8）。
 *
 * 两个标签页：单本（#待看）与合集（#待看合集）。卡片是 MangaCard，四个页面共用。
 *
 * 三件事值得先说清楚，因为它们决定了这一页的形态：
 *
 * 1. **新漫画不入库，每次现扫。** 所有操作做完都重扫一次而不是就地改 DOM ——
 *    改名会牵动规范化命名的结果、评分会牵动归档匹配（高分映射入低分），
 *    就地改就得在前端把这些规则再实现一遍，而不一致的那次会让人照着错的信息点归档。
 *
 * 2. **存储的闸门由后端算。** 阅读页「归档」按钮可不可点，看的是后端的
 *    score / matchedRule / extraExhibit / extraParody，前端只画不判。
 *
 * 3. **存储不可逆**（NConvert 删原文件），所以归档前先弹一次确认，
 *    提交后是后台任务 + 轮询进度。
 *
 * 单本页有批量归档：勾选能存储的漫画（不能存储的勾选框禁用），「批量归档已选」一次提交。
 * 逐本改名/评分仍走阅读页。
 */
const MangaNewPage = (() => {

    let tab = 'single';
    let scan = null;
    let collections = null;
    /**
     * 归档前是否强制打标签（`nya-entworks.manga.require-tags-before-archive`），
     * 随合集扫描一起下发。只用来决定提示语与保存前那道预检 —— **认定在后端**，
     * 这里跟着关掉只是别拿一条已经关掉的规矩去拦人（前端只画不判）。
     */
    let requireTagsBeforeArchive = false;
    let pollTimer = null;
    /** TaskPoll.wait 返回的停止句柄，切页/重扫时停掉后台轮询 */
    let pollStop = null;
    /** 最近一次轮询到的任务快照，收尾重扫后把它补回 #task-box（失败明细/结果要看得到） */
    let lastTask = null;
    /** 合集展开互斥：只留一个展开。纯 DOM 状态，重扫时按它恢复展开 */
    let expandedPath = null;
    /** 扫描栏计数标签的筛选：选中的 key 集合。命中才算（同时满足所有已选条件） */
    let filters = new Set();
    /** 按文件名（目录名）过滤的关键字，纯客户端。与 filters 是 AND 关系 */
    let nameQuery = '';
    /** 批量归档勾选的目录路径。跨筛选切换保留，重扫/切页签时清空 */
    let selected = new Set();
    /** 正在压缩存档的目录路径（单本与合集共用）。存着期间对应卡片锁定、不可操作 */
    let storing = new Set();
    /** 最近一次单本/批量存储提交的标签，供压缩失败二次确认时原样带上（同一批同组标签） */
    let pendingStoreTags = [];
    const FILTER_LABELS = {
        storable: '可存储', unscored: '未评分', irregular: '不规范',
        exhibit: '未识别展会', parody: '未识别原作'
    };
    /** 合集页筛选：与单本页 filters 分开，各自管各自页签 */
    let collFilters = new Set();
    const COLL_FILTER_LABELS = {
        storable: '可存储', badName: '目录名不合法', irregular: '不规范',
        exhibit: '未识别展会', parody: '未识别原作', conflict: '作者已归档'
    };
    /** 展开的合集里，对内部漫画的筛选（不规范/未识别展会/未识别原作）。
     *  换合集或收起时清空，别把上一个合集的筛选带过来 */
    let mangaFilter = new Set();

    async function render(host) {
        stopPolling();
        // 进页面就清关键字（同 selected）：留着上一次的关键字会让人以为「怎么少了一批」
        nameQuery = '';
        host.innerHTML = '<div class="card"><div class="row">'
            + '<button id="tab-single">单本（#待看）</button>'
            + '<button id="tab-coll">作者合集（#待看合集）</button>'
            + '</div></div>'
            + '<div id="new-body">' + UI.spinner() + '</div>';

        host.querySelector('#tab-single').onclick = () => {
            tab = 'single';
            render(host);
        };
        host.querySelector('#tab-coll').onclick = () => {
            tab = 'coll';
            render(host);
        };
        host.querySelector(tab === 'single' ? '#tab-single' : '#tab-coll')
            .classList.add('btn-primary');

        if (tab === 'single') {
            await renderSingle(host);
        } else {
            await renderCollections(host);
        }
    }

    // ------------------------------------------------------------------
    // 单本
    // ------------------------------------------------------------------

    async function renderSingle(host) {
        selected.clear();
        const box = host.querySelector('#new-body');
        box.innerHTML = UI.spinner('扫描中…');
        try {
            scan = await Api.get('/api/manga/new/scan');
        } catch (e) {
            box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        renderContent(host, box);
    }

    /** 重画单本页。筛选切换也走这里 —— scan 还在内存里，不重扫 */
    function renderContent(host, box) {
        box.innerHTML = renderScanHead(scan) + renderExtras(scan)
            + '<div id="task-box"></div>'
            // 网格单独包一层：搜索框每敲一个字只重画这里，头部（含输入框本身）不重画，焦点不丢
            + '<div id="new-grid">' + renderGrid(scan.mangas) + '</div>';
        bindGrid(host, box);
        bindExtras(host, box);
        bindScanHead(host, box);
        bindBatch(host, box);
        const rescan = box.querySelector('#rescan-btn');
        if (rescan) {
            rescan.onclick = (ev) => UI.withBusy(ev.target, '扫描中…', () => renderSingle(host));
        }
    }

    /** 扫描栏计数标签可点击：选中后只显示符合条件的漫画，再点取消 */
    function bindScanHead(host, box) {
        box.querySelectorAll('[data-filter]').forEach(btn => btn.onclick = () => {
            const key = btn.dataset.filter;
            if (filters.has(key)) {
                filters.delete(key);
            } else {
                filters.add(key);
            }
            renderContent(host, box);
        });
        const search = box.querySelector('#new-name-query');
        if (search) {
            // 边输入边过滤，只重画网格 —— scan 在内存里，不重扫、不发请求
            search.oninput = () => {
                nameQuery = search.value;
                renderGridOnly(host, box);
            };
        }
    }

    /** 只重画网格（搜索框输入时走这里）。头部与输入框原样留着，所以焦点不丢 */
    function renderGridOnly(host, box) {
        const grid = box.querySelector('#new-grid');
        if (!grid) {
            return;
        }
        grid.innerHTML = renderGrid(scan.mangas);
        bindGrid(host, box);
    }

    /** 扫描栏计数标签：可点击的筛选按钮，选中时高亮 */
    function filterTag(key, label, cls) {
        const active = filters.has(key);
        return '<button class="tag' + (active ? ' tag-filter-on' : '') + '" data-filter="' + key + '"'
            + (cls ? ' ' + cls : '') + '>' + label + '</button>';
    }

    function renderScanHead(s) {
        const total = s.mangas.length;
        const storable = s.mangas.filter(storableNoScore).length;
        const unscored = s.mangas.filter(m => !m.score).length;
        const irregular = s.mangas.filter(m => m.matchedRule === 0).length;
        const needRename = s.mangas.filter(m => m.needsRename).length;

        let html = '<div class="card"><h2>' + UI.esc(s.rootPath)
            + ' <span class="muted small">' + total + ' 本，词典版本 '
            + s.dictVersion + '</span></h2>'
            // 按文件名（目录名）过滤，纯前端、不重扫
            + '<div class="row"><input type="text" id="new-name-query" value="'
            + UI.esc(nameQuery) + '" placeholder="搜文件名（目录名）"></div>';

        html += '<p class="row">'
            + filterTag('storable', '可存储 ' + storable, 'tag-ok')
            + filterTag('unscored', '未评分 ' + unscored, unscored ? 'tag-warn' : '')
            + filterTag('irregular', '不规范 ' + irregular, irregular ? 'tag-err' : '')
            + filterTag('exhibit', '未识别展会 ' + Object.keys(s.extraExhibits).length,
                Object.keys(s.extraExhibits).length ? 'tag-warn' : '')
            + filterTag('parody', '未识别原作 ' + Object.keys(s.extraParodies).length,
                Object.keys(s.extraParodies).length ? 'tag-warn' : '')
            + '</p>'
            + (filters.size
                ? '<p class="small muted">已筛选：' + [...filters].map(k => FILTER_LABELS[k]).join('、')
                    + '。再点一次选中的计数标签取消。</p>'
                : '');

        html += '<div class="row"><button id="rescan-btn">重新扫描</button>'
            + '<button id="select-all-btn"' + (storable ? '' : ' disabled') + '>全选可存储</button>'
            + '<button id="batch-store-btn" class="btn-primary"'
            + (selected.size ? '' : ' disabled') + '>批量归档已选（' + selected.size + '）</button>'
            + '<button id="clear-select-btn"' + (selected.size ? '' : ' disabled') + '>清空勾选</button>'
            + '<button id="batch-normalize-btn"' + (needRename ? '' : ' disabled')
            + '>批量规范化命名（' + needRename + '）</button>'
            + '</div>';

        if (s.noArchiveDb) {
            html += '<div class="hint hint-err">库里没有任何归档目录，'
                + '所有漫画都会被判成未归档。先到「归档作者」页跑一次同步。</div>';
        }
        return html + '</div>';
    }

    /** 未识别展会/原作：一行紧凑的图标按钮，展会与原作不同底色。悬停看目录，点击补录 */
    function renderExtras(s) {
        const exhibits = Object.entries(s.extraExhibits);
        const parodies = Object.entries(s.extraParodies);
        if (!exhibits.length && !parodies.length) {
            return '';
        }
        let html = '<div class="card"><h2>未识别的展会与原作</h2>'
            + '<p class="muted small">带着它们的漫画不能归档。点按钮补录（保存即生效），'
            + '回来点「重新扫描」。</p><div class="row">';
        const icon = (rows, cls) => rows.map(([value, folders]) =>
            '<button class="dict-icon dict-icon-' + cls + '" data-value="' + UI.esc(value) + '"'
            + ' title="出现在 ' + folders.length + ' 个目录：'
            + UI.esc(folders.slice(0, 5).map(f => f.split('\\').pop()).join('、'))
            + (folders.length > 5 ? '…' : '') + '">' + UI.esc(value) + '</button>').join('');
        html += icon(exhibits, 'exhibit') + icon(parodies, 'parody');
        return html + '</div></div>';
    }

    function renderGrid(mangas) {
        // 命中所有已选筛选条件（以及搜索框关键字）的才显示
        const list = filteredMangas(mangas);
        if (!list.length) {
            return '<div class="card">' + UI.empty(nameQuery.trim()
                ? '没有匹配「' + UI.esc(nameQuery.trim()) + '」的漫画。'
                : (filters.size
                    ? '没有符合当前筛选的漫画。再点一次选中的计数标签取消筛选。'
                    : '没有新漫画。解压到 #待看 下面就会出现在这里。')) + '</div>';
        }
        return '<div class="card"><div class="manga-grid">'
            // 新漫画卡片：改名收进「归档」弹窗，卡片上不单独放改名
            + list.map(m => MangaCard.render(m, {
                acts: ['read', 'archive', 'delete'],
                select: {can: (x) => storableNoScore(x), checked: (x) => selected.has(x.folderPath)},
                storing: storing.has(m.folderPath)
            })).join('')
            + '</div></div>';
    }

    /**
     * 命中所有已选筛选条件、且目录名含搜索关键字的漫画（都没选时原样全列）。
     * grid、阅读页导航、「全选可存储」共用 —— 三处因此始终一致。
     */
    function filteredMangas(mangas) {
        const q = nameQuery.trim().toLowerCase();
        return mangas.filter(m => [...filters].every(f => matchFilter(m, f))
            && (!q || (m.folderName || '').toLowerCase().includes(q)));
    }

    /**
     * 阅读页「上一个 / 下一个」走的列表 = **列表页这一刻显示的那一份**（跟着筛选走）。
     * 直接拿 scan.mangas 当导航列表，点了「未评分」筛选之后再翻页就会翻到被筛掉的漫画上
     * —— 列表里根本没有它，看起来就是「下一个的顺序跟列表页对不上」。
     *
     * @param collPath 合集路径（单本传 null）。合集内的筛选只在展开时生效，收起时是空的
     */
    function visibleMangas(collPath) {
        const col = collPath ? findCollection(collPath) : null;
        if (collPath) {
            return col ? col.mangas.filter(m => [...mangaFilter].every(f => matchFilter(m, f))) : [];
        }
        return scan ? filteredMangas(scan.mangas) : [];
    }

    /**
     * 不带筛选的完整列表。只给阅读页的 reload 兜底用：改名后这本可能不再命中当前筛选
     * （例如开着「不规范」筛选、把它改成了规范名），那时退回完整列表继续，
     * 免得阅读页平白无故关掉。
     */
    function allMangas(collPath) {
        const col = collPath ? findCollection(collPath) : null;
        return collPath ? (col ? col.mangas : []) : (scan ? scan.mangas : []);
    }

    function findCollection(collPath) {
        return (collections || []).find(c => c.folderPath === collPath) || null;
    }

    /**
     * 可存储判定（不含评分）：打分已不再在扫描时落盘，评分随归档提交，
     * 所以「还没评分」不再挡住存储。与后端 checkStorable 同口径。
     */
    function storableNoScore(m) {
        return m.matchedRule !== 0 && !m.extraExhibit && !m.extraParody && m.imageCount > 0;
    }

    /** 一本漫画是否满足某个筛选条件（与后端闸门同口径） */
    function matchFilter(m, key) {
        switch (key) {
            case 'storable': return storableNoScore(m);
            case 'unscored': return !m.score;
            case 'irregular': return m.matchedRule === 0;
            case 'exhibit': return !!m.extraExhibit;
            case 'parody': return !!m.extraParody;
            default: return true;
        }
    }

    function bindGrid(host, box) {
        MangaCard.bind(box, {
            read: (path) => {
                const manga = scan.mangas.find(m => m.folderPath === path);
                if (manga) {
                    openReader(host, manga, false, null);
                }
            },
            archive: (path) => {
                const manga = scan.mangas.find(m => m.folderPath === path);
                if (manga) {
                    openArchiveModal(host, manga);
                }
            },
            select: (path, checked) => {
                if (checked) {
                    selected.add(path);
                } else {
                    selected.delete(path);
                }
                syncBatchControls(box);
            },
            remove: async (path, btn) => {
                if (!await UI.confirm('删除这个目录及里面的所有文件？\n\n' + path
                    + '\n\n目录会送进 Windows 回收站，删错了可以从那里还原。')) {
                    return;
                }
                await act(host, () => Api.del(
                    '/api/manga/new?folderPath=' + encodeURIComponent(path)), btn, true);
            }
        });
    }

    /** 勾选后只更新批量按钮的文案与可用态，不整页重画（勾选本身已在 DOM 上） */
    function syncBatchControls(box) {
        const store = box.querySelector('#batch-store-btn');
        if (store) {
            store.disabled = selected.size === 0;
            store.textContent = '批量归档已选（' + selected.size + '）';
        }
        const clear = box.querySelector('#clear-select-btn');
        if (clear) {
            clear.disabled = selected.size === 0;
        }
    }

    function bindBatch(host, box) {
        const all = box.querySelector('#select-all-btn');
        if (all) {
            all.onclick = () => {
                // 全选当前筛选视图里能存储的（看得见的才勾，别偷偷带上被筛掉的）
                filteredMangas(scan.mangas)
                    .filter(storableNoScore)
                    .forEach(m => selected.add(m.folderPath));
                renderContent(host, box);
            };
        }
        const clear = box.querySelector('#clear-select-btn');
        if (clear) {
            clear.onclick = () => {
                selected.clear();
                renderContent(host, box);
            };
        }
        const store = box.querySelector('#batch-store-btn');
        if (store) {
            store.onclick = (ev) => batchStore(host, box, ev.target);
        }
        const normalize = box.querySelector('#batch-normalize-btn');
        if (normalize) {
            normalize.onclick = (ev) => openBatchNormalize(host, box, ev.target);
        }
    }

    /** 批量归档：先打标签（可留空，留空则归档时从 eh 拉取），再逐本评分，最后一起提交存储（压缩 + 规范化 + 归档，后台任务 + 轮询） */
    async function batchStore(host, box, btn) {
        const paths = [...selected];
        if (!paths.length) {
            UI.err('先勾选要归档的漫画');
            return;
        }
        const tags = await askStoreTags(paths.length);
        if (tags === null) {
            return;
        }
        const scores = await askStoreScores(paths);
        if (scores === null) {
            return;
        }
        if (!await UI.confirm('批量归档 ' + paths.length + ' 本？\n\n每本都会压缩'
            + '（NConvert 删原图，不可回滚）→ 规范化改名 → 按作者归档或落进未归档目录。',
            {title: '批量归档', okText: '归档'})) {
            return;
        }
        await UI.withBusy(btn, '提交中…', async () => {
            try {
                pendingStoreTags = tags;
                const items = paths.map(p => ({folderPath: p, score: scores[p]}));
                const taskId = await Api.post('/api/manga/new/store',
                    {items: items, tags: tags});
                paths.forEach(p => storing.add(p));
                selected.clear();
                renderContent(host, box);
                pollTask(host, taskId, (r) => storeOnResult(host, r));
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    /** 收集归档标签（可留空：留空则归档时从 eh 拉取，失败需手动补）。同一批打同一组标签；取消返回 null */
    function askStoreTags(count) {
        return new Promise((resolve) => {
            const m = UI.modal('<div class="modal">'
                + '<div class="modal-head"><span>给这 ' + count + ' 本打标签</span>'
                + '<button class="modal-close" type="button">×</button></div>'
                + '<div class="modal-body">'
                + '<p class="muted small">标签可留空，留空则归档时从 eh 拉取（失败需手动补）。'
                + '标签按空格 / 顿号 / 逗号切分，同一批漫画打上同一组标签。</p>'
                + '<input type="text" id="bt-tags" placeholder="标签，空格分隔（可留空）" style="width:100%">'
                + '</div>'
                + '<div class="modal-foot">'
                + '<button type="button" class="btn-plain" id="bt-cancel">取消</button>'
                + '<button type="button" class="btn-primary" id="bt-ok">下一步</button>'
                + '</div></div>');
            const close = (val) => { m.close(); resolve(val); };
            m.box.querySelector('.modal-close').onclick = () => close(null);
            m.box.querySelector('#bt-cancel').onclick = () => close(null);
            m.box.querySelector('#bt-ok').onclick = () => {
                const raw = m.box.querySelector('#bt-tags').value.trim();
                const tags = raw ? raw.split(/[\s、，,]+/).filter(Boolean) : [];
                close(tags);
            };
            m.box.querySelector('#bt-tags').onkeydown = (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    m.box.querySelector('#bt-ok').click();
                }
            };
        });
    }

    /** 批量归档的逐本评分（打分不再移动文件，评分随归档提交）。全部选完返回 {path: score}，取消返回 null */
    function askStoreScores(paths) {
        return new Promise((resolve) => {
            const options = scoreOptionsOf();
            const rows = paths.map(p => {
                const manga = scan.mangas.find(m => m.folderPath === p);
                return {path: p, name: manga ? manga.folderName : p.split('\\').pop()};
            });
            const scores = {};   // path -> score
            const m = UI.modal('<div class="modal">'
                + '<div class="modal-head"><span>给这 ' + paths.length + ' 本评分</span>'
                + '<button class="modal-close" type="button">×</button></div>'
                + '<div class="modal-body" style="max-height:70vh;overflow:auto">'
                + '<p class="muted small">打分不再移动文件，评分随归档提交。每本都要选一个评分。</p>'
                + rows.map((r, i) => '<div class="row" style="margin:6px 0">'
                    + '<span class="mono small" style="min-width:320px" title="'
                    + UI.esc(r.path) + '">' + UI.esc(r.name) + '</span>'
                    + '<span class="bs-scores" data-i="' + i + '"></span>'
                    + '</div>').join('')
                + '</div>'
                + '<div class="modal-foot">'
                + '<button type="button" class="btn-plain" id="bs-cancel">取消</button>'
                + '<button type="button" class="btn-primary" id="bs-ok">下一步</button>'
                + '</div></div>');
            const close = (val) => { m.close(); resolve(val); };
            m.box.querySelector('.modal-close').onclick = () => close(null);
            m.box.querySelector('#bs-cancel').onclick = () => close(null);

            const draw = () => {
                rows.forEach((r, i) => {
                    const hostEl = m.box.querySelector('.bs-scores[data-i="' + i + '"]');
                    hostEl.innerHTML = options.map(s => '<button class="score-btn bs-score'
                        + (scores[r.path] === s ? ' on' : '') + '" data-i="' + i
                        + '" data-score="' + s + '">' + s + '</button>').join('')
                        + (scores[r.path] != null
                            ? '<button class="btn-plain bs-unscore" data-i="' + i + '">撤销</button>' : '');
                });
                m.box.querySelectorAll('.bs-score').forEach(b => b.onclick = () => {
                    scores[rows[Number(b.dataset.i)].path] = Number(b.dataset.score);
                    draw();
                });
                m.box.querySelectorAll('.bs-unscore').forEach(b => b.onclick = () => {
                    scores[rows[Number(b.dataset.i)].path] = null;
                    draw();
                });
                m.box.querySelector('#bs-ok').disabled = rows.some(r => scores[r.path] == null);
            };
            draw();
            m.box.querySelector('#bs-ok').onclick = () => {
                if (rows.every(r => scores[r.path] != null)) {
                    close(scores);
                }
            };
        });
    }

    /**
     * 批量规范化命名弹窗：列出所有 needsRename 的漫画（原名 → 规范化名），默认全选。
     * 执行提交成后台任务，逐项返回成功/失败；改名不可回滚，但规范化幂等，重扫后可再改。
     */
    async function openBatchNormalize(host, box, btn) {
        const targets = scan.mangas.filter(m => m.needsRename);
        if (!targets.length) {
            UI.err('没有需要规范化的漫画');
            return;
        }
        const chosen = new Set(targets.map(m => m.folderPath));

        const modal = UI.modal('<div class="modal modal-wide">'
            + '<div class="modal-head"><span>批量规范化命名</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body" id="bn-body"></div>'
            + '<div class="modal-foot" id="bn-foot"></div></div>');
        const body = modal.box.querySelector('#bn-body');
        const foot = modal.box.querySelector('#bn-foot');

        function drawList() {
            let html = '<p class="muted small">勾选要规范化的漫画，点「执行」把目录名改成规范名。</p>'
                + '<div class="row" style="margin-bottom:8px">'
                + '<button id="bn-all">全选</button><button id="bn-none">清空</button>'
                + '<span class="muted small">已选 ' + chosen.size + ' / ' + targets.length + '</span></div>'
                + '<table><thead><tr><th style="width:5%"></th><th>原名</th><th>规范化名</th></tr></thead><tbody>';
            for (const t of targets) {
                html += '<tr data-path="' + UI.esc(t.folderPath) + '">'
                    + '<td><input type="checkbox" class="bn-check"'
                    + (chosen.has(t.folderPath) ? ' checked' : '') + '></td>'
                    + '<td class="mono small">' + UI.esc(t.folderName) + '</td>'
                    + '<td class="mono small">' + UI.esc(t.normalizedName) + '</td></tr>';
            }
            html += '</tbody></table>';
            body.innerHTML = html;
            body.querySelector('#bn-all').onclick = () => {
                targets.forEach(t => chosen.add(t.folderPath));
                drawList();
            };
            body.querySelector('#bn-none').onclick = () => {
                chosen.clear();
                drawList();
            };
            body.querySelectorAll('.bn-check').forEach(cb => cb.onchange = () => {
                const path = cb.closest('tr').dataset.path;
                if (cb.checked) {
                    chosen.add(path);
                } else {
                    chosen.delete(path);
                }
            });
        }

        function drawFoot() {
            foot.innerHTML = '<button class="btn-primary" id="bn-run">执行（' + chosen.size
                + '）</button><button class="btn-plain" id="bn-cancel">取消</button>';
            foot.querySelector('#bn-cancel').onclick = () => modal.close();
            foot.querySelector('#bn-run').onclick = run;
        }

        async function run(ev) {
            const paths = [...chosen];
            if (!paths.length) {
                UI.err('先勾选要规范化的漫画');
                return;
            }
            // 提交 → 轮询到终态期间全程禁用按钮（同 song.js 批量规范化的坑：
            // 提交一起轮询就返回、不接终态会让 withBusy 立刻恢复按钮，连点会重复提交）
            await UI.withBusy(ev.target, '执行中…', () =>
                new Promise((resolve) => {
                    const settled = () => resolve();
                    TaskPoll.submitAndWait(
                        async () => {
                            const taskId = await Api.post('/api/manga/new/normalize-batch',
                                {folderPaths: paths});
                            body.innerHTML = UI.spinner('正在规范化 ' + paths.length + ' 个目录名…');
                            return taskId;
                        },
                        {
                            onResult: (result) => {
                                try {
                                    let html = '<p class="row"><span class="tag tag-ok">成功 '
                                        + result.succeeded + '</span><span class="tag">共 '
                                        + result.total + '</span></p>'
                                        + '<table><tbody>';
                                    for (const r of result.results) {
                                        html += r.error
                                            ? '<tr><td class="mono small">' + UI.esc(r.fromName || r.folderPath) + '</td>'
                                                + '<td style="color:var(--err)">' + UI.esc(r.error) + '</td></tr>'
                                            : '<tr><td class="mono small">' + UI.esc(r.fromName) + ' → '
                                                + UI.esc(r.toName) + '</td><td><span class="tag tag-ok">已改</span></td></tr>';
                                    }
                                    html += '</tbody></table>';
                                    body.innerHTML = html;
                                    foot.innerHTML = '<button class="btn-primary" id="bn-done">完成</button>';
                                    foot.querySelector('#bn-done').onclick = finish;
                                    modal.box.querySelector('.modal-close').onclick = finish;
                                    return true;
                                } finally {
                                    settled();
                                }
                            },
                            onFail: (error) => {
                                body.innerHTML = '<div class="hint hint-err">' + UI.esc(error) + '</div>';
                                foot.innerHTML = '<button class="btn-primary" id="bn-done">完成</button>';
                                foot.querySelector('#bn-done').onclick = finish;
                                settled();
                            }
                        }).catch((e) => {
                            // 提交失败：弹窗停在列表上，按钮已恢复，可直接再点
                            UI.err(e.message);
                            settled();
                        });
                }));
        }

        async function finish() {
            modal.close();
            await renderSingle(host);
        }

        drawList();
        drawFoot();
        modal.box.querySelector('.modal-close').onclick = () => modal.close();
    }

    function bindExtras(host, box) {
        box.querySelectorAll('.dict-icon').forEach(btn => btn.onclick = () => {
            const value = btn.dataset.value;
            const type = btn.classList.contains('dict-icon-exhibit') ? 'EXHIBIT' : 'PARODY';
            const folders = (type === 'EXHIBIT' ? scan.extraExhibits : scan.extraParodies)[value] || [];
            openDictDialog(host, type, value, folders);
        });
    }

    // ------------------------------------------------------------------
    // 未识别展会/原作 补录对话框
    // ------------------------------------------------------------------

    /**
     * 阅读页与归档弹窗共用的部分挪到了 MangaReader（js/mangareader.js）。
     * 这里只组装两页差异：写端点、归档动作（压缩 + 任务轮询）、数据来源（scan/collections）。
     */

    /** 重画单本列表（不重扫，scan 还在内存）：存储提交后立即把对应卡片画成锁定态 */
    function redrawSingle(host) {
        const box = host.querySelector('#new-body');
        if (box) {
            renderContent(host, box);
        }
    }

    /** 重画合集列表（不重扫，collections 还在内存），同上 */
    function redrawCollections(host) {
        const box = host.querySelector('#new-body');
        if (box) {
            renderCollContent(host, box);
        }
    }

    /** 评分档位：有分区配置用配置，否则默认 9/7/5/3 */
    function scoreOptionsOf() {
        let options = [9, 7, 5, 3];
        if (scan && scan.scoreDirs) {
            const s = Object.keys(scan.scoreDirs).map(Number).sort((a, b) => b - a);
            if (s.length) {
                options = s;
            }
        }
        return options;
    }

    /** 单本与合集共用的写端点；归档动作按是否合集分开（合集是「压缩并规范化」） */
    function archiveOpts(host, isCollection) {
        const archive = isCollection ? {
            label: '压缩并规范化',
            title: '压缩并规范化',
            confirm: () => '压缩这本的全部图片（NConvert 删原图，不可回滚），'
                + '并把目录名改成规范名？',
            run: async (path) => {
                const taskId = await Api.post('/api/manga/new/compress-normalize?folderPath='
                    + encodeURIComponent(path));
                storing.add(path);
                redrawCollections(host);
                pollTask(host, taskId);
            }
        } : {
            label: '归档',
            title: '归档',
            confirm: (m) => '归档这本：压缩（NConvert 删原图，不可回滚）→ 规范化改名 → '
                + '按作者归档或落进未归档目录？\n\n将归档到：'
                + (m.destFolder || '待定（按所选评分重算）'),
            run: async (path, score, tags) => {
                pendingStoreTags = tags || [];
                const taskId = await Api.post('/api/manga/new/store',
                    {items: [{folderPath: path, score: score}], tags: pendingStoreTags});
                storing.add(path);
                redrawSingle(host);
                pollTask(host, taskId, (r) => storeOnResult(host, r));
            }
        };
        return {
            scoreOptions: scoreOptionsOf(),
            ops: {
                rename: (path, name) => Api.put('/api/manga/new/folder-name',
                    {folderPath: path, newFolderName: name}),
                remove: (path) => Api.del('/api/manga/new?folderPath=' + encodeURIComponent(path))
            },
            archive: archive
        };
    }

    /** 归档弹窗：单本。数据重扫后按目录名找回。
     *  新漫画不入库（无 mangaId），只预填父级标签（归档匹配的目标目录，作者相关标签）。 */
    async function openArchiveModal(host, manga, onDone) {
        let withTags = manga;
        if (manga.archiveMatch && manga.archiveMatch.target) {
            try {
                const parentTags = await Api.get('/api/manga/archive/units/'
                    + manga.archiveMatch.target.unitId + '/tags') || [];
                withTags = Object.assign({}, manga, {parentTags: parentTags});
            } catch (e) {
                // 读不到父级标签就降级，弹窗标签栏留空
            }
        }
        const opts = archiveOpts(host, false);
        opts.reload = () => renderSingle(host);
        opts.onDone = onDone;
        MangaReader.openArchiveModal(host, withTags, opts);
    }

    /**
     * 阅读页：单本（有「修改/归档」弹窗入口）与合集（内联改名 + 压缩并规范化）。
     * 导航列表 = **列表页这一刻显示的那一份**（单本跟 filters、合集跟展开后的合集内筛选），
     * 重扫后自动取新 —— 阅读页的「上一个 / 下一个」与列表页的顺序因此始终一致。
     */
    function openReader(host, manga, isCollection, collPath) {
        const opts = archiveOpts(host, isCollection);
        const key = isCollection ? collPath : null;
        opts.list = () => visibleMangas(key);
        opts.listAll = () => allMangas(key);
        opts.rescan = () => isCollection ? renderCollections(host) : renderSingle(host);
        if (!isCollection) {
            // 单本：阅读页「修改/归档」按钮 → 关阅读页 → 打开归档弹窗
            opts.edit = (m, onDone) => openArchiveModal(host, m, onDone);
        }
        MangaReader.openReader(host, manga, opts);
    }

    /** 未识别展会/原作 补录弹窗：弹窗本体在 MangaReader（两页共用），存完重扫当前页签 */
    function openDictDialog(host, type, value, folders) {
        MangaReader.openDictDialog({
            type, value, folders,
            onSaved: () => tab === 'single' ? renderSingle(host) : renderCollections(host)
        });
    }

    // ------------------------------------------------------------------
    // 合集
    // ------------------------------------------------------------------

    async function renderCollections(host) {
        mangaFilter.clear();
        const box = host.querySelector('#new-body');
        box.innerHTML = UI.spinner('扫描中…');
        try {
            const res = await Api.get('/api/manga/new/collections');
            collections = res.collections;
            requireTagsBeforeArchive = !!res.requireTagsBeforeArchive;
        } catch (e) {
            box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        renderCollContent(host, box);
    }

    /** 重画合集页。筛选切换也走这里 —— collections 还在内存里，不重扫 */
    function renderCollContent(host, box) {
        box.innerHTML = renderCollHead(collections) + renderCollExtras(collections)
            + '<div id="task-box"></div>'
            + renderCollCards(collections);
        bindCollections(host, box);
        bindCollHead(host, box);
        bindCollExtras(host, box);
    }

    /** 合集计数标签：与单本页同一套交互（选中高亮，再点取消），键名换成合集维度 */
    function collFilterTag(key, label, cls) {
        const active = collFilters.has(key);
        return '<button class="tag' + (active ? ' tag-filter-on' : '') + '" data-cfilter="' + key + '"'
            + (cls ? ' ' + cls : '') + '>' + label + '</button>';
    }

    function renderCollHead(list) {
        const total = list.length;
        const storable = list.filter(c => c.storable).length;
        const badName = list.filter(c => !c.groupArtistOk).length;
        const irregular = list.filter(c => c.mangas.some(m => m.matchedRule === 0)).length;
        const exhibit = list.filter(c => c.mangas.some(m => m.extraExhibit)).length;
        const parody = list.filter(c => c.mangas.some(m => m.extraParody)).length;
        const conflict = list.filter(c => c.archiveMatch && c.archiveMatch.target).length;

        let html = '<div class="card"><h2>作者合集 <span class="muted small">'
            + total + ' 个</span></h2>'
            + '<p class="muted small">一个子目录是一个合集，目录名形如 '
            + '<span class="mono">[社团 (作者)]</span>。合集是<strong>整体</strong>评分与打标签，'
            + '不视为对单本评分（落库时记成「继承」）。存储后整个目录搬进对应评分的归档根，'
            + '合集目录本身就成了归档目录。合集内的单本在阅读页只有「压缩并规范化」，'
            + '存储是合集级别整体做的。</p>';

        html += '<p class="row">'
            + collFilterTag('storable', '可存储 ' + storable, 'tag-ok')
            + collFilterTag('badName', '目录名不合法 ' + badName, badName ? 'tag-err' : '')
            + collFilterTag('irregular', '不规范 ' + irregular, irregular ? 'tag-warn' : '')
            + collFilterTag('exhibit', '未识别展会 ' + exhibit, exhibit ? 'tag-warn' : '')
            + collFilterTag('parody', '未识别原作 ' + parody, parody ? 'tag-warn' : '')
            + collFilterTag('conflict', '作者已归档 ' + conflict, conflict ? 'tag-err' : '')
            + '</p>'
            + (collFilters.size
                ? '<p class="small muted">已筛选：' + [...collFilters].map(k => COLL_FILTER_LABELS[k]).join('、')
                    + '。再点一次选中的计数标签取消。</p>'
                : '');

        return html + '</div>';
    }

    /** 未识别展会/原作：跨合集汇总，图标按钮点开补录（保存后自动重扫） */
    function renderCollExtras(list) {
        const exhibits = {}, parodies = {};
        for (const c of list) {
            for (const m of c.mangas) {
                if (m.extraExhibit) {
                    (exhibits[m.extraExhibit] = exhibits[m.extraExhibit] || []).push(m.folderPath);
                }
                if (m.extraParody) {
                    (parodies[m.extraParody] = parodies[m.extraParody] || []).push(m.folderPath);
                }
            }
        }
        const eEntries = Object.entries(exhibits);
        const pEntries = Object.entries(parodies);
        if (!eEntries.length && !pEntries.length) {
            return '';
        }
        let html = '<div class="card"><h2>未识别的展会与原作</h2>'
            + '<p class="muted small">带着它们的漫画不能归档，合集也因此不能整体存储。'
            + '点按钮补录（保存即生效，会自动重扫）。</p><div class="row">';
        const icon = (rows, cls) => rows.map(([value, folders]) =>
            '<button class="dict-icon dict-icon-' + cls + '" data-value="' + UI.esc(value) + '"'
            + ' title="出现在 ' + folders.length + ' 个目录：'
            + UI.esc(folders.slice(0, 5).map(f => f.split('\\').pop()).join('、'))
            + (folders.length > 5 ? '…' : '') + '">' + UI.esc(value) + '</button>').join('');
        html += icon(eEntries, 'exhibit') + icon(pEntries, 'parody');
        return html + '</div></div>';
    }

    /** 合集是否命中某个筛选条件（与合集闸门同口径） */
    function collMatches(c, key) {
        switch (key) {
            case 'storable': return c.storable;
            case 'badName': return !c.groupArtistOk;
            case 'irregular': return c.mangas.some(m => m.matchedRule === 0);
            case 'exhibit': return c.mangas.some(m => m.extraExhibit);
            case 'parody': return c.mangas.some(m => m.extraParody);
            case 'conflict': return !!(c.archiveMatch && c.archiveMatch.target);
            default: return true;
        }
    }

    function renderCollCards(list) {
        const filtered = list.filter(c => [...collFilters].every(f => collMatches(c, f)));
        if (!filtered.length) {
            return '<div class="card">' + UI.empty(collFilters.size
                ? '没有符合当前筛选的合集。再点一次选中的计数标签取消筛选。'
                : '没有合集。') + '</div>';
        }
        return filtered.map(c => renderCollectionCard(c)).join('');
    }

    function bindCollHead(host, box) {
        box.querySelectorAll('[data-cfilter]').forEach(btn => btn.onclick = () => {
            const key = btn.dataset.cfilter;
            if (collFilters.has(key)) {
                collFilters.delete(key);
            } else {
                collFilters.add(key);
            }
            renderCollContent(host, box);
        });
    }

    function bindCollExtras(host, box) {
        box.querySelectorAll('.dict-icon').forEach(btn => btn.onclick = () => {
            const value = btn.dataset.value;
            const type = btn.classList.contains('dict-icon-exhibit') ? 'EXHIBIT' : 'PARODY';
            const folders = [];
            for (const c of collections) {
                for (const m of c.mangas) {
                    const v = type === 'EXHIBIT' ? m.extraExhibit : m.extraParody;
                    if (v === value) {
                        folders.push(m.folderPath);
                    }
                }
            }
            openDictDialog(host, type, value, folders);
        });
    }

    /** 合集里的漫画区：展开且出错的合集带筛选标签，按命中的筛选过滤内部漫画 */
    function renderCollMangas(c, expanded) {
        if (!expanded) {
            return '<div class="manga-grid">' + c.mangas.map(m => MangaCard.render(m)).join('') + '</div>';
        }
        const filterBar = c.blockers.length ? mangaFilterBar(c.mangas) : '';
        const filtered = c.mangas.filter(m => [...mangaFilter].every(f => matchFilter(m, f)));
        let html = filterBar
            + '<div class="manga-grid">' + filtered.map(m => MangaCard.render(m)).join('') + '</div>';
        if (!filtered.length) {
            html += UI.empty('没有符合当前筛选的漫画。');
        }
        return html;
    }

    /** 内部漫画筛选标签：只对「不规范/未识别展会/未识别原作」三个出错维度，键名复用单本页 matchFilter */
    function mangaFilterBar(mangas) {
        const irregular = mangas.filter(m => m.matchedRule === 0).length;
        const exhibit = mangas.filter(m => m.extraExhibit).length;
        const parody = mangas.filter(m => m.extraParody).length;
        return '<p class="row small muted" style="margin:0 0 10px">本合集漫画筛选：'
            + mangaFilterTag('irregular', '不规范 ' + irregular, irregular ? 'tag-err' : '')
            + mangaFilterTag('exhibit', '未识别展会 ' + exhibit, exhibit ? 'tag-warn' : '')
            + mangaFilterTag('parody', '未识别原作 ' + parody, parody ? 'tag-warn' : '')
            + '</p>';
    }

    function mangaFilterTag(key, label, cls) {
        const active = mangaFilter.has(key);
        return '<button class="tag' + (active ? ' tag-filter-on' : '') + '" data-mfilter="' + key + '"'
            + (cls ? ' ' + cls : '') + '>' + label + '</button>';
    }

    function renderCollectionCard(c) {
        const isStoring = storing.has(c.folderPath);
        const expanded = !isStoring && c.folderPath === expandedPath;
        let html = '<div class="card' + (isStoring ? ' storing' : '') + '" data-path="'
            + UI.esc(c.folderPath) + '">'
            + '<h2><span class="mono">' + UI.esc(c.folderName) + '</span>'
            + ' <span class="muted small">' + c.mangaCount + ' 本，'
            + c.fileCount + ' 个文件</span></h2>';

        if (c.artist || c.groupName) {
            html += '<p class="small muted">'
                + (c.groupName ? UI.esc(c.groupName) + ' ' : '')
                + (c.artist ? '(' + UI.esc(c.artist) + ')' : '') + '</p>';
        }

        if (c.archiveMatch && c.archiveMatch.target) {
            // 与已归档作者重名 → 存储会改名入库成第二个目录，作者名落两个 unit、
            // 成为合并冲突，到合并冲突页并排比对。folderPath 是 String（不是 Path），
            // 不会有 file:/// 前缀
            html += '<div class="hint">这个作者已经有归档目录了：'
                + '<span class="mono">' + UI.esc(c.archiveMatch.target.folderPath) + '</span><br>'
                + '存储后会改名入库、和这个目录在作者名上<strong>重名</strong>，'
                + '到合并冲突页并排比对、把两边并到一处。</div>';
        }

        if (c.blockers.length) {
            html += '<div class="hint">还不能存储：<br>'
                + c.blockers.map(b => '· ' + UI.esc(b)).join('<br>') + '</div>';
        }

        if (isStoring) {
            html += '<div class="row">'
                + '<span class="tag tag-warn">存档中…</span>'
                + '<span class="small muted">正在压缩并搬进归档根，暂时不能操作。</span>'
                + '</div>';
        } else {
            html += '<div class="row">'
                + '<span class="small muted">整体评分</span>'
                + [9, 7, 5, 3].map(s => '<button class="score-btn cs-score" data-score="' + s
                    + '">' + s + '</button>').join('')
                + '<input type="text" class="cs-tags" placeholder="整体标签，空格分隔（会写进目录名）">'
                + '<button class="btn-primary cs-store"' + (c.storable ? '' : ' disabled')
                + '>存储整个合集</button>'
                + '<button class="btn-danger cs-del">删除合集</button>'
                + '<button class="cs-toggle">' + (expanded ? '收起 ' : '展开 ')
                + c.mangaCount + ' 本</button>'
                + '</div>';
        }
        html += '<div class="cs-mangas" style="margin-top:12px"' + (expanded ? '' : ' hidden')
            + '>' + renderCollMangas(c, expanded) + '</div>';

        return html + '</div>';
    }

    function bindCollections(host, box) {
        box.querySelectorAll('.card[data-path]').forEach(card => {
            const path = card.dataset.path;

            card.querySelectorAll('.cs-score').forEach(btn => btn.onclick = () => {
                card.querySelectorAll('.cs-score').forEach(b => b.classList.remove('on'));
                btn.classList.add('on');
            });

            const toggle = card.querySelector('.cs-toggle');
            if (toggle) {
                toggle.onclick = () => toggleExpand(host, box, card, path);
            }

            // 展开合集里的漫画筛选标签：改筛选就整页重画（collections 在内存里，不重扫）
            card.querySelectorAll('[data-mfilter]').forEach(btn => btn.onclick = () => {
                const key = btn.dataset.mfilter;
                if (mangaFilter.has(key)) {
                    mangaFilter.delete(key);
                } else {
                    mangaFilter.add(key);
                }
                renderCollContent(host, box);
            });

            const store = card.querySelector('.cs-store');
            if (store && !store.disabled) {
                store.onclick = async (ev) => {
                    const on = card.querySelector('.cs-score.on');
                    if (!on) {
                        UI.err('先给这个合集选一个整体评分');
                        return;
                    }
                    const raw = card.querySelector('.cs-tags').value.trim();
                    const tags = raw ? raw.split(/[\s、，,]+/).filter(Boolean) : [];
                    // 只有配置里开了「归档前强制打标签」才先拦一道；后端同样有闸门，
                    // 这里提前说是为了少一趟「提交了才知道不行」
                    if (requireTagsBeforeArchive && !tags.length) {
                        UI.err('不打标签不能归档，先在上面的「整体标签」里填标签');
                        return;
                    }
                    if (!await UI.confirm('存储整个合集？\n\n' + path
                        + '\n\n会逐本压缩（删原图，不可回滚），然后把整个目录搬进 '
                        + on.dataset.score + ' 分归档根。',
                        {title: '存储整个合集', okText: '存储'})) {
                        return;
                    }
                    await UI.withBusy(ev.target, '提交中…', async () => {
                        try {
                            const taskId = await Api.post('/api/manga/new/collection/store', {
                                folderPath: path,
                                score: Number(on.dataset.score),
                                tags: tags
                            });
                            storing.add(path);
                            expandedPath = null;
                            redrawCollections(host);
                            // 合集确认需保留提交时的 folderPath/score/tags，闭包捕获即可
                            pollTask(host, taskId, async (r) => {
                                if (r && r.pendingConfirmations && r.pendingConfirmations.length) {
                                    await MangaReader.confirmCollectionMove(r.pendingConfirmations, {
                                        commit: () => Api.post('/api/manga/new/collection/store/confirm', {
                                            folderPath: path,
                                            score: Number(on.dataset.score),
                                            tags: tags
                                        })
                                    });
                                    return true;
                                }
                                return false;
                            });
                        } catch (e) {
                            UI.err(e.message);
                        }
                    });
                };
            }

            const del = card.querySelector('.cs-del');
            if (del) {
                del.onclick = async (ev) => {
                    if (!await UI.confirm('删除整个合集？\n\n' + path
                        + '\n\n连同里面的所有文件一起送进 Windows 回收站，可以从那里还原。',
                        {title: '删除合集', okText: '删除'})) {
                        return;
                    }
                    await UI.withBusy(ev.target, '删除中…', async () => {
                        try {
                            await Api.del('/api/manga/new?folderPath=' + encodeURIComponent(path));
                            UI.ok('已删除合集');
                            if (expandedPath === path) {
                                expandedPath = null;
                            }
                            await renderCollections(host);
                        } catch (e) {
                            UI.err(e.message);
                        }
                    });
                };
            }

            // 合集里的单本：阅读进 openReader（isCollection=true），改名/删除复用卡片组件
            MangaCard.bind(card, {
                read: (p) => {
                    const col = collections.find(cc => cc.folderPath === path);
                    const manga = col && col.mangas.find(mm => mm.folderPath === p);
                    if (manga) {
                        openReader(host, manga, true, path);
                    }
                },
                rename: async (p, btn) => {
                    const current = p.split('\\').pop();
                    const name = prompt('新的文件夹名：', current);
                    if (!name || name === current) {
                        return;
                    }
                    await act(host, () => Api.put('/api/manga/new/folder-name',
                        {folderPath: p, newFolderName: name}), btn, true);
                },
                remove: async (p, btn) => {
                    if (!await UI.confirm('删除这本？\n\n' + p
                        + '\n\n目录会送进 Windows 回收站，删错了可以从那里还原。')) {
                        return;
                    }
                    await act(host, () => Api.del(
                        '/api/manga/new?folderPath=' + encodeURIComponent(p)), btn, true);
                }
            });
        });
    }

    /** 合集展开互斥：先把所有卡片收起，再展开被点的那张（再点同一张就是收起） */
    function toggleExpand(host, box, card, path) {
        // 换合集/收起都清掉漫画筛选；展开后的本数用合集总本数，别被筛选后的 DOM 数量带偏
        mangaFilter.clear();
        const countOf = (p) => {
            const col = collections.find(c => c.folderPath === p);
            return col ? col.mangaCount : 0;
        };
        box.querySelectorAll('.card[data-path]').forEach(c => {
            const list = c.querySelector('.cs-mangas');
            if (list) {
                list.hidden = true;
            }
            const t = c.querySelector('.cs-toggle');
            if (t) {
                t.textContent = '展开 ' + countOf(c.dataset.path) + ' 本';
            }
        });
        if (expandedPath === path) {
            expandedPath = null;
            return;
        }
        expandedPath = path;
        const list = card.querySelector('.cs-mangas');
        if (list) {
            list.hidden = false;
        }
        const t = card.querySelector('.cs-toggle');
        if (t) {
            t.textContent = '收起 ' + countOf(path) + ' 本';
        }
    }

    // ------------------------------------------------------------------
    // 共用：操作后重扫 / 任务进度
    // ------------------------------------------------------------------

    /**
     * 跑一个操作，完了重扫。
     * 每次都重扫是因为改名/评分会牵动规范化结果与归档匹配，就地改 DOM
     * 等于在前端把后端那套规则再写一遍。
     */
    async function act(host, fn, btn, reload) {
        const run = async () => {
            try {
                await fn();
                if (reload) {
                    await (tab === 'single' ? renderSingle(host) : renderCollections(host));
                }
            } catch (e) {
                UI.err(e.message);
            }
        };
        if (btn) {
            await UI.withBusy(btn, '…', run);
        } else {
            await run();
        }
    }

    /**
     * 轮询任务进度。DONE 时先调 onResult(task.result)（若有），由它决定要不要
     * 弹二次确认；返回 true 表示已处理（弹了确认），跳过通用的「处理完成」提示。
     * 确认弹窗里点「归档」是同步提交，所以这里 await 等它关掉再重扫。
     */
    function pollTask(host, taskId, onResult) {
        stopPolling();
        pollStop = TaskPoll.wait(taskId, {
            onTick: (task) => {
                lastTask = task;
                const current = host.querySelector('#task-box');
                if (current) {
                    current.innerHTML = TaskPoll.renderTask(task);
                }
            },
            onResult: async (result) => {
                const handled = onResult ? await onResult(result) === true : false;
                await finishStore(host, null);
                return handled;
            },
            onFail: async (error) => {
                await finishStore(host, error);
            }
        });
    }

    /** 任务结束（成功/失败/中断）后的收尾：解锁卡片、重扫列表、把结果补回去 */
    async function finishStore(host, error) {
        storing.clear();
        await (tab === 'single' ? renderSingle(host) : renderCollections(host));
        const after = host.querySelector('#task-box');
        if (after) {
            // 重扫会重画 #task-box，把结果/失败原因补回去，否则错误就看不到了
            after.innerHTML = error
                ? '<div class="hint hint-err">' + UI.esc(error) + '</div>'
                : (lastTask ? TaskPoll.renderTask(lastTask) : '');
        }
    }

    /**
     * 单本/批量存储完成后：有压缩失败的，弹批量确认弹窗逐本归档；
     * 归档成功但 eh 标签拉取失败（且没填标签）的，逐本弹补标签。
     * commit 只归档单本（items 单元素，带提交时的评分），tags 与提交时一致（pendingStoreTags）。
     */
    async function storeOnResult(host, r) {
        let handled = false;
        if (r && r.pendingConfirmations && r.pendingConfirmations.length) {
            await MangaReader.confirmPending(r.pendingConfirmations, {
                commit: (p, score) => Api.post('/api/manga/new/store/confirm',
                    {items: [{folderPath: p, score: score}], tags: pendingStoreTags})
            });
            handled = true;
        }
        // 归档成功但 eh 标签拉取失败（且用户没填标签）的，逐本弹补标签
        if (r && r.results) {
            for (const it of r.results) {
                if (it.tagPullFailed && it.mangaId != null) {
                    await MangaReader.promptMissingTags(it.mangaId,
                        it.fromFolderPath.split('\\').pop());
                }
            }
        }
        return handled;
    }

    function stopPolling() {
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
        if (pollStop) {
            pollStop();
            pollStop = null;
        }
    }

    return {render};
})();
