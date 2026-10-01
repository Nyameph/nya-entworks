/**
 * 使用说明页（js/md.js + js/guide-data.js + js/guide.js + static/guide/*.md）的守门 ——
 * 运行：node tools/guide-check.js
 *
 * 为什么需要它：说明页要防的错**全是静默的**，一件也不会自己冒出来 ——
 * modules.js 加了页面而那一章的 md 里没补上对应的 `## ` 块（页面上少一节，没人发现）、
 * md 文件名拼错一个字母（整章永远显示「没读出来」）、文案顺手把 modules.js
 * 那句 desc 抄了一份（第二个会漂移的权威，正是这个项目最在意的）、
 * 面向用户的正文里漏进了 `gaps_json` 这种实现细节、渲染器里图省事写死了一句中文
 * （文案从此有两个来源）。所以这里把它们全部变成机检。
 *
 * 正文现在是 md，所以这一轮的判据也从「数据对象里字段对不对」换成
 * 「文件在不在、名字对不对得上、正文里有没有不该出现的字」。
 *
 * 十一组断言：
 *   1. 覆盖度：每个**章**都有一份非空的 `static/guide/<章 id>.md`（章 ＝
 *      「首次上手」/ 各模块 / 「系统工具」，共 5 章），而且**每个页面在它那一章里都有
 *      一块同名**（逐字）—— 这一条就是「每个页面都讲到」的钉子（章的结构与顺序以 md
 *      为准，块与页面**按标题对名**，见 sectionsOf）。反过来，对不上任何页面的块
 *      **只 WARN**：作者愿意多讲一节是合法的，但那多半意味着某个页面**标题被改了字、
 *      于是丢了「打开这一页」按钮**，所以点名（2026-09-30 起；原先这条是「块数必须相等」，
 *      而那个口径会在 md 中间插一节时整体错位一格）；
 *   2. 反向：static/guide/ 下不许有这 5 个之外的 md（**孤儿文件 = 写了没人看**）；
 *   3. 反抄写：正文里不许有与 modules.js 的 title / desc 相同的整句，
 *      也不许拿 desc 的前 20 字当正文首句（**标题行豁免** —— 页面标题本来就写在 md 里）；
 *   4. md 的体例：不许有一级标题（章的标题只有 modules.js 一份）、不许手打图号；
 *   5. 禁词表：规格（列名 / 阈值 / 类名 / 键名）与「对象.成员」形态的代码标识符；
 *      跨模块的那两章（`intro.md` / `standalone.md`）另外**不许点名任何模块或它的页面** ——
 *      模块是可以被裁掉的，裁掉之后那句话就成了一条指不存在的页面的遗留信息（2026-09-30）；
 *   6. 合同句：两处都要这么说的那几句话（填词页的折叠速查 → 说明页；反方向那句
 *      2026-09-30 由作者从 md 里去掉，就不再钉它）；
 *   7. 图：引用数、文件名唯一、都写成**相对 md 文件**的 `../img/guide/<名字>.png`、
 *      真存在的图必须是能读出 IHDR 的 PNG，且 static/img/guide/ 里不许有 md 没引用的图
 *      （白名单 `UNREFERENCED_OK` 除外 —— 条目失效也判红）；图还没截时打印还剩几张待补；
 *   8. 渲染器零文案：guide.js / md.js 里不许有成句的中文字面量；
 *   9. md.js 的冒烟：把一段「什么语法都用上」的样本渲染一遍，逐条核对
 *      （HTML 必须被转义、javascript: 必须被挡、孤零零的 * 不许吞字、标题 id 唯一…）；
 *  10. 渲染冒烟：搭一套假 DOM 把 guide.js 的 render() 真跑一遍 —— 「按标题对名出来的 id
 *      对不对、顺序是不是照 md」这条只活在运行时（第 1 组只看得见「同名的那一块在不在」），
 *      另走五条退化分支（整章 md 读不到 / 少一块 / 标题被改一个字 / 缺磁盘根 / 模块关着 /
 *      后端没起）；
 *  11. 体量：正文在 md 里，guide-data.js 就该一直是一张薄清单。
 *
 * 复用的是项目里已有的两件现成的东西：`Modules` / `ModuleIndex`（modules.js 的全局 const）
 * 与 `GuideData`（纯数据），两者在 node 的 `vm` 里都能直接加载 ——
 * md.js 也一样（它不碰 document），所以第 9 组能在这里跑；guide.js 只用
 * host.innerHTML / firstElementChild 上那几个方法，第 10 组给它搭最小的假 DOM。
 *
 * 覆盖不到的：文案本身对不对（那靠「定位取 desc」+「只写用户可见行为」两条约束压）、
 * 以及页面上真画出来**什么样**（字号 / 颜色 / 图；第 10 组只验结构，长相得用眼睛看）。
 *
 * **改 modules.js 的顶层项 / children / 页面清单，改 guide-data.js，或增删 static/guide/
 * 下的 md 之后，先跑它。**
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..');
const JS_DIR = path.join(ROOT, 'src', 'main', 'resources', 'static', 'js');
const MODULES_JS = path.join(JS_DIR, 'modules.js');
const DATA_JS = path.join(JS_DIR, 'guide-data.js');
const SCRIPT_JS = path.join(JS_DIR, 'guide.js');
const MD_JS = path.join(JS_DIR, 'md.js');
const SONGFILL_JS = path.join(JS_DIR, 'songfill.js');
const GUIDE_DIR = path.join(ROOT, 'src', 'main', 'resources', 'static', 'guide');
const IMG_DIR = path.join(ROOT, 'src', 'main', 'resources', 'static', 'img', 'guide');

let pass = 0;
let fail = 0;

function ok(msg) {
    pass++;
    console.log('  ok   ' + msg);
}

function bad(msg, why) {
    fail++;
    console.log('  FAIL ' + msg + (why ? '  —— ' + why : ''));
}

function assert(cond, msg, why) {
    if (cond) {
        ok(msg);
    } else {
        bad(msg, why);
    }
}

/** 在 vm 里跑一个「顶层 const」的 js 文件，再把要的常量取出来 */
function loadConsts(file, names) {
    const sandbox = {console};
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(file, 'utf8'), sandbox, {filename: path.basename(file)});
    return vm.runInContext('({' + names.join(',') + '})', sandbox);
}

const {Modules, ModuleIndex} = loadConsts(MODULES_JS, ['Modules', 'ModuleIndex']);
const {GuideData} = loadConsts(DATA_JS, ['GuideData']);
const {Md} = loadConsts(MD_JS, ['Md']);

