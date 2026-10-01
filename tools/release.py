#!/usr/bin/env python
"""一键生成可发布版：跑完得到一个**可以直接打包发出去**的文件夹。

    python tools/release.py                 # 全流程
    python tools/release.py --emit-sqlite-ddl   # 只转出建表脚本（开发时用）
    python tools/release.py --no-build      # 复用 target 里现成的 jar
    python tools/release.py --jar PATH      # 不构建、也不用 target 里那个，拿这一个去打包
    python tools/release.py --no-shell      # 只落到文件夹，不做 jlink / electron-builder
    python tools/release.py --selftest      # 不碰盘：DDL 转换与结构 diff 的回归用例

产物落在 `C:\\Code\\呜啊娱乐工坊\\`（可 `--release-dir` 改）：

    当前版本1.1.0                      ← 一个空文件，名字就是版本号
    呜啊娱乐工坊-1.1.0.exe                 ← portable，双击即用、免安装、免 UAC
    data/nya_entworks.sqlite ← 按最新结构建好的空库（首启用的也是它）
    config/nya-entworks.yaml           ← 清理过的覆盖层（只有歌曲 + 喊麦）
    config/application-secret.yaml     ← 空值版密钥文件（绝不含发版人自己的密钥）
    config/application-release.yaml    ← 把数据源换成 SQLite 的那一份
    结构更新/1.0.0->1.1.0结构更新.md    ← 结构或配置**变了**才有，没变不生成

## 为什么是这样

* **换库只在打包这条线**：发版人自己开发仍然用 MySQL，代码一行不受影响。
  机制是一个 Spring profile（`release`）+ 外部的 `config/application-release.yaml`，
  `application.yaml` 一个字不改（见 docs/已完成/桌面化收尾实施计划.md 阶段 6）。
* **建表脚本只有一份真源**：`src/main/resources/db/nya_entworks.sql`（MySQL 那份），
  本脚本按规则转出 SQLite 版 —— 不手写第二份，否则两边一定会漂。转出来的那份
  `db/nya_entworks.sqlite.sql` **会被 git 跟踪**（便于 review 与 diff 出改动）。
* **裁剪靠覆盖层**：交付的 `nya-entworks.yaml` 里只写歌曲与喊麦的 `enabled: true`，
  漫画整段不写 —— 「键在不在 = 模块在不在」这条口径见 CLAUDE.md「模块的『有』与『没有』」。
  `application.yaml` 里本来就没有 `nya-entworks:` 段，所以外侧删掉就是真的没有了。
* **结构更新是给人读的**：不是能自动跑的迁移（SQLite 库每次重建、开发库升级走
  `docs/sql/待执行/` 那条既定通道），正文 + 附录里可抄进那个目录的 MySQL ALTER 片段。

命令与产物都写进 `tools/README.md` 与 CLAUDE.md 的「打包」一段。
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

MYSQL_DDL = ROOT / "src" / "main" / "resources" / "db" / "nya_entworks.sql"
SQLITE_DDL = ROOT / "src" / "main" / "resources" / "db" / "nya_entworks.sqlite.sql"

LOCAL_BACKUP = ROOT / ".local-backup"
SCHEMA_BACKUP = LOCAL_BACKUP / "schema"          # bump_version.py minor 落的库结构快照
RELEASE_BACKUP = LOCAL_BACKUP / "release"        # 本脚本落的「上次发版长什么样」

STAGED = ROOT / "desktop" / "build" / "staged"
DEFAULT_RELEASE_DIR = Path(r"C:\Code\呜啊娱乐工坊")

DEFAULT_VERSION_MARKER = "当前版本"
UPDATE_DIR_NAME = "结构更新"
APP_NAME = "呜啊娱乐工坊"                             # 与 package.json 的 productName 同一个名字

# electron-builder 的工具包（winCodeSign / nsis）都挂在 GitHub releases 上，直连在国内
# 常连不上 —— 症状是卡在 `downloading url=https://github.com/... size=5.6 MB` 反复重试，
# 每次都要等一轮 TCP 超时（几分钟起步），看起来像卡死了。这是 electron-builder 认的官方
# 镜像变量，**值必须以 / 结尾**。2026-09-30 本机实测：直连超时，走它 1 秒下完。
# 想用别的镜像就设同行名的环境变量，本脚本不会覆盖已有值。
BINARIES_MIRROR = "https://npmmirror.com/mirrors/electron-builder-binaries/"

# 保留哪些表 —— **只留这次发出去的模块用得到的**（用户裁决：歌曲 + 喊麦，裁掉漫画）。
# 判据与 EnvCheckService#checkTables 那张 module→表 映射同源：模块不在时它的表连检查
# 一起跳过，所以库里没有漫画表**不会**让自检报假警。
KEEP_TABLES = [
    "async_task", "file_op_log",                              # 通用
    "song_group", "song_file", "song_tag", "song_original_setting",
    "song_lyric_fill", "rhyme_entry", "lyric_corpus_line", "lyric_corpus_pair",
    "shout_group", "shout_file", "shout_tag",
]

# 镜像表：内容由磁盘扫描重建，交付的空库里**刻意不 seed**（朋友首装磁盘为空，同步自然跳过）。
# 这里只是记一笔，真正要 seed 的只有 rhyme_entry（由 RhymeSeedRunner 首启后台灌）。

# 控制台多半是 936（GBK）。与 tools/mysql.py / bump_version.py 同一口径：两个流钉成 UTF-8 +
# 替换字符，**绝不能让 print 抛 UnicodeEncodeError**（一抛就是「每次跑都跟一段 traceback」）。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


# ============================================================================
# 一、MySQL DDL → SQLite DDL
# ============================================================================

# CREATE TABLE 块。正文里没有「) 后面紧跟 ENGINE」这种东西，所以非贪婪到第一个
# `) ENGINE` 是安全的；末尾 `[^;]*;` 吃掉 `ENGINE = InnoDB AUTO_INCREMENT = 72 ...;` 一整段。
CREATE_TABLE_RE = re.compile(
    r"CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+`?(\w+)`?\s*\((.*?)\)\s*ENGINE\s*=[^;]*;",
    re.S | re.I)

# 列注释（正文里出现过 `\\` 转义与中文括号）；去掉后注释不会进 SQLite —— 那边没有这语法
COMMENT_RE = re.compile(r"\s*COMMENT\s*'(?:[^'\\]|\\.|'')*'", re.S | re.I)
CHARSET_RE = re.compile(r"\s+CHARACTER\s+SET\s+\w+", re.I)
COLLATE_RE = re.compile(r"\s+COLLATE\s+(\w+)", re.I)
ON_UPDATE_RE = re.compile(r"\s*ON\s+UPDATE\s+CURRENT_TIMESTAMP(\(\d*\))?", re.I)
# 裸 NULL 不是合法的 SQLite 列约束（`NULL` 只在 `NOT NULL` / `DEFAULT NULL` 里有意义）。
# 两个定长 lookbehind 把这两种情形摘出去 —— 变长 lookbehind 在 Python re 里不允许，
# 但这两种写法都是定长的，正好能写。顺序也有讲究：先摘 NOT- / DEFAULT-，再删剩下的。
BARE_NULL_RE = re.compile(r"(?<!NOT )(?<!DEFAULT )\bNULL\b", re.I)
PRIMARY_KEY_RE = re.compile(r"^PRIMARY\s+KEY\s*\((.*)\)\s*(USING\s+\w+)?$", re.I | re.S)
INDEX_RE = re.compile(
    r"^(UNIQUE\s+)?(?:INDEX|KEY)\s+`?(\w+)`?\s*\((.*)\)\s*(USING\s+\w+)?$", re.I | re.S)
COLUMN_RE = re.compile(r"^`?([A-Za-z_]\w*)`?\s+(.*)$", re.S)
# 类型映射：SQLite 只有 5 种存储类别，其余一律落到最贴近的那个。
# **`bigint` 必须映射成 `INTEGER` 而不是 `BIGINT`**：只有 INTEGER PRIMARY KEY 才是 rowid
# 的别名，BIGINT PRIMARY KEY 拿不到自增、getGeneratedKeys 也回不来 id。
TYPE_MAP = {
    "bigint": "INTEGER", "int": "INTEGER", "integer": "INTEGER", "smallint": "INTEGER",
    "tinyint": "INTEGER", "mediumint": "INTEGER", "bit": "INTEGER",
    "double": "REAL", "float": "REAL",
    "decimal": "NUMERIC", "numeric": "NUMERIC",
    "varchar": "TEXT", "char": "TEXT", "text": "TEXT", "tinytext": "TEXT",
    "mediumtext": "TEXT", "longtext": "TEXT",
    "datetime": "TEXT", "timestamp": "TEXT", "date": "TEXT", "time": "TEXT",
}


def split_top_level(s: str) -> list[str]:
    """按**顶层**逗号切分（括号内的逗号不算，单引号内的也不算）。

    建表正文里 `decimal(4, 2)`、`INDEX x(a, b)` 都有括号内逗号，直接 `split(",")`
    会把一条定义切成两半 —— 那是静默的，切出来的半条会被当成「不认识的列定义」。
    """
    parts, buf, depth, in_str = [], [], 0, False
    i = 0
    while i < len(s):
        ch = s[i]
        if in_str:
            buf.append(ch)
            if ch == "\\" and i + 1 < len(s):
                buf.append(s[i + 1])
                i += 2
                continue
            if ch == "'":
                in_str = False
            i += 1
            continue
        if ch == "'":
            in_str = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    if "".join(buf).strip():
        parts.append("".join(buf))
    return [p.strip() for p in parts if p.strip()]


def index_columns(raw: str) -> list[str]:
    """索引里的列名列表：去 `ASC`/`DESC`、去反引号、**去前缀长度**。

    前缀索引 `dict_value(191)` 是 InnoDB 3072 字节键长的妥协，SQLite 没有这个限制，
    照抄会变成「对 `dict_value(191)` 这个不存在的表达式建索引」—— 静默建不出来。
    """
    cols = []
    for part in split_top_level(raw):
        part = re.sub(r"\s+(ASC|DESC)\s*$", "", part.strip(), flags=re.I)
        m = re.match(r"^`?([^`(]+)`?\(\d+\)$", part)
        if m:
            part = m.group(1)
        cols.append(part.strip().strip("`"))
    return cols


def convert_column(line: str) -> str:
    """一条列定义：MySQL 写法 → SQLite 写法。"""
    m = COLUMN_RE.match(line)
    if not m:
        raise ValueError("看不懂的列定义：" + line)
    name, rest = m.group(1), m.group(2)

    if re.search(r"\bAUTO_INCREMENT\b", rest, re.I):
        # 自增列即主键。SQLite 里必须精确写成 `INTEGER PRIMARY KEY`（见 TYPE_MAP 注释）
        return f"{name} INTEGER PRIMARY KEY"

    rest = COMMENT_RE.sub("", rest)
    rest = CHARSET_RE.sub("", rest)
    binary = False

    def _collate(cm: re.Match) -> str:
        nonlocal binary
        # `utf8mb4_bin` 是**语义**（韵脚那四列：ai_ci 下 lǜ = lù，会把同字不同调当重复），
        # 不能像 ai_ci 那样一删了事 —— 映射成 SQLite 的 BINARY。ai_ci 那半是表默认值，删掉。
        if cm.group(1).lower().endswith("_bin"):
            binary = True
            return ""
        return ""

    rest = COLLATE_RE.sub(_collate, rest)
    rest = ON_UPDATE_RE.sub("", rest)   # 更新时间的自动维护交给 MetaObjectHandler

    tm = re.match(r"^([A-Za-z]+)\s*(\([^)]*\))?(.*)$", rest.strip(), re.S)
    if not tm:
        raise ValueError("看不懂的列类型：" + line)
    base, tail = tm.group(1).lower(), tm.group(3)
    sql_type = TYPE_MAP.get(base, (base + (tm.group(2) or "")).upper())

    # `DEFAULT CURRENT_TIMESTAMP` 在 SQLite 里是 **UTC**，在 MySQL 里是会话本地时间 ——
    # 同一个库会混出两种时刻，按时间排出来是乱的（实测：Java 侧写 22:47、SQL 侧写 14:47）。
    # 换成 datetime('now','localtime')，产物形状与 MySQL 那边一致（'YYYY-MM-DD HH:MM:SS'）。
    # 表达式当默认值在 SQLite 里必须加括号。
    if re.search(r"DEFAULT\s+CURRENT_TIMESTAMP", tail, re.I):
        tail = re.sub(r"DEFAULT\s+CURRENT_TIMESTAMP(\(\d*\))?",
                      "DEFAULT (datetime('now','localtime'))", tail, flags=re.I)

    tail = BARE_NULL_RE.sub("", tail)
    tail = re.sub(r"\s+", " ", tail).strip()
    if binary:
        tail = (tail + " COLLATE BINARY").strip()
    return f"{name} {sql_type}" + ((" " + tail) if tail else "")


def convert_table(name: str, body: str) -> tuple[str, list[str]]:
    """一张表：返回 (建表语句, 建索引语句列表)。"""
    columns: list[str] = []
    uniques: list[list[str]] = []
    indexes: list[tuple[str, list[str]]] = []

    for raw in split_top_level(body):
        line = re.sub(r"\s+", " ", raw).strip()
        if not line:
            continue
        pk = PRIMARY_KEY_RE.match(line)
        if pk:
            cols = index_columns(pk.group(1))
            # 本项目的每张表都是「id 自增 + PRIMARY KEY(id)」，由列级的
            # `INTEGER PRIMARY KEY` 表达。复合主键要另想办法 —— 与其默默丢掉，不如报错。
            if cols != ["id"]:
                raise ValueError(f"{name} 的主键不是单列 id：{cols}（转换规则没覆盖这种）")
            continue
        idx = INDEX_RE.match(line)
        if idx:
            cols = index_columns(idx.group(3))
            if idx.group(1):        # UNIQUE INDEX → 必须**内联**成 UNIQUE(...)
                # 内联不只是风格：SQLite 的 upsert 要写 `ON CONFLICT(text, pinyin)`，
                # 那个目标必须匹配一条 UNIQUE 约束 —— 独立 CREATE UNIQUE INDEX 匹配不上。
                uniques.append(cols)
            else:
                indexes.append((idx.group(2), cols))
            continue
        columns.append(convert_column(line))

    lines = ["  " + c for c in columns]
    lines += ["  UNIQUE (" + ", ".join(c) + ")" for c in uniques]
    ddl = f"CREATE TABLE IF NOT EXISTS {name} (\n" + ",\n".join(lines) + "\n);"
    for idx_name, cols in indexes:
        # 索引名带上表名 —— SQLite 的索引名是**整个库**唯一的（MySQL 是每张表各自命名），
        # 三张表各有一个 idx_status 在 MySQL 里相安无事，到 SQLite 就会撞名，
        # 而 `CREATE INDEX IF NOT EXISTS` 撞名**不报错、直接跳过** ——
        # 症状是后两张表的索引静默地不存在，慢查询只在数据量上来以后才显形。
        ddl += (f"\nCREATE INDEX IF NOT EXISTS {name}_{idx_name} "
                f"ON {name}({', '.join(cols)});")
    return ddl, []


def to_sqlite_ddl(mysql_text: str, tables: list[str] | None = None) -> str:
    """整份 MySQL 建表脚本 → SQLite 建表脚本（默认只保留 KEEP_TABLES）。"""
    tables = KEEP_TABLES if tables is None else tables
    blocks = {name: body for name, body in
              ((m.group(1), m.group(2)) for m in CREATE_TABLE_RE.finditer(mysql_text))}
    missing = [t for t in tables if t not in blocks]
    if missing:
        raise ValueError(
            "建表脚本里没有这些表：" + "、".join(missing)
            + " —— 要么 KEEP_TABLES 过时了，要么 MySQL 那份 DDL 改了名。"
              "两处必须对齐（判据与 EnvCheckService 的 module→表 映射同源）")

    out = [
        "-- ============================================================================",
        "-- **本文件由 tools/release.py 生成，不要手改** —— 改 src/main/resources/db/"
        "nya_entworks.sql",
        "-- 再跑 `python tools/release.py --emit-sqlite-ddl`。",
        "--",
        "-- 它是发出去那一份的建表脚本：只含歌曲 + 喊麦 + 通用表（漫画裁掉），随包打进 jar，",
        "-- 首启由 spring.sql.init 自动执行（application-release.yaml 里 mode: always）。",
        "-- 全是 CREATE ... IF NOT EXISTS，可重复执行。",
        "--",
        "-- 与 MySQL 那份的两处**有意**不同（其余逐字对应）：",
        "--   一、索引名带上了表名。MySQL 的索引名在每张表里各自命名，SQLite 是全库唯一；",
        "--       不带表名的话后建的那条会撞名，而 IF NOT EXISTS 撞名不报错、直接跳过。",
        "--   二、唯一键写成表内的 UNIQUE (...)。SQLite 的 upsert（ON CONFLICT）只认表内约束，",
        "--       独立 CREATE UNIQUE INDEX 匹配不上，会报 no matching constraint。",
        "-- ============================================================================",
        "",
    ]
    for table in tables:
        ddl, _ = convert_table(table, blocks[table])
        out.append(f"-- ----------------------------\n-- {table}\n-- ----------------------------")
        out.append(ddl)
        out.append("")
    return "\n".join(out)


# ============================================================================
# 二、结构比对（都按 **MySQL** 那份比 —— 开发库才是要出 ALTER 片段的地方）
# ============================================================================

def parse_mysql_structure(text: str) -> dict:
    """MySQL 建表脚本 → 可比对的结构字典。

    只抽「结构」，其余一律不进比较：注释、`AUTO_INCREMENT=NNNN`（那是行数的痕迹）、
    表选项（ENGINE / 字符集 / ROW_FORMAT）、反引号、对齐用的空白。
    """
    text = re.sub(r"^--.*$", "", text, flags=re.M)          # 行注释
    st = {}
    for m in CREATE_TABLE_RE.finditer(text):
        name, body = m.group(1), m.group(2)
        cols, uniques, indexes = {}, {}, {}
        for raw in split_top_level(body):
            line = re.sub(r"\s+", " ", COMMENT_RE.sub("', '", raw)).strip()
            pk = PRIMARY_KEY_RE.match(line)
            if pk:
                continue
            idx = INDEX_RE.match(line)
            if idx:
                cols_ = index_columns(idx.group(3))
                if idx.group(1):
                    uniques[", ".join(cols_)] = True
                else:
                    indexes[idx.group(2)] = ", ".join(cols_)
                continue
            cm = COLUMN_RE.match(line)
            if not cm:
                continue
            col = cm.group(1)
            val = cm.group(2)
            val = CHARSET_RE.sub("", val)
            # 只有 _bin 那种「有语义的」排序规则才进比较（见 convert_column 里的同一段理由）
            val = COLLATE_RE.sub(lambda x: ("COLLATE " + x.group(1)) if x.group(1).lower().endswith("_bin") else "", val)
            cols[col] = re.sub(r"\s+", " ", val).strip().rstrip(",")
        st[name] = {"columns": cols, "unique": uniques, "indexes": indexes}
    return st


def diff_structure(old: dict, new: dict) -> list[str]:
    """两个结构字典的差，按「人话」逐条列出（顺序稳定，便于 diff）。"""
    lines: list[str] = []
    for t in sorted(set(new) - set(old)):
        lines.append(f"新增表 `{t}`")
    for t in sorted(set(old) - set(new)):
        lines.append(f"删除表 `{t}`")
    for t in sorted(set(old) & set(new)):
        o, n = old[t], new[t]
        for c in sorted(set(n["columns"]) - set(o["columns"])):
            lines.append(f"`{t}` 新增列 `{c}`：{n['columns'][c]}")
        for c in sorted(set(o["columns"]) - set(n["columns"])):
            lines.append(f"`{t}` 删除列 `{c}`（原：{o['columns'][c]}）")
        for c in sorted(set(o["columns"]) & set(n["columns"])):
            if o["columns"][c] != n["columns"][c]:
                lines.append(f"`{t}`.`{c}` 变化：{o['columns'][c]}  →  {n['columns'][c]}")
        for k in sorted(set(n["unique"]) - set(o["unique"])):
            lines.append(f"`{t}` 新增唯一键（{k}）")
        for k in sorted(set(o["unique"]) - set(n["unique"])):
            lines.append(f"`{t}` 删除唯一键（{k}）")
        for k in sorted(set(n["indexes"]) - set(o["indexes"])):
            lines.append(f"`{t}` 新增索引 `{k}`（{n['indexes'][k]}）")
        for k in sorted(set(o["indexes"]) - set(n["indexes"])):
            lines.append(f"`{t}` 删除索引 `{k}`")
        for k in sorted(set(o["indexes"]) & set(n["indexes"])):
            if o["indexes"][k] != n["indexes"][k]:
                lines.append(f"`{t}` 索引 `{k}` 变化：{o['indexes'][k]} → {n['indexes'][k]}")
    return lines


def diff_config(old: dict, new: dict) -> list[str]:
    """两个「清理过的配置」的键值差（只留发出去的那几项，见 build_release_config）。"""
    lines: list[str] = []
    for k in sorted(set(new) - set(old)):
        lines.append(f"新增配置项 `{k}` = {new[k]!r}")
    for k in sorted(set(old) - set(new)):
        lines.append(f"删除配置项 `{k}`（原值 {old[k]!r}）")
    for k in sorted(set(old) & set(new)):
        if old[k] != new[k]:
            lines.append(f"配置项 `{k}` 变化：{old[k]!r} → {new[k]!r}")
    return lines


# ============================================================================
# 三、自检（不碰盘、不连库）
# ============================================================================

# 一段刻意「什么写法都占上一条」的 MySQL 建表语句，用来钉住转换规则。
# 每一处都对应 CLAUDE.md / 计划里点过名的一个坑，不是随便编的：
#   * `id bigint AUTO_INCREMENT`  —— 必须落成**精确的** INTEGER PRIMARY KEY
#   * `utf8mb4_bin`               —— 是语义（韵脚靠它区分 lǜ / lù），不能一删了事
#   * `'a, b'` / `'F:\\x\\y'`     —— 注释里的逗号与转义反斜杠，切分与剥离都要扛得住
#   * `idx_finals` 建在 finals 上 —— 普通索引要挪到建表之后
#   * `uk_text_pinyin`            —— 唯一键必须内联（upsert 的 ON CONFLICT 靠它匹配）
#   * `rate ... NULL DEFAULT NULL`—— 裸 NULL 要删、DEFAULT NULL 要留
SQLITE_SELFTEST_CASE = """\
CREATE TABLE IF NOT EXISTS `rhyme_entry`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增代理主键',
  `pinyin` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'lǜ = lù 那条',
  `text` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'a, b',
  `finals` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `tier` tinyint NOT NULL DEFAULT 0 COMMENT '常用度',
  `rate` decimal(4, 2) NULL DEFAULT NULL COMMENT '倍速',
  `note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '里头有 F:\\\\x\\\\y',
  `create_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_finals`(`finals`(20) ASC) USING BTREE,
  UNIQUE INDEX `uk_text_pinyin`(`text` ASC, `pinyin` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 122329 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '韵脚词典' ROW_FORMAT = Dynamic;
"""

# (说明, 产物里**必须**出现的片段, 产物里**必须不**出现的片段或 None)
SQLITE_SELFTEST_CASES = [
    ("自增主键落成精确的 INTEGER PRIMARY KEY（不是 BIGINT）",
     "id INTEGER PRIMARY KEY", "bigint"),
    ("表级 PRIMARY KEY 由列级表达，不再单列",
     "id INTEGER PRIMARY KEY", "PRIMARY KEY (`id`)"),
    ("唯一键内联成 UNIQUE(...)（upsert 的 ON CONFLICT 靠它匹配）",
     "UNIQUE (text, pinyin)", "CREATE UNIQUE INDEX"),
    ("普通索引挪到建表之后、带 IF NOT EXISTS、前缀长度已去掉、名字带上表名",
     "CREATE INDEX IF NOT EXISTS rhyme_entry_idx_finals ON rhyme_entry(finals);", "USING BTREE"),
    ("_bin 排序规则是语义，映射成 BINARY",
     "pinyin TEXT NOT NULL COLLATE BINARY", "utf8mb4_bin"),
    ("ai_ci 那半是表默认值，删掉",
     None, "utf8mb4_0900_ai_ci"),
    ("裸 NULL 不是合法约束要删、DEFAULT NULL 与 NOT NULL 都要留",
     "rate NUMERIC DEFAULT NULL", None),
    ("注释里的逗号没把列定义切开（否则会多出一个坏列）",
     "text TEXT NOT NULL", "a, b"),
    ("注释里的转义反斜杠没把注释剥离搞崩",
     "note TEXT DEFAULT NULL", "F:"),
    ("ON UPDATE CURRENT_TIMESTAMP 去掉；DEFAULT 换成当地时间（SQLite 的 CURRENT_TIMESTAMP 是 UTC）",
     "update_time TEXT DEFAULT (datetime('now','localtime'))", "ON UPDATE"),
    ("类型各归各的存储类别",
     "tier INTEGER NOT NULL DEFAULT 0", "tinyint"),
    ("反引号、注释关键字、表选项全部清干净",
     None, None),
]


def selftest() -> int:
    bad = 0
    checks = 0

    out = to_sqlite_ddl(SQLITE_SELFTEST_CASE, ["rhyme_entry"])
    # 所有「不该出现」的判据都只扫 **DDL 正文**：文件头的说明文字里本来就有反引号、
    # 「DEFAULT NULL」这种词，扫进去就是自己给自己判红。
    body = "\n".join(l for l in out.splitlines() if not l.lstrip().startswith("--"))
    low = body.lower()

    for desc, needle, forbid in SQLITE_SELFTEST_CASES:
        checks += 1
        if needle is not None and needle not in out:
            print(f"  FAIL {desc}\n        产物里没有 {needle!r}：\n{out}")
            bad += 1
        if forbid is not None and forbid.lower() in low:
            print(f"  FAIL {desc}\n        产物里不该有 {forbid!r}：\n{out}")
            bad += 1
    for forbid in ("`", "AUTO_INCREMENT", "ROW_FORMAT", "ENGINE =", "COMMENT '", "USING BTREE"):
        checks += 1
        if forbid.lower() in low:
            print(f"  FAIL 产物里不该出现 {forbid!r}：\n{out}")
            bad += 1
    # 裸 NULL —— 必须用与转换器同一对 lookbehind（这是**有意**的重复：抓的就是
    # 有人把 `(?<!NOT )(?<!DEFAULT )` 那对前缀写成只删 NULL，症状是表里凭空多出 NOT / DEFAULT）
    checks += 1
    stray = BARE_NULL_RE.search(body)
    if stray:
        print(f"  FAIL 正文里还剩裸 NULL：{body[max(0, stray.start() - 40):stray.end() + 10]!r}")
        bad += 1

    # 真去 SQLite 里建一遍 —— 规则对不对，只有它能说了算
    try:
        conn = sqlite3.connect(":memory:")
        conn.executescript(out)
        cols = [r[1] for r in conn.execute("PRAGMA table_info(rhyme_entry)")]
        if cols[:3] != ["id", "pinyin", "text"]:
            print(f"  FAIL 建出来的表列不对：{cols}")
            bad += 1
        cur = conn.execute(
            "INSERT INTO rhyme_entry (pinyin, text) VALUES ('a', 'x')")
        if cur.lastrowid != 1 and cur.lastrowid is not None:
            print(f"  FAIL 自增主键没回 rowid：{cur.lastrowid}")
            bad += 1
        conn.close()
    except Exception as e:  # noqa: BLE001 —— selftest 里就是要抓住任何一种炸法
        print(f"  FAIL 产物在 SQLite 里跑不过：{e}\n" + out)
        bad += 1

    # 结构 diff
    old = parse_mysql_structure(
        "CREATE TABLE IF NOT EXISTS `t` (\n"
        "  `id` bigint NOT NULL AUTO_INCREMENT,\n"
        "  `a` varchar(10) COLLATE utf8mb4_bin NOT NULL COMMENT 'x',\n"
        "  PRIMARY KEY (`id`) USING BTREE\n"
        ") ENGINE = InnoDB AUTO_INCREMENT = 5;")
    new = parse_mysql_structure(
        "CREATE TABLE IF NOT EXISTS `t` (\n"
        "  `id` bigint NOT NULL AUTO_INCREMENT,\n"
        "  `a` varchar(20) COLLATE utf8mb4_bin NOT NULL COMMENT 'x',\n"
        "  `b` int NULL DEFAULT NULL,\n"
        "  PRIMARY KEY (`id`) USING BTREE,\n"
        "  INDEX `i`(`b` ASC) USING BTREE\n"
        ") ENGINE = InnoDB AUTO_INCREMENT = 99;")
    d = diff_structure(old, new)
    if not any("新增列 `b`" in x for x in d):
        print(f"  FAIL 没 diff 出新增列：{d}")
        bad += 1
    if not any("变化" in x and "varchar(10)" in x and "varchar(20)" in x for x in d):
        print(f"  FAIL 没 diff 出类型变化：{d}")
        bad += 1
    if not any("新增索引 `i`" in x for x in d):
        print(f"  FAIL 没 diff 出新增索引：{d}")
        bad += 1
    if diff_structure(old, old):
        print("  FAIL 同一个结构 diff 出了差异（会产生假的「结构更新」文件）")
        bad += 1

    # 真 DDL：能转、裁到该裁的、**而且真能在 SQLite 里建起来**（跑两遍验幂等）
    checks += 1
    real = to_sqlite_ddl(MYSQL_DDL.read_text(encoding="utf-8"))
    for t in KEEP_TABLES:
        if f"CREATE TABLE IF NOT EXISTS {t} " not in real:
            print(f"  FAIL 真 DDL 里少了表 {t}")
            bad += 1
    if "manga_data" in real:
        print("  FAIL 漫画的表没被裁掉（这次只发歌曲 + 喊麦）")
        bad += 1
    try:
        conn = sqlite3.connect(":memory:")
        conn.executescript(real)
        conn.executescript(real)          # 首启会重跑，必须幂等
        have = {r[0] for r in conn.execute(
            "select name from sqlite_master where type = 'table'")}
        for t in KEEP_TABLES:
            cols = [r[1] for r in conn.execute(f"PRAGMA table_info({t})")]
            if not cols:
                print(f"  FAIL 真 DDL 转出来在 SQLite 里建不出表 {t}")
                bad += 1
            if "id" in cols:
                pk = [r[1] for r in conn.execute(f"PRAGMA table_info({t})") if r[5]]
                if pk != ["id"]:
                    print(f"  FAIL {t} 的主键不是 id 单列 INTEGER：{pk}"
                          "（会拿不到自增 id）")
                    bad += 1
        extra = have - set(KEEP_TABLES) - {"sqlite_sequence"}
        if extra:
            print(f"  FAIL 多出来不该有的表：{sorted(extra)}")
            bad += 1
        conn.close()
    except Exception as e:  # noqa: BLE001 —— selftest 里就是要抓住任何一种炸法
        print(f"  FAIL 真 DDL 转出来的东西在 SQLite 里跑不过：{e}")
        bad += 1

    # 真 DDL 里三张表都叫 idx_status —— SQLite 的索引名是全库唯一的，必须各带表名。
    # 这条专门抓「撞名的 CREATE INDEX IF NOT EXISTS 不报错、直接跳过」那个静默分支。
    checks += 1
    dup = to_sqlite_ddl(
        "CREATE TABLE IF NOT EXISTS `a` (`id` bigint NOT NULL AUTO_INCREMENT, "
        "`s` varchar(8), PRIMARY KEY (`id`) USING BTREE, INDEX `idx_status`(`s`) USING BTREE) "
        "ENGINE = InnoDB;"
        "CREATE TABLE IF NOT EXISTS `b` (`id` bigint NOT NULL AUTO_INCREMENT, "
        "`s` varchar(8), PRIMARY KEY (`id`) USING BTREE, INDEX `idx_status`(`s`) USING BTREE) "
        "ENGINE = InnoDB;", ["a", "b"])
    try:
        conn = sqlite3.connect(":memory:")
        conn.executescript(dup)
        made = {r[0] for r in conn.execute(
            "select name from sqlite_master where type = 'index' and name like '%idx_status'")}
        conn.close()
        if made != {"a_idx_status", "b_idx_status"}:
            print(f"  FAIL 同名的索引在 SQLite 里只建出一个（另一个被 IF NOT EXISTS 静默跳过）：{made}")
            bad += 1
    except Exception as e:  # noqa: BLE001
        print(f"  FAIL 两张表同索引名时转换产物跑不过：{e}")
        bad += 1

    # jar 里带不带建表脚本 —— `--no-build` 复用旧 jar 时唯一的拦路虎。
    # 用一个临时 zip 冒充 jar：带那个条目就该放行，不带就必须拦下。
    checks += 1
    entry = "BOOT-INF/classes/db/" + SQLITE_DDL.name
    with tempfile.TemporaryDirectory() as td:
        good = Path(td) / "good.jar"
        with zipfile.ZipFile(good, "w") as zf:
            zf.writestr(entry, "-- x")
        assert_jar_carries_sqlite_ddl(good)          # 不该炸

        bad_jar = Path(td) / "bad.jar"
        with zipfile.ZipFile(bad_jar, "w") as zf:
            zf.writestr("BOOT-INF/classes/db/nya_entworks.sql", "-- 只有 MySQL 那份")
        try:
            assert_jar_carries_sqlite_ddl(bad_jar)
        except SystemExit:
            pass
        else:
            print("  FAIL 旧 jar（没有 SQLite 建表脚本）没被拦下 —— 会做出打不开的 exe")
            bad += 1

    # 跨文件的那一条契约：RhymeEntryMapper.xml 的 SQLite 分支写 `ON CONFLICT(text, pinyin)`，
    # 而 SQLite 的 upsert **只认表内的 UNIQUE 约束**（独立 CREATE UNIQUE INDEX 匹配不上，
    # 报 no matching constraint，而且只有真跑到那条语句才会显形）。所以拿 XML 里那个
    # 目标列去对转换器内联出来的 UNIQUE，再真跑一次 upsert —— 两处对齐才算过。
    checks += 1
    try:
        xml = (ROOT / "src" / "main" / "resources" / "mapper" / "RhymeEntryMapper.xml") \
            .read_text(encoding="utf-8")
        targets = set(re.findall(r"ON\s+CONFLICT\s*\(([^)]*)\)", xml))
        if not targets:
            print("  FAIL RhymeEntryMapper.xml 里找不到 ON CONFLICT(...) —— 语句被改写了？")
            bad += 1
        else:
            tgt = sorted(targets)[0].replace(" ", "")
            if f"UNIQUE ({tgt.replace(',', ', ')})" not in real:
                print(f"  FAIL 转换器没把 {tgt} 内联成表内 UNIQUE，SQLite 跑 upsert 会报"
                      " no matching constraint")
                bad += 1
            conn = sqlite3.connect(":memory:")
            conn.executescript(real)
            conn.execute("INSERT INTO rhyme_entry (entry_type, text, pinyin, finals,"
                         " rhyme_body, yun18, source, tier, freq, update_time)"
                         " VALUES ('CHAR','x','a','a','a','一麻','MANUAL',1,0,"
                         " datetime('now','localtime'))")
            # 造一条「已有行是 MANUAL、本次来的是 CORPUS」的冲突，正是 upsert 要保住的那条语义
            conn.execute(f"INSERT INTO rhyme_entry (entry_type, text, pinyin, finals,"
                         f" rhyme_body, yun18, source, tier, freq, update_time)"
                         f" VALUES ('CHAR','x','a','a','a','一麻','CORPUS',9,42,"
                         f" datetime('now','localtime'))"
                         f" ON CONFLICT({tgt}) DO UPDATE SET"
                         f" freq = CASE WHEN rhyme_entry.source = 'CORPUS'"
                         f" THEN excluded.freq ELSE rhyme_entry.freq END")
            src, freq = conn.execute(
                "select source, freq from rhyme_entry where text = 'x'").fetchone()
            conn.close()
            if (src, freq) != ("MANUAL", 0):
                print(f"  FAIL upsert 把人工认领的行覆盖了（应为 MANUAL/0，实际 {src}/{freq}）")
                bad += 1
    except Exception as e:  # noqa: BLE001
        print(f"  FAIL upsert 契约检查跑不过：{e}")
        bad += 1

    # 另一个跨文件的不变量：配了 DatabaseIdProvider 之后，requiredDatabaseId 非 null，
    # **没写 databaseId 的 XML 语句一条都不会被加载**。所以每个语句 id 都必须两个变体齐全 ——
    # 漏一个不是「那一边坏」，而是那一边抛 Invalid bound statement。
    checks += 1
    try:
        mapper_dir = ROOT / "src" / "main" / "resources" / "mapper"
        for x in sorted(mapper_dir.glob("*.xml")):
            text = x.read_text(encoding="utf-8")
            variants: dict[str, set[str]] = {}
            for m in re.finditer(r"<(?:insert|update|delete|select)\s+id=\"(\w+)\"[^>]*?"
                                 r"databaseId=\"(\w+)\"", text):
                variants.setdefault(m.group(1), set()).add(m.group(2))
            bare = re.findall(r"<(?:insert|update|delete|select)\s+id=\"(\w+)\"(?![^>]*databaseId)", text)
            for sid, ids in sorted(variants.items()):
                if ids != {"mysql", "sqlite"}:
                    print(f"  FAIL {x.name} 的 {sid} 只写了 {sorted(ids)} 变体"
                          "（两个都要有，没写的那一边会 Invalid bound statement）")
                    bad += 1
            if bare:
                print(f"  FAIL {x.name} 里有没标 databaseId 的语句 {bare} —— 配了 provider 之后"
                      "这类语句一条都不会被加载")
                bad += 1
    except Exception as e:  # noqa: BLE001
        print(f"  FAIL mapper XML 变体检查跑不过：{e}")
        bad += 1

    if bad:
        print(f"selftest：{bad} 条失败（共 {checks} 条）")
        return 1
    print(f"selftest：{checks} 条全过")
    return 0

