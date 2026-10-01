/**
 * 填词页批量填词的行为核对：`applyReflow`（重新分行 —— 分句重建 / 视觉空位 / 声部组重编号）、
 * `batchUnits` 与 `applyByLine`（文本切分 / 按句对位 / 文本里的「-」当普通单元 / 一句里多个
 * 声部按顺序吃同一行文本 / **字数正好合上时把一行拆到相邻几句、把相邻几行并进一句**，见例 8b）；
 * 末尾几例覆盖**手动**那几条路径：`mergeLine`（并入上一句按轨归组）、
 * `stepInLine`（自动前进/退格：先走完本声部那一行再进下一行）、`copyTwinOnBlur`（光标离开才把
 * 值抄给合唱副本的空格）、`selSlots` / `mirrorCopies`（框选只圈本行那一行；批量填词把副本声部
 * 整行对齐到源、盖掉残留旧值）、`clearCellEverywhere`（清一格连副本声部那一格一起清，否则联动
 * 当场抄回来 —— 第 59 / 60 / 62 条）。
 * 不属于 Maven 构建，`node tools/songfill-reflow-check.js` 手动跑（全绿打印 ALL OK、退出码 0）。
 *
 * 做法：把 `songfill.js` 的 IIFE 在 node 里载起来（DOM 全部塞空壳 Proxy），**临时**在它的
 * `return` 那一行注入 `_x` 导出钩子、直接调内部函数 —— 所以 `return {render, _tokenize: tokenize};`
 * 那行是个硬约定，改了这里会直接抛错提醒你改钩子，不会静默跑成「什么都没测」。
 * 断言按「某一组渲染出来的那一行字」比（`row()`），不手数下标。
 *
 * 背景与口径见 `docs/填词工具设计.md` §13 第 43 条（重新分行）、第 46 条（「-」占位）、
 * 第 50 条（一句里多个声部）与第 55 条（手动合并 / 前进 / 失焦抄写）。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const FH = path.join(__dirname, '..', 'src/main/resources/static/js/songfill.js');
let src = fs.readFileSync(FH, 'utf8');
const hook = 'return {render, _tokenize: tokenize};';
if (!src.includes(hook)) {
    throw new Error('导出钩子没找到：songfill.js 末尾的 `return` 行改了？改钩子以匹配。');
}
src = src.replace(hook, `return {render, _tokenize: tokenize, _x: {
    applyReflow, applyByLine, batchUnits, mkLine, lineGroups, longestGroup, isDash,
    alignByLine, capacity,
    lineLyric, lineTargets, stepInLine, mergeLine, copyTwinOnBlur, mirrorCopies,
    selSlots, groupLyric, clearCellEverywhere, syncVoiceCopies,
    setState: (s) => { lines = s.lines; filled = s.filled; defaults = s.defaults;
        gaps = s.gaps; brackets = s.brackets; noteMap = s.noteMap || new Map(); },
    getState: () => ({lines, filled, defaults, gaps, brackets})
}};`);
src += '\n;globalThis.__PAGE = SongFillPage;\n';

const noop = () => {};
const anyProxy = () => new Proxy(function () {}, {
    get: (t, p) => (p === Symbol.toPrimitive ? () => '' : anyProxy()),
    apply: noop
});
global.window = anyProxy();
global.document = anyProxy();
global.location = {hash: ''};
vm.runInThisContext(src, {filename: FH});

const X = global.__PAGE._x;

// ==================== 假骨架 ====================
// 合唱两轨，各 24 个音符；两轨**逐格相同**（同 onset、同原词），note 3 / 17 是延音
// —— 这就是「整轨复制」的实况（《免我蹉跎苦》那种），两组合成一句但只算一份歌词，
// 填词只填代表组、另一组靠联动复制跟上（见 docs/填词工具设计.md §13 第 50 条）。
// 原有分句 6 句，每句取两轨各 4 个音符（组 0 = 轨 0，组 1 = 轨 1）。
const NOTES = 24;
function slotOf(track, n) {
    const dash = n === 3 || n === 17;
    return {
        trackIndex: track, noteIndex: n,
        slotType: dash ? 'DASH' : 'HANZI', original: dash ? '-' : '原' + n
    };
}
function fillState(lines, noteMap) {
    return {
        lines,
        filled: lines.map(l => l.slots.map(() => '')),
        defaults: lines.map(l => l.slots.map(() => '<默认>')),
        gaps: lines.map(() => []),
        brackets: lines.map(l => l.slots.map(() => false)),
        noteMap
    };
}
function onsetMap(tracks, at) {
    const noteMap = new Map();
    tracks.forEach(({track, onset}) => {
        for (let n = 0; n < NOTES; n++) {
            noteMap.set(track + ':' + n, {trackIndex: track, noteIndex: n, onset: onset(n)});
        }
    });
    return noteMap;
}
function build() {
    const noteMap = onsetMap([{track: 0, onset: n => n * 70560000},
        {track: 1, onset: n => n * 70560000}]);
    const t0 = [];
    const t1 = [];
    for (let n = 0; n < NOTES; n++) {
        t0.push(slotOf(0, n));
        t1.push(slotOf(1, n));
    }
    const lines = [];
    for (let i = 0; i < 6; i++) {
        const a = i * 4;
        lines.push(X.mkLine(t0.slice(a, a + 4).concat(t1.slice(a, a + 4)),
            [0, 0, 0, 0, 1, 1, 1, 1]));
    }
    return fillState(lines, noteMap);
}

// 「一句里两个声部各唱各的」—— 轨 0 与轨 1 的音符**错开**（轨 1 晚 1 秒起唱，远超 0.06 秒的
// 孪生容差），两组音符互不相同，所以这一句的歌词是两段（对话框那一行里空格分隔），不是一份。
// 实况见 Blackpink / 芒种那种「一轨唱完接另一轨」的句子。
// 2 句：第 1 句 = 两轨各 3 个有词音符（各 3 格）；第 2 句 = 各 1 个延音 + 各 1 个有词音符。
function buildDuet() {
    const noteMap = onsetMap([{track: 0, onset: n => n * 70560000},
        {track: 1, onset: n => n * 70560000 + 1411200000}]);
    const groups = [0, 0, 0, 1, 1, 1];
    const lines = [
        X.mkLine([slotOf(0, 0), slotOf(0, 1), slotOf(0, 2),
            slotOf(1, 0), slotOf(1, 1), slotOf(1, 2)], groups),
        X.mkLine([slotOf(0, 3), slotOf(0, 4), slotOf(1, 3), slotOf(1, 4)], [0, 0, 1, 1])
    ];
    return fillState(lines, noteMap);
}

// 「一句里两条轨的音符**按 onset 逐拍交错**」—— 后端的真实排布（`LyricFillAligner.line` 按代表
// 音符时刻展开成员，实测 groups = [0,1,0,1,…]，快照 `target/corpus-fix7.txt` 同为
// `1:H:0 2:H:1 1:D:0 …`），与 `build()` 那种「组内连续」的假布局正相反（《aLIEz》那种两轨错开
// 又合成一句的实况）。第 1 句：两轨各 3 格、轨 1 晚 1 秒（互不包含 → 两行都算歌词）；
// 第 2 句：只有轨 1 一格（句内组号是局部的 —— 它的「组 0」与上一句的「组 0」不是同一条轨）。
function buildInterleaved() {
    const noteMap = onsetMap([{track: 0, onset: n => n * 70560000},
        {track: 1, onset: n => n * 70560000 + 1411200000}]);
    const lines = [
        X.mkLine([slotOf(0, 0), slotOf(1, 0), slotOf(0, 1), slotOf(1, 1), slotOf(0, 2), slotOf(1, 2)],
            [0, 1, 0, 1, 0, 1]),
        X.mkLine([slotOf(1, 6)], [0])
    ];
    return fillState(lines, noteMap);
}

let failed = 0;
function eq(actual, expected, what) {
    const a = JSON.stringify(actual);
    const e = JSON.stringify(expected);
    if (a === e) {
        console.log('  ok   ' + what);
    } else {
        failed++;
        console.log('  FAIL ' + what + '\n        期望 ' + e + '\n        实际 ' + a);
    }
}

/** 第 g 组渲染出来的一行：填的词 / 延音画 `-` / 空格位 / 空格处画 `·`；空位用 ␣ 标出来 */
function row(s, i, g) {
    const slots = X.lineGroups(s.lines[i])[g] || [];
    return slots.map(si => {
        const v = s.filled[i][si];
        const txt = v || (X.isDash(s.lines[i].slots[si]) ? '-' : '·');
        return txt + ((s.gaps[i] || [])[si] ? '␣' : '');
    }).join('');
}

