#!/usr/bin/env python
"""版本号 bump：`pom.xml` 是唯一真源，`desktop/` 下**两份**跟着它同步成同一个值
（`package.json` 与 `package-lock.json`）。

## 版本号怎么涨

- **第三组（patch）＝ 每次提交自动 +1**。不看提交类型 —— `feat` / `fix` / `chore`
  一视同仁。由 git 的 `post-commit` 钩子调 `--auto` 做，它会额外生成一个
  `chore: bump version to X` 提交（版本号的变化因此永远是一次独立、可回退的提交）。
- **第二组（minor）＝ 手动**，`python tools/bump_version.py minor`。这是**发版决策**：
  一批小改动攒够了、要发一个较大版本时人来说了算。**刻意不做成「`feat` 就 +1」** ——
  「这个提交是不是新功能」与「该不该发较大版本」是两件事，用提交类型去猜必然猜错
  （日常大量改动都是 feat 语义，但不该每次加一个小版本号）。

两组都不会碰第一组（major）。

## 发版时顺带留两份存档

`minor` 会在改版本号**之前**做两件事，**两份都落在 `.local-backup/` 下**
（已 gitignore，与 `mysql.py` 的 `.local-backup/db/` 并列）：

1. **库结构** → `.local-backup/schema/<新版本>.sql`（只有建表语句、不含任何数据）。
   **失败即中止，版本号一个字不动**（与 `tools/mysql.py` 里「dump 失败即中止」
   同一口径：没有备份就不发版）。
2. **本机覆盖层** `config/nya-entworks.yaml` → `.local-backup/config/nya-entworks-<新版本>.yaml`。
   文件**不在时只提示、不中止** —— 新机器上本来就没有这一份。
   复制真失败（磁盘满、没权限）才中止。

按版本命名是为了「两个版本的库差了什么」—— 拿两次发版的快照直接比即可。
**两份都不进仓库**：覆盖层那份里有 cookie 明文，库结构那份是「我这台机器当时长什么样」。

`patch` 两件都不做 —— 它每次提交都会跑。

## 用法

    python tools/bump_version.py patch        # 1.0.1 -> 1.0.2（只改文件，不提交）
    python tools/bump_version.py minor        # 1.0.1 -> 1.1.0 + 库结构留档（只改文件，不提交）
    python tools/bump_version.py --auto       # 供 post-commit 钩子调用，见下
    python tools/bump_version.py --selftest   # 改完正则跑一遍：不碰盘、不连 git

手动跑完自己提交即可，message 建议 `chore: bump version to X.Y.Z` —— 钩子认得这行，
不会再叠一次。

## 装钩子（每台机器 / 每次新 clone 各做一次）

    git config core.hooksPath .githooks

`.git/config` 不进版本库，所以这条命令**不会**跟着仓库走。忘了装的症状是
「版本号一直不动」，别的都正常（提交照做）。`tools/README.md` 有同一份说明。

## `--auto` 什么时候跳过

按顺序判，命中任一即**静默不动**（打印一行、退出码 0，绝不让提交失败）：

1. 提交信息里有 `bump version`（不区分大小写）—— 防递归：钩子自己生成的那个提交
   会再触发一次钩子，靠这条终止。
2. 提交信息以 `Merge` / `Revert` 开头 —— 不是普通提交，不该跟着涨版本。
3. **本次提交本身已经改过 `pom.xml` 的项目版本行** —— 手动 bump 的那次提交、
   以及首次引入版本号的初始化提交都属这类；不跳过就会「改一次涨两次」。
4. 取不到上一个提交（仓库的第一个提交）—— 没有可比较的旧版本号。
"""
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
POM = os.path.join(ROOT, "pom.xml")
PKG = os.path.join(ROOT, "desktop", "package.json")
LOCK = os.path.join(ROOT, "desktop", "package-lock.json")
MYSQL_TOOL = os.path.join(ROOT, "tools", "mysql.py")
LOCAL_CONFIG_REL = os.path.join("config", "nya-entworks.yaml")
# 两份发版存档都落在 `.local-backup/` 下(已 gitignore),与 mysql.py 的 `.local-backup/db/`
# 并列 —— 库结构那份**刻意不进仓库**:它是「我这台机器当时长什么样」的快照,
# 不是需要随代码走的产物(要 diff 两个版本的库,拿两次发版的快照比即可)。
LOCAL_BACKUP = os.path.join(".local-backup")
SCHEMA_BACKUP_REL = os.path.join(LOCAL_BACKUP, "schema")
LOCAL_CONFIG_BACKUP_REL = os.path.join(LOCAL_BACKUP, "config")

