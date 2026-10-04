#!/usr/bin/env bash
# 组装交付物 dist/：server fat-jar + stdio 桥 jar + 插件 zip + 示例配置
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p dist
cp arkrag-server/target/arkrag-server.jar dist/
cp arkrag-mcp-stdio/target/arkrag-mcp-stdio-jar-with-dependencies.jar dist/arkrag-mcp-stdio.jar
chmod +x scripts/*.sh
# 插件 zip（plugin.json 在包根，符合 ArkWork zip 安装约束）
rm -f dist/arkrag-plugin-1.1.1.zip
(cd plugin/arkrag && zip -qr ../../dist/arkrag-plugin-1.1.1.zip .)
echo "== dist/ =="
ls -la dist/
echo "== 插件 zip 内容 =="
unzip -l dist/arkrag-plugin-1.1.1.zip
