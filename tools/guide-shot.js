/**
 * 使用说明页（#guide）的截图工具 —— 运行：`node tools/guide-shot.js`（零依赖）。
 *
 * 干什么：起一个无头 Chrome，逐个打开本机真实页面，**在页面里先把不该露出去的东西换掉、
 * 把要指的地方画上图例**，再截一张 PNG 落到 `src/main/resources/static/img/guide/`。
 * md 里早就写着 `![说明](../img/guide/<名字>.png)`（相对 md 文件的那一份），图放进去就生效 —— 不用改 md、不用改 JS。
 *
 *   node tools/guide-shot.js                    全部镜头
 *   node tools/guide-shot.js song-fill          名字里含这个子串的（先试一张再跑全部）
 *   node tools/guide-shot.js --list             只列镜头，不动手
 *   node tools/guide-shot.js --rect #settings a,b 打开一页，把几个选择器的框打出来
 *                                               （调 crop / 标注位置时用，不截图）
 *
 * <b>三条硬规矩</b>（改之前先看）：
 *
 * 1. **填词页不许派发任何输入事件**。那一页每 5 秒自动保存一次，保存就写
 *    `song_lyric_fill` —— 用 CDP 往填写格里 `keyDown` / `insertText`，就是**真写库**，
 *    而且会覆盖用户正在编辑的那一份。所以本工具只对填词页做「打开 / 悬浮 / 直接改 DOM」；
 *    直接改 DOM 的 `value` 不走 `input` 事件、不置脏，因此那 5 秒自动保存不会启动。
 * 2. **截图前必须把非原曲页面的真实内容换掉**。漫画名 / 作者名 / 歌名 / 文件名 / 路径 /
 *    封面图，一样都不许留在图里。做法不是打码，是**换示例文字 + 换占位图**
 *    （`gs.text` / `gs.img` / `gs.scrub`），这样图里的东西仍然看得懂、仍然像真的。
 *    **原曲页（`song-stat`）是唯一例外**：那两张保留真实名称（作者 2026-09-30 定）。
 * 3. **不许点任何破坏性按钮**。打分 / 归档 / 删除 / 改名 / 保存 / 同步 / 重启 —— 都不能碰。
 *    每条镜头的 `prep` 只允许：改文字、换图、加标注、点「打开某个浮层 / 切个页签」这类
 *    纯展示动作，以及**只读**的导出预览（`#f-export` → 回填模板文本，后端只读、不写库）。
 *
 * <b>为什么不用 fixture 假数据</b>：页面多、接口散（/api 下上百个端点），给每一页造一份假
 * 响应既写不完也养不住。换文字这一步放在**渲染完之后**，于是界面结构与真实页面逐字一致，
 * 只有内容被换成示例 —— 这也是「图看着像真的」的来源。
 *
 * <b>Chrome 136+ 的坑</b>（照 docs/已完成/使用说明页实施计划.md §5 实测）：
 * `--remote-debugging-port` 在默认 user-data-dir 下会被**静默忽略**（端口不通、日志一句话
 * 都没有），所以必须永远带一个独立目录；`--headless=old` 在 132 起已移除。取 ws 地址走
 * `/json/list`（GET），别用 `/json/new`（较新 Chrome 只收 PUT，返回 405）。
 *
 * <b>两个库外副作用</b>（页面自己就有的行为，不是本工具引入的，且都幂等 / 只补空行）：
 * 打开原曲页、以及点「播放」，后端会顺手给缺记录的原曲补一条 `song_original_setting` 行。
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const {spawn} = require('child_process');

const ROOT = path.join(__dirname, '..');
const OUT_DIR = path.join(ROOT, 'src', 'main', 'resources', 'static', 'img', 'guide');
const BASE = 'http://localhost:50721';

// 标注那个蓝色。带两个用它的地方：页面里的高亮/编号（PAGE_HELPERS 里的 COLOR）与
// 拼图下方图例带的编号徽标（legendHtml）—— 改一处就得改另一处，否则徽标与牌子对不上色
const CLIP_COLOR = '#5b9dff';

const CHROME_CANDIDATES = [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    process.env.CHROME_PATH
].filter(Boolean);

// ==================================================================
// 一、页面内工具：脱敏（换文字 / 换图 / 抹路径）与标注（高亮框 + 编号 + 箭头）
// ==================================================================

/**
 * 注入到页面里的 `window.gs`。**只在这里写一次**，每条镜头的 `prep` / `mark` 都是用它
 * 拼出来的一小段 JS。
 *
 * 为什么标注画在页面里、而不是拿坐标去画在 PNG 上：坐标要跟着页面走（字体、缩放、内容
 * 长短都会挪），在页面里画就等于「让浏览器自己算」。画完直接截图，标注就是图的一部分 ——
 * md 里仍是一句普通的 `![](…)`，不需要任何新语法。
 *
 * 分两步走（`prep` 改内容、`mark` 画标注）是为了让标注知道**这次要截哪一块**：
 * 裁剪范围是 `prep` 跑完才量得准的，而牌子要摆在裁剪范围里才不会白画。
 */
