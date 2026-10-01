/**
 * 前端公共件（js/api.js 的 Util / UI 构件）的行为核对 —— 运行：node tools/page-util-check.js
 *
 * 为什么需要它：前端没有测试框架，而这几个函数是从五六个页面里合并出来的同一个实现
 * （原先各页各抄一份）。合并本身是「行为不能变」的重构，但页面得点开才看得出坏没坏，
 * 所以把纯逻辑部分搬到这里钉住 —— 改 api.js 里那几个公共件之前先跑它。
 *
 * 它做两件事：
 *   1. 把 api.js 抠出来在 node 里跑（不需要浏览器），逐个断言那几个公共件的行为；
 *   2. 扫一遍各页面 js，确认没有残留的旧本地实现（合并前那些定义），
 *      以及没有漏改的调用点（调了 qs() 却没有 Util.qs 前缀这类）。
 *
 * 覆盖不到的：真正要浏览器才能验的部分（modal / 真 DOM 事件）。
 * pagerBar 用一个假 container 测（它只用 innerHTML 与 querySelectorAll 两件事）、
 * toast 用一个假 #toast 元素 + 假定时器测（它只用 textContent / appendChild /
 * className / hidden / onclick 与一个 setTimeout）。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const JS_DIR = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js');
const API_JS = path.join(JS_DIR, 'api.js');

let pass = 0;
let fail = 0;

function ok(name) {
    console.log('  ok   ' + name);
    pass++;
}

function bad(name, detail) {
    console.log('  FAIL ' + name + (detail ? '  —— ' + detail : ''));
    fail++;
}

function eq(name, actual, expected) {
    if (actual === expected) {
        ok(name);
    } else {
        bad(name, `期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`);
    }
}

function contains(name, haystack, needle) {
    if (typeof haystack === 'string' && haystack.includes(needle)) {
        ok(name);
    } else {
        bad(name, `「${needle}」不在 ${JSON.stringify(haystack)}`);
    }
}

// ------------------------------------------------------------------
// 1. 把 api.js 在 node 里跑起来
// ------------------------------------------------------------------

const sandbox = {console};
vm.createContext(sandbox);
try {
    vm.runInContext(fs.readFileSync(API_JS, 'utf8'), sandbox, {filename: 'api.js'});
} catch (e) {
    bad('api.js 能在无 DOM 环境里加载', e.message);
    process.exit(1);
}
ok('api.js 能在无 DOM 环境里加载');

// api.js 用的是 const 声明，不会挂到 context 对象上，得在同一个 context 里取回来
const {Util, UI} = vm.runInContext('({Util, UI})', sandbox);
if (!Util) {
    bad('暴露了 Util', '未定义');
    process.exit(1);
}
if (!UI) {
    bad('暴露了 UI', '未定义');
    process.exit(1);
}

// ------------------------------------------------------------------
// 2. Util
// ------------------------------------------------------------------

console.log('\nUtil：');
eq('qs 编码值里的中文与空格', Util.qs('artist', '中 文'), 'artist=%E4%B8%AD%20%E6%96%87');
eq('qs 键不改动', Util.qs('originalTitle', 'x'), 'originalTitle=x');
eq('qs 编码 & 与 =', Util.qs('k', 'a&b=c'), 'k=a%26b%3Dc');

eq('num(null) 沉到最后', Util.num(null), -Infinity);
eq('num(undefined) 沉到最后', Util.num(undefined), -Infinity);
eq('num 字符串转数', Util.num('3.5'), 3.5);
eq('num(0) 不当成空', Util.num(0), 0);

eq('isCbzName 认小写', Util.isCbzName('a.cbz'), true);
eq('isCbzName 忽略大小写', Util.isCbzName('a.CBZ'), true);
eq('isCbzName 拒绝别的扩展名', Util.isCbzName('a.zip'), false);
eq('isCbzName 空名不算', Util.isCbzName(''), false);
eq('isCbzName null 不算', Util.isCbzName(null), false);

eq('mediaUrl 拼路径并编码',
    Util.mediaUrl('F:\\歌曲\\a b.mp4'),
    '/api/media/stream?path=F%3A%5C%E6%AD%8C%E6%9B%B2%5Ca%20b.mp4');

eq('variantsOf 缺字段给空数组', JSON.stringify(Util.variantsOf({})), '[]');
eq('variantsOf 原样返回', JSON.stringify(Util.variantsOf({variants: [{mainName: 'a'}]})),
    '[{"mainName":"a"}]');

// ------------------------------------------------------------------
// 3. UI 构件
// ------------------------------------------------------------------

console.log('\nUI 构件：');
eq('sortArrow 当前列升序', UI.sortArrow('a', 'a', 1), ' ↑');
eq('sortArrow 当前列降序', UI.sortArrow('a', 'a', -1), ' ↓');
eq('sortArrow 非当前列不给箭头', UI.sortArrow('a', 'b', 1), '');

const thHtml = UI.th('average', '平均分', '7%', 'average', 1);
contains('th 带宽度', thHtml, 'style="width:7%"');
contains('th 可排序标记', thHtml, 'class="sortable" data-sort="average"');
contains('th 带标签', thHtml, '平均分');
contains('th 带当前列箭头', thHtml, ' ↑');
eq('th 非当前列不带箭头', UI.th('average', '平均分', '7%', 'title', 1).includes('↑'), false);

/** pagerBar 只用 innerHTML 与 querySelectorAll，所以假 container 就够 */
function fakePager() {
    return {
        innerHTML: '',
        buttons: [],
        querySelectorAll() {
            return this.buttons;
        }
    };
}

