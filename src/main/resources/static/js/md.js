/**
 * 极简 markdown 渲染器（零依赖）—— 使用说明页的正文是一批 md 文件，这里把它们画成 HTML。
 *
 * <b>为什么自己写</b>：项目没有构建链，前端全靠 <script> 直接引；为一份说明页引一整套
 * marked + 高亮 + 消毒不划算，而说明页真正用到的语法就那么几种。渲染器放这儿之后，
 * 文案改一句话不用碰 JS，改的是 static/guide/ 下的 md。
 *
 * <b>安全</b>：默认把 HTML 全转义 —— 正文里原样写一个 <script>，页面上显示的是这段文字
 * 本身，不会被执行。链接与图片的地址只放行 http / https / mailto 与相对路径，
 * `javascript:` `data:` 一律挡下（挡下时只留可见的文字，不留可点的链接）。
 *
 * <b>支持</b>：标题、段落、无序 / 有序列表（可嵌套）、表格、围栏代码块、引用、分隔线、
 * 行内代码、加粗 / 斜体 / 删除线、链接、图片。
 *
 * <b>两处有意与 GitHub 不同</b>：① 段落里的换行**就是换行**（GitHub 会并成一行）——
 * 中文长文一句一行地写更顺手，也让「写的时候什么样、看的时候什么样」成立；
 * ② 图片渲染成「图 + 一句说明」的一小块，图没加载出来时就地画一个占位框（见 CSS 的
 * .md-imgwrap），于是底图还没截的说明页也能看 —— 等哪天真把图放进去，不用改代码。
 *
 * 入口只有 `Md.render(text) -> html`。
 */