const PAGE_HELPERS = `(() => {
  const gs = window.gs = {};
  const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

  /** 示例文字池：写死在这儿，是为了同一批图里的示例名互相对得上（每张各造一套会看着像拼的） */
  const FAKE = {
    manga: ['[示例] 星海旅团 总集篇', '[示例] 夏日回声 第01-03话', '[示例] 银岭夜行',
            '[示例] 纸上食堂 合订本', '[示例] 风与齿轮', '[示例] 旧书店的午后'],
    artist: ['[示例] 绫濑', '[示例] 桐生', '[示例] 白鸟', '[示例] 三枝'],
    song: ['[示例] 夜航星', '[示例] 折光', '[示例] 春分之后', '[示例] 沉默的港口'],
    line: ['夜航星折光', '春分之后', '沉默的港口', '风停了', '海的尽头',
           '火车开过', '你不在这里', '灯还亮着'],
    word: ['夜', '航', '星', '折', '光', '春', '分', '之', '后', '沉', '默', '的', '港', '口'],
    path: ['[示例盘]\\\\示例目录\\\\示例文件', '[示例盘]\\\\示例目录\\\\另一份'],
    tag: ['示例标签一', '示例标签二', '示例标签三']
  };
  gs.FAKE = FAKE;
  gs.wait = (ms) => new Promise(r => setTimeout(r, ms));
  gs.el = (sel, i) => $$(sel)[i || 0] || null;
  gs.byText = (sel, text) => $$(sel).find(e => (e.textContent || '').indexOf(text) >= 0) || null;

  /**
   * 取元素：选择器末尾可以带一个「@N」取第 N 个命中项（配置页有 12 张长得一模一样的卡片，
   * CSS 没法选中「第三张」，而 :nth-of-type 要先推理兄弟结构 —— 直接数序号最省事）。
   */
  function pick(sel) {
    if (typeof sel !== 'string') { return sel; }
    const m = /^(.*?)\\s*@(\\d+)\\s*(.*)$/.exec(sel);
    if (!m) { return document.querySelector(sel); }
    const host = $$(m[1])[Number(m[2])];
    if (!host) { return null; }
    return m[3] ? host.querySelector(m[3]) : host;
  }
  gs.pick = pick;

  /**
   * 等一个选择器出现。**换文字之前必须等**：页面上的东西有一半是 fetch 回来才画的，
   * 拿 spinner 那一刻去换，换了个空、真内容随后自己长出来 —— 这就是漫画标签页那张
   * 第一版把真实漫画名原样拍进图里的原因。
   */
  gs.until = async function (sel, ms) {
    const n = Math.ceil((ms || 10000) / 200);
    for (let i = 0; i < n; i++) {
      if (document.querySelector(sel)) { return true; }
      await gs.wait(200);
    }
    throw new Error('等不到 ' + sel + '（' + (ms || 10000) + 'ms）');
  };

  /**
   * 换文字。values 是字符串或字符串数组（数组按顺序循环取用）。
   * opt.attr 写属性名时改属性（title / placeholder）；opt.only 是一个正则，
   * 只在当前文字命中它时才换 —— 用来跳过表头那种本来就该留着的单元格。
   *
   * 换掉的**原文照抄进 SEEN**，截图前由 gs.checkLeaks 在图上再找一遍。脱敏最怕的不是
   * 「没换」，是「以为换了」：选择器写歪、或内容晚一步才画出来，换字的那一下一声不响地
   * 换了个空，图里就留着真名 —— 而且看图的当下根本看不出来那是真的。
   */
  const SEEN = [];
  gs.seen = SEEN;
  gs.text = function (sel, values, opt) {
    const o = opt || {};
    const pool = Array.isArray(values) ? values : [values];
    let n = 0;
    // 选择器里带 @N 时只换那一个（「只换第一段说明，后面的照旧」这种用法）
    const els = /@\\d+/.test(sel) ? [pick(sel)].filter(Boolean) : $$(sel);
    els.forEach((el) => {
      // attr=value 时读写**属性**而不是 value property —— textarea 上这两者是分开的，
      // 改属性不会改显示出来的值，所以 input / textarea 走 property
      const isVal = o.attr === 'value' && 'value' in el;
      const cur = isVal ? el.value : (o.attr ? el.getAttribute(o.attr) : el.textContent);
      if (o.only && !o.only.test(String(cur || ''))) { return; }
      const was = String(cur == null ? '' : cur);
      const v = pool[n++ % pool.length];
      if (was && was !== v && was.trim().length >= 3) { SEEN.push(was); }
      if (isVal) {
        el.value = v;
        // textarea 渲染出来的是 .value，但它的原文还挂在**子文本节点**上 —— 只改 property
        // 的话 DOM 里那份是真的，漏检自检会读出来（这一张就是这么红的）
        if (el.tagName === 'TEXTAREA') {
          Array.from(el.childNodes).forEach((nd) => {
            if (nd.nodeType === 3) { nd.nodeValue = ''; }
          });
        }
      } else if (o.attr) { el.setAttribute(o.attr, v); }
      else { el.textContent = v; }
    });
    return n;
  };

  /**
   * 只换元素里的**纯文字节点**，保留它内部的子元素。
   *
   * 填词页的原词格子是 fill-slot 里套一个 fill-split、再跟一个字 —— 断句那条竖线就住在
   * 格子里面。用 gs.text 会连竖线一起抹掉（而这一组图恰恰要演示竖线），所以那一页一律走这个。
   */
  gs.chars = function (sel, values, opt) {
    const o = opt || {};
    const pool = Array.isArray(values) ? values : [values];
    let n = 0;
    const els = /@\\d+/.test(sel) ? [pick(sel)].filter(Boolean) : $$(sel);
    els.forEach((el) => {
      Array.from(el.childNodes).forEach((node) => {
        if (node.nodeType !== 3) { return; }
        const was = String(node.nodeValue || '');
        if (!was.trim()) { return; }
        if (o.only && !o.only.test(was)) { return; }
        const v = pool[n++ % pool.length];
        if (was !== v && was.trim().length >= 3) { SEEN.push(was); }
        node.nodeValue = v;
      });
    });
    return n;
  };

  /** 文字与属性里是否还看得见换掉的原文 —— 只看**这次要截的那一块** */
  gs.checkLeaks = function () {
    if (!SEEN.length) { return 0; }
    const cl = CLIP || {x: 0, y: 0, w: window.innerWidth, h: window.innerHeight};
    const over = (el) => {
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height) { return false; }
      const x = r.left + window.scrollX, y = r.top + window.scrollY;
      return x < cl.x + cl.w && x + r.width > cl.x && y < cl.y + cl.h && y + r.height > cl.y;
    };
    // 攒的是「哪一段文字来自哪个元素」而不是一整条大字符串：报错时要能说出**是谁**漏了，
    // 否则只能拿着名字满页面猜（这张图就是这么卡住的：名字在 title 属性里，肉眼看不见）
    const who = (el, what) => {
      const cls = typeof el.className === 'string' && el.className
        ? '.' + el.className.trim().split(/\\s+/).join('.') : '';
      return el.tagName.toLowerCase() + cls + (what ? '[' + what + ']' : '');
    };
    const bits = [];
    const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let n = w.nextNode(); n; n = w.nextNode()) {
      if (!n.nodeValue || !n.nodeValue.trim()) { continue; }
      const el = n.parentElement;
      if (!el || !over(el)) { continue; }
      // textarea 的子文本节点不在画面上（画面显示的是 .value，下面那轮扫得到），读它会误报
      if (el.tagName === 'TEXTAREA') { continue; }
      bits.push({s: n.nodeValue, at: who(el, '')});
    }
    for (const el of $$('[title],[placeholder],[alt],[data-path],input,textarea')) {
      if (!over(el)) { continue; }
      for (const a of ['title', 'placeholder', 'alt', 'data-path', 'value']) {
        const v = a === 'value' ? (el.value || '') : el.getAttribute(a);
        if (v) { bits.push({s: v, at: who(el, a)}); }
      }
    }
    for (const s of SEEN) {
      const b = bits.find(x => x.s.indexOf(s) >= 0);
      if (b) {
        throw new Error('图上还能看到真实内容：「' + s + '」（在 ' + b.at + '）—— 换的时候它还没画出来，'
          + '或者选择器没包住它');
      }
    }
    return 0;
  };

  /** select 里 option 的文字也换掉 */
  gs.options = function (sel, values) {
    return gs.text(sel + ' option', values);
  };

  /** 生成一张「示例图」的 data URI：斜纹底 + 一行说明，用来顶掉真实封面 */
  gs.placeholder = function (label) {
    const txt = label || '示例图片';
    const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400">'
      + '<defs><pattern id="p" width="24" height="24" patternUnits="userSpaceOnUse" '
      + 'patternTransform="rotate(45)">'
      + '<rect width="24" height="24" fill="#1b1f27"/>'
      + '<rect width="12" height="24" fill="#222833"/></pattern></defs>'
      + '<rect width="300" height="400" fill="url(#p)"/>'
      + '<rect x="1" y="1" width="298" height="398" fill="none" stroke="#39404d" stroke-width="2"/>'
      + '<text x="150" y="200" fill="#7b8496" font-size="20" font-family="sans-serif" '
      + 'text-anchor="middle">' + txt + '</text></svg>';
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  };

  /** 把真实封面换成示例图（img 的 src 与行内 background-image 两种都管） */
  gs.img = function (sel, label) {
    const uri = gs.placeholder(label);
    let n = 0;
    $$(sel).forEach((el) => {
      if (el.tagName === 'IMG') {
        el.removeAttribute('srcset');
        el.src = uri;
      } else {
        el.style.backgroundImage = 'url("' + uri + '")';
      }
      n++;
    });
    return n;
  };

  /**
   * 抹掉全页的盘符路径。抓的是「单个字母 + 冒号 + 斜杠」，且**前一个字符不能是字母数字**
   * —— 否则 http:// 里的 p:// 也会被当成路径。text 节点、title / placeholder / alt /
   * data-path 属性、input 的值一起扫：真实路径在这些地方都会露出来，漏一处这张图就不能公开。
   */
  gs.scrub = function (label) {
    const rep = label || '[示例盘]\\\\示例目录';
    // 反斜杠不是终止符（真实路径里全是它），终止符是空白、中文标点、引号与方括号
    const re = /(?<![A-Za-z0-9])[A-Za-z]:[\\\\/][^\\s，。；、）)】」"'\\]]*/g;
    const fix = (s) => s.replace(re, rep);
    let count = 0;
    const nodes = [];
    const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let n = w.nextNode(); n; n = w.nextNode()) { nodes.push(n); }
    for (const t of nodes) {
      if (re.test(t.nodeValue)) { t.nodeValue = fix(t.nodeValue); count++; }
      re.lastIndex = 0;
    }
    for (const el of $$('[title],[placeholder],[alt],[data-path]')) {
      for (const a of ['title', 'placeholder', 'alt', 'data-path']) {
        const v = el.getAttribute(a);
        if (v && re.test(v)) { el.setAttribute(a, fix(v)); count++; }
        re.lastIndex = 0;
      }
    }
    for (const el of $$('input,textarea')) {
      if (el.value && re.test(el.value)) { el.value = fix(el.value); count++; }
      re.lastIndex = 0;
    }
    return count;
  };

  // ---------- 标注 ----------

  function layer() {
    let el = document.getElementById('gs-layer');
    if (!el) {
      el = document.createElement('div');
      el.id = 'gs-layer';
      el.setAttribute('style',
        'position:absolute;top:0;left:0;width:0;height:0;z-index:2147483000;pointer-events:none');
      document.body.appendChild(el);
    }
    return el;
  }

  /** 视口坐标 → 页面坐标。本工具只在 prep 里滚动一次，之后不再动滚动位置 */
  function pageRect(el) {
    const r = el.getBoundingClientRect();
    return {x: r.left + window.scrollX, y: r.top + window.scrollY, w: r.width, h: r.height};
  }

  const COLOR = '#5b9dff';

  /** 这次要截的那一块（页面坐标）。牌子往外摆时撞到边界就往回拉，保证画进图里 */
  let CLIP = null;
  gs.setClip = function (r) { CLIP = r; };
  gs.clip = function () { return CLIP; };

  /**
   * 一条图例：把 sel 命中的元素框起来，旁边挂一个「编号 + 一句话」的牌子，中间连一根箭头。
   * side 决定牌子挂哪一边（top / bottom / left / right）。
   *
   * 用「牌子 + 箭头」而不是「图上只放编号、说明写正文里」：md 里装不下编号与坐标的对应
   * 关系（docs/已完成/使用说明页实施计划.md §1.3），把话直接画进图里，图自己就能读懂。
   */
  gs.callout = function (sel, opt) {
    const o = opt || {};
    const el = pick(sel);
    if (!el) { return null; }
    const r = pageRect(el);
    const root = layer();
    const pad = o.pad == null ? 3 : o.pad;
    const color = o.color || COLOR;

    // 高亮框。那圈 9999px 的 box-shadow 是「把框外压暗」 —— 只在 o.dim 时画
    // （默认只框不变暗，不然整张图太沉）
    const shadow = o.dim ? ';box-shadow:0 0 0 9999px rgba(10,12,16,.34)' : '';
    const box = document.createElement('div');
    box.setAttribute('style', 'position:absolute;border:2px solid ' + color
      + ';border-radius:5px' + shadow
      + ';left:' + (r.x - pad) + 'px;top:' + (r.y - pad) + 'px'
      + ';width:' + (r.w + pad * 2) + 'px;height:' + (r.h + pad * 2) + 'px');
    root.appendChild(box);

    if (!o.label && !o.n) { return r; }

    const chip = document.createElement('div');
    chip.setAttribute('style', 'position:absolute;display:flex;align-items:center;gap:7px'
      + ';background:#0d1117;border:1px solid ' + color + ';border-radius:6px'
      + ';padding:5px 10px;font:13px/1.5 "Microsoft YaHei",sans-serif;color:#e6e8ec'
      + ';white-space:nowrap;box-shadow:0 4px 14px rgba(0,0,0,.5)');
    if (o.n) {
      const b = document.createElement('span');
      b.textContent = o.n;
      b.setAttribute('style', 'display:inline-flex;align-items:center;justify-content:center'
        + ';min-width:18px;height:18px;padding:0 4px;border-radius:9px'
        + ';background:' + color + ';color:#0d1117;font-weight:700;font-size:12px');
      chip.appendChild(b);
    }
    if (o.label) {
      const t = document.createElement('span');
      t.textContent = o.label;
      chip.appendChild(t);
    }
    root.appendChild(chip);

    // 位置：先按 side 摆在框外面，再往回拉到「这次要截的那一块」里 —— 拉出图外的牌子
    // 等于没画，所以这里的边界用的是裁剪范围，不是视口
    const side = o.side || 'bottom';
    const GAP = o.gap == null ? 12 : o.gap;
    const cw = chip.offsetWidth, ch = chip.offsetHeight;
    let cx, cy;
    if (side === 'bottom') { cx = r.x + r.w / 2 - cw / 2; cy = r.y + r.h + pad + GAP; }
    else if (side === 'top') { cx = r.x + r.w / 2 - cw / 2; cy = r.y - pad - GAP - ch; }
    else if (side === 'left') { cx = r.x - pad - GAP - cw; cy = r.y + r.h / 2 - ch / 2; }
    else { cx = r.x + r.w + pad + GAP; cy = r.y + r.h / 2 - ch / 2; }
    // dx / dy 用来手动挪开牌子 —— 被指的是一整条边（导航、表头那种）时，算出来的中心点
    // 十有八九压在别的控件上，只能人工挑个空位
    cx += o.dx || 0;
    cy += o.dy || 0;
    const cl = CLIP || {x: 0, y: 0, w: o.viewW || window.innerWidth, h: o.viewH || window.innerHeight};
    const bw = Math.max(cl.w - 8, cw), bh = Math.max(cl.h - 8, ch);
    cx = Math.max(cl.x + 4, Math.min(cx, cl.x + bw - cw));
    cy = Math.max(cl.y + 4, Math.min(cy, cl.y + bh - ch));
    chip.style.left = cx + 'px';
    chip.style.top = cy + 'px';

    // 箭头：从牌子的连线端指向框的中心，两端各留一点 —— 不盖住牌子上的字、也不戳进框里
    const vert = side === 'bottom' || side === 'top';
    const fx = cx + cw / 2, fy = cy + ch / 2;
    const tx = r.x + r.w / 2, ty = r.y + r.h / 2;
    const dx = tx - fx, dy = ty - fy;
    const len = Math.max(1, Math.hypot(dx, dy));
    const sx = fx + dx / len * (vert ? ch / 2 : cw / 2);
    const sy = fy + dy / len * (vert ? ch / 2 : cw / 2);
    const ex = tx - dx / len * 13, ey = ty - dy / len * 13;
    const ang = Math.atan2(ey - sy, ex - sx) * 180 / Math.PI;
    const line = document.createElement('div');
    line.setAttribute('style', 'position:absolute;transform-origin:0 50%'
      + ';left:' + sx + 'px;top:' + (sy - 1.5) + 'px'
      + ';width:' + Math.hypot(ex - sx, ey - sy) + 'px;height:3px'
      + ';background:' + color + ';transform:rotate(' + ang + 'deg)');
    root.appendChild(line);
    const head = document.createElement('div');
    head.setAttribute('style', 'position:absolute;width:10px;height:10px'
      + ';background:' + color + ';transform:translate(-50%,-50%) rotate(45deg)'
      + ';left:' + ex + 'px;top:' + ey + 'px');
    root.appendChild(head);
    return r;
  };

  /** 在画面上放一块说明牌（不指元素，用来写「这张图讲什么」）；坐标是**页面坐标** */
  gs.note = function (text, opt) {
    const o = opt || {};
    const cl = CLIP || {x: 0, y: 0};
    const el = document.createElement('div');
    el.setAttribute('style', 'position:absolute;background:#0d1117'
      + ';border:1px solid ' + (o.color || COLOR) + ';border-radius:6px;padding:7px 12px'
      + ';font:13px/1.6 "Microsoft YaHei",sans-serif;color:#e6e8ec'
      + ';left:' + (o.x == null ? cl.x + 12 : o.x) + 'px'
      + ';top:' + (o.y == null ? cl.y + 12 : o.y) + 'px'
      + ';max-width:' + (o.maxWidth || 460) + 'px');
    el.textContent = text;
    layer().appendChild(el);
    return el;
  };

  /** 点一下（只允许点「打开某个浮层 / 切页签」这类纯展示动作） */
  gs.click = function (sel) {
    const el = pick(sel);
    if (!el) { return false; }
    el.click();
    return true;
  };

  /** 滚到画面中间 —— 悬浮要求元素真的在视口里，CDP 的鼠标坐标是视口坐标 */
  gs.scroll = function (sel) {
    const el = pick(sel);
    if (!el) { return false; }
    el.scrollIntoView({block: 'center'});
    return true;
  };
})();`;

