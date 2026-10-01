/**
 * 歌曲页（文档 8.2）。F:\歌曲\成品-歌曲 下已归档的填词歌曲。
 *
 * 列表走 /merged-groups：**同作者+曲名+原曲名的不同版本/文件类型合并成一行**。
 * 比如 {@code kinray7 - 卡路里#1.MP4}、{@code kinray7 - 卡路里（卡路里）#2.MP4}、
 * {@code kinray7 - 卡路里.mp3} 是同一行，播放浮层里可以切换播哪一个。
 * 一行上的「修改」（评分 / 需加速 / 改名）与删除作用于主 variant
 * （{@code variants[0]}），其余版本在播放浮层里切换后再操作。
 *
 * 筛选（关键词 / 只看解析失败 / 只看需规范化）走后端。「解析失败」「需要规范化」这两个
 * 判断本来就在后端，搬到前端等于把文件名规则抄第二份。
 *
 * 随机播放列表**纯前端、不落库**：勾选来源分区，把当前筛选结果里命中分区的行洗牌，
 * 交给播放器轮播。切页/刷新即失，符合「仅本次播放」。
 */
const SongPage = (() => {

    let rows = [];
    let partitions = [];
    let roots = null;
    let filter = {partition: '', keyword: '', onlyFailed: false, onlyNormalizable: false};
    let page = 1;
    // 冗余文件折叠卡：默认收起，展开才拉数据
    let rdOpen = false;
    let rdPage = 1;

    async function render(host, params) {
        host.innerHTML = UI.spinner();
        try {
            roots = await Api.get('/api/song/roots');
            partitions = await Api.get('/api/song/partitions');
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        // 原曲页/作者页点击跳转：把该原曲名/作者当关键词预填
        if (params && params.originalTitle) {
            filter.keyword = params.originalTitle;
        } else if (params && params.artist) {
            filter.keyword = params.artist;
        }
        host.innerHTML = shell();
        bind(host);
        await load(host);
    }

    function shell() {
        let html = '';
        if (!roots.archivedRootExists) {
            // 根不在时列表是空的，与「确实没有歌」长得一样，所以要明说
            html += '<div class="hint hint-err">根目录 <span class="mono">'
                + UI.esc(roots.archivedRoot) + '</span> 不存在，下面必然是空的 —— '
                + '这不代表没有歌，多半是外挂盘没挂上。</div>';
        }
        html += '<div class="card">'
            + '<div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">歌曲 <span class="muted small" id="song-count"></span></h2>'
            + '<span class="muted small mono">' + UI.esc(roots.archivedRoot) + '</span>'
            + '</div>'
            + '<div class="row">'
            // 筛选项只列磁盘上真有的分区：没建的那几档在 /partitions 里也下发（打分要能选），
            // 但它们永远是空的，列出来只是干扰
            + '<select id="f-partition"><option value="">全部分区</option>'
            + partitions.filter(p => p.onDisk).map(p => '<option value="' + UI.esc(p.dirName) + '">'
                + UI.esc(p.display) + '</option>').join('')
            + '</select>'
            + '<input type="text" id="f-keyword" placeholder="搜作者 / 曲名 / 原曲名 / 文件名">'
            + '<button id="f-clear" title="清空搜索条件">清空</button>'
            + '<label class="small muted"><input type="checkbox" id="f-failed"> 只看解析失败</label>'
            + '<label class="small muted"><input type="checkbox" id="f-normalizable"> 只看需规范化</label>'
            + '<button class="btn-primary" id="f-go">查询</button>'
            + '<button id="batch-normalize">批量规范化命名</button>'
            // 归档根不在时别让跑：那样会把该分区下的歌全判成失踪（同归档页的立场）
            + '<button id="sync"' + (roots.archivedRootExists ? '' : ' disabled')
            + ' title="扫磁盘重建歌库镜像（标签 / 倍速 / 失踪标记读的是它）；启动时已自动跑过一次，'
            + '改完磁盘文件名或分区后点这个补跑">重新同步</button>'
            + '<button id="shuffle">随机播放</button>'
            + '</div>'
            + '<div id="song-list">' + UI.spinner() + '</div>'
            + '<div class="pager" id="song-pager"></div>'
            + '</div>'
            + redundantCard();
        return html;
    }

    async function load(host) {
        const list = host.querySelector('#song-list');
        list.innerHTML = UI.spinner('扫磁盘…');
        try {
            rows = await Api.get('/api/song/merged-groups'
                + '?partition=' + encodeURIComponent(filter.partition)
                + '&keyword=' + encodeURIComponent(filter.keyword)
                + '&onlyFailed=' + filter.onlyFailed
                + '&onlyNormalizable=' + filter.onlyNormalizable);
        } catch (e) {
            list.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }

        const needNormalize = rows.filter(r => r.needsNormalize).length;
        host.querySelector('#song-count').textContent = rows.length + ' 条'
            + (needNormalize ? '，其中 ' + needNormalize + ' 条待规范化' : '');
        host.querySelector('#batch-normalize').disabled = needNormalize === 0;

        page = 1;
        drawList(host);
    }

    function drawList(host) {
        const list = host.querySelector('#song-list');
        const pager = host.querySelector('#song-pager');
        if (!rows.length) {
            list.innerHTML = UI.empty(roots.archivedRootExists
                ? '没有符合条件的歌曲。'
                : '根目录不存在，扫不到东西。');
            pager.innerHTML = '';
            return;
        }
        const pageRows = SongActions.pageRows(rows, page);
        list.innerHTML = table(pageRows);
        bindRows(host);
        SongActions.pagerBar(pager, rows.length, page, (p) => {
            page = p;
            drawList(host);
        });
    }

    function table(pageRows) {
        let html = '<table><thead><tr>'
            + '<th style="width:16%">作者</th><th style="width:18%">曲名</th>'
            + '<th style="width:15%">原曲名</th><th style="width:11%">分区</th>'
            + '<th style="width:11%">文件</th><th style="width:6%">倍速</th>'
            + '<th style="width:14%">标签</th><th></th>'
            + '</tr></thead><tbody>';
        for (const r of pageRows) {
            html += row(r);
        }
        return html + '</tbody></table>';
    }

    /** 作者名列表 → 各自可点击的链接，跳作者统计页对应项 */
    function artistLinks(artists) {
        return (artists || []).map(a =>
            '<a href="#song-artist?' + Util.qs('artist', a) + '">' + UI.esc(a) + '</a>').join(' & ');
    }

    /**
     * 倍速列。组级倍速是「本身倍速」，直接显示；没组级但有原曲名级的是「继承倍速」，
     * 用「原曲」标签区别 —— 否则两种倍速长得一样，看不出这条倍速是哪一层定下来的。
     */
    function rateCell(v) {
        if (v.defaultRate) {
            return Number(v.defaultRate) + '×';
        }
        if (v.originalRate) {
            return '<span class="muted" title="这一组没存组级倍速，继承自原曲名默认倍速">'
                + Number(v.originalRate) + '×</span>'
                + '<span class="tag" title="继承自原曲名默认倍速">原曲</span>';
        }
        return '<span class="muted">—</span>';
    }

    function row(r) {
        const v0 = r.variants[0];
        // 解析失败时字段都没有，退回显示原文件名并把原因摆出来
        const nameCells = r.parsed
            ? '<td>' + artistLinks(r.artists) + '</td>'
            + '<td>' + UI.esc(r.title)
            + (r.needsNormalize
                ? '<span class="tag tag-warn" title="曲名缺原曲名括号，用列表顶部的「批量规范化命名」处理">'
                + '待补原曲名</span>' : '')
            + '</td>'
            + '<td><a href="#song-stat?' + Util.qs('originalTitle', r.originalTitle) + '">'
            + UI.esc(r.originalTitle) + '</a></td>'
            : '<td colspan="3"><span class="tag tag-err">解析失败</span> '
            + '<span class="mono small">' + UI.esc(v0.mainName) + '</span>'
            + '<div class="muted small">' + UI.esc(r.parseFailedReason || '') + '</div></td>';

        return '<tr data-key="' + UI.esc(r.mergeKey) + '">'
            + nameCells
            + '<td class="small">' + UI.esc(v0.partitionDisplay)
            + (r.variants.length > 1
                ? ' <span class="tag" title="' + UI.esc(r.variants.map(v => v.mainName).join('\n'))
                + '">×' + r.variants.length + ' 版本</span>' : '')
            + '</td>'
            + '<td>' + SongActions.fileBadges(v0) + '</td>'
            + '<td class="mono small">' + rateCell(v0) + '</td>'
            + '<td>' + SongTag.chips(r.tags) + '</td>'
            + '<td class="row">'
            + '<button class="btn-primary act-play">播放</button>'
            + '<button class="act-edit">修改</button>'
            + '<button class="btn-danger act-delete">删除</button>'
            + '</td></tr>';
    }

    function bind(host) {
        host.querySelector('#rd-toggle').onclick = () => toggleRedundant(host);
        const go = () => {
            filter.partition = host.querySelector('#f-partition').value;
            filter.keyword = host.querySelector('#f-keyword').value.trim();
            filter.onlyFailed = host.querySelector('#f-failed').checked;
            filter.onlyNormalizable = host.querySelector('#f-normalizable').checked;
            load(host);
        };
        host.querySelector('#f-go').onclick = go;
        host.querySelector('#f-keyword').onkeydown = (e) => {
            if (e.key === 'Enter') {
                go();
            }
        };
        host.querySelector('#f-clear').onclick = () => {
            filter = {partition: '', keyword: '', onlyFailed: false, onlyNormalizable: false};
            host.querySelector('#f-partition').value = '';
            host.querySelector('#f-keyword').value = '';
            host.querySelector('#f-failed').checked = false;
            host.querySelector('#f-normalizable').checked = false;
            load(host);
        };
        host.querySelector('#f-partition').onchange = go;
        host.querySelector('#f-failed').onchange = go;
        host.querySelector('#f-normalizable').onchange = go;
        host.querySelector('#batch-normalize').onclick = () => batchNormalize(host);
        host.querySelector('#shuffle').onclick = () => shufflePlay(host);
        const syncBtn = host.querySelector('#sync');
        if (!syncBtn.disabled) {
            // 同步只动库（列表现扫磁盘），完成后重拉一次是为了让标签 / 倍速 / 失踪标记跟上
            syncBtn.onclick = (ev) => SongActions.syncNow(ev.target, null, () => load(host));
        }

        host.querySelector('#f-partition').value = filter.partition;
        host.querySelector('#f-keyword').value = filter.keyword;
        host.querySelector('#f-failed').checked = filter.onlyFailed;
        host.querySelector('#f-normalizable').checked = filter.onlyNormalizable;
    }

    function bindRows(host) {
        const find = (btn) => rows.find(r => r.mergeKey === btn.closest('tr').dataset.key);
        const reload = () => load(host);

        host.querySelectorAll('.act-play').forEach(btn => btn.onclick = () => {
            const r = find(btn);
            // list 传的是当前筛选后的行，所以「下一首」跟着筛选走，符合直觉
            SongPlayer.open(r, {list: rows, onRateSaved: reload});
        });
        host.querySelectorAll('.act-edit').forEach(btn => btn.onclick = () =>
            SongActions.edit(find(btn), {partitions, onDone: reload}));
        host.querySelectorAll('.act-delete').forEach(btn => btn.onclick = () =>
            SongActions.remove(find(btn), {onDone: reload}));
    }

    /**
     * 随机播放：弹窗勾选来源分区，把当前筛选结果里命中分区的行洗牌后交给播放器轮播。
     * 纯前端，不落库 —— 切页/刷新即失。
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
                UI.err('勾选的分区里没有可播的歌');
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
            SongPlayer.open(list[0], {list, onRateSaved: () => load(host)});
        };
    }

    /**
     * 批量规范化命名。逐 variant 提交：一个合并行里可能只有部分版本缺原曲名括号，
     * 只把需要规范化的 variant 的 key 交出去 —— 后端对不需要的组会报「已经是规范形态」。
     *
     * 与单组不同，这里**一组失败不影响其它组**（后端 applyNormalizeBatch），
     * 因为几百组里总会有一两个撞上同名目标文件，为此整批不做代价太大。
     */
    function batchNormalize(host) {
        const targets = rows.flatMap(r => r.variants.filter(v => v.needsNormalize));
        if (!targets.length) {
            return;
        }
        const m = UI.modal('<div class="modal modal-wide">'
            + '<div class="modal-head"><span>批量规范化命名 ' + targets.length + ' 组</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<p class="muted small">把 <span class="mono">作者 - 曲名</span> 补成 '
            + '<span class="mono">作者 - 曲名（曲名）</span>。没写原曲名意味着「原曲没被改词，'
            + '直接翻唱」，补上之后按原曲名统计就不必区分两种形态了。</p>'
            + '<table class="small"><thead><tr><th style="width:50%">现主名</th><th>改成</th>'
            + '</tr></thead><tbody>'
            + targets.map(t => '<tr><td class="mono">' + UI.esc(t.mainName) + '</td>'
                + '<td class="mono">' + UI.esc(t.normalizedMainName) + '</td></tr>').join('')
            + '</tbody></table>'
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" id="bn-cancel">取消</button>'
            + '<button type="button" class="btn-primary" id="bn-run">确认改这 '
            + targets.length + ' 组</button>'
            + '</div></div>');
        m.box.querySelector('.modal-close').onclick = () => m.close();
        m.box.querySelector('#bn-cancel').onclick = () => m.close();
        m.box.querySelector('#bn-run').onclick = async (ev) => {
            // 提交 → 轮询到终态期间全程禁用按钮（同 songaction runPlan 的坑：
            // submitAndWait 起轮询就返回、不接终态会让 withBusy 立刻恢复按钮，连点会重复提交）
            const btn = ev.currentTarget;
            await UI.withBusy(btn, '提交中…', () =>
                new Promise((resolve) => {
                    const settled = () => resolve();
                    TaskPoll.submitAndWait(
                        () => Api.post('/api/song/normalize-batch',
                            {keys: targets.map(t => t.key)}),
                        {
                            onResult: async (result) => {
                                try {
                                    UI.ok('改了 ' + result.renamed + ' 组'
                                        + (result.errors.length
                                            ? '，' + result.errors.length + ' 组失败' : ''));
                                    m.close();
                                    if (result.errors.length) {
                                        const em = UI.modal('<div class="modal">'
                                            + '<div class="modal-head"><span>没改成的 '
                                            + result.errors.length + ' 组</span>'
                                            + '<button class="modal-close" type="button">×</button></div>'
                                            + '<div class="modal-body"><ul class="small">'
                                            + result.errors.map(msg => '<li>' + UI.esc(msg) + '</li>').join('')
                                            + '</ul></div>'
                                            + '<div class="modal-foot"><button type="button" class="btn-plain"'
                                            + ' id="err-close">关闭</button></div></div>');
                                        em.box.querySelector('.modal-close').onclick = () => em.close();
                                        em.box.querySelector('#err-close').onclick = () => em.close();
                                    }
                                    await load(host);
                                    return true;
                                } finally {
                                    settled();
                                }
                            },
                            onFail: (error) => {
                                UI.err(error);
                                settled();
                            }
                        }).catch((e) => {
                            UI.err(e.message);
                            settled();
                        });
                }));
        };
    }

    // ------------------------------------------------------------------
    // 冗余文件（从歌曲组剔除的已归档文件：磁盘在 成品-歌曲\冗余，库里 song_id=0）
    // ------------------------------------------------------------------

    function redundantCard() {
        return '<div class="card" style="margin-top:12px">'
            + '<div class="row" style="justify-content:space-between">'
            + '<h3 style="margin:0">冗余文件 <span class="muted small" id="rd-count"></span></h3>'
            + '<button id="rd-toggle">展开</button>'
            + '</div>'
            + '<p class="muted small">从歌曲组里剔除的已归档文件落在这里：磁盘在 '
            + '<span class="mono">' + UI.esc(roots ? roots.archivedRoot : '') + '\\冗余</span>，'
            + '库里 song_id=0，不再属于任何一组。处置（删除 / 搬回）请到资源管理器操作，'
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
            data = await Api.get('/api/song/redundant-files?page=' + rdPage + '&size=20');
        } catch (e) {
            body.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        host.querySelector('#rd-count').textContent = data.total + ' 个';
        if (!data.items.length) {
            body.innerHTML = UI.empty('没有冗余文件。从歌曲组里剔除的文件会出现在这里。');
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

    return {render};
})();
