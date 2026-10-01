#!/usr/bin/env python
"""数据库直连入口 —— AI 操作 MySQL 的唯一通道。

存在的理由：把「哪些 SQL 可以直接跑」这条约定做成机械护栏，而不是每次靠自觉。
本脚本之外的裸调用（直接敲 mysql.exe）能绕过护栏，所以 `.claude/settings.local.json`
只放行本脚本、**不放行裸 mysql 调用** —— 那一道是权限弹窗。

护栏规则（`guard()`，用例见 `--selftest`）：

  1. 逐条语句看首个动词。UPDATE / DELETE / DROP / TRUNCATE / REPLACE / RENAME / GRANT /
     REVOKE / LOAD / FLUSH / KILL / LOCK / SHUTDOWN 一律拒绝 —— 这些按约定要整理成
     SQL 文件由人工执行，落到 `docs/sql/待执行/`。
  2. ALTER 放行（加列 / 改类型 / 加索引），但 ALTER 里的任何 DROP 子句拒绝 ——
     `DROP COLUMN` 是删数据，属于第 1 条的范围，只是换了层语法外壳。
  3. 目标表若在 MIRROR_TABLES 里：
     - `INSERT` 一律拒绝。库是磁盘的镜像（CLAUDE.md「文件系统是权威，库是镜像」），
       手写行会被下次同步覆盖，或造出指向不存在路径的失联行。
     - `ALTER` **只放行「只加不删」的结构变更**（`ADD` / `MODIFY` / `CHANGE` /
       `RENAME COLUMN` / `COMMENT` / `CONVERT TO` / `ENGINE` 这几个动作；DROP 子句
       照旧全拒）。理由是「同步写的是**行**，不是列」—— 补列不跟同步抢数据，
       而补列恰恰是建表脚本里最常见的动作。改类型理论上能改窄导致截断，但
       `ALTER` 本来就属于「AI 直接做」那一档，不比现状更宽。
     加数据只对磁盘表达不出来的设置 / 词典类表开放。
  4. `-q` 只读模式只放行 SELECT / SHOW / DESC / EXPLAIN / WITH / TABLE / VALUES。
  5. 写语句（INSERT / ALTER）执行前自动 mysqldump 目标表到 `.local-backup/db/`，
     dump 失败即中止 —— 这是唯一能兜住「INSERT 打错又不可回滚」的东西。

护栏的方向是「宁可漏报、不能误报」：`strip_noise()` 先剥掉注释与字符串字面量，
这只会让关键字匹配变少。漏报的兜底是第 5 条（备份）与人工复核。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
APP_YAML = PROJECT_ROOT / "src" / "main" / "resources" / "application.yaml"
BACKUP_DIR = PROJECT_ROOT / ".local-backup" / "db"
PENDING_REL = "docs/sql/待执行"
KEEP_BACKUPS_PER_TABLE = 20

# 与 Maven 无关，只在本机定位客户端。装到别处就改这里，或用环境变量覆盖。
MYSQL_BIN_DIR = Path(os.environ.get("MYSQL_BIN_DIR", r"C:\Program Files\MySQL\MySQL Server 8.0\bin"))

# 磁盘镜像表：库跟着磁盘走，不跟着人走。手写 INSERT / ALTER 一律拒绝。
# 想临时放开就改这一行 —— 但先回头读 CLAUDE.md 的「文件系统是权威，库是镜像」。
MIRROR_TABLES = {
    "manga_data",          # 漫画资源表（由归档同步写入）
    "manga_archive_unit",  # 漫画归档目录
    "manga_eh_scan",       # e-hentai 扫描结果
    "manga_tag_ref",       # 标签关联
    "song_group",          # 归档歌曲合并条目
    "song_file",           # 归档歌曲文件清单
    "shout_group",         # 归档喊麦条目
    "shout_file",          # 归档喊麦文件清单
    "async_task",          # 异步任务（由任务框架写状态机，手插行会让程序去跑不存在的任务）
    # 语料两张表虽然不是磁盘镜像，但由重扫任务整表删掉重建 —— 手写必被冲掉，
    # 所以按镜像口径挡（用户已确认，填词助手设计 §0.3.1 第 12 条）。
    "lyric_corpus_line",   # 语料：规范化歌词句（song.corpus.rescan 删掉重建）
    "lyric_corpus_pair",   # 语料：原词→新词配对（保存钩子 diff 写入）
}

DENY_VERBS = {
    "UPDATE", "DELETE", "DROP", "TRUNCATE", "REPLACE", "RENAME",
    "GRANT", "REVOKE", "LOAD", "FLUSH", "KILL", "LOCK", "UNLOCK",
    "SHUTDOWN", "RESET", "PURGE",
}
READONLY_VERBS = {"SELECT", "SHOW", "DESC", "DESCRIBE", "EXPLAIN", "WITH", "TABLE", "VALUES"}

INSERT_RE = re.compile(
    r"\binsert\s+(?:low_priority\s+|delayed\s+|high_priority\s+|ignore\s+)*(?:into\s+)?([`\w.]+)", re.I)
ALTER_RE = re.compile(r"\balter\s+(?:online\s+|ignore\s+)?table\s+([`\w.]+)", re.I)

# 镜像表上放行的 ALTER 动作 —— 「只加不删」的那几个。逐条动作比对，
# 有一条不在列就整句拒（宁可漏报的对面：这里宁可严）。
MIRROR_DDL_ACTIONS = ("add", "modify", "change", "rename column",
                      "comment", "convert to", "engine", "algorithm", "lock")


# ---------------------------------------------------------------- 配置

def load_db_config() -> dict:
    """从 application.yaml 读连接信息 —— 不在这儿存第二份凭据。

    只解析 `datasource:` 段，避免误取别的 yaml 段的 username/password。
    application-secret.yaml 不覆盖数据源，所以这里是唯一来源。
    """
    host, port, database = "127.0.0.1", "3306", "nya_entworks"
    user, password = None, None

    text = APP_YAML.read_text(encoding="utf-8") if APP_YAML.exists() else ""
    m = re.search(r"^\s*datasource:\s*$(.*?)(?=^\S|\Z)", text, re.M | re.S)
    seg = m.group(1) if m else text

    def pick(key, default=None):
        mm = re.search(rf"^\s*{key}:\s*(\S.*?)\s*$", seg, re.M)
        return mm.group(1).strip().strip("\"'") if mm else default

    user = pick("username") or user
    password = pick("password") or password

    url = pick("url", "")
    mm = re.search(r"//([^:/]+):(\d+)/([^?]+)", url)
    if mm:
        host, port, database = mm.group(1), mm.group(2), mm.group(3)

    return {
        "host": os.environ.get("NYA_DB_HOST", host),
        "port": os.environ.get("NYA_DB_PORT", port),
        "database": os.environ.get("NYA_DB_NAME", database),
        "user": os.environ.get("NYA_DB_USER", user or "root"),
        "password": os.environ.get("NYA_DB_PASSWORD", password or ""),
    }


# ---------------------------------------------------------------- 护栏

def strip_noise(sql: str) -> str:
    """剥掉注释与字符串字面量，留下关键字骨架。

    反引号包的是标识符（表名），**保留内容**只去引号 —— 否则镜像表检测就瞎了。
    单/双引号是字符串，整体替换成一个空格：字符串里出现 `delete` 不该误报，
    方向上是「漏报」而不是「误报」，安全。
    """
    out, i, n = [], 0, len(sql)
    while i < n:
        c = sql[i]
        if (sql.startswith("--", i) and (i + 2 >= n or sql[i + 2] in " \t\r\n")) or c == "#":
            j = sql.find("\n", i)
            i = n if j == -1 else j + 1
            continue
        if sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            i = n if j == -1 else j + 2
            continue
        if c == "`":
            i += 1
            while i < n and sql[i] != "`":
                out.append(sql[i])
                i += 1
            i += 1
            continue
        if c in ("'", '"'):
            q, i = c, i + 1
            while i < n:
                if sql[i] == "\\" and q != "`":
                    i += 2
                    continue
                if sql[i] == q:
                    if i + 1 < n and sql[i + 1] == q:  # 双写转义
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            out.append(" ")
            continue
        out.append(c)
        i += 1
    return "".join(out)


def split_statements(clean: str) -> list[str]:
    return [s.strip() for s in clean.split(";") if s.strip()]


def statement_verb(stmt: str) -> str:
    m = re.match(r"\s*\(*\s*([A-Za-z_]+)", stmt)
    return m.group(1).upper() if m else ""


def target_table(stmt: str, verb: str) -> str | None:
    pat = INSERT_RE if verb == "INSERT" else ALTER_RE if verb == "ALTER" else None
    if pat is None:
        return None
    m = pat.search(stmt)
    return m.group(1).split(".")[-1].strip("`") if m else None


def split_alter_actions(rest: str) -> list[str]:
    """把 `ALTER TABLE t` 之后的部分按**顶层**逗号切开（括号里的逗号不算）。

    `ALTER TABLE t ADD COLUMN a INT, MODIFY COLUMN b VARCHAR(50)`
      → ["ADD COLUMN a INT", "MODIFY COLUMN b VARCHAR(50)"]
    """
    out: list[str] = []
    buf: list[str] = []
    depth = 0
    for ch in rest:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        if ch == "," and depth == 0:
            out.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    out.append("".join(buf))
    return [a for a in (s.strip() for s in out) if a]


def mirror_alter_reason(stmt: str) -> str | None:
    """镜像表上的 ALTER 是「只加不删」的结构变更吗？是则 None，否则给拒绝原因。"""
    m = ALTER_RE.search(stmt)
    actions = split_alter_actions(stmt[m.end():] if m else stmt)
    if not actions:
        return "看不出这句 ALTER 要做什么"
    for a in actions:
        if not any(a.lower().startswith(k) for k in MIRROR_DDL_ACTIONS):
            return (f"`{a[:60]}` 不是「只加不删」的结构变更 —— 镜像表上只放行 "
                    f"ADD / MODIFY / CHANGE / RENAME COLUMN / COMMENT / CONVERT TO / ENGINE")
    return None


def guard(sql: str, readonly: bool = False) -> tuple[bool, str]:
    """放行返回 (True, "")；拒绝返回 (False, 给人看的原因)。"""
    stmts = split_statements(strip_noise(sql))
    if not stmts:
        return False, "空语句"

    for stmt in stmts:
        verb = statement_verb(stmt)
        if not verb:
            return False, f"看不懂的语句：{stmt[:60]}"

        # WITH ... 后面可以跟 UPDATE / DELETE，别被开头的 WITH 骗过去
        if verb == "WITH" and re.search(
                r"\b(update|delete|insert|replace|drop|truncate|rename|alter)\b", stmt, re.I):
            verb = "UPDATE"

        if readonly and verb not in READONLY_VERBS:
            return False, f"只读模式（-q）不接受 {verb} 语句"

        if verb in DENY_VERBS:
            return False, (
                f"{verb} 属于删改语句，按约定要整理成 SQL 文件由人工执行。\n"
                f"  写入：{PENDING_REL}/YYYY-MM-DD_用途.sql\n"
                f"  片段：{stmt[:120]}")

        if verb == "ALTER":
            m = re.search(r"\bdrop\s+(column|index|key|primary\s+key|foreign\s+key|constraint|partition|check)\b",
                          stmt, re.I)
            if m:
                return False, (f"ALTER 里的 `{m.group(0)}` 是删除操作，按约定走 SQL 文件人工执行。\n"
                               f"  片段：{stmt[:120]}")

        if verb == "SET" and re.search(r"\bset\s+(global|persist|@@global)", stmt, re.I):
            return False, "SET GLOBAL / PERSIST 影响整个实例，走 SQL 文件人工执行"

        if verb == "CREATE" and re.search(r"\bcreate\s+(user|role|trigger|event|procedure|function)\b", stmt, re.I):
            return False, "CREATE USER / TRIGGER / EVENT / PROCEDURE 超出「建表」的范围，走 SQL 文件"

        if verb in ("INSERT", "ALTER"):
            tbl = target_table(stmt, verb)
            if tbl and tbl.lower() in MIRROR_TABLES:
                if verb == "INSERT":
                    return False, (
                        f"`{tbl}` 是磁盘镜像表（CLAUDE.md：文件系统是权威，库是镜像），不能手写行。\n"
                        f"  正确做法：改磁盘，再跑对应模块的同步；改库会被下次同步覆盖。\n"
                        f"  片段：{stmt[:120]}")
                # ALTER：同步写的是行、不是列，所以「只加不删」的结构变更放行（补列不抢数据）
                reason = mirror_alter_reason(stmt)
                if reason:
                    return False, (
                        f"`{tbl}` 是磁盘镜像表，只放行「只加不删」的结构变更：{reason}\n"
                        f"  片段：{stmt[:120]}")

    return True, ""


# ---------------------------------------------------------------- 执行

def _esc_cnf(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def _defaults_file(cfg: dict, path: str) -> None:
    """密码走临时 cnf 文件，不走命令行 —— 不进进程列表、不进 shell 历史。"""
    Path(path).write_text(
        "[client]\n"
        f"host={cfg['host']}\n"
        f"port={cfg['port']}\n"
        f"user={cfg['user']}\n"
        f'password="{_esc_cnf(cfg["password"])}"\n'
        "default-character-set=utf8mb4\n",
        encoding="utf-8")


def _client(tool: str) -> str:
    exe = MYSQL_BIN_DIR / f"{tool}.exe"
    if not exe.exists():
        sys.exit(f"找不到 {exe}。装到别处请设环境变量 MYSQL_BIN_DIR。")
    return str(exe)


def _run_client(tool: str, cfg: dict, args: list[str], stdin_text: str | None = None) -> int:
    with tempfile.NamedTemporaryFile("w", suffix=".cnf", delete=False, encoding="utf-8") as f:
        cnf = f.name
    try:
        _defaults_file(cfg, cnf)
        cmd = [_client(tool), f"--defaults-extra-file={cnf}"] + args
        data = stdin_text.encode("utf-8") if stdin_text is not None else None
        proc = subprocess.run(cmd, input=data, capture_output=True)
        if proc.stdout:
            sys.stdout.write(proc.stdout.decode("utf-8", "replace"))
        if proc.stderr:
            sys.stderr.write(proc.stderr.decode("utf-8", "replace"))
        return proc.returncode
    finally:
        os.unlink(cnf)


def backup_tables(tables: set[str], cfg: dict) -> None:
    """写操作前的自动备份。失败即抛 —— 宁可不执行，也不要在没有退路的情况下改库。"""
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    for t in sorted(tables):
        out = BACKUP_DIR / f"{t}_{stamp}.sql"
        with tempfile.NamedTemporaryFile("w", suffix=".cnf", delete=False, encoding="utf-8") as f:
            cnf = f.name
        try:
            _defaults_file(cfg, cnf)
            with open(out, "wb") as fh:
                p = subprocess.run(
                    [_client("mysqldump"), f"--defaults-extra-file={cnf}",
                     "--single-transaction", "--quick", "--skip-lock-tables",
                     "--default-character-set=utf8mb4", cfg["database"], t],
                    stdout=fh, stderr=subprocess.PIPE)
            if p.returncode != 0:
                out.unlink(missing_ok=True)
                raise RuntimeError(f"备份 {t} 失败：{p.stderr.decode('utf-8', 'replace')}")
        finally:
            os.unlink(cnf)
        print(f"[备份] {t} → {out.relative_to(PROJECT_ROOT)}")
        _prune(BACKUP_DIR, t)


def _prune(directory: Path, table: str) -> None:
    files = sorted(directory.glob(f"{table}_*.sql"))
    for old in files[:-KEEP_BACKUPS_PER_TABLE]:
        old.unlink(missing_ok=True)


# 表选项里那个 `AUTO_INCREMENT=1903452`（等号 + 数字）。列定义里的 `AUTO_INCREMENT`
# 不带等号，所以这条只咬表选项那一个，列定义不会被动到。
AUTO_INCREMENT_RE = re.compile(r"\s+AUTO_INCREMENT=\d+")


def strip_auto_increment(text: str) -> str:
    """去掉 CREATE TABLE 尾部那个 `AUTO_INCREMENT=NNNN`。

    它是**行数**的痕迹，不是结构。留着的话，两份只差数据的结构备份会 diff 出一堆
    与结构无关的噪音 —— 而这些按版本留档的备份，价值恰恰在于能一眼 diff 出版本之间
    结构改了什么。用例见 `SCHEMA_SELFTEST_CASES`。
    """
    return AUTO_INCREMENT_RE.sub("", text)


def dump_schema(target: str | None, cfg: dict) -> int:
    """整库**结构**导出（`mysqldump --no-data`）：只有建表语句，不含任何行数据。

    两个调用方：`tools/bump_version.py` 发版（minor）时留一份到 `.local-backup/schema/`；
    以及手工排查 / 比对时直接跑 `python tools/mysql.py --dump-schema out.sql`。

    密码仍走临时 cnf（见 `_defaults_file`），不进命令行也不进进程列表。
    """
    with tempfile.NamedTemporaryFile("w", suffix=".cnf", delete=False, encoding="utf-8") as f:
        cnf = f.name
    try:
        _defaults_file(cfg, cnf)
        p = subprocess.run(
            [_client("mysqldump"), f"--defaults-extra-file={cnf}",
             "--single-transaction", "--skip-lock-tables",
             "--no-data", "--skip-comments", "--skip-dump-date",
             "--default-character-set=utf8mb4", cfg["database"]],
            capture_output=True)
    finally:
        os.unlink(cnf)
    if p.returncode != 0:
        sys.stderr.write(p.stderr.decode("utf-8", "replace"))
        return p.returncode

    # mysqldump 在 Windows 上吐 CRLF，而仓库里的 .sql 一律是 LF
    # （src/main/resources/db/nya_entworks.sql 就是）—— 统一过来。
    # 发版快照虽然不进 git，但两份快照要能对着比（`diff .local-backup/schema/*.sql`），
    # 换行风格跟着平台变就白比了。
    # 再去掉 --skip-comments 之后开头剩的那个空行，让文件第一行就是语句。
    text = p.stdout.decode("utf-8", "replace").replace("\r\n", "\n")
    text = strip_auto_increment(text).lstrip("\n")
    if target and target != "-":
        path = Path(target)
        if not path.is_absolute():
            path = PROJECT_ROOT / path
        path.parent.mkdir(parents=True, exist_ok=True)
        # 行尾固定 LF：与仓库里那份 .sql 一致，两次快照 diff 出来的才只有真改动
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        print(f"[结构] {cfg['database']} → {path}")
    else:
        sys.stdout.write(text)
    return 0


def execute(sql: str, cfg: dict, grid: bool, tsv: bool, no_backup: bool) -> int:
    ok, reason = guard(sql)
    if not ok:
        print(f"拒绝执行：{reason}", file=sys.stderr)
        return 3

    verbs = {statement_verb(s) for s in split_statements(strip_noise(sql))}
    if not no_backup and verbs & {"INSERT", "ALTER"}:
        tables = {t for s in split_statements(strip_noise(sql))
                  for t in [target_table(s, statement_verb(s))] if t}
        if tables:
            try:
                backup_tables(tables, cfg)
            except RuntimeError as e:
                print(f"拒绝执行：{e}", file=sys.stderr)
                return 4

    args = [cfg["database"]]
    if grid:
        pass
    else:
        args.append("-B")
        if tsv:
            args.append("-N")
    return _run_client("mysql", cfg, args, stdin_text=sql)


# ---------------------------------------------------------------- 自检

SELFTEST_CASES = [
    # (SQL, readonly, 期望放行?)
    ("SELECT * FROM manga_tag LIMIT 1", False, True),
    ("select 1", False, True),
    ("SHOW TABLES", False, True),
    ("EXPLAIN SELECT 1", False, True),
    ("DESC song_group", False, True),
    ("WITH x AS (SELECT 1 AS a) SELECT * FROM x", False, True),
    ("-- 注释里写 delete\nSELECT 1", False, True),
    ("SELECT 'delete from t' AS s", False, True),          # 字符串里的关键字不误报
    ("SET NAMES utf8mb4", False, True),
    ("CREATE TABLE IF NOT EXISTS t_new (id BIGINT PRIMARY KEY)", False, True),
    ("INSERT INTO manga_tag (name) VALUES ('x')", False, True),
    ("ALTER TABLE manga_tag ADD COLUMN remark VARCHAR(50)", False, True),
    ("ALTER TABLE manga_tag ADD INDEX idx_name (name)", False, True),
    ("SELECT 1; SELECT 2", False, True),

    ("DELETE FROM manga_data", False, False),
    ("delete from song_group where id=1", False, False),
    ("UPDATE song_tag SET name='x'", False, False),
    ("DROP TABLE manga_tag", False, False),
    ("TRUNCATE TABLE async_task", False, False),
    ("REPLACE INTO manga_tag (id) VALUES (1)", False, False),
    ("RENAME TABLE a TO b", False, False),
    ("SELECT 1; DROP TABLE manga_tag", False, False),       # 多语句里夹带
    ("ALTER TABLE manga_tag DROP COLUMN remark", False, False),
    ("ALTER TABLE song_group DROP INDEX idx_x", False, False),
    ("SET GLOBAL max_connections = 100", False, False),
    ("CREATE USER 'x'@'%'", False, False),
    ("INSERT INTO manga_data (id) VALUES (1)", False, False),    # 镜像表
    ("INSERT INTO `song_file` (id) VALUES (1)", False, False),   # 反引号也要认出来
    ("INSERT INTO shout_group (main_name) VALUES ('x')", False, False),  # 喊麦镜像表
    # 镜像表上的「只加不删」结构变更放行（补列不跟同步抢数据），用户 2026-09-15 拍板
    ("ALTER TABLE shout_file ADD COLUMN x INT", False, True),    # 喊麦镜像表
    ("ALTER TABLE async_task ADD COLUMN x INT", False, True),    # 镜像表
    ("ALTER TABLE manga_data MODIFY COLUMN artist VARCHAR(200)", False, True),
    ("ALTER TABLE manga_data ADD COLUMN a INT, ADD COLUMN b INT", False, True),
    ("ALTER TABLE manga_data ADD COLUMN score INT COMMENT '评分，含逗号, 与括号()'", False, True),
    ("ALTER TABLE manga_data ADD INDEX idx_x (artist(50))", False, True),
    ("ALTER TABLE manga_data ADD UNIQUE INDEX uk_x (artist, title)", False, True),
    ("ALTER TABLE manga_data MODIFY COLUMN artist VARCHAR(200) AFTER group_name", False, True),
    ("ALTER TABLE manga_data DROP COLUMN score", False, False),        # DROP 子句照旧拒
    ("ALTER TABLE manga_data ADD COLUMN a INT, DROP COLUMN b", False, False),
    ("ALTER TABLE manga_data RENAME TO manga_data_old", False, False), # 改名不是「只加」
    ("ALTER TABLE manga_data ENGINE = InnoDB", False, True),
    ("INSERT INTO song_original_setting (raw_name) VALUES ('x')", False, True),

    ("INSERT INTO manga_tag (name) VALUES ('x')", True, False),  # 只读模式
    ("SELECT 1", True, True),
]

# `--dump-schema` 那个文本后处理的用例（不是护栏，是结构备份的 diff 是否干净）。
# 正则写错的症状很轻：备份照出、版本照发，只是每份文件都带一段行数痕迹，
# 于是「两个版本之间结构改了什么」这个唯一的用途被噪音淹掉 —— 所以在这儿钉住。
SCHEMA_SELFTEST_CASES = [
    ("表选项里的 AUTO_INCREMENT 要去掉",
     ") ENGINE=InnoDB AUTO_INCREMENT=1903452 DEFAULT CHARSET=utf8mb4",
     ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
    ("列定义里的 AUTO_INCREMENT 不动（它不带等号）",
     "`id` bigint NOT NULL AUTO_INCREMENT,",
     "`id` bigint NOT NULL AUTO_INCREMENT,"),
    ("两者同现时只动表选项那一个",
     "`id` bigint NOT NULL AUTO_INCREMENT,\n) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4",
     "`id` bigint NOT NULL AUTO_INCREMENT,\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
    ("没有 AUTO_INCREMENT 时原样返回",
     "CREATE TABLE `t` (`a` int) ENGINE=InnoDB;",
     "CREATE TABLE `t` (`a` int) ENGINE=InnoDB;"),
]


def selftest() -> int:
    bad = 0
    for sql, readonly, want in SELFTEST_CASES:
        got = guard(sql, readonly)[0]
        if got != want:
            bad += 1
            flag = "readonly" if readonly else "write"
            print(f"FAIL [{flag}] 期望{'放行' if want else '拒绝'}，实际{'放行' if got else '拒绝'}：{sql!r}")
    for name, text, want in SCHEMA_SELFTEST_CASES:
        got = strip_auto_increment(text)
        if got != want:
            bad += 1
            print(f"FAIL {name}：期望 {want!r}，实际 {got!r}")
    total = len(SELFTEST_CASES) + len(SCHEMA_SELFTEST_CASES)
    if bad:
        print(f"\n{bad}/{total} 条不符")
        return 1
    print(f"ALL OK（{total} 条：护栏 {len(SELFTEST_CASES)} + 结构文本 {len(SCHEMA_SELFTEST_CASES)}）")
    return 0


# ---------------------------------------------------------------- CLI

def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    ap = argparse.ArgumentParser(
        prog="mysql.py", description="数据库直连入口（带删改护栏）",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=f"""
