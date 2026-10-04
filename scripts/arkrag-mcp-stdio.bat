@echo off
rem ArkRAG MCP stdio 桥启动器（Windows；配置 ArkWork mcp-servers.json 时指向本文件）
if not exist "%~dp0..\dist\arkrag-mcp-stdio.jar" (
  echo 找不到 arkrag-mcp-stdio.jar，请先构建 & exit /b 1
)
java -jar "%~dp0..\dist\arkrag-mcp-stdio.jar"