function runOn(st, text, skipDash) {
    X.setState(st);
    const extra = X.applyReflow(text, skipDash !== false);
    return {s: X.getState(), extra};
}
function run(text, skipDash) {
    return runOn(build(), text, skipDash);
}

// ==================== 1) 合并 + 行内空格 + 重建 ====================
// 22 个字正好铺满轨 0 的 22 个可填格；第 1 行 11 个字落在原第 1~3 句上
let r = run(['我叹那春花秋月 不问别离', '阁楼里写一纸相思未停笔']);
console.log('--- 例 1\n  句数 ' + r.s.lines.length + '，丢弃 ' + r.extra);
r.s.lines.forEach((_, i) => console.log('  #' + i + ' 轨0 |' + row(r.s, i, 0) + '|  轨1 |' + row(r.s, i, 1) + '|'));
eq(r.s.lines.length, 2, '6 句 → 2 句（文本 2 行）');
eq(r.extra, 0, '22 个字全放下');
eq(row(r.s, 0, 0), '我叹那-春花秋月␣不问别离', '第 1 句：合并了原 1~3 句，行内空格 → 「月」后一个空位');
eq(row(r.s, 1, 0), '阁楼里写一-纸相思未停笔', '第 2 句：接着填满剩下的格');
eq(row(r.s, 0, 1), '我叹那-春花秋月␣不问别离', '合唱轨：联动复制跟上，空位也跟着展开（后端同一口径）');
eq(X.lineGroups(r.s.lines[0]).map(g => g.length), [12, 12], '两条轨各占一行、各自贯穿合并后的句子');
eq(r.s.defaults[0].every(d => d === '<默认>'), true, '默认词跟着槽位走，没丢');

