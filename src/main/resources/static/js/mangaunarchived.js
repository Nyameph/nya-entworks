/**
 * 「未归档」漫画页（文档 4.9）。
 *
 * 与新漫画页不同，这一层<b>已入库</b>（manga_data.status = UNARCHIVED），
 * 所以数据从库里读：分页展示、可搜索。卡片字段（规则/未识别展会/归档匹配）
 * 由后端每行现扫现算（复用 toNewManga，与新漫画页同一套判定）。
 *
 * 顶部是「贝叶斯平均分前 10」的作者与社团；「重新扫描」扫 #待整理散漫：
 * 规范化且命中归档作者的自动搬进归档目录（不压缩），其余按磁盘 upsert，
 * 目录消失的标失踪。
 *
 * 行内操作（阅读/归档/删除）复用 MangaReader 的阅读页与归档弹窗，改名/评分收进阅读页，
 * 写端点指向 /api/manga/unarchived/*。目录不在磁盘上的行降级成「失踪」卡，
 * 只给删除（清库记录，保评分与标签）。
 *
 * 「前端只画不判」同新漫画页：改名预演与归档闸门都走后端。
 */
const MangaUnarchivedPage = (() => {

    /** 未归档分区与归档分区共用同一组评分档位（MangaScoreDir.SCORES） */
    const SCORE_OPTIONS = [9, 7, 5, 3];
    const PAGE_SIZE = 20;

    // 模块级状态：一个页面实例，重扫后整页重画
    let keyword = '';
    let page = 1;
    let data = null;   // UnarchivedPage
    let top = null;    // TopResult
    let selected = new Set();  // 批量归档勾选的目录路径，限定在当前页（翻页/筛选即清空）
    let packing = new Set();   // 打包任务进行中的漫画路径：卡片锁定、禁止操作，任务结束清掉

    // ------------------------------------------------------------------
    // 顶层渲染
    // ------------------------------------------------------------------

    function render(host) {
        selected.clear();
        host.innerHTML = '<div id="unarchived-top"></div>'
            + '<div id="unarchived-body">' + UI.spinner('加载中…') + '</div>';
        let topErr = null, dataErr = null;
        Api.get('/api/manga/unarchived/top')
            .then(r => { top = r; })
            .catch(e => { top = null; topErr = e.message; })
            .then(() => renderTop(host, topErr));
        fetchPage()
            .then(r => { data = r; })
            .catch(e => { data = null; dataErr = e.message; })
            .then(() => renderBody(host, dataErr));
    }

    /** 只重拉列表并重画主体，Top10 不动（改名/评分/归档后走这里） */
    async function reload(host) {
        // 数据变了勾选就不作数：限定在当前页，翻页/筛选/单本操作后清空
        selected.clear();
        let err = null;
        try {
            data = await fetchPage();
        } catch (e) {
            data = null;
            err = e.message;
        }
        // 页号越界（自动归档让总行数变小）：回退到最后一页，别停在空页
        if (data && data.total > 0 && !data.items.length && data.page > 1) {
            page = Math.max(1, Math.ceil(data.total / data.size));
            try {
                data = await fetchPage();
            } catch (e) {
                data = null;
                err = e.message;
            }
        }
        renderBody(host, err);
    }

    async function fetchPage() {
        const q = new URLSearchParams();
        q.set('page', String(page));
        q.set('size', String(PAGE_SIZE));
        if (keyword) {
            q.set('keyword', keyword);
        }
        return await Api.get('/api/manga/unarchived?' + q.toString());
    }

    // ------------------------------------------------------------------
    // Top10 卡片 + 重新扫描
    // ------------------------------------------------------------------

    function renderTop(host, err) {
        const box = host.querySelector('#unarchived-top');
        const col = (title, entries) => '<div style="flex:1;min-width:0">'
            + '<h3 class="muted small" style="margin:0 0 8px">' + title + '</h3>'
            + (err ? '<div class="hint hint-err">' + UI.esc(err) + '</div>' : table(entries))
            + '</div>';
        box.innerHTML = '<div class="card">'
            + '<div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">贝叶斯平均分前 10</h2>'
            + '<button class="btn-primary" id="scan-unarchived">重新扫描</button>'
            + '</div>'
            + '<p class="muted small" style="margin-top:6px">对作者与社团分别聚合，'
            + '按加权贝叶斯平均分（低分降权、高分升权，样本越少越向全局均值靠拢）排序。'
            + '「重新扫描」扫 <span class="mono">#待整理散漫</span>：规范化且命中归档作者的'
            + '直接搬进归档目录（不压缩），其余按磁盘更新，目录消失的标失踪。</p>'
            + '<div class="row" style="align-items:flex-start;gap:24px">'
            + col('作者', top ? top.artists : [])
            + col('社团', top ? top.groups : [])
            + '</div>'
            + '</div>';

        const btn = box.querySelector('#scan-unarchived');
        btn.onclick = async (ev) => {
            await UI.withBusy(ev.target, '提交中…', async () => {
                try {
                    const taskId = await Api.post('/api/manga/unarchived/scan');
                    TaskPoll.wait(taskId, {
                        onResult: async (r) => {
                            UI.ok('扫描完成：扫 ' + r.scanned + ' 本，新增 ' + r.inserted
                                + '，更新 ' + r.updated + '，自动归档 ' + r.archived
                                + '，失踪 ' + r.missing);
                            await render(host);
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

    function table(entries) {
        const rows = entries.length
            ? entries.map((e, i) => '<tr><td class="muted">' + (i + 1) + '</td>'
                + '<td>' + UI.esc(e.name) + '</td>'
                + '<td>' + e.count + '</td>'
                + '<td><strong>' + (e.bayesian == null ? '<span class="muted">—</span>'
                    : Number(e.bayesian).toFixed(1)) + '</strong></td></tr>').join('')
            : '<tr><td colspan="4" class="muted">还没有数据。先点「重新扫描」。</td></tr>';
        return '<table><thead><tr>'
            + '<th style="width:8%"></th><th>名字</th>'
            + '<th style="width:16%">本数</th><th style="width:20%">贝叶斯分</th>'
            + '</tr></thead><tbody>' + rows + '</tbody></table>';
    }

    // ------------------------------------------------------------------
    // 列表主体：筛选 + 分页 + 卡片
    // ------------------------------------------------------------------

    function renderBody(host, err) {
        const box = host.querySelector('#unarchived-body');
        if (err) {
            box.innerHTML = '<div class="hint hint-err">' + UI.esc(err) + '</div>';
            return;
        }
        box.innerHTML = filterCard() + batchCard() + pagerCard() + gridCard();
        bindFilter(host);
        bindPager(host);
        bindGrid(host);
        bindBatch(host);
    }

    function filterCard() {
        return '<div class="card"><div class="row">'
            + '<input type="text" id="u-keyword" value="' + UI.esc(keyword)
            + '" placeholder="搜标题 / 作者 / 社团 / 目录路径">'
            + '<button class="btn-primary" id="u-query">查询</button>'
            + '</div></div>';
    }

    /** 批量归档工具条：只在有列表时出现。可勾选的判定与后端 archive 闸门同口径 */
    function batchCard() {
        if (!data || !data.items.length) {
            return '';
        }
        return '<div class="card"><div class="row">'
            + '<button id="u-select-all">全选本页可归档</button>'
            + '<button id="u-batch-archive" class="btn-primary"'
            + (selected.size ? '' : ' disabled') + '>批量归档已选（' + selected.size + '）</button>'
            + '<button id="u-clear-select"' + (selected.size ? '' : ' disabled') + '>清空勾选</button>'
            + '<button id="u-batch-pack">打包本页全部 cbz</button>'
            + '</div></div>';
    }

    /** 可归档：命名规范、无未识别展会/原作、有图、命中归档目标（评分不在候选条件，归档弹窗里选） */
    function archivable(m) {
        return m.matchedRule !== 0 && !m.extraExhibit && !m.extraParody && m.imageCount > 0
            && m.archiveMatch && m.archiveMatch.target != null;
    }


    /** 目录漫画才有「打包 cbz」按钮；已经是 cbz 的不显示 */
    function actsFor(m) {
        const a = ['read', 'archive', 'delete'];
        if (!Util.isCbzName(m.folderName)) {
            a.push('pack');
        }
        return a;
    }

    function pagerCard() {
        if (!data || !data.total) {
            return '';
        }
        const totalPages = Math.max(1, Math.ceil(data.total / data.size));
        return '<div class="card"><div class="row" style="justify-content:space-between">'
            + '<span class="muted small">共 ' + data.total + ' 本，第 ' + data.page + ' / '
            + totalPages + ' 页</span>'
            + '<div class="row">'
            + '<button id="u-prev"' + (data.page <= 1 ? ' disabled' : '') + '>上一页</button>'
            + '<button id="u-next"' + (data.page >= totalPages ? ' disabled' : '') + '>下一页</button>'
            + '</div></div></div>';
    }

    function gridCard() {
        if (!data || !data.items.length) {
            return '<div class="card">' + UI.empty(keyword
                ? '没有符合搜索条件的未归档漫画。'
                : '还没有未归档漫画。先到「新漫画」页存储，或点上面的「重新扫描」。') + '</div>';
        }
        const html = data.items.map(item => item.exists && item.manga
            ? MangaCard.render(item.manga, {
                acts: actsFor(item.manga),
                busy: packing.has(item.manga.folderPath) ? '打包中…' : null,
                select: {can: (m) => archivable(m), checked: (m) => selected.has(m.folderPath)}
            })
            : degradedCard(item)).join('');
        return '<div class="card"><div class="manga-grid">' + html + '</div></div>';
    }

    /** 目录不在磁盘上的行：降级成一行，只给删除（清库记录）。MangaCard.bind 的 c-delete 顺带绑定 */
    function degradedCard(item) {
        const row = item.row;
        return '<div class="manga-card blocked" data-path="' + UI.esc(row.folderPath) + '">'
            + '<div class="manga-cover-none">失踪</div>'
            + '<div class="manga-name" title="' + UI.esc(row.folderPath) + '">'
            + UI.esc(baseName(row.folderPath)) + '</div>'
            + '<div class="manga-meta"><span class="muted">目录已不在磁盘</span></div>'
            + '<div class="manga-status"><span class="tag tag-err">失踪</span>'
            + (row.score ? '<span class="tag tag-ok">' + row.score + ' 分</span>' : '')
            + '</div>'
            + '<div class="manga-acts">'
            + '<button class="btn-danger c-delete">删除</button>'
            + '</div>'
            + '</div>';
    }

    function baseName(p) {
        return String(p).split(/[\\/]/).filter(Boolean).pop() || p;
    }

    // ------------------------------------------------------------------
    // 事件绑定
    // ------------------------------------------------------------------

    function bindFilter(host) {
        const box = host.querySelector('#unarchived-body');
        const apply = () => {
            keyword = box.querySelector('#u-keyword').value.trim();
            page = 1;
            reload(host);
        };
        box.querySelector('#u-query').onclick = apply;
        box.querySelector('#u-keyword').onkeydown = (ev) => {
            if (ev.key === 'Enter') {
                apply();
            }
        };
    }

    function bindPager(host) {
        const box = host.querySelector('#unarchived-body');
        const prev = box.querySelector('#u-prev');
        const next = box.querySelector('#u-next');
        if (prev) {
            prev.onclick = () => {
                page = Math.max(1, page - 1);
                reload(host);
            };
        }
        if (next) {
            next.onclick = () => {
                const totalPages = Math.max(1, Math.ceil(data.total / data.size));
                if (page < totalPages) {
                    page++;
                    reload(host);
                }
            };
        }
    }

    function bindGrid(host) {
        const box = host.querySelector('#unarchived-body');
        const byPath = (path) => data && data.items.find(i => i.row.folderPath === path);
        MangaCard.bind(box, {
            read: (path) => {
                const item = byPath(path);
                if (item && item.manga) {
                    openReaderFor(host, item.manga);
                }
            },
            archive: (path) => {
                const item = byPath(path);
                if (item && item.manga) {
                    openArchiveModalFor(host, item.manga);
                }
            },
            pack: (path, btn) => {
                packManga(host, path, btn);
            },
            select: (path, checked) => {
                if (checked) {
                    selected.add(path);
                } else {
                    selected.delete(path);
                }
                syncBatchControls(host);
            },
            remove: async (path, btn) => {
                const item = byPath(path);
                const exists = !!(item && item.exists);
                if (!await UI.confirm(exists
                    ? '删除这个目录及里面的所有文件？\n\n' + path
                        + '\n\n目录会送进 Windows 回收站，删错了可以从那里还原。'
                    : '这个目录已不在磁盘上，删除它的库记录？\n\n' + path)) {
                    return;
                }
                await UI.withBusy(btn, '…', async () => {
                    try {
                        await Api.del('/api/manga/unarchived?folderPath='
                            + encodeURIComponent(path));
                        UI.ok(exists ? '已删除' : '已清除记录');
                        await reload(host);
                    } catch (e) {
                        UI.err(e.message);
                    }
                });
            }
        });
    }

    /** 勾选后只更新批量按钮的文案与可用态，不整页重画（勾选本身已在 DOM 上） */
    function syncBatchControls(host) {
        const box = host.querySelector('#unarchived-body');
        const store = box.querySelector('#u-batch-archive');
        if (store) {
            store.disabled = selected.size === 0;
            store.textContent = '批量归档已选（' + selected.size + '）';
        }
        const clear = box.querySelector('#u-clear-select');
        if (clear) {
            clear.disabled = selected.size === 0;
        }
    }

    function bindBatch(host) {
        const box = host.querySelector('#unarchived-body');
        const all = box.querySelector('#u-select-all');
        if (all) {
            all.onclick = () => {
                // 只勾当前页里能归档的，别把失踪卡或归不了的带上
                data.items.filter(i => i.exists && i.manga && archivable(i.manga))
                    .forEach(i => selected.add(i.manga.folderPath));
                renderBody(host, null);
            };
        }
        const clear = box.querySelector('#u-clear-select');
        if (clear) {
            clear.onclick = () => {
                selected.clear();
                renderBody(host, null);
            };
        }
        const store = box.querySelector('#u-batch-archive');
        if (store) {
            store.onclick = (ev) => batchArchive(host, ev.target);
        }
        const pack = box.querySelector('#u-batch-pack');
        if (pack) {
            pack.onclick = (ev) => batchPack(host, ev.target);
        }
    }

    /** 批量归档：先打标签（可留空，留空则归档时从 eh 拉取），勾选的路径逐本提交。后端一条失败不中断整批 */
    async function batchArchive(host, btn) {
        const paths = [...selected];
        if (!paths.length) {
            UI.err('先勾选要归档的漫画');
            return;
        }
        const tags = await askBatchTags(paths.length);
        if (tags === null) {
            return;
        }
        if (!await UI.confirm('批量归档 ' + paths.length + ' 本？\n\n每本都会规范化改名（如需）'
            + '→ 按作者搬进归档目录。存储时已压缩过，这里不再压缩。',
            {title: '批量归档', okText: '归档'})) {
            return;
        }
        await UI.withBusy(btn, '提交中…', async () => {
            try {
                const taskId = await Api.post('/api/manga/unarchived/archive-batch',
                    {folderPaths: paths, tags: tags});
                selected.clear();
                TaskPoll.wait(taskId, {
                    onResult: async (r) => {
                        if (r.errors && r.errors.length) {
                            UI.err('归档完成 ' + r.archived + ' 本，失败 ' + r.errors.length + ' 本：\n'
                                + r.errors.slice(0, 5).join('\n')
                                + (r.errors.length > 5 ? '\n……' : ''));
                        } else {
                            UI.ok('已归档 ' + r.archived + ' 本');
                        }
                        await reload(host);
                        // 归档成功但 eh 标签拉取失败的，逐本弹补标签
                        if (r.tagPullFailed && r.tagPullFailed.length) {
                            for (const it of r.tagPullFailed) {
                                await MangaReader.promptMissingTags(it.mangaId, it.folderName);
                            }
                        }
                        return true;
                    },
                    onFail: (error) => UI.err(error)
                });
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    /** 单本打包：原地生成同名 cbz 并删源目录（异步任务）。完成后重拉列表 */
    async function packManga(host, path, btn) {
        if (!await UI.confirm('把这本打包成 cbz 并删除原目录？\n\n' + path
            + '\n\n原目录会送进 Windows 回收站，可以从那里还原。')) {
            return;
        }
        await UI.withBusy(btn, '…', async () => {
            try {
                const taskId = await Api.post('/api/manga/pack-cbz', {folderPaths: [path]});
                packing.add(path);
                await reload(host);
                TaskPoll.wait(taskId, {
                    onResult: async (r) => {
                        packing.delete(path);
                        if (r.failed) {
                            UI.err('打包完成 ' + r.packed + ' 本，失败 ' + r.failed + ' 本');
                        } else {
                            UI.ok('已打包');
                        }
                        await reload(host);
                        return true;
                    },
                    onFail: (error) => {
                        packing.delete(path);
                        UI.err(error);
                    }
                });
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    /** 批量打包：本页全部目录漫画（不含 cbz 与失踪卡），一条批任务 */
    async function batchPack(host, btn) {
        const paths = (data ? data.items : [])
            .filter(i => i.exists && i.manga && !Util.isCbzName(i.manga.folderName))
            .map(i => i.manga.folderPath);
        if (!paths.length) {
            UI.err('本页没有可打包的漫画目录');
            return;
        }
        if (!await UI.confirm('把本页 ' + paths.length + ' 本目录漫画打包成 cbz 并删除原目录？'
            + '\n\n原目录会送进 Windows 回收站，可以从那里还原。',
            {title: '批量打包', okText: '打包'})) {
            return;
        }
        await UI.withBusy(btn, '提交中…', async () => {
            try {
                const taskId = await Api.post('/api/manga/pack-cbz', {folderPaths: paths});
                paths.forEach(p => packing.add(p));
                await reload(host);
                TaskPoll.wait(taskId, {
                    onResult: async (r) => {
                        paths.forEach(p => packing.delete(p));
                        if (r.failed) {
                            UI.err('打包 ' + r.packed + ' 本，失败 ' + r.failed + ' 本');
                        } else {
                            UI.ok('已打包 ' + r.packed + ' 本');
                        }
                        await reload(host);
                        return true;
                    },
                    onFail: (error) => {
                        paths.forEach(p => packing.delete(p));
                        UI.err(error);
                    }
                });
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    /** 收集归档标签（可留空：留空则归档时从 eh 拉取，失败需手动补）。同一批打同一组标签；取消返回 null */
    function askBatchTags(count) {
        return new Promise((resolve) => {
            const m = UI.modal('<div class="modal">'
                + '<div class="modal-head"><span>给这 ' + count + ' 本打标签</span>'
                + '<button class="modal-close" type="button">×</button></div>'
                + '<div class="modal-body">'
                + '<p class="muted small">标签可留空，留空则归档时从 eh 拉取（失败需手动补）。'
                + '标签按空格 / 顿号 / 逗号切分，同一批漫画打上同一组标签。</p>'
                + '<input type="text" id="bat-tags" placeholder="标签，空格分隔（可留空）" style="width:100%">'
                + '</div>'
                + '<div class="modal-foot">'
                + '<button type="button" class="btn-plain" id="bat-cancel">取消</button>'
                + '<button type="button" class="btn-primary" id="bat-ok">下一步</button>'
                + '</div></div>');
            const close = (val) => { m.close(); resolve(val); };
            m.box.querySelector('.modal-close').onclick = () => close(null);
            m.box.querySelector('#bat-cancel').onclick = () => close(null);
            m.box.querySelector('#bat-ok').onclick = () => {
                const raw = m.box.querySelector('#bat-tags').value.trim();
                const tags = raw ? raw.split(/[\s、，,]+/).filter(Boolean) : [];
                close(tags);
            };
            m.box.querySelector('#bat-tags').onkeydown = (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    m.box.querySelector('#bat-ok').click();
                }
            };
        });
    }

    // ------------------------------------------------------------------
    // 阅读页 / 归档弹窗（复用 MangaReader，写端点指向 unarchived）
    // ------------------------------------------------------------------

    /** 单本统一的操作与归档定义。归档走同步端点，不压缩（存储时已压缩过），评分与标签随归档提交 */
    function unarchivedOpts(host) {
        return {
            scoreOptions: SCORE_OPTIONS,
            ops: {
                rename: (path, name) => Api.put('/api/manga/unarchived/folder-name',
                    {folderPath: path, newFolderName: name}),
                tags: (mangaId, tags) => Api.put('/api/manga/tags/manga/' + mangaId,
                    {tags: tags}),
                remove: (path) => Api.del('/api/manga/unarchived?folderPath='
                    + encodeURIComponent(path))
            },
            archive: {
                label: '归档',
                title: '归档',
                confirm: (m) => '归档这本：规范化改名（如需）→ 按作者搬进归档目录。'
                    + '这本存储时已压缩过，这里不再压缩。\n\n将归档到：'
                    + (m.destFolder || '待定（按所选评分重算）'),
                run: async (path, score, tags) => {
                    const r = await Api.post('/api/manga/unarchived/archive',
                        {folderPath: path, score: score, tags: tags});
                    UI.ok('已归档');
                    await reload(host);
                    if (r && r.tagPullFailed) {
                        await MangaReader.promptMissingTags(r.mangaId, r.folderName);
                    }
                }
            }
        };
    }

    /** 归档弹窗：数据重扫后按目录名找回。标签预填两步：先独立标签（mangaId），
     *  没有则父级标签（归档匹配的目标目录，作者相关标签）。 */
    async function openArchiveModalFor(host, manga, onDone) {
        let withTags = manga;
        if (manga.mangaId != null) {
            try {
                const tags = await Api.get('/api/manga/tags/manga/' + manga.mangaId) || [];
                withTags = Object.assign({}, withTags, {ownTags: tags});
            } catch (e) {
                // 读不到独立标签就降级
            }
        }
        if ((!withTags.ownTags || !withTags.ownTags.length)
                && manga.archiveMatch && manga.archiveMatch.target) {
            try {
                const parentTags = await Api.get('/api/manga/archive/units/'
                    + manga.archiveMatch.target.unitId + '/tags') || [];
                withTags = Object.assign({}, withTags, {parentTags: parentTags});
            } catch (e) {
                // 读不到父级标签就降级，弹窗标签栏留空
            }
        }
        const opts = unarchivedOpts(host);
        opts.reload = () => reload(host);
        opts.onDone = onDone;
        MangaReader.openArchiveModal(host, withTags, opts);
    }

    /** 阅读页：单本（「修改/归档」弹窗入口）。导航列表 = 当前页里还在磁盘上的漫画 */
    function openReaderFor(host, manga) {
        const opts = unarchivedOpts(host);
        opts.list = () => (data ? data.items : [])
            .filter(i => i.exists && i.manga)
            .map(i => i.manga);
        opts.rescan = () => reload(host);
        opts.edit = (m, onDone) => openArchiveModalFor(host, m, onDone);
        MangaReader.openReader(host, manga, opts);
    }

    return {render};
})();