# ============================================================================
# 四、流程
# ============================================================================

def say(msg: str) -> None:
    print("[release] " + msg, flush=True)


def run(argv: list[str], cwd: Path = ROOT, env: dict | None = None,
        capture: bool = False) -> subprocess.CompletedProcess:
    """跑一条外部命令。**Windows 上必须经 cmd.exe**：mvnw.cmd / npm 都是批处理，

    CreateProcess 直接执行 .cmd 会报「不是有效的 Win32 应用程序」。
    """
    full = ["cmd", "/c", *argv] if os.name == "nt" else argv
    return subprocess.run(full, cwd=str(cwd), env=env, text=True,
                          encoding="utf-8", errors="replace",
                          capture_output=capture)


def assert_free_to_replace(path: Path, hint: str) -> None:
    """确认 `path` 上的文件**没被别的进程占着**，占着就带着 `hint` 报出来。

    判据是**能不能改名**（Windows 上被占用的文件既不能删也不能改名，但**能**被覆盖写 ——
    「能写」不足以说明安全：占用者读到一半的文件被换掉是另一种坏事）。
    改得动就说明没人占，动完立刻改回来，不改变盘上任何一个字节。
    """
    if not path.is_file():
        return
    probe = path.with_name(path.name + ".lockprobe")
    try:
        os.replace(path, probe)
    except OSError as exc:
        raise SystemExit(
            f"{path.name} 改不动名（{exc.strerror or exc}）—— 它正被别的进程占着。\n{hint}")
    os.replace(probe, path)   # 上一步能成，这一步必然能成；真失败就该炸出来