// ==================== 2) 幂等 ====================
const snap = s => JSON.stringify({
    lines: s.lines.map(l => l.groups), filled: s.filled,
    gaps: s.gaps.map(x => x.map(v => !!v))
});
const first = snap(r.s);
X.applyReflow(['我叹那春花秋月 不问别离', '阁楼里写一纸相思未停笔'], true);
eq(snap(X.getState()), first, '同样文本再跑一次：分句 / 填词 / 空位一字不差');

// ==================== 3) 分句按文本行数走；行首缩进不是空位 ====================
r = run(['  我叹那', '春花秋月', '不问别离']);
console.log('--- 例 2（3 行，首行缩进）\n  句数 ' + r.s.lines.length);
r.s.lines.forEach((_, i) => console.log('  #' + i + ' |' + row(r.s, i, 0) + '|'));
// 11 个字铺在轨 0 前 11 个可填格上 = 原第 1~3 句；尾部 3 句没被文本碰到，原样留着
eq(r.s.lines.length, 6, '文本 3 行 + 尾部 3 句原样保留');
eq(row(r.s, 0, 0), '我叹那-', '第 1 句：延音留在句尾');
eq(row(r.s, 1, 0), '春花秋月', '第 2 句：行首缩进没被当空位');
eq(row(r.s, 2, 0), '不问别离', '第 3 句');
eq(row(r.s, 3, 0), '····', '尾部第 4 句：没被碰，还是自己的 4 格');

// ==================== 4) 字比格多：截断 + 报数 ====================
const LONG = '一二三四五六七八九十百千万亿零壹贰叁肆伍陆柒捌';   // 23 个字，只有 22 格
r = run([LONG]);
console.log('--- 例 3（23 个字，只放得下 22）\n  句数 ' + r.s.lines.length + '，丢弃 ' + r.extra);
console.log('  #0 轨0 |' + row(r.s, 0, 0) + '|');
eq(LONG.length, 23, '样例文本 23 个字');
eq(r.extra, 1, '丢 1 个字');
eq(row(r.s, 0, 0), '一二三-四五六七八九十百千万亿零壹-贰叁肆伍陆柒', '单行文本 → 单句，延音格空着不占字');
eq(r.s.lines.length, 1, '单句（文本铺满了整首）');

// ==================== 5) 文本只覆盖前半：尾部原样保留分句 ====================
r = run(['甲乙丙丁']);
console.log('--- 例 4（只填 4 个字）\n  句数 ' + r.s.lines.length);
r.s.lines.forEach((_, i) => console.log('  #' + i + ' |' + row(r.s, i, 0) + '|'));
// 原第 1 句的轨 0 只有 3 个可填格（note 3 是延音），所以第 4 个字落到原第 2 句
eq(r.s.lines.length, 5, '覆盖区 2 句并成 1 句，尾部 4 句原样留着');
eq(row(r.s, 0, 0), '甲乙丙-丁···', '4 个字跨过原第 1 句的延音、接着吃原第 2 句');
eq(row(r.s, 1, 0), '····', '尾部第 3 句没被碰');
eq(r.s.filled[1].every(v => v === ''), true, '尾部一句都没被写');

