/**
 * 配置页开关（js/settings.js 的 toggleBtn / 点击处理）的行为核对 —— 运行：node tools/settings-toggle-check.js
 *
 * 为什么需要它：这段代码的错**看着是对的**。2026-09-18 把开关改成仿 iOS 的 UISwitch 之后，
 * 点击处理里留着一句 `btn.textContent = ...`（「已开启 / 已关闭」那版的遗留），而圆钮
 * 正是按钮里那个 `<span class="set-toggle-knob">` —— 点一下 `<span>` 就被文字节点顶掉，
 * 开关变成一个空胶囊。但 `data-bool` 确实改了、保存也确实存下去了，只有「画」坏掉，
 * 而「画」得用眼睛看才发现。所以这里把「点完之后 DOM 该长什么样」钉住：
 *
 *   1. 每个 `<button class="set-toggle">` 里必须那颗 `<span class="set-toggle-knob">` 还在；
 *   2. 按钮里**不许有汉字文本**（有就说明有人在往 `textContent` 里写字）；
 *   3. 无障碍属性（`role` / `aria-checked` / `aria-label`）齐全，开的那颗带 `.on`；
 *   4. 点一下之后，上面这三条仍然成立。
 *
 * 它做两件事：在 node 的 `vm` 里加载真的 `settings.js`（不引浏览器），喂一个假的 `view`
 * 把页面画出来，然后对一个假按钮真跑一次点击处理。
 *
 * 覆盖不到的：CSS 本身（尺寸、颜色、位移量在 `app.css` 里，得用眼睛看或量 `getBoundingClientRect`）、
 * 真浏览器的事件与焦点行为。这两样改完请手动开一次配置页。
 *
 * 改 `settings.js` 里 `toggleBtn` / 那段点击处理之前先跑它。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SETTINGS_JS = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'settings.js');

let pass = 0;
let fail = 0;

function ok(msg) {
    pass++;
    console.log('  ok   ' + msg);
}

function bad(msg) {
    fail++;
    console.log('  FAIL ' + msg);
}

function assert(cond, msg) {
    if (cond) {
        ok(msg);
    } else {
        bad(msg);
    }
}

/** 一个够用的假元素：能挂事件、能存 innerHTML，子节点查询返回空 */
function el(tag) {
    const e = {
        tagName: tag, dataset: {}, style: {}, title: '', textContent: '', value: '',
        classList: { toggle() {}, add() {}, remove() {}, contains() { return false; } },
        setAttribute() {}, getAttribute() { return null; }, addEventListener() {},
        appendChild() {}, remove() {}, focus() {}, onclick: null, oninput: null,
        _html: '',
        querySelector() { return el('div'); },
        querySelectorAll() { return []; }
    };
    Object.defineProperty(e, 'innerHTML', {
        get() { return e._html; },
        set(v) { e._html = v; }
    });
    return e;
}

/** 页面上会出现的两项开关：一开一关，用来验 `.on` 只给开的那颗 */
const VIEW = {
    filePath: 'config/nya-entworks.yaml', fileExists: true, backupPath: '', backupExists: false,
    modules: [], pendingCount: 0, notices: [], secrets: [],
    secretFilePath: 'config/application-secret.yaml',
    groups: [
        {
            group: '核对用', module: null, fields: [
                { key: 'nya-entworks.x.on', label: '开着的开关', desc: 'd', type: 'BOOL',
                    value: 'true', overridden: false, pending: false, required: false },
                { key: 'nya-entworks.x.off', label: '关着的开关', desc: 'd', type: 'BOOL',
                    value: 'false', overridden: false, pending: false, required: false }
            ]
        }
    ]
};

/** 从一段 HTML 里抠出所有开关按钮 —— 不用 DOM 解析器，按钮里没有嵌套的 `</button>` */
function togglesOf(html) {
    return html.match(/<button[^>]*class="set-toggle[^"]*"[\s\S]*?<\/button>/g) || [];
}