def assert_target_not_locked(target_dir: Path) -> None:
    """发版前先确认 `target/` 里的 jar 没被占用 —— 也就是**桌面壳窗口已经关掉**。

    为什么非查不可：壳跑着的时候，它起的 java sidecar 持有 jar 的句柄，
    `mvnw clean package` 会在删 jar 那一步报「另一个程序正在使用此文件」。
    那个报错被 mvn 的一大段输出埋着，读起来像构建脚本坏了，而不像「你的窗口没关」。
    """
    if not target_dir.is_dir():
        return
    for jar in sorted(target_dir.glob("*.jar")):
        assert_free_to_replace(
            jar,
            "  十有八九是**桌面壳还开着**：壳起的 java 进程持有这个 jar，\n"
            "  `mvnw clean package` 于是死在「另一个程序正在使用此文件」。\n"
            "  请先关掉那个窗口（后端进程会跟着一起退），再跑本脚本。")


def assert_jar_carries_sqlite_ddl(jar: Path) -> None:
    """确认这个 jar 里**带着** SQLite 建表脚本 —— 不带就是「用了个旧 jar」。

    为什么非查不可：建表脚本是**在构建之前**才写进 `src/main/resources/db/` 的
    （见 main 的第 1 步），所以只有「先生成、后 mvn」那条顺序产出的 jar 才有它。
    `--no-build` 会直接拿 `target/` 里现成的那一个 —— 那个多半是**更早**编的，
    里面只有 MySQL 那份 `nya_entworks.sql`。

    少了它的症状**全在运行期**、而且极难倒查：双击 exe 后端起不来，
    `sidecar.log` 里是
    `No schema scripts found at location 'classpath:db/nya_entworks.sqlite.sql'`
    —— 一句话里既没有「旧 jar」也没有「少了个文件」，看着像打包本身坏了。
    （2026-10-01 就是这么踩上的：`--no-build` 复用了 22:09 的 jar，
    而脚本是 00:11 才生成的那份脚本，于是交付出去的 exe 一打开就退。）

    只读地看一眼 zip 目录，不落盘、不改 jar。
    """
    entry = "BOOT-INF/classes/db/" + SQLITE_DDL.name
    with zipfile.ZipFile(jar) as zf:
        if entry in zf.namelist():
            return
        raise SystemExit(
            f"{jar.name} 里没有 {entry} —— 这是个**旧 jar**，直接打包会做出一个打不开的 exe。\n"
            "  建表脚本是构建前才生成的，所以 jar 必须比它新。\n"
            "  两个办法：① 去掉 `--no-build` 让它重新构建（要先关掉桌面壳窗口）；\n"
            "            ② 自己先跑一次 `mvnw clean package -DskipTests` 再来。")