// ==================== 6) 空文本 / 全是标点 = 空操作（不把整首并成一句） ====================
X.setState(build());
const keep = snap(X.getState());
eq(X.applyReflow([], true), 0, '空文本返回 0');
eq(snap(X.getState()), keep, '空文本不改分句');
eq(X.applyReflow(['  ', '\t'], true), 0, '只有空白也是空操作');
eq(snap(X.getState()), keep, '只有空白也不改分句');
eq(X.applyReflow(['！！！。。。'], true), 0, '标点被忽略，不算「放不下」');
eq(snap(X.getState()), keep, '只有标点也不改分句');

// ==================== 7) 文本里的「-」与汉字同权 ====================
// 轨 0 的 note 3 / 17 是延音。文本在这两位各写一个「-」，长度与槽位一一对应，于是 22 个汉字
// + 2 个「-」正好铺满轨 0 的 24 个槽 —— 不勾「忽略延音」时字与格严格对上（没写「-」的话
// 22 个字会怼进 24 个格、后面的字整体前挪一格）。
const HAN22 = '一二三四五六七八九十百千万亿零壹贰叁肆伍陆柒';
const WITH_DASH = '一二三-四五六七八九十百千万亿零壹-贰叁肆伍陆柒';
eq(HAN22.length, 22, '22 个汉字');
eq(X.batchUnits(WITH_DASH).length, 24, '「-」也切成单元（22 个字 + 2 个「-」）');
eq(X.batchUnits('我叹那-春'), ['我', '叹', '那', '-', '春'], '「-」排在汉字之间，不打乱顺序');

r = run([WITH_DASH], false);
console.log('--- 例 5（不勾「忽略延音」，文本里写了 -）\n  句数 ' + r.s.lines.length + '，丢弃 ' + r.extra);
console.log('  #0 轨0 |' + row(r.s, 0, 0) + '|');
eq(r.extra, 0, '一个字都没丢');
eq(row(r.s, 0, 0), WITH_DASH, '24 个单元铺满 24 格，汉字原位不动');
eq(r.s.filled[0][3], '-', '「-」和汉字一样**写进格子**（界面上看得见），不是「占位不改值」');
eq(r.s.lines[0].slots[3].slotType, 'HANZI',
    '它落在延音格上就把那格激活成真音 —— 与汉字同权，不特殊匹配原词是「-」的格子');

// 反面对照：文本里没写「-」时行为一字未变（22 个字仍怼进 24 格，第 4 格被字占掉并激活）
r = run([HAN22], false);
eq(r.s.filled[0][3], '四', '没写「-」时老行为不变');

// ==================== 8) 按句模式一条同口径的线 ====================
// applyByLine 是另一条代码路径，同样得让「-」当普通单元 —— 两条路走岔了才是最麻烦的
X.setState(build());
let extra = X.applyByLine(['一二-三'], false);
eq(extra, 0, '按句模式：一个字都没丢');
eq(X.getState().filled[0].slice(0, 4), ['一', '二', '-', '三'],
    '「-」按序占第 3 格并写进去，后面的字照常顺延');
X.setState(build());
extra = X.applyByLine(['一二三-'], true);
eq(X.getState().filled[0].slice(0, 4), ['一', '二', '三', ''],
    '勾「忽略延音」：第 4 格被跳过后「-」没格子可去，整行丢弃');
eq(extra, 1, '丢弃的就是那个「-」');

// ==================== 8b) 按句模式的拆 / 并（字数正好合上才拆 / 并） ====================
// 用户口径：原词 5 + 2 字时填一行 7 字 → 拆开匹配；原词 7 字时填 5 + 2 两行 → 并起来匹配。
// 判据是**可填格数正好相等**（不是韵脚），差一个字就不拆不并（见 docs/填词工具设计.md §13 第 58 条）。
/** 每句容量可定制的骨架：一条轨、按 sizes 逐句铺有词格（组 0），onset 依次递增。 */
function buildCaps(sizes) {
    const noteMap = new Map();
    let n = 0;
    const lines = sizes.map(size => {
        const slots = [];
        for (let k = 0; k < size; k++) {
            // 不走 slotOf：它把「第 3 / 17 个音符」当延音（那是 build() 的骨架设定），
            // 这里要的是**每个音符都算一格**的干净骨架
            slots.push({trackIndex: 0, noteIndex: n, slotType: 'HANZI', original: '原' + n});
            noteMap.set('0:' + n, {trackIndex: 0, noteIndex: n, onset: n * 70560000});
            n++;
        }
        return X.mkLine(slots, slots.map(() => 0));
    });
    return fillState(lines, noteMap);
}
function byLine(st, rows, skipDash) {
    X.setState(st);
    const extra = X.applyByLine(rows, skipDash !== false);
    return {s: X.getState(), extra};
}
/** 某句填进去的那串字（空串 = 一个字没填）。 */
const cells = (s, i) => s.filled[i].map(v => v || '').join('');

