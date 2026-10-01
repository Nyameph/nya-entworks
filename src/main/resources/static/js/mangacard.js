/**
 * 漫画卡片组件：封面 + 修正后标题 + 文件数 + 规则 + 评分 + 归档分类 + 操作。
 *
 * 文档 4.8 要求这个组件在四个页面共用（新漫画、合集内、未归档、已归档），
 * 所以它不认识「新漫画」这件事 —— 卡片只管画，能做哪些操作由调用方按 status
 * 传进来。目前用在新漫画与合集两处，另两页做的时候直接复用。
 *
 * 新漫画不入库，所以卡片的 id 是目录全路径（data-path）。
 *
 * 勾选、评分按钮、存储确认这些旧形态都移去了阅读页（见 manganew.js 的
 * openReader）：卡片只负责「这一本长什么样、能读能改名能删」。
 */
const MangaCard = (() => {

    /** 封面端点。缓存键含源文件的大小与修改时间，所以路径变了就会重取 */
    function coverUrl(folderPath) {
        return '/api/manga/new/cover?folderPath=' + encodeURIComponent(folderPath);
    }

    /**
     * 画一张卡片。
     *
     * @param m       NewManga
     * @param options acts 要显示哪些操作，默认 阅读/改名/删除；
     *                select 批量勾选：{can(m), checked(m)}，can 为假时勾选框禁用
     */
    function render(m, options) {
        const opt = options || {};
        // busy 文案非空 = 处理中（存储/打包等）：封面占位、操作区换成文案，操作按钮与勾选全部隐藏
        const busyText = opt.busy || (opt.storing ? '存档中…' : null);
        const busy = !!busyText;
        const acts = opt.acts || ['read', 'rename', 'delete'];
        // 归档分类行（「归档 X 分 / 未归档」）默认画；归档作者页已归档的漫画传 false 去掉
        const showArchive = opt.showArchive !== false;
        const cls = busy ? 'storing' : (m.blockers && m.blockers.length ? 'blocked' : 'ok');
        // 修正后的标题：需要规范化时就是规范名（展示改完的结果），否则是目录名
        const title = (m.needsRename && m.normalizedName) ? m.normalizedName : m.folderName;

        let html = '<div class="manga-card ' + cls + '" data-path="' + UI.esc(m.folderPath) + '">';

        // 批量勾选：浮在封面左上角。不能归档的禁用，只让勾「可存储/可归档」的
        if (opt.select && !busy) {
            const can = opt.select.can ? opt.select.can(m) : true;
            const checked = opt.select.checked ? opt.select.checked(m) : false;
            html += '<input type="checkbox" class="manga-check"'
                + (checked ? ' checked' : '') + (can ? '' : ' disabled')
                + ' title="' + (can ? '勾选以批量归档' : '还不能归档，无法勾选') + '">';
        }

        // 封面：点它就是「阅读」。取不到图时占位，别显示碎图；处理中换成占位、不给点
        html += busy
            ? '<div class="manga-cover-none">' + UI.esc(busyText) + '</div>'
            : '<img class="manga-cover" loading="lazy" alt="封面"'
                + ' src="' + UI.esc(coverUrl(m.folderPath)) + '"'
                + ' onerror="this.outerHTML=\'<div class=&quot;manga-cover-none&quot;>无封面</div>\'">';

        // 标题要能完整看到，长名折行而不是截断；title 挂着原目录名，规范名差异悬停可查
        html += '<div class="manga-name" title="' + UI.esc(m.folderName) + '">'
            + UI.esc(title) + '</div>';

        html += '<div class="manga-meta"><span>' + m.fileCount + ' 个文件</span></div>'
            // 匹配规则、是否评分、是否规范化三件事一行放，不折行
            + '<div class="manga-status">'
            + (m.matchedRule ? '<span class="tag">规则' + m.matchedRule + '</span>'
                : '<span class="tag tag-err">不规范</span>')
            + (m.score ? '<span class="tag ' + (m.scoreSource === 'INHERIT_ARCHIVE'
                    ? 'tag-dim' : 'tag-ok') + '">' + m.score + ' 分</span>'
                : '<span class="tag tag-warn">未评分</span>')
            + (m.scoreSource === 'SELF' ? '<span class="tag tag-ok">单独</span>' : '')
            + (m.needsRename ? '<span class="tag tag-warn">未规范化</span>'
                : '<span class="tag tag-ok">已规范化</span>')
            + '</div>';

        if (showArchive) {
            html += renderArchive(m.archiveMatch);
        }

        // 标签回显：后端现扫时注入的 m.tags（独立优先、父级标签兜底），卡片直接画
        if (m.tags && m.tags.length) {
            html += '<div class="small" style="margin-top:4px">'
                + m.tags.map(t => '<span class="tag">'
                    + UI.esc(typeof t === 'string' ? t : t.tagName) + '</span>').join(' ')
                + '</div>';
        }

        if (m.irregularReason) {
            html += '<div class="small muted">' + UI.esc(m.irregularReason) + '</div>';
        }

        // 处理中不画操作按钮，用文案标签占位，同时断了所有操作入口
        html += busy
            ? '<div class="manga-acts"><span class="tag tag-warn">' + UI.esc(busyText) + '</span></div>'
            : '<div class="manga-acts">'
            + (acts.includes('read') ? '<button class="c-read">阅读</button>' : '')
            + (acts.includes('rename') ? '<button class="c-rename">改名</button>' : '')
            + (acts.includes('archive') ? '<button class="c-archive">归档</button>' : '')
            + (acts.includes('edit') ? '<button class="c-edit">修改</button>' : '')
            + (acts.includes('normalize')
                ? '<button class="c-normalize"' + (m.needsRename ? '' : ' disabled') + '>规范化</button>'
                : '')
            + (acts.includes('pack') ? '<button class="c-pack">打包cbz</button>' : '')
            + (acts.includes('delete') ? '<button class="btn-danger c-delete">删除</button>' : '')
            + '</div>';

        return html + '</div>';
    }

    /**
     * 归档分类，简化成一句话：能归档 → 「归档 X分」；没匹配到 → 「未归档」。
     */
    function renderArchive(match) {
        if (!match) {
            return '';
        }
        let tag;
        if (match.target) {
            tag = '<span class="tag tag-ok">归档 ' + match.target.score + ' 分</span>';
            if (match.matchedNames && match.matchedNames.length) {
                tag += '<span class="muted"> 凭 ' + UI.esc(match.matchedNames.join('、')) + '</span>';
            }
        } else {
            tag = '<span class="tag">未归档</span>';
        }
        return '<div class="small">' + tag + '</div>';
    }

    /**
     * 绑定卡片上的操作。
     * @param handlers {read, rename, archive, remove, normalize} 都可选
     */
    function bind(host, handlers) {
        const pathOf = (el) => el.closest('.manga-card').dataset.path;

        host.querySelectorAll('.manga-cover').forEach(img => img.onclick = () =>
            handlers.read && handlers.read(pathOf(img)));
        host.querySelectorAll('.c-read').forEach(btn => btn.onclick = () =>
            handlers.read && handlers.read(pathOf(btn)));
        host.querySelectorAll('.c-rename').forEach(btn => btn.onclick = () =>
            handlers.rename && handlers.rename(pathOf(btn), btn));
        host.querySelectorAll('.c-archive').forEach(btn => btn.onclick = () =>
            handlers.archive && handlers.archive(pathOf(btn), btn));
        host.querySelectorAll('.c-edit').forEach(btn => btn.onclick = () =>
            handlers.edit && handlers.edit(pathOf(btn), btn));
        host.querySelectorAll('.c-normalize').forEach(btn => btn.onclick = () =>
            handlers.normalize && handlers.normalize(pathOf(btn), btn));
        host.querySelectorAll('.c-delete').forEach(btn => btn.onclick = () =>
            handlers.remove && handlers.remove(pathOf(btn), btn));
        host.querySelectorAll('.c-pack').forEach(btn => btn.onclick = () =>
            handlers.pack && handlers.pack(pathOf(btn), btn));
        host.querySelectorAll('.manga-check').forEach(cb => cb.onchange = () =>
            handlers.select && handlers.select(pathOf(cb), cb.checked, cb));
    }

    return {render, bind, coverUrl};
})();
