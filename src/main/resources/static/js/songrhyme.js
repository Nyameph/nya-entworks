/**
 * 韵脚词典页（填词助手设计 §4.7）。
 *
 * 两个页签：
 *   查询     —— 整块交给共用组件 RhymeQuery（js/rhymequery.js），本页不自己实现查询，
 *               免得填词页右栏那份「韵脚词典」和这里长歪成两套。工具条上另挂六件运维
 *               （重扫语料 / 重生成单字词典 / 补全词性 / 导入开源词表 / 合并重复词 / 导出 JSONL），
 *               前五件都是提交后台任务，最后一件是直接下载。
 *   韵部总表 —— 十八韵 18 条，带两级条数统计（该韵总条数 + 每个韵母的条数），点一行跳去查询。
 *
 * 「管理」页签（粘贴导入 / 从词表文件导入 / 单条补录 / 按来源清理 / 改词性备注）已随
 * 后端端点一并删除：新增改走查询页签的「新增」（批量粘贴），删除改走方块勾选 + 「删除」。
 *
 * 「锚」= 查询起点（汉字 / 韵母 / 韵部名），解析在后端（RhymeService.anchorOf）；
 * 认不出时后端给一句具体的 message，前端原样展示 —— 「0 条」和「认不出」必须长得不一样。
 */