X.setState(buildCaps([5, 2]));
eq(X.capacity(0, true), 5, '第 1 句 5 个可填格');
eq(X.capacity(1, true), 2, '第 2 句 2 个可填格');
eq(X.alignByLine([5, 2], [7]), [{line: 0, row: 0, lines: 2, rows: 1}], '7 字一行 = 5 + 2 两格 → 拆成一句跨两句');
eq(X.alignByLine([7], [5, 2]), [{line: 0, row: 0, lines: 1, rows: 2}], '7 格一句 = 5 + 2 两行 → 并成两句进一句');
eq(X.alignByLine([5, 2], [6]), [{line: 0, row: 0, lines: 1, rows: 1}], '6 字 ≠ 5 + 2 → 老老实实逐句对位');
eq(X.alignByLine([3, 3], [6, 3]),
    [{line: 0, row: 0, lines: 1, rows: 1}, {line: 1, row: 1, lines: 1, rows: 1}],
    '下一行正好对上第二句 → 不许把它抢过来拆（「只要下一句也能匹配上」）');

let b = byLine(buildCaps([5, 2]), ['一二三四五六七']);
console.log('--- 例 8b-1（原词 5 + 2，填一行 7 字）\n  丢弃 ' + b.extra);
eq(b.extra, 0, '7 个字全放下');
eq(cells(b.s, 0), '一二三四五', '前 5 个字落在第 1 句');
eq(cells(b.s, 1), '六七', '后 2 个字拆到第 2 句');

b = byLine(buildCaps([7]), ['一二三四五', '六七']);
console.log('--- 例 8b-2（原词 7，填 5 + 2 两行）\n  丢弃 ' + b.extra);
eq(b.extra, 0, '一个字都没丢（并起来正好填满）');
eq(cells(b.s, 0), '一二三四五六七', '两行的字并进同一句');

b = byLine(buildCaps([5, 2]), ['一二三四五六'], true);
console.log('--- 例 8b-3（6 字对 5 + 2：差一个字，不拆）\n  丢弃 ' + b.extra);
eq(b.extra, 1, '多出来的那 1 个字被丢弃（不硬拆到下一句）');
eq(cells(b.s, 0), '一二三四五', '第 1 句照常');
eq(cells(b.s, 1), '', '第 2 句一个字没动');

b = byLine(buildCaps([3, 4]), ['甲乙丙', '丁戊己庚']);
eq(b.extra, 0, '字数与格子一一对应时行为不变');
eq([cells(b.s, 0), cells(b.s, 1)], ['甲乙丙', '丁戊己庚'], '逐句对位，不拆不并');

// 拆 / 并可以同时出现：第 1 行跨前两句，第 2 行正好填第 3 句
b = byLine(buildCaps([5, 2, 5]), ['一二三四五六七', '甲乙丙丁戊']);
console.log('--- 例 8b-4（拆一句 + 逐句对位）\n  丢弃 ' + b.extra);
eq(b.extra, 0, '没有一个字放不下');
eq([cells(b.s, 0), cells(b.s, 1), cells(b.s, 2)],
    ['一二三四五', '六七', '甲乙丙丁戊'], '第 1 行拆到前两句，第 2 行照原位');

// ==================== 9) 一句里的两个声部各唱各的：一行文本摊给该句每个声部 ====================
// 新旧行为的分水岭：老代码把整行字全灌进「最长的那组」（代表轨），另一组等联动复制 ——
// 两轨音符不同的句子就丢词、那轨一个字都填不上。实况：Blackpink 第 4 句
// 「kojoqiyagaji ogujiala」= 10 格那组吞掉 13 个字、4 格那组空着；芒种「前世迟来者（擦肩而过）」
// 同理。现在按 lineLyric 的声部顺序依次落进每个声部（整轨复制的那份仍靠联动复制）。
let d = buildDuet();
X.setState(d);
eq(X.lineLyric(0), '<默认><默认><默认> <默认><默认><默认>', '两轨音符错开：这句子算两段歌词，空格分隔');
eq(X.applyByLine(['甲乙丙 丁戊己', '庚辛'], true), 0, '按句模式：两段 + 第二句的字都放得下');
eq(row(X.getState(), 0, 0), '甲乙丙', '第 1 句声部 0 吃前 3 个字');
eq(row(X.getState(), 0, 1), '丁戊己', '第 1 句声部 1 接着吃后 3 个字');
eq(row(X.getState(), 1, 0), '-庚', '第 2 句声部 0：延音照旧跳过，字落在后面那格');
eq(row(X.getState(), 1, 1), '-辛', '第 2 句声部 1 同理');

