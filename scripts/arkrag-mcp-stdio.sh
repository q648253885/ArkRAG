#!/usr/bin/env bash
# ArkRAG MCP stdio 桥启动器 —— 配置在 ArkWork 的 mcp-servers.json：
#   { "id": "arkrag", "transport": "stdio",
#     "command": "<本项目>/scripts/arkrag-mcp-stdio.sh",
#     "env": { "ARKRAG_TOKEN": "secret:arkragToken" } }
# secrets.json 里登记 { "arkragToken": "<与服务端一致的 token>" }。
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/../dist/arkrag-mcp-stdio.jar"
[ -f "$JAR" ] || JAR="$DIR/../arkrag-mcp-stdio/target/arkrag-mcp-stdio-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "找不到 arkrag-mcp-stdio jar，请先构建" >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "错误：未找到 java（需 JDK 21+）" >&2; exit 2; }
exec java -jar "$JAR"
