#!/usr/bin/env bash
# 兼容原入口；完整备份包含数据库与聊天附件，默认永久保留。
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIR/backup.py" backup "$@"