r = runOn(buildDuet(), ['甲乙丙 丁戊己', '庚辛']);
console.log('--- 例 6（重新分行：两个声部各唱各的）\n  句数 ' + r.s.lines.length + '，丢弃 ' + r.extra);
r.s.lines.forEach((_, i) =>
    console.log('  #' + i + ' 声部0 |' + row(r.s, i, 0) + '|  声部1 |' + row(r.s, i, 1) + '|'));
eq(r.extra, 0, '重新分行：一个字都没丢');
eq(r.s.lines.length, 2, '文本 2 行 → 2 句');
eq(row(r.s, 0, 0), '甲乙丙␣-', '第 1 句声部 0：自己那 3 个字 + 句尾延音（空格落成声部界上的视觉空位）');
eq(row(r.s, 0, 1), '丁戊己', '第 1 句声部 1：接着吃后 3 个字');
eq(row(r.s, 1, 0), '庚', '第 2 句声部 0：延音跳过，字落在可填格');
eq(row(r.s, 1, 1), '-辛', '第 2 句声部 1 同理，延音留在格上');
eq(r.s.lines[0].groups.slice(0, 3).every(g => g === r.s.lines[0].groups[0]), true,
    '合并重建后两轨仍是各自一组（没被并成一组）');

// ==================== 10) 自动前进：先走完本声部那一行，再进下一行 ====================
// 老代码照平坦下标走相邻格（`neighbour`），而多声部句的槽位是按 onset 交错的 —— 从本行第 1 格
// 走一步就踩进另一条轨的格子。现在按 lineTargets 的行序走：本行走完才换行。
const il = buildInterleaved();
X.setState(il);
console.log('--- 例 7（行内行进：两轨交错的一句）\n  行序 ' +
    JSON.stringify(X.lineGroups(il.lines[0])));
eq(X.lineGroups(il.lines[0]).map(g => g.length), [3, 3], '两轨各一行：轨 0 = 平坦下标 0/2/4，轨 1 = 1/3/5');
eq(X.stepInLine(0, 0, 1, true), 2, '本行下一格（老行为会给 1 —— 那是另一条轨的格）');
eq(X.stepInLine(0, 4, 1, true), 1, '本行走完 → 下一行的第一个可填格');
eq(X.stepInLine(0, 5, 1, true), -1, '整句走完 → -1（commit 报「超出」、不挪光标）');
eq(X.stepInLine(0, 2, -1, true), 0, '退格同理：本行上一格');
eq(X.stepInLine(0, 1, -1, true), 4, '本行行首 → 上一行的最后一格');
eq(X.stepInLine(0, 0, -1, true), -1, '整句之前没有了');
// 延音在行尾、两行都吃字（两轨错开）时：跳掉本行的延音、换到下一行
X.setState(fillState([
    X.mkLine([slotOf(0, 0), slotOf(1, 0), slotOf(0, 3), slotOf(1, 1)], [0, 1, 0, 1])
], onsetMap([{track: 0, onset: n => n * 70560000},
    {track: 1, onset: n => n * 70560000 + 1411200000}])));
eq(X.stepInLine(0, 0, 1, true), 1, '本行下一格是延音（行尾）→ 跳过它、换到下一行的第一格；反向 = 3');
eq(X.stepInLine(0, 1, -1, true), 0, '反向：本行行首 → 上一行的最后一格（延音跳过）');
// 整轨复制那句（build()，两轨逐格相同）：副本行**不参与吃字**，所以本行走完就是句尾
X.setState(build());
eq(X.stepInLine(0, 2, 1, true), -1, '副本行不吃字（它的字靠失焦抄写）→ 走到本行尾就是 -1');
eq(X.stepInLine(0, 4, -1, true), 2, '点进副本行也能走本行：退回全行序，上一行的最后一格');