const p1 = fakePager();
UI.pagerBar(p1, 250, 1, 50, () => {});
contains('pagerBar 报总数', p1.innerHTML, '共 250 条');
// 首页的 « 指向第 1 页 —— 合法页码，所以是高亮而不是禁用（‹ 才是禁用的那个）
contains('pagerBar 第 1 页时 « 高亮', p1.innerHTML, '<button data-pg="1" class="on">«</button>');
contains('pagerBar 第 1 页时 ‹ 禁用', p1.innerHTML, '<button disabled>‹</button>');
contains('pagerBar 第 1 页高亮', p1.innerHTML, 'class="on">1</button>');
contains('pagerBar 只在后面留省略号', p1.innerHTML, 'pager-gap');
eq('pagerBar 首页不留前省略号', (p1.innerHTML.match(/pager-gap/g) || []).length, 1);

const p5 = fakePager();
UI.pagerBar(p5, 250, 5, 50, () => {});
// 末页对称：» 指向最后一页（合法、高亮），› 越界才禁用
contains('pagerBar 末页时 » 高亮', p5.innerHTML, '<button data-pg="5" class="on">»</button>');
contains('pagerBar 末页时 › 禁用', p5.innerHTML, '<button disabled>›</button>');
eq('pagerBar 末页不留后省略号', (p5.innerHTML.match(/pager-gap/g) || []).length, 1);

// 10 页取第 5 页，两侧才都该有省略号（5 页取第 3 页会正好铺满，不该有）
const pMid = fakePager();
UI.pagerBar(pMid, 500, 5, 50, () => {});
eq('pagerBar 中间页两侧都有省略号',
    (pMid.innerHTML.match(/pager-gap/g) || []).length, 2);
contains('pagerBar 中间页两边页码都截断', pMid.innerHTML, 'data-pg="3"');
contains('pagerBar 中间页给出尾页按钮', pMid.innerHTML, 'data-pg="10"');

const pSmall = fakePager();
UI.pagerBar(pSmall, 30, 1, 50, () => {});
eq('pagerBar 不足一页时无省略号', pSmall.innerHTML.includes('pager-gap'), false);
contains('pagerBar 只有一页时 1 可点', pSmall.innerHTML, 'data-pg="1"');

const pZero = fakePager();
UI.pagerBar(pZero, 0, 1, 50, () => {});
contains('pagerBar 空数据仍按 1 页算', pZero.innerHTML, '共 0 条');

// 翻页回调：假 button 带 dataset.pg
const pClick = fakePager();
let wentTo = null;
pClick.buttons = [{dataset: {pg: '4'}, onclick: null}];
UI.pagerBar(pClick, 250, 2, 50, (p) => {
    wentTo = p;
});
pClick.buttons[0].onclick();
eq('pagerBar 点击回调给的是页码数字', wentTo, 4);