const SongRhymePage = (() => {

    let meta = null;          // /meta 的结果，进程内缓存（韵部总表用）
    let metaSeq = 0;          // 竞态：只认最后一次 meta 请求
    let tab = 'query';        // query | table
    let aiStatus = null;      // /api/ai/status 的缓存：「补全词性」要本机 AI，没配就不画那个按钮

    /**
     * 本机 AI 端点配全了没有。
     * <p>取不到状态时当作<b>能用</b>：按钮画出来、点了会得到一句具体的报错，
     * 比按钮凭空消失、页面上没有任何解释好排查。与 app.js 取不到模块开关时同一套退让。
     */
    function aiReady() {
        return !(aiStatus && aiStatus.ready === false);
    }

    async function ensureMeta(force) {
        if (meta && !force) {
            return meta;
        }
        const mySeq = ++metaSeq;
        const data = await Api.get('/api/song/rhyme/meta');
        if (mySeq === metaSeq) {
            meta = data;
        }
        return data;
    }

    async function render(host) {
        host.innerHTML = UI.spinner('加载韵部总表…');
        try {
            await ensureMeta(true);
            // 与 meta 一起拉：留着下次进来都是新的（配置改完要重启后端，重启后页面本来也会重载）
            aiStatus = await Api.get('/api/ai/status').catch(() => null);
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        host.innerHTML =
            '<div class="card">'
            + '<div class="rh-tabs">'
            + '<button type="button" id="rh-tab-query" class="rh-tab">'
            + '<span class="rh-tab-key">查询</span></button>'
            + '<button type="button" id="rh-tab-table" class="rh-tab">'
            + '<span class="rh-tab-key">韵部总表</span></button>'
            + '<span class="muted small rh-tab-note">按韵部 / 韵母查字与词；'
            + '反查某个字押什么韵去填词页的右栏。</span>'
            + '</div></div>'
            + '<div id="rh-body"></div>';
        host.querySelector('#rh-tab-query').onclick = () => {
            tab = 'query';
            drawTabs(host);
            drawQuery(host);
        };
        host.querySelector('#rh-tab-table').onclick = () => {
            tab = 'table';
            drawTabs(host);
            drawTable(host);
        };
        drawTabs(host);
        if (tab === 'table') {
            drawTable(host);
        } else {
            drawQuery(host);
        }
    }

    function drawTabs(host) {
        for (const [id, key] of [['#rh-tab-query', 'query'], ['#rh-tab-table', 'table']]) {
            host.querySelector(id).classList.toggle('on', tab === key);
        }
    }

    // ==================== 查询（共用组件 + 运维工具条） ====================

    function drawQuery(host) {
        const body = host.querySelector('#rh-body');
        body.innerHTML = '<div class="card rh-ops">'
            + '<span class="muted small">运维</span>'
            + '<button id="rh-op-rescan" class="btn-plain">重扫歌词语料</button>'
            + '<button id="rh-op-seed" class="btn-plain">重新生成单字词典</button>'
            // 「补全词性」要本机 AI 端点：没配全就不画按钮，改画一句说明 ——
            // 画个点不出结果的按钮比不画更让人摸不着头脑（配置页顶部也有同款提示）
            + (aiReady()
                ? '<button id="rh-op-pos" class="btn-plain">补全词性</button>'
                : '<span class="muted small">补全词性不可用：'
                    + UI.esc((aiStatus && aiStatus.reason) || '本机 AI 端点没配全') + '</span>')
            + '<button id="rh-op-import-open" class="btn-plain">导入开源词表</button>'
            + '<button id="rh-op-dedup" class="btn-plain">合并重复词</button>'
            + '<button id="rh-op-jsonl" class="btn-plain">导出 JSONL</button>'
            + '<span class="muted small">前五个都提交成后台任务，提示「已提交」即可，进度去「任务记录」看；'
            + '不必等它跑完。</span>'
            + '</div>'
            + '<div id="rh-query-host"></div>';
        bindOps(body);
        // 不传 onPick：单击归组件自己管（进勾选态，`删除` 才亮得起来），双击复制。
        // 传了 onPick 就是「调用方接管单击」，勾选态永远进不去、删除按钮恒 disabled。
        RhymeQuery.render(body.querySelector('#rh-query-host'), {});
    }

    function bindOps(body) {
        const submit = (id, confirmText, url) => {
            const btn = body.querySelector(id);
            if (!btn) {
                // 按钮没画出来：AI 端点没配时的「补全词性」就是这种，别在这里炸
                return;
            }
            btn.onclick = () => UI.confirm(confirmText, {okText: '提交任务'}).then(ok => {
                if (!ok) {
                    return;
                }
                return UI.withBusy(btn, '提交中…', async () => {
                    try {
                        const taskId = await Api.post(url);
                        UI.ok('已提交任务 #' + taskId + '，去「任务记录」看进度');
                    } catch (e) {
                        UI.err(e.message);
                    }
                });
            });
        };
        submit('#rh-op-rescan',
            '重扫会删掉重建全部语料句与 CORPUS 词条（人工 / 词表 / 字表不动），几分钟跑完。现在提交？',
            '/api/song/corpus/rescan');
        submit('#rh-op-seed',
            '重新生成单字词典：只补现代规范字表里新增的字，已有词条不动。现在提交？',
            '/api/song/rhyme/seed');
        submit('#rh-op-pos',
            '补全词性：由本机 Ollama 逐条判词性，词条多时会跑一会儿。现在提交？',
            '/api/song/rhyme/pos');
        submit('#rh-op-import-open',
            '导入开源词表：把仓库内那份开源词表（约 4.7 万条）补进词典，已有的词条不动，'
            + '幂等、可重跑。现在提交？',
            '/api/song/rhyme/import-open');
        submit('#rh-op-dedup',
            '合并重复词：把同一个词、同一个音的重复行并成一行（单字轻声行让位给带声调的，'
            + '多字词以词表读音为准），人工行优先，幂等、可重跑。现在提交？',
            '/api/song/rhyme/dedup');
        const jsonlBtn = body.querySelector('#rh-op-jsonl');
        jsonlBtn.onclick = () => UI.withBusy(jsonlBtn, '导出中…', exportJsonl);
    }

    /** 导出微调素材：原始 JSONL 不走 ApiResult，直接 fetch 文本再落成 Blob */
    async function exportJsonl() {
        try {
            const res = await fetch('/api/song/corpus/pairs.jsonl?limit=100000');
            const text = await res.text();
            if (!text.trim()) {
                UI.err('还没有配对语料（填满整句并保存后会自动回流）');
                return;
            }
            const blob = new Blob([text], {type: 'application/jsonl'});
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = 'lyric_corpus_pairs.jsonl';
            a.click();
            URL.revokeObjectURL(a.href);
            UI.ok('已导出 ' + text.trim().split('\n').length + ' 行');
        } catch (e) {
            UI.err('导出失败：' + e.message);
        }
    }

    // ==================== 韵部总表 ====================

    function drawTable(host) {
        const body = host.querySelector('#rh-body');
        const rows = ((meta && meta.rhymes) || []).map(r =>
            '<tr class="rh-row" data-body="' + UI.esc(r.body) + '">'
            + '<td><b>' + UI.esc(r.yun18) + '</b></td>'
            + '<td class="mono">' + UI.esc(r.body) + '</td>'
            + '<td class="mono">' + finalsWithCounts(r) + '</td>'
            + '<td>' + UI.esc(r.sample) + '</td>'
            + '<td>' + UI.esc(r.zhe13) + '</td>'
            + '<td>' + UI.esc(r.yun14) + '</td>'
            + '<td class="mono">' + countHtml(r.count) + '</td>'
            + '</tr>').join('');
        body.innerHTML = '<div class="card">'
            + '<div class="muted small">十八韵与韵身一一对应；按韵查按韵身走，按韵母查是它的子集'
            + '（ia/ua 都归 a）。舌尖元音 -i（知 / 蚩 / 姿）单独一个韵部（五支），与 i（七齐）不混。'
            + '点一行跳去按这个韵部查询。</div>'
            + '<table><thead><tr><th>十八韵</th><th>韵身</th><th>韵母（条数）</th><th>代表字</th>'
            + '<th>十三辙</th><th>十四韵</th><th>总条数</th></tr></thead><tbody>' + rows + '</tbody></table>'
            + '</div>';
        body.querySelectorAll('.rh-row').forEach(tr => tr.onclick = () => {
            tab = 'query';
            drawTabs(host);
            drawQuery(host);
            RhymeQuery.setAnchor(tr.dataset.body);
        });
    }

    /** 「ang 132 / iang 41」：条数拿不到时只写韵母，不画一个假的 0 */
    function finalsWithCounts(r) {
        const counts = r.finalsCounts || {};
        return (r.finals || []).map(f => {
            const n = counts[f];
            return UI.esc(f) + (n === null || n === undefined ? '' : ' ' + UI.esc(n));
        }).join(' / ');
    }

    function countHtml(n) {
        return (n === null || n === undefined) ? '—' : UI.esc(n);
    }

    return {render};
})();