const dataSrc = fs.readFileSync(DATA_JS, 'utf8');
const scriptSrc = fs.readFileSync(SCRIPT_JS, 'utf8');
const mdSrc = fs.readFileSync(MD_JS, 'utf8');

// ------------------------------------------------------------------
// 0. 语料：static/guide/ 下全部 md，以及「按约定应该有哪些」
// ------------------------------------------------------------------

const mdText = {};                                  // 文件名 → 正文
for (const name of fs.readdirSync(GUIDE_DIR)) {
    if (name.endsWith('.md')) {
        mdText[name] = fs.readFileSync(path.join(GUIDE_DIR, name), 'utf8');
    }
}

/** 可路由页面 = 注册表里有 render 的项（顶层分组 manga/song/shout 没有 render，不是页面） */
const routable = Object.keys(ModuleIndex).filter(id => !!ModuleIndex[id].render);
/** 有 children 的顶层项 = 一个模块 */
const moduleItems = Modules.filter(m => m.children);
/** 不属任何模块的顶层页（文件记录 / 任务记录 / 系统配置 / 本页） */
const plainItems = Modules.filter(m => !m.children && m.render);

/**
 * 说明页的章 —— **必须与 guide.js 的 chapters() 同一份算术**（那边按运行时的模块三态
 * 可能少几章，静态这一份按「都在」列全）。每章 = 一份 md，章里的 `## ` 块按位置就是这些节。
 */
const CHAPTERS = [
    {
        id: 'intro',
        pages: GuideData.intro.map(s => ({id: 'intro-' + s.id, title: s.title}))
    },
    ...moduleItems.map(m => ({
        id: m.id,
        pages: m.children.map(c => ({id: c.id, title: c.title}))
    })),
    ...(plainItems.length ? [{
        id: 'standalone',
        pages: plainItems.map(m => ({id: m.id, title: m.title}))
    }] : [])
];

const expected = new Map();                         // 文件名 → 它是谁
for (const ch of CHAPTERS) {
    expected.set(ch.id + '.md', '章 ' + ch.id + '（' + ch.pages.length + ' 个页面）');
}

const totalKb = Math.round(Object.values(mdText).reduce((n, s) => n + s.length, 0) / 1024);

/**
 * 一节 ＝ md 里的一个块，**章节结构与顺序以 md 为准**（2026-09-30 用户定：原先目录来自
 * 注册表、块与节**按位置**配对，于是作者在 md 中间插一节就整体错位一格）。块的 id 仍然
 * 只有 modules.js 知道，所以两者**按标题对名**：逐字相同 → 用那一页的 id；对不上任何页面
 * 的块照画成一节，id 现场合成 `<章 id>-b<序号>`（与 guide.js 的 sectionsOf 同一套算术）。
 *
 * 合成 id 的算术**必须与 guide.js 的 sectionsOf 逐字相同**（`-b` + 块在本章里的真实序号，
 * 从 1 起）。原先这里写的是「模 3 的序号」（`(i % 3) + 1`），理由是「两者在 md 里补满、
 * 顺序不乱时必然相等」—— 那句话只在**一章不超过 3 块**时成立。2026-09-30 在 song.md 的
 * 第 2 块插进《添加文件》之后，第 4 块的《播放页面》在守门这边算成 `song-b1`、页面那边是
 * `song-b4`，两条断言一起判红（守门算错、却报成页面的错，正是最难查的那种）。改成同一个
 * 算术之后，「页面画出来的 id 与 md 逐字一致」才真的被这条断言钉住。
 */
function sectionsOf(ch) {
    return blocksOf(mdText[ch.id + '.md'] || '').map((b, i) => {
        const page = ch.pages.find(p => p.title === b.title);
        return {
            title: b.title,
            id: page ? page.id : ch.id + '-b' + (i + 1),
            page: page || null
        };
    });
}

const SECTIONS = CHAPTERS.map(ch => ({id: ch.id, sections: sectionsOf(ch)}));
const allSections = SECTIONS.flatMap(ch => ch.sections);

console.log('正文：' + Object.keys(mdText).length + ' 个 md、' + totalKb + ' KB'
    + '（' + CHAPTERS.length + ' 章、共 ' + allSections.length + ' 节；可路由页面 '
    + routable.length + '）\n');

/**
 * 一份 md 里行首的 `## ` 块标题（代码围栏里的不算 —— 那是正文举例，与 guide.js
 * 的 splitChapter 同一口径）。返回 [{no, title}]。
 *
 * <p>按 `\r?\n` 切：JS 里 `.` 与 `$` **都不认 `\r`**，所以 CRLF 的 md 上
 * `/^##\s+(.*)$/` 一个都匹配不上 —— 那会让这一章**一块都切不出来**，而画面上的症状
 * 只是「这一章底下空着」（守门与页面用的是同一套正则，于是两边**一起**看不见）。
 * 2026-09-30 作者用别的编辑器改一次 md 就踩到了。这里容忍 CRLF 之外，第 4 组还**判红**
 * 「md 一律 LF」—— 因为 CRLF 还会让 md.js 画出来的小节标题 id 多带一个 `\r`。
 */