def find_jdk() -> Path:
    """找一个能跑本项目的 JDK（Java 25）。

    顺序照 `desktop/启动.cmd` 的第 (0) 步：`desktop/java-home.txt` 是本机覆盖，
    其次 JAVA_HOME，最后在标准的 Adoptium / Java 安装根下挑一个版本够高的。
    找不到就报清楚「装/指一个 JDK 25」—— 悄悄退回 JAVA_HOME 会死在
    `--sun-misc-unsafe-memory-access` 那句「Unrecognized option」上，看不出跟 JDK 有关。
    """
    def usable(p: Path) -> bool:
        return (p / "bin" / "java.exe").is_file() and (p / "bin" / "jlink.exe").is_file()

    def major(p: Path) -> int:
        r = run([str(p / "bin" / "java.exe"), "-version"], capture=True)
        m = re.search(r'version "(\d+)', (r.stderr or "") + (r.stdout or ""))
        return int(m.group(1)) if m else 0

    candidates: list[Path] = []
    marker = ROOT / "desktop" / "java-home.txt"
    if marker.is_file():
        candidates.append(Path(marker.read_text(encoding="utf-8").strip()))
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    for base in (Path(r"C:\Program Files\Eclipse Adoptium"),
                 Path(r"C:\Program Files\Java"),
                 Path(r"C:\Program Files\Microsoft")):
        if base.is_dir():
            candidates += sorted(base.glob("jdk*"))

    ok = [c for c in candidates if usable(c)]
    for c in ok:
        if major(c) >= 25:
            say(f"JDK：{c}（Java {major(c)}）")
            return c
    raise SystemExit(
        "找不到 Java 25 的 JDK（要的是能编译本项目的那一个，不是只跑 java 的运行时）。\n"
        "装一个 Temurin 25，或者把 JDK 根目录写进 desktop/java-home.txt 一行。\n"
        "试过这些：" + ("、".join(str(c) for c in candidates) or "（一个都没有）"))


