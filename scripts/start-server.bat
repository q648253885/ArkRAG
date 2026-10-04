@echo off
rem ArkRAG 服务启动脚本（Windows）
if not exist "%~dp0..\dist\arkrag-server.jar" (
  echo 找不到 arkrag-server.jar，请先构建：mvnw -DskipTests package
  exit /b 1
)
java -jar "%~dp0..\dist\arkrag-server.jar"
