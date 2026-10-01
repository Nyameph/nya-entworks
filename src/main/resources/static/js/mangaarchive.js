/**
 * 归档作者/社团页（文档 4.4）。
 *
 * 这一页的立场是「文件系统是权威，库是镜像」：社团名、作者名、标签一律只读，
 * 要改就改目录名 —— 因为库里改了磁盘没改，下次同步会原样覆盖回去。所以页面上
 * 唯一能写的入口是「改目录名」，改完库自己跟上。
 *
 * 同步按钮旁先报归档根在不在：外挂盘没挂上时跑同步会把该分区下的目录全判成失踪，
 * 拦在动手之前比事后补救省事。
 */
const MangaArchivePage = (() => {

    const filter = {status: '', score: '', keyword: '', tag: '', conflictOnly: false};
    let tags = [];
    let roots = [];
    /** id → UnitView，编辑表单要拿整条数据，从表格 dataset 里现搓不出来 */
    let unitById = {};
    /** 展开互斥：一次只展开一个目录的漫画，存当前展开的目录 id 与已加载列表 */
    let expandedUnitId = null;
    let expandedMangas = [];
    /** 打包任务进行中的漫画路径：卡片锁定、禁止操作，任务结束清掉 */
    let packing = new Set();

    async function render(host, params) {
        // 从标签页点「归档作者」跳转过来时带 tag：用它做标签筛选，只留挂了该标签的归档目录
        if (params && params.tag) {
            filter.tag = params.tag;
        }
        host.innerHTML = UI.spinner();
        try {
            roots = await Api.get('/api/manga/archive/roots');
            tags = await Api.get('/api/manga/tags');
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }

        host.innerHTML = renderSyncCard(roots)
            + '<div id="sync-result"></div>'
            + renderFilterCard(roots)
            + '<div id="unit-list">' + UI.spinner() + '</div>';
        bindSync(host);
        bindFilter(host);
        await renderUnits(host);
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    function renderSyncCard(roots) {
        const missing = roots.filter(r => !r.exists);
        let html = '<div class="card"><div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">从磁盘同步</h2>'
            + '<div class="row">'
            + '<button class="btn-primary" id="sync-btn"' + (missing.length ? ' disabled' : '')
            + '>同步</button>'
            + '<button class="btn-primary" id="scan-mangas-btn"'
            + (missing.length ? ' disabled' : '') + '>扫描归档漫画</button>'
            + '</div></div>'
            + '<p class="muted small">「同步」扫归档根下四个评分分区里的一级目录，解析 '
            + '<span class="mono">[社团 (作者甲、作者乙)]【标签1 标签2】</span> 后入库。'
            + '幂等，目录没变时重跑只产生「更新」。</p>'
            + '<p class="muted small">「扫描归档漫画」递归扫各归档目录下的漫画，补全 '
            + '<span class="mono">manga_data</span> 的 ARCHIVED 层：每本重算封面后缀，'
            + '新行记目录所在分区评分，已有的单独评分不动。扫完下面列表的「本数」与「平均分」才变真。</p>';
        html += '<table><tbody>';
        for (const r of roots) {
            html += '<tr><td style="width:10%">' + r.score + ' 分</td>'
                // 分区不在磁盘上时后端给不出它的路径（分区目录名要从磁盘上扫才知道），
                // 所以这里如实空着，不拿归档根顶上
                + '<td class="mono small">' + (r.exists
                    ? UI.esc(r.rootPath) : '<span class="muted">—</span>') + '</td>'
                + '<td style="width:12%">' + (r.exists
                    ? '<span class="tag tag-ok">在</span>'
                    : '<span class="tag tag-err">不在</span>') + '</td></tr>';
        }
        html += '</tbody></table>';
        if (missing.length) {
            // 分区缺失时同步会把该分区下所有目录判成 MISSING，所以直接禁用而非事后解释
            html += '<div class="hint hint-err">有 ' + missing.length
                + ' 个评分分区不在磁盘上（外挂盘没挂上？分区目录被改名了？）。'
                + '这时候同步会把该分区下的归档目录全判成失踪，所以按钮先禁用了。'
                + '归档根在系统配置页（左下角「系统工具」里的齿轮）改。</div>';
        }
        return html + '</div>';
    }

    function bindSync(host) {
        const btn = host.querySelector('#sync-btn');
        if (!btn.disabled) {
            btn.onclick = async (ev) => {
                await UI.withBusy(ev.target, '提交中…', async () => {
                    try {
                        const taskId = await Api.post('/api/manga/archive/sync');
                        const resultBox = host.querySelector('#sync-result');
                        resultBox.innerHTML = UI.spinner('同步中…');
                        TaskPoll.wait(taskId, {
                            onResult: async (result) => {
                                resultBox.innerHTML = renderSyncResult(result);
                                UI.ok('同步完成');
                                await renderUnits(host);
                                return true;
                            },
                            onFail: (error) => {
                                resultBox.innerHTML = '<div class="hint hint-err">'
                                    + UI.esc(error) + '</div>';
                                UI.err(error);
                            }
                        });
                    } catch (e) {
                        host.querySelector('#sync-result').innerHTML =
                            '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
                        UI.err(e.message);
                    }
                });
            };
        }
        // 扫描归档漫画：归档根缺失时与「同步」一并禁用（都会把缺失分区判成失踪）
        const scanBtn = host.querySelector('#scan-mangas-btn');
        if (!scanBtn.disabled) {
            scanBtn.onclick = async (ev) => {
                await UI.withBusy(ev.target, '提交中…', async () => {
                    try {
                        const taskId = await Api.post('/api/manga/archive/scan-mangas');
                        TaskPoll.wait(taskId, {
                            onResult: async (result) => {
                                UI.ok('扫描完成：扫描 ' + result.scanned + ' 本，新增 '
                                    + result.inserted + '，更新 ' + result.updated + '，失踪 '
                                    + result.missing);
                                await renderUnits(host);
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
    }

    function renderSyncResult(r) {
        let html = '<div class="card"><h2>同步结果</h2>'
            + '<p class="row"><span class="tag">新增 ' + r.inserted + '</span>'
            + '<span class="tag">更新 ' + r.updated + '</span>'
            + '<span class="tag' + (r.renamed ? ' tag-warn' : '') + '">改名 ' + r.renamed + '</span>'
            + '<span class="tag' + (r.merged ? ' tag-warn' : '') + '">合并 ' + r.merged + '</span>'
            + '<span class="tag' + (r.missing ? ' tag-err' : '') + '">失踪 ' + r.missing + '</span></p>';

        if (r.migrations.length) {
            // 合并会删行，且判定有推测成分（别名子集 + 旧路径已不在磁盘上），必须逐条核对
            html += '<div class="hint"><strong>目录迁移 ' + r.migrations.length
                + ' 条，请逐条核对</strong><br>'
                + '「合并」会把被并目录的行连同标签关联一起删掉，判错的话标签就挂到别的目录上了。</div>'
                + '<table><thead><tr><th style="width:10%">类型</th><th>原路径</th><th>现路径</th></tr></thead><tbody>';
            for (const m of r.migrations) {
                html += '<tr><td>' + (m.merged
                    ? '<span class="tag tag-warn">合并</span>'
                    : '<span class="tag">改名</span>') + '</td>'
                    + '<td class="mono small">' + UI.esc(m.fromFolderPath) + '</td>'
                    + '<td class="mono small">' + UI.esc(m.toFolderPath) + '</td></tr>';
            }
            html += '</tbody></table>';
        }

        if (r.unparsedFolders.length) {
            html += '<h2 style="margin-top:16px">不规范目录（未入库）' + r.unparsedFolders.length + '</h2>'
                + '<p class="muted small">目录名不是 <span class="mono">[社团 (作者)]</span> 形态，'
                + '这些目录里的漫画不会被归档匹配到。手工改名后重新同步。</p>'
                + '<div class="small mono">'
                + r.unparsedFolders.map(UI.esc).join('<br>') + '</div>';
        }

        if (r.conflicts.length) {
            html += '<h2 style="margin-top:16px">重名的群组或作者 ' + r.conflicts.length + '</h2>'
                + renderConflicts(r.conflicts);
        }
        return html + '</div>';
    }

    function renderConflicts(conflicts) {
        let html = '<table><thead><tr><th style="width:20%">名字</th><th>落在这些目录上</th></tr></thead><tbody>';
        for (const c of conflicts) {
            html += '<tr><td class="mono">' + UI.esc(c.name) + '</td><td class="small mono">'
                + c.units.map(u => UI.esc(u.folderPath)).join('<br>') + '</td></tr>';
        }
        return html + '</tbody></table>'
            + '<p class="muted small">同一个名字落在多个归档目录上，归档时系统会按归档根优先级取其一。'
            + '到「合并冲突」页并排比对、把两边并到一处。</p>';
    }

    // ------------------------------------------------------------------
    // 列表与筛选
    // ------------------------------------------------------------------

    function renderFilterCard(roots) {
        return '<div class="card"><h2>筛选</h2><div class="row">'
            + '<input type="text" id="f-keyword" value="' + UI.esc(filter.keyword)
            + '" placeholder="按社团名/作者名/目录名搜">'
            + '<select id="f-score"><option value="">全部评分</option>'
            + roots.map(r => '<option value="' + r.score + '"'
                + (String(filter.score) === String(r.score) ? ' selected' : '') + '>'
                + r.score + ' 分</option>').join('')
            + '</select>'
            + '<select id="f-status"><option value="">全部状态</option>'
            + '<option value="ACTIVE"' + (filter.status === 'ACTIVE' ? ' selected' : '') + '>正常</option>'
            + '<option value="MISSING"' + (filter.status === 'MISSING' ? ' selected' : '') + '>失踪</option>'
            + '</select>'
            + '<select id="f-tag"><option value="">全部标签</option>'
            + tags.map(t => '<option value="' + UI.esc(t.tag.tagName) + '"'
                + (filter.tag === t.tag.tagName ? ' selected' : '') + '>'
                + UI.esc(t.tag.tagName) + ' (' + t.archiveUnitCount + ')</option>').join('')
            + '</select>'
            + '<label class="small muted"><input type="checkbox" id="f-conflict"'
            + (filter.conflictOnly ? ' checked' : '') + '> 只看重名的</label>'
            + '<button id="f-btn">查询</button>'
            + '</div>'
            + '<p class="muted small">搜索会把输入按 NFC + 大写归一后再比库里的别名，'
            + '所以日文假名的分解形式也能搜到。</p></div>';
    }

    function bindFilter(host) {
        const apply = () => {
            filter.keyword = host.querySelector('#f-keyword').value.trim();
            filter.score = host.querySelector('#f-score').value;
            filter.status = host.querySelector('#f-status').value;
            filter.tag = host.querySelector('#f-tag').value;
            filter.conflictOnly = host.querySelector('#f-conflict').checked;
            renderUnits(host);
        };
        host.querySelector('#f-btn').onclick = apply;
        host.querySelector('#f-keyword').onkeydown = (ev) => {
            if (ev.key === 'Enter') {
                apply();
            }
        };
        ['#f-score', '#f-status', '#f-tag', '#f-conflict'].forEach(sel =>
            host.querySelector(sel).onchange = apply);
    }

    async function renderUnits(host) {
        const box = host.querySelector('#unit-list');
        box.innerHTML = UI.spinner();
        let units;
        try {
            const q = new URLSearchParams();
            if (filter.status) {
                q.set('status', filter.status);
            }
            if (filter.score) {
                q.set('score', filter.score);
            }
            if (filter.keyword) {
                q.set('keyword', filter.keyword);
            }
            if (filter.tag) {
                q.set('tag', filter.tag);
            }
            if (filter.conflictOnly) {
                q.set('conflictOnly', 'true');
            }
            units = await Api.get('/api/manga/archive/units?' + q.toString());
        } catch (e) {
            box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }

        unitById = {};
        expandedUnitId = null;
        expandedMangas = [];
        units.forEach(v => unitById[v.unit.id] = v);

        if (!units.length) {
            box.innerHTML = '<div class="card">' + UI.empty(
                '没有符合条件的归档目录。库是空的话先点上面的「同步」。') + '</div>';
            return;
        }

        // 按评分分区分组，与磁盘上的目录结构对齐
        const groups = new Map();
        for (const v of units) {
            const key = v.unit.rootPath;
            if (!groups.has(key)) {
                groups.set(key, []);
            }
            groups.get(key).push(v);
        }

        let html = '<div class="hint">社团名、作者名、标签都来自目录名，这里只读。'
            + '要改请点「改目录名」—— 文件系统是权威，库只是镜像。</div>';
        for (const [root, list] of groups) {
            html += '<div class="card"><h2>' + UI.esc(root)
                + ' <span class="muted small">' + list.length + ' 个</span></h2>'
                + '<table><thead><tr>'
                + '<th style="width:30%">目录名</th><th style="width:20%">标签</th>'
                + '<th style="width:16%" title="该目录下每本漫画自己的标签，按出现本数取前 3">高频标签</th>'
                + '<th style="width:7%">本数</th><th style="width:9%">平均分</th>'
                + '<th style="width:12%">状态</th><th></th>'
                + '</tr></thead><tbody>';
            for (const v of list) {
                html += renderUnitRow(v);
            }
            html += '</tbody></table></div>';
        }
        box.innerHTML = html;
        bindUnits(host, box);
    }

    function renderUnitRow(v) {
        const u = v.unit;
        const tagCells = v.tags.length
            ? v.tags.map(t => '<span class="tag">' + UI.esc(t) + '</span>').join(' ')
            : '<span class="muted">—</span>';
        // 高频标签 = 该目录下每本漫画自己的标签按出现本数取前 3（后端算好，前端只画）
        const topTagCells = v.topTags.length
            ? v.topTags.map(t => '<span class="tag">' + UI.esc(t.tagName)
                + '<span class="muted small">×' + t.count + '</span></span>').join(' ')
            : '<span class="muted">—</span>';
        // 手工关联的作者目录名里没有，标出来 —— 否则「为什么目录名里没有他却归到这」无从解释
        const nameCells = v.names.map(n => UI.esc(n.rawName)
            + (n.nameType === 'GROUP' ? '<span class="muted small">·社团</span>' : '')
            + (n.source === 'MANUAL' ? '<span class="tag tag-warn small">额外</span>' : '')).join('、');
        // status 是上次同步的结论，folderExists 是此刻的事实，两者不一致就该重新同步
        let statusCell;
        if (u.status === 'MISSING') {
            statusCell = '<span class="tag tag-err">失踪</span>';
        } else if (!v.folderExists) {
            statusCell = '<span class="tag tag-warn">目录已不在</span>';
        } else {
            statusCell = '<span class="tag tag-ok">正常</span>';
        }
        return '<tr data-id="' + u.id + '">'
            + '<td><span class="mono">' + UI.esc(u.folderName) + '</span>'
            + (v.conflicted ? ' <span class="tag tag-warn">重名</span>' : '')
            + '<div class="muted small">' + nameCells + '</div></td>'
            + '<td>' + tagCells + '</td>'
            + '<td>' + topTagCells + '</td>'
            // 本数可点：点开在下方展开该目录下的漫画（惰性加载）
            + '<td><button class="btn-plain act-mangas" title="点开看这本目录下的漫画">'
                + v.mangaCount + ' ▸</button></td>'
            // 平均分 = 各本 SELF 分与目录分的均值；没扫过漫画时是 0，显示 —（扫完才真）
            + '<td>' + (v.averageScore > 0 ? v.averageScore.toFixed(1) + ' 分'
                : '<span class="muted">—</span>') + '</td>'
            + '<td>' + statusCell + '</td>'
            + '<td class="row">'
            + '<button class="act-edit">修改</button>'
            + (u.status === 'MISSING' || !v.folderExists
                ? '<button class="btn-danger act-forget">确认已删除</button>' : '')
            + '</td></tr>'
            + '<tr class="unit-mangas-tr" data-id="' + u.id + '" hidden>'
            + '<td colspan="7"><div class="unit-mangas"></div></td>'
            + '</tr>';
    }

    function bindUnits(host, box) {
        box.querySelectorAll('.act-edit').forEach(btn => btn.onclick = () => {
            const tr = btn.closest('tr');
            openEditForm(host, unitById[tr.dataset.id]);
        });

        box.querySelectorAll('.act-mangas').forEach(btn => btn.onclick = () => {
            const tr = btn.closest('tr');
            toggleUnitMangas(host, unitById[tr.dataset.id].unit.id);
        });

        box.querySelectorAll('.act-forget').forEach(btn => btn.onclick = async (ev) => {
            const tr = btn.closest('tr');
            if (!await UI.confirm('确认这个归档目录已经删除？\n\n'
                + unitById[tr.dataset.id].unit.folderName
                + '\n\n库里的行、别名与标签关联会一并清掉。'
                + '如果只是外挂盘没挂上，请选取消 —— 保留着标签关联，等盘挂回来再同步。',
                {title: '确认已删除', okText: '确认删除'})) {
                return;
            }
            await UI.withBusy(ev.target, '清理中…', async () => {
                try {
                    await Api.del('/api/manga/archive/units/' + tr.dataset.id);
                    UI.ok('已从库中清除');
                    await renderUnits(host);
                } catch (e) {
                    UI.err(e.message);
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // 目录漫画：点「本数」在下方展开（惰性加载），每本卡片 + 状态标签 + 未识别列表
    // ------------------------------------------------------------------

    /**
     * 展开互斥：一次只展开一个目录。点「本数」先收起别的、再展开自己，
     * 内容在展开后才拉（与新漫画-作者合集的展开一致）。列表存在模块级
     * `expandedMangas` 里，阅读页的「上一本/下一本」导航就指着它。
     */
    function toggleUnitMangas(host, unitId) {
        const list = host.querySelector('#unit-list');
        const wasOpen = expandedUnitId === unitId;
        // 先全收：隐藏所有展开行、把「本数」按钮文字拨回收起态
        list.querySelectorAll('tr.unit-mangas-tr').forEach(r => { r.hidden = true; });
        list.querySelectorAll('.act-mangas').forEach(b => {
            const v = unitById[b.closest('tr').dataset.id];
            b.textContent = (v ? v.mangaCount : 0) + ' ▸';
        });
        if (wasOpen) {
            expandedUnitId = null;
            expandedMangas = [];
            return;
        }
        expandedUnitId = unitId;
        expandedMangas = [];
        const row = list.querySelector('tr.unit-mangas-tr[data-id="' + unitId + '"]');
        row.hidden = false;
        const btn = list.querySelector('tr[data-id="' + unitId + '"] .act-mangas');
        if (btn) {
            btn.textContent = (unitById[unitId] ? unitById[unitId].mangaCount : 0) + ' ▾';
        }
        loadUnitMangas(host, unitId, row.querySelector('.unit-mangas'));
    }

    /** 惰性拉取该目录的漫画。展开已切到别的目录则丢弃过期结果，别把列表串了 */
    async function loadUnitMangas(host, unitId, box) {
        box.innerHTML = UI.spinner();
        let list;
        try {
            list = await Api.get('/api/manga/archive/units/' + unitId + '/mangas');
        } catch (e) {
            if (expandedUnitId === unitId) {
                box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            }
            return;
        }
        if (expandedUnitId !== unitId) {
            return;
        }
        // 独立标签（MANGA_DATA）批量取，注入 ownTags（编辑弹窗精确编辑用）。
        // 回显标签 m.tags 由后端注入（独立优先、父级兜底），卡片/阅读页直接读它
        let tagsByManga = {};
        const ids = list.map(m => m.mangaId).filter(id => id != null);
        if (ids.length) {
            try {
                tagsByManga = await Api.post('/api/manga/tags/manga/batch', {ids: ids}) || {};
            } catch (e) {
                tagsByManga = {};
            }
        }
        list = list.map(m => Object.assign({}, m, {
            ownTags: tagsByManga[String(m.mangaId)] || []
        }));
        expandedMangas = list;
        renderUnitMangas(host, box, list, unitId);
    }


    /** 目录漫画才有「打包 cbz」按钮；已经是 cbz 的不显示 */
    function actsForArchived(m) {
        const a = m.mangaId != null ? ['read', 'edit', 'delete'] : ['read', 'delete'];
        if (!Util.isCbzName(m.folderName)) {
            a.push('pack');
        }
        return a;
    }

    function renderUnitMangas(host, box, list, unitId) {
        if (!list.length) {
            box.innerHTML = '<div class="hint">这个目录下没扫到漫画。'
                + '先在页面顶部跑「扫描归档漫画」。</div>';
            return;
        }

        // 汇总标签：可存储 / 未评分 / 不规范 / 未规范化 / 未识别展会 / 未识别原作
        const stat = {
            storable: list.filter(x => x.storable).length,
            noScore: list.filter(x => x.score == null).length,
            irregular: list.filter(x => x.matchedRule === 0).length,
            needRename: list.filter(x => x.needsRename).length,
            extraExhibit: list.filter(x => x.extraExhibit).length,
            extraParody: list.filter(x => x.extraParody).length,
        };
        const chips = [];
        if (stat.storable) chips.push('<span class="tag tag-ok">可存储 ' + stat.storable + '</span>');
        if (stat.noScore) chips.push('<span class="tag tag-warn">未评分 ' + stat.noScore + '</span>');
        if (stat.irregular) chips.push('<span class="tag tag-warn">不规范 ' + stat.irregular + '</span>');
        if (stat.needRename) chips.push('<span class="tag tag-warn">未规范化 ' + stat.needRename + '</span>');
        if (stat.extraExhibit) chips.push('<span class="tag tag-err">未识别展会 ' + stat.extraExhibit + '</span>');
        if (stat.extraParody) chips.push('<span class="tag tag-err">未识别原作 ' + stat.extraParody + '</span>');

        // 未识别展会/原作：按值聚合目录后画成可补录的图标按钮（与新漫画页同款）
        const extraExhibits = {};
        const extraParodies = {};
        for (const x of list) {
            if (x.extraExhibit) (extraExhibits[x.extraExhibit] ||= []).push(x.folderPath);
            if (x.extraParody) (extraParodies[x.extraParody] ||= []).push(x.folderPath);
        }
        const icon = (rows, cls) => rows.map(([value, folders]) =>
            '<button class="dict-icon dict-icon-' + cls + '" data-value="' + UI.esc(value) + '"'
            + ' title="出现在 ' + folders.length + ' 个目录：'
            + UI.esc(folders.slice(0, 5).map(f => f.split('\\').pop()).join('、'))
            + (folders.length > 5 ? '…' : '') + '">' + UI.esc(value) + '</button>').join('');
        const extraBlock = (Object.keys(extraExhibits).length || Object.keys(extraParodies).length)
            ? '<div class="card"><h2>未识别的展会与原作</h2>'
                + '<p class="muted small">带着它们的漫画不能归档。点按钮补录（保存即生效），'
                + '补完回来点页面顶部的「扫描归档漫画」同步。</p><div class="row">'
                + icon(Object.entries(extraExhibits), 'exhibit')
                + icon(Object.entries(extraParodies), 'parody')
                + '</div></div>'
            : '';

        const packable = list.filter(m => !Util.isCbzName(m.folderName));
        const packAllBtn = packable.length
            ? '<button id="pack-unit-all" class="btn-primary">打包本目录全部 cbz（' + packable.length + '）</button>'
            : '';
        box.innerHTML = '<div class="row" style="gap:8px;flex-wrap:wrap;margin-bottom:10px;align-items:center">'
            + (chips.length ? chips.join('') : '<span class="muted small">没有待处理的项</span>')
            + packAllBtn
            + '</div>'
            + extraBlock
            + '<div class="card"><div class="manga-grid">'
            + list.map(m => MangaCard.render(m, {
                acts: actsForArchived(m),
                busy: packing.has(m.folderPath) ? '打包中…' : null,
                showArchive: false
            }))
                .join('')
            + '</div></div>';

        // 阅读：完整阅读页（改名/评分/删除），与未归档页一致；归档漫画没有「归档」动作
        MangaCard.bind(box, {
            read: (path) => {
                const manga = list.find(m => m.folderPath === path);
                if (manga) {
                    openArchivedReader(host, manga, unitId);
                }
            },
            edit: (path) => {
                const manga = list.find(m => m.folderPath === path);
                if (manga && manga.mangaId != null) {
                    openArchivedEditModal(host, manga, unitId);
                }
            },
            pack: (path, btn) => {
                packArchivedManga(host, unitId, box, path, btn);
            },
            remove: async (path, btn) => {
                if (!await UI.confirm('删除这本及其目录？\n\n' + path
                    + '\n\n目录会送进 Windows 回收站，删错了可以从那里还原。')) {
                    return;
                }
                await UI.withBusy(btn, '…', async () => {
                    try {
                        await Api.del('/api/manga/archive/mangas?folderPath='
                            + encodeURIComponent(path));
                        UI.ok('已删除');
                        await loadUnitMangas(host, unitId, box);
                    } catch (e) {
                        UI.err(e.message);
                    }
                });
            }
        });

        const packAll = box.querySelector('#pack-unit-all');
        if (packAll) {
            packAll.onclick = (ev) => batchPackUnit(host, unitId, box, list, ev.target);
        }

        // 补录弹窗存完，展开区里的判定标签要跟着变，重拉一次
        box.querySelectorAll('.dict-icon').forEach(btn => btn.onclick = () => {
            const value = btn.dataset.value;
            const type = btn.classList.contains('dict-icon-exhibit') ? 'EXHIBIT' : 'PARODY';
            const folders = (type === 'EXHIBIT' ? extraExhibits : extraParodies)[value] || [];
            MangaReader.openDictDialog({
                type, value, folders,
                onSaved: async () => {
                    await loadUnitMangas(host, unitId, box);
                }
            });
        });
    }

    /** 单本打包：原地生成同名 cbz 并删源目录（异步任务）。完成后重拉当前展开目录 */
    async function packArchivedManga(host, unitId, box, path, btn) {
        if (!await UI.confirm('把这本打包成 cbz 并删除原目录？\n\n' + path
            + '\n\n原目录会送进 Windows 回收站，可以从那里还原。')) {
            return;
        }
        await UI.withBusy(btn, '…', async () => {
            try {
                const taskId = await Api.post('/api/manga/pack-cbz', {folderPaths: [path]});
                packing.add(path);
                await loadUnitMangas(host, unitId, box);
                TaskPoll.wait(taskId, {
                    onResult: async (r) => {
                        packing.delete(path);
                        if (r.failed) {
                            UI.err('打包完成 ' + r.packed + ' 本，失败 ' + r.failed + ' 本');
                        } else {
                            UI.ok('已打包');
                        }
                        await loadUnitMangas(host, unitId, box);
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

    /** 批量打包：本目录全部目录漫画（不含 cbz），一条批任务 */
    async function batchPackUnit(host, unitId, box, list, btn) {
        const paths = list.filter(m => !Util.isCbzName(m.folderName)).map(m => m.folderPath);
        if (!paths.length) {
            UI.err('本目录没有可打包的漫画目录');
            return;
        }
        if (!await UI.confirm('把本目录 ' + paths.length + ' 本目录漫画打包成 cbz 并删除原目录？'
            + '\n\n原目录会送进 Windows 回收站，可以从那里还原。',
            {title: '批量打包', okText: '打包'})) {
            return;
        }
        await UI.withBusy(btn, '提交中…', async () => {
            try {
                const taskId = await Api.post('/api/manga/pack-cbz', {folderPaths: paths});
                paths.forEach(p => packing.add(p));
                await loadUnitMangas(host, unitId, box);
                TaskPoll.wait(taskId, {
                    onResult: async (r) => {
                        paths.forEach(p => packing.delete(p));
                        if (r.failed) {
                            UI.err('打包 ' + r.packed + ' 本，失败 ' + r.failed + ' 本');
                        } else {
                            UI.ok('已打包 ' + r.packed + ' 本');
                        }
                        await loadUnitMangas(host, unitId, box);
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


    /** 已归档的「修改」弹窗：改名 + 评分（落库不动目录）+ 独立标签。没有归档动作。
     *  标签预填两步：先独立标签（mangaId），没有则父级标签（unitId，作者相关标签）。 */
    async function openArchivedEditModal(host, manga, unitId, onDone) {
        // 独立标签已由 loadUnitMangas 批量注入到 ownTags；没有独立标签再取父级标签预填
        let withTags = manga;
        if (!withTags.ownTags || !withTags.ownTags.length) {
            try {
                const parentTags = await Api.get('/api/manga/archive/units/' + unitId + '/tags') || [];
                withTags = Object.assign({}, withTags, {parentTags: parentTags});
            } catch (e) {
                // 读不到父级标签就降级，弹窗标签栏留空
            }
        }
        const opts = MangaReader.archivedOpts();
        opts.reload = () => reloadExpanded(host, unitId);
        opts.onDone = onDone;
        MangaReader.openArchiveModal(host, withTags, opts);
    }

    /** 归档漫画的完整阅读页：与未归档页一致（改名/评分/删除），只少了「归档」按钮 */
    async function openArchivedReader(host, manga, unitId) {
        // 阅读页底栏读后端注入的 manga.tags 回显；编辑弹窗自己从 ownTags/父级标签取
        const opts = MangaReader.archivedOpts();
        opts.list = () => expandedMangas;
        opts.rescan = () => reloadExpanded(host, unitId);
        opts.edit = (m, onDone) => openArchivedEditModal(host, m, unitId, onDone);
        MangaReader.openReader(host, manga, opts);
    }

    /** 阅读页里的改名/评分/删除落盘后，重拉当前展开的目录列表 */
    async function reloadExpanded(host, unitId) {
        const box = host.querySelector('tr.unit-mangas-tr[data-id="' + unitId + '"] .unit-mangas');
        if (box) {
            await loadUnitMangas(host, unitId, box);
        }
    }

    // ------------------------------------------------------------------
    // 编辑表单：目录名 / 关联作者社团 / 标签 / 评分分区，一次提交
    // ------------------------------------------------------------------

    /**
     * 表单里的待提交状态。改动在提交前只活在这里，磁盘与库都不动 ——
     * 四类改动互相有牵连（删作者可能改目录名、改目录名要重判标签），
     * 分头即时落盘会出现中间态。
     */
    let form = null;

    function openEditForm(host, view) {
        form = {
            unitId: view.unit.id,
            folderName: view.unit.folderName,
            folderNameEdited: false,
            // 深拷贝：直接改 view 里的数组会让取消后的列表显示已改过的值
            names: view.names.map(n => ({...n})),
            tags: view.tags.slice(),
            rootPath: view.unit.rootPath,
            original: view
        };
        // 修改改弹窗（原先是内联到列表上方的表单），表单内容不变，只是容器换成 modal-body
        const m = UI.modal('<div class="modal modal-wide">'
            + '<div class="modal-head"><span>修改归档目录：' + UI.esc(view.unit.folderName) + '</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body" id="edit-form-body"></div>'
            + '</div>');
        const closeForm = () => {
            form = null;
            m.close();
        };
        m.box.querySelector('.modal-close').onclick = closeForm;
        drawEditForm(host, m.box.querySelector('#edit-form-body'), closeForm);
    }

    function drawEditForm(host, scope, closeForm) {
        scope.innerHTML = '<h2>修改归档目录</h2>'
            + '<p class="muted small">以下改动都只在表单里生效，点「提交」才会真的改磁盘目录与库。'
            + '目录名会按关联作者与标签重新拼出来，下面能看到最终结果。</p>'

            + '<h2 style="margin-top:14px">目录名</h2>'
            + '<p class="muted small">直接改这里适合调整社团/作者的写法。'
            + '改完会按新目录名重新判定标签；<strong>原有的关联作者不会解绑</strong>，'
            + '不在新目录名里的会转成「额外」关联。</p>'
            + '<input type="text" id="e-folder" class="mono" style="width:100%" value="'
            + UI.esc(form.folderName) + '">'

            + '<h2 style="margin-top:14px">关联的社团与作者</h2>'
            + '<p class="muted small">勾了「写进目录名」的会按 '
            + '<span class="mono">[社团 (作者)]</span> 拼进目录名，社团与作者都可以有多个'
            + '（用 <span class="mono">、</span> 连起来）。不勾的只存库、同步不会动它 ——'
            + '用于「这个作者的作品也放这个目录」。删到目录名里既没有社团也没有作者则无法提交。</p>'
            + '<div id="e-names"></div>'
            + '<div class="row" style="margin-top:8px">'
            + '<input type="text" id="e-new-name" placeholder="作者名或社团名">'
            + '<select id="e-new-type"><option value="ARTIST">作者</option>'
            + '<option value="GROUP">社团</option></select>'
            + '<button id="e-add-name">+ 加关联（不改目录名）</button>'
            + '</div>'

            + '<h2 style="margin-top:14px">标签</h2>'
            + '<p class="muted small">标签是目录名的一部分，增删改都会同步进 '
            + '<span class="mono">【…】</span> 块。按大类选，或手填一个库里没有的新标签。</p>'
            + '<div id="e-tags"></div>'

            + '<h2 style="margin-top:14px">评分分区</h2>'
            + '<p class="muted small">改这里会把整个目录移动到对应的评分分区下。</p>'
            // 不存在的分区只禁选、不剔除：本目录当前所在的分区可能正是不在磁盘上的那一个
            //（外挂盘没挂上）。把它从选项里拿掉，浏览器会挑中第一个可选项 ——
            // 用户只是改个标签，提交时却把目录「移」到了另一个评分区。留在列表里禁掉，
            // 提交时后端会照实拒（分区不在磁盘上，本来也不该改名）
            + '<select id="e-root">'
            + roots.map(r => '<option value="' + UI.esc(r.rootPath) + '"'
                + (r.rootPath === form.rootPath ? ' selected' : '')
                + (r.exists ? '' : ' disabled')
                + '>' + r.score + ' 分 — ' + UI.esc(r.rootPath)
                + (r.exists ? '' : '（不在磁盘上）') + '</option>').join('')
            + '</select>'

            + '<div id="e-preview" style="margin-top:14px">' + UI.spinner('计算中…') + '</div>'
            + '<div class="row" style="margin-top:12px">'
            + '<button class="btn-primary" id="e-submit">提交</button>'
            + '<button class="btn-plain" id="e-cancel">取消</button>'
            + '<span class="muted small">目录改名与移动不可回滚</span>'
            + '</div>';

        drawNameList(scope);
        createTagPicker(scope);
        bindEditForm(scope, closeForm, host);
        refreshPreview(scope);
    }

    function drawNameList(scope) {
        const box = scope.querySelector('#e-names');
        if (!form.names.length) {
            box.innerHTML = '<p class="hint hint-err">一个关联都不剩了，提交会被拒。</p>';
            return;
        }
        let html = '<table><thead><tr><th style="width:36%">名字</th>'
            + '<th style="width:14%">类型</th><th style="width:26%">写进目录名</th><th></th>'
            + '</tr></thead><tbody>';
        form.names.forEach((n, i) => {
            html += '<tr data-i="' + i + '">'
                + '<td><input type="text" class="n-name" value="' + UI.esc(n.rawName)
                + '" style="width:100%"></td>'
                + '<td><select class="n-type">'
                + '<option value="ARTIST"' + (n.nameType === 'ARTIST' ? ' selected' : '') + '>作者</option>'
                + '<option value="GROUP"' + (n.nameType === 'GROUP' ? ' selected' : '') + '>社团</option>'
                + '</select></td>'
                + '<td><label class="small muted"><input type="checkbox" class="n-src"'
                + (n.source === 'MANUAL' ? '' : ' checked') + '> '
                + (n.source === 'MANUAL' ? '额外关联，只存库' : '出现在目录名里') + '</label></td>'
                + '<td><button class="btn-plain n-del">移除</button></td></tr>';
        });
        box.innerHTML = html + '</tbody></table>';

        box.querySelectorAll('.n-src').forEach(cb => cb.onchange = () => {
            form.names[Number(cb.closest('tr').dataset.i)].source =
                cb.checked ? 'FOLDER' : 'MANUAL';
            drawNameList(scope);
            refreshPreview(scope);
        });

        box.querySelectorAll('.n-name').forEach(input => input.onchange = () => {
            form.names[Number(input.closest('tr').dataset.i)].rawName = input.value.trim();
            refreshPreview(scope);
        });
        box.querySelectorAll('.n-type').forEach(sel => sel.onchange = () => {
            form.names[Number(sel.closest('tr').dataset.i)].nameType = sel.value;
            refreshPreview(scope);
        });
        box.querySelectorAll('.n-del').forEach(btn => btn.onclick = () => {
            form.names.splice(Number(btn.closest('tr').dataset.i), 1);
            drawNameList(scope);
            refreshPreview(scope);
        });
    }

    function createTagPicker(scope) {
        const picker = MangaTagPicker.create(scope.querySelector('#e-tags'), {
            initTags: form.tags,
            onChange: () => {
                form.tags = picker.names();
                refreshPreview(scope);
            }
        });
    }

    function bindEditForm(box, closeForm, host) {
        box.querySelector('#e-folder').onchange = (ev) => {
            const value = ev.target.value.trim();
            // 改回原样就当没改过，好让「按关联作者重拼目录名」那条路重新生效
            form.folderNameEdited = value !== form.original.unit.folderName;
            form.folderName = value;
            refreshPreview(box);
        };
        box.querySelector('#e-root').onchange = (ev) => {
            form.rootPath = ev.target.value;
            refreshPreview(box);
        };
        box.querySelector('#e-add-name').onclick = () => {
            const input = box.querySelector('#e-new-name');
            const raw = input.value.trim();
            if (!raw) {
                UI.err('请填名字');
                return;
            }
            form.names.push({
                rawName: raw,
                nameType: box.querySelector('#e-new-type').value,
                source: 'MANUAL'
            });
            input.value = '';
            drawNameList(box);
            refreshPreview(box);
        };
        box.querySelector('#e-cancel').onclick = () => {
            form = null;
            closeForm();
        };
        box.querySelector('#e-submit').onclick = async (ev) => {
            await UI.withBusy(ev.target, '提交中…', async () => {
                try {
                    const result = await Api.put(
                        '/api/manga/archive/units/' + form.unitId, payload());
                    UI.ok(result.folderRenamed
                        ? (result.movedRoot ? '已移动并改名' : '已改目录名')
                        : '已保存');
                    form = null;
                    closeForm();
                    await renderUnits(host);
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };
    }

    /**
     * folderName 只在用户直接改过时才发：发了就以它为准，那样按关联作者重拼目录名
     * 这条路会被它盖掉（后端如此约定），改作者就不生效了。
     */
    function payload() {
        return {
            folderName: form.folderNameEdited ? form.folderName : null,
            names: form.names.map(n => ({
                rawName: n.rawName, nameType: n.nameType, source: n.source
            })),
            tags: form.tags,
            rootPath: form.rootPath
        };
    }

    /**
     * 预演：目录名怎么拼、会不会被拒，一律问后端。
     * 前端自己拼一遍就有两套规则，迟早不一致，而错的那次会改错目录。
     */
    async function refreshPreview(scope) {
        const box = scope.querySelector('#e-preview');
        if (!box) {
            return;
        }
        box.innerHTML = UI.spinner('计算中…');
        let plan;
        try {
            plan = await Api.post('/api/manga/archive/units/' + form.unitId + '/edit-plan', payload());
        } catch (e) {
            box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            scope.querySelector('#e-submit').disabled = true;
            return;
        }

        let html = '<table><tbody>'
            + '<tr><td style="width:16%">现目录名</td><td class="mono small">'
            + UI.esc(plan.fromFolderName) + '</td></tr>'
            + '<tr><td>提交后</td><td class="mono small">'
            + (plan.folderRenamed
                ? '<strong>' + UI.esc(plan.toFolderName) + '</strong>'
                : UI.esc(plan.toFolderName) + ' <span class="muted">（不变）</span>')
            + '</td></tr>';
        if (plan.movedRoot) {
            html += '<tr><td>移动到</td><td class="mono small">'
                + UI.esc(plan.toFolderPath) + '<br><span class="tag tag-warn">评分 '
                + plan.fromScore + ' → ' + plan.toScore + '</span></td></tr>';
        }
        if (plan.addedNames.length) {
            html += '<tr><td>新增关联</td><td class="small">'
                + plan.addedNames.map(n => UI.esc(n.rawName)
                    + (n.source === 'MANUAL' ? '<span class="muted">（额外，不进目录名）</span>' : ''))
                    .join('、') + '</td></tr>';
        }
        if (plan.removedNames.length) {
            html += '<tr><td>解除关联</td><td class="small">'
                + plan.removedNames.map(n => UI.esc(n.rawName)).join('、') + '</td></tr>';
        }
        html += '</tbody></table>';

        if (plan.blockedReason) {
            html += '<div class="hint hint-err">' + UI.esc(plan.blockedReason) + '</div>';
        }
        box.innerHTML = html;
        scope.querySelector('#e-submit').disabled = !!plan.blockedReason;
    }

    return {render};
})();
