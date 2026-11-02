#!/usr/bin/env bash
# 宿主机 JAR 的统一 Docker 分组；环境文件由 Compose 解析，不执行 shell 文本。
set -euo pipefail
STACK_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
for STACK_ENV in .env .env.integrations.local .env.monitoring.local .env.local-stack; do
  if [[ ! -f "$STACK_ROOT/$STACK_ENV" ]]; then
    echo "缺少本地部署配置：$STACK_ENV" >&2
    exit 1
  fi
done
exec docker compose --project-name personal-ai-os --project-directory "$STACK_ROOT" \
  --env-file "$STACK_ROOT/.env" \
  --env-file "$STACK_ROOT/.env.integrations.local" \
  --env-file "$STACK_ROOT/.env.monitoring.local" \
  --env-file "$STACK_ROOT/.env.local-stack" \
  -f "$STACK_ROOT/compose.local.yml" "$@"
