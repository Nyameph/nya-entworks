/**
 * 作者统计页（需求变更新增）。
 *
 * 统计规则与原曲页一致（已归档与待打分分开计数、贝叶斯分只计已归档），
 * 只是换一个维度：**按作者看产出**。多作者歌（{@code A & B - 歌名}）每位作者各算一次，
 * 这样才能看出单个作者的总产出 —— 这在后端已经算好，前端只负责展示。
 *
 * 列：作者 / 已归档 / 待打分 / 贝叶斯分。没有倍速列 —— 没有「作者级默认倍速」
 * 这回事（倍速回落只到原曲名级）。
 *
 * 交互同原曲页：点「已归档/待打分」数字跳到对应页并筛选该作者、每列可排序（前端，
 * 不重新加载）、分页。
 */
const SongArtistPage = (() => {

    let stats = [];
    let keyword = '';
    let sortKey = 'total';
    let sortDir = -1;
    let page = 1;

    async function render(host, params) {
        // 歌曲页/播放页点作者跳转：把该作者当关键词预填，筛出这一项
        if (params && params.artist) {
            keyword = params.artist;
        }
        host.innerHTML = UI.spinner('扫磁盘…');
        try {
            stats = await Api.get('/api/song/artists');
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }
        draw(host);
    }

    function draw(host) {
        const needle = keyword.toLowerCase();
        const filtered = needle
            ? stats.filter(s => s.artistName.toLowerCase().includes(needle))
            : stats;
        const list = [...filtered].sort((a, b) => {
            const r = sortKey === 'name'
                ? a.artistName.localeCompare(b.artistName, 'zh-Hans-CN')
                : sortKey === 'archived'
                    ? a.archivedCount - b.archivedCount
                    : sortKey === 'staging'
                        ? a.stagingCount - b.stagingCount
                        : sortKey === 'bayesian'
                            ? Util.num(a.bayesianScore) - Util.num(b.bayesianScore)
                            : (a.archivedCount + a.stagingCount)
                                - (b.archivedCount + b.stagingCount);
            return r * sortDir;
        });
        const songs = stats.reduce((n, s) => n + s.archivedCount + s.stagingCount, 0);

        let html = '<div class="hint">只统计填词歌曲（多作者歌每位作者各算一次）。'
            + '喊麦不解析文件名，没有作者可统计，自成一个模块。点「已归档 / 待打分」'
            + '数字会跳到对应页并筛出该作者。</div>';

        html += '<div class="card">'
            + '<div class="row" style="justify-content:space-between">'
            + '<div class="row">'
            + '<input type="text" id="f-keyword" placeholder="搜作者" value="'
            + UI.esc(keyword) + '">'
            + '<button id="f-clear" title="清空搜索条件">清空</button>'
            + '<button id="f-refresh">重新扫描</button>'
            + '</div>'
            + '<h2 style="margin:0">作者 <span class="muted small">' + stats.length
            + ' 位，' + songs + ' 首（多作者重复计数）</span></h2>'
            + '</div>';

        if (!list.length) {
            html += UI.empty(stats.length ? '没有匹配的作者。' : '扫不到已解析的填词歌曲。');
        } else {
            const pageRows = list.slice((page - 1) * SongActions.PAGE_SIZE,
                page * SongActions.PAGE_SIZE);
            html += '<table><thead><tr>'
                + th('name', '作者', '46%')
                + th('archived', '已归档', '18%')
                + th('staging', '待打分', '18%')
                + th('bayesian', '贝叶斯分', '18%')
                + '</tr></thead><tbody>';
            for (const s of pageRows) {
                html += row(s);
            }
            html += '</tbody></table>'
                + '<p class="muted small">贝叶斯分只按<strong>已归档</strong>的版本算 —— '
                + '待打分的还没定分。低分降权、高分升权，样本越少越向全局均值靠拢。</p>';
        }
        html += '</div>';
        html += '<div class="pager" id="artist-pager"></div>';
        host.innerHTML = html;
        bind(host);
        if (list.length) {
            SongActions.pagerBar(host.querySelector('#artist-pager'), list.length, page, (p) => {
                page = p;
                draw(host);
            });
        }
    }

    /** 表头：排序状态是本文件的模块状态，绑定后交给 UI.th */
    function th(key, label, width) {
        return UI.th(key, label, width, sortKey, sortDir);
    }

    function row(s) {
        return '<tr data-key="' + UI.esc(s.artistKey) + '">'
            + '<td>' + UI.esc(s.artistName) + '</td>'
            + '<td><a href="#song-list?' + Util.qs('artist', s.artistName) + '">'
            + s.archivedCount + '</a></td>'
            + '<td>' + (s.stagingCount
                ? '<a href="#song-score?' + Util.qs('artist', s.artistName) + '">'
                + '<span class="tag tag-warn">' + s.stagingCount + '</span></a>'
                : '<span class="muted">0</span>') + '</td>'
            + '<td class="mono">' + (s.bayesianScore == null
                ? '<span class="muted">—</span>' : Number(s.bayesianScore).toFixed(1)) + '</td>'
            + '</tr>';
    }

    function bind(host) {
        const input = host.querySelector('#f-keyword');
        if (input) {
            // 中文输入法：组合（拼音）期间也会触发 input，若照常重绘会把输入框 DOM 换掉、
            // 打断组合，导致只能打英文。composition 期间只存值不重绘，选完字再搜。
            const search = () => {
                keyword = input.value.trim();
                page = 1;
                const at = input.selectionStart;
                draw(host);
                const next = host.querySelector('#f-keyword');
                next.focus();
                next.setSelectionRange(at, at);
            };
            let composing = false;
            input.addEventListener('compositionstart', () => { composing = true; });
            input.addEventListener('compositionend', () => {
                composing = false;
                setTimeout(search, 0); // 等 IME 收尾后再搜，避免焦点恢复打断组合
            });
            input.oninput = () => {
                if (!composing) {
                    search();
                }
            };
            host.querySelector('#f-refresh').onclick = () => {
                page = 1;
                render(host);
            };
            host.querySelector('#f-clear').onclick = () => {
                keyword = '';
                page = 1;
                draw(host);
            };
        }

        host.querySelectorAll('th[data-sort]').forEach(thEl => thEl.onclick = () => {
            const key = thEl.dataset.sort;
            if (sortKey === key) {
                sortDir = -sortDir;
            } else {
                sortKey = key;
                sortDir = key === 'name' ? 1 : -1;
            }
            draw(host);
        });
    }

    return {render};
})();