const pSelf = fakePager();
let selfCalls = 0;
pSelf.buttons = [{dataset: {pg: '2'}, onclick: null}];
UI.pagerBar(pSelf, 250, 2, 50, () => {
    selfCalls++;
});
pSelf.buttons[0].onclick();
eq('pagerBar 点当前页不回调', selfCalls, 0);

// ------------------------------------------------------------------
// 3.5 toast：成功提示自己消失，错误提示不主动消失（要人点掉）
// ------------------------------------------------------------------

console.log('\nUI.toast：');

/**
 * 假 #toast：只实现 toast() 用到的那几样。
 * textContent 赋值在真 DOM 里会清空子节点，这里必须一样 —— 否则跨次调用
 * 子节点会越堆越多，断言「这条提示的字」就成了几条拼起来。
 */
function fakeToastEl() {
    const el = {
        children: [],
        className: '',
        hidden: true,
        title: '',
        onclick: null,
        _text: '',
        appendChild(child) {
            this.children.push(child);
        },
        /** 条子上除关闭键以外的文字 */
        text() {
            return this.children.filter(c => c.className !== 'toast-close')
                .map(c => c.textContent).join('');
        },
        closeBtn() {
            return this.children.find(c => c.className === 'toast-close') || null;
        }
    };
    Object.defineProperty(el, 'textContent', {
        get() {
            return this._text;
        },
        set(v) {
            this._text = v;
            this.children.length = 0;
        }
    });
    return el;
}

/** 假 DOM + 只记账的定时器（排过几次、多长时间）。setTimeout 只被 toast 用来「自己消失」 */
function fakeToastDom() {
    const el = fakeToastEl();
    const timers = [];
    const dom = {
        el,
        timers,
        document: {
            getElementById: (id) => (id === 'toast' ? el : null),
            createElement: () => fakeToastEl()
        },
        setTimeout: (fn, ms) => {
            timers.push({fn, ms});
            return timers.length;
        },
        clearTimeout: () => {
        }
    };
    return dom;
}

/** 单独一个 context 跑 api.js：上面那个 sandbox 是「无 DOM 也要能加载」，别把它弄脏 */
const dom = fakeToastDom();
const toastSandbox = {
    console,
    document: dom.document,
    setTimeout: dom.setTimeout,
    clearTimeout: dom.clearTimeout
};
vm.createContext(toastSandbox);
vm.runInContext(fs.readFileSync(API_JS, 'utf8'), toastSandbox, {filename: 'api.js'});
const toastUI = vm.runInContext('UI', toastSandbox);

toastUI.err('扫描失败：F:\\MangaGroup 下没有 9- 分区');
eq('错误提示画出来了', dom.el.text(), '扫描失败：F:\\MangaGroup 下没有 9- 分区');
eq('错误提示没被藏起来', dom.el.hidden, false);
eq('错误提示带错误样式', dom.el.className, 'toast toast-err');
eq('错误提示不排定时器（不自动消失）', dom.timers.length, 0);
eq('错误提示右侧挂着关闭键', !!dom.el.closeBtn(), true);
eq('错误提示能点（挂了 onclick）', typeof dom.el.onclick, 'function');

// 正压着一条错误时，后来的成功提示不许顶掉它 —— 顶掉就等于错误自己溜走了
toastUI.ok('已保存');
eq('错误还在时成功提示不覆盖', dom.el.text(), '扫描失败：F:\\MangaGroup 下没有 9- 分区');
eq('错误还在时成功提示也不排定时器', dom.timers.length, 0);

// 新的错误顶掉旧的错误（两条都是要人点掉的，没道理叠着）
toastUI.err('第二条错误');
eq('新错误顶掉旧错误', dom.el.text(), '第二条错误');
eq('新错误同样不排定时器', dom.timers.length, 0);

// 手动点掉：错误提示唯一的出口
dom.el.onclick();
eq('点一下就把错误提示收起来', dom.el.hidden, true);