def build_release_config() -> dict[str, str]:
    """生成交付用的两份配置，返回「清理后的这份长什么样」的扁平字典（供 diff）。

    **不含发版人本机的任何路径与密钥**，这是我们唯一的底线：这份文件是要发给别人的。
    """
    return {
        # 裁剪的介质就是这一份：留哪个模块，哪一段的 enabled: true 就在里面。
        # 漫画整段不写 —— application.yaml 里没有 nya-entworks: 段，所以「没写」等于「没有」。
        "nya-entworks.song.enabled": "true",
        "nya-entworks.shout.enabled": "true",
        # 本机路径一律显式置空 —— 「空 = 没配」是既定口径（见 SongProperties#songDir），
        # 用户在配置页填自己的。
        "nya-entworks.song.song-dir": "''",
        "nya-entworks.song.staging-dir": "''",
        "nya-entworks.song.template-dir": "''",
        "nya-entworks.song.only-original-dir": "''",
        "nya-entworks.song.original-retired-dir": "''",
        "nya-entworks.shout.archived-dir": "''",
        "nya-entworks.shout.staging-dir": "''",
        "nya-entworks.common.local-ai.base-url": "''",
        "nya-entworks.common.local-ai.model": "''",
    }


def render_nya_entworks_yaml() -> str:
    flat = build_release_config()

    def emit(prefix: str, indent: str, out: list[str]) -> None:
        # 按 key 的第二段（模块名 / common）分组，组内再按剩余路径展开。
        # 只有这十几项，手写展开比引一个 YAML 库清楚（项目里没有任何第三方 Python 依赖）。
        pass

    return f"""\
# 呜啊娱乐工坊 —— 本机配置（覆盖层）。
#
# 这个文件是**本机状态**，不是出厂值：里面的路径与开关是这台机器上要用的，
# 换个地方就按自己的情况改。左边的「系统配置」页面改的就是它。
#
# 它同时是**模块裁剪的介质**：留哪个模块，就在下面留下哪一段的 enabled: true；
# 整段不写的模块在这份构建里**根本不存在**（页面不画、接口不注册）。
# 所以别把 manga 那段「补回来」—— 补回来它就会从页面上冒出来。
#
# 「空 = 没配」：路径留空是合法状态，填了才生效。填完要**重启后端**。

nya-entworks:
  song:
    enabled: {flat['nya-entworks.song.enabled']}
    song-dir: {flat['nya-entworks.song.song-dir']}
    staging-dir: {flat['nya-entworks.song.staging-dir']}
    template-dir: {flat['nya-entworks.song.template-dir']}
    only-original-dir: {flat['nya-entworks.song.only-original-dir']}
    original-retired-dir: {flat['nya-entworks.song.original-retired-dir']}
  shout:
    enabled: {flat['nya-entworks.shout.enabled']}
    archived-dir: {flat['nya-entworks.shout.archived-dir']}
    staging-dir: {flat['nya-entworks.shout.staging-dir']}
  common:
    local-ai:
      base-url: {flat['nya-entworks.common.local-ai.base-url']}
      model: {flat['nya-entworks.common.local-ai.model']}
"""


