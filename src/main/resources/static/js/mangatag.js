/**
 * 标签页（文档 4.5）。
 *
 * 标签独立成表且跨实体通用（manga_tag_ref 用 target_type + target_id 指目标），
 * 所以值得单独一页。这一页的关键约束只有一条，但贯穿所有操作：
 *
 *   归档目录的标签来自目录名的【…】块，目录名才是权威。
 *   库里改了、目录名没改，下次同步会把旧标签原样建回来。
 *
 * 所以重命名/合并/删除都会**连带改归档目录名**。目录移动不可回滚，因此每个操作
 * 都先拿预演清单（会改哪些目录名）给人看，确认后才执行；有一个目录改不了就整批
 * 不做 —— 漏改一个，下次同步旧标签就冒出来了，那是最难查的一类现象。
 */
const MangaTagPage = (() => {

    let usages = [];
    let showUnusedOnly = false;
    let searchQuery = '';
    let searchTimer = null;
    /** 输入法拼音组合中：composition 期间不触发搜索，等上屏完成再搜，否则每敲一
     *  个字母就重渲染，把输入法的组合态打断，中文根本输不进去 */
    let composing = false;
    let page = 1;
    let filtered = [];
    const PAGE_SIZE = 50;

    // 语义大类（与后端 MangaEhTagFetcher.MAJOR_CATEGORIES 同序），「新建/修改」弹窗的下拉
    const MAJOR_CATEGORIES = ['人物类型', '关系', '服饰', '玩法', '地点', '作品类型', '风格', '语言'];
    // e-hentai 命名空间（值 + 中文名），空 = 目录标签（无命名空间）
    const NAMESPACES = [
        ['female', 'female · 女性'],
        ['male', 'male · 男性'],
        ['mixed', 'mixed · 混合'],
        ['other', 'other · 其他'],
        ['location', 'location · 地点'],
        ['language', 'language · 语言'],
        ['reclass', 'reclass · 重新分类']
    ];

    /** 命名空间 → 中文名（供搜索匹配；未知命名空间返回原值，空返回空串） */
    function namespaceLabel(ns) {
        if (!ns) {
            return '';
        }
        const hit = NAMESPACES.find(([v]) => v === ns);
        return hit ? hit[1] : ns;
    }

    /** 搜索匹配：中文名 / eh 英文名 / 命名空间（原始值或中文）/ 大类 / 描述 任一命中 */
    function matchesSearch(u) {
        const q = searchQuery.trim().toLowerCase();
        if (!q) {
            return true;
        }
        const t = u.tag;
        const hay = [t.tagName, t.enName, t.namespace, namespaceLabel(t.namespace),
            t.majorCategory, t.description]
            .filter(Boolean).join(' ').toLowerCase();
        return hay.includes(q);
    }

    async function render(host) {
        host.innerHTML = UI.spinner();
        try {
            usages = await Api.get('/api/manga/tags');
        } catch (e) {
            host.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }

        const unused = usages.filter(u => u.archiveUnitCount === 0 && u.mangaCount === 0);
        let html = '<div class="hint">标签是从归档目录名的 <span class="mono">【…】</span> 块里扫出来的，'
            + '<strong>目录名是权威</strong>。所以这里的重命名、合并、删除都会连带改归档目录名 ——'
            + '只改库的话，下次同步会把旧标签建回来。</div>';

        html += renderCreateCard();

        html += '<div class="card"><div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">标签 <span class="muted small">' + usages.length + ' 个</span></h2>'
            + '<div class="row">'
            + '<input type="text" id="tag-search" placeholder="搜索标签/英文/命名空间/大类/描述…"'
            + (searchQuery ? ' value="' + UI.esc(searchQuery) + '"' : '') + ' style="width:210px">'
            + '<button id="eh-sync-btn" class="btn-primary">从 e-hentai 同步</button>'
            + '<label class="small muted"><input type="checkbox" id="only-unused"'
            + (showUnusedOnly ? ' checked' : '') + '> 只看未使用（' + unused.length + '）</label>'
            + '<button id="cleanup-btn" class="btn-danger"' + (unused.length ? '' : ' disabled')
            + '>清理未使用</button></div></div>';

        filtered = (showUnusedOnly ? unused : usages).filter(matchesSearch);
        const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
        if (page < 1 || page > totalPages) {
            page = 1;
        }
        const pageItems = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
        if (!filtered.length) {
            html += UI.empty(searchQuery.trim()
                ? '没有匹配「' + UI.esc(searchQuery.trim()) + '」的标签。'
                : (usages.length ? '没有未使用的标签。'
                    : '库里还没有标签。标签是同步归档目录时从目录名里扫出来的，先去「归档作者」页同步。'));
        } else {
            html += '<table><thead><tr>'
                + '<th style="width:12%">标签</th><th style="width:7%">命名空间</th>'
                + '<th style="width:10%">eh 英文名</th><th style="width:8%">大类</th>'
                + '<th>描述</th><th style="width:7%">来源</th>'
                + '<th style="width:7%">归档作者</th><th style="width:6%">漫画</th>'
                + '<th>备注</th><th style="width:24%"></th>'
                + '</tr></thead><tbody>';
            for (const u of pageItems) {
                html += renderRow(u);
            }
            html += '</tbody></table>';
            html += '<div class="pager" id="tag-pager"></div>';
            // manga_data 还没有写入点，漫画列必然是 0，说清楚免得被当成数据丢了
            html += '<p class="muted small">「漫画」一列现在恒为 0：漫画本身还没入库'
                + '（文档 4.7），标签表那一侧已经能用，只是还没有东西挂上去。</p>';
        }
        html += '</div>';
        html += '<div id="plan-box"></div>';
        host.innerHTML = html;
        bind(host);
    }

    function renderRow(u) {
        const t = u.tag;
        const unused = u.archiveUnitCount === 0 && u.mangaCount === 0;
        const source = t.source === 'EHENTAI' ? '词典'
            : (t.source === 'FOLDER' ? '目录' : (t.source || '—'));
        return '<tr data-id="' + t.id + '" data-name="' + UI.esc(t.tagName) + '">'
            + '<td><span class="tag' + (unused ? '' : ' tag-ok') + '">' + UI.esc(t.tagName) + '</span></td>'
            + '<td class="mono small muted">' + UI.esc(t.namespace || '—') + '</td>'
            + '<td class="mono small">' + UI.esc(t.enName || '') + '</td>'
            + '<td class="small">' + UI.esc(t.majorCategory || '') + '</td>'
            + '<td class="small muted" title="' + UI.esc(t.description || '') + '">'
            + UI.esc(truncate(t.description, 40)) + '</td>'
            + '<td class="small muted">' + UI.esc(source) + '</td>'
            + '<td>' + (u.archiveUnitCount
                ? '<a href="#" class="act-units">' + u.archiveUnitCount + '</a>'
                : '<span class="muted">0</span>') + '</td>'
            + '<td class="muted">' + u.mangaCount + '</td>'
            + '<td class="small muted">' + UI.esc(t.remark || '') + '</td>'
            + '<td class="row">'
            + '<button class="act-edit">修改</button>'
            + '<button class="act-merge">合并到…</button>'
            + '<button class="btn-danger act-delete">删除</button>'
            + '</td></tr>';
    }

    /** 超长文本截断，悬停靠 title 属性看全量（描述通常是一整句） */
    function truncate(s, n) {
        if (!s) {
            return '';
        }
        return s.length > n ? s.slice(0, n) + '…' : s;
    }

    /** 从 e-hentai 拉取词典标签入库，无法判断大类的弹出来让用户手动填 */
    async function ehSync(host, btn) {
        if (!await UI.confirm('从 e-hentai 拉取词典标签并入库？\n\n'
            + '会访问 raw.githubusercontent.com 下载 7 个命名空间的标签，'
            + '按（命名空间, 中文）比对：缺的补、已有的刷新。可能需要十几秒。',
            {title: '从 e-hentai 同步标签', okText: '同步', danger: false})) {
            return;
        }
        await UI.withBusy(btn, '提交中…', async () => {
            try {
                const taskId = await Api.post('/api/manga/tags/eh-sync');
                TaskPoll.wait(taskId, {
                    onResult: async (result) => {
                        UI.ok('新增 ' + result.inserted + ' / 更新 ' + result.updated
                            + '（共拉到 ' + result.fetched + ' 条）');
                        if (result.undetermined && result.undetermined.length) {
                            askMajorCategory(host, result.undetermined);
                        }
                        await render(host);
                        return true;
                    },
                    onFail: (error) => UI.err(error)
                });
            } catch (e) {
                UI.err(e.message);
            }
        });
    }

    /** 手动填大类的模态：每个无法判断的标签一个下拉，默认兜底值 */
    function askMajorCategory(host, undetermined) {
        const rows = undetermined.map((u, i) =>
            '<tr>'
            + '<td>' + UI.esc(u.tagName) + '</td>'
            + '<td class="mono small">' + UI.esc(u.namespace) + '</td>'
            + '<td class="small muted">' + UI.esc(u.enName || '') + '</td>'
            + '<td class="small muted">' + UI.esc(u.category || '') + '</td>'
            + '<td><select data-idx="' + i + '">'
            + MAJOR_CATEGORIES.map(c => '<option value="' + c + '"'
                + (c === u.fallbackCategory ? ' selected' : '') + '>' + c + '</option>').join('')
            + '</select></td></tr>').join('');

        const m = UI.modal('<div class="modal modal-wide">'
            + '<div class="modal-head"><span>无法判断大类的标签：手动选大类</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<p class="small muted">以下 ' + undetermined.length
            + ' 个标签无法自动判断大类，请各选一个大类（默认是猜的兜底值）。'
            + '跳过则这次不入库它们。</p>'
            + '<table><thead><tr><th>中文</th><th>命名空间</th><th>英文</th>'
            + '<th>细分类</th><th>大类</th></tr></thead><tbody>'
            + rows + '</tbody></table></div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" data-act="cancel">跳过</button>'
            + '<button type="button" class="btn-primary" data-act="ok">入库</button>'
            + '</div></div>');

        m.box.querySelector('.modal-close').onclick = () => m.close();
        m.box.querySelector('[data-act="cancel"]').onclick = () => m.close();
        m.box.querySelector('[data-act="ok"]').onclick = async (ev) => {
            const fills = undetermined.map((u, i) => ({
                namespace: u.namespace,
                tagName: u.tagName,
                enName: u.enName,
                category: u.category,
                description: u.description,
                majorCategory: m.box.querySelector('select[data-idx="' + i + '"]').value
            }));
            await UI.withBusy(ev.target, '入库中…', async () => {
                try {
                    const n = await Api.post('/api/manga/tags/eh-sync/undetermined', {fills});
                    UI.ok('已入库 ' + n + ' 个标签');
                    m.close();
                    await render(host);
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };
    }

    function renderCreateCard() {
        return '<div class="card"><div class="row" style="justify-content:space-between">'
            + '<h2 style="margin:0">新建标签</h2>'
            + '<button class="btn-primary" id="open-create">新建标签</button>'
            + '</div>'
            + '<p class="muted small">新建出来的标签在被某个目录名用到之前一直是「未使用」——'
            + '真正给归档目录打标签的方式是改目录名，把标签写进 <span class="mono">【…】</span> 里。'
            + '这个入口的用处是先建好、填上大类与描述（可选，作归类参考）。</p></div>';
    }

    /** 命名空间下拉：空 = 目录标签（无命名空间） */
    function namespaceOptions(selected) {
        return '<option value="">（无 · 目录标签）</option>'
            + NAMESPACES.map(([v, label]) => '<option value="' + v + '"'
                + (v === selected ? ' selected' : '') + '>' + label + '</option>').join('');
    }

    /** 新建标签弹窗：标签名 + 命名空间 + 大类下拉 + 描述 + 备注 */
    function openCreateModal(host) {
        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>新建标签</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<p class="muted small">新建出来的标签在被某个目录名用到之前一直是「未使用」。'
            + '命名空间、大类与描述可选，先填上便于后续归类。</p>'
            + '<div style="margin-top:10px">'
            + '<input type="text" id="new-tag" placeholder="标签名（不能有空格与【】）" style="width:100%">'
            + '</div>'
            + '<div style="margin-top:8px">'
            + '<select id="new-ns">' + namespaceOptions('') + '</select>'
            + '</div>'
            + '<div style="margin-top:8px">'
            + '<select id="new-major">'
            + '<option value="">（不指定大类）</option>'
            + MAJOR_CATEGORIES.map(c => '<option value="' + c + '">' + c + '</option>').join('')
            + '</select>'
            + '</div>'
            + '<div style="margin-top:8px">'
            + '<input type="text" id="new-desc" placeholder="描述" style="width:100%">'
            + '</div>'
            + '<div style="margin-top:8px">'
            + '<input type="text" id="new-remark" placeholder="备注" style="width:100%">'
            + '</div>'
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" data-act="cancel">取消</button>'
            + '<button type="button" class="btn-primary" data-act="ok">新建</button>'
            + '</div></div>');

        m.box.querySelector('.modal-close').onclick = () => m.close();
        m.box.querySelector('[data-act="cancel"]').onclick = () => m.close();
        m.box.querySelector('[data-act="ok"]').onclick = async (ev) => {
            const tagName = m.box.querySelector('#new-tag').value.trim();
            if (!tagName) {
                UI.err('请填标签名');
                return;
            }
            await UI.withBusy(ev.target, '新建中…', async () => {
                try {
                    await Api.post('/api/manga/tags', {
                        tagName,
                        remark: m.box.querySelector('#new-remark').value.trim(),
                        namespace: m.box.querySelector('#new-ns').value,
                        majorCategory: m.box.querySelector('#new-major').value,
                        description: m.box.querySelector('#new-desc').value.trim()
                    });
                    UI.ok('已新建');
                    m.close();
                    await render(host);
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };
    }

    /** 修改标签弹窗：与新建同款字段，初值取当前行。改标签名走改名预演，元数据直接落库 */
    function openEditModal(host, u) {
        const t = u.tag;
        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>修改标签</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<p class="muted small">改标签名会连带改归档目录名（不可回滚，先预演）；'
            + '命名空间/大类/描述/备注只改库，来源（目录/词典）保持不变。</p>'
            + '<div style="margin-top:10px">'
            + '<input type="text" id="edit-tag" value="' + UI.esc(t.tagName) + '" style="width:100%">'
            + '</div>'
            + '<div style="margin-top:8px">'
            + '<select id="edit-ns">' + namespaceOptions(t.namespace) + '</select>'
            + '</div>'
            + '<div style="margin-top:8px">'
            + '<select id="edit-major">'
            + '<option value="">（不指定大类）</option>'
            + MAJOR_CATEGORIES.map(c => '<option value="' + c + '"'
                + (c === t.majorCategory ? ' selected' : '') + '>' + c + '</option>').join('')
            + '</select>'
            + '</div>'
            + '<div style="margin-top:8px">'
            + '<input type="text" id="edit-desc" value="' + UI.esc(t.description || '')
            + '" placeholder="描述" style="width:100%">'
            + '</div>'
            + '<div style="margin-top:8px">'
            + '<input type="text" id="edit-remark" value="' + UI.esc(t.remark || '')
            + '" placeholder="备注" style="width:100%">'
            + '</div>'
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" data-act="cancel">取消</button>'
            + '<button type="button" class="btn-primary" data-act="ok">保存</button>'
            + '</div></div>');

        m.box.querySelector('.modal-close').onclick = () => m.close();
        m.box.querySelector('[data-act="cancel"]').onclick = () => m.close();
        m.box.querySelector('[data-act="ok"]').onclick = async (ev) => {
            const tagName = m.box.querySelector('#edit-tag').value.trim();
            if (!tagName) {
                UI.err('请填标签名');
                return;
            }
            const meta = {
                namespace: m.box.querySelector('#edit-ns').value,
                majorCategory: m.box.querySelector('#edit-major').value,
                description: m.box.querySelector('#edit-desc').value.trim(),
                remark: m.box.querySelector('#edit-remark').value.trim()
            };
            await UI.withBusy(ev.target, '保存中…', async () => {
                try {
                    // 元数据先落库（不动来源）；标签名若变了再走改名预演
                    await Api.put('/api/manga/tags/' + t.id + '/meta', meta);
                    m.close();
                    if (tagName !== t.tagName) {
                        preview(host, '/api/manga/tags/' + t.id + '/rename-plan?newTagName='
                            + encodeURIComponent(tagName),
                            () => Api.put('/api/manga/tags/' + t.id, {newTagName: tagName}));
                    } else {
                        UI.ok('已保存');
                        await render(host);
                    }
                } catch (e) {
                    UI.err(e.message);
                }
            });
        };
    }

    /** 分页条：每页条数用本页的 PAGE_SIZE，画法见 UI.pagerBar */
    function pagerBar(container, total, current, onGo) {
        UI.pagerBar(container, total, current, PAGE_SIZE, onGo);
    }

    function bind(host) {
        host.querySelector('#only-unused').onchange = (ev) => {
            showUnusedOnly = ev.target.checked;
            page = 1;
            render(host);
        };

        // 搜索：防抖 300ms，重渲染后把焦点还给输入框、光标停在末尾，避免每敲一个字就丢焦点。
        // 输入法组合（compositionstart → … → compositionend）期间不搜：拼音敲击会连续触发
        // input 事件，若每次都防抖重渲染，组合态被打断，中文永远上不了屏。
        const searchInput = host.querySelector('#tag-search');
        const scheduleSearch = () => {
            searchQuery = searchInput.value;
            page = 1;
            clearTimeout(searchTimer);
            searchTimer = setTimeout(() => {
                render(host).then(() => {
                    const s = host.querySelector('#tag-search');
                    if (s) {
                        s.focus();
                        s.setSelectionRange(s.value.length, s.value.length);
                    }
                });
            }, 300);
        };
        searchInput.addEventListener('compositionstart', () => { composing = true; });
        searchInput.addEventListener('compositionend', () => {
            composing = false;
            scheduleSearch();  // 上屏完成，此时 value 是最终汉字，再搜
        });
        searchInput.oninput = () => {
            if (composing) {
                return;
            }
            scheduleSearch();
        };

        host.querySelector('#eh-sync-btn').onclick = (ev) => ehSync(host, ev.target);

        const pager = host.querySelector('#tag-pager');
        if (pager) {
            pagerBar(pager, filtered.length, page, (p) => {
                page = p;
                render(host);
            });
        }

        const cleanupBtn = host.querySelector('#cleanup-btn');
        if (!cleanupBtn.disabled) {
            cleanupBtn.onclick = async (ev) => {
                if (!await UI.confirm('删掉所有零引用的标签？\n\n'
                    + '它们没有挂在任何归档目录或漫画上，删掉不影响任何目录名。',
                    {title: '清理未使用标签', okText: '清理'})) {
                    return;
                }
                await UI.withBusy(ev.target, '清理中…', async () => {
                    try {
                        const n = await Api.post('/api/manga/tags/cleanup-unused');
                        UI.ok('清理了 ' + n + ' 个未使用标签');
                        await render(host);
                    } catch (e) {
                        UI.err(e.message);
                    }
                });
            };
        }

        host.querySelector('#open-create').onclick = () => openCreateModal(host);

        host.querySelectorAll('.act-units').forEach(a => a.onclick = (ev) => {
            ev.preventDefault();
            const tr = a.closest('tr');
            // 跳到归档作者页，并按标签筛选出挂了它的归档目录
            App.navigate('manga-archive', {tag: tr.dataset.name});
        });

        host.querySelectorAll('.act-edit').forEach(btn => btn.onclick = () => {
            const tr = btn.closest('tr');
            const u = usages.find(x => String(x.tag.id) === tr.dataset.id);
            openEditModal(host, u);
        });

        host.querySelectorAll('.act-merge').forEach(btn => btn.onclick = () => {
            const tr = btn.closest('tr');
            const others = usages.filter(x => String(x.tag.id) !== tr.dataset.id);
            if (!others.length) {
                UI.err('没有别的标签可合并');
                return;
            }
            openMergeModal(host, tr.dataset.id, tr.dataset.name, others);
        });

        host.querySelectorAll('.act-delete').forEach(btn => btn.onclick = () => {
            const tr = btn.closest('tr');
            previewDelete(host, tr.dataset.id, tr.dataset.name);
        });
    }

    /**
     * 合并到…弹窗。用下拉选目标而非手打标签名 ——
     * 打错一个字就去预演一个不存在的标签，而这个操作最终要改磁盘目录。
     */
    function openMergeModal(host, sourceId, sourceName, others) {
        const m = UI.modal('<div class="modal">'
            + '<div class="modal-head"><span>把「' + UI.esc(sourceName) + '」合并到</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">'
            + '<p class="muted small">合并会把源标签的关联并到目标标签，并连带把归档目录名里的'
            + '源标签改成目标标签；目标标签的来源分类保持不变。下一步会先列出要改的目录名。</p>'
            + '<input type="text" id="merge-filter" placeholder="输入关键字过滤目标标签…" style="width:100%">'
            + '<select id="merge-target" style="width:100%"></select>'
            + '<p class="muted small" id="merge-count"></p>'
            + '</div>'
            + '<div class="modal-foot">'
            + '<button type="button" class="btn-plain" data-act="cancel">取消</button>'
            + '<button type="button" class="btn-primary" data-act="ok">预演</button>'
            + '</div></div>');

        m.box.querySelector('.modal-close').onclick = () => m.close();
        m.box.querySelector('[data-act="cancel"]').onclick = () => m.close();

        const filter = m.box.querySelector('#merge-filter');
        const sel = m.box.querySelector('#merge-target');
        const count = m.box.querySelector('#merge-count');
        const okBtn = m.box.querySelector('[data-act="ok"]');

        // 只重建 select 的 option，不碰过滤输入框本身，所以输入法组合不受影响
        const renderOptions = () => {
            const q = filter.value.trim().toLowerCase();
            const list = q
                ? others.filter(u => (u.tag.tagName || '').toLowerCase().includes(q))
                : others;
            sel.innerHTML = list.map(u => '<option value="' + u.tag.id + '">'
                + UI.esc(u.tag.tagName)
                + (u.tag.namespace ? ' [' + UI.esc(u.tag.namespace) + ']' : '')
                + '（归档 ' + u.archiveUnitCount + '）</option>').join('');
            count.textContent = list.length ? ('共 ' + list.length + ' 个候选') : '没有匹配的标签';
            okBtn.disabled = !list.length;
        };
        renderOptions();
        filter.oninput = renderOptions;

        m.box.querySelector('[data-act="ok"]').onclick = () => {
            const targetTagId = sel.value;
            m.close();
            preview(host, '/api/manga/tags/' + sourceId + '/merge-plan?targetTagId=' + targetTagId,
                () => Api.post('/api/manga/tags/' + sourceId + '/merge', {targetTagId: Number(targetTagId)}));
        };
    }

    /**
     * 删除标签：预演 → 确认 → 执行，全程在弹窗里完成。
     * 删除会连带从归档目录名里去掉该标签，目录改名不可回滚，所以先把
     * 「会改哪些目录名」摊开，确认了才动手；有一条 blockedReason 就整批不执行。
     */
    async function previewDelete(host, tagId, tagName) {
        const m = UI.modal('<div class="modal modal-wide"><div class="modal-head">'
            + '<span>删除标签「' + UI.esc(tagName) + '」</span>'
            + '<button class="modal-close" type="button">×</button></div>'
            + '<div class="modal-body">' + UI.spinner('预演中…') + '</div></div>');
        m.box.querySelector('.modal-close').onclick = () => m.close();

        let plan;
        try {
            plan = await Api.get('/api/manga/tags/' + tagId + '/delete-plan');
        } catch (e) {
            m.box.querySelector('.modal-body').innerHTML =
                '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }

        const body = m.box.querySelector('.modal-body');
        const blocked = plan.rewrites.filter(r => r.blockedReason);
        const changing = plan.rewrites.filter(r => !r.blockedReason && r.fromFolderName !== r.toFolderName);

        let html = '<p class="small">删除标签「' + UI.esc(plan.tagName) + '」'
            + (plan.newTagName ? ' → 「' + UI.esc(plan.newTagName) + '」' : '') + '</p>';
        if (!plan.rewrites.length) {
            html += '<p class="small">没有归档目录用着这个标签，只删标签本身，不涉及目录改名。</p>';
        } else {
            html += '<p class="small">会从 ' + changing.length + ' 个归档目录的标签块里去掉它'
                + (plan.rewrites.length > changing.length + blocked.length
                    ? ('，另有 ' + (plan.rewrites.length - changing.length - blocked.length)
                        + ' 个目录名无变化') : '') + '。</p>';
            html += '<table><thead><tr><th>现目录名</th><th>改成</th></tr></thead><tbody>';
            for (const r of plan.rewrites) {
                html += '<tr><td class="mono small">' + UI.esc(r.fromFolderName) + '</td>'
                    + '<td class="mono small">' + (r.blockedReason
                        ? '<span class="tag tag-err">' + UI.esc(r.blockedReason) + '</span>'
                        : (r.fromFolderName === r.toFolderName
                            ? '<span class="muted">无变化</span>' : UI.esc(r.toFolderName)))
                    + '</td></tr>';
            }
            html += '</tbody></table>';
        }
        if (plan.mangaRefs) {
            html += '<p class="small muted">另有 ' + plan.mangaRefs
                + ' 条漫画标签关联受影响，漫画侧只在库里，没有目录名要改。</p>';
        }

        if (blocked.length) {
            html += '<div class="hint hint-err">有 ' + blocked.length
                + ' 个目录改不了，整批不能执行。'
                + '先把它们处理掉（目录失踪的到「归档作者」页确认删除或等盘挂回来，'
                + '目标目录已存在的手工合并），再回来重试。</div>'
                + '<div class="modal-foot"><button class="btn-danger" disabled>删除</button>'
                + '<button type="button" class="btn-plain" data-act="cancel">取消</button></div>';
        } else {
            html += '<div class="modal-foot">'
                + '<button type="button" class="btn-danger" data-act="run">确认删除</button>'
                + '<button type="button" class="btn-plain" data-act="cancel">取消</button>'
                + '<span class="muted small">目录改名不可回滚</span></div>';
        }
        body.innerHTML = html;

        body.querySelector('[data-act="cancel"]').onclick = () => m.close();
        const runBtn = body.querySelector('[data-act="run"]');
        if (runBtn) {
            runBtn.onclick = async (ev) => {
                // 预演清单是第一次确认，这里是第二次
                if (!await UI.confirm('删除标签「' + plan.tagName + '」？\n\n'
                    + '已列出的 ' + changing.length + ' 个归档目录会把标签块里的它去掉，'
                    + '不可回滚。', {title: '删除标签', okText: '删除'})) {
                    return;
                }
                await UI.withBusy(ev.target, '提交中…', async () => {
                    let taskId;
                    try {
                        taskId = await Api.del('/api/manga/tags/' + tagId);
                    } catch (e) {
                        UI.err(e.message);
                        return;
                    }
                    body.innerHTML = UI.spinner('执行中…');
                    TaskPoll.wait(taskId, {
                        onResult: async (result) => {
                            m.close();
                            UI.ok('完成：改了 ' + result.renamedFolders + ' 个目录名'
                                + (result.movedRefs
                                    ? ('，并了 ' + result.movedRefs + ' 条关联') : ''));
                            await render(host);
                            return true;
                        },
                        onFail: (error) => UI.err(error)
                    });
                });
            };
        }
    }

    /**
     * 预演 → 审查 → 执行。
     * 目录改名不可回滚，所以先把「会改哪些目录名」摊开，确认了才动手。
     */
    async function preview(host, planUrl, execute) {
        const box = host.querySelector('#plan-box');
        box.innerHTML = UI.spinner('预演中…');
        let plan;
        try {
            plan = await Api.get(planUrl);
        } catch (e) {
            box.innerHTML = '<div class="hint hint-err">' + UI.esc(e.message) + '</div>';
            return;
        }

        const action = {RENAME: '重命名', MERGE: '合并', DELETE: '删除'}[plan.action] || plan.action;
        const blocked = plan.rewrites.filter(r => r.blockedReason);
        const changing = plan.rewrites.filter(r => !r.blockedReason && r.fromFolderName !== r.toFolderName);

        let html = '<div class="card"><h2>' + action + '「' + UI.esc(plan.tagName) + '」'
            + (plan.newTagName ? ' → 「' + UI.esc(plan.newTagName) + '」' : '') + '</h2>';
        if (plan.merge) {
            html += '<div class="hint">目标标签已存在，本次按<strong>合并</strong>处理：'
                + '源标签的关联会并到目标标签上，源标签随后删除。</div>';
        }

        if (!plan.rewrites.length) {
            html += '<p class="small">没有归档目录用着这个标签，不涉及任何目录改名。</p>';
        } else {
            html += '<p class="small">会改 ' + changing.length + ' 个目录名'
                + (plan.rewrites.length > changing.length + blocked.length
                    ? ('，另有 ' + (plan.rewrites.length - changing.length - blocked.length)
                        + ' 个目录名无变化') : '') + '。</p>';
            html += '<table><thead><tr><th>现目录名</th><th>改成</th></tr></thead><tbody>';
            for (const r of plan.rewrites) {
                html += '<tr><td class="mono small">' + UI.esc(r.fromFolderName) + '</td>'
                    + '<td class="mono small">' + (r.blockedReason
                        ? '<span class="tag tag-err">' + UI.esc(r.blockedReason) + '</span>'
                        : (r.fromFolderName === r.toFolderName
                            ? '<span class="muted">无变化</span>' : UI.esc(r.toFolderName)))
                    + '</td></tr>';
            }
            html += '</tbody></table>';
        }
        if (plan.mangaRefs) {
            html += '<p class="small muted">另有 ' + plan.mangaRefs
                + ' 条漫画标签关联受影响，漫画侧只在库里，没有目录名要改。</p>';
        }

        if (blocked.length) {
            // 漏改一个目录，下次同步旧标签就会冒出来 —— 所以整批不执行，而不是跳过继续
            html += '<div class="hint hint-err">有 ' + blocked.length
                + ' 个目录改不了，整批不能执行。'
                + '先把它们处理掉（目录失踪的到「归档作者」页确认删除或等盘挂回来，'
                + '目标目录已存在的手工合并），再回来重试。</div>'
                + '<button disabled>' + action + '</button>';
        } else {
            html += '<div class="row"><button class="btn-primary" id="plan-run">确认' + action + '</button>'
                + '<button class="btn-plain" id="plan-cancel">取消</button>'
                + '<span class="muted small">目录改名不可回滚</span></div>';
        }
        box.innerHTML = html + '</div>';
        box.scrollIntoView({behavior: 'smooth', block: 'nearest'});

        const runBtn = box.querySelector('#plan-run');
        if (runBtn) {
            runBtn.onclick = async (ev) => {
                await UI.withBusy(ev.target, '提交中…', async () => {
                    let taskId;
                    try {
                        taskId = await execute();
                    } catch (e) {
                        UI.err(e.message);
                        return;
                    }
                    box.innerHTML = UI.spinner('执行中…');
                    TaskPoll.wait(taskId, {
                        onResult: async (result) => {
                            UI.ok('完成：改了 ' + result.renamedFolders + ' 个目录名'
                                + (result.movedRefs
                                    ? ('，并了 ' + result.movedRefs + ' 条关联') : ''));
                            await render(host);
                            return true;
                        },
                        onFail: (error) => UI.err(error)
                    });
                });
            };
            box.querySelector('#plan-cancel').onclick = () => box.innerHTML = '';
        }
    }

    return {render};
})();
