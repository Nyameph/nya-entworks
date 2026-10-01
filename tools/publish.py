#!/usr/bin/env python
"""把当前提交发布成线上分支：**把只该留在本机的文件排除掉**。

    python tools/publish.py --list       # 只看会排除哪些、还剩多少个文件
    python tools/publish.py --dry-run    # 生成提交但不推（打印提交号）
    python tools/publish.py              # 生成并推送
    python tools/publish.py --selftest   # 不碰仓库：排除判定与泄露扫描的回归用例

## 为什么要有它

`docs/` 与 `CLAUDE.md` 是**本机笔记**（里面写着本机的盘符布局、真实曲目名、归档标签的
来龙去脉），`data/` 下两份 CSV 是**本机归档标签的迁移映射** —— 都不适合随公开仓库推上去。

git 没有「推这条分支、但排除某些文件」的开关：推送的单位是**提交**，一个提交里有什么文件
就公开什么，**历史里删掉的文件照样读得到**（`git show <旧提交>:docs/xxx`）。所以做法是反转
过来 —— 本地在一条含全部内容的提交上开发，线上那条分支的历史**由本脚本从零生成、从不合并
回去**：

    master（全量，**永不推送**） ──publish.py──> main（过滤后的快照，推给 origin）

## 两条分支的关系（别名/口径别改）

* 线上每个提交 = 当时 `HEAD` 的整棵树去掉 `EXCLUDE`，父提交接在线上上一次快照后面。
* 本地**不建** `main` 分支，推送用 `<提交>:refs/heads/main` 直接送 —— 免得手滑
  `git checkout main` 把 `docs/` 与 `CLAUDE.md` 从磁盘上抹掉（那棵树里没有它们）。
* `master` 不要设 upstream：`git push` 于是会直接拒绝（`has no upstream branch`），
  而不是顺手把全量分支推上去。发布入口只有这一个脚本。

## 三道闸门（任一不过就拒绝，绝不静默放行）

1. **工作区干净** —— 已跟踪文件没有未提交的改动。发布的是提交，不是磁盘上的半成品；
   拿 HEAD 的树去发、却让你以为发的是眼前这份，是最坏的一种错。
2. **过滤后的树里一个排除项都不剩**。
3. **过滤后的树里扫不出本机标识** —— 本机用户名、git 配置里的邮箱、`C:\\Users\\<真名>`
   形状的绝对路径。图案是**运行时算出来的**，不写死在脚本里（写死就等于把要藏的东西
   抄进公开文件）。

## 与 `release.py` 的分工

* `release.py` 出的是**给别人的成品**（exe + 空库 + 清理过的配置）。
* `publish.py` 出的是**给你自己看的源码仓库**。两者互不相干，可以各自单独跑。
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

# 控制台的代码页可能是 936（GBK）：不转 UTF-8 的话，下面那对 ✓ / ✗ 会直接
# UnicodeEncodeError 把脚本打崩（`启动.cmd` 必须保持纯 ASCII 是同一个坑）。
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# ── 排除清单 ──────────────────────────────────────────────────────────────────
# 目录写目录名（整棵排除），文件写仓库相对路径。改动这里之前先想清楚：
# 少一项就是一次静默泄露，多一项可能让公开版**跑不起来**（比如 tools/mysql.py 要写
# docs/sql/待执行/，好在它是 mkdir(parents=True, exist_ok=True)，缺目录不会炸）。
EXCLUDE_DIRS = ("docs",)
EXCLUDE_FILES = (
    "CLAUDE.md",
    "data/ehentai-tags.csv",
    "data/manga-tag-migration-map.csv",
)

DEFAULT_REMOTE = "origin"
DEFAULT_BRANCH = "main"

# 泄露扫描跳过的：二进制（解不出 UTF-8 本来就跳）、以及超过这个体积的
MAX_SCAN_BYTES = 2 * 1024 * 1024

# 扫描时豁免本文件自己：它的任务就是"定义要扫的图案"，于是**图案字面量**与
# **自测样例**必然长得像要抓的东西（`re.compile(r"/home/(?!<)[^/\s]+")` 会被自己那条
# `/home/…` 命中；`--selftest` 里那句 `Path.of('C:\Users\张三\t.svp')` 会被
# `C:\Users\<真名>` 命中）。豁免的代价是：**本文件里不许出现真的本机路径** ——
# 要举例子就用假名（张三 / 某用户），真值一律在运行时从环境变量与 git config 取。
SELF = Path(__file__).resolve()


# ── 纯函数（--selftest 只测这几件）────────────────────────────────────────────

def is_excluded(rel: str) -> bool:
    """这条仓库相对路径（正斜杠）该不该排除。"""
    rel = rel.replace("\\", "/")
    if rel in EXCLUDE_FILES:
        return True
    return any(rel == d or rel.startswith(d + "/") for d in EXCLUDE_DIRS)


def surviving(paths) -> list:
    """过滤后的路径列表，保持原顺序。"""
    return [p for p in paths if not is_excluded(p)]


def leak_patterns(username: str = "", email: str = "") -> list:
    """要扫的本机标识。**运行时算出来**，脚本里不写死任何一个真值。"""
    pats = [
        # C:\Users\<真名>；占位符 C:\Users\<用户名> 放行
        (re.compile(r"[A-Za-z]:\\Users\\(?!<)"), "本机用户目录（写成 C:\\Users\\<用户名>）"),
        (re.compile(r"/home/(?!<)[^/\s]+"), "本机用户目录（Linux 形状）"),
    ]
    if username:
        pats.append((re.compile(r"(?<![A-Za-z0-9_])" + re.escape(username) + r"(?![A-Za-z0-9_])"),
                     "本机用户名"))
    if email:
        pats.append((re.compile(re.escape(email)), "git 配置里的邮箱"))
    return pats


def scan_text(text: str, patterns) -> list:
    """返回 [(行号, 图案说明, 该行摘要)]。"""
    hits = []
    for no, line in enumerate(text.splitlines(), 1):
        for pat, why in patterns:
            if pat.search(line):
                hits.append((no, why, line.strip()[:120]))
    return hits


# ── git 包装 ─────────────────────────────────────────────────────────────────

def run_git(*args, env=None):
    return subprocess.run(["git", *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", env=env)


def git(*args, env=None, check=True):
    """跑一条 git，返回 stdout（去尾换行）；check=False 时失败返回空串。"""
    r = run_git(*args, env=env)
    if check and r.returncode != 0:
        sys.exit(f"git {' '.join(args)} 失败：\n{r.stderr.strip()}")
    return r.stdout.strip() if r.returncode == 0 else ""


def build_filtered_tree() -> str:
    """在**临时索引**里做过滤，写出一棵树；返回 tree sha。不动工作区、不动真索引。"""
    fd, tmp = tempfile.mkstemp(prefix="publish-index-")
    os.close(fd)
    os.unlink(tmp)                       # git 要自己建这个文件
    env = dict(os.environ, GIT_INDEX_FILE=tmp)
    try:
        git("read-tree", "HEAD^{tree}", env=env)
        drop = [*EXCLUDE_DIRS, *EXCLUDE_FILES]
        git("rm", "--cached", "-r", "-q", "--ignore-unmatch", *drop, env=env)
        return git("write-tree", env=env)
    finally:
        for suffix in ("", ".lock"):
            Path(tmp + suffix).unlink(missing_ok=True)


def tree_files(tree: str) -> list:
    """树里的文件路径（仓库相对）。

    **必须带 -z**：git 默认把非 ASCII 路径转义成 `"docs/\\346\\241\\214..."`（带引号 +
    八进制），于是 `is_excluded` 的 `startswith("docs/")` 全部落空 —— 中文名的文档会被
    静默漏掉，而这正是最坏的一种错（闸门看着通过、东西照样推上去）。-z 是 NUL 分隔、
    原样输出。
    """
    out = run_git("ls-tree", "-r", "-z", "--name-only", tree).stdout
    return [p for p in out.split("\0") if p]


# ── 主流程 ───────────────────────────────────────────────────────────────────

def cmd_list():
    tree = build_filtered_tree()
    kept = tree_files(tree)
    dropped = [p for p in tree_files(git("rev-parse", "HEAD^{tree}")) if is_excluded(p)]
    print(f"排除 {len(dropped)} 个：")
    for p in dropped:
        print("  - " + p)
    print(f"\n保留 {len(kept)} 个文件")


def cmd_selftest() -> int:
    ok = True

    def check(cond, what):
        nonlocal ok
        print(("  ✓ " if cond else "  ✗ ") + what)
        ok = ok and bool(cond)

    print("排除判定：")
    check(is_excluded("docs/设计概述.md"), "docs/ 下任何文件都排除")
    check(is_excluded("CLAUDE.md"), "CLAUDE.md 排除")
    check(is_excluded("data/ehentai-tags.csv"), "标签词典排除")
    check(not is_excluded("docsx/a.md"), "同前缀但不是它：不排除")
    check(not is_excluded("src/main/resources/static/guide/song.md"),
          "**使用说明页的正文在 static/guide/ 下，是交付物，不排除**")
    check(not is_excluded("data/merge_panda.py"), "重建脚本本身留着（它是给别人生成数据的）")

    print("过滤：")
    check(surviving(["a.py", "docs/x", "CLAUDE.md", "b/c"]) == ["a.py", "b/c"], "顺序保持、只去该去的")

    print("泄露扫描：")
    pats = leak_patterns("张三", "me@example.com")
    check(scan_text(r"Path.of('C:\Users\张三\t.svp')", pats), "抓到 C:\\Users\\真名")
    check(not scan_text(r"C:\Users\<用户名>\AppData", pats), "占位符放行")
    check(scan_text("started by 张三 in C:", pats), "抓到本机用户名")
    check(scan_text("联系 me@example.com", pats), "抓到 git 邮箱")
    check(not scan_text("歌名_某歌手.mp3", pats), "普通内容不误报")
    check(not scan_text("C:\\Users", pats), "落单的 C:\\Users 不误报")

    print("\n" + ("全绿" if ok else "有失败"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="把当前提交发布成线上分支（排除本机文件）")
    ap.add_argument("--list", action="store_true", help="只列出排除项与保留数，不生成提交")
    ap.add_argument("--dry-run", action="store_true", help="生成提交但不推")
    ap.add_argument("--remote", default=DEFAULT_REMOTE)
    ap.add_argument("--branch", default=DEFAULT_BRANCH)
    ap.add_argument("--message", default=None, help="提交说明（默认沿用 HEAD 的那一行）")
    ap.add_argument("--selftest", action="store_true", help="不碰仓库，跑纯函数用例")
    args = ap.parse_args()

    if args.selftest:
        return cmd_selftest()
    if args.list:
        cmd_list()
        return 0

    # 必须在仓库根跑：树里的路径是仓库相对的，泄露扫描要顺着它们读文件
    root = Path(git("rev-parse", "--show-toplevel"))
    if Path.cwd() != root:
        sys.exit(f"在工作目录之外跑：请在仓库根（{root}）执行。")

    # 闸门 1：工作区干净（只看已跟踪文件；未跟踪的一律不在提交里，与本次发布无关）
    if run_git("diff", "--quiet").returncode != 0 \
            or run_git("diff", "--cached", "--quiet").returncode != 0:
        sys.exit("工作区有未提交的改动。发布的是**提交**，先提交再发（git status 看一眼）。")

    tree = build_filtered_tree()

    # 闸门 2：排除项一个都不许剩
    left = [p for p in tree_files(tree) if is_excluded(p)]
    if left:
        sys.exit("拒绝发布：过滤后仍含排除项 ——\n  " + "\n  ".join(left))

    # 闸门 3：扫本机标识
    patterns = leak_patterns(
        username=os.environ.get("USERNAME") or os.environ.get("USER") or "",
        email=git("config", "user.email", check=False),
    )
    leaks = []
    for rel in tree_files(tree):
        p = Path(rel)
        if p.resolve() == SELF:
            continue                      # 详见 SELF 的注释
        try:
            if p.stat().st_size > MAX_SCAN_BYTES:
                continue
            text = p.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue                      # 二进制 / 读不到：跳过
        for no, why, line in scan_text(text, patterns):
            leaks.append(f"{rel}:{no}  [{why}]  {line}")
    if leaks:
        sys.exit(f"拒绝发布：公开集里还有 {len(leaks)} 处本机标识 ——\n  "
                 + "\n  ".join(leaks[:20])
                 + ("\n  …（还有更多）" if len(leaks) > 20 else ""))

    # 接到线上上一次快照后面；首次发布则是根提交
    parent = git("rev-parse", "-q", "--verify", f"refs/remotes/{args.remote}/{args.branch}",
                 check=False)
    if not parent:
        git("fetch", "--quiet", args.remote, args.branch, check=False)
        parent = git("rev-parse", "-q", "--verify", f"refs/remotes/{args.remote}/{args.branch}",
                     check=False)
    message = args.message or git("log", "-1", "--format=%s")
    cmd = ["commit-tree", tree]
    if parent:
        cmd += ["-p", parent]
    cmd += ["-m", message]
    commit = git(*cmd)

    files = len(tree_files(tree))
    print(f"过滤后 {files} 个文件，提交 {commit[:12]}"
          + (f"（接在 {parent[:12]} 后）" if parent else "（首个发布）"))
    if args.dry_run:
        print("--dry-run：没有推送。")
        return 0

    git("push", "-f", args.remote, f"{commit}:refs/heads/{args.branch}")
    print(f"已推送：{args.remote}/{args.branch}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
