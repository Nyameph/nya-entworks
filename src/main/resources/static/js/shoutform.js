/**
 * 列表式喊麦表单：文件列表 + 一个可选的全名框 + 评分 + 标签 + 按钮集合，
 * 两个入口共用这一份：
 *   ① 未归档页「添加文件」弹窗（两个按钮：迁移到未归档 / 归档）；
 *   ② 未归档页行内「归档」、已归档页行内「修改」（列表来自现扫的 variants）。
 *
 * 它是 {@code SongForm} 的<b>简化版</b>，砍掉的全是「喊麦不解析文件名」的直接后果
 * （口径见 docs/已完成/添加歌曲设计.md 与 shout/service/ShoutImportService.java）：
 *   - 没有作者 / 曲名 / 原曲名三框，改名就是<b>一个可选的全名框</b>；
 *   - 没有逐文件编号列 —— 喊麦的 {@code #} 是普通字符，不是版本号；
 *   - 没有「三框填满 = 重排」那条 rename 语义：名字框留空 = 保持原名，填了 = 全组改成它；
 *   - 没有 /group-files 那一套「库与磁盘逐文件对齐」（喊麦的源一律拒受管根，
 *     列表行天然就是现扫事实）—— 所以没有 gone / libMissing 两种行状态。
 *   骨架（预演 → 确认页 → 轮询、闸门措辞分行、按钮锁到底、无壳粘贴回退）与 SongForm 一致。
 *
 * **判定只为实时，认定仍在后端**：按钮可点不代表一定过 —— plan 那一关永远在。
 * 名字里的非法字符也<b>不在前端写第二份字符表</b>：交给 {@code GroupFileOps.requireMainName}
 * 一处判，plan 回一条 blockedReason，确认页上原样显示。
 *
 * 按钮 `need` 的三个预设（闸门全在 evaluate 里）：
 *   'changed'         ②：名字/评分/标签/文件集合任一变了 + 目标主名只有一个 + 批内无重名
 *   'import-migrate'  ①：有文件 + 目标主名只有一个 + 批内无重名 + 至少一个媒体文件
 *   'import-archive'  ①：上面全部 + 选了评分 +（requireTags 时）有标签
 *
 * 调用方通过 buttons[].run / .submit 注入接口：run(values) → 预演响应（ImportPlan），
 * submit(values) → taskId。
 */