# Windows 的控制台代码页多半不是 UTF-8（本机是 936）。两个流都钉成 UTF-8 + 替换字符，
# 与 tools/mysql.py 的 main() 同一口径 —— 少了 encoding 那一半，中文会按 GBK 编出去，
# 重定向 / 管道里（含钩子的输出）就成乱码。errors="replace" 那半是不能省的：
# 编不出来的字符降级即可，**绝不能让 print 抛 UnicodeEncodeError** ——
# 钩子里抛异常就是「每次提交都跟一段 traceback」（同「启动.cmd 必须纯 ASCII」那笔老账）。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

# 项目版本那一行：用 artifactId 锚定，不用「文件里第一个 <version>」——
# 上面父 POM 的 spring-boot-starter-parent 也有一个 <version>，按第一个匹配会改错人。
#
# `(?:\s|<!--.*?-->)*` 那一段是必需的：两者之间**可以夹注释**（本仓库的 pom 就夹了一条），
# 写死成 `\s*` 会让整份 pom 匹配不上、脚本直接报错退出 —— 跑 `--selftest` 能逮住这类回归。
# re.S 是为了让注释里的 `.` 能跨行。
POM_VERSION_RE = re.compile(
    r"(<artifactId>\s*nya-entworks\s*</artifactId>"
    r"(?:\s|<!--.*?-->)*"
    r"<version>\s*)([^<\s]+)(\s*</version>)",
    re.S,
)
PKG_VERSION_RE = re.compile(r'("version"\s*:\s*")([^"]*)(")')
# 包名那一行：lockfile 里那两处版本号要靠它锚定（见 lock_version_re）
PKG_NAME_RE = re.compile(r'"name"\s*:\s*"([^"]*)"')
SEMVER_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")

# lockfile 里的版本号是**第三处**，而且有**两行**：顶层一个、`packages[""]` 里再一个。
# 它比 package.json 难改 —— 全文三百多个 `"version":`（每个依赖各一个），
# 按「前两处」去数，npm 哪天换了排版就会**静默改到某个依赖头上**；那种错改完
# 还要等下一次 `npm install` 把它拉回去才看得见，症状是「有时好有时坏」，最难查。
# 所以用**包名**锚定：只有那两行带自己的 name，依赖条目不写 name。
# 名字从 package.json 现读 —— 写死 `nya-entworks-desktop` 就是 `FileOpCoverageTest`
# 那个坑的翻版（`docs/已完成/公开仓库发布设计.md` §9.3：写死的路径改名后扫到不存在的目录）。
LOCK_VERSION_COUNT = 2


def lock_version_re(name):
    """锚定 lockfile 里那两行自带包名的版本号；依赖自己的 version 一个都碰不到。"""
    return re.compile(
        r'("name"\s*:\s*"' + re.escape(name) + r'"\s*,\s*"version"\s*:\s*")([^"]*)(")')

USAGE = "用法：python tools/bump_version.py patch|minor|--auto|--selftest"

LEVELS = ("patch", "minor")


def read(path):
    # newline='' 保留文件本来的行尾（这份仓库是 CRLF），原样读进来、原样写回去
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def parse(text, regex, path):
    m = regex.search(text)
    if not m:
        raise SystemExit(f"在 {path} 里找不到版本号那一行（正则 {regex.pattern}）")
    v = m.group(2)
    if not SEMVER_RE.match(v):
        raise SystemExit(
            f"{path} 里的版本号是 {v!r}，不是 MAJOR.MINOR.PATCH 三段纯数字。\n"
            "带 -SNAPSHOT 之类的后缀时本脚本拒绝改（与其瞎猜后缀留不留，不如让人先定）。"
        )
    return [int(x) for x in SEMVER_RE.match(v).groups()]


def current_version():
    return parse(read(POM), POM_VERSION_RE, "pom.xml")


def format_version(parts):
    return ".".join(str(x) for x in parts)


