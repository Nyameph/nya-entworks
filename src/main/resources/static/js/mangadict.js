/**
 * 词典页（文档 4.2）。
 *
 * 词条在库里之后，「改源码-重编译」那条循环已经断了：保存即 reload、当场生效。
 * 所以这一页要解决的是另外两件事 ——
 *   1. 把补录做得快：批量粘贴、默认匹配方式按类型预填；
 *   2. 把「匹配方式」这个迁库后才出现的决策点表达清楚，并在补录前预检
 *      相似词条与已能匹配的词条，免得越补越乱。
 *
 * 顶部三个诊断是 MangaDictDiagnoseTest 的界面版，用途是把「解析代码的问题」
 * 和「表数据的问题」分开 —— 某类型为空、缺 REGEX、正则编译失败，症状都是
 * 解析结果莫名变差，而根因在数据不在代码。
 */
const MangaDictPage = (() => {

    const MATCH_MODES = ['EXACT', 'PREFIX', 'SUFFIX', 'CONTAINS', 'REGEX'];
    const MODE_HINT = {
        EXACT: '全等', PREFIX: '前缀', SUFFIX: '后缀', CONTAINS: '包含', REGEX: '正则（整体匹配，不用写 ^$）'
    };

    const PAGE_SIZE = 50;  // 每页条数，与后端 size 一致
    let types = [];        // 类型元信息，含中文名与默认匹配方式
    let currentType = null;
    let keyword = '';
    let page = 1;          // 当前页，切类型/搜索/整页重载时回到 1

    async function render(host) {
        host.innerHTML = UI.spinner();
        let summary;
        try {
            types = await Api.get('/api/manga/dict/types');
            summary = await Api.get('/api/manga/dict/summary');
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        if (!currentType) {
            currentType = types[0].dictType;
        }
        page = 1;

        host.innerHTML = renderDiagnose(summary)
            + '<div id="probe-card"></div>'
            + '<div id="entries-card">' + UI.spinner() + '</div>';

        host.querySelector('#probe-card').innerHTML = renderProbeCard();
        bindDiagnose(host);
        bindProbe(host);
        await renderEntries(host);
    }

    // ------------------------------------------------------------------
    // 诊断三件套
    // ------------------------------------------------------------------

    function renderDiagnose(summary) {
        let html = '';
        // 无法编译的正则是静默失效的：快照构建时跳过，解析侧什么都不会报
        if (summary.invalidRegexValues.length) {
            html += '<div class="hint hint-err"><strong>有 ' + summary.invalidRegexValues.length
                + ' 条正则无法编译，已被静默跳过</strong>'
                + '<div class="small mono">' + summary.invalidRegexValues.map(UI.esc).join('<br>')
                + '</div>这些词条等于不存在，在下面找到它们改掉。</div>';
        }
        const empties = summary.stats.filter(s => s.total === 0);
        if (empties.length) {
            html += '<div class="hint">以下类型一条词条都没有，相关识别会全部漏判：'
                + empties.map(s => UI.esc(labelOf(s.dictType))).join('、') + '</div>';
        }

        html += '<div class="card"><div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">类型分布 <span class="muted small">词典版本 '
            + summary.version + '</span></h2>'
            + '<div class="row"><button id="nonnfc-btn">查非 NFC 词条</button>'
            + '<button id="reload-btn">重新加载词典</button></div></div>'
            + '<table><thead><tr><th style="width:16%">类型</th><th style="width:8%">条数</th>'
            + '<th style="width:36%">匹配方式分布</th><th>说明</th></tr></thead><tbody>';
        for (const stat of summary.stats) {
            const meta = types.find(t => t.dictType === stat.dictType) || {};
            const modes = Object.entries(stat.byMatchMode || {})
                .map(([m, c]) => '<span class="tag">' + UI.esc(m) + ' ' + c + '</span>').join(' ');
            // 杂志只有 PREFIX 没有 REGEX 时，靠年月号识别的会全部漏判 —— 单独点出来
            const missRegex = stat.dictType === 'MAGAZINE' && stat.total > 0
                && !(stat.byMatchMode || {}).REGEX;
            html += '<tr>'
                + '<td>' + UI.esc(meta.label || stat.dictType)
                + '<div class="muted small mono">' + UI.esc(stat.dictType) + '</div></td>'
                + '<td>' + (stat.total === 0
                    ? '<span class="tag tag-err">0</span>' : stat.total) + '</td>'
                + '<td>' + (modes || '<span class="muted">—</span>')
                + (stat.nonNfcCount ? ' <span class="tag tag-warn">非 NFC ' + stat.nonNfcCount + '</span>' : '')
                + '</td>'
                + '<td class="small muted">' + UI.esc(meta.description || '')
                + (missRegex ? '<br><span class="tag tag-warn">只有前缀没有正则</span>' : '')
                + '</td></tr>';
        }
        html += '</tbody></table><div id="nonnfc-box"></div></div>';
        return html;
    }

    function bindDiagnose(host) {
        host.querySelector('#reload-btn').onclick = async (ev) => {
            await UI.withBusy(ev.target, '加载中…', async () => {
                try {
                    const version = await Api.post('/api/manga/dict/reload');
                    UI.ok('词典已重新加载，版本 ' + version);
                    await render(host);
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };
        host.querySelector('#nonnfc-btn').onclick = async (ev) => {
            const box = host.querySelector('#nonnfc-box');
            await UI.withBusy(ev.target, '查询中…', async () => {
                try {
                    const list = await Api.get('/api/manga/dict/non-nfc');
                    if (!list.length) {
                        box.innerHTML = '<p class="small muted">没有非 NFC 词条。</p>';
                        return;
                    }
                    // 这类行 MySQL 判等而 Java 判不等，解析不受影响（快照会归一），
                    // 但能解释「库里明明有却匹配不上、又因唯一键冲突补录不进去」
                    box.innerHTML = '<div class="hint">以下 ' + list.length
                        + ' 条是 Unicode 分解形式。解析不受影响（快照会归一），'
                        + '但它们能解释「库里明明有却补录不进去」这类怪现象。'
                        + '<div class="small mono">'
                        + list.map(e => UI.esc(e.dictType + '  ' + e.dictValue + '  id=' + e.id)).join('<br>')
                        + '</div></div>';
                } catch (e) {
                    box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
                }
            });
        };
    }

    // ------------------------------------------------------------------
    // 试探输入框
    // ------------------------------------------------------------------

    function renderProbeCard() {
        return '<div class="card"><h2>试探</h2>'
            + '<p class="muted small">输一个文件夹名片段，看词典把它认成什么。'
            + '排查漏判最快的手段 —— 判定与解析时用的是同一套（含按 <span class="mono">、</span> 拆分）。</p>'
            + '<div class="row"><input type="text" id="probe-input" style="flex:1;min-width:260px" '
            + 'placeholder="例如 COMIC1☆25 或 COMIC BAVEL 2019年3月号">'
            + '<button id="probe-btn">试探</button></div>'
            + '<div id="probe-result"></div></div>';
    }

    function bindProbe(host) {
        const run = async () => {
            const text = host.querySelector('#probe-input').value.trim();
            const box = host.querySelector('#probe-result');
            if (!text) {
                box.innerHTML = '';
                return;
            }
            try {
                const res = await Api.get('/api/manga/dict/probe?text=' + encodeURIComponent(text));
                if (!res.hitTypes.length) {
                    box.innerHTML = '<p class="small">未命中任何类型。'
                        + '如果它本该被识别，就是缺词条 —— 在下面补录。</p>';
                    return;
                }
                let html = '<p class="small">命中 '
                    + res.hitTypes.map(t => '<span class="tag tag-ok">' + UI.esc(labelOf(t))
                        + '</span>').join(' ') + '</p>';
                if (res.hitEntries.length) {
                    html += '<table><thead><tr><th>类型</th><th>词条</th><th>匹配方式</th></tr></thead><tbody>';
                    for (const e of res.hitEntries) {
                        html += '<tr><td class="small">' + UI.esc(labelOf(e.dictType)) + '</td>'
                            + '<td class="mono small">' + UI.esc(e.dictValue) + '</td>'
                            + '<td class="small">' + UI.esc(e.matchMode || 'EXACT') + '</td></tr>';
                    }
                    html += '</tbody></table>';
                }
                box.innerHTML = html;
            } catch (e) {
                box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            }
        };
        host.querySelector('#probe-btn').onclick = run;
        host.querySelector('#probe-input').onkeydown = (ev) => {
            if (ev.key === 'Enter') {
                run();
            }
        };
    }

    // ------------------------------------------------------------------
    // 词条浏览与维护
    // ------------------------------------------------------------------

    async function renderEntries(host) {
        const card = host.querySelector('#entries-card');
        card.innerHTML = UI.spinner();
        let data;
        try {
            data = await Api.get('/api/manga/dict?type=' + currentType
                + (keyword ? '&keyword=' + encodeURIComponent(keyword) : '')
                + '&page=' + page + '&size=' + PAGE_SIZE);
        } catch (e) {
            card.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        const entries = data.items;
        // 当前页删空越界（如删掉末页最后一条）时，回退到最后一页
        if (!entries.length && data.total > 0 && data.page > 1) {
            page = Math.ceil(data.total / PAGE_SIZE);
            return renderEntries(host);
        }

        const meta = types.find(t => t.dictType === currentType) || {};
        let html = '<div class="card">'
            + '<div class="row" style="margin-bottom:12px">'
            + types.map(t => '<button class="type-tab' + (t.dictType === currentType ? ' btn-primary' : '')
                + '" data-type="' + t.dictType + '">' + UI.esc(t.label) + '</button>').join('')
            + '</div>'
            + '<div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">' + UI.esc(meta.label || currentType)
            + ' <span class="muted small">' + data.total + ' 条</span></h2>'
            + '<div class="row"><input type="text" id="kw" value="' + UI.esc(keyword)
            + '" placeholder="按值模糊搜"><button id="kw-btn">搜索</button></div></div>'
            + '<p class="muted small">' + UI.esc(meta.description || '') + '</p>';

        if (entries.length) {
            html += '<table><thead><tr>'
                + '<th style="width:38%">值</th><th style="width:12%">匹配方式</th>'
                + '<th style="width:8%">忽略大小写</th><th>备注</th><th style="width:14%"></th>'
                + '</tr></thead><tbody>';
            for (const e of entries) {
                const mode = e.matchMode || 'EXACT';
                html += '<tr data-id="' + e.id + '">'
                    + '<td><input type="text" class="f-value mono" value="' + UI.esc(e.dictValue)
                    + '" style="width:100%"></td>'
                    + '<td>' + modeSelect('f-mode', mode) + '</td>'
                    + '<td><input type="checkbox" class="f-ic"'
                    + (e.ignoreCase === false ? '' : ' checked') + '></td>'
                    + '<td><input type="text" class="f-remark" value="' + UI.esc(e.remark || '')
                    + '" style="width:100%" placeholder="可记来源文件夹"></td>'
                    + '<td class="row"><button class="act-save">保存</button>'
                    + '<button class="btn-danger act-del">删除</button></td>'
                    + '</tr>';
            }
            html += '</tbody></table>';
        } else {
            html += UI.empty(keyword ? '没有匹配的词条。' : '这个类型还没有词条。');
        }
        html += '<div class="pager" id="dict-pager"></div>';
        html += '</div>';
        html += renderAddCard(meta);
        html += renderBatchCard(meta);
        card.innerHTML = html;
        bindEntries(host, card);
        const pager = card.querySelector('#dict-pager');
        if (pager) {
            pagerBar(pager, data.total, data.page, (p) => {
                page = p;
                renderEntries(host);
            });
        }
    }

    function modeSelect(cls, selected) {
        return '<select class="' + cls + '">'
            + MATCH_MODES.map(m => '<option value="' + m + '"'
                + (m === selected ? ' selected' : '') + ' title="' + UI.esc(MODE_HINT[m]) + '">'
                + m + '</option>').join('')
            + '</select>';
    }

    /** 分页条：每页条数用本页的 PAGE_SIZE，画法见 UI.pagerBar */
    function pagerBar(container, total, current, onGo) {
        UI.pagerBar(container, total, current, PAGE_SIZE, onGo);
    }

    function renderAddCard(meta) {
        const def = meta.defaultMatchMode || 'EXACT';
        return '<div class="card"><h2>补录一条</h2>'
            + '<p class="muted small">匹配方式已按类型预填成 <span class="mono">' + UI.esc(def)
            + '</span>（' + UI.esc(MODE_HINT[def]) + '），多数时候不用改。'
            + '正则不必写 <span class="mono">^$</span>，语义就是整体匹配。</p>'
            + '<div class="row">'
            + '<input type="text" id="new-value" style="flex:1;min-width:240px" placeholder="词条内容">'
            + modeSelect('new-mode', def)
            + '<label class="small muted"><input type="checkbox" id="new-ic" checked> 忽略大小写</label>'
            + '<input type="text" id="new-remark" placeholder="备注（可记来源文件夹）">'
            + '<button id="check-btn">预检</button>'
            + '<button class="btn-primary" id="add-btn">添加</button>'
            + '</div><div id="check-box"></div></div>';
    }

    function renderBatchCard(meta) {
        return '<div class="card"><h2>批量补录</h2>'
            + '<p class="muted small">一行一条，同类型同值的自动跳过。'
            + '扫描页做出来之后，未识别的展会/原作会直接送到这里，现在先手工粘。</p>'
            + '<textarea id="batch-values" rows="5" style="width:100%" '
            + 'placeholder="一行一条"></textarea>'
            + '<div class="row" style="margin-top:8px">'
            + modeSelect('batch-mode', meta.defaultMatchMode || 'EXACT')
            + '<label class="small muted"><input type="checkbox" id="batch-ic" checked> 忽略大小写</label>'
            + '<input type="text" id="batch-remark" placeholder="备注，整批共用">'
            + '<button class="btn-primary" id="batch-btn">批量添加到「'
            + UI.esc(meta.label || currentType) + '」</button>'
            + '</div></div>';
    }

    function bindEntries(host, card) {
        card.querySelectorAll('.type-tab').forEach(btn => btn.onclick = () => {
            currentType = btn.dataset.type;
            keyword = '';
            page = 1;
            renderEntries(host);
        });
        const doSearch = () => {
            keyword = card.querySelector('#kw').value.trim();
            page = 1;
            renderEntries(host);
        };
        card.querySelector('#kw-btn').onclick = doSearch;
        card.querySelector('#kw').onkeydown = (ev) => {
            if (ev.key === 'Enter') {
                doSearch();
            }
        };

        card.querySelectorAll('.act-save').forEach(btn => btn.onclick = async (ev) => {
            const tr = btn.closest('tr');
            const body = {
                dictType: currentType,
                dictValue: tr.querySelector('.f-value').value.trim(),
                matchMode: tr.querySelector('.f-mode').value,
                ignoreCase: tr.querySelector('.f-ic').checked,
                remark: tr.querySelector('.f-remark').value.trim()
            };
            await UI.withBusy(ev.target, '保存中…', async () => {
                try {
                    await Api.put('/api/manga/dict/' + tr.dataset.id, body);
                    UI.ok('已保存，词典已重新加载');
                    await renderEntries(host);
                } catch (e) {
                    // 正则编译失败在这里报出来，而不是弹一个 500
                    UI.err(e.message);
                }
            });
        });

        card.querySelectorAll('.act-del').forEach(btn => btn.onclick = async (ev) => {
            const tr = btn.closest('tr');
            const value = tr.querySelector('.f-value').value;
            if (!await UI.confirm('删除词条「' + value + '」？\n\n删掉后这个词就不再被识别了。',
                {title: '删除词条', okText: '删除'})) {
                return;
            }
            await UI.withBusy(ev.target, '删除中…', async () => {
                try {
                    await Api.del('/api/manga/dict/' + tr.dataset.id);
                    UI.ok('已删除');
                    await renderEntries(host);
                } catch (e) {
                    UI.err(e.message);
                }
            });
        });

        card.querySelector('#check-btn').onclick = (ev) => runCheck(card, ev.target);
        card.querySelector('#add-btn').onclick = async (ev) => {
            const value = card.querySelector('#new-value').value.trim();
            if (!value) {
                UI.err('请填词条内容');
                return;
            }
            await UI.withBusy(ev.target, '添加中…', async () => {
                try {
                    await Api.post('/api/manga/dict', {
                        dictType: currentType,
                        dictValue: value,
                        matchMode: card.querySelector('.new-mode').value,
                        ignoreCase: card.querySelector('#new-ic').checked,
                        remark: card.querySelector('#new-remark').value.trim()
                    });
                    UI.ok('已添加，词典已重新加载');
                    await renderEntries(host);
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };

        card.querySelector('#batch-btn').onclick = async (ev) => {
            const values = card.querySelector('#batch-values').value
                .split('\n').map(s => s.trim()).filter(Boolean);
            if (!values.length) {
                UI.err('没有要补录的内容');
                return;
            }
            await UI.withBusy(ev.target, '提交中…', async () => {
                try {
                    const taskId = await Api.post('/api/manga/dict/batch', {
                        dictType: currentType,
                        matchMode: card.querySelector('.batch-mode').value,
                        ignoreCase: card.querySelector('#batch-ic').checked,
                        remark: card.querySelector('#batch-remark').value.trim(),
                        values
                    });
                    TaskPoll.wait(taskId, {
                        onResult: async (n) => {
                            UI.ok('补录 ' + n + ' 条'
                                + (n < values.length
                                    ? ('，' + (values.length - n) + ' 条已存在跳过') : ''));
                            await renderEntries(host);
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

    /** 补录前预检：相似变体与「已经能匹配」的既有词条 */
    async function runCheck(card, btn) {
        const value = card.querySelector('#new-value').value.trim();
        const box = card.querySelector('#check-box');
        if (!value) {
            UI.err('请填词条内容');
            return;
        }
        await UI.withBusy(btn, '预检中…', async () => {
            try {
                const res = await Api.get('/api/manga/dict/check?type=' + currentType
                    + '&value=' + encodeURIComponent(value));
                let html = '';
                if (res.overlapping.length) {
                    // 多半是漏了 reload 而不是缺词条，这条提示能省掉一次无谓的新增
                    html += '<div class="hint"><strong>已有 ' + res.overlapping.length
                        + ' 条能匹配这个值，可能不必新增</strong><div class="small mono">'
                        + res.overlapping.map(e => UI.esc(e.dictValue + '  ('
                            + (e.matchMode || 'EXACT') + ')')).join('<br>')
                        + '</div></div>';
                }
                if (res.similar.length) {
                    html += '<div class="hint">库中已有相似词条（去掉 <span class="mono">/ 空格 ・ -</span> '
                        + '等分隔符后同名）：<div class="small mono">'
                        + res.similar.map(e => UI.esc(e.dictValue + '  (id=' + e.id + ')')).join('<br>')
                        + '</div>确认要作为新变体加进去，再点添加。</div>';
                }
                box.innerHTML = html || '<p class="small"><span class="tag tag-ok">没有相似或重叠的词条</span></p>';
            } catch (e) {
                box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            }
        });
    }

    function labelOf(dictType) {
        const meta = types.find(t => t.dictType === dictType);
        return meta ? meta.label : dictType;
    }

    return {render};
})();