// 收起来之后，成功提示照旧自己消失（2.5 秒）
toastUI.ok('已保存');
eq('成功提示画出来了', dom.el.text(), '已保存');
eq('成功提示不带错误样式', dom.el.className, 'toast');
eq('成功提示排了一个定时器', dom.timers.length, 1);
eq('成功提示 2.5 秒后消失', dom.timers[0].ms, 2500);

// ------------------------------------------------------------------
// 4. 页面侧：没有残留的旧实现、没有漏改的调用点
// ------------------------------------------------------------------

console.log('\n各页面 js：');

/**
 * 合并后这些函数只该有一个实现：
 *   owner  —— 调用方该走的路径
 *   home   —— 允许持有该实现的文件（api.js 之外，如 MangaReader.archivedOpts 就在 mangareader.js）
 */
const MOVED = [
    {name: 'qs', owner: 'Util.qs'},
    {name: 'num', owner: 'Util.num'},
    {name: 'isCbzName', owner: 'Util.isCbzName'},
    {name: 'mediaUrl', owner: 'Util.mediaUrl'},
    {name: 'variantsOf', owner: 'Util.variantsOf'},
    {name: 'pagerBar', owner: 'UI.pagerBar'},
    {name: 'archivedOpts', owner: 'MangaReader.archivedOpts', home: 'mangareader.js'}
];
/** 允许保留薄包装的文件（绑定本模块状态 / 常量，不是重复实现） */
const WRAPPERS = {
    'songaction.js': ['pagerBar', 'variantsOf'],
    'mangadict.js': ['pagerBar'],
    'mangatag.js': ['pagerBar'],
    'songartist.js': ['th'],
    'songstat.js': ['th', 'arrow'],
    'songplayer.js': ['idOf']
};

/**
 * 是不是「函数定义」。
 * 光看 `const x =` 会把局部变量也算进来（mangareader.js 有 `const num = bar.querySelector(...)`、
 * task-page.js 有 `const qs = new URLSearchParams()`），所以右侧必须是函数形态或已知的转发目标。
 */
const ALIAS = /^(function\b|\(|Util\.|UI\.|SongActions\.|MangaReader\.|SongPlayer\.|MangaCard\.)/;
function definesFunction(src, name) {
    if (new RegExp(`^\\s*function\\s+${name}\\s*\\(`, 'm').test(src)) {
        return true;
    }
    const m = src.match(new RegExp(`^\\s*const\\s+${name}\\s*=\\s*(.*)$`, 'm'));
    return !!m && ALIAS.test(m[1].trim());
}

const files = fs.readdirSync(JS_DIR).filter(f => f.endsWith('.js') && f !== 'api.js');
let leftovers = 0;
for (const file of files) {
    const src = fs.readFileSync(path.join(JS_DIR, file), 'utf8');
    for (const {name, owner, home} of MOVED) {
        if (!definesFunction(src, name)) {
            continue;
        }
        if ((WRAPPERS[file] || []).includes(name) || home === file) {
            ok(`${file} 持有 ${name}：${home === file ? '实现所在处' : '薄包装'}（允许）`);
            continue;
        }
        leftovers++;
        bad(`${file} 仍有本地 ${name} 定义`, `应当只用 ${owner}`);
    }
    // 调用点：裸调 name( 而没有 owner 前缀
    for (const {name, owner, home} of MOVED) {
        const bare = new RegExp(`(?<![\\w.$])${name}\\s*\\(`, 'g');
        const hits = (src.match(bare) || []).length;
        if (hits === 0) {
            continue;
        }
        if ((WRAPPERS[file] || []).includes(name) || home === file) {
            // 薄包装自身 / 同文件内的调用都算正常
            ok(`${file} 里 ${name} 的调用走本文件`);
            continue;
        }
        bad(`${file} 里有 ${hits} 处未加前缀的 ${name}(`, `应当走 ${owner}`);
    }
}
if (leftovers === 0) {
    ok('所有页面都用的是合并后的公共件');
}

// 断言旧的本地实现确实没了（防止脚本自身的正则写错导致「没查到」被当成通过）
const apiSrc = fs.readFileSync(API_JS, 'utf8');
for (const {name, owner} of MOVED) {
    if (owner.startsWith('Util.') || owner.startsWith('UI.')) {
        const short = owner.split('.')[1];
        contains(`api.js 暴露了 ${owner}`, apiSrc, short);
    }
}

// ------------------------------------------------------------------
// 5. 交叉引用：页面写的 Util.x / UI.x / MangaReader.x … 必须真的有这个成员
//
// 上面那节只能证明「没有旧的本地实现」，证明不了「改出来的调用点名字是对的」——
// Util.qso( 这种拼错静态扫不出来，要等点开页面才炸。所以这里拿运行时的成员表对一遍：
// Util / UI 直接从上面跑过的 context 里 Object.keys 取（最可靠），
// 其余模块解析各文件 IIFE 结尾的 return {...}。
// ------------------------------------------------------------------

console.log('\n交叉引用：');

/** 模块名 → 成员集合。api.js 的三个用运行时真值，其余解析导出列表 */
const exportsOf = {
    Util: new Set(Object.keys(Util)),
    UI: new Set(Object.keys(UI))
};
const MODULE_FILE = {
    Api: 'api.js', Util: 'api.js', UI: 'api.js',
    MangaReader: 'mangareader.js', MangaCard: 'mangacard.js',
    SongActions: 'songaction.js', SongPlayer: 'songplayer.js',
    SongTag: 'songtag.js', SongForm: 'songform.js', MangaTagPicker: 'mangatagpicker.js'
};
for (const [mod, file] of Object.entries(MODULE_FILE)) {
    if (exportsOf[mod]) {
        continue;
    }
    const src = fs.readFileSync(path.join(JS_DIR, file), 'utf8');
    // IIFE 结尾那个 return {...}; 后面紧跟 })();
    const m = src.match(/return\s*\{([^}]*)\}\s*;?\s*\}\)\(\);/);
    if (!m) {
        bad(`解析 ${mod} 的导出列表`, `在 ${file} 里没找到 IIFE 结尾的 return {...}`);
        continue;
    }
    exportsOf[mod] = new Set(m[1].split(',')
        .map(x => x.trim().split(':')[0].trim())
        .filter(Boolean));
    ok(`解析出 ${mod} 的 ${exportsOf[mod].size} 个导出成员`);
}