function blocksOf(src) {
    const out = [];
    let fence = false;
    src.split(/\r?\n/).forEach((line, i) => {
        if (/^\s*```/.test(line)) {
            fence = !fence;
            return;
        }
        if (fence) {
            return;
        }
        const m = /^##\s+(.*)$/.exec(line);
        if (m) {
            out.push({no: i + 1, title: m[1].trim()});
        }
    });
    return out;
}

// ------------------------------------------------------------------
// 1. 覆盖度：该有的 md 一份不少、而且不是空壳
// ------------------------------------------------------------------

console.log('覆盖度：');

const missingFiles = [];
const emptyFiles = [];
for (const [file, who] of expected) {
    const text = mdText[file];
    if (text === undefined) {
        missingFiles.push(file + '（' + who + '）');
    } else if (!text.trim()) {
        emptyFiles.push(file + '（' + who + '）');
    }
}
// 刻意**不写死「19」这个数字**：加一个页面是合法的事，硬数字会让它变成假警报。
// 「没有漏、也没有多余」这两条（本组 + 下一组）在结构上已经把基线钉住了
assert(missingFiles.length === 0, expected.size + ' 章的说明 md 一份不少（含配置页与本页自己）',
    '缺这些：' + missingFiles.join(' / '));
assert(emptyFiles.length === 0, '每一份说明 md 都有内容（不是空文件）',
    '这些是空的：' + emptyFiles.join(' / '));

// 「每个页面都讲到」的钉子：**页面在它那一章里必有一块同名**（逐字比）。块与页面按标题
// 对名（见 sectionsOf），所以 md 里少写一块 ＝ 那一页在说明页上整个不出现 —— 静默，
// 只有这条拦得住。
//
// 反向（**多出来的块**）只 WARN：md 是结构的权威，作者愿意多讲一节（比如那个不属任何页面的
// 《播放页面》）是合法的事，画出来有目录项、有锚点，只是没有「打开这一页」按钮而已。
// 但得点名 —— 那多半意味着**页面标题被改了字**（于是那一页静悄悄丢了按钮），是这一轮
// 最该被看见的一种改动。
const blockNoise = [];
const pageMissing = [];
const orphanBlocks = [];
for (const ch of CHAPTERS) {
    const src = mdText[ch.id + '.md'];
    if (src === undefined) {
        continue;                                   // 缺文件上面已经报过了
    }
    const blocks = blocksOf(src);
    const titles = new Set(blocks.map(b => b.title));
    for (const page of ch.pages) {
        if (!titles.has(page.title)) {
            pageMissing.push(ch.id + '.md 里没有「' + page.title + '」这一块（页面 ' + page.id + '）');
        }
    }
    for (const b of blocks) {
        if (!ch.pages.some(p => p.title === b.title)) {
            orphanBlocks.push(ch.id + '.md:' + b.no + ' ' + b.title);
        }
        // 标题里手打 md 的标记（`**粗**`）不行：那一行不渲染，标记会原样显示成一串星号
        if (/[*_`~]/.test(b.title)) {
            blockNoise.push(ch.id + '.md:' + b.no + ' 的标题里有 md 标记：' + b.title);
        }
    }
}
assert(pageMissing.length === 0,
    '每个页面在它那一章的 md 里都有一块同名（＝每个页面都讲到）',
    pageMissing.join('；') + ' —— 少了那一块，这一页在说明页上就不出现了；'
    + '标题被改过字也会落到这里（块与页面是**按标题逐字**对名的）');
assert(blockNoise.length === 0, '页标题里没有 md 标记（那一行不渲染，标记会原样露出来）',
    blockNoise.join(' / '));
if (orphanBlocks.length) {
    console.log('  WARN 这些块不对应任何页面（照画成一节、有目录项与锚点，但没有「打开这一页」按钮）：');
    orphanBlocks.forEach(m => console.log('       ' + m));
    console.log('       （若本意是讲某个页面 —— 那是它的标题被改了字；'
        + '标题与左侧导航上那一行必须逐字相同）');
}

// md 里的图片必须能被 md.js 认出（写错成 [](x) 就成了一个链接，页面上不会有图）
const refRe = /!\[([^\]]*)\]\(([^)\s]*)\)/g;
const noTarget = [];
const noAlt = [];
for (const [file, src] of Object.entries(mdText)) {
    // 代码块里的 `![...]` 是举例说明，不算引用 —— 本语料里目前没有，但先按规矩来
    const body = src.replace(/^```[\s\S]*?^```/gm, '');
    for (const m of body.matchAll(refRe)) {
        if (!m[1].trim()) {
            noAlt.push(file + '：![...] 里没写说明');
        }
        if (!m[2]) {
            noTarget.push(file + '：![...] 里没有地址');
        }
    }
}
assert(noAlt.length === 0 && noTarget.length === 0,
    '正文里的图都写全了（`![说明](地址)`）', noAlt.concat(noTarget).join('；'));

// ------------------------------------------------------------------
// 2. 反向：static/guide/ 下不许有约定之外的 md
// ------------------------------------------------------------------

const orphans = Object.keys(mdText).filter(f => !expected.has(f));
assert(orphans.length === 0, 'static/guide/ 下没有孤儿 md（写了没人看的那种）',
    '这些文件名对不上任何章：' + orphans.join(' / ')
    + '（约定：一份 md ＝一章，文件名就是章 id —— intro / 各模块 / standalone，'
    + '见 guide-data.js 文件头）');

// ------------------------------------------------------------------
// 3. 反抄写：定位那句只有 modules.js 一份（页面标题行豁免 —— 它本来就写在 md 里）
// ------------------------------------------------------------------

console.log('\n反抄写（定位那句只有 modules.js 一份；页面标题以 md 为准，不算抄）：');

/**
 * 比对用的一行：去掉标记与空白。
 * 两边的「去掉」必须一致 —— 一边留 `**` 一边不留，就会出现「看着一样却不相等」，
 * 那是最难查的假通过。
 */
function bare(s) {
    return String(s || '')
        .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')     // 图片 → 留说明
        .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')      // 链接 → 留文字
        .replace(/[*_~`#>|]/g, '')
        .replace(/\s+/g, '');
}

const registry = new Map();
for (const id of Object.keys(ModuleIndex)) {
    const page = ModuleIndex[id];
    for (const s of [page.title, page.desc]) {
        if (s && s.length >= 8) {
            registry.set(bare(s), id);
        }
    }
}

const lines = [];
for (const [file, src] of Object.entries(mdText)) {
    src.split('\n').forEach((text, i) => {
        // 标题行豁免：页面标题现在就写在 md 里（`## 标题`），与 modules.js 同名是**有意的**。
        // 这一条本来拦的是「正文里顺手抄一句定位」。正文行仍然一个字都不许抄
        if (/^#{1,6}\s/.test(text)) {
            return;
        }
        lines.push({file, no: i + 1, text: bare(text)});
    });
}

const copied = lines.filter(l => registry.has(l.text));
assert(copied.length === 0, '正文里没有整句抄自 modules.js 的 title / desc',
    copied.map(l => l.file + ':' + l.no + ' 抄了 ' + registry.get(l.text)).join('；'));

// 顺手拦一个更常见的手滑：整段以 desc 的前 20 字开头（= 把 desc 当正文首句用了）。
// 只判「开头」不判「包含」：正文里顺着 desc 的意思换个说法写一句，是正常的行文
const suspicious = lines.filter(l =>
    [...registry.keys()].some(text => l.text.length >= 20 && l.text.startsWith(text.slice(0, 20))));
assert(suspicious.length === 0, '没有整段以 desc 开头（把 desc 当正文首句）',
    suspicious.map(l => l.file + ':' + l.no).join(' / '));

// ------------------------------------------------------------------
// 4. md 的体例
// ------------------------------------------------------------------

console.log('\nmd 的体例：');

