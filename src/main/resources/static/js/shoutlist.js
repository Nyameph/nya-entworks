/**
 * 喊麦 · 已归档页。F:\歌曲\成品-喊麦 下已归档的喊麦。
 *
 * **单独一个模块而不是歌曲页加个页签**，因为喊麦的文件名没有规律 —— 不是
 * 「作者 - 曲名（原曲名）」那套，解析它没有意义。所以这一页（以及整个喊麦模块）：
 *
 *   - 只显示原文件名，没有作者/曲名/原曲名/版本号四列
 *   - 没有「规范化命名」，因为没有可规范化的形态
 *   - 也没有原曲 / 作者统计，没有「按原曲名设倍速」那一层回落
 *   - 列表**每次现扫磁盘**（同歌曲的 merged-groups、漫画的「新漫画不入库」）；
 *     已归档的组另有一份镜像（`shout_group` / `shout_file`，由同步固化），
 *     给默认倍速一个挂靠点、并提供 MISSING 判定 —— 但它不参与出这一页的列表
 *
 * 播放、修改、删除与歌曲页共用 SongPlayer 与 SongActions，行为一模一样 ——
 * 差别（接口前缀、改名改全名、没有原曲层倍速）全在
 * {@code SongActions.SHOUT_CFG} 那一份约定里，见 songaction.js 的注释。
 * **随机播放也照歌曲页那一套**（选来源分区 → 洗牌轮播）—— 2026-09-29 补的：
 * 原先只有歌曲页与喊麦的未归档页有，这一页漏了。
 */