const Md = (() => {

    const ESCAPES = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'};

    /** 转义成 HTML 文本。渲染器里所有「落到页面上的字」都必须先过它 */
    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, c => ESCAPES[c]);
    }

    /**
     * 地址闸门。没有协议的（`../img/guide/x.png`、`#anchor`）与 http / https / mailto 放行，
     * 其余带协议的（javascript: / data: / vbscript: …）返回空串 = 调用方降级成纯文字。
     */
    function safeUrl(u) {
        const s = String(u == null ? '' : u).trim();
        if (!s) {
            return '';
        }
        if (/^[a-z][a-z0-9+.-]*:/i.test(s)) {
            return /^(https?|mailto):/i.test(s) ? s : '';
        }
        return s;
    }

    /** 行内标记：先剥标记再取文字，给 alt / 标题这种不该带格式的地方用 */
    function plain(s) {
        return String(s == null ? '' : s)
            .replace(/`([^`]*)`/g, '$1')
            .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
            .replace(/[*~]/g, '')
            .trim();
    }

    // ---------- 行内 ----------

    /**
     * 一行文字 → HTML。手写扫描而不是一串 replace：`*` 与 `[` 在中文里也会出现，
     * 认不出标记时必须**原样吐回去**，一串全局替换做不到这件事。
     */
    function inline(src) {
        const text = String(src == null ? '' : src);
        let out = '';
        let i = 0;
        while (i < text.length) {
            const rest = text.slice(i);
            let m;
            if (rest[0] === '`' && (m = /^`([^`]+)`/.exec(rest))) {
                out += '<code>' + esc(m[1]) + '</code>';
            } else if (rest[0] === '!' && (m = /^!\[([^\]]*)\]\(([^)\s]*)\)/.exec(rest))) {
                out += image(m[1], m[2]);
            } else if (rest[0] === '[' && (m = /^\[([^\]]*)\]\(([^)\s]*)\)/.exec(rest))) {
                out += link(m[1], m[2]);
            } else if ((m = /^\*\*([^*]+)\*\*/.exec(rest))) {
                out += '<strong>' + inline(m[1]) + '</strong>';
            } else if ((m = /^~~([^~]+)~~/.exec(rest))) {
                out += '<del>' + inline(m[1]) + '</del>';
            } else if ((m = /^\*([^*\n]+)\*/.exec(rest))) {
                out += '<em>' + inline(m[1]) + '</em>';
            } else {
                // 认不出的标记（孤零零一个 * 或 [）原样吐回去，绝不吞字
                const plainRun = /^[^`!\[*~\n]+/.exec(rest);
                if (plainRun) {
                    out += esc(plainRun[0]);
                    i += plainRun[0].length;
                } else {
                    // 段落里的换行就是换行（见文件头「有意不同」那条）
                    out += rest[0] === '\n' ? '<br>' : esc(rest[0]);
                    i += 1;
                }
                continue;
            }
            i += m[0].length;
        }
        return out;
    }

    function link(text, href) {
        const url = safeUrl(href);
        if (!url) {
            return inline(text);
        }
        const out = /^https?:/i.test(url) ? ' target="_blank" rel="noopener noreferrer"' : '';
        return '<a href="' + esc(url) + '"' + out + '>' + inline(text) + '</a>';
    }

    /**
     * 图片。外层那个 span 是给「图还没截」用的：图加载不出来时由 guide.js 给它加
     * .is-missing，CSS 就把 img 收起来、把里面的说明文字画成一个占位框。
     */
    function image(alt, src) {
        const url = safeUrl(src);
        if (!url) {
            return esc(plain(alt));
        }
        // **不写 loading="lazy"**：占位框是靠 img 的 error 事件画出来的，而懒加载的图在
        // 滚到它跟前之前根本不发请求、也就永远不发 error —— 一屏之外的十几处会是一片空白。
        // 场景本来就在本机（图要么在同目录磁盘上、要么就没有），一次全取没有代价。
        return '<span class="md-imgwrap">'
            + '<img class="md-img" src="' + esc(url) + '" alt="' + esc(plain(alt)) + '">'
            + '<span class="md-imgph">' + inline(alt) + '</span>'
            + '</span>';
    }

    // ---------- 块 ----------

    /** 标题的锚点 id。中文原样留着（浏览器接受），空白与重复的交给 uniqueId 收 */
    function slug(s) {
        return plain(s).toLowerCase().replace(/\s+/g, '-').replace(/[^\w一-龥-]/g, '');
    }

    function uniqueId(base, used) {
        const root = base || 'sec';
        let id = root;
        let n = 2;
        while (used.has(id)) {
            id = root + '-' + n++;
        }
        used.add(id);
        return id;
    }

    const LIST_ITEM = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;
    const FENCE = /^(```|~~~)(.*)$/;
    const HEADING = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
    const HR = /^\s*(-{3,}|\*{3,}|_{3,})\s*$/;

    function isTableSep(line) {
        if (!line || !line.includes('|') || !line.includes('-')) {
            return false;
        }
        const t = line.trim().replace(/^\|/, '').replace(/\|$/, '');
        return t.split('|').every(c => /^\s*:?-+:?\s*$/.test(c));
    }

    function tableCells(line) {
        let t = line.trim();
        if (t.startsWith('|')) {
            t = t.slice(1);
        }
        if (t.endsWith('|')) {
            t = t.slice(0, -1);
        }
        return t.split('|').map(s => s.trim());
    }

    function alignOf(sep) {
        return /^:-+:$/.test(sep) ? 'center' : /^-+:$/.test(sep) ? 'right' : /^:-+$/.test(sep) ? 'left' : '';
    }

    /** 这一行会不会开一个新块（段落循环靠它决定在哪里停） */
    function startsBlock(lines, i) {
        const line = lines[i];
        if (!line.trim()) {
            return true;
        }
        if (FENCE.test(line) || HEADING.test(line) || HR.test(line)
            || /^\s*>/.test(line) || LIST_ITEM.test(line)) {
            return true;
        }
        return line.includes('|') && i + 1 < lines.length && isTableSep(lines[i + 1]);
    }

    function list(lines, start) {
        const baseIndent = /^(\s*)/.exec(lines[start])[1].length;
        const ordered = /^\s*\d/.test(lines[start]);
        const items = [];
        let i = start;

        while (i < lines.length) {
            const m = LIST_ITEM.exec(lines[i]);
            if (!m || m[1].length !== baseIndent) {
                break;                       // 缩进不同 = 别人（上层递归）的项
            }
            if (/^\s*\d/.test(lines[i]) !== ordered) {
                break;                       // 换了列表类型 = 另起一个列表
            }
            const body = m[3];
            i++;
            const sub = [];
            while (i < lines.length) {
                if (!lines[i].trim()) {
                    // 空行后面还缩进着就是这一项的续行，否则这一项到此为止
                    if (i + 1 < lines.length && /^\s{2,}\S/.test(lines[i + 1])) {
                        sub.push('');
                        i++;
                        continue;
                    }
                    break;
                }
                if (LIST_ITEM.test(lines[i]) && LIST_ITEM.exec(lines[i])[1].length <= baseIndent) {
                    break;
                }
                sub.push(lines[i].replace(new RegExp('^\\s{0,' + (baseIndent + 2) + '}'), ''));
                i++;
            }
            items.push({body, sub});
        }

        const tag = ordered ? 'ol' : 'ul';
        return {
            html: '<' + tag + '>' + items.map(it =>
                '<li>' + inline(it.body) + (it.sub.length ? render(it.sub.join('\n')) : '') + '</li>'
            ).join('') + '</' + tag + '>',
            next: i
        };
    }

    function blockquote(lines, start) {
        const buf = [];
        let i = start;
        while (i < lines.length) {
            const line = lines[i];
            if (/^\s*>/.test(line)) {
                buf.push(line.replace(/^\s*>\s?/, ''));
                i++;
                continue;
            }
            if (!line.trim() && i + 1 < lines.length && /^\s*>/.test(lines[i + 1])) {
                buf.push('');
                i++;
                continue;
            }
            break;
        }
        return {html: '<blockquote>' + render(buf.join('\n')) + '</blockquote>', next: i};
    }

    function table(lines, start) {
        const head = tableCells(lines[start]);
        const aligns = tableCells(lines[start + 1]).map(alignOf);
        let i = start + 2;
        const rows = [];
        while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
            rows.push(tableCells(lines[i]));
            i++;
        }
        const attrs = k => aligns[k] ? ' style="text-align:' + aligns[k] + '"' : '';
        return {
            html: '<table><thead><tr>'
                + head.map((c, k) => '<th' + attrs(k) + '>' + inline(c) + '</th>').join('')
                + '</tr></thead><tbody>'
                + rows.map(r => '<tr>'
                    + head.map((_, k) => '<td' + attrs(k) + '>' + inline(r[k] || '') + '</td>').join('')
                    + '</tr>').join('')
                + '</tbody></table>',
            next: i
        };
    }

    function render(src) {
        const text = String(src == null ? '' : src).replace(/\r\n?/g, '\n');
        if (!text.trim()) {
            return '';
        }
        const lines = text.split('\n');
        const used = new Set();
        const out = [];
        let i = 0;

        while (i < lines.length) {
            const line = lines[i];
            if (!line.trim()) {
                i++;
                continue;
            }

            const fence = FENCE.exec(line);
            if (fence) {
                const mark = fence[1];
                const buf = [];
                i++;
                while (i < lines.length && lines[i].slice(0, 3) !== mark) {
                    buf.push(lines[i]);
                    i++;
                }
                i++;                        // 闭合围栏；忘了闭合也照样收，不吞掉后面
                out.push('<pre><code>' + esc(buf.join('\n')) + '</code></pre>');
                continue;
            }

            const heading = HEADING.exec(line);
            if (heading) {
                const lvl = heading[1].length;
                const body = heading[2];
                const id = uniqueId(slug(body), used);
                out.push('<h' + lvl + ' id="' + esc(id) + '">' + inline(body) + '</h' + lvl + '>');
                i++;
                continue;
            }

            if (HR.test(line)) {
                out.push('<hr>');
                i++;
                continue;
            }

            if (/^\s*>/.test(line)) {
                const bq = blockquote(lines, i);
                out.push(bq.html);
                i = bq.next;
                continue;
            }

            if (line.includes('|') && i + 1 < lines.length && isTableSep(lines[i + 1])) {
                const tb = table(lines, i);
                out.push(tb.html);
                i = tb.next;
                continue;
            }

            if (LIST_ITEM.test(line)) {
                const li = list(lines, i);
                out.push(li.html);
                i = li.next;
                continue;
            }

            const buf = [];
            while (i < lines.length && !startsBlock(lines, i)) {
                buf.push(lines[i]);
                i++;
            }
            out.push('<p>' + inline(buf.join('\n')) + '</p>');
        }

        return out.join('\n');
    }

    return {render};
})();