RELEASE_YAML = """\
# 发出去那一份专用的：把数据源从 MySQL 换成 SQLite。
#
# 靠的是 Spring profile（启动命令多一个 --spring.profiles.active=release）。
# profile 专属文件的优先级高于 application.yaml，所以 datasource 被整体覆盖，
# MySQL 那串 URL 参数（nullCatalogMeansCurrent 等）跟着一起失效 —— SQLite 不需要它们。
#
# 路径按**工作目录**解析（壳把 cwd 指到 exe 所在目录），所以 ./data 就在 exe 旁边。
# 这一个文件的存在，就是「开发机保持 MySQL、发出去的是 SQLite」的全部代价。

spring:
  datasource:
    driver-class-name: org.sqlite.JDBC
    url: jdbc:sqlite:./data/nya_entworks.sqlite
    username: ""
    password: ""
  sql:
    init:
      # SQLite 不被 Spring 认作「嵌入式数据库」，默认的 embedded 模式会让建表脚本压根不跑
      # （症状是启动正常、查询全部报 no such table）。所以这里必须是 always。
      # 脚本里全是 CREATE ... IF NOT EXISTS，每次启动重跑无害。
      mode: always
      schema-locations: classpath:db/nya_entworks.sqlite.sql
"""

SECRET_YAML = """\
# 密钥文件 —— 发出去的这一份是**空值版**：cookie 与在线 AI 都留空。
# 空着不影响启动：网易云 VIP 渠道不用、在线 AI 分支被 enabled: false 挡住，其余功能照常。
#
# 需要的人自己往里填（填完重启后端）。这个文件不要提交进任何仓库。

nya-entworks:
  song:
    # 网易云登录 cookie（至少含 MUSU_U）。填了才启用「网易云 VIP 直链」渠道。
    netease-cookie: ""
  common:
    online-ai:
      # 在线 AI 填词。要一个 OpenAI 兼容地址；不开就只走本机 Ollama。
      enabled: false
      base-url: ""
      model: ""
      api-key: ""
"""

VERSION_MARKER_CONTENT = """\
这个文件夹是「呜啊娱乐工坊」的一个可发布版本。
双击「呜啊娱乐工坊-{version}.exe」即可使用，不用安装。

· 数据（数据库与你的配置）就放在这个文件夹里：
    data/nya_entworks.sqlite   数据库
    config/                              配置
  所以整个文件夹拷到别处、整个删掉，都是安全的；升级时把这两处留下、其余换掉即可。

· 第一次打开请去左下角「系统配置」把自己机器上的目录填上（歌曲根、待打分根、模板根…），
  填完点「保存并重启后端」。
"""


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def write_config_files(config_dir: Path) -> None:
    """把交付用的三份配置写进 `<某处>/config/`。

    三份都是**生成**的（不是拷仓库里的现成文件），因为它们的共同要求是「不含发版人
    本机的任何路径与密钥」—— 这件事只有生成才守得住。成品文件夹、随壳打包的 staged、
    以及冒烟用的临时工作区，都从这一处出。
    """
    write_text(config_dir / "nya-entworks.yaml", render_nya_entworks_yaml())
    write_text(config_dir / "application-secret.yaml", SECRET_YAML)
    write_text(config_dir / "application-release.yaml", RELEASE_YAML)