def bumped(parts, level):
    major, minor, patch = parts
    if level == "minor":
        return [major, minor + 1, 0]
    return [major, minor, patch + 1]


def apply(new_version):
    """把新版本号写进三份文件（pom.xml / package.json / package-lock.json），返回 (旧, 新)。

    三份都先读出来、**全部校验通过再落盘** —— 写到一半才发现 lockfile 对不上，
    会留下「pom 改了、package.json 没改」的半截状态。`auto()` 是钩子调的，
    那时抛异常只是提交输出里的一段 stderr，没人会去逐份核对。
    """
    pom_text = read(POM)
    old_version = format_version(parse(pom_text, POM_VERSION_RE, "pom.xml"))

    pkg_text = read(PKG)
    name_match = PKG_NAME_RE.search(pkg_text)
    if not name_match:
        raise SystemExit(f"在 {PKG} 里找不到 name 字段（lockfile 那边要靠它锚定）")
    if not PKG_VERSION_RE.search(pkg_text):
        raise SystemExit(f"在 {PKG} 里找不到 version 字段")

    lock_text = read(LOCK)
    lock_re = lock_version_re(name_match.group(1))
    found = len(lock_re.findall(lock_text))
    if found != LOCK_VERSION_COUNT:
        raise SystemExit(
            f"在 {LOCK} 里用包名 {name_match.group(1)!r} 锚定到 {found} 处版本号，"
            f"期望 {LOCK_VERSION_COUNT} 处。\n"
            "npm 换了 lockfile 排版时要来这里对齐，**别默默跳过** —— 跳过就是"
            "「三处只同步了两处」，而它看起来一切正常。"
        )

    write(POM, POM_VERSION_RE.sub(lambda m: m.group(1) + new_version + m.group(3), pom_text, count=1))
    write(PKG, PKG_VERSION_RE.sub(lambda m: m.group(1) + new_version + m.group(3), pkg_text, count=1))
    write(LOCK, lock_re.sub(lambda m: m.group(1) + new_version + m.group(3), lock_text,
                            count=LOCK_VERSION_COUNT))
    return old_version, new_version


def dump_schema_for_release(version):
    """发版时把当前库结构留一份到 `.local-backup/schema/<版本>.sql`（不进 git）。

    转调 `tools/mysql.py --dump-schema`，**不在这里另写一份连库逻辑** ——
    凭据怎么读、mysqldump 在哪、密码怎么传，那些知识只该有一份（就是 mysql.py）。

    失败即抛，且**在改版本号之前调用**：没有备份就不发版，与 mysql.py 里
    「dump 失败即中止」同一口径。抛的时候版本号还没动，人可以从容处理再重跑。

    与下面那份本机覆盖层不同，**这里没有「文件不在」这条退路** ——
    库连不上就是真出错，不像覆盖层在新机器上本来就不存在。
    """
    rel = os.path.join(SCHEMA_BACKUP_REL, version + ".sql")
    out = os.path.join(ROOT, rel)
    if os.path.exists(out):
        print(f"注意：{rel} 已存在，本次会覆盖它")
    r = subprocess.run(
        [sys.executable, MYSQL_TOOL, "--dump-schema", out],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        raise SystemExit(
            "库结构备份失败，版本号未改动（没有备份就不发版）。\n"
            + (r.stdout or "") + (r.stderr or "")
            + "\n常见原因：MySQL 没起、mysqldump 不在 MYSQL_BIN_DIR 下。\n"
            "处理好再重跑一次即可。"
        )
    print(f"库结构已留档 → {rel}")


def backup_local_config_for_release(version):
    """发版时把本机覆盖层 `config/nya-entworks.yaml` 另存一份到 `.local-backup/config/`。

    **刻意不进仓库、不进 `docs/`**：那一份里现在有 `manga.eh-scan.cookie` 明文
    （配置页设计 §14 那条口径 a 把 cookie 收进了页面），进 git 就是把密钥写进历史 ——
    `.local-backup/` 本身是 gitignore 的，与 `mysql.py` 的 `.local-backup/db/` 同一个去向。

    为什么值得备：这个文件既是 gitignored 的**本机状态**，又是**模块裁剪的介质** ——
    丢了它三个模块一起从页面上消失，而它不在任何备份里（CLAUDE.md「模块的『有』与『没有』」）。
    库结构那份回答「结构改了什么」，这份回答「我这台机器当时是怎么配的」。

    **文件不在时不中止发版**，只提示一句：新 clone / 另一台机器上它本来就没有，
    拿「没有本机覆盖层」去挡发版是把两件事混为一谈。真正复制失败（磁盘满、没权限）
    才抛 —— 与库结构那份同一口径：留档没做成就不发版。
    """
    src = os.path.join(ROOT, LOCAL_CONFIG_REL)
    if not os.path.exists(src):
        print(f"提示：{LOCAL_CONFIG_REL} 不在，跳过本机配置留档（新机器上本来就没有这一份）")
        return
    rel = os.path.join(LOCAL_CONFIG_BACKUP_REL, f"nya-entworks-{version}.yaml")
    out = os.path.join(ROOT, rel)
    try:
        os.makedirs(os.path.dirname(out), exist_ok=True)
        # copy2 保留时间戳；不经过文本模式，原样搬字节，行尾一个都不动
        shutil.copy2(src, out)
    except OSError as e:
        raise SystemExit(
            f"本机配置留档失败（{LOCAL_CONFIG_REL} → {rel}）：{e}\n"
            "版本号未改动（与库结构那份同一口径：留档没做成就不发版）。"
        )
    print(f"本机配置已另存 → {rel}")


def git(*args):
    """跑一条 git 命令，返回 CompletedProcess（不抛异常，调用方看 returncode）。"""
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    )