let badRefs = 0;
for (const file of fs.readdirSync(JS_DIR).filter(f => f.endsWith('.js'))) {
    const src = fs.readFileSync(path.join(JS_DIR, file), 'utf8');
    for (const [mod, members] of Object.entries(exportsOf)) {
        for (const hit of src.matchAll(new RegExp(`(?<![\\w.$])${mod}\\.(\\w+)`, 'g'))) {
            const member = hit[1];
            if (!members.has(member)) {
                badRefs++;
                bad(`${file} 引用了不存在的 ${mod}.${member}`,
                    `${mod} 的导出里没有它`);
            }
        }
    }
}
if (badRefs === 0) {
    ok('所有跨模块引用都指向真实存在的导出成员');
}

// ------------------------------------------------------------------
// 5. index.html：注释真的闭合、启动时按 id 找的元素都在、引的 js 都存在
// ------------------------------------------------------------------
// 为什么要有这一节：2026-09-28 往左下角加「使用说明」按钮时，新写的 HTML 注释
// 顺手用 JS 的 `*/` 收了尾（而不是 `-->`）。浏览器于是把**从那行起到下一个 `-->`
// 为止的整段标记当成注释吞掉** —— 被吞掉的是 guide-btn、env-btn、`</nav>` 与整个
// `<main>`。症状不是「这一页坏了」，而是**每一页都刷不出来**（app.js 在最后
// `getElementById('env-btn').onclick` 上抛 TypeError，`boot()` 里 route() 也一起挂），
// 而控制台之外没有任何提示：静态资源照常 200、`curl` 出来的 HTML 也一个字不缺。
// 这类错的共性是「源码看着全对、浏览器解析出来不是那样」，所以只能靠**看懂 HTML 注释
// 规则**的机检来拦，不能靠 grep 字符串（那几个 id 在被吞掉的那段里照样字符串匹配得到）。

console.log('\nindex.html：');

const HTML_FILE = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'index.html');
const htmlSrc = fs.readFileSync(HTML_FILE, 'utf8');