// ==================================================================
// 二、极简 CDP 客户端（node 24 自带 WebSocket，零依赖）
// ==================================================================

class Cdp {
    constructor(url) {
        this.url = url;
        this.seq = 0;
        this.pending = new Map();
        this.waiters = [];
    }

    open() {
        return new Promise((resolve, reject) => {
            const ws = new WebSocket(this.url);
            this.ws = ws;
            ws.addEventListener('open', resolve);
            ws.addEventListener('error', () => reject(new Error('CDP 连接失败')));
            ws.addEventListener('message', (ev) => {
                const msg = JSON.parse(ev.data);
                if (msg.id && this.pending.has(msg.id)) {
                    const {resolve: ok, reject: no} = this.pending.get(msg.id);
                    this.pending.delete(msg.id);
                    if (msg.error) {
                        no(new Error('CDP ' + msg.error.message));
                    } else {
                        ok(msg.result);
                    }
                    return;
                }
                for (const w of this.waiters.slice()) {
                    if (w.method === msg.method) {
                        this.waiters.splice(this.waiters.indexOf(w), 1);
                        w.resolve(msg.params);
                    }
                }
            });
        });
    }

    send(method, params) {
        const id = ++this.seq;
        return new Promise((resolve, reject) => {
            this.pending.set(id, {resolve, reject});
            this.ws.send(JSON.stringify({id, method, params: params || {}}));
        });
    }

    once(method, timeoutMs) {
        return new Promise((resolve, reject) => {
            const w = {method, resolve};
            this.waiters.push(w);
            setTimeout(() => {
                const i = this.waiters.indexOf(w);
                if (i >= 0) {
                    this.waiters.splice(i, 1);
                    reject(new Error('等 ' + method + ' 超时'));
                }
            }, timeoutMs || 20000);
        });
    }
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** 每次导航加一个不同的查询串（理由见 capture 里那段注释） */
let navSeq = 0;

/** 在页面里跑一段 JS，抛出异常时**把原文带出来**（否则只能看见一句「prep 里抛了」） */
async function evalJs(cdp, expression, awaitPromise) {
    const r = await cdp.send('Runtime.evaluate', {
        expression, awaitPromise: !!awaitPromise, returnByValue: true
    });
    if (r.exceptionDetails) {
        const d = r.exceptionDetails;
        throw new Error('页面里抛了：' + ((d.exception && d.exception.description) || d.text));
    }
    return r.result.value;
}

// ==================================================================
// 三、Chrome 起停
// ==================================================================

function findChrome() {
    for (const p of CHROME_CANDIDATES) {
        if (fs.existsSync(p)) {
            return p;
        }
    }
    throw new Error('找不到 Chrome，设 CHROME_PATH 环境变量指过去');
}

async function launchChrome() {
    const exe = findChrome();
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'guide-shot-'));
    const proc = spawn(exe, [
        '--headless=new',
        '--remote-debugging-port=0',
        '--user-data-dir=' + profile,
        '--no-first-run',
        '--no-default-browser-check',
        '--disable-extensions',
        '--disable-gpu',
        '--hide-scrollbars',
        '--force-device-scale-factor=1',
        '--autoplay-policy=no-user-gesture-required',
        '--window-size=1280,800',
        'about:blank'
    ], {stdio: ['ignore', 'ignore', 'pipe']});

    const browserWs = await new Promise((resolve, reject) => {
        let buf = '';
        const timer = setTimeout(() => reject(new Error('Chrome 没在 20s 内报出调试端口'
            + '（多半是 --user-data-dir 没写对，136+ 会静默忽略它）')), 20000);
        proc.stderr.on('data', (d) => {
            buf += d.toString();
            const m = buf.match(/DevTools listening on (ws:\/\/\S+)/);
            if (m) {
                clearTimeout(timer);
                resolve(m[1]);
            }
        });
        proc.on('error', reject);
    });

    const port = new URL(browserWs).port;
    // 取页面级 target 的 ws —— 用 /json/list（GET），别用 /json/new（较新 Chrome 只收 PUT）
    let pageWs = null;
    for (let i = 0; i < 40 && !pageWs; i++) {
        const list = await fetch('http://127.0.0.1:' + port + '/json/list').then(r => r.json());
        const page = list.find(t => t.type === 'page');
        if (page) {
            pageWs = page.webSocketDebuggerUrl;
        } else {
            await sleep(150);
        }
    }
    if (!pageWs) {
        throw new Error('拿不到页面 target 的 ws 地址');
    }
    return {proc, profile, pageWs};
}

// ==================================================================
// 四、一条镜头的执行
// ==================================================================

/**
 * 选择器里可以带一个 `@N` 取第 N 个命中项，例如 `'.card @1'`。
 * 页面上一排长得一模一样的卡片（配置页有 12 张）没法用 CSS 选中件「第三张」，
 * 而 `.card:nth-of-type(3)` 又要先推理它的兄弟结构 —— 直接数命中序号最省事。
 */
function splitAt(sel) {
    const m = /^(.*?)\s*@(\d+)\s*(.*)$/.exec(sel);
    if (!m) {
        return {sel, i: 0, sub: ''};
    }
    return {sel: m[1], i: Number(m[2]), sub: m[3]};
}

/** 「找到那个元素」那段表达式，rectOf 与 viewportRectOf 共用一份（两份就会漂） */
function elemExpr(sel) {
    const {sel: s, i, sub} = splitAt(sel);
    const base = 'document.querySelectorAll(' + JSON.stringify(s) + ')[' + i + ']';
    return sub ? '(' + base + ' && ' + base + '.querySelector(' + JSON.stringify(sub) + '))' : base;
}

/** 元素在**页面坐标**里的框（裁剪用它，所以加回滚动偏移） */
async function rectOf(cdp, sel) {
    return evalJs(cdp, '(() => { const e = ' + elemExpr(sel)
        + '; if (!e) return null; const b = e.getBoundingClientRect();'
        + 'return {x: b.left + window.scrollX, y: b.top + window.scrollY, w: b.width, h: b.height}; })()');
}

/** 元素在**视口坐标**里的框（鼠标事件用它 —— CDP 的鼠标坐标不带滚动偏移） */
async function viewportRectOf(cdp, sel) {
    return evalJs(cdp, '(() => { const e = ' + elemExpr(sel)
        + '; if (!e) return null; const b = e.getBoundingClientRect();'
        + 'return {x: b.left, y: b.top, w: b.width, h: b.height}; })()');
}

async function waitFor(cdp, sel, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (await evalJs(cdp, '!!document.querySelector(' + JSON.stringify(sel) + ')')) {
            return;
        }
        await sleep(200);
    }
    throw new Error('等不到 ' + sel + ' 出现（' + timeoutMs + 'ms）');
}

/**
 * 悬浮到一个元素上，**并且自检真浮上去了**。
 * 失败模式全是静默的：图拍出来了、看着就是缺了那条竖线 / 那个 `+`，截的人不当回事就过去了 ——
 * 所以这里必须断言，宁可直接报错。滚动由 prep 里的 `gs.scroll` 负责：本函数**不改滚动位置**
 * （改了就会把刚量好的标注坐标作废），只按元素此刻在视口里的位置派发鼠标。
 */