const h1 = [];
const figNo = [];
const crlf = [];
for (const [file, src] of Object.entries(mdText)) {
    // 行尾一律 LF。CRLF 会让 md.js 画出来的小节标题 id 多带一个 \r（`.$` 不认 `\r`，
    // 于是那一串控制字符就进了锚点）；块的切分已经两边都容忍了，但这类「看不见的字符
    // 混进 id / 属性」的毛病最难查，干脆不许它出现。编辑器里把行尾设成 LF 即可
    if (/\r/.test(src)) {
        crlf.push(file);
    }
    src.split(/\r?\n/).forEach((text, i) => {
        if (/^#\s/.test(text)) {
            h1.push(file + ':' + (i + 1));
        }
        // 图号由页面算（「第几张」是渲染时数出来的），手打一个「图 2」必然与真实序号漂移
        if (/图\s*\d/.test(text)) {
            figNo.push(file + ':' + (i + 1));
        }
    });
}
assert(h1.length === 0, 'md 里没有一级标题（`# `）—— 章的标题只有 modules.js 一份',
    '这些地方该用 `##`：' + h1.join(' / '));
assert(figNo.length === 0, 'md 里没有手打的图号',
    figNo.join(' / '));
assert(crlf.length === 0, 'md 的行尾一律是 LF（没有 CRLF）',
    '这些文件里有 \\r（多半是编辑器把行尾改成了 CRLF）：' + crlf.join(' / ')
    + ' —— 行尾设成 LF 再存一次即可');

// ------------------------------------------------------------------
// 5. 禁词表
// ------------------------------------------------------------------

console.log('\n禁词（规格不进面向用户的正文）：');

const BANNED = [
    'gaps_json', 'song_lyric_fill', 'WEAK_MATCH_RATIO', 'prolongationEnd', 'chainTail',
    'buildLines', 'LyricFillAligner', 'moduleExists', 'dropAbsentModules', 'PageGates',
    'needs', '0.85'
];
/** 正文（去掉了图片 / 链接的**地址**，那是 URL、不是写给用户看的话） */
const prose = Object.entries(mdText)
    .map(([file, src]) => ({
        file,
        text: src
            .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
            .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    }));

const hitBanned = [];
for (const {file, text} of prose) {
    for (const word of BANNED) {
        if (text.includes(word)) {
            hitBanned.push(file + ' 里有「' + word + '」');
        }
    }
}
assert(hitBanned.length === 0, '正文里没有规格词（列名 / 类名 / 阈值 / 键名）',
    hitBanned.join('；'));

/**
 * 「对象.成员」形态的代码标识符。文件名（启动.cmd）不算 —— 前一个字是汉字，本模式
 * 起不到头；`nya-entworks.yaml` 里 `alpha.yaml` 这种会被扫到，所以把已知的扩展名放行。
 */
const ALLOWED_SUFFIX = new Set(['js', 'cmd', 'bat', 'md', 'py', 'html', 'css', 'yaml', 'yml',
    'json', 'png', 'jpg', 'db', 'sql', 'lrc', 'svp', 'mid', 'txt', 'exe', 'csv']);
const hitIdent = [];
for (const {file, text} of prose) {
    for (const hit of text.matchAll(/[A-Za-z_$][A-Za-z0-9_$]*\.([A-Za-z_$][A-Za-z0-9_$]*)/g)) {
        if (!ALLOWED_SUFFIX.has(hit[1])) {
            hitIdent.push(file + ' 里有「' + hit[0] + '」');
        }
    }
}
assert(hitIdent.length === 0, '正文里没有「对象.成员」形态的标识符',
    hitIdent.join('；'));

/**
 * 跨模块的那两章（首次上手 / 系统工具）**一个字都不许点名模块或它的页面**。
 *
 * 为什么：模块是可以被裁掉的（`docs/桌面化与模块裁剪设计.md` §5 —— 「只要歌曲的那份构建」
 * 就是把配置里那两段删掉），而这两章在**任何**构建里都在。于是「漫画的几个目录」/
 * 「韵脚词典那几件」这种句子，在裁掉那个模块的构建里就成了一条指着不存在的东西的遗留信息
 * —— 用户 2026-09-30 定的口径。各模块自己的事写在它自己那一章里（那一章随模块一起消失），
 * 所以这里拦的只是这两章；模块**机制**的说法（「关掉的模块，左边导航里那一块也不见了」）
 * 不算点名，照样可以写。
 *
 * 判据是**按标题整串匹配**（`title` 就是左侧导航上那一行字）：子串匹配也是想要的 ——
 * 「韵脚词典那几件」里就含着一个页面名。跨模块章由 CHAPTERS 里**不属任何模块**的那两章推出来，
 * 不写死文件名，免得以后多一章这里漏掉。
 */
const CROSS_FILES = CHAPTERS.filter(ch => !moduleItems.some(m => m.id === ch.id))
    .map(ch => ch.id + '.md');
const moduleNames = [];
for (const m of moduleItems) {
    moduleNames.push({word: m.title, who: '模块 ' + m.id});
    for (const c of m.children) {
        moduleNames.push({word: c.title, who: '页面 ' + c.id});
    }
}
const hitModuleName = [];
for (const file of CROSS_FILES) {
    const text = mdText[file];
    if (text === undefined) {
        continue;                                   // 缺文件第 1 组已经报过
    }
    for (const {word, who} of moduleNames) {
        if (text.includes(word)) {
            hitModuleName.push(file + ' 里点了名「' + word + '」（' + who + '）');
        }
    }
}
assert(hitModuleName.length === 0,
    '跨模块的两章（' + CROSS_FILES.join(' / ') + '）里不点名任何模块或它的页面',
    hitModuleName.join('；') + ' —— 模块被裁掉时这些句子就成了遗留信息；'
    + '要讲的那件事请写进它所属的那一章，或者换成不点名的说法');

// ------------------------------------------------------------------
// 6. 合同句：两处都要这么说的那几句
// ------------------------------------------------------------------

console.log('\n合同句（填词页的折叠速查 ↔ 说明页）：');

const songfillSrc = fs.readFileSync(SONGFILL_JS, 'utf8');

