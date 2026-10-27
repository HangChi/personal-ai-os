#!/usr/bin/env bash
# ============================================================
# 数据库备份（P3）
#
# 用法：
#   ./scripts/dev/backup-db.sh              # 备份一次
#   BACKUP_RETAIN=30 ./scripts/dev/backup-db.sh   # 只保留最近 30 份
#
# 建议每天跑一次（crontab 示例见 docs/operations.md）：
#   0 3 * * * cd /path/to/personal-ai-os && ./scripts/dev/backup-db.sh >> /tmp/paios-backup.log 2>&1
#
# 为什么用 pg_dump -Fc 而不是纯 SQL：
#   -Fc 是压缩的自定义格式，体积约为 SQL 的 1/5，且 pg_restore 支持
#   只恢复某几张表（真出事时这比「全量恢复」实用得多）。
# ============================================================
set -euo pipefail

CONTAINER="${DB_CONTAINER:-personal-ai-os-postgres}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETAIN="${BACKUP_RETAIN:-14}"

# 凭据与库名从 .env 取，与 compose 保持一致；取不到就用 compose 的默认值
if [[ -f .env ]]; then
  # shellcheck disable=SC1091
  set -a; source .env; set +a
fi
DB_NAME="${POSTGRES_DB:-personal_ai_os}"
DB_USER="${POSTGRES_USER:-personalos}"

mkdir -p "$BACKUP_DIR"

if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true; then
  echo "错误：容器 $CONTAINER 未运行。先 docker compose up -d" >&2
  exit 1
fi

STAMP="$(date -u +%Y%m%d-%H%M%S)"
TARGET="$BACKUP_DIR/personal-ai-os-$STAMP.dump"

echo "备份 $DB_NAME → $TARGET"
# -Fc：自定义压缩格式；在容器内执行，避免宿主机没装 psql 版本的坑
if ! docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$TARGET"; then
  echo "错误：pg_dump 失败" >&2
  rm -f "$TARGET"
  exit 1
fi

# 校验非空：pg_dump 偶尔会「成功退出但产出空文件」（如权限问题），
# 而空备份比没有备份更危险 —— 它看起来是成功的
if [[ ! -s "$TARGET" ]]; then
  echo "错误：备份文件为空，已删除" >&2
  rm -f "$TARGET"
  exit 1
fi

echo "完成：$(du -h "$TARGET" | cut -f1)"

# ---- 保留策略 ----
# 只用通配符删自己产出的文件，不做 rm -rf；文件名前缀是硬编码的，
# 即使 BACKUP_DIR 指错也不会误删别人的东西
COUNT=$(find "$BACKUP_DIR" -maxdepth 1 -name 'personal-ai-os-*.dump' -type f | wc -l | tr -d ' ')
if [[ "$COUNT" -gt "$RETAIN" ]]; then
  echo "清理旧备份（保留最近 $RETAIN 份）"
  find "$BACKUP_DIR" -maxdepth 1 -name 'personal-ai-os-*.dump' -type f \
    | sort -r | tail -n "+$((RETAIN + 1))" | while read -r old; do
        echo "  删除 $(basename "$old")"
        rm -f "$old"
      done
fi