/** 一个按钮该有的样子。返回它的 data-key，供点击环节回查 */
function checkShape(btn, label) {
    assert(/<span class="set-toggle-knob"><\/span>/.test(btn),
        label + ' 里有那颗圆钮 <span class="set-toggle-knob">');
    assert(/role="switch"/.test(btn), label + ' 带 role="switch"');
    assert(/aria-checked="(true|false)"/.test(btn), label + ' 带 aria-checked');
    assert(/aria-label="[^"]+：(开|关)"/.test(btn), label + ' 带「标签：开/关」的 aria-label');
    assert(!/>[^<]*[一-龥][^<]*<\/button>/.test(btn),
        label + ' 里没有汉字文本（有就说明在往 textContent 写字，会把圆钮顶掉）');
}

async function main() {
    const src = fs.readFileSync(SETTINGS_JS, 'utf8');

    const ctx = {
        console,
        setTimeout,
        clearTimeout,
        document: {
            createElement: el,
            getElementById: () => el('div'),
            querySelector: () => el('div')
        },
        window: { addEventListener() {} },
        location: { hash: '' },
        UI: {
            esc: s => String(s), toast() {}, ok() {}, err() {}, spinner() { return ''; },
            empty() { return ''; }, withBusy(f) { return f(); }, modal() {}, confirm() {},
            pagerBar() { return ''; }, sortArrow() { return ''; }, th() { return ''; }
        },
        Api: {
            get: async () => VIEW,
            post() {}, put() {}, del() {}
        }
    };
    ctx.globalThis = ctx;
    vm.createContext(ctx);
    vm.runInContext(src, ctx);

    const SettingsPage = vm.runInContext('SettingsPage', ctx);

    // 第二个开关（关着的那颗）的假按钮：bind() 会把真的点击处理挂到它身上，
    // 所以下面调的就是生产代码本身，不是从源码里抠出来的一段字符串
    const btn = el('button');
    btn.dataset = { key: 'nya-entworks.x.off', bool: 'false', label: '关着的开关' };
    let sawOnClass = null;
    btn.classList.toggle = (cls, v) => { if (cls === 'on') { sawOnClass = v; } };
    const attrs = {};
    btn.setAttribute = (k, v) => { attrs[k] = v; };
    btn.attrs = attrs;

    const host = el('div');
    host.querySelectorAll = sel => (sel === '.set-toggle' ? [btn] : []);
    await SettingsPage.render(host).catch(e => console.log('  （render 收尾时报了 ' + e.message
        + '，桩的环境不全，不影响下面这几条）'));

    console.log('画出来的开关：');
    const toggles = togglesOf(host.innerHTML);
    assert(toggles.length === 2, '两个布尔项画出两个开关（实际 ' + toggles.length + ' 个）');
    if (toggles.length !== 2) {
        return;
    }
    toggles.forEach((t, i) => checkShape(t, '第 ' + (i + 1) + ' 个'));
    assert(/class="set-toggle on"/.test(toggles[0]), '开的那颗带 .on（绿轨道）');
    assert(!/class="set-toggle on"/.test(toggles[1]), '关的那颗不带 .on（灰轨道）');

    // ---- 点一下 ----
    console.log('点一下关着的那颗：');
    assert(typeof btn.onclick === 'function', 'bind() 给开关挂上了点击处理');
    if (typeof btn.onclick !== 'function') {
        return;
    }
    btn.onclick();

    assert(btn.dataset.bool === 'true', '点完 data-bool 翻成 true');
    assert(sawOnClass === true, '点完加上 .on（轨道变绿、圆钮滑过去）');
    assert(attrs['aria-checked'] === 'true', '点完 aria-checked 跟着翻');
    assert(attrs['aria-label'] === '关着的开关：开', '点完 aria-label 跟着翻');
    assert(btn.title === '已开启，点击关闭', '点完 title 跟着翻');
    assert(btn.textContent === '', '点完没往 textContent 写字（写了就把圆钮顶掉了）');
}

main().then(() => {
    console.log('\n' + (fail === 0 ? 'PASS' : 'FAILED') + '：通过 ' + pass + '，失败 ' + fail);
    process.exit(fail === 0 ? 0 : 1);
}).catch(e => {
    console.error(e);
    process.exit(1);
});
