/**
 * 漫画随机抽选页（需求 4.x 之外新增）。
 *
 * 已归档 / 未归档漫画各抽一批，按分数加权随机（高分比例更高、无放回），
 * 优先抽「没有独立标签」的漫画。抽选与权重都在后端（MangaRandomService），
 * 前端只选来源、点抽、画结果。
 *
 * 抽中的漫画后端已现扫出卡片字段（NewManga），所以这里能像已归档/未归档页一样
 * 直接「阅读」与「修改」：阅读走 MangaReader.openReader，修改走统一弹窗
 * （已归档落评分/标签/改名，未归档走「修改/归档」）。抽选结果只活在内存里，
 * 改名/归档后卡片可能轻微过时，点「抽选」重新抽一遍即回到磁盘事实。
 */
const MangaRandomPage = (() => {

    let result = null;   // RandomDraw
    let source = 'ARCHIVED';
    let count = 50;

    async function render(host) {
        host.innerHTML = shell();
        bind(host);
        if (result) {
            drawResult(host);
        }
    }

    function shell() {
        return '<div class="card">'
            + '<div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">随机抽选</h2>'
            + '<span class="muted small">按分数加权，高分更容易抽中；优先抽没有独立标签的</span>'
            + '</div>'
            + '<p class="muted small">抽选只读库与磁盘目录状态，不落任何结果 —— '
            + '抽出来就为「今天看这几本」。抽中后可直接「阅读」「修改」，与对应列表页一致。</p>'
            + '<div class="row">'
            + '<select id="mr-source">'
            + '<option value="ARCHIVED"' + (source === 'ARCHIVED' ? ' selected' : '') + '>已归档</option>'
            + '<option value="UNARCHIVED"' + (source === 'UNARCHIVED' ? ' selected' : '') + '>未归档</option>'
            + '</select>'
            + '<label class="small muted">抽 <input type="number" id="mr-count" min="1" max="500"'
            + ' value="' + count + '" style="width:72px"> 部</label>'
            + '<button class="btn-primary" id="mr-go">抽选</button>'
            + '</div>'
            + '<div id="mr-result"></div>'
            + '</div>';
    }

    function bind(host) {
        host.querySelector('#mr-source').onchange = (ev) => {
            source = ev.target.value;
        };
        host.querySelector('#mr-count').onchange = (ev) => {
            const v = Number(ev.target.value);
            count = Number.isFinite(v) && v > 0 ? Math.floor(v) : 50;
        };
        host.querySelector('#mr-go').onclick = async (ev) => {
            await UI.withBusy(ev.target, '抽选中…', async () => {
                try {
                    result = await Api.get('/api/manga/random?source=' + source
                        + '&count=' + count);
                    drawResult(host);
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };
    }

    function drawResult(host) {
        const box = host.querySelector('#mr-result');
        if (!box) {
            return;
        }
        const m = result;
        if (!m.mangas || !m.mangas.length) {
            box.innerHTML = '<div class="card">' + UI.empty('这个来源下没有能抽的漫画'
                + '（或目录都已不在磁盘上）。') + '</div>';
            return;
        }
        let html = '<p class="row" style="margin-top:14px">'
            + '<span class="tag tag-ok">抽中 ' + m.drawn + ' 部</span>'
            + '<span class="tag">无独立标签 ' + m.untaggedCount + '</span>'
            + '<span class="tag">有独立标签 ' + m.taggedCount + '</span>'
            + '<span class="muted small">来源：' + UI.esc(m.source) + '，请求 ' + m.requested + ' 部</span>'
            + '</p>'
            + '<div class="manga-grid">'
            + m.mangas.map(mm => MangaCard.render(mm.manga,
                {acts: ['read', 'edit']})).join('')
            + '</div>';
        box.innerHTML = html;
        MangaCard.bind(box, {
            read: (path) => openReader(host, findManga(path)),
            edit: (path) => openEdit(host, findManga(path))
        });
    }

    /** 按目录路径找回抽中的 RandomManga（改名后路径会变，找不回时点「抽选」重抽） */
    function findManga(path) {
        return result && result.mangas.find(mm => mm.manga.folderPath === path);
    }

    // ------------------------------------------------------------------
    // 阅读 / 修改（复用 MangaReader，端点按来源分流）
    // ------------------------------------------------------------------

    /** 与已归档/未归档页同款的写端点。已归档没有「归档」动作，只保存；未归档走「修改/归档」 */
    function randomOpts(host) {
        if (source === 'ARCHIVED') {
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
        return {
            scoreOptions: [9, 7, 5, 3],
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
                    drawResult(host);
                    if (r && r.tagPullFailed) {
                        await MangaReader.promptMissingTags(r.mangaId, r.folderName);
                    }
                }
            }
        };
    }

    /** 标签预填：独立标签后端已带（rm.tags）；没有则父级标签（作者相关标签，灰态区分） */
    async function fillTags(rm) {
        // 独立标签用 rm.tags（带命名空间的 TagItem）注入 ownTags；没有则取父级标签预填
        let manga = Object.assign({}, rm.manga, {ownTags: rm.tags || []});
        if (!manga.ownTags.length) {
            const unitId = source === 'ARCHIVED' ? rm.archiveUnitId
                : (rm.manga.archiveMatch && rm.manga.archiveMatch.target
                    ? rm.manga.archiveMatch.target.unitId : null);
            if (unitId != null) {
                try {
                    const parentTags = await Api.get('/api/manga/archive/units/'
                        + unitId + '/tags') || [];
                    manga = Object.assign({}, manga, {parentTags: parentTags});
                } catch (e) {
                    // 读不到父级标签就降级，弹窗标签栏留空
                }
            }
        }
        return manga;
    }

    /** 阅读页：导航列表 = 本次抽中的漫画（单一来源，状态一致） */
    function openReader(host, rm) {
        if (!rm) {
            return;
        }
        const opts = randomOpts(host);
        opts.list = () => (result ? result.mangas.map(x => x.manga) : []);
        opts.rescan = () => drawResult(host);
        opts.edit = (m, onDone) => openEdit(host, findManga(m.folderPath), onDone);
        MangaReader.openReader(host, rm.manga, opts);
    }

    /** 修改弹窗：按来源打开已归档（只保存）或未归档（修改/归档）的弹窗 */
    async function openEdit(host, rm, onDone) {
        if (!rm) {
            return;
        }
        const manga = await fillTags(rm);
        const opts = randomOpts(host);
        opts.reload = () => drawResult(host);
        opts.onDone = onDone;
        MangaReader.openArchiveModal(host, manga, opts);
    }

    return {render};
})();
