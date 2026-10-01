/**
 * 文件记录页（#file-ops，导航上叫「文件记录」）：三个模块的磁盘改动流水，只读 + 手工清理。
 *
 * 入口在左下角「系统工具」那一栏（foot: true，与「任务记录」同列，2026-09-23 用户定）；不进 PageModule /
 * 模块开关、不作 Modules 数组第一项、不在 /api/modules 里：它跨三个模块，模块裁剪后
 * 仍要能看；放主导航第一位会变成默认落地页（app.js 的 defaultId 取「第一个没有
 * children 又有 render 的顶层项」）。
 *
 * 只记应用自己做的改动；在资源管理器里手工动的盘这里看不见 —— 这句话页面上要有，
 * 免得被当成完整审计。记录本身只追加，页面上的「清理」是唯一能删它的途径。
 */
const FileOpLogPage = (() => {

    const PAGE_SIZE = 50;
    let state = {page: 1, module: '', op: '', level: '', source: '', range: '7d', keyword: '', batchId: ''};

    async function render(host) {
        host.innerHTML = '<div class="card">'
            + '<p class="muted small">只记应用自己做的改动；在资源管理器里手工动的盘这里看不见。'
            + '同一次操作的多条记录共用一个批次号，点批次号只看那一次。</p>'
            + '<div class="row" style="flex-wrap:wrap;gap:6px" id="fo-filters"></div>'
            + '<div class="row" style="flex-wrap:wrap;gap:6px;margin-top:6px">'
            + '<input type="text" id="fo-keyword" placeholder="路径关键字（改前或改后）" style="flex:1;min-width:180px">'
            + '<button id="fo-go" class="btn-primary">查询</button>'
            + '<button id="fo-clear" title="清空全部筛选">清空</button>'
            + '<button id="fo-cleanup" style="margin-left:auto">清理…</button>'
            + '</div>'
            + '<div id="fo-list" style="margin-top:8px">' + UI.spinner() + '</div>'
            + '<div class="pager" id="fo-pager"></div>'
            + '</div>';
        drawFilters(host);
        host.querySelector('#fo-go').onclick = () => applyInput(host);
        host.querySelector('#fo-keyword').onkeydown = (e) => {
            if (e.key === 'Enter') {
                applyInput(host);
            }
        };
        host.querySelector('#fo-clear').onclick = () => {
            state = {page: 1, module: '', op: '', level: '', source: '', range: '7d',
                keyword: '', batchId: ''};
            host.querySelector('#fo-keyword').value = '';
            drawFilters(host);
            reload(host);
        };
        host.querySelector('#fo-cleanup').onclick = () => cleanup(host);
        await reload(host);
    }

    /** 筛选条。范围默认近 7 天 */
    function drawFilters(host) {
        const sel = (id, label, options, current) => '<label class="small muted">' + label
            + ' <select id="' + id + '">'
            + options.map(o => '<option value="' + o.v + '"'
                + (o.v === current ? ' selected' : '') + '>' + o.t + '</option>').join('')
            + '</select></label>';
        host.querySelector('#fo-filters').innerHTML =
            sel('fo-module', '模块', [
                {v: '', t: '全部'}, {v: 'MANGA', t: '漫画'}, {v: 'SONG', t: '歌曲'}, {v: 'SHOUT', t: '喊麦'}
            ], state.module)
            + sel('fo-op', '类型', [
                {v: '', t: '全部'}, {v: 'MOVE', t: '移动'}, {v: 'DELETE', t: '删除'}, {v: 'WRITE', t: '写入'}
            ], state.op)
            + sel('fo-level', '层级', [
                {v: '', t: '全部'}, {v: 'AUTHOR', t: '作者目录'}, {v: 'COLLECTION', t: '合集目录'},
                {v: 'SINGLE', t: '单本'}, {v: 'GROUP', t: '一组'}, {v: 'OTHER', t: '其它'}
            ], state.level)
            + sel('fo-source', '来源', [
                {v: '', t: '全部'}, {v: 'PAGE', t: '页面'}, {v: 'TASK', t: '任务'},
                {v: 'SCRIPT', t: '脚本'}, {v: 'UNKNOWN', t: '未标注'}
            ], state.source)
            + sel('fo-range', '时间', [
                {v: '1d', t: '近 1 天'}, {v: '7d', t: '近 7 天'}, {v: '30d', t: '近 30 天'}, {v: '', t: '全部'}
            ], state.range);
        for (const [id, key] of [['fo-module', 'module'], ['fo-op', 'op'],
            ['fo-level', 'level'], ['fo-source', 'source'], ['fo-range', 'range']]) {
            host.querySelector('#' + id).onchange = (e) => {
                state[key] = e.target.value;
                state.page = 1;
                reload(host);
            };
        }
    }

    function applyInput(host) {
        state.keyword = host.querySelector('#fo-keyword').value.trim();
        state.page = 1;
        reload(host);
    }

    function timeFrom() {
        if (!state.range) {
            return null;
        }
        const hours = state.range === '1d' ? 24 : state.range === '7d' ? 168 : 720;
        const d = new Date(Date.now() - hours * 3600 * 1000);
        // LocalDateTime 的 ISO 形态（后端 @DateTimeFormat(iso = DATE_TIME)）
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
            + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    }

    const pad = (n) => String(n).padStart(2, '0');

    async function reload(host) {
        const list = host.querySelector('#fo-list');
        list.innerHTML = UI.spinner();
        const params = [];
        const push = (k, v) => {
            if (v) {
                params.push(Util.qs(k, v));
            }
        };
        push('module', state.module);
        push('op', state.op);
        push('level', state.level);
        push('source', state.source);
        push('batchId', state.batchId);
        push('keyword', state.keyword);
        const from = timeFrom();
        if (from) {
            params.push('from=' + encodeURIComponent(from));
        }
        params.push('page=' + state.page, 'size=' + PAGE_SIZE);
        let data;
        try {
            data = await Api.get('/api/file-op-log?' + params.join('&'));
        } catch (e) {
            list.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        if (state.batchId) {
            // 批次筛选的提示条，能一键回到全部
            list.innerHTML = '<div class="row small muted" style="margin-bottom:6px">只看批次 '
                + '<span class="mono">' + UI.esc(state.batchId.slice(0, 8)) + '</span> '
                + '<a href="#" id="fo-batch-clear">看全部</a></div>'
                + table(data);
            const clear = list.querySelector('#fo-batch-clear');
            if (clear) {
                clear.onclick = (ev) => {
                    ev.preventDefault();
                    state.batchId = '';
                    reload(host);
                };
            }
        } else {
            list.innerHTML = table(data);
        }
        bindRows(list, host);
        UI.pagerBar(host.querySelector('#fo-pager'), data.total, data.page, data.size,
            (p) => {
                state.page = p;
                reload(host);
            });
    }

    function emptyText(data) {
        // 两种空态是两句不同的话：完全没有 = 可能记录器没接上，指向环境自检
        const filtered = state.module || state.op || state.level || state.source
            || state.keyword || state.batchId || state.range;
        return filtered
            ? '这个筛选下一无所有。放宽条件再试。'
            : '一条记录都没有。如果刚搬过文件，先看左下角「环境自检」里 '
                + '「表 file_op_log 已存在」是不是绿的 —— 记录器接不上时这里就是空的。';
    }

    function table(data) {
        if (!data.items.length) {
            return UI.empty(emptyText(data));
        }
        // 改前 / 改后各一列、显示完整路径（自动换行，不靠横向滚动——路径本来就长，
        // 滚动反而看不到全貌）。窄列显式宽度，两列路径分掉余宽，min-width 兜底
        const widths = [140, 56, 56, 84, 260, 260, 130, 60, 60, 64];
        const sum = widths.reduce((a, b) => a + b, 0);
        let html = '<div style="overflow-x:auto"><table style="min-width:' + (sum + 40) + 'px">'
            + '<thead><tr>'
            + '<th style="width:140px">时间</th><th style="width:56px">模块</th>'
            + '<th style="width:56px">类型</th><th style="width:84px">层级</th>'
            + '<th style="min-width:260px">改前路径</th><th style="min-width:260px">改动后路径</th>'
            + '<th style="width:130px">动作</th>'
            + '<th style="width:60px">来源</th><th style="width:60px">结果</th>'
            + '<th style="width:64px">批次</th>'
            + '</tr></thead><tbody>';
        let prevBatch = null;
        let batchAlt = false;
        for (const r of data.items) {
            // 同一批次相邻的行用同一条浅色左边框 + 同色底纹串起来
            if (r.batchId !== prevBatch) {
                batchAlt = !batchAlt;
                prevBatch = r.batchId;
            }
            const stripe = batchAlt ? ' class="fo-batch-a"' : ' class="fo-batch-b"';
            // 删除只有改前、写入只有改后，空侧画 —；其余原样完整显示
            const beforeCell = r.fromPath
                ? '<div class="mono small fo-path">' + UI.esc(r.fromPath) + '</div>'
                : '<span class="muted">—</span>';
            const afterCell = r.toPath
                ? '<div class="mono small fo-path">' + UI.esc(r.toPath) + '</div>'
                : '<span class="muted">—</span>';
            html += '<tr' + stripe + ' data-batch="' + UI.esc(r.batchId) + '">'
                + '<td class="small mono">' + UI.esc(r.timeText) + '</td>'
                + '<td>' + UI.esc(r.moduleText) + '</td>'
                + '<td>' + UI.esc(r.opText) + '</td>'
                + '<td>' + UI.esc(r.levelText) + '</td>'
                + '<td>' + beforeCell + '</td>'
                + '<td>' + afterCell + '</td>'
                + '<td class="small">' + UI.esc(r.action || '') + '</td>'
                + '<td>' + UI.esc(r.sourceText) + (r.taskId ? '<div class="muted tiny">#' + r.taskId + '</div>' : '') + '</td>'
                + '<td>' + (r.resultText === '失败'
                    ? '<span class="tag tag-err" title="' + UI.esc(r.detail || '') + '">失败</span>'
                    : '<span class="tag tag-ok">成功</span>') + '</td>'
                + '<td><a href="#" class="fo-batch mono small" title="只看这个批次">'
                    + UI.esc(r.batchId.slice(0, 8)) + '</a></td>'
                + '</tr>';
        }
        return html + '</tbody></table></div>';
    }

    function bindRows(list, host) {
        list.querySelectorAll('.fo-batch').forEach(a => a.onclick = (ev) => {
            ev.preventDefault();
            state.batchId = a.closest('tr').dataset.batch;
            state.page = 1;
            reload(host);
        });
        // 悬停某一行时同批次的一起高亮
        list.querySelectorAll('tr[data-batch]').forEach(tr => {
            tr.onmouseenter = () => mark(tr.dataset.batch, true);
            tr.onmouseleave = () => mark(tr.dataset.batch, false);
        });
    }

    function mark(batchId, on) {
        document.querySelectorAll('tr[data-batch]').forEach(tr => {
            if (tr.dataset.batch === batchId) {
                tr.classList.toggle('fo-hover', on);
            }
        });
    }

    async function cleanup(host) {
        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>清理记录</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<p class="muted small">删除多少天前的记录（最小 7 天，防止手滑删掉最近的）。</p>'
            + '<input type="number" id="fo-days" value="30" min="7" style="width:120px">'
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" id="fo-days-cancel">取消</button>'
            + '<button type="button" class="btn-danger" id="fo-days-ok">删除</button>'
            + '</div></div>');
        m.box.querySelector('.modal-close').onclick = () => m.close();
        m.box.querySelector('#fo-days-cancel').onclick = () => m.close();
        m.box.querySelector('#fo-days-ok').onclick = async (ev) => {
            const days = Number(m.box.querySelector('#fo-days').value);
            const btn = ev.currentTarget;
            await UI.withBusy(btn, '删除中…', async () => {
                try {
                    const n = await Api.post('/api/file-op-log/cleanup?days=' + days);
                    m.close();
                    UI.ok('删了 ' + n + ' 条');
                    state.page = 1;
                    reload(host);
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };
    }

    return {render};
})();