/** 抠掉注释，顺便记下每条注释的正文与「有没有闭合」 */
const commentBodies = [];
let stripped = '';
let unterminated = null;
{
    let i = 0;
    while (i < htmlSrc.length) {
        const open = htmlSrc.indexOf('<!--', i);
        if (open < 0) { stripped += htmlSrc.slice(i); break; }
        stripped += htmlSrc.slice(i, open);
        const close = htmlSrc.indexOf('-->', open + 4);
        if (close < 0) { unterminated = htmlSrc.slice(open, open + 80); break; }
        commentBodies.push(htmlSrc.slice(open + 4, close));
        i = close + 3;
    }
}
if (unterminated) {
    bad('每条 <!-- 都有配对的 -->', `这一条到文件结束都没闭合：${JSON.stringify(unterminated)}`);
} else {
    ok(`每条 <!-- 都有配对的 -->（共 ${commentBodies.length} 条）`);
}

// 注释正文里出现 JS 块注释的尾巴，几乎一定是 `-->` 打成了 `*/`（上面那次的写法）。
// 真去查「它到底会不会提前闭合」也行，但这条更直给：正常人不会在 HTML 注释里写 `*/`。
const starEnds = commentBodies.filter(c => c.includes('*/'));
if (starEnds.length) {
    bad('注释正文里没有 JS 块注释的尾巴 */',
        `${starEnds.length} 条命中，第一条：${JSON.stringify(starEnds[0].slice(-40))}`
        + ' —— 多半是 `-->` 打成了 `*/`（会把后面的标记整段吞掉）');
} else {
    ok('注释正文里没有 JS 块注释的尾巴 */');
}

// app.js 是启动文件：它在加载时按 id 取元素并直接挂 onclick，取不到就抛，
// 一抛整站不渲染（这一次就是这么死的）。所以它对 id 的要求是硬要求。
const APP_JS = path.join(JS_DIR, 'app.js');
const appSrc = fs.readFileSync(APP_JS, 'utf8');
const wanted = [...new Set([...appSrc.matchAll(/getElementById\(\s*'([^']+)'\s*\)/g)].map(m => m[1]))];
const missing = wanted.filter(id => !stripped.includes('id="' + id + '"'));
if (missing.length) {
    bad('app.js 启动时取的每个 id 都在 index.html 里',
        `缺 ${missing.join(' / ')} —— app.js 会在挂事件那几行抛 TypeError，整站不渲染`);
} else {
    ok(`app.js 启动时取的 ${wanted.length} 个 id 都在 index.html 里`);
}

// 同一个 id 出现两次时 getElementById 只认第一个，另一个永远取不到（静默）
const dupIds = [];
{
    const seen = new Map();
    for (const m of stripped.matchAll(/\sid="([^"]+)"/g)) {
        seen.set(m[1], (seen.get(m[1]) || 0) + 1);
    }
    for (const [id, n] of seen) {
        if (n > 1) { dupIds.push(id); }
    }
}
if (dupIds.length) {
    bad('index.html 里没有重复的 id', dupIds.join(' / '));
} else {
    ok('index.html 里没有重复的 id');
}

// 引错文件名只会 404，页面上一点提示都没有（脚本不加载 = 那一页功能悄悄少一块）
const STATIC_DIR = path.join(__dirname, '..', 'src', 'main', 'resources', 'static');
const refs = [];
for (const m of stripped.matchAll(/<script[^>]*\ssrc="([^"]+)"/g)) { refs.push(m[1]); }
for (const m of stripped.matchAll(/<link[^>]*\shref="([^"]+)"/g)) { refs.push(m[1]); }
const deadRefs = refs
    .map(r => r.split('?')[0])
    .filter(r => !/^https?:|^\/\//.test(r))
    .filter(r => !fs.existsSync(path.join(STATIC_DIR, r)));
if (deadRefs.length) {
    bad('index.html 引的本地资源都存在', deadRefs.join(' / '));
} else {
    ok(`index.html 引的 ${refs.length} 个本地资源都存在`);
}

console.log('\n' + (fail ? `FAILED（${fail} 条不通过，${pass} 条通过）` : `ALL OK（${pass} 条）`));
process.exit(fail ? 1 : 0);