const ShoutForm = (() => {

    /** 后端 role → 展示文字。角色词由后端下发（import-expand 的 role），前端不写扩展名表 */
    const ROLE_TEXT = {VIDEO: '视频', AUDIO: '音频', LYRIC: '歌词'};

    /** 文件集合的签名（剔除行不算）：添加 / 剔除都会让签名变化 */
    function signatureOf(files) {
        return files.filter(f => !f.excluded)
            .map(f => f.path || ((f.fromPartition || '') + '|' + f.fileName))
            .sort().join('\n');
    }

    /** 完整文件名 = mainName + suffix（原文大小写），suffix 用来拼预览（口径同后端 MediaExtensions.suffix） */
    function suffixOf(f) {
        return f.fileName.startsWith(f.mainName) ? f.fileName.slice(f.mainName.length) : '';
    }

    /**
     * 这一组现在的主名（只有编辑态才有：带 groupKey 的行就是它）。
     * 剔除的行也参与 —— 全剔除 + 补新文件时，那一组还在、名字也还是它。
     */
    function groupMainOf(state) {
        const inGroup = state.files.find(f => f.groupKey);
        return inGroup ? inGroup.mainName : null;
    }

    /** 这个文件落盘后的名字：名字框留空 = 原名，填了 = 那个名字（全组共用） */
    function targetMainOf(state, f) {
        const name = (state.newMainName || '').trim();
        if (name) {
            return name;
        }
        // 编辑态补进来的外部文件**跟着这一组走** —— 与后端 planEditForm 同一口径。
        // 让它保持自己的主名，预览与结果就不一致，而且会被「一次只处理一组」当场挡下
        // （用户只是给这一组补个歌词，不该被要求先想个新名字）
        if (!f.groupKey) {
            const groupMain = groupMainOf(state);
            if (groupMain) {
                return groupMain;
            }
        }
        return f.mainName;
    }

    /** 这一组现在在哪个分区（编辑态才有；待打分层是空串） */
    function groupDirOf(state) {
        const inGroup = state.files.find(f => f.groupKey);
        return inGroup ? (inGroup.fromPartition || '') : '';
    }

    /** 目标分区近似：改评分 = 所选分区；否则留在原分区（① 全部一起进目标目录） */
    function targetDirOf(state, f) {
        if (state.selScore != null) {
            return state.selDir || '';
        }
        // 与 targetMainOf 同理：补进来的文件跟着这一组的分区走，别算成「没有位置」
        if (!f.groupKey) {
            const groupDir = groupDirOf(state);
            if (groupDir) {
                return groupDir;
            }
        }
        return f.fromPartition || '';
    }

    // ------------------------------------------------------------------
    // 闸门：evaluate(state, need) → {ok, reasons[]}
    // ------------------------------------------------------------------

    function evaluate(state, need) {
        const reasons = [];
        const live = state.files.filter(f => !f.excluded);
        let goneFile = null;
        let dupPair = null;
        const seen = new Map();
        for (const f of live) {
            if (f.gone && !goneFile) {
                goneFile = f;
            }
            const key = targetDirOf(state, f) + '|' + targetMainOf(state, f) + suffixOf(f);
            const lower = key.toLowerCase();
            if (seen.has(lower) && !dupPair) {
                dupPair = [seen.get(lower), f];
            }
            seen.set(lower, f);
        }
        // 「一次只处理一组」：落盘后的主名只许有一个（与后端 plan 同一口径）。
        // 名字框填了时天然只有一个；没填就是各自保持原名，跨主名 = 这一次要加进两组
        const mains = [...new Set(live.map(f => targetMainOf(state, f)))];
        const hasMedia = live.some(f => f.role !== 'LYRIC');

        const tagsChanged = JSON.stringify(state.tags) !== JSON.stringify(state.initialTags)
            || (state.tagEditor && state.tagEditor.hasPending());
        const willRename = live.some(f => targetMainOf(state, f) !== f.mainName);
        const willScore = state.selScore != null;
        const filesChanged = signatureOf(state.files) !== state.initialSignature;
        // 只改标签也算「有要保存的」：标签没有自己的保存按钮（SongTag.editor 只收集），
        // 只能跟着这次提交一起走 —— 后端 apply 明说「moves 为空是合法输入」
        const unchanged = !(willScore || willRename || tagsChanged || filesChanged);

        if (need === 'changed') {
            if (goneFile) {
                reasons.push('「' + goneFile.fileName + '」在磁盘上已经不在这一组里了，先重新扫描');
            }
            if (unchanged) {
                reasons.push('评分、名字和标签都没变，没有要保存的');
            }
            if (mains.length > 1) {
                reasons.push(groupHint(mains));
            }
            if (dupPair) {
                reasons.push('两个文件会重名，' + dupPair[1].fileName + ' 得换个名字');
            }
        } else if (need === 'import-migrate' || need === 'import-archive') {
            // 空列表最优先：表单可以直接打开，文件是进去之后加的
            if (!live.length) {
                reasons.push('还没有选择要添加的文件');
            }
            if (mains.length > 1) {
                reasons.push(groupHint(mains));
            }
            if (dupPair) {
                reasons.push('两个文件会重名，' + dupPair[1].fileName + ' 得换个名字');
            }
            if (live.length && !hasMedia) {
                reasons.push('至少要有一个视频或音频文件');
            }
            if (need === 'import-archive') {
                if (state.selScore == null) {
                    reasons.push('还没选评分，归档必须给一个分');
                }
                // 与后端同一口径：**提交后**这一组一个标签都没有就拦（清空标签也算没标签）
                if (state.requireTags && !state.tags.length) {
                    reasons.push('归档前要先打标签');
                }
            }
        }
        return {ok: reasons.length === 0, reasons};
    }

    /** 「一次只处理一组」的原因（与后端 blockedReason 同一层意思，措辞按前端分行惯例压缩） */
    function groupHint(mains) {
        return '一次只能处理一组：这些文件属于 ' + mains.length + ' 组（'
            + mains.slice(0, 3).join('、') + (mains.length > 3 ? ' 等' : '')
            + '）。要么只留一组的文件，要么在上面填一个新名把它们合成一组';
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    function open(opts) {
        const state = {
            files: (opts.files || []).map(f => Object.assign({}, f)),
            newMainName: opts.newMainName || '',
            tags: (opts.tags || []).slice(),
            initialTags: (opts.tags || []).slice(),
            selScore: null,
            selDir: null,
            requireTags: !!opts.requireTags,
            // canAdd：表单内「＋ 添加文件」（① 与 ②③ 都开）
            canAdd: !!opts.canAdd,
            // removable：每行一个 × / ↩（剔除已归档文件走「移入冗余」）
            removable: !!opts.removable,
            blocked: (opts.blocked || []).slice(),
            adding: false,
            partitions: opts.partitions || [],
            // strictPartitions：「添加文件」（导入）那条路置 true —— 只列磁盘上真有的分区。
            // 导入是在新文件落盘之前定归档目标，缺档时后端直接拒（不自动建，见实现说明 8.4）；
            // 打分那一路是 false：缺的那档选它会现建出来
            strictPartitions: !!opts.strictPartitions,
            currentPartition: opts.currentPartition || null,
            currentScoreText: opts.currentScoreText || null,
            tagEditor: null,
            initialSignature: signatureOf(opts.files || [])
        };

        const m = UI.modal('<div class="modal modal-wide">'
            + '<div class="modal-head"><span>' + UI.esc(opts.title || '修改') + '</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body"></div>'
            + '<div class="modal-foot" id="shf-foot"></div>'
            + '</div>');
        m.box.querySelector('.modal-close').onclick = () => m.close();

        // ---- foot：按钮 + 关闭 + 原因区。**先建按钮再 renderForm** ——
        // 否则初始那次 refresh() 找不到按钮、禁用被跳过（「表单刚打开时不合格的按钮也能点」）
        const foot = m.box.querySelector('#shf-foot');
        foot.style.flexWrap = 'wrap';
        const reasonsEl = document.createElement('div');
        reasonsEl.style.cssText = 'flex-basis:100%;margin-top:4px';
        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'btn-plain';
        closeBtn.textContent = '关闭';
        closeBtn.onclick = () => m.close();
        foot.appendChild(closeBtn);
        for (const b of opts.buttons) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = b.danger ? 'btn-danger' : (b.primary ? 'btn-primary' : '');
            btn.textContent = b.label;
            btn.dataset.btn = b.id;
            btn.onclick = (ev) => runButton(b, ev.currentTarget);
            foot.insertBefore(btn, closeBtn);
        }
        foot.appendChild(reasonsEl);

        renderForm();

        function renderForm() {
            // 回到表单 = 上一轮的预演作废：把那一轮插进来的确认按钮摘掉（连同它闭包里那份 values）
            foot.querySelectorAll('[data-confirm]').forEach(el => el.remove());
            const body = m.box.querySelector('.modal-body');
            let html = '';
            if (opts.hint) {
                html += '<p class="muted small">' + UI.esc(opts.hint) + '</p>';
            }
            for (const blocked of state.blocked) {
                html += '<div class="hint hint-err">' + UI.esc(blocked) + '</div>';
            }

            // ---- 名字框（可选）。留空 = 保持原文件名；填了 = 全组改成它（扩展名不变）----
            html += '<div style="display:grid;gap:6px">'
                + '<span class="muted small">名字（可留空）</span>'
                + '<input type="text" id="shf-name" placeholder="留空 = 保持原文件名" value="'
                + UI.esc(state.newMainName) + '">'
                + '<span class="muted small">喊麦不解析文件名，这里改的是<b>完整名字</b>'
                + '（不带扩展名）。<span class="mono">#</span> 只是普通字符，不是版本号。</span>'
                + '</div>';

            // ---- 文件列表（canAdd 时带「＋ 添加文件」入口）----
            html += '<div class="row" style="justify-content:space-between;align-items:center;margin-top:10px">'
                + '<span class="muted small">文件'
                + (state.canAdd ? '（同名不同后缀自动联动，联动只在同一个源目录内）' : '')
                + '</span>'
                + (state.canAdd ? '<button id="shf-add-files">＋ 添加文件</button>' : '')
                + '</div>'
                + '<div id="shf-filebox" style="margin-top:6px">' + filesInner() + '</div>';

            // ---- 评分区 ----
            // 打分那一路把没建的档也画出来（onDisk=false，选它落盘时自动建，2026-09-30 起）；
            // 导入那一路只画真在盘上的，见 state.strictPartitions
            const scoreOptions = state.partitions.filter(
                p => !state.strictPartitions || p.onDisk);
            if (scoreOptions.length) {
                html += '<p class="muted small" style="font-weight:600;margin-top:10px">评分</p>'
                    + '<p class="muted small">'
                    + (state.currentScoreText ? '当前：' + UI.esc(state.currentScoreText) + '。' : '')
                    + '不选分值就是不改评分；再点一次已选中的分可取消。</p>'
                    + '<div class="row score-pad" id="shf-scores">'
                    + scoreOptions.map(p => '<button class="score-btn'
                        + (p.dirName === (state.selDir || state.currentPartition) ? ' on' : '')
                        + (p.dirName === state.currentPartition ? ' disabled' : '') + '"'
                        + ' data-score="' + p.score + '" data-dir="' + UI.esc(p.dirName) + '"'
                        // 还没建的那档说清楚它跟别的不一样：点了会在归档根下现建一个目录
                        + (p.onDisk ? '' : ' title="这一档还没建，选它会建出 '
                            + UI.esc(p.dirName) + '（评语回头自己加）"')
                        + '>' + UI.esc(p.display) + '</button>').join('')
                    + '</div>';
            }

            // ---- 标签区 ----
            if (opts.showTags) {
                html += '<p class="muted small" style="font-weight:600;margin-top:10px">标签</p>'
                    + (state.requireTags
                        ? '<p class="muted small">归档前要先有标签，不打标签不能归档。</p>' : '')
                    + '<div id="shf-tags"></div>';
            }
            body.innerHTML = html;
            bindForm();
        }

        /** 文件区内容：空列表给一句引导（文件是进表单之后加的），非空画表格 */
        function filesInner() {
            if (!state.files.length) {
                return UI.empty(state.canAdd
                    ? '还没有文件，点「＋ 添加文件」选择要导入的文件。'
                    : '没有文件。');
            }
            return '<div style="overflow-x:auto"><table class="small" id="shf-files" '
                + 'style="min-width:700px">'
                + '<thead><tr>'
                + '<th style="width:72px">类型</th><th style="width:38%">文件</th>'
                + '<th style="width:38%">改名后</th><th></th>'
                + '</tr></thead><tbody>' + fileRows() + '</tbody></table></div>';
        }

        function fileRows() {
            return state.files.map((f, i) => {
                const preview = UI.esc(targetMainOf(state, f) + suffixOf(f));
                let status = '';
                if (f.gone) {
                    status = '<span class="tag tag-err">磁盘上已经不在</span>';
                } else if (f.excluded) {
                    status = '<span class="tag tag-warn">将移入冗余</span>';
                } else if (f.fromPartition) {
                    status = '<span class="tag" title="这一组已在库里（' + UI.esc(f.fromPartition)
                        + '），提交时会跟着改键">已归档</span>';
                }
                const remove = state.removable
                    ? '<button class="shf-remove" data-i="' + i + '" title="' + removeTitle(f) + '">'
                        + (f.excluded ? '↩' : '×') + '</button>'
                    : '';
                // 在资源管理器中显示：壳只放行**它自己亲手交出来的**那些路径（grantedPaths），
                // 所以只对来自 pickFiles 的行画（f.picked）；粘贴进来的与磁盘现扫的行一律不画
                const show = f.path && f.picked && hasShowItem()
                    ? '<button class="shf-show" data-i="' + i
                        + '" title="在资源管理器中显示这个文件">位置</button>'
                    : '';
                return '<tr' + (f.gone || f.excluded ? ' style="opacity:.55"' : '') + '>'
                    + '<td><span class="tag">' + UI.esc(ROLE_TEXT[f.role] || f.role) + '</span></td>'
                    + '<td class="mono" title="' + UI.esc(f.path || f.fileName) + '">' + UI.esc(f.fileName) + '</td>'
                    + '<td class="mono" data-prev="' + i + '">' + preview + '</td>'
                    + '<td>' + status + show + remove + '</td>'
                    + '</tr>';
            }).join('');
        }

        function bindForm() {
            const body = m.box.querySelector('.modal-body');

            body.querySelector('#shf-name').oninput = (e) => {
                state.newMainName = e.target.value;
                refresh();
            };

            body.querySelectorAll('#shf-scores button').forEach(btn => btn.onclick = () => {
                if (btn.disabled) {
                    return;
                }
                // 再点一次已选中的分 = 取消（回到「不改评分」，回显回落到当前分区）
                if (state.selDir === btn.dataset.dir
                    && String(state.selScore) === btn.dataset.score) {
                    state.selScore = null;
                    state.selDir = null;
                    body.querySelectorAll('#shf-scores button').forEach(b => b.classList.remove('on'));
                    refresh();
                    return;
                }
                body.querySelectorAll('#shf-scores button').forEach(b => b.classList.remove('on'));
                btn.classList.add('on');
                state.selScore = Number(btn.dataset.score);
                state.selDir = btn.dataset.dir;
                refresh();
            });

            if (opts.showTags) {
                state.tagEditor = SongTag.editor(body.querySelector('#shf-tags'), state.tags, refresh);
            }

            const addBtn = body.querySelector('#shf-add-files');
            if (addBtn) {
                addBtn.onclick = addFiles;
            }

            bindTable(body);
            refresh();
        }

        /** 只重画文件区（增删行时用），不重建名字框 —— 免得输入焦点丢失 */
        function drawFiles() {
            const body = m.box.querySelector('.modal-body');
            body.querySelector('#shf-filebox').innerHTML = filesInner();
            bindTable(body);
        }

        /** × 的提示按文件来源分两种 */
        function removeTitle(f) {
            if (f.excluded) {
                return '恢复：不剔除，照常参与本次保存';
            }
            if (f.fromPartition) {
                return '剔除：提交后文件移入「冗余」文件夹并标记冗余（shout_id=0）';
            }
            return '本次不处理：只从这次列表移除，文件留在原处不动';
        }

        function bindTable(body) {
            body.querySelectorAll('.shf-show').forEach(btn => btn.onclick = async () => {
                const f = state.files[Number(btn.dataset.i)];
                const r = await window.NyaEntworksShell.showItem(f.path);
                // 取消与成功都不出声；拒绝（壳不认这个路径 / 文件已不在）给人话
                if (!r.ok && r.message && r.message !== '已取消') {
                    UI.err(r.message);
                }
            });
            body.querySelectorAll('.shf-remove').forEach(btn => btn.onclick = async () => {
                const i = Number(btn.dataset.i);
                const f = state.files[i];
                // 已归档文件：× = 标记剔除（可再点 ↩ 恢复），提交时才真正移入「冗余」
                if (f.fromPartition) {
                    if (!f.excluded && !await UI.confirm('「' + f.fileName + '」提交后将从这一组剔除：'
                            + '文件会移入「冗余」文件夹、库里标记为冗余（shout_id=0），'
                            + '不再属于这一组。确定剔除？',
                        {title: '剔除已归档文件', okText: '剔除'})) {
                        return;
                    }
                    f.excluded = !f.excluded;
                    drawFiles();
                    refresh();
                    return;
                }
                // 其余（新选进来的外部文件 / 待打分层的行）：只从这次列表移除，磁盘一个文件都不动。
                // 但**同组行「本次不处理」是有后果的**：如果这次还要改名，它会留在旧名字上，
                // 下次扫描就成了另一组（喊麦按文件名归组）—— 所以这两种情形要问一句
                if (f.groupKey || willRenameNow()) {
                    if (!await UI.confirm('「' + f.fileName + '」本次不处理：文件留在原处不动。'
                            + (willRenameNow()
                                ? '这次还要改名，它会留在旧名字上，下次扫描就成了另一组。确定？'
                                : '喊麦按文件名归组，下次扫描它还会出现在这一组里。确定？'),
                        {title: '本次不处理', okText: '确定'})) {
                        return;
                    }
                }
                state.files.splice(i, 1);
                drawFiles();
                refresh();
            });
        }

        /** 这次提交会不会改名（按非剔除行判） */
        function willRenameNow() {
            return state.files.some(f => !f.excluded && targetMainOf(state, f) !== f.mainName);
        }

        /** 刷新预览、按钮可用性与按按钮分行的原因 */
        function refresh() {
            const body = m.box.querySelector('.modal-body');
            state.files.forEach((f, i) => {
                const cell = body.querySelector('[data-prev="' + i + '"]');
                if (cell) {
                    cell.textContent = targetMainOf(state, f) + suffixOf(f);
                }
            });

            let lines = '';
            for (const b of opts.buttons) {
                const gate = evaluate(state, b.need);
                const btnEl = foot.querySelector('[data-btn="' + b.id + '"]');
                if (btnEl) {
                    btnEl.disabled = !gate.ok;
                    btnEl.title = gate.reasons.join('\n');
                }
                if (!gate.ok && lines.split('<').length <= 3) {
                    lines += '<div class="muted small">［' + UI.esc(b.label) + '］'
                        + UI.esc(gate.reasons[0] || '') + '</div>';
                }
            }
            reasonsEl.innerHTML = lines;
        }

        /** 提交给 run / submit 的快照。标签先 flush（输入框里打了还没回车的内容也算数） */
        function snapshot() {
            if (state.tagEditor) {
                state.tagEditor.flush();
            }
            const tagsChanged = JSON.stringify(state.tags) !== JSON.stringify(state.initialTags);
            return {
                files: state.files.map(f => Object.assign({}, f)),
                newMainName: (state.newMainName || '').trim(),
                tags: state.tags.slice(),
                tagsChanged,
                selScore: state.selScore,
                selDir: state.selDir,
                filesChanged: signatureOf(state.files) !== state.initialSignature,
                requireTags: state.requireTags,
                willRename: willRenameNow()
            };
        }

        // ---- 表单内添加文件（canAdd 模式）----
        // 选择对话框的默认目录：列表已有文件 → 最后一个「有磁盘路径」的文件所在文件夹；
        // 列表为空（全新新增）→ 不传 defaultPath，由壳的主进程回落到持久化的「上次新增目录」

        /** 目录部分（两种分隔符都认）；取不到返回空串 */
        function dirOf(path) {
            const i = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
            return i > 0 ? path.slice(0, i) : '';
        }

        /**
         * 路径比对用的键：分隔符统一成 `\`、折叠大小写。
         * 用来认「后端回显的这个 path 是不是壳刚亲手交出去的那一个」—— 后端 import-expand
         * 回显的是 toRealPath()（挡符号链接 / 目录联接，Windows 上还带盘上的真实大小写）。
         * 判错的代价只有一种：**少画**一个「位置」按钮，不会误放行（真正的放行判定在壳里）。
         */
        function pathKey(p) {
            return String(p || '').replace(/\//g, '\\').toLowerCase();
        }

        function defaultImportDir() {
            for (let i = state.files.length - 1; i >= 0; i--) {
                if (state.files[i].path) {
                    return dirOf(state.files[i].path);
                }
            }
            return '';
        }

        function hasShell() {
            return !!(window.NyaEntworksShell && typeof window.NyaEntworksShell.pickFiles === 'function');
        }

        /** 壳的「在资源管理器中显示」在不在（老壳没有这个能力 —— 改过 preload 必须重启壳） */
        function hasShowItem() {
            return !!(window.NyaEntworksShell && typeof window.NyaEntworksShell.showItem === 'function');
        }

        /** 无壳时的路径录入框：每行一个绝对路径（资源管理器 Shift+右键「复制文件地址」粘进来） */
        function pastePathsModal() {
            return new Promise((resolve) => {
                const pm = UI.modal('<div class="modal">'
                    + '<div class="modal-head"><span>粘贴路径</span>'
                    + '<button class="modal-close" type="button">×</button></div>'
                    + '<div class="modal-body">'
                    + '<p class="muted small">每行一个文件的绝对路径。在资源管理器里 Shift+右键文件'
                    + ' →「复制文件地址」，一行粘一个。</p>'
                    + '<textarea id="shf-paste" rows="8" style="width:100%" '
                    + 'placeholder="F:\\NetdiskDownload\\某段喊麦.mp4&#10;F:\\NetdiskDownload\\某段喊麦.lrc"></textarea>'
                    + '</div>'
                    + '<div class="modal-foot">'
                    + '<button type="button" class="btn-plain" id="shf-paste-cancel">取消</button>'
                    + '<button type="button" class="btn-primary" id="shf-paste-ok">添加</button>'
                    + '</div></div>');
                const finish = (val) => {
                    pm.close();
                    resolve(val);
                };
                pm.box.querySelector('.modal-close').onclick = () => finish(null);
                pm.box.querySelector('#shf-paste-cancel').onclick = () => finish(null);
                pm.box.querySelector('#shf-paste-ok').onclick = () => {
                    const paths = pm.box.querySelector('#shf-paste').value.split('\n')
                        .map(s => s.trim().replace(/^"+|"+$/g, ''))
                        .filter(Boolean);
                    finish(paths.length ? paths : null);
                };
            });
        }

        /** 取一批源路径（壳多选 / 粘贴），取消返回 null；fromShell = 这批路径是不是壳亲手给的 */
        async function pickImportPaths() {
            if (hasShell()) {
                const picked = await window.NyaEntworksShell.pickFiles({
                    title: '选择要添加的喊麦文件',
                    defaultPath: defaultImportDir()
                });
                if (!picked.ok) {
                    if (picked.message && picked.message !== '已取消') {
                        UI.err(picked.message);
                    }
                    return null;
                }
                return {paths: picked.paths, fromShell: true};
            }
            const pasted = await pastePathsModal();
            return pasted ? {paths: pasted, fromShell: false} : null;
        }

        /** 「＋ 添加文件」：选路径 → import-expand 联动展开 → 追加进列表（去重、提示累积） */
        async function addFiles() {
            if (state.adding) {
                return;
            }
            state.adding = true;
            try {
                const picked = await pickImportPaths();
                if (!picked) {
                    return;
                }
                const paths = picked.paths;
                const showable = new Set(picked.fromShell ? paths.map(pathKey) : []);
                const groups = await Api.post('/api/shout/import-expand', {paths});
                for (const g of groups) {
                    // 受管根命中 / 认不出的扩展名：追加到顶部红字区（累积，不覆盖既有提示）
                    if (g.blockedReason) {
                        state.blocked.push(g.blockedReason);
                        continue;
                    }
                    for (const skipped of g.skipped || []) {
                        state.blocked.push(skipped);
                    }
                    for (const f of g.files) {
                        if (state.files.some(x => x.path === f.path)) {
                            continue;   // 按绝对路径去重：同一批反复选 / 与已有行重复都不进
                        }
                        state.files.push({
                            fileName: f.fileName,
                            role: f.role,
                            mainName: f.mainName,
                            path: f.path,
                            picked: showable.has(pathKey(f.path)),
                            fromPartition: '',
                            groupKey: ''      // 新选进来的文件不属于任何既有组
                        });
                    }
                }
                renderForm();
            } catch (e) {
                UI.err(e.message);
            } finally {
                state.adding = false;
            }
        }

        // ---- 预演 → 确认页 → 执行 → 轮询 ----
        async function runButton(b, btnEl) {
            const values = snapshot();
            let planResp;
            try {
                // 预演期间禁用按钮，避免连点重复发请求
                await UI.withBusy(btnEl, '预演中…', async () => {
                    planResp = await b.run(values);
                });
            } catch (e) {
                UI.err(e.message);
                return;
            }
            if (!planResp) {
                return;   // run 里主动取消（如迁移前的提醒选了「否」），静默回到表单
            }
            drawConfirm(b, planResp, values);
        }

        /** 确认页：旧名 → 新名、源目录 → 目标目录、跨卷标记、blockedReason 红字（整批不执行） */
        function drawConfirm(b, planResp, values) {
            const body = m.box.querySelector('.modal-body');
            const plan = planResp.plan;
            const cross = planResp.crossVolume || [];
            let html = '<p class="mono small modal-plan-title">' + UI.esc(plan.mainName);
            if (plan.toMainName && plan.toMainName !== plan.mainName) {
                html += ' <span class="muted">→</span> ' + UI.esc(plan.toMainName);
            }
            if (plan.toPartition) {
                html += ' <span class="muted">→</span> ' + UI.esc(plan.toPartition);
            }
            html += '</p>';

            if (plan.blockedReason) {
                html += '<div class="hint hint-err">' + UI.esc(plan.blockedReason) + '</div>';
            }
            const blockedMoves = plan.moves.filter(mv => mv.blockedReason);
            if (plan.moves.length) {
                const first = plan.moves[0];
                const parentOf = p => p.replace(/[\\/][^\\/]*$/, '');
                html += '<p class="muted small mono">' + UI.esc(parentOf(first.fromPath))
                    + ' <span class="muted">→</span> ' + UI.esc(parentOf(first.toPath)) + '</p>';
                html += '<table class="small"><thead><tr><th style="width:44%">文件</th>'
                    + '<th style="width:34%">会变成</th><th></th></tr></thead><tbody>';
                for (const mv of plan.moves) {
                    html += '<tr><td class="mono">' + UI.esc(mv.fileName) + '</td><td class="mono">'
                        + (mv.blockedReason
                            ? '<span class="tag tag-err">' + UI.esc(mv.blockedReason) + '</span>'
                            : UI.esc(mv.toFileName));
                    if (!mv.blockedReason && cross.includes(mv.fileName)) {
                        html += '</td><td><span class="tag tag-warn">跨卷，需拷贝，可能较慢</span></td></tr>';
                    } else {
                        html += '</td><td></td></tr>';
                    }
                }
                html += '</tbody></table>';
            }
            if (blockedMoves.length) {
                // 整批不执行，不是跳过继续：搬一半这一组会裂成两半散在两个地方
                html += '<div class="hint hint-err">有 ' + blockedMoves.length + ' 个文件动不了，'
                    + '整批不执行 —— 只搬一部分的话，这一组会裂成两半散在两个地方。</div>';
            }
            // 0 条搬动是**合法输入**（名字与位置都没变，最典型的就是「只改标签」）
            if (!plan.blockedReason && !plan.moves.length) {
                html += '<div class="hint">没有文件要搬动（名字与位置都没变）'
                    + (values.tagsChanged ? '，这次只写标签。' : '。') + '</div>';
            }
            body.innerHTML = html;

            // 旧的确认按钮先摘掉：连点两个动作按钮会重画这一页，
            // 留着上一轮那个按钮的话，它的 label 与闭包里那份 values 都是上一轮的
            foot.querySelectorAll('[data-confirm]').forEach(el => el.remove());

            const runable = !plan.blockedReason && !blockedMoves.length;
            if (runable) {
                const runBtn = document.createElement('button');
                runBtn.type = 'button';
                runBtn.className = b.danger ? 'btn-danger' : 'btn-primary';
                runBtn.textContent = '确认' + b.label;
                runBtn.dataset.confirm = b.id;
                foot.insertBefore(runBtn, foot.firstChild);
                runBtn.onclick = (ev) => execute(b, values, ev.currentTarget);
            }
        }

        function execute(b, values, btnEl) {
            const body = m.box.querySelector('.modal-body');
            // 提交 → 轮询到终态期间全程禁用按钮。TaskPoll.wait 是「起轮询就返回 stop()」、
            // 不是 Promise，直接调用会让 withBusy 立刻恢复按钮 —— 任务还在跑、用户再点一次
            // 就重复提交（同一批文件动两遍）。用 Promise 接住终态，按钮保持禁用到底。
            // 除了被点的那一个，**整条 foot 上的动作按钮一起锁**：任务是异步的，
            // 期间点另一个动作按钮就会又提交一个任务、同一批文件动两遍。
            // 失败回退时 renderForm() 会按闸门重新算 disabled。「关闭」不动
            foot.querySelectorAll('[data-btn], [data-confirm]').forEach(el => { el.disabled = true; });
            UI.withBusy(btnEl, '提交中…', () => new Promise((resolve) => {
                const settled = () => resolve();
                Promise.resolve()
                    .then(() => b.submit(values))
                    .then((taskId) => {
                        body.innerHTML = UI.spinner('执行中…');
                        TaskPoll.wait(taskId, {
                            onResult: async (result) => {
                                try {
                                    UI.ok('完成');
                                    m.close();
                                    if (opts.onDone) {
                                        await opts.onDone(result, values, b);
                                    }
                                    return true;
                                } finally {
                                    settled();
                                }
                            },
                            onFail: (error) => {
                                UI.err(error);
                                settled();
                                renderForm();   // 回到表单让人改完再试
                            }
                        });
                    })
                    .catch((e) => {
                        UI.err(e.message);
                        settled();
                        renderForm();
                    });
            })).catch(() => {});   // withBusy 只透传 submit 的同步异常，上面已 UI.err
        }
    }

    /**
     * ② 的请求体：files 按 groupKey（分区|主名）归回 groups；
     * 剔除的已归档行带 exclude 标记留在原组里（后端据此移入「冗余」并置 shout_id=0）；
     * 「＋ 添加文件」进来的外部文件（groupKey 为空、带 path）走 extraFiles；
     * 标签只在变了时送（null = 后端不动标签）。
     *
     * <p>喊麦没有逐文件编号，所以每项只有 fileName（+ exclude）；也没有 targetPartition ——
     * 评分分区由 score 唯一确定，多一个字段就多一处可能打架（见后端 EditFormRequest）。
     */
    function editFormBody(v) {
        const byGroup = new Map();
        const extraFiles = [];
        for (const f of v.files) {
            if (!f.groupKey) {
                if (!f.excluded && f.path) {
                    extraFiles.push({sourcePath: f.path});
                }
                continue;
            }
            if (!byGroup.has(f.groupKey)) {
                byGroup.set(f.groupKey, {
                    partition: f.fromPartition || null,
                    mainName: f.mainName,
                    files: []
                });
            }
            byGroup.get(f.groupKey).files.push(f.excluded
                ? {fileName: f.fileName, exclude: true}
                : {fileName: f.fileName});
        }
        return {
            groups: [...byGroup.values()],
            newMainName: v.newMainName || null,
            score: v.selScore,
            tags: v.tagsChanged ? v.tags : null,
            extraFiles
        };
    }

    /**
     * ① 的请求体：源路径 + 新全名 + 目标（由按钮定：staging 时 score 必须为 null）。
     * 标签只在非空时送 —— 送空数组的含义是「提交后没有标签」，那是另一件事。
     */
    function importBody(v, target, score) {
        return {
            files: v.files.filter(f => !f.excluded && f.path).map(f => ({sourcePath: f.path})),
            newMainName: v.newMainName || null,
            target,
            score,
            tags: v.tags.length ? v.tags : null
        };
    }

    return {open, editFormBody, importBody};
})();
