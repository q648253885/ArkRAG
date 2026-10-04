#!/usr/bin/env bash
# ArkRAG 服务启动脚本（macOS/Linux）
# 用法：ARKRAG_TOKEN=xxx ./scripts/start-server.sh
# 或复制 scripts/application.local.yml 到数据目录后编辑（见 README）
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/../dist/arkrag-server.jar"
[ -f "$JAR" ] || JAR="$DIR/../arkrag-server/target/arkrag-server.jar"
[ -f "$JAR" ] || { echo "找不到 arkrag-server.jar，请先构建：./mvnw -DskipTests package"; exit 1; }

if ! command -v java >/dev/null 2>&1; then
  echo "错误：未找到 java。请安装 JDK 21+（https://adoptium.net）"; exit 2
fi
V=$(java -version 2>&1 | head -1)
echo "$V" | grep -qE '"(21|22|23|24|25)\.' || {
  echo "警告：检测到 $V。ArkRAG 需要 JDK 21+，低版本将无法启动。"
}
exec java -jar "$JAR"