// ==================== 11) 手动合并：组身份是轨（不是句内组号） ====================
// 组号是**句内局部**的：下一句的「组 0」与上一句的「组 0」往往不是同一条轨。按组号拼会把两个轨
// 糊成一行（《栖凰》「谯鼓响」后端修的就是这个坑）；按轨归组才对：上一句有这条轨 → 并进它那一行，
// 没有 → 新开一行（= 用户说的「合并成同一句子中的 2 行」）。
/** 每一行的「轨号序列」（一行一组，组内按槽位顺序）—— 断言组归属用，比数下标直观 */
const tracks = (s, i) => X.lineGroups(s.lines[i])
    .map(slots => slots.map(si => s.lines[i].slots[si].trackIndex).join(''));

X.setState(buildInterleaved());
X.mergeLine(null, 1, false);
console.log('--- 例 8（并入上一句：上一句两轨、下一句只有轨 1）\n  行序 ' +
    JSON.stringify(X.lineGroups(X.getState().lines[0])));
eq(X.getState().lines.length, 1, '两句并成一句');
eq(tracks(X.getState(), 0), ['000', '1111'],
    '轨 1 原有的三格 + 搬来的那一格在同一行（轨 0 的三格没被糊进来）'
    + '—— 按组号拼会得到 0000 / 111（搬来的「组 0」被当成轨 0）');
eq(X.getState().lines[0].groups.length, X.getState().lines[0].slots.length, '组号与槽位仍等长');

// 上一句只有轨 0、下一句只有轨 1 → 没有同一轨道 → 这一句就该是两行
const nm = onsetMap([{track: 0, onset: n => n * 70560000}, {track: 1, onset: n => n * 70560000}]);
X.setState(fillState([
    X.mkLine([slotOf(0, 0), slotOf(0, 1)], [0, 0]),
    X.mkLine([slotOf(1, 0), slotOf(1, 1)], [0, 0])
], nm));
X.mergeLine(null, 1, false);
console.log('  没有同一轨道 → 行序 ' + JSON.stringify(X.lineGroups(X.getState().lines[0])));
eq(tracks(X.getState(), 0), ['00', '11'], '两条轨各占一行（同 slotOf 的「组 0」没被当成同一组）');

// 同一轨道的相邻两句 → 并进同一行（用户说的「合并到同一个轨道的句子里」）
X.setState(fillState([
    X.mkLine([slotOf(0, 0)], [0]),
    X.mkLine([slotOf(0, 1), slotOf(1, 0)], [0, 1])
], nm));
X.mergeLine(null, 1, false);
eq(tracks(X.getState(), 0), ['00', '1'],
    '轨 0 的上一句 + 轨 0 的那一格并进同一行，轨 1 那一格另开一行');

// ==================== 12) 光标离开才抄给合唱副本的空格 ====================
// 「两个轨道因为音符相同合并成一句」= build() 那种逐格相同（组 1 是组 0 的副本）。
// 只补空格、不覆盖；源格自己空着也不反向回填。
let st = build();
X.setState(st);
eq(X.copyTwinOnBlur(0, 0), [], '源格空着 → 什么都不抄（也不反向回填）');
eq(X.getState().filled[0][4], '', '副本行还是空的');
X.getState().filled[0][0] = '甲';
eq(X.copyTwinOnBlur(0, 0), [4], '本格有字 → 抄给副本行的同音符格（返回被补的下标）');
eq(X.getState().filled[0][4], '甲', '副本行填上了');
X.getState().filled[0][4] = '乙';
eq(X.copyTwinOnBlur(0, 0), [], '副本格已有值 → 不覆盖');
eq(X.getState().filled[0][4], '乙', '人家自己填的字留着');

// ==================== 13) 选区只圈本行；批量填词把副本行整行对齐 ====================
// 用户报（2026-09-14）：① 框选「第 44 句的第一行」两行都高亮，把第一行复制到第二行「顺序都乱了」
// —— 拖选/取字/整段清空按**平坦下标区间**扫，而多声部句的槽位是按 onset 逐拍交错的
// （groups = [0,1,0,1,…]），区间把另一条轨夹在中间的格子一起圈了进来（见第 59 条）；
// ② 批量填词后「第 44 句之后同一句的不同音轨都不一样」—— 副本行的旧值（后端按 (轨, 音符) 回指
// 保留下来的）在「只补空」的联动下永远盖不掉（见第 60 条）。

// 交错骨架（buildInterleaved）：轨 0 = 平坦下标 0/2/4，轨 1 = 1/3/5
X.setState(buildInterleaved());
const il2 = X.getState();
eq(X.lineGroups(il2.lines[0]).map(g => g.join('')), ['024', '135'],
    '交错骨架：两条轨各占一行（平坦下标交错）');