def make_empty_sqlite(path: Path, ddl: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        path.unlink()
    conn = sqlite3.connect(str(path))
    try:
        conn.executescript(ddl)
        conn.commit()
    finally:
        conn.close()


def previous_schema_version(current: str) -> tuple[str | None, Path | None]:
    """上一份库结构快照（bump_version.py minor 落的）。没有更早的版本就返回 (None, None)。"""
    if not SCHEMA_BACKUP.is_dir():
        return None, None
    def parts(v: str) -> tuple:
        return tuple(int(x) for x in v.split(".")) if re.fullmatch(r"\d+\.\d+\.\d+", v) else (0, 0, 0)
    cur = parts(current)
    older = [p.stem for p in SCHEMA_BACKUP.glob("*.sql") if parts(p.stem) < cur]
    if not older:
        return None, None
    newest = max(older, key=parts)
    return newest, SCHEMA_BACKUP / (newest + ".sql")


def read_flat_yaml(text: str) -> dict[str, str]:
    """把生成的那几份简单缩进 YAML 读成扁平的 `a.b.c` → 值。够用即可。"""
    out: dict[str, str] = {}
    stack: list[tuple[int, str]] = []
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        key, _, val = raw.strip().partition(":")
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = ".".join([p for _, p in stack] + [key.strip()])
        val = val.strip()
        if val:
            out[path] = val
        else:
            stack.append((indent, key.strip()))
    return out


def write_update_doc(path: Path, old_v: str, new_v: str,
                     schema_changes: list[str], config_changes: list[str],
                     alter_snippets: list[str]) -> None:
    body = [f"# {old_v} → {new_v} 结构与配置更新", "",
            "由 `tools/release.py` 在发版时自动比对生成。**只有变了才会有这个文件。**", ""]
    body.append("## 数据结构" if schema_changes else "## 数据结构（无变化）")
    body += [f"- {x}" for x in schema_changes] or ["- 无"]
    body.append("")
    body.append("## 配置项" if config_changes else "## 配置项（无变化）")
    body += [f"- {x}" for x in config_changes] or ["- 无"]
    body.append("")
    if alter_snippets:
        body += ["## 附录：开发库（MySQL）补结构的语句", "",
                 "发出去的包里库是每次按最新结构重建的，**不用跑这些**。",
                 "这一段是给发版人自己的开发库用的：抄进 `docs/sql/待执行/` 那条人工通道执行。", "",
                 "```sql", *alter_snippets, "```", ""]
    write_text(path, "\n".join(body) + "\n")


def alter_snippets_for(old: dict, new: dict) -> list[str]:
    """从结构差里拼出「加列 / 加索引」那一半的 MySQL ALTER（删的那一半不自动生成）。"""
    out: list[str] = []
    for t in sorted(set(old) & set(new)):
        o, n = old[t], new[t]
        for c in sorted(set(n["columns"]) - set(o["columns"])):
            out.append(f"ALTER TABLE `{t}` ADD COLUMN `{c}` {n['columns'][c]};")
        for c in sorted(set(o["columns"]) & set(n["columns"])):
            if o["columns"][c] != n["columns"][c]:
                out.append(f"-- 请人工确认（可能截断数据）：\n"
                           f"-- ALTER TABLE `{t}` MODIFY COLUMN `{c}` {n['columns'][c]};")
        for k in sorted(set(n["unique"]) - set(o["unique"])):
            out.append(f"-- 唯一键要自己起名（uk_<表>_<列>）：\n"
                       f"-- ALTER TABLE `{t}` ADD UNIQUE INDEX `uk_xxx`({k});")
        for k in sorted(set(n["indexes"]) - set(o["indexes"])):
            out.append(f"CREATE INDEX `{k}` ON `{t}`({n['indexes'][k]});")
    return out


def pack_portable(desktop: Path, dist: Path) -> None:
    """跑 electron-builder 出 portable exe。

    **两次尝试**：常规一次，失败再跳过「往 exe 里写资源」那一步
    （`signAndEditExecutable=false`）重来一次。

    为什么要留第二次：electron-builder 会下 winCodeSign 工具包，而那个包里带着 macOS
    用的**符号链接**（`darwin/*.dylib`）。在 Windows 上创建符号链接要么是管理员、要么开了
    开发者模式，普通账户解压会报「客户端没有所需的特权」，然后 electron-builder 会**反复
    重下重试**（2026-09-30 在这台机器上实测：卡了十几分钟仍在转，而 `.7z` 每次都下得下来、
    每次都在解压那一步死）。rcedit 只负责把图标与版本信息写进 exe，跳过它产物照样能跑 ——
    代价只有「exe 的图标是 Electron 默认那个、右键属性里的产品名是 Electron」这两点；
    窗口标题、任务栏名字、数据目录取的都是 app 自己的名字（package.json 的 productName），
    仍然是「呜啊娱乐工坊」。
    """
    env = dict(os.environ)
    env.setdefault("ELECTRON_BUILDER_BINARIES_MIRROR", BINARIES_MIRROR)
    say(f"工具包镜像：{env['ELECTRON_BUILDER_BINARIES_MIRROR']}")

    attempts = [
        ("", ["npx", "electron-builder", "--win", "portable"]),
        ("（跳过写 exe 资源 —— 上一轮多半是 winCodeSign 解不出符号链接）",
         ["npx", "electron-builder", "--win", "portable",
          "-c.win.signAndEditExecutable=false"]),
    ]
    code = 0
    for note, argv in attempts:
        if note:
            say("重试一次" + note + "……")
            # 清干净再来：上一轮的 dist 是半成品，留着容易拿到旧文件
            shutil.rmtree(dist, ignore_errors=True)
        code = run(argv, cwd=desktop, env=env).returncode
        if code == 0:
            return
    raise SystemExit(
        f"electron-builder 两次都失败（最后一次退出码 {code}）。\n"
        "  看上面那几行下载日志：若是 `dial tcp ...: connectex` 之类的**连接超时**，\n"
        "  那是网络到不了 GitHub，换一个镜像再跑 ——\n"
        "     set ELECTRON_BUILDER_BINARIES_MIRROR=<以 / 结尾的镜像地址>\n"
        f"  （本脚本的默认值是 {BINARIES_MIRROR}）")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(add_help=True, usage=__doc__)
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--emit-sqlite-ddl", action="store_true")
    ap.add_argument("--emit-config", metavar="DIR",
                    help="只把三份交付配置写进 DIR/config/ 然后退出"
                         "（冒烟测试用：临时工作区放上它就能起一个 SQLite 实例）")
    ap.add_argument("--no-build", action="store_true", help="复用 target 里现成的 jar")
    ap.add_argument("--jar", metavar="PATH",
                    help="不构建、也不用 target 里那个，直接拿这一个 jar 去打包"
                         "（适合别名的地方编好了递过来 —— 比如 target 里的 jar 正被"
                         "运行中的桌面壳占着、mvn 改不了名的时候）")
    ap.add_argument("--no-shell", action="store_true", help="不做 jlink / electron-builder")
    ap.add_argument("--release-dir", default=str(DEFAULT_RELEASE_DIR))
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    release_dir = Path(args.release_dir)

    # --- 1. SQLite 建表脚本（必须在 mvn 之前：它要被打进 jar）---
    sqlite_ddl = to_sqlite_ddl(MYSQL_DDL.read_text(encoding="utf-8"))
    write_text(SQLITE_DDL, sqlite_ddl)
    say(f"SQLite 建表脚本已生成 → {SQLITE_DDL.relative_to(ROOT)}"
        f"（{len(KEEP_TABLES)} 张表，裁掉漫画）")
    if args.emit_sqlite_ddl:
        return 0
    if args.emit_config:
        write_config_files(Path(args.emit_config) / "config")
        say(f"三份交付配置已生成 → {Path(args.emit_config) / 'config'}")
        return 0

    sys.path.insert(0, str(ROOT / "tools"))
    import bump_version                                     # noqa: PLC0415 —— 同目录的兄弟脚本
    version = bump_version.format_version(bump_version.current_version())
    say(f"版本号 {version}（真源 pom.xml）")

    # 交付目录里那个同名 exe 要是正跑着（上一版没关），**最后一步**才炸 ——
    # portable 单文件运行时会把源 exe 占住，`shutil.copy2` 于是报 WinError 32。
    # 那要等 jlink + electron-builder 跑完四五分钟才轮到，所以先在这儿问一句。
    assert_free_to_replace(
        release_dir / f"{APP_NAME}-{version}.exe",
        "  十有八九是**上一版还没关掉**：portable 一运行就会占住它自己那个文件。\n"
        f"  请先退出「{APP_NAME}」（任务栏那个窗口，或任务管理器里的同名进程），再跑本脚本。")

    jar = Path(args.jar) if args.jar else ROOT / "target" / f"nya-entworks-{version}.jar"
    jdk = find_jdk()
    env = dict(os.environ, JAVA_HOME=str(jdk))

    # --- 2. 打包后端 ---
    if args.jar:
        say(f"用指定的 jar：{jar}")
    elif args.no_build:
        say(f"跳过构建，用现成的 {jar.name}")
    else:
        assert_target_not_locked(ROOT / "target")
        say("构建后端（mvnw clean package -DskipTests）……")
        r = run(["mvnw.cmd", "clean", "package", "-DskipTests"], env=env)
        if r.returncode != 0:
            raise SystemExit("后端构建失败，未生成可发布版")
    if not jar.is_file():
        raise SystemExit(f"没有找到 {jar} —— 构建没成功？")
    assert_jar_carries_sqlite_ddl(jar)

    # --- 3. 成品文件夹：数据 / 配置 / 版本标记 ---
    release_dir.mkdir(parents=True, exist_ok=True)
    db_path = release_dir / "data" / "nya_entworks.sqlite"
    make_empty_sqlite(db_path, sqlite_ddl)
    say(f"空库已生成 → {db_path}")

    write_config_files(STAGED / "config")
    write_config_files(release_dir / "config")
    say(f"配置已生成 → {release_dir / 'config'}（只有歌曲 + 喊麦，路径全空，无密钥）")

    write_text(release_dir / (DEFAULT_VERSION_MARKER + version),
               VERSION_MARKER_CONTENT.format(version=version))
    say(f"版本标记 → {DEFAULT_VERSION_MARKER}{version}")

    # --- 4. 结构 / 配置更新说明（**变了才写**）---
    old_v, old_path = previous_schema_version(version)
    schema_changes: list[str] = []
    alter_snippets: list[str] = []
    old_structure = None
    if old_path is not None:
        old_structure = parse_mysql_structure(old_path.read_text(encoding="utf-8"))
        new_structure = parse_mysql_structure(MYSQL_DDL.read_text(encoding="utf-8"))
        schema_changes = diff_structure(old_structure, new_structure)
        alter_snippets = alter_snippets_for(old_structure, new_structure)

    old_cfg_dir = RELEASE_BACKUP / old_v / "config" if old_v else None
    config_changes: list[str] = []
    if old_cfg_dir is not None and (old_cfg_dir / "nya-entworks.yaml").is_file():
        config_changes = diff_config(
            read_flat_yaml((old_cfg_dir / "nya-entworks.yaml").read_text(encoding="utf-8")),
            read_flat_yaml(render_nya_entworks_yaml()))

    if old_v is None:
        say("没有更早的库结构快照（.local-backup/schema/），跳过结构比对")
    elif schema_changes or config_changes:
        doc = release_dir / UPDATE_DIR_NAME / f"{old_v}->{version}结构更新.md"
        write_update_doc(doc, old_v, version, schema_changes, config_changes, alter_snippets)
        say(f"结构与配置有变化，已生成 → {doc}"
            f"（结构 {len(schema_changes)} 条 / 配置 {len(config_changes)} 条）")
    else:
        say("结构与配置都没变，不生成「结构更新」文件")

    # 归档这一版的清理配置，下次发版拿它比配置
    backup_cfg = RELEASE_BACKUP / version / "config"
    backup_cfg.mkdir(parents=True, exist_ok=True)
    shutil.copy2(release_dir / "config" / "nya-entworks.yaml", backup_cfg / "nya-entworks.yaml")

    if args.no_shell:
        say("（--no-shell：不做 jlink 与 electron-builder）")
        say(f"完成：{release_dir}")
        return 0

    # --- 5. jlink 裁剪 JRE ---
    jre = STAGED / "jre"
    if jre.exists():
        shutil.rmtree(jre)
    jre.parent.mkdir(parents=True, exist_ok=True)
    say("jlink 裁剪 JRE（几十兆，比整个 JDK 小得多）……")
    r = run([str(jdk / "bin" / "jlink.exe"),
             "--add-modules",
             # 少一个模块 = 运行期才炸，而且报错看不出跟 jlink 有关：
             #   java.sql(JDBC) / jdk.unsupported(sqlite-jdbc 的 System.load) /
             #   jdk.crypto.ec(TLS) / java.net.http(HttpClient) / java.management(Spring 的运行时信息)
             ",".join(["java.base", "java.logging", "java.sql", "java.naming", "java.management",
                       "java.instrument", "java.desktop", "java.security.jgss", "java.net.http",
                       "java.xml", "jdk.unsupported", "jdk.crypto.ec", "jdk.zipfs", "java.compiler",
                       "jdk.crypto.cryptoki"]),
             "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
             "--output", str(jre)])
    if r.returncode != 0:
        raise SystemExit("jlink 失败")

    # --- 6. jar 与图标就位 ---
    STAGED.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar, STAGED / "app.jar")
    say(f"jar → {(STAGED / 'app.jar').relative_to(ROOT)}")

    icon_png = ROOT / "desktop" / "icon.png"
    icon_ico = ROOT / "desktop" / "build" / "icon.ico"
    if icon_png.is_file():
        try:
            from PIL import Image                            # noqa: PLC0415
            icon_ico.parent.mkdir(parents=True, exist_ok=True)
            Image.open(icon_png).save(icon_ico, sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
            say("图标 → desktop/build/icon.ico")
        except ImportError:
            if not icon_ico.is_file():
                raise SystemExit(
                    "要生成 Windows 用的 .ico，装一下 Pillow（pip install pillow），"
                    "或者手工放一份 desktop/build/icon.ico")
            say("没装 Pillow，用现成的 desktop/build/icon.ico")

    # --- 7. electron-builder ---
    say("electron-builder 打包 portable exe……")
    desktop = ROOT / "desktop"
    # 判据要看 electron-builder 自己那个目录：node_modules 在装上 electron 时就有了，
    # 拿它当「依赖齐了」会让第一次发版停在 npx 的「找不到命令」上。
    if not (desktop / "node_modules" / "electron-builder").is_dir():
        say("装依赖（npm install，第一次要几分钟）……")
        if run(["npm", "install", "--no-audit", "--no-fund"], cwd=desktop).returncode != 0:
            raise SystemExit("npm install 失败")

    dist = desktop / "dist"
    # 先清干净：留着上一版的 exe，下面按名字取也不会拿错，但 dist 里躺着旧版本
    # 本身就是下次排查「怎么还是旧界面」的坑
    if dist.is_dir():
        shutil.rmtree(dist, ignore_errors=True)

    pack_portable(desktop, dist)

    # ★ 按**名字**取，不 glob()[0]：名字对不上就是配置漂了（artifactName），
    #   这时候要炸出来，而不是把碰巧先枚举到的那个文件当成品发出去
    expected = dist / f"呜啊娱乐工坊-{version}.exe"
    if not expected.is_file():
        found = [p.name for p in dist.glob("*.exe")] if dist.is_dir() else []
        raise SystemExit(f"没生成 {expected.name}，dist 里只有 {found or '（空）'}"
                         " —— 查 desktop/package.json 的 build.portable.artifactName")
    target = release_dir / expected.name
    try:
        shutil.copy2(expected, target)
    except PermissionError as exc:
        # 前面已经查过一次，这里是「跑这四五分钟里有人把上一版打开了」的那条缝 ——
        # 不留一句 Python 回溯，照样把该关什么说清楚。
        raise SystemExit(
            f"复制不到 {target}（{exc.strerror or exc}）—— 那个文件正被占着，\n"
            f"  多半是刚才有人打开了「{APP_NAME}」。关掉它再跑一次本脚本即可，\n"
            f"  新打包的成品还在 {expected}，没白跑。")
    say(f"exe → {target}")

    say(f"完成：{release_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
