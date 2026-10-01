/**
 * 任务记录页（导航上叫「任务记录」，在左下角「系统工具」那一栏）：
 * 各模块（漫画 / 填词歌曲 / 喊麦）的异步任务都在这看状态、看日志、点「重新执行」。
 */
const TaskPage = (() => {

    let refreshTimer = null;

    // 防止连点/自动刷新重画后二次提交：重跑期间整页只许有一个提交在进行
    let rerunning = false;

    const STATUS_BADGE = {
        PENDING: ['tag', '排队中'],
        RUNNING: ['tag-warn', '进行中'],
        DONE: ['tag-ok', '完成'],
        FAILED: ['tag-err', '失败'],
        INTERRUPTED: ['tag-err', '已中断']
    };

    const MODULES = [['', '全部'], ['manga', '漫画'], ['song', '填词歌曲'], ['shout', '喊麦']];

    // taskType → 中文短标签。没列的（manga.pack-cbz）退回原 taskType 字符串
    const TYPE_LABEL = {
        'manga.new.store': '存储',
        'manga.new.collection-store': '存储合集',
        'manga.new.compress-normalize': '压缩',
        'manga.new.normalize-batch': '规范化命名',
        'manga.archive.sync': '归档同步',
        'manga.archive.scan-mangas': '扫描归档',
        'manga.unarchived.scan': '扫描散漫',
        'manga.unarchived.archive-batch': '重新归档',
        'manga.tag.rename': '标签改名',
        'manga.tag.merge': '标签合并',
        'manga.tag.delete': '标签删除',
        'manga.tag.eh-sync': 'EH标签同步',
        'manga.eh-scan': 'EH扫描',
        'manga.dict.batch': '词典补录',
        'song.sync': '歌曲同步',
        'song.normalize-batch': '规范化命名',
        'song.edit-batch': '评分/改名',
        'song.delete-batch': '批量删除',
        'song.template.scan': '模板扫描',
        'song.template.search': '搜索资源',
        'shout.sync': '喊麦同步',
        'shout.edit-batch': '评分/改名',
        'shout.delete-batch': '删除'
    };

    function render(host) {
        // app.js 的 route() 只清 innerHTML，没有 dispose 钩子，定时器要自己防泄漏
        stopRefresh();
        host.innerHTML = '<div class="card">'
            + '<div class="row" style="gap:8px;align-items:center;justify-content:space-between">'
            + '<div class="row" style="gap:8px;align-items:center">'
            + '<label class="muted small">模块</label><select id="task-module"></select>'
            + '<label class="muted small">状态</label><select id="task-status"></select>'
            + '</div>'
            + '<button type="button" class="btn-danger" id="del-completed">删除已完成</button>'
            + '</div></div>'
            + '<div id="task-list">' + UI.spinner('加载任务…') + '</div>';

        const moduleSel = host.querySelector('#task-module');
        const statusSel = host.querySelector('#task-status');
        MODULES.forEach(([v, label]) => {
            moduleSel.insertAdjacentHTML('beforeend',
                '<option value="' + v + '">' + UI.esc(label) + '</option>');
        });
        [['', '全部'], ['PENDING', '排队中'], ['RUNNING', '进行中'],
            ['DONE', '完成'], ['FAILED', '失败'], ['INTERRUPTED', '已中断']]
            .forEach(([v, label]) => {
                statusSel.insertAdjacentHTML('beforeend',
                    '<option value="' + v + '">' + UI.esc(label) + '</option>');
            });
        moduleSel.onchange = () => load(host, moduleSel.value, statusSel.value);
        statusSel.onchange = () => load(host, moduleSel.value, statusSel.value);
        host.querySelector('#del-completed').onclick = (ev) => delCompleted(ev.target);

        load(host, '', '');
    }

    async function load(host, module, status) {
        const box = host.querySelector('#task-list');
        if (!box || !box.isConnected) {
            return;
        }
        let tasks;
        try {
            const qs = new URLSearchParams();
            if (module) {
                qs.set('module', module);
            }
            if (status) {
                qs.set('status', status);
            }
            const url = '/api/tasks' + (qs.toString() ? '?' + qs.toString() : '');
            tasks = await Api.get(url);
        } catch (e) {
            box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            stopRefresh();
            return;
        }
        draw(box, tasks);

        // 有活干就自动刷新，全终态了就停
        const hasActive = tasks.some(t => t.status === 'RUNNING' || t.status === 'PENDING');
        stopRefresh();
        if (hasActive) {
            refreshTimer = setInterval(() => {
                if (!host.isConnected) {
                    stopRefresh();
                    return;
                }
                load(host, module, status);
            }, 2000);
        }
    }

    function draw(box, tasks) {
        if (!tasks || !tasks.length) {
            box.innerHTML = UI.empty('没有任务');
            return;
        }
        let html = '<div class="card"><table class="table"><thead><tr>'
            + '<th>时间</th><th>类型</th><th>模块</th><th>任务</th><th>状态</th><th>进度</th>'
            + '<th>耗时</th><th>说明</th><th></th></tr></thead><tbody>';
        for (const t of tasks) {
            const [cls, text] = STATUS_BADGE[t.status] || ['tag', t.status];
            const pct = t.total ? Math.round(t.done * 100 / t.total) : 0;
            const rerun = (t.status === 'FAILED' || t.status === 'INTERRUPTED')
                && t.rerunnable;
            // 终态才能删；PENDING/RUNNING 还在内存热态和队列里，删了库行会漏进度
            const deletable = t.status === 'DONE' || t.status === 'FAILED'
                || t.status === 'INTERRUPTED';
            const typeLabel = TYPE_LABEL[t.taskType] || (t.taskType || '');
            // source=RERUN 表示这条是任务页点「重新执行」后重跑的（复用原行，不再另起新行）
            const srcMark = t.source === 'RERUN'
                ? '<div class="small muted">↺ 重跑</div>' : '';
            html += '<tr data-id="' + t.id + '">'
                + '<td class="small muted mono">' + UI.esc(fmt(t.createTime)) + '</td>'
                + '<td><span class="tag">' + UI.esc(typeLabel) + '</span>' + srcMark + '</td>'
                + '<td><span class="tag">' + UI.esc(t.module || '') + '</span></td>'
                + '<td>' + UI.esc(t.taskName) + '</td>'
                + '<td><span class="' + cls + '">' + text + '</span></td>'
                + '<td style="min-width:120px"><div class="progress"><div style="width:'
                + pct + '%"></div></div><span class="small muted">'
                + (t.total ? t.done + '/' + t.total : '—') + '</span></td>'
                + '<td class="small muted mono">' + UI.esc(duration(t)) + '</td>'
                + '<td class="small muted">' + UI.esc(t.message || '') + '</td>'
                + '<td>' + (rerun
                    ? '<button type="button" class="btn-plain" data-act="rerun">重新执行</button>'
                    : '')
                + (deletable
                    ? '<button type="button" class="btn-danger" data-act="del">删除</button>'
                    : '')
                + '</td></tr>';
            if (t.error || (t.logs && t.logs.length)) {
                html += '<tr class="detail" data-for="' + t.id + '" style="display:none">'
                    + '<td colspan="9">'
                    + (t.error ? '<div class="hint hint-err">' + UI.esc(t.error) + '</div>' : '')
                    + (t.logs && t.logs.length
                        ? '<div class="task-logs">' + t.logs.map(UI.esc).join('\n') + '</div>'
                        : '')
                    + '</td></tr>';
            }
        }
        html += '</tbody></table></div>';
        box.innerHTML = html;

        // 行点击展开/收起日志；点重跑只处理自己，不冒泡去展开
        box.querySelectorAll('tr[data-id]').forEach(tr => {
            const detail = box.querySelector('tr.detail[data-for="' + tr.dataset.id + '"]');
            if (!detail) {
                return;
            }
            tr.style.cursor = 'pointer';
            tr.onclick = () => {
                detail.style.display = detail.style.display === 'none' ? '' : 'none';
            };
        });
        box.querySelectorAll('[data-act="rerun"]').forEach(btn => {
            btn.onclick = async (ev) => {
                ev.stopPropagation();
                await rerun(btn, ev.target.closest('tr[data-id]').dataset.id);
            };
        });
        box.querySelectorAll('[data-act="del"]').forEach(btn => {
            btn.onclick = async (ev) => {
                ev.stopPropagation();
                await delTask(btn, ev.target.closest('tr[data-id]').dataset.id);
            };
        });
    }

    async function rerun(btn, id) {
        if (rerunning) {
            return;
        }
        if (!await UI.confirm('从头重新执行这个任务？\n\n'
            + '它会复用这一条任务记录，按保存的参数（可能已在执行中更新过）再跑一遍。'
            + '如果上次已经动了一部分磁盘，重跑前先确认磁盘状态，避免在半成品上二次搬运。',
            {title: '重新执行', okText: '重新执行'})) {
            return;
        }
        // 先置位再动手：自动刷新会每 2s 重画一次、把按钮的 disabled 抹掉，
        // 光靠 withBusy 拦不住第二次点击，得靠这个模块级标志
        rerunning = true;
        btn.disabled = true;
        try {
            await UI.withBusy(btn, '提交中…', async () => {
                await Api.post('/api/tasks/' + id + '/rerun');
                UI.ok('已重新执行');
            });
        } catch (e) {
            UI.err(e.message);
        } finally {
            rerunning = false;
            btn.disabled = false;
        }
        const host = document.getElementById('page-body');
        // 重新拉一版：这条任务已重置为排队中
        load(host, '', '');
    }

    /** 删除 / 批量删除后重拉列表，保留当前模块与状态的筛选 */
    function reloadList() {
        const host = document.getElementById('page-body');
        const m = host.querySelector('#task-module');
        const s = host.querySelector('#task-status');
        load(host, m ? m.value : '', s ? s.value : '');
    }

    async function delTask(btn, id) {
        if (!await UI.confirm('删除这条任务记录？\n\n只删掉这条历史记录，'
            + '不影响已经做完的磁盘 / 库改动。', {title: '删除任务', okText: '删除'})) {
            return;
        }
        await UI.withBusy(btn, '删除中…', async () => {
            try {
                await Api.del('/api/tasks/' + id);
                UI.ok('已删除');
                reloadList();
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    async function delCompleted(btn) {
        if (!await UI.confirm('删除所有「已完成」的任务记录？\n\n'
            + '失败 / 已中断的任务会留下（它们还能重新执行），只删已完成的历史。这条不可恢复。',
            {title: '删除已完成任务', okText: '删除'})) {
            return;
        }
        await UI.withBusy(btn, '删除中…', async () => {
            try {
                const n = await Api.del('/api/tasks/completed');
                UI.ok(n > 0 ? ('已删除 ' + n + ' 条已完成任务') : '没有已完成的任务');
                reloadList();
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    function fmt(iso) {
        if (!iso) {
            return '';
        }
        // 后端 LocalDateTime 序列化成 "2026-08-27T12:34:56"，去掉 T 看得顺眼点
        return String(iso).replace('T', ' ');
    }

    /** 耗时：无开始时间（排队）显示 —；运行中算到现在，终态算到 endTime */
    function duration(t) {
        if (!t.startTime) {
            return '—';
        }
        // LocalDateTime 无时区，两端同一台机器，本地解析后相减即真实耗时
        const start = new Date(t.startTime).getTime();
        const end = t.endTime ? new Date(t.endTime).getTime() : Date.now();
        const s = Math.max(0, Math.round((end - start) / 1000));
        if (s < 60) {
            return s + 's';
        }
        const m = Math.floor(s / 60);
        if (m < 60) {
            return m + 'm' + (s % 60) + 's';
        }
        return Math.floor(m / 60) + 'h' + (m % 60) + 'm';
    }

    function stopRefresh() {
        if (refreshTimer) {
            clearInterval(refreshTimer);
            refreshTimer = null;
        }
    }

    return {render};
})();