async function hover(cdp, sel, expectSel) {
    const r = await viewportRectOf(cdp, sel);
    if (!r) {
        throw new Error('hover 选择器没命中：' + sel);
    }
    await cdp.send('Input.dispatchMouseEvent', {
        type: 'mouseMoved', x: Math.round(r.x + r.w / 2), y: Math.round(r.y + r.h / 2), buttons: 0
    });
    await sleep(280);
    const check = expectSel || sel;
    const s = await evalJs(cdp, '(() => { const e = document.querySelector(' + JSON.stringify(check) + ');'
        + 'if (!e) return null; const cs = getComputedStyle(e);'
        + 'return {opacity: cs.opacity, visibility: cs.visibility, display: cs.display}; })()');
    if (!s || s.display === 'none' || s.visibility === 'hidden' || Number(s.opacity) === 0) {
        throw new Error('悬浮没生效（' + check + ' 仍是 ' + JSON.stringify(s) + '）——'
            + '拍出一张缺了提示的图比报错更坏，所以这儿直接停下');
    }
}

/** 打开页面 → 换内容 → 悬浮 → 量裁剪范围 → 画标注 → 截图，返回 {data, w, h} */
async function capture(cdp, shot) {
    const size = shot.size || {w: 1280, h: 1200};
    const scale = shot.scale || 1;
    const page = typeof shot.page === 'function' ? shot.page() : shot.page;
    if (page === false) {
        throw new Error('这一页要的数据不在库里（见 main 里那几处探测）');
    }

    await cdp.send('Emulation.setDeviceMetricsOverride',
        {width: size.w, height: size.h, deviceScaleFactor: 1, mobile: false});
    // 那个 `?_=` 不是缓存击穿，是**逼出一次真导航**：只变 hash 是同一文档内的跳转，
    // `Page.loadEventFired` 永远不会来（第一张之后的每张都会在这儿空等到超时）。
    // 后端是静态资源 + hash 路由，查询串不参与任何判断，加它没有任何副作用
    await cdp.send('Page.navigate', {url: BASE + '/?_=' + (++navSeq) + (page ? '#' + page : '')});
    await cdp.once('Page.loadEventFired', 30000);

    // 数据是 fetch 回来的，load 事件时页面往往还是空的 —— 等一个「已经画出来」的信号
    await waitFor(cdp, shot.ready || '#page-body', 30000);
    await sleep(shot.settle == null ? 700 : shot.settle);

    // 被遮罩盖住的页面截出来是「还差配置」，不是那个页面 —— 早报错，别拍一张废图
    if (await evalJs(cdp, '!!document.querySelector(".page-gate")')) {
        throw new Error('这一页此刻被遮罩盖着（缺必需路径根），截出来不是页面本身');
    }

    // 页面内工具每题都重注一次：换页会整块重画，早先注入的引用可能已经脱离
    await evalJs(cdp, PAGE_HELPERS);

    // prep / mark 里写的是「一段函数体」（可以 return、可以 await），所以在这儿包一层
    // 只收「函数体」。自己再包一层函数的话，外层只会执行到「创建一个 Promise」那一句就返回，
    // 后面那句 await 全落空 —— 页面还在加载、量到的裁剪范围和画出来的东西对不上（踩过）
    const run = (code) => {
        if (/^\s*\(\s*(async\b|function\b)/.test(code)) {
            throw new Error('prep / mark 只能写函数体（可以 await、可以 return），'
                + '别再自己包 (async () => {…})() —— 外层不会 await 它');
        }
        return evalJs(cdp, '(async () => { ' + code + ' })()', true);
    };

    if (shot.prep) {
        await run(shot.prep);
    }
    // `:hover` 只能在 CDP 层触发，页面里 JS 改不了
    if (shot.hover) {
        await hover(cdp, shot.hover, shot.expect);
    }

    // 裁剪范围要在这儿量：它取决于 prep 换过内容之后的真实布局。
    // 标注挂在一个零尺寸的绝对定位层里、不参与布局，所以量完再画不会把它挪走
    const p = shot.pad == null ? 12 : shot.pad;
    let clip;
    if (shot.crop) {
        const r = await rectOf(cdp, shot.crop);
        if (!r) {
            throw new Error('crop 选择器没命中：' + shot.crop);
        }
        clip = {
            x: Math.round(Math.max(0, r.x - p)), y: Math.round(Math.max(0, r.y - p)),
            w: Math.round(r.w + p * 2),
            h: Math.round(Math.min(r.h + p * 2, shot.maxH || 1e5))
        };
    } else {
        clip = {x: 0, y: 0, w: size.w, h: size.h};
    }
    if (process.env.GS_DEBUG) {
        const dbg = await evalJs(cdp, '(() => { const e = ' + elemExpr(shot.crop || '#page-body')
            + '; const b = e && e.getBoundingClientRect();'
            + 'return {rect: b && {x: b.left, y: b.top, w: b.width, h: b.height},'
            + ' html: e && e.outerHTML.slice(0,400), tag: e && e.tagName + "." + e.className,'
            + ' vh: window.innerHeight}; })()');
        console.log('  [debug] clip=' + JSON.stringify(clip) + ' ' + JSON.stringify(dbg));
    }
    // 标注靠这个范围把自己拉回图内（牌子跑到裁剪范围外就等于没画）
    await evalJs(cdp, 'gs.setClip(' + JSON.stringify(clip) + ')');

    if (shot.mark) {
        await run(shot.mark);
    }
    await sleep(shot.after == null ? 250 : shot.after);

    // 这一张图里还看得见真实内容吗？看图看不出来（真名混在假名里毫无异样），只能在这儿拦住
    await run('gs.checkLeaks(); return true;');

    // captureBeyondViewport 只在**真的超出视口**时才开：它会按裁剪高度重排一遍页面，
    // 而 `position:fixed` 的浮层（弹窗、播放条）是相对视口摆的 —— 重排之后它会挪到另一个
    // 位置，于是截出来的是「按老坐标量、按新布局画」的错位块（漫画标签页那张就踩过：
    // 量到的是弹窗，截出来的是表格中间一段）。
    const beyond = clip.y + clip.h > size.h;
    const png = await cdp.send('Page.captureScreenshot', {
        format: 'png',
        clip: {x: clip.x, y: clip.y, width: clip.w, height: clip.h, scale},
        captureBeyondViewport: beyond
    });
    return {data: png.data, w: Math.round(clip.w * scale), h: Math.round(clip.h * scale)};
}

/** 图例带：`[{n, text}]` 竖排到底图下方。牌子上的编号就靠这一行解释 */
function legendHtml(legend) {
    if (!legend || !legend.length) { return ''; }
    const badge = 'display:inline-flex;align-items:center;justify-content:center'
        + ';min-width:18px;height:18px;padding:0 4px;border-radius:9px;margin-right:9px'
        + ';background:' + CLIP_COLOR + ';color:#0d1117;font-weight:700;font-size:12px';
    return '<div style="padding:6px 0 0;color:#c9d1d9;font-size:13.5px;line-height:2.1">'
        + legend.map(e => '<div><span style="' + badge + '">' + e.n + '</span>' + e.text + '</div>')
            .join('')
        + '</div>';
}

/** 几张图竖着拼成一张：每张上面标一句（浮层会盖住内容、一张图装不下两件事时用） */
async function compose(cdp, segs, outName, title, legend) {
    const W = 1180, PAD = 44, SIDE = PAD / 2;
    const html = '<!doctype html><meta charset="utf-8"><body style="margin:0;background:#0f1116;'
        + 'font:14px/1.7 \'Microsoft YaHei\',sans-serif;color:#e6e8ec">'
        + '<div style="padding:20px 0 0 ' + SIDE + 'px;color:#7b8496;font-size:13px">'
        + (title || '') + '</div>'
        + segs.map(s => '<div style="padding:16px ' + SIDE + 'px 0">'
            + (s.label ? '<div style="color:#9aa3b2;font-size:13px;margin-bottom:8px">'
                + s.label + '</div>' : '')
            + '<img src="data:image/png;base64,' + s.data + '" style="display:block;'
            + 'max-width:' + (W - PAD) + 'px;border:1px solid #262c36;border-radius:6px">'
            + '</div>').join('')
        + '<div style="padding:8px ' + SIDE + 'px 0">' + legendHtml(legend) + '</div>'
        + '<div style="height:26px"></div></body>';

    await cdp.send('Page.navigate', {url: 'about:blank'});
    await cdp.once('Page.loadEventFired', 20000);
    // 先把宽度定下来再写文档：不然量到的高度是按上一张镜头的宽度折行折出来的
    await cdp.send('Emulation.setDeviceMetricsOverride',
        {width: W, height: 800, deviceScaleFactor: 1, mobile: false});
    await evalJs(cdp, 'document.open();document.write(' + JSON.stringify(html) + ');document.close();');
    await sleep(500);
    // 量 body 的框，不用 scrollHeight —— 后者最小就是视口高度，内容比视口矮时量出来
    // 是「视口那么高」，图底下白多出一大块（短图都成了 1200 高，就是这么来的）
    const h = await evalJs(cdp, 'Math.ceil(document.body.getBoundingClientRect().height)');
    const H = Math.min(h || 800, 6000);
    await cdp.send('Emulation.setDeviceMetricsOverride',
        {width: W, height: H, deviceScaleFactor: 1, mobile: false});
    await sleep(400);
    const png = await cdp.send('Page.captureScreenshot', {format: 'png'});
    fs.writeFileSync(path.join(OUT_DIR, outName + '.png'), Buffer.from(png.data, 'base64'));
    return W + '×' + H;
}

async function shoot(cdp, shot) {
    if (shot.parts) {
        const segs = [];
        for (const part of shot.parts) {
            let c;
            try {
                c = await capture(cdp, part);
            } catch (e) {
                // 分段图出问题要说得清是**哪一段** —— 否则只看到一个笼统的 shot 名
                throw new Error('「' + part.label + '」那一段：' + e.message);
            }
            segs.push({label: part.label, data: c.data});
        }
        return compose(cdp, segs, shot.name, shot.what);
    }
    const c = await capture(cdp, shot);
    // 有 legend 的走拼图那条路：牌子缩成一个编号，话挪到图下方 —— 表格一格挨一格，
    // 长牌子摆进去必然压住旁边的格子（压住的往往正是它要解释的那一格）
    if (shot.legend) {
        return compose(cdp, [{label: '', data: c.data}], shot.name, shot.what, shot.legend);
    }
    fs.writeFileSync(path.join(OUT_DIR, shot.name + '.png'), Buffer.from(c.data, 'base64'));
    return c.w + '×' + c.h;
}

// ==================================================================
// 五、镜头清单
// ==================================================================

/** 填词页那一组要一份真实存在的填词：启动时从接口取，别把 id 写死在文件里 */
let FILL = null;
const fillPage = () => FILL && ('song-fill?originalId=' + FILL.originalId + '&fillId=' + FILL.id);

/**
 * 一条镜头：`{name, page, what, ...}`
 *
 * | 键 | 用途 |
 * | --- | --- |
 * | `name` | 产物名，md 里 `../img/guide/<name>.png` 引的就是它（相对 md 文件） |
 * | `page` | 页面 id（= hash 路由），根页写空串；函数则现算 |
 * | `what` | 这张图讲什么（打印 + 拼图的标题） |
 * | `ready` | 等到这个选择器出现再动手（数据是 fetch 回来的，load 事件太早） |
 * | `prep` | 渲染完之后在页面里跑的 JS：换示例文字 / 换占位图 / 滚动 / 打开浮层 |
 * | `hover` / `expect` | 悬浮到这个选择器上，并断言 `expect` 真的显出来了 |
 * | `mark` | 悬浮之后、截图之前画的标注（裁剪范围已量好，`gs.clip()` 拿得到） |
 * | `crop` / `maxH` / `pad` / `scale` | 只截这个选择器、最高多少像素、留白、放大倍数 |
 * | `parts` | 拼图：逐个 `capture` 再竖着拼（浮层盖住内容时用） |
 * | `legend` | `[{n, text}]`：牌子只留编号，说明挪到图**下方**一行一条（见下） |
 * | `manual` | 有值 = 截不了，值是原因（跑的时候列出来交人工） |
 *
 * `legend` 和牌子上直接写话，二选一，判据是**这块地方有没有空**：
 * 表格 / 编辑窗那种一格挨一格的，长牌子摆进去只能压在旁边的格子上（压住的常常正是
 * 它要解释的那一格 —— 原曲页那张「七个标记」就压在标记上），这时把话挪到图下方，
 * 牌子上只留一个编号；页面本来就松（导航、卡片墙、填词页）的照旧写在牌子上，离得近更好读。
 */
/**
 * 歌曲列表「那一行」的匿名化，两处共用：列表那一张，和播放条那一张。
 *
 * 播放条是 `position:fixed` 盖在整个内容区上的 —— 它在几何上压着下面那张列表，
 * 所以漏检自检也会去底下那几行里找真名（这一张就是这么红的：播放条本身换得很干净，
 * 底下第一行的「歌词」小标签 title 里还挂着真文件名）。换掉它们两处都省心。
 */
const SONG_ROW_SCRUB = `
    gs.text('#page-body tbody td:first-child a', gs.FAKE.artist);
    gs.text('#page-body tbody td:nth-child(2)', gs.FAKE.song);
    gs.text('#page-body tbody td:nth-child(3)', gs.FAKE.song);
    gs.text('#page-body tbody .tag', '示例标签', {attr: 'title'});
`;

/**
 * 填词页的匿名化：上排原词那排字，与句头里抄的那一句。
 *
 * 这一页上排照抄的是**模板 lrc 里的真歌词**（下排才是填进去的），歌名 / 歌手各自由
 * 自己的 prep 换掉 —— 歌词本身也是真实内容，一并换掉。两个注意点：
 * ① 只换**文字节点**：格子里面还住着断句那条竖线，`gs.text` 会把竖线一起抹掉；
 * ② 整页一起换、不只换看得见的那几格，否则滚下去又冒出真词；
 * ③ 下面那排填写格是空的，**灰字是 placeholder**（默认词 = 原词），也得换 ——
 *    只改上排的话，下排照样把整句真歌词灰着写出来（第一版就是这么漏的）。
 */
const FILL_LYRIC_SCRUB = `
    gs.text('.fill-line .fill-line-lyric', gs.FAKE.line);
    gs.chars('.fill-slot:not(.dash)', gs.FAKE.word);
    gs.text('.fill-cell', gs.FAKE.word, {attr: 'placeholder'});
`;

const SHOTS = [
    // 原先这里有一个 `start-window` 镜头（整窗一眼）。**2026-09-30 用户要求删掉、不补别的图**：
    // 那种整窗截图一定会带上左边的模块清单，而模块是可以被裁掉的 —— 裁掉之后图上还印着它，
    // 就成了遗留信息（说明页的正文里也不许点名模块，见 tools/guide-check.js 第 5 组）。
    // 所以这个镜头连同 static/img/guide/start-window.png 一起去掉，别再捡回来。
    {
        // 配置页整页有 5300px 高，一张图装不下 —— 拆成四段拼起来，正好对上说明页那句
        // 「顶部的提示条、待重启标记、恢复出厂、模块开关和底下的密钥卡」。
        // 卡片全靠 `@N` 序号挑：这一页有 12 张长得一模一样的 `.card`
        name: 'settings-page',
        what: '系统配置页：顶部提示条、模块开关、一组设置项（含待重启标记与恢复出厂）、密钥卡',
        parts: [
            {
                label: '① 顶部：说清这台机器上哪些功能因为没配而用不了，右边是保存',
                page: 'settings',
                ready: '.card',
                crop: '#page-body',
                maxH: 312,   // 正好停在「配置文件」卡的下沿，别带出下半截标题
                prep: `gs.scrub(); return true;`,
                mark: `
                    const hint = gs.el('#page-body .hint');
                    if (hint) { gs.callout(hint, {n: 1, label: '缺什么、缺了会怎样，都写在这儿', side: 'bottom'}); }
                    gs.callout('#set-save', {n: 2, label: '改完点保存；这一步只写文件，不生效', side: 'left'});
                    return true;`
            },
            {
                label: '② 模块总开关：关掉一个模块，它的页面入口就从左边导航里消失',
                page: 'settings',
                ready: '.set-mod-head',
                crop: '.set-mod-head @0',
                pad: 26,
                prep: `gs.scrub(); return true;`,
                mark: `
                    const head = gs.el('.set-mod-head');
                    if (head) { gs.callout(head.querySelector('.set-toggle') || head, {n: 1, label: '这就是那个开关', side: 'bottom'}); }
                    return true;`
            },
            {
                label: '③ 一组设置项：左边写这一项是什么、存在哪个键，右边是输入框',
                page: 'settings',
                ready: '.card',
                crop: '.card @1',
                maxH: 790,
                prep: `gs.scrub(); return true;`,
                mark: `
                    const row = gs.el('[data-row]');
                    if (row) { gs.callout(row.children[0], {n: 1, label: '名字下面是存在配置文件里的键', side: 'bottom'}); }
                    const badge = gs.el('.set-badge');
                    if (badge) { gs.callout(badge, {n: 2, label: '改过的项会挂一枚标记，提醒还没生效', side: 'left'}); }
                    const restore = gs.el('[data-restore]');
                    if (restore) { gs.callout(restore, {n: 3, label: '恢复出厂：只做标记，保存时才真删', side: 'top'}); }
                    return true;`
            },
            {
                label: '④ 密钥卡：只读 —— 只说配没配、要去哪个文件的哪一行改',
                page: 'settings',
                ready: '.card',
                crop: '.card @11',
                // 留白收到 6：默认那 12 会把上一段（「③ 一组设置项」正文）的最后一行带进来，
                // 拼图上方又正好写着同一句，看起来像那段话被重了一遍
                pad: 6,
                prep: `gs.scrub(); return true;`,
                mark: `
                    gs.callout('.card @11 table', {n: 1, label: '这几项不在这页改：改那个文件、重启后端', side: 'top'});
                    return true;`
            }
        ]
    },

    // ---------------- 漫画 ----------------
    {
        name: 'manga-new-grid',
        page: 'manga-new',
        what: '新漫画页：工具条（计数标签 + 按钮）加卡片墙',
        ready: '.manga-card',
        crop: '#page-body',
        maxH: 960,
        prep: `
            gs.scrub();
            gs.img('.manga-cover', '示例封面');
            gs.text('.manga-name', gs.FAKE.manga);
            gs.text('.manga-name', gs.FAKE.manga, {attr: 'title'});   // 名字在字与 title 两处
            gs.text('.manga-card', gs.FAKE.path, {attr: 'data-path'});
            gs.text('.manga-card .small .tag', gs.FAKE.tag);
            return true;`,
        mark: `
            gs.callout('[data-filter]', {n: 1, label: '这些计数标签点得动：点一下只留这一类', side: 'bottom', dy: 44});
            const card = gs.pick('.manga-card @0');
            gs.callout(card, {});
            const chk = card && card.querySelector('.manga-check');
            if (chk) { gs.callout(chk, {n: 2, label: '一格一本；勾选框给批量操作用', side: 'right'}); }
            return true;`
    },
    {
        name: 'manga-archive-row',
        page: 'manga-archive',
        what: '归档作者页：同步卡、筛选卡、单位表格',
        ready: '#page-body tbody tr',
        crop: '#page-body',
        maxH: 1200,
        prep: `
            gs.scrub();
            gs.text('#page-body tbody td:first-child', gs.FAKE.artist);
            gs.text('#page-body tbody td .mono', gs.FAKE.manga);
            gs.text('#page-body tbody td .tag', gs.FAKE.tag);
            gs.options('#f-tag', gs.FAKE.tag);
            return true;`,
        mark: `
            const cards = document.querySelectorAll('#page-body .card');
            if (cards[0]) { gs.callout(cards[0], {}); }
            if (cards[1]) { gs.callout(cards[1], {}); }
            const sync = gs.el('#sync-btn');
            if (sync) { gs.callout(sync, {n: 1, label: '从磁盘同步：把记录更新成磁盘现在的样子', side: 'bottom'}); }
            const filter = gs.el('#f-tag') || (cards[1] && cards[1].querySelector('select,button'));
            if (filter) { gs.callout(filter, {n: 2, label: '筛选卡：一改就重新列一遍', side: 'bottom', dy: 46}); }
            const row = gs.el('#page-body tbody tr');
            if (row) {
                gs.callout(row, {});
                const n = row.querySelector('.act-mangas');
                if (n) { gs.callout(n, {n: 3, label: '「本数 N ▸」点开看里面', side: 'right'}); }
            }
            return true;`
    },
    {
        name: 'manga-conflict-pair',
        manual: '库里此刻没有重名冲突，左右并排那一屏进不去；造一个假冲突要往归档根里写目录，不做',
        what: '合并冲突页：左右两栏'
    },
    {
        name: 'manga-tag-plan',
        page: 'manga-tag',
        what: '标签页：删除前的预演清单（每个目录名从什么改成什么）',
        ready: '#page-body tbody tr',
        crop: '.modal',
        maxH: 820,
        // 内边距收到 2：默认那 12 会连弹窗背后「标签表」那一列描述也带进图里
        pad: 2,
        // prep / mark 都是一段**函数体**（capture 会替它包一层 async IIFE），
        // 所以这里绝不能自己再包一层 (async () => {…})()：那样外层只会「创建一个 Promise」就返回，
        // 不 await 它 —— 量裁剪范围时页面还停在 spinner 上（这张图踩过：量到 153px，图只截了一条表头）
        prep: `
            // 挑一个「被 N 个目录用着」的标签 —— 没人用的标签，预演清单是空的
            const tr = Array.from(document.querySelectorAll('#page-body tbody tr'))
                .find(t => t.querySelector('.act-units'));
            if (!tr) { throw new Error('库里没有被归档目录用着的标签，出不了非空清单'); }
            tr.querySelector('.act-delete').click();   // 只开预演浮层；真正执行要再点一次，这里不点
            // 表格是接口回来才画的 —— 必须等它，不然换的是 spinner，真名随后自己长出来
            await gs.until('.modal tbody td.mono');
            // 清单里的目录名 = 真实漫画名，整列换掉；换不到就报错，别拍出一张带真名的图
            if (!gs.text('.modal tbody td.mono', gs.FAKE.manga)) { throw new Error('预演表格是空的'); }
            gs.text('.modal .modal-head span', '删除标签「示例标签一」');
            // 头一段带着真标签名（删除标签「3D」），必须换 —— 真页面这句话与标题栏本来就重复，
            // 照原样重复才像真的；后面那段是「会改 N 个目录」的说明、不指名道姓，留着不动
            gs.text('.modal-body > p.small @0', '删除标签「示例标签一」');
            return true;`,
        mark: `
            gs.callout('.modal table', {n: 1, label: '每个目录名从什么改成什么', side: 'top'});
            // 牌子往右偏一点：它正上方那行「另有 N 条漫画标签关联受影响…」是有用的信息，别盖住
            gs.callout('.modal-foot', {n: 2, label: '有目录改不动就整批不做', side: 'top', dx: 210});
            return true;`
    },

    // ---------------- 歌曲 ----------------
    {
        name: 'song-score-list',
        page: 'song-score',
        what: '未归档页：一行一首，行尾是播放 / 归档 / 删除',
        ready: '#page-body tbody tr',
        crop: '#page-body',
        maxH: 620,
        prep: `
            gs.scrub();
            gs.text('#page-body tbody td:first-child', gs.FAKE.song);
            gs.text('#page-body tbody .tag', '示例标签', {attr: 'title'});
            return true;`,
        legend: [
            {n: 1, text: '一行一首，多个版本并成一行'},
            {n: 2, text: '行尾三个按钮：播放 / 归档（打分）/ 删除（删盘上的文件）'}
        ],
        mark: `
            const row = gs.el('#page-body tbody tr');
            if (row) {
                gs.callout(row, {n: 1, side: 'bottom'});
                gs.callout(row.lastElementChild, {n: 2, side: 'bottom'});
            }
            return true;`
    },
    {
        name: 'song-add-files',
        page: 'song-score',
        what: '未归档页「添加文件」的浮层：三个框 + 文件表 + 评分 + 两个按钮',
        ready: '#page-body tbody tr',
        // 裁**整层遮罩**：浮层居中（900px 宽），两侧各空出约 190px，编号牌正好挂进那条
        // 空白；裁浮层本身的话牌子会被拉回浮层里面、只能压住按钮
        crop: '.modal-overlay',
        pad: 0,
        maxH: 1200,
        prep: `
            gs.scrub();
            // 浮层底下压着真实的未归档列表（遮罩只有 55% 黑，照样看得清）——
            // 同 song-score-list 那份匿名化，一个字都不能漏。
            // **别借 SONG_ROW_SCRUB**：那一份是给「歌曲」页写的，按第 2 / 3 列取单元格，
            // 拿过来用会把这一页的徽标列与倍速列写成曲名，真名反倒一个字没换
            gs.text('#page-body tbody td:first-child', gs.FAKE.song);
            gs.text('#page-body tbody .tag', '示例标签', {attr: 'title'});
            const parts = ((await (await fetch('/api/song/partitions')).json()) || {}).data || [];
            const roots = ((await (await fetch('/api/song/roots')).json()) || {}).data || {};
            // 「＋ 添加文件」真点下去会弹系统的文件选择框（CDP 驱动不了），而表单里的
            // state 是闭包里的、注入 JS 够不着 —— 所以照 openImportDialog 那一份参数
            // 直接把表单开出来，只把文件列表换成示例条目。
            // **纯客户端渲染**：不发任何写请求、不碰磁盘。
            SongForm.open({
                files: [
                    {role: 'VIDEO', fileName: '示例_01.mp4', mainName: '示例_01', version: '',
                     path: '示例目录/示例_01.mp4', fromPartition: null},
                    {role: 'AUDIO', fileName: '示例_01.mp3', mainName: '示例_01', version: '2',
                     path: '示例目录/示例_01.mp3', fromPartition: null},
                    {role: 'LYRIC', fileName: '示例_01.lrc', mainName: '示例_01', version: '',
                     path: '示例目录/示例_01.lrc', fromPartition: null}
                ],
                canAdd: true,
                removable: true,
                initial: {artists: '[示例] 绫濑', title: '[示例] 夜航星', originalTitle: '[示例] 折光'},
                partitions: parts,
                score: null,
                tags: [],
                requireTags: !!roots.requireTagsBeforeArchive,
                showTags: true,
                title: '添加文件',
                hint: '点「＋ 添加文件」选择文件；作者 / 曲名 / 原曲名填好后按两个按钮之一提交。',
                buttons: [
                    // run / submit 永远不该被调到（本工具一个按钮都不点）——写成抛错，
                    // 免得将来谁顺手点了却在无声地跑一次没人料到的提交
                    {id: 'migrate', label: '迁移到未归档', need: 'import-migrate',
                     run: async () => { throw new Error('截图工具不该点这个按钮'); },
                     submit: async () => { throw new Error('截图工具不该点这个按钮'); }},
                    {id: 'archive', label: '归档', need: 'import-archive',
                     run: async () => { throw new Error('截图工具不该点这个按钮'); },
                     submit: async () => { throw new Error('截图工具不该点这个按钮'); }}
                ]
            });
            await gs.wait(300);
            if (!document.querySelector('.modal-overlay .modal')) { throw new Error('添加文件的浮层没出来'); }
            // 选一个分：两张按钮都点亮的样子才是这条路的常态（「归档」不选分是灰的）
            const sc = document.querySelector('#sf-scores button');
            if (sc) { sc.click(); }
            await gs.wait(200);
            gs.scrub();
            return true;`,
        legend: [
            {n: 1, text: '上面三个框：作者 / 曲名 / 原曲名 —— 这一条路上三个都要填满，名字按它们重排'},
            {n: 2, text: '「＋ 添加文件」：多选文件，或者粘一串路径'},
            {n: 3, text: '一行一个文件：中间那格填编号（重名靠它分开），右边那格是按名字实时算出的新文件名'},
            {n: 4, text: '评分点一下选中、再点一下取消；「归档」必须先选一个分。标签在它下面'},
            {n: 5, text: '底下两个按钮：迁移到未归档（先放着待打分）/ 归档（直接定档），点下去先出确认页'}
        ],
        mark: `
            const grid = gs.pick('#sf-artists');
            if (grid) { gs.callout(grid.parentElement, {n: 1, side: 'left'}); }
            gs.callout('#sf-add-files', {n: 2, side: 'right'});
            gs.callout('#sf-files', {n: 3, side: 'right'});
            gs.callout('#sf-scores', {n: 4, side: 'right'});
            gs.callout('#sf-foot', {n: 5, side: 'bottom'});
            return true;`
    },
    {
        name: 'song-list-row',
        what: '歌曲页：列表里的一行，和点「播放」展开的播放条',
        parts: [
            {
                label: '① 列表里的一行（×N 表示这首有好几个版本，并成一行）',
                page: 'song-list',
                ready: '#page-body tbody tr',
                crop: '#page-body table',
                // 表头 + 一整行；不留 pad —— 上面那条筛选条被切掉一半挂在图顶上很难看
                pad: 0,
                maxH: 190,
                prep: `
                    gs.scrub();
                    ${SONG_ROW_SCRUB}
                    return true;`,
                mark: `
                    const row = gs.el('#page-body tbody tr');
                    if (row) {
                        gs.callout(row, {n: 1, label: '一行不是一首：×N 版本合一行', side: 'bottom'});
                        gs.callout(row.lastElementChild, {n: 2, label: '播放 / 修改 / 删除', side: 'bottom'});
                    }
                    return true;`
            },
            {
                label: '② 点「播放」展开的播放条（它盖住整个内容区，所以只能单独截）',
                page: 'song-list',
                ready: '#page-body tbody tr',
                crop: '#song-player',
                // 播放条是 100vh 的浮层：窗口多高它多高。默认 1200 的视口拍出来是一大块
                // 黑画面（没真视频可放），把视口压到 760 就跟平时开的窗口一个比例了
                size: {w: 1280, h: 760},
                maxH: 820,
                prep: `
                    const btn = document.querySelector('#page-body tbody .act-play');
                    if (!btn) { throw new Error('列表里没有可播放的行'); }
                    btn.click();
                    await gs.wait(2000);
                    if (!document.querySelector('#song-player')) { throw new Error('播放浮层没出来'); }
                    gs.scrub();
                    ${SONG_ROW_SCRUB}
                    gs.text('.sp-title', '[示例] 夜航星');
                    gs.options('#sp-variant', gs.FAKE.song);
                    gs.text('#sp-lyric-head', '歌词：示例.lrc');
                    gs.text('#sp-bigwords', '（示例歌词）');
                    gs.text('#sp-lyric-body', gs.FAKE.line);
                    return true;`,
                // 不标「版本切换」：同一首歌只有一个版本时那个下拉根本不存在
                // （标签写在图上、控件却不见，比不标更让人困惑）
                mark: `
                    gs.callout('#sp-lyric', {n: 1, label: '右边是歌词：点一句就跳到那里',
                        side: 'left', dy: -140});
                    gs.callout('.sp-bar', {n: 2, label: '播放 / 进度 / 倍速 / 存为默认 / 上一首下一首', side: 'top'});
                    return true;`
            }
        ]
    },
    {
        name: 'song-artist-row',
        page: 'song-artist',
        what: '作者页的一行',
        ready: '#page-body tbody tr',
        crop: '#page-body',
        maxH: 560,
        prep: `
            gs.text('#page-body tbody td:first-child', gs.FAKE.artist);
            return true;`,
        legend: [
            {n: 1, text: '表头都能点：点一下排序，再点换方向'},
            {n: 2, text: '一行一个作者'},
            {n: 3, text: '两个数字都能点，跳过去并按他筛好'}
        ],
        mark: `
            // 表头那条牌子往上摆：摆下面的话连线要横穿表头，正好压在列名上
            gs.callout('#page-body thead', {n: 1, side: 'top'});
            const row = gs.el('#page-body tbody tr');
            if (row) {
                gs.callout(row.children[0], {n: 2, side: 'bottom'});
                gs.callout(row.children[1], {n: 3, side: 'bottom'});
            }
            return true;`
    },
    {
        name: 'song-stat-row',
        page: 'song-stat',
        what: '原曲页的表头和一行（这一页按作者 2026-09-30 的交代保留真实名称）',
        ready: '#page-body tbody tr',
        crop: '#page-body table',
        maxH: 400,
        prep: `return true;`,
        legend: [
            {n: 1, text: '表头都能点：点一下排序，再点换方向'},
            {n: 2, text: '这首歌的默认倍速：存 / 清掉'},
            {n: 3, text: '七个标记：原 词 伴 声 样 mid svp —— 亮的有、灰的没有'}
        ],
        mark: `
            // 往右挪到表头那一列空着的地方：不挪的话它正好压在「已归档数 / 待打分」上
            gs.callout('#page-body thead', {n: 1, side: 'top', dx: 360});
            const row = gs.el('#page-body tbody tr');
            if (row) {
                gs.callout(row.querySelector('.rate-cell'), {n: 2, side: 'bottom'});
                gs.callout(row.querySelector('.tpl-flags'), {n: 3, side: 'bottom'});
            }
            return true;`
    },
    {
        name: 'song-stat-form',
        page: 'song-stat',
        what: '新增原曲 / 编辑的浮层（同上，保留真实名称）',
        ready: '#page-body tbody tr',
        // 裁**整层遮罩**而不是 `.modal` 本身：浮层居中，两侧各空出约 180px，
        // 编号牌正好挂进那条空白（挂浮层里面就只能压住按钮了）。
        // 视口仍用默认的 1200 高：浮层比视口矮一点才不出现内部滚动条 ——
        // 压到 1000 时底下两格（mid / 工程）就被滚出可视区、拍不进去
        crop: '.modal-overlay',
        pad: 0,
        maxH: 1200,
        prep: `
            const btn = document.querySelector('#page-body .act-edit-tpl');
            if (!btn) { throw new Error('原曲页没有可编辑的行'); }
            btn.click();
            await gs.wait(1800);
            if (!document.querySelector('.modal-overlay .modal')) { throw new Error('编辑浮层没出来'); }
            return true;`,
        legend: [
            {n: 1, text: '八格分三组，一组一种播放口径'},
            {n: 2, text: '一格一类：挑文件 / 取消选中 / 播放 / 移入冗余'},
            {n: 3, text: '播放键按组来；mid 与工程那两格没有'}
        ],
        mark: `
            // 牌子全挂到浮层**左边的空白**里（裁的是整个遮罩层，浮层居中，两侧留得下）——
            // 挂在右边就要压住那两排按钮，而按钮正是这几条要讲的东西
            const groups = document.querySelectorAll('.modal .sof-group');
            if (groups[0]) { gs.callout(groups[0], {n: 1, side: 'left'}); }
            const box = document.querySelector('.modal .sof-box');
            if (box) { gs.callout(box, {n: 2, side: 'left'}); }
            const play = document.querySelector('.modal .sof-play');
            if (play) { gs.callout(play, {n: 3, side: 'left'}); }
            return true;`
    },

    // ---------------- 填词 ----------------
    {
        name: 'song-fill-layout',
        page: fillPage,
        what: '填词页整体：工具条、选轨条、一句一卡的正文、右边缘的竖排开关',
        ready: '.fill-lines',
        crop: '#page-body',
        maxH: 1080,
        prep: `
            gs.scrub();
            // h2 是「曲名 + 一个歌手 span」，用 chars 只换曲名那一段，歌手 span 留着
            gs.chars('.fill-main .card h2', '[示例] 夜航星');
            gs.text('.fill-main .card h2 .muted', '[示例] 绫濑');
            // 这两行整行都是真实路径 / 文件名，scrub 只认得出盘符那一段，剩下的自己写死
            gs.text('.fill-main .card .fill-path',
                '[示例盘]\\\\示例目录\\\\[工程] 示例曲.svp');
            gs.text('.fill-main .card .fill-align .muted @0', '分句歌词：[demo] 示例曲.lrc');
            gs.text('#f-name', '填词 1', {attr: 'value'});
            ${FILL_LYRIC_SCRUB}
            return true;`,
        mark: `
            const cards = document.querySelectorAll('.fill-main > .card');
            if (cards[0]) { gs.callout(cards[0], {n: 1, label: '工具条：保存 / 导出 / 重新解析 / 删除', side: 'left'}); }
            if (cards[1]) { gs.callout(cards[1], {n: 2, label: '选轨条：勾上要一起填的声部', side: 'left'}); }
            if (cards[2]) { gs.callout(cards[2], {n: 3, label: '正文：一句一张卡片', side: 'left'}); }
            gs.callout('#f-side-toggle', {n: 4, label: '右栏默认收着，点这条竖排的字展开', side: 'left'});
            return true;`
    },
    {
        name: 'song-fill-card',
        page: fillPage,
        what: '一句卡片：句头与上下两排格子',
        ready: '.fill-lines',
        crop: '.fill-line',
        pad: 14,
        prep: `
            gs.scrub();
            ${FILL_LYRIC_SCRUB}
            // 给前几格填上示例字，看着才像在用的页面。**只改 DOM 的 value**：不走 input
            // 事件、不置脏，所以那个 5 秒自动保存不会启动（见文件头硬规矩 1）
            const line = document.querySelector('.fill-line');
            Array.from(line.querySelectorAll('.fill-cell')).slice(0, 9).forEach((c, i) => {
                c.value = gs.FAKE.word[i % gs.FAKE.word.length];
                c.classList.add('on');
            });
            return true;`,
        // 三块牌子全挂右边：句头与两排格子都是**满宽**的，牌子挂左边 / 上下都只会压住格子，
        // 而这三行右边一大截是空的（格子只占左边三分之二）—— 一行一块，谁也不挡谁
        mark: `
            const head = gs.el('.fill-line .fill-line-head');
            if (head) { gs.callout(head, {n: 1, label: '句头：第几句 / 时间 / 已填几个字', side: 'right'}); }
            const grids = document.querySelectorAll('.fill-line .fill-grid');
            if (grids[0]) { gs.callout(grids[0], {n: 2, label: '上排是原词，点它跳到下排', side: 'right'}); }
            if (grids[1]) { gs.callout(grids[1], {n: 3, label: '下排是你填的：一格一个字', side: 'right'}); }
            return true;`
    },
    {
        name: 'song-fill-split',
        page: fillPage,
        what: '断句：鼠标移到原词两个字中间，浮出竖线，点一下断开',
        ready: '.fill-lines',
        scale: 2,
        crop: '.fill-line',
        pad: 30,
        prep: `
            gs.scrub();
            ${FILL_LYRIC_SCRUB}
            // 找一格带竖线的原词格（首格与延音格没有），标出来给 hover 用
            const slot = Array.from(document.querySelectorAll('.fill-line .fill-slot'))
                .find(s => s.querySelector('.fill-split'));
            if (!slot) { throw new Error('这一句里没有能断开的格'); }
            slot.setAttribute('data-gs-hover', '1');
            gs.scroll('[data-gs-hover]');
            return true;`,
        hover: '[data-gs-hover]',
        expect: '[data-gs-hover] .fill-split',
        mark: `
            const head = gs.el('.fill-line .fill-line-head');
            if (head) { gs.callout(head, {n: 1, label: '先看句头：断出来的就是这个结果', side: 'top'}); }
            gs.callout('[data-gs-hover]', {n: 2, label: '两个字中间浮出竖线，点一下就在这里断开', side: 'bottom'});
            return true;`
    },
    {
        name: 'song-fill-gap',
        page: fillPage,
        what: '加空格：鼠标移到格子上方那条窄带上，浮出加号，点一下加一个空格',
        ready: '.fill-lines',
        scale: 2,
        // 裁一句卡，上下各留 60 的空白带专门给牌子 —— 只裁那一格（原先的做法）时，
        // 牌子被夹进一格那么大的图里，整张图就只剩两块牌子了
        crop: '.fill-line',
        pad: 60,
        prep: `
            gs.scrub();
            ${FILL_LYRIC_SCRUB}
            // 挑中间那一格：热区在这一列的**左缘上方**（就是格子上方那条窄带）
            const slots = Array.from(document.querySelectorAll('.fill-line .fill-slot'))
                .filter(s => s.querySelector('.fill-gap-hit'));
            const slot = slots[Math.min(3, slots.length - 1)];
            if (!slot) { throw new Error('这一句里没有可加空格的格'); }
            slot.setAttribute('data-gs-hover', '1');
            gs.scroll('[data-gs-hover]');
            return true;`,
        hover: '[data-gs-hover]',
        expect: '[data-gs-hover] .fill-gap-hit',
        // 两块牌子一律甩到卡外：dy 给一个大值，靠 setClip 的夹取把它们分别顶到
        // 「卡上方那条空白带」和「卡下方那条空白带」里（见 crop 的注释）
        mark: `
            gs.callout('[data-gs-hover] .fill-gap-hit',
                {n: 1, label: '格子上方这条窄带里的加号：点一下加一个空格', side: 'top', dy: -400});
            gs.callout('[data-gs-hover]',
                {n: 2, label: '空格落在延音链之后，不插进字和它的「-」之间', side: 'bottom', dy: 400});
            return true;`
    },
    {
        name: 'song-fill-polyphone',
        page: fillPage,
        what: '多音字：填进去的字有多个读音时，格子下沿虚线 + 鼠标移上去浮出建议条',
        ready: '.fill-lines',
        scale: 2,
        crop: '.fill-line',
        pad: 70,
        prep: `
            gs.scrub();
            ${FILL_LYRIC_SCRUB}
            // 给一格填一个多音字：浮条是「格子里已经有值、且那个值是多音字」才弹的。
            // **只改 DOM 的 value**，不派发任何输入事件 —— 见文件头硬规矩 1
            const cells = Array.from(document.querySelectorAll('.fill-line .fill-cell'));
            const cell = cells[2] || cells[0];
            if (!cell) { throw new Error('这一句里没有填写格'); }
            cell.value = '行';
            cell.classList.add('on', 'poly');
            cell.setAttribute('data-gs-hover', '1');
            gs.scroll('[data-gs-hover]');
            return true;`,
        hover: '[data-gs-hover]',
        expect: '#f-poly-chips',
        mark: `
            gs.callout('#f-poly-chips', {n: 1, label: '鼠标移上去才浮出的建议条：点一个候选字', side: 'bottom'});
            gs.callout('[data-gs-hover]', {n: 2, label: '字下沿那条虚线 = 这个字有多个常用读音', side: 'top'});
            return true;`
    },
    {
        name: 'song-fill-export',
        page: fillPage,
        what: '导出浮层：点一下生成「回填模板文本」，每一块旁边是复制',
        ready: '.fill-lines',
        crop: '.modal',
        maxH: 820,
        // 留白收到 0：默认那 12 会在图顶上挂出一条半截的工具条（浮层背后那一页的）
        pad: 0,
        prep: `
            gs.scrub();
            ${FILL_LYRIC_SCRUB}
            const btn = document.querySelector('#f-export');
            if (!btn) { throw new Error('找不到导出按钮'); }
            btn.click();
            await gs.wait(500);
            const text = document.querySelector('.modal [data-act="text"]');
            if (!text) { throw new Error('导出浮层没出来'); }
            text.click();     // 只读预览：后端只读 JSON 拼文本，一行库都不写
            await gs.wait(2000);
            if (!document.querySelector('.modal .fill-export')) { throw new Error('回填模板文本没生成出来'); }
            gs.text('.modal .modal-head span', '导出 · [示例] 夜航星');
            // 导出浮层是**一轨一块**：有几条轨就有几个框，标签与内容都要一格格换
            // （同一个选择器换成同一句，两块会长得一模一样，看不出是两条轨）
            if (!gs.text('.modal .fill-export strong', ['轨 #1 主唱', '轨 #2 和声'])) {
                throw new Error('导出浮层里没有轨的块');
            }
            // 框里是回填文本 = **照着真歌词生成的东西**，整框换掉；value 是 property，
            // 只改属性的话框里还是原文（gs.text 的 attr=value 已按元素类型分开处理）
            if (!gs.text('.modal .fill-textarea', [
                'ye hang xing zhe guang chun fen zhi hou - -',
                'wo ceng zou guo chun tian de hai bian - -'
            ], {attr: 'value'})) {
                throw new Error('导出浮层里没有文本框');
            }
            return true;`,
        mark: `
            gs.callout('.modal [data-act="text"]',
                {n: 1, label: '两种导出二选一，点哪个生成哪个', side: 'bottom'});
            const block = document.querySelector('.modal .fill-export');
            if (block) { gs.callout(block, {n: 2, label: '按轨一份、整轨一行、空格分隔，用来粘回合成器', side: 'bottom'}); }
            return true;`
    },
    {
        name: 'song-rhyme-query',
        page: 'song-rhyme',
        what: '韵脚词典的查询页',
        ready: '#rh-result .rh-chip',
        settle: 1100,
        crop: '#page-body',
        maxH: 860,
        prep: `return true;`,
        legend: [
            {n: 1, text: '锚：输一个字 / 一个韵母 / 一个韵部'},
            {n: 2, text: '按韵部把同韵的字和词列出来'},
            {n: 3, text: '单击选中、双击复制；下面写着韵部与频次'}
        ],
        mark: `
            // 往**上**摆：往下摆正好落在结果卡上方那句说明上，而 ② 也摆在那儿，两个编号叠一起
            gs.callout('.rh-bar', {n: 1, side: 'top'});
            gs.callout('#rh-result', {n: 2, side: 'top'});
            const chip = gs.el('#rh-result .rh-chip');
            if (chip) { gs.callout(chip, {n: 3, side: 'bottom'}); }
            return true;`
    }
];

// ==================================================================
// 六、入口
// ==================================================================

/**
 * 量尺：打开一页，把几个选择器的框打出来。写镜头时「这一段到底在哪、有多宽」全靠猜 ——
 * 猜错的后果是裁剪框切掉半张卡片、牌子压在别的控件上，而且**看截图看不出来是算错了**。
 * 所以留这个只读的口子：`--rect #settings "#page-body,.card,.set-mod-head"`。
 */
async function probe(spec) {
    const args = process.argv.slice(2);
    const page = (args[args.indexOf('--rect') + 1] || '').replace(/^#/, '');
    const sels = (args[args.indexOf('--rect') + 2] || '#page-body').split(',');
    const {proc, profile, pageWs} = await launchChrome();
    const cdp = new Cdp(pageWs);
    try {
        await cdp.open();
        await cdp.send('Page.enable');
        await cdp.send('Runtime.enable');
        await cdp.send('Emulation.setDeviceMetricsOverride',
            {width: 1280, height: 1200, deviceScaleFactor: 1, mobile: false});
        await cdp.send('Page.navigate', {url: BASE + '/?_=1' + (page ? '#' + page : '')});
        await cdp.once('Page.loadEventFired', 30000);
        await sleep(2000);
        // 想让页面先点开某个浮层再量，就再给一段 JS（和 prep 同样写成函数体）
        const js = args[args.indexOf('--js') + 1];
        if (js) {
            const out = await evalJs(cdp, '(async () => { ' + js + ' })()', true);
            if (out != null && out !== '') { console.log('--js 返回：' + out); }
            await sleep(300);
        }
        for (const s of sels) {
            const r = await rectOf(cdp, s.trim());
            const base = splitAt(s.trim()).sel;
            const n = await evalJs(cdp, 'document.querySelectorAll('
                + JSON.stringify(base) + ').length');
            console.log(String(s).trim().padEnd(34) + '×' + n + '  ' + JSON.stringify(r));
        }
        console.log('视口滚动 document.scrollHeight = '
            + await evalJs(cdp, 'document.documentElement.scrollHeight'));
    } finally {
        try { cdp.ws.close(); } catch { /* ignore */ }
        proc.kill();
        await sleep(300);
        fs.rmSync(profile, {recursive: true, force: true});
    }
}

async function main() {
    const args = process.argv.slice(2);

    if (args.includes('--list')) {
        for (const s of SHOTS) {
            console.log('  ' + s.name.padEnd(22) + (s.manual ? '[人工] ' + s.manual : s.what));
        }
        return;
    }

    // --rect <页面> <选择器…>：调 crop / 标注位置时的量尺，不截图
    if (args.includes('--rect')) {
        const spec = args[args.indexOf('--rect') + 1] || '';
        return probe(spec);
    }

    const filter = args.filter(a => !a.startsWith('-'));
    const list = filter.length ? SHOTS.filter(s => filter.some(f => s.name.includes(f))) : SHOTS;
    if (!list.length) {
        console.log('没有匹配的镜头。可用：' + SHOTS.map(s => s.name).join(', '));
        return;
    }

    const alive = await fetch(BASE + '/api/modules').then(r => r.ok).catch(() => false);
    if (!alive) {
        throw new Error('后端没起来（' + BASE + '）—— 先起后端再跑本工具');
    }
    // 填词页那几张要一份真实存在的填词：id 从接口取，别写死在文件里
    const fills = await fetch(BASE + '/api/song/fill/list').then(r => r.json())
        .then(d => (d && d.data) || []).catch(() => []);
    FILL = Array.isArray(fills) ? (fills[0] || null) : null;
    if (!FILL && list.some(s => s.page === fillPage)) {
        console.log('提示：库里一份填词都没有，填词页那几张会失败');
    }

    fs.mkdirSync(OUT_DIR, {recursive: true});
    const {proc, profile, pageWs} = await launchChrome();
    const cdp = new Cdp(pageWs);
    await cdp.open();
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');

    let ok = 0;
    const skipped = [];
    const failed = [];
    try {
        for (const shot of list) {
            if (shot.manual) {
                skipped.push(shot.name + ' —— ' + shot.manual);
                continue;
            }
            try {
                const size = await shoot(cdp, shot);
                ok++;
                console.log('  ok   ' + (shot.name + '.png').padEnd(28) + size.padEnd(11) + shot.what);
            } catch (e) {
                failed.push(shot.name + ' —— ' + e.message);
                console.log('  FAIL ' + (shot.name + '.png').padEnd(28) + e.message);
            }
        }
    } finally {
        try { cdp.ws.close(); } catch { /* 关不掉就算了 */ }
        proc.kill();
        await sleep(300);
        fs.rmSync(profile, {recursive: true, force: true});
    }

    if (skipped.length) {
        console.log('\n交人工（截不了，原因见右）：');
        skipped.forEach(s => console.log('  --   ' + s));
    }
    console.log('\n' + ok + ' 张已写入 ' + path.relative(ROOT, OUT_DIR).replace(/\\/g, '/')
        + (failed.length ? '，' + failed.length + ' 张失败' : ''));
    if (failed.length) {
        process.exitCode = 1;
    }
}

main().catch((e) => {
    console.error('FAILED：' + e.message);
    process.exit(1);
});