/** 去掉标签、md 标记与空白再比 —— 一边加 <b>、一边用 **，不该算成「说法不同」 */
function plain(src) {
    return String(src).replace(/<[^>]*>/g, '').replace(/[*_~`]/g, '').replace(/\s+/g, '');
}

const mdPlain = plain(Object.values(mdText).join('\n'));
const songfillPlain = plain(songfillSrc);

/**
 * 每一句都是「用户照着做就会踩坑」的说法，两处必须一字不差 ——
 * 改一边忘另一边就红，这是把「重复」变成「同步」的那一招。
 */
const CONTRACTS = [
    '延音链之后',
    '每 5 秒自动保存',
    '只提示、不改分句',
    '导出回填模板'
];
for (const phrase of CONTRACTS) {
    // 短语本身也去一遍空白：两边写的是「每 5 秒自动保存」，去空白后是「每5秒自动保存」，
    // 不一起归一化就会因为一个空格判成「两处都缺」
    const p = plain(phrase);
    const inMd = mdPlain.includes(p);
    const inFill = songfillPlain.includes(p);
    assert(inMd && inFill, '「' + phrase + '」两处都有',
        (inMd ? '' : '说明页缺（static/guide/song.md）；')
        + (inFill ? '' : '填词页速查缺（songfill.js#helpHtml）；'));
}

// 指针句只剩**一个方向**：填词页速查顶部指去说明页。
// 反方向那句（说明页正文里的「使用说明（点开看）」）2026-09-30 由作者从 md 里去掉 ——
// 说明页正文是「怎么做」，回指一个页面里的折叠块属于页面上的事，不写在这儿。删了就不再钉它
assert(songfillPlain.includes('左下角「使用说明」'),
    '填词页速查顶部指去说明页（左下角「使用说明」）');

// ------------------------------------------------------------------
// 7. 图
// ------------------------------------------------------------------

console.log('\n图：');

const refs = [];
for (const [file, src] of Object.entries(mdText)) {
    for (const m of src.matchAll(refRe)) {
        refs.push({file, alt: m[1], src: m[2]});
    }
}

// 引用一律写成**相对 md 文件**的形式（md 在 static/guide/ 下，图在 static/img/guide/ 下）：
// 这样 md 拿去别的阅读器（编辑器预览 / GitHub 网页 / Typora…）也能显示图。页面里那份不受影响 ——
// 注入到静态根下之后，`../img/...` 对着 `/` 解析仍然落在 /img/...（第 9 组钉着 `../` 不被吞）
const IMG_PREFIX = '../img/guide/';

const problems = [];
const names = new Map();
for (const r of refs) {
    if (!r.src.startsWith(IMG_PREFIX)) {
        problems.push(r.file + ' 引的图不是相对 md 文件的 ../img/guide/ 形式：' + r.src);
        continue;
    }
    const name = r.src.slice(IMG_PREFIX.length);
    if (names.has(name)) {
        problems.push('图被引用了两次：' + name + '（' + names.get(name) + ' / ' + r.file + '）');
    }
    names.set(name, r.file);
    if (!/\.png$/i.test(name)) {
        problems.push('图不是 png：' + name);
    }
}

/** PNG 头的宽高。读不出 IHDR 就不是 PNG */
function pngSize(file) {
    const buf = fs.readFileSync(file);
    if (buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47) {
        return null;
    }
    return {w: buf.readUInt32BE(16), h: buf.readUInt32BE(20)};
}

let onDisk = 0;
for (const [name, file] of names) {
    const full = path.join(IMG_DIR, name);
    if (!fs.existsSync(full)) {
        continue;
    }
    onDisk++;
    const size = pngSize(full);
    if (!size || size.w <= 0 || size.h <= 0) {
        problems.push(name + ' 不是能读出 IHDR 的 PNG');
    }
}
assert(problems.length === 0,
    refs.length + ' 处图引用都合规（唯一、相对 md 文件写成 ../img/guide/…、是 png、真在的都读得出尺寸）',
    problems.join('；'));

/**
 * 反向：static/img/guide/ 里放着 md 没引用的图 = 一张没人看的截图（多半是改名后忘了改引用）。
 *
 * <p><b>白名单</b>：`settings-page.png` 是作者 2026-09-30 明确说「不管这张图」的那一张
 * （原话见《实施记录 · 目录改由 md 解析》），既不补引用也不删，所以它列在这儿。
 * 白名单条目**失效也判红**（被引用了、或盘上根本没有）—— 与
 * {@code FileOpCoverageTest#permanentDeletesAreAllJustified} 同一个做法：豁免名单不许烂在那儿。
 */
const UNREFERENCED_OK = ['settings-page.png'];
if (fs.existsSync(IMG_DIR)) {
    const onDir = fs.readdirSync(IMG_DIR);
    const dangling = onDir.filter(f => !names.has(f) && !UNREFERENCED_OK.includes(f));
    assert(dangling.length === 0,
        'static/img/guide/ 里没有 md 没引用的图' + (UNREFERENCED_OK.length
            ? '（白名单除外：' + UNREFERENCED_OK.join(' / ') + '）' : ''),
        '这些图放了却没人引：' + dangling.join(' / '));
    const stale = UNREFERENCED_OK.filter(f => names.has(f) || !onDir.includes(f));
    assert(stale.length === 0, '图的白名单里没有失效条目（被引用了 / 盘上已经不在了）',
        stale.map(f => f + (names.has(f) ? ' 已经被 md 引用了' : ' 盘上已经没有这张图了')).join('；')
        + ' —— 白名单是「明知不管」的清单，条目失效就该连同这一行一起删掉');
}

if (onDisk === 0) {
    console.log('       （' + refs.length + ' 张图还都是占位框（图还没截），'
        + '页面画成一个虚线框 + md 里那句说明 —— 把同名 png 放进 '
        + 'static/img/guide/ 就会自动变成图，不用改代码）');
} else {
    console.log('       （' + refs.length + ' 张图里已经有 ' + onDisk + ' 张真图，'
        + '还剩 ' + (refs.length - onDisk) + ' 张待补）');
}

// ------------------------------------------------------------------
// 8. 渲染器不许自己写文案（一个字都不行）
// ------------------------------------------------------------------

console.log('\n渲染器：');

/**
 * js 里出现「一整句中文」= 有人图省事把文案写进渲染里了（文案从此有两个来源）。
 * 注释（// 与 /*）不算，所以先删掉；判据是**中文字的个数**而不是字符串长度 ——
 * 标签名里有中文的只有 `>图 ` 这种一个字的，不该算成一句文案。
 */
function chineseLiterals(src) {
    const noComment = src
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/^\s*\/\/.*$/gm, '');
    // `[^'\\\n]*` 里的 \n 不能少：空字面量（`replace(/x/g, '')`）之后若允许跨行，
    // 正则会把「下一个引号」之前的一大段源码当成一个字面量，报出一串看不懂的东西
    return [...noComment.matchAll(/'([^'\\\n]*)'/g)]
        .map(m => m[1])
        .filter(s => (s.match(/[一-龥]/g) || []).length >= 4);
}

const guideLiterals = chineseLiterals(scriptSrc);
assert(guideLiterals.length === 0, 'guide.js 里没有成句的中文字面量（文案一律在 guide-data.js / md）',
    '这些字符串该搬走：' + guideLiterals.join(' / '));

const mdLiterals = chineseLiterals(mdSrc);
assert(mdLiterals.length === 0, 'md.js 里没有成句的中文字面量（它是纯渲染器，一个字文案都不该有）',
    '这些字符串该搬走：' + mdLiterals.join(' / '));

// ------------------------------------------------------------------
// 9. md.js 的冒烟：把一段「什么语法都用上」的样本渲染一遍
// ------------------------------------------------------------------

console.log('\nmd.js 冒烟：');

const SAMPLE = [
    '# 一级标题',
    '',
    '正文里有 <script>alert(1)</script>，也有 <b>标签</b>。',
    '',
    '**粗** 与 *斜* 与 `code` 与 ~~删~~，还有一个孤零零的 * 星号。',
    '这一行紧跟上一行（段落里的换行就是换行）。',
    '',
    '- 甲',
    '- 乙',
    '  - 乙一',
    '',
    '1. 一',
    '2. 二',
    '',
    '> 引用一行',
    '',
    '| A | B |',
    '| --- | ---: |',
    '| 1 | 2 |',
    '',
    '```',
    '<em>代码里的标签也不许原样出来</em>',
    '```',
    '',
    '---',
    '',
    '![说明图](../img/guide/x.png)',
    '',
    '[好链接](https://example.com)，[坏链接](javascript:alert(1))。'
].join('\n');

const out = Md.render(SAMPLE);

assert(!out.includes('<script>') && out.includes('&lt;script&gt;'),
    'HTML 被转义（正文里写 <script> 只会显示成字）');
assert(!out.includes('href="javascript:'), 'javascript: 链接被挡下（降级成纯文字）');
assert(out.includes('<strong>粗</strong>') && out.includes('<em>斜</em>')
    && out.includes('<code>code</code>') && out.includes('<del>删</del>'),
    '行内标记：加粗 / 斜体 / 行内代码 / 删除线');
assert(out.includes('孤零零的 * 星号'), '认不出的 * 原样吐回去（不吞字）');
assert(out.includes('<br>'), '段落里的换行就是换行（有意与 GitHub 不同，见 md.js 文件头）');
assert(out.includes('<ul>') && out.includes('乙一'), '列表（含嵌套）');
assert(out.includes('<ol>'), '有序列表');
assert(out.includes('<blockquote>'), '引用');
assert(out.includes('<table>') && out.includes('style="text-align:right"'), '表格（含对齐）');
assert(out.includes('<pre><code>') && out.includes('&lt;em&gt;'), '围栏代码块（内容照样转义）');
assert(out.includes('<hr>'), '分隔线');
assert(out.includes('class="md-imgwrap"') && out.includes('class="md-img"')
    && out.includes('class="md-imgph"'),
    '图片画成「图 + 一句说明」（图没加载出来时指南把说明画成占位框）');
assert(out.includes('src="../img/guide/x.png"'),
    '图地址原样进 src（相对路径那份 ../ 不许被吞 —— 页面里它对着静态根解析，仍是 /img/…）');
assert(out.includes('<a href="https://example.com" target="_blank" rel="noopener noreferrer">'),
    '外链带 target=_blank 与 rel=noopener');

// 标题 id：中文照留、重名的自动加后缀（两节用同一个 `## 保存` 是常事）
const dup = Md.render('## 保存\n\n## 保存\n\n# a\n\n# a');
assert(dup.includes('id="保存"') && dup.includes('id="保存-2"') && dup.includes('id="a-2"'),
    '重名标题的 id 自动去重');
assert(Md.render('') === '' && Md.render(null) === '', '空输入回空串（不画一个空段落）');

if (onDisk === 0 && fail === 0) {
    console.log('       （图还没有一张真图，所以「换成真图之后长什么样」这一条此刻只是纸面结论）');
}

// ------------------------------------------------------------------
// 10. 渲染冒烟：假 DOM 上把 render() 真跑一遍
// ------------------------------------------------------------------

console.log('\n渲染冒烟：');

/**
 * 「块 ↔ 页面按标题对名、没对上就现场合成 id」这条规则**只活在运行时**：第 1 组钉住了
 * 「每个页面都有一块同名」，但派生出来的 id 对不对、顺序是不是照 md、md 读不到时那几条
 * else 画成什么，静态都看不见。所以这里搭一套最小 DOM 把 render() 跑起来。guide.js 只用
 * 到三样东西 —— `host.innerHTML`、`host.firstElementChild` 上的 querySelectorAll /
 * querySelector / addEventListener —— 于是图那一层（wireImages）与目录联动（wireSpy，
 * 它先问 `typeof IntersectionObserver`，这儿没有，直接返回）在假 DOM 里空转。
 *
 * 场景可调，用来走那四条 else（都是新写的分支，最容易静默出错）：
 *   dead = 这一份 md 读不出来（缺文件 / 后端没起）；drop = 这一份 md 少切一块
 *   （末块那一行删掉，正文并进上一块）；rename = 末块的标题改一个字（于是它不再对得上
 *   任何页面 —— 该照画成一节、给合成锚点、不给按钮）；off = 这一章关着；
 *   settingsDown = GET /api/settings 挂了；sec = 带锚点的跳转；
 *   gaps = 给某个页面喂一条「缺这个根」（`App.gateMisses` 的替身）。
 *
 * 每个场景都在**新沙箱**里跑一遍：guide.js 的模块级状态（renderToken、滚动观察器）
 * 会跨调用留着，同一个沙箱里连跑两次，第二次看到的是第一次改过的对象。
 */
const SCEN = {
    dead: '', drop: '', rename: '', off: '', settingsDown: false, sec: '', gaps: null
};

function renderSmoke(scen) {
    Object.assign(SCEN,
        {dead: '', drop: '', rename: '', off: '', settingsDown: false, sec: '', gaps: null}, scen);

    // 章文件的内容照磁盘那一份；drop / rename 两个场景都动末块那一行
    const files = {...mdText};
    for (const [key, edit] of [['drop', line => null], ['rename', line => line + '（改过的）']]) {
        const file = SCEN[key];
        if (!file || !files[file]) {
            continue;
        }
        const arr = files[file].split('\n');
        for (let i = arr.length - 1; i >= 0; i--) {
            if (/^##\s/.test(arr[i])) {
                const next = edit(arr[i]);
                if (next === null) {
                    arr.splice(i, 1);
                } else {
                    arr[i] = next;
                }
                break;
            }
        }
        files[file] = arr.join('\n');
    }

    const sandbox = {
        console,
        Modules,
        ModuleIndex,
        GuideData,
        Md,
        // UI.esc 是 api.js 的；假 DOM 里只需要它转义这一半功能
        UI: {
            esc: s => String(s).replace(/[&<>"']/g,
                c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]))
        },
        Api: {
            get: async () => {
                if (SCEN.settingsDown) {
                    throw new Error('settings down');
                }
                return {
                    modules: moduleItems.map(m => ({
                        id: m.id,
                        toggle: {value: m.id === SCEN.off ? 'false' : 'true'}
                    }))
                };
            }
        },
        // 读的必须就是那份真文件（顺带盯住 GuideData.dir 没错位）
        Util: {
            text: async url => {
                const name = url.startsWith(GuideData.dir) ? url.slice(GuideData.dir.length) : url;
                if (name === SCEN.dead || files[name] === undefined) {
                    throw new Error('404 ' + url);
                }
                return files[name];
            }
        },
        // App.gateMisses 的替身：按页 id 喂一条缺根进去（真实那份读的是 PageGaps，见 app.js）
        window: {
            App: {
                navigate: () => {},
                gateMisses: page => (SCEN.gaps && SCEN.gaps[page.id]) || []
            }
        }
    };
    vm.createContext(sandbox);
    for (const file of [MODULES_JS, DATA_JS, MD_JS, SCRIPT_JS]) {
        vm.runInContext(fs.readFileSync(file, 'utf8'), sandbox, {filename: path.basename(file)});
    }

    let html = '';
    sandbox.__scrolled = null;
    sandbox.__host = {
        set innerHTML(v) {
            html = v;
            // 顺手记下正文里各节的出场顺序 —— 「节是不是照 md 的顺序画的」只能这么看，
            // 因为目录里也有一份 data-sec，两处混在一起数就没法分清
            sandbox.__rs = [...v.matchAll(/class="guide-page" data-sec="([^"]+)"/g)]
                .map(m => m[1]);
        },
        get innerHTML() {
            return html;
        },
        // `querySelector` 只认 guide.js 真正会问的那两族选择器：`[data-sec="…"]`
        // 与限定在正文里的 `.guide-body [data-sec="…"]`。
        //
        // **这一段是 2026-09-30 补的**，原先写的是 `querySelector: () => null` —— 于是
        // 「带 ?sec= 进来到底滚的是哪个元素」在假 DOM 里根本看不见，那个 bug 就从它眼皮
        // 底下过去了：目录项与正文节**都**带 data-sec，而目录在 `wrap` 里排在正文**前面**，
        // 不限定范围时真实 DOM 命中的是那条小小的目录链接（`scrollIntoView` 只把目录挪
        // 几百像素，正文纹丝不动 —— 症状就是「点目录不跳」，而 hash / 目录高亮全都正常）。
        // 所以这里照真实文档顺序实现：不限定 → 先命中目录那一条；限定在正文里 → 目录不算。
        get firstElementChild() {
            const tocIds = (() => {
                const m = /<nav class="guide-toc">([\s\S]*?)<\/nav>/.exec(html);
                return m ? [...m[1].matchAll(/data-sec="([^"]+)"/g)].map(x => x[1]) : [];
            })();
            const node = (kind, id) => ({
                kind, id,
                scrollIntoView: () => {
                    sandbox.__scrolled = {kind, id};
                }
            });
            return {
                querySelectorAll: () => [],
                querySelector: (sel) => {
                    const m = /\[data-sec="([^"]+)"\]/.exec(sel);
                    if (!m) {
                        return null;
                    }
                    const id = m[1];
                    const inBody = sandbox.__rs.indexOf(id) >= 0;
                    if (/\.guide-body|\.guide-page/.test(sel)) {
                        return inBody ? node('section', id) : null;
                    }
                    return tocIds.indexOf(id) >= 0 ? node('toc', id)
                        : (inBody ? node('section', id) : null);
                },
                addEventListener: () => {}
            };
        }
    };
    sandbox.__params = SCEN.sec ? {sec: SCEN.sec} : {};
    // 同一个沙箱里把 render() 跑完再取结果 —— 直接 runInContext('__host.innerHTML')
    // 会拿到赋值前那个空串
    return vm.runInContext(
        'GuidePage.render(__host, __params)'
        + '.then(() => ({html: __host.innerHTML, secSeq: __rs, scrolled: __scrolled}))',
        sandbox);
}

// 期望值全部由上面那份 sectionsOf 算出来（与 guide.js 的算法同源，只是取样方式差一点，
// 见它的注释）。顺序 = md 的顺序，id = 对上的页面的 id / 对不上就合成。
const flatSections = allSections;
const flatTitles = allSections.map(s => s.title);
const flatIds = allSections.map(s => s.id);
// 「打开这一页」= 这一节对上了**可路由**的页面（intro 那两节对不上、不属页面的块也对不上）；
// 「模块关着就不给按钮」这一条只在 off 场景里看，不在这个静态数里
const openable = allSections
    .filter(s => s.page && ModuleIndex[s.page.id] && ModuleIndex[s.page.id].render).length;

// 从这里往下要 await（CommonJS 的顶层不许），所以整段包进 async IIFE ——
// 体量那一组和最后的总账也跟着进来，否则它们在 render() 还没跑完时就先打印了
(async () => {

    const countIn = (h, re) => (h.match(re) || []).length;

    const clean = await renderSmoke({});

    const chapSeq = [...clean.html.matchAll(/data-chap="([^"]+)"/g)].map(m => m[1]);
    assert(chapSeq.join() === CHAPTERS.map(c => c.id).join(),
        '五章都画出来了，顺序与注册表一致：' + chapSeq.join(' / '),
        '画出来的是 ' + chapSeq.join(' / '));

    assert(clean.secSeq.join() === flatIds.join(),
        '每一节就是一个块、**顺序照 md**（共 ' + clean.secSeq.length + ' 节，跨章也对）',
        '画出来的是 ' + clean.secSeq.join(' / ') + '，该是 ' + flatIds.join(' / '));

    // 这一条是「按标题对名」的落点：对上了才拿得到那一页的 id，对不上就是合成的
    const titleSeq = [...clean.html.matchAll(/<h3 class="guide-page-title">([^<]*)/g)]
        .map(m => m[1].trim());
    assert(titleSeq.join() === flatTitles.join(),
        '每节的标题就是 md 里那一行（' + titleSeq.length + ' 个）',
        '页面上是 ' + JSON.stringify(titleSeq) + '，md 里是 ' + JSON.stringify(flatTitles));

    assert(countIn(clean.html, /data-act="page"/g) === openable,
        '真页面挂「打开这一页」按钮（' + openable + ' 个：可路由页减去不属页面的那几节）',
        '画出来 ' + countIn(clean.html, /data-act="page"/g) + ' 个，该有 ' + openable + ' 个');
    assert(countIn(clean.html, /href="#guide\?sec=/g) === flatSections.length,
        '目录里每节一条（' + flatSections.length + ' 条）');
    // **作者要的就是这一条**：目录的条目、顺序、文字逐条来自 md 的块
    const tocSeq = [...clean.html.matchAll(/data-sec="([^"]+)">([^<]*)<\/a>/g)]
        .map(m => m[1] + '|' + m[2]);
    assert(tocSeq.join() === flatSections.map(s => s.id + '|' + s.title).join(),
        '左侧目录的条目与顺序逐条来自 md 的块（' + tocSeq.length + ' 条）',
        '目录里是 ' + JSON.stringify(tocSeq) + '，该是 '
        + JSON.stringify(flatSections.map(s => s.id + '|' + s.title)));
    assert(countIn(clean.html, /class="hint"/g) === 0
        && countIn(clean.html, /class="md-body"/g) >= flatSections.length,
        '一切正常时一条提示都不挂、每节都画了正文');

    // 四条退化分支：新写的 else 就在这里
    const dead = await renderSmoke({dead: 'manga.md'});
    const deadManga = dead.secSeq.filter(id => id.startsWith('manga-'));
    assert(countIn(dead.html, /class="guide-chapter"/g) === CHAPTERS.length
        && countIn(dead.html, /class="hint"/g) === 1
        && deadManga.join() === CHAPTERS[1].pages.map(p => p.id).join(),
        '整章的 md 读不到：章照画、节退回注册表那一份照画，只在章首挂一条（不逐节喊）');

    // 少写一块 = 那一页从说明页上整个消失（第 1 组判红点名）；其余各节照常、也不挂提示
    const drop = await renderSmoke({drop: 'manga.md'});
    const dropManga = drop.secSeq.filter(id => id.startsWith('manga-'));
    assert(countIn(drop.html, /class="hint"/g) === 0
        && !dropManga.includes('manga-random')
        && dropManga.length === CHAPTERS[1].pages.length - 1,
        'md 里少一块：那一节整节不画、别的节照常、不挂任何提示（这是「没讲」不是「讲坏了」）');

    // 标题被改了一个字：那一块成了「不属任何页面」的一块 —— 照画、有锚点，但按钮没了
    const rename = await renderSmoke({rename: 'manga.md'});
    assert(rename.secSeq.filter(id => /^manga-b\d+$/.test(id)).length === 1
        && countIn(rename.html, /class="hint"/g) === 0
        && countIn(rename.html, /data-act="page" data-page="manga-b\d+"/g) === 0,
        '块标题对不上页面：照画成一节（合成锚点），不给「打开这一页」按钮');

    // 缺磁盘根：那一节挂一条 —— 逐项的文字透传 App.gateMisses（与那张遮罩同一句话）
    const gap = {label: '未归档目录', group: '漫画 · 路径', reason: '还没填'};
    const gapHtml = (await renderSmoke({gaps: {'manga-unarchived': [gap]}})).html;
    assert(countIn(gapHtml, /guide-gate/g) === 1 && gapHtml.includes('未归档目录')
        && gapHtml.includes('漫画 · 路径') && gapHtml.includes('还没填'),
        '这一页缺磁盘根时，那一节挂一条提示（逐项文字与遮罩同一份）');
    assert(countIn(clean.html, /guide-gate/g) === 0,
        '不缺根时一条都不挂（gateMisses 返回空）');

    const off = await renderSmoke({off: 'song'});
    assert(countIn(off.html, /guide-mod-off/g) === 1 && off.html.includes('>去系统配置</button>'),
        '模块关着：那一章章首挂提醒 +「去系统配置」');
    assert(countIn(off.html, /data-act="page" data-page="song-/g) === 0
        && countIn(off.html, /data-act="page" data-page="manga-/g) === CHAPTERS[1].pages.length,
        '模块关着：只那一章不给「打开这一页」按钮，别的章照常');

    const downHtml = (await renderSmoke({settingsDown: true})).html;
    assert(countIn(downHtml, /class="guide-chapter"/g) === CHAPTERS.length
        && downHtml.includes('class="hint hint-info"')
        && !downHtml.includes('guide-mod-off'),
        '后端没起：五章照画（按「都在」）+ 页顶一条 INFO，不瞎挂「关着」的提醒');

    const jump = await renderSmoke({sec: 'song-fill'});
    assert(countIn(jump.html, /data-sec=/g) === flatSections.length * 2,
        '带 ?sec= 进来（要滚到那一节）：画的还是同一张页面，不因为找不到锚点就半路歇了');
    // 光「页面画全了」不算数：真正要滚的是**正文那一节**。选择器漏了限定范围就会命中文档
    // 顺序在前的目录链接，滚动只挪几百像素 —— 症状是「点目录不跳」，而上面那条断言照样绿
    assert(jump.scrolled && jump.scrolled.kind === 'section' && jump.scrolled.id === 'song-fill',
        '带 ?sec= 进来滚的是正文那一节（不是左边目录里同 id 的那一条链接）',
        '滚动落到了 ' + JSON.stringify(jump.scrolled) + ' —— 目录项与正文节都带 data-sec，'
        + '查的时候必须限定在 .guide-body 里（目录排在前，不限定就先命中它）');
    const nowhere = await renderSmoke({sec: 'no-such-section'});
    assert(nowhere.scrolled === null
            && countIn(nowhere.html, /data-sec=/g) === flatSections.length * 2,
        '?sec= 给了一个不存在的锚点：页面照画，一次都不滚（不半路歇、也不瞎滚到顶上）');

    // ------------------------------------------------------------------
    // 11. 体量：正文在 md 里，guide-data.js 就该一直是一张薄清单
    // ------------------------------------------------------------------

    console.log('\n体量：');

    const dataKb = dataSrc.length / 1024;
    assert(dataKb <= 5, 'guide-data.js ' + dataKb.toFixed(1) + ' KB（薄清单，≤ 5 KB）',
        '它又长回成一份说明书了（' + dataKb.toFixed(1) + ' KB）—— 正文一律进 static/guide/ 的 md');

    if (totalKb > 200) {
        console.log('  WARN 说明页正文已经 ' + totalKb + ' KB（线是 200 KB）——'
            + '它每次进页面都要整批下载，别再让它长下去了');
    } else {
        ok('说明页正文 ' + totalKb + ' KB（在 200 KB 线内）');
    }

    console.log('\n' + (fail ? `FAILED（${fail} 条不通过，${pass} 条通过）` : `ALL OK（${pass} 条）`));
    process.exit(fail ? 1 : 0);
})();