def head_message():
    r = git("log", "-1", "--format=%s")
    return r.stdout.strip() if r.returncode == 0 else ""


def version_at_previous_commit():
    """上一个提交里 pom.xml 的项目版本号；取不到（首个提交 / pom 还不存在）返回 None。"""
    r = git("show", "HEAD~1:pom.xml")
    if r.returncode != 0:
        return None
    m = POM_VERSION_RE.search(r.stdout)
    return m.group(2) if m else None


def auto():
    msg = head_message()

    # 1 防递归：钩子自己生成的 bump 提交会再触发一次钩子
    if "bump version" in msg.lower():
        return 0
    # 2 合并 / 回滚不是普通提交
    if msg.startswith(("Merge", "Revert")):
        return 0
    # 3 本次提交自己就动过版本行（手动 bump、初始化、amend）—— 再叠一次就成了「改一次涨两次」
    prev = version_at_previous_commit()
    if prev is None or prev != format_version(current_version()):
        return 0

    parts = current_version()
    new_version = format_version(bumped(parts, "patch"))
    old_version, _ = apply(new_version)

    # 三份都要进这次提交 —— 少一份，那个文件就永远脏在工作区里，
    # 被下一次不相干的提交顺手带进来（diff 看着像「莫名改了一行版本号」）
    paths = ["pom.xml", "desktop/package.json", "desktop/package-lock.json"]
    git("add", "--", *paths)
    # 带 pathspec 的 commit：只提交这几个文件的工作区内容，**不带上**索引里别的暂存改动
    r = git("commit", "-m", f"chore: bump version to {new_version}", "--", *paths)
    if r.returncode != 0:
        # 版本文件已经改在工作区里了，但提交没成 —— 说出来，别让它躺在那儿没人知道
        sys.stderr.write("版本号已改为 " + new_version + "，但自动提交失败：\n" + r.stderr)
        return 0
    print(f"版本号 {old_version} -> {new_version}")
    return 0