示例:
  python tools/mysql.py -q "SELECT COUNT(*) FROM manga_data"
  python tools/mysql.py -e "INSERT INTO manga_tag (name) VALUES ('x')"
  python tools/mysql.py -f src/main/resources/db/nya_entworks.sql
  python tools/mysql.py --dump manga_tag
  python tools/mysql.py --selftest

待执行 SQL 的落盘位置：{PENDING_REL}/
备份位置：.local-backup/db/（不进 git）
""")
    src = ap.add_mutually_exclusive_group()
    src.add_argument("-e", "--execute", metavar="SQL", help="执行一条 SQL（过护栏）")
    src.add_argument("-f", "--file", metavar="FILE", help="执行 .sql 文件（过护栏）")
    src.add_argument("-q", "--query", metavar="SQL", help="只读查询（只放行 SELECT/SHOW/DESC/EXPLAIN）")
    src.add_argument("--tables", action="store_true", help="列出库中所有表")
    src.add_argument("--desc", metavar="TABLE", help="查看表结构")
    src.add_argument("--dump", metavar="TABLE", help="备份单表到 .local-backup/db/")
    src.add_argument("--dump-schema", metavar="FILE", nargs="?", const="-",
                     help="整库结构（只有建表语句、不含数据）写到 FILE；省略 FILE 打到屏幕")
    src.add_argument("--selftest", action="store_true", help="跑护栏内置用例，不连库")

    ap.add_argument("--grid", action="store_true", help="对齐表格输出（给人看）")
    ap.add_argument("--tsv", action="store_true", help="制表符输出、不要表头行（给程序看）")
    ap.add_argument("--no-backup", action="store_true", help="写操作前不自动备份（默认备份）")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    cfg = load_db_config()

    if args.tables:
        return execute(
            "SELECT table_name AS 表, table_comment AS 说明 FROM information_schema.tables "
            f"WHERE table_schema='{cfg['database']}' ORDER BY table_name",
            cfg, args.grid, args.tsv, no_backup=True)

    if args.desc:
        if args.desc not in _known_tables(cfg):
            print(f"拒绝执行：库里没有表 {args.desc}", file=sys.stderr)
            return 3
        return execute(f"SHOW FULL COLUMNS FROM `{args.desc}`", cfg, args.grid, args.tsv, no_backup=True)

    if args.dump:
        backup_tables({args.dump}, cfg)
        return 0

    if args.dump_schema is not None:
        return dump_schema(args.dump_schema, cfg)

    if args.query:
        return execute(args.query, cfg, args.grid, args.tsv, no_backup=True)

    if args.file:
        p = Path(args.file)
        if not p.is_absolute():
            p = PROJECT_ROOT / p
        if not p.exists():
            print(f"拒绝执行：找不到 {p}", file=sys.stderr)
            return 2
        sql = p.read_text(encoding="utf-8")
    elif args.execute:
        sql = args.execute
    else:
        ap.print_help()
        return 2

    return execute(sql, cfg, args.grid, args.tsv, args.no_backup)


def _known_tables(cfg: dict) -> set[str]:
    """表名白名单校验用 —— 避免把用户输入直接拼进 SQL。"""
    with tempfile.NamedTemporaryFile("w", suffix=".cnf", delete=False, encoding="utf-8") as f:
        cnf = f.name
    try:
        _defaults_file(cfg, cnf)
        p = subprocess.run(
            [_client("mysql"), f"--defaults-extra-file={cnf}", "-N", "-B",
             "-e", f"SELECT table_name FROM information_schema.tables WHERE table_schema='{cfg['database']}'"],
            capture_output=True)
        return set(p.stdout.decode("utf-8", "replace").split())
    finally:
        os.unlink(cnf)


if __name__ == "__main__":
    sys.exit(main())