const ShoutListPage = (() => {

    let rows = [];
    let partitions = [];
    let roots = null;
    let filter = {partition: '', keyword: ''};
    let page = 1;
    // 冗余文件折叠卡：默认收起，展开才拉数据
    let rdOpen = false;
    let rdPage = 1;

    const CFG = SongActions.SHOUT_CFG;

    async function render(host) {
        host.innerHTML = UI.spinner();
        try {
            roots = await Api.get('/api/shout/roots');
            partitions = await Api.get('/api/shout/partitions');
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        host.innerHTML = shell();
        bind(host);
        await load(host);
    }

    function shell() {
        let html = '';
        if (!roots.archivedRootExists) {
            html += '<div class="hint hint-err">根目录 <span class="mono">'
                + UI.esc(roots.archivedRoot) + '</span> 不存在，下面必然是空的 —— '
                + '这不代表没有喊麦，多半是外挂盘没挂上。</div>';
        }
        html += '<div class="hint">喊麦的文件名没有规律，所以这一页<strong>不解析文件名</strong>，'
            + '只按原文件名列；也<strong>不归并</strong>，一个文件组就是一行。'
            + '列表每次现扫磁盘（库里那份归档镜像只用来存默认倍速、判失踪）。</div>';
        html += '<div class="card">'
            + '<div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">喊麦 <span class="muted small" id="shout-count"></span></h2>'
            + '<span class="muted small mono">' + UI.esc(roots.archivedRoot) + '</span>'
            + '</div>'
            + '<div class="row">'
            // 筛选项只列磁盘上真有的分区：没建的那几档在 /partitions 里也下发（打分要能选），
            // 但它们永远是空的，列出来只是干扰
            + '<select id="f-partition"><option value="">全部分区</option>'
            + partitions.filter(p => p.onDisk).map(p => '<option value="' + UI.esc(p.dirName) + '">'
                + UI.esc(p.display) + '</option>').join('')
            + '</select>'
            + '<input type="text" id="f-keyword" placeholder="搜文件名">'
            + '<button id="f-clear" title="清空搜索条件">清空</button>'
            + '<button class="btn-primary" id="f-go">查询</button>'
            // 归档根不在时别让跑：那样会把该分区下的组全判成失踪（同归档页的立场）
            + '<button id="sync"' + (roots.archivedRootExists ? '' : ' disabled')
            + ' title="扫磁盘重建喊麦库镜像（默认倍速 / 标签 / 失踪标记读的是它）；'
            + '启动时已自动跑过一次，改完磁盘文件名或分区后点这个补跑">重新同步</button>'
            + '<button id="shuffle">随机播放</button>'
            + '</div>'
            + '<div id="shout-list">' + UI.spinner() + '</div>'
            + '<div class="pager" id="shout-pager"></div>'
            + '</div>'
            + redundantCard();
        return html;
    }

    async function load(host) {
        const list = host.querySelector('#shout-list');
        list.innerHTML = UI.spinner('扫磁盘…');
        try {
            rows = await Api.get('/api/shout/groups'
                + '?partition=' + encodeURIComponent(filter.partition)
                + '&keyword=' + encodeURIComponent(filter.keyword));
        } catch (e) {
            list.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        host.querySelector('#shout-count').textContent = rows.length + ' 条';
        page = 1;
        drawList(host);
    }

    function drawList(host) {
        const list = host.querySelector('#shout-list');
        const pager = host.querySelector('#shout-pager');
        if (!rows.length) {
            list.innerHTML = UI.empty(roots.archivedRootExists
                ? '没有符合条件的喊麦。' : '根目录不存在，扫不到东西。');
            pager.innerHTML = '';
            return;
        }
        const pageRows = SongActions.pageRows(rows, page);
        let html = '<table><thead><tr>'
            + '<th style="width:42%">文件名</th><th style="width:11%">分区</th>'
            + '<th style="width:12%">文件</th><th style="width:6%">倍速</th>'
            + '<th style="width:16%">标签</th><th></th>'
            + '</tr></thead><tbody>';
        for (const r of pageRows) {
            const v0 = r.variants[0];
            html += '<tr data-key="' + UI.esc(r.mergeKey) + '">'
                + '<td class="mono small">' + UI.esc(v0.mainName) + '</td>'
                + '<td class="small">' + UI.esc(v0.partitionDisplay) + '</td>'
                + '<td>' + SongActions.fileBadges(v0) + '</td>'
                + '<td class="mono small">' + (v0.defaultRate ? Number(v0.defaultRate) + '×'
                    : '<span class="muted">—</span>') + '</td>'
                + '<td>' + SongTag.chips(r.tags) + '</td>'
                + '<td class="row">'
                + '<button class="btn-primary act-play">播放</button>'
                + '<button class="act-edit">修改</button>'
                + '<button class="btn-danger act-delete">删除</button>'
                + '</td></tr>';
        }
        list.innerHTML = html + '</tbody></table>';
        bindRows(host);
        SongActions.pagerBar(pager, rows.length, page, (p) => {
            page = p;
            drawList(host);
        });
    }

    function bind(host) {
        const go = () => {
            filter.partition = host.querySelector('#f-partition').value;
            filter.keyword = host.querySelector('#f-keyword').value.trim();
            load(host);
        };
        host.querySelector('#f-go').onclick = go;
        host.querySelector('#f-clear').onclick = () => {
            filter.partition = '';
            filter.keyword = '';
            host.querySelector('#f-partition').value = '';
            host.querySelector('#f-keyword').value = '';
            load(host);
        };
        host.querySelector('#f-partition').onchange = go;
        host.querySelector('#f-keyword').onkeydown = (e) => {
            if (e.key === 'Enter') {
                go();
            }
        };
        host.querySelector('#f-partition').value = filter.partition;
        host.querySelector('#f-keyword').value = filter.keyword;
        const syncBtn = host.querySelector('#sync');
        if (!syncBtn.disabled) {
            // 喊麦这边尤其需要：文件名没有规律，改完名字不来点一下就只能在下次重启才进库
            syncBtn.onclick = (ev) =>
                SongActions.syncNow(ev.target, SongActions.SHOUT_CFG, () => load(host));
        }
        host.querySelector('#shuffle').onclick = () => shufflePlay(host);
        host.querySelector('#rd-toggle').onclick = () => toggleRedundant(host);
    }

    // ------------------------------------------------------------------
    // 冗余文件（从喊麦组剔除的已归档文件：磁盘在 成品-喊麦\冗余，库里 shout_id=0）
    // ------------------------------------------------------------------

    function redundantCard() {
        return '<div class="card" style="margin-top:12px">'
            + '<div class="row" style="justify-content:space-between">'
            + '<h3 style="margin:0">冗余文件 <span class="muted small" id="rd-count"></span></h3>'
            + '<button id="rd-toggle">展开</button>'
            + '</div>'
            + '<p class="muted small">从喊麦组里剔除的已归档文件落在这里：磁盘在 '
            + '<span class="mono">' + UI.esc(roots ? roots.archivedRoot : '') + '\\冗余</span>，'
            + '库里 shout_id=0，不再属于任何一组。处置（删除 / 搬回）请到资源管理器操作，'
            + '来龙去脉查左下角「文件记录」。</p>'
            + '<div id="rd-body" hidden></div>'
            + '</div>';
    }

    async function toggleRedundant(host) {
        rdOpen = !rdOpen;
        const body = host.querySelector('#rd-body');
        body.hidden = !rdOpen;
        host.querySelector('#rd-toggle').textContent = rdOpen ? '收起' : '展开';
        if (rdOpen) {
            rdPage = 1;
            await loadRedundant(host);
        }
    }

    async function loadRedundant(host) {
        const body = host.querySelector('#rd-body');
        body.innerHTML = UI.spinner();
        let data;
        try {
            data = await Api.get('/api/shout/redundant-files?page=' + rdPage + '&size=20');
        } catch (e) {
            body.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        host.querySelector('#rd-count').textContent = data.total + ' 个';
        if (!data.items.length) {
            body.innerHTML = UI.empty('没有冗余文件。从喊麦组里剔除的文件会出现在这里。');
            return;
        }
        let html = '<table><thead><tr><th>文件名</th><th style="width:120px">磁盘上</th>'
            + '</tr></thead><tbody>'
            + data.items.map(r => '<tr><td class="mono small">' + UI.esc(r.fileName) + '</td>'
                + '<td>' + (r.onDisk
                    ? '<span class="tag tag-ok">在</span>'
                    : '<span class="tag tag-err">已不在</span>') + '</td></tr>').join('')
            + '</tbody></table>'
            + '<div class="pager" id="rd-pager"></div>';
        body.innerHTML = html;
        UI.pagerBar(body.querySelector('#rd-pager'), data.total, data.page, data.size,
            (pg) => {
                rdPage = pg;
                loadRedundant(host);
            });
    }

    function bindRows(host) {
        const find = (btn) => rows.find(r => r.mergeKey === btn.closest('tr').dataset.key);
        const reload = () => load(host);
        host.querySelectorAll('.act-play').forEach(btn => btn.onclick = () =>
            SongPlayer.open(find(btn), {list: rows, cfg: CFG, onRateSaved: reload}));
        host.querySelectorAll('.act-edit').forEach(btn => btn.onclick = () =>
            SongActions.edit(find(btn), {partitions, cfg: CFG, onDone: reload}));
        host.querySelectorAll('.act-delete').forEach(btn => btn.onclick = () =>
            SongActions.remove(find(btn), {cfg: CFG, onDone: reload}));
    }

    // ------------------------------------------------------------------
    // 随机播放（与歌曲页同一套：选来源分区 → 洗牌成纯内存队列轮播）
    // ------------------------------------------------------------------

    /**
     * 随机播放：弹窗勾选来源分区，把当前筛选结果里命中分区的行洗牌后交给播放器轮播。
     * 纯前端，不落库 —— 切页/刷新即失。
     *
     * <p>与歌曲页同一个写法（`song.js` 的 `shufflePlay`）：已归档这一层有分区可分，
     * 所以要和歌曲一样先问「从哪几档里抽」；喊麦的**未归档页不分来源**（只有那一层），
     * 见 `shoutscore.js`。
     */
    function shufflePlay(host) {
        if (!rows.length) {
            UI.err('当前列表是空的，没有可随机播放的');
            return;
        }
        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>随机播放来源</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<p class="muted small">从勾选的分区里随机抽，作用于当前筛选结果。'
            + '生成的列表只在本次播放用，不存库。</p>'
            + '<div id="shuffle-parts">'
            // 同上：没建的那几档没歌可抽，不列
            + partitions.filter(p => p.onDisk).map(p => '<label class="shuffle-item"><input type="checkbox"'
                + ' data-dir="' + UI.esc(p.dirName) + '" checked> '
                + UI.esc(p.display) + '</label>').join('')
            + '</div>'
            + '<p class="muted small" id="shuffle-count"></p>'
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" id="shuffle-cancel">取消</button>'
            + '<button type="button" class="btn-primary" id="shuffle-go">随机播放</button>'
            + '</div></div>');
        m.box.querySelector('.modal-close').onclick = () => m.close();
        m.box.querySelector('#shuffle-cancel').onclick = () => m.close();

        const updateCount = () => {
            const dirs = new Set([...m.box.querySelectorAll('#shuffle-parts input:checked')]
                .map(i => i.dataset.dir));
            const n = rows.filter(r => dirs.has(r.variants[0].partitionName)).length;
            m.box.querySelector('#shuffle-count').textContent = '勾选的来源里共 ' + n + ' 条可播';
        };
        m.box.querySelectorAll('#shuffle-parts input').forEach(i => i.onchange = updateCount);
        updateCount();

        m.box.querySelector('#shuffle-go').onclick = () => {
            const dirs = new Set([...m.box.querySelectorAll('#shuffle-parts input:checked')]
                .map(i => i.dataset.dir));
            const pool = rows.filter(r => dirs.has(r.variants[0].partitionName));
            if (!pool.length) {
                UI.err('勾选的分区里没有可播的喊麦');
                return;
            }
            m.close();
            // Fisher–Yates 洗牌
            const list = [];
            const src = pool.slice();
            while (src.length) {
                const j = Math.floor(Math.random() * src.length);
                list.push(src.splice(j, 1)[0]);
            }
            SongPlayer.open(list[0], {list, cfg: CFG, onRateSaved: () => load(host)});
        };
    }

    return {render};
})();