def selftest():
    """正则与算数的回归用例（`python tools/bump_version.py --selftest`，不碰盘也不碰 git）。

    存在的理由：这套东西的失败大多是**静默**的 —— 正则在真 pom 上匹配不上时，
    症状是「每次提交钩子都报一句错、版本号一直不动」，而钩子的 stderr 很容易被
    提交输出淹没。用例把「哪些形态必须认出来」钉死，改正则前后各跑一次。
    """
    cases = [
        ("artifactId 与 version 相邻",
         "<artifactId>nya-entworks</artifactId>\n    <version>1.0.1</version>", "1.0.1"),
        ("中间夹注释（本仓库的 pom 就是这样）",
         "<artifactId>nya-entworks</artifactId>\n    <!-- 注释 -->\n    <version>1.0.1</version>",
         "1.0.1"),
        ("中间夹多行注释与空行",
         "<artifactId>nya-entworks</artifactId>\n\n<!-- 一\n  二 -->\n\n<version>2.3.4</version>",
         "2.3.4"),
        ("父 POM 的 version 在前，不该被选中",
         "<artifactId>spring-boot-starter-parent</artifactId>\n<version>4.1.0</version>\n"
         "<artifactId>nya-entworks</artifactId>\n<version>1.0.1</version>", "1.0.1"),
    ]
    bad = 0
    for name, text, want in cases:
        m = POM_VERSION_RE.search(text)
        got = m.group(2) if m else None
        if got != want:
            print(f"  FAIL {name}：期望 {want!r}，实际 {got!r}")
            bad += 1

    for name, text in [
        ("带 -SNAPSHOT 后缀要拒绝",
         "<artifactId>nya-entworks</artifactId>\n<version>1.0.1-SNAPSHOT</version>"),
        ("artifactId 对不上时要拒绝（宁可报错，别改错人）",
         "<artifactId>别人的项目</artifactId>\n<version>1.0.1</version>"),
    ]:
        try:
            parse(text, POM_VERSION_RE, "样本")
        except SystemExit:
            continue
        print(f"  FAIL {name}：本该报错却读成功了")
        bad += 1

    for name, level, parts, want in [
        ("patch +1", "patch", [1, 0, 1], [1, 0, 2]),
        ("patch 进位不关 minor 的事", "patch", [1, 0, 9], [1, 0, 10]),
        ("minor +1 并把 patch 归 0", "minor", [1, 0, 1], [1, 1, 0]),
    ]:
        got = bumped(parts, level)
        if got != want:
            print(f"  FAIL {name}：期望 {want}，实际 {got}")
            bad += 1

    # lockfile：那两行自带包名的要认全，**依赖自己的 version 一个都不能碰**。
    # 样本里刻意放一个 `5.0.0-alpha.10`（真 lock 里就有一个带 -alpha 的依赖），
    # 它同时钉住两件事：锚定不会漏进依赖条目，以及版本号里的非数字后缀照收不误。
    lock_text = (
        '{\n'
        '  "name": "nya-entworks-desktop",\n'
        '  "version": "1.1.0",\n'
        '  "lockfileVersion": 3,\n'
        '  "packages": {\n'
        '    "": {\n'
        '      "name": "nya-entworks-desktop",\n'
        '      "version": "1.1.0"\n'
        '    },\n'
        '    "node_modules/electron": {\n'
        '      "version": "44.4.0"\n'
        '    },\n'
        '    "node_modules/app-builder-bin": {\n'
        '      "version": "5.0.0-alpha.10"\n'
        '    }\n'
        '  }\n'
        '}\n'
    )
    lock_re = lock_version_re("nya-entworks-desktop")
    if len(lock_re.findall(lock_text)) != LOCK_VERSION_COUNT:
        print("  FAIL lockfile 的两处版本号没被认全（或认多了）")
        bad += 1
    swapped = lock_re.sub(lambda m: m.group(1) + "9.9.9" + m.group(3), lock_text,
                          count=LOCK_VERSION_COUNT)
    if (swapped.count('"9.9.9"') != 2
            or '"version": "44.4.0"' not in swapped
            or '"version": "5.0.0-alpha.10"' not in swapped):
        print("  FAIL 改 lockfile 时动到了依赖自己的 version")
        bad += 1

    if bad:
        print(f"selftest：{bad} 条失败")
        return 1
    # 条数：正例 len(cases) + 该拒绝的 2 + bumped 的 3 + lockfile 的 2
    print(f"selftest：{len(cases) + 7} 条全过")
    return 0


def main(argv):
    if argv == ["--selftest"]:
        return selftest()
    if argv == ["--auto"]:
        return auto()
    if len(argv) == 1 and argv[0] in LEVELS:
        level = argv[0]
        new_version = format_version(bumped(current_version(), level))
        if level == "minor":
            # 先备份、后改号：备份失败时版本号还一个字没动，人可以从容处理再重跑
            dump_schema_for_release(new_version)
            backup_local_config_for_release(new_version)
        old_version, _ = apply(new_version)
        print(f"版本号 {old_version} -> {new_version}（只改了文件，自己提交）")
        return 0
    sys.stderr.write(USAGE + "\n")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