eq(X.selSlots(il2.lines[0], 0, 0, 4), [0, 2, 4], '框选第 0 行 0..4 格：只圈本行的三格（不含另一行的 1/3）');
eq(X.selSlots(il2.lines[0], 1, 5, 1), [1, 3, 5], '反向框选同理（from > to 一样认）');
eq(X.selSlots(il2.lines[0], 0, 1, 3), [], '起点不属于这一组 → 空（选区与行对不上时不该动格）');
// 取字走 groupLyric：两条轨各填上不同的字，取出来的必须是本行那串，不是交错的
il2.filled[0][0] = '甲'; il2.filled[0][2] = '乙'; il2.filled[0][4] = '丙';
il2.filled[0][1] = '一'; il2.filled[0][3] = '二'; il2.filled[0][5] = '三';
X.setState(il2);
eq(X.groupLyric(0, X.selSlots(X.getState().lines[0], 0, 0, 4)), '甲乙丙',
    'Ctrl+C 取到的是本行那串字（粘回一行顺序不会乱）');
eq(X.groupLyric(0, [0, 1, 2, 3, 4]), '甲一乙二丙',
    '（对照）照平坦区间取字 = 两行交错 —— 这就是改前的行为');

// 批量填词：副本行整行对齐（症状 = 副本行残留上一次填的旧值，永远盖不掉）
let mm = build();
mm.filled[0][4] = '旧'; mm.filled[0][5] = '旧'; mm.filled[0][6] = '旧';
X.setState(mm);
eq(X.applyByLine(['甲乙丙'], true), 0, '按句模式：3 个字全落在吃字那一行');
eq(row(X.getState(), 0, 0), '甲乙丙-', '吃字行（组 0）拿到新词');
eq(row(X.getState(), 0, 1), '甲乙丙-', '副本行（组 1）整行对齐 —— 旧值被盖掉（改前是「旧旧旧-」）');
// 整行对齐的另一半：源空着的格，副本行的残留也要清掉
mm = build();
mm.filled[0][4] = '旧';
X.setState(mm);
X.mirrorCopies(0);
eq(row(X.getState(), 0, 1), '···-', '源（组 0）空着 → 副本行的残留跟着清掉');
// 两条轨各唱各的（音符错开、互不包含）→ 不是副本，一个字都不许动
const d2 = buildDuet();
d2.filled[0][0] = '甲';
d2.filled[0][3] = '丁';
X.setState(d2);
X.mirrorCopies(0);
eq([row(X.getState(), 0, 0), row(X.getState(), 0, 1)], ['甲··', '丁··'],
    '各唱各的两组：整行对齐不动它们，各自留着自己的字');

// 清格：已填的孪生格原本「清不掉」——只清自己这一格，紧跟着的联动从副本行抄回来
mm = build();
mm.filled[0][0] = '甲'; mm.filled[0][4] = '甲';
X.setState(mm);
X.getState().filled[0][0] = '';
X.syncVoiceCopies(0);
eq([X.getState().filled[0][0], X.getState().filled[0][4]], ['甲', '甲'],
    '（对照）只清自己这一格 → 联动从副本行抄回来（改前的整段清空就是这么白按的）');
// 修复后：清一格连着副本声部同音符那一格一起清，联动便无事可做
mm = build();
mm.filled[0][0] = '甲'; mm.filled[0][4] = '甲';
mm.filled[0][1] = '乙'; mm.filled[0][5] = '乙';
X.setState(mm);
eq(X.clearCellEverywhere(0, 0), [4], '清一格：副本行同音符那一格跟着清（返回它的下标）');
X.syncVoiceCopies(0);
eq([X.getState().filled[0][0], X.getState().filled[0][4]], ['', ''],
    '两行都空 → 联动不会再抄回来（这才清得掉）');
eq([X.getState().filled[0][1], X.getState().filled[0][5]], ['乙', '乙'], '旁边那格不受影响');
// 各唱各的两组（相位错开、没有孪生格）→ 只清自己这一格，另一行的字不许动
const d3 = buildDuet();
d3.filled[0][0] = '甲'; d3.filled[0][3] = '丁';
X.setState(d3);
eq(X.clearCellEverywhere(0, 0), [], '没有孪生格：只清自己这一格（返回空）');
eq([X.getState().filled[0][0], X.getState().filled[0][3]], ['', '丁'], '另一行自己的字没被碰');

console.log(failed ? '\nFAILED: ' + failed : '\nALL OK');
process.exit(failed ? 1 : 0);
