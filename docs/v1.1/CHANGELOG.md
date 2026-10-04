# CHANGELOG：ArkRAG v1.1

> 本版变更日志 ｜ 正本：docs/v1.0/04-system-design.md；增量：docs/v1.1/04-system-design-delta.md

## v1.1.0（2026-10-04）

用户新需求："ArkRAG 作为独立服务应有自己的后台管理；可独立配置大模型，作为独立 RAG 使用。"

### 新增
- **管理控制台**（`http://127.0.0.1:8964/admin`，零依赖单页，深浅色跟随系统+手动切换）：
  知识库管理（表格/新建/删除）、文档管理（真实 multipart 上传/状态轮询/删除/重建索引）、检索试用、
  问答试用、模型配置中心；token 登录（localStorage），错 token 显式红字。
- **运行时模型配置**：`GET/PUT /api/v1/settings/model`（脱敏回显、来源标注 runtime/yaml/unset、
  缺省组=清除回落 yml）+ `POST /api/v1/settings/model/test` 分路连通性测试；
  配置落 `{dataDir}/settings.json`，**保存热生效**（Embedding/Chat 模型实例按配置指纹缓存，变更即重建）。
- **独立 RAG 问答**：`POST /api/v1/ask`（检索 topK → Chat 生成带 [n] 引用答案 → citations）；
  MCP 双传输新增 `rag_ask` 工具（server+stdio 桥同步）；ArkWork 插件同步注册（plugin.json 1.1.0）。
- application.yml 新增 `arkrag.chat.*` 启动初值（环境变量 ARKRAG_CHAT_*）。

### 修复（过程发现，已回测）
- 并发上传互踩 docs.json.tmp → 临时文件名加 UUID + 提交登记纳入 KB 锁。
- 控制台 token 门改用受保护接口验证（原 /health 免鉴权探不出 token 对错）。

### 版本
- 全模块 1.1.0；插件 zip `arkrag-plugin-1.1.0.zip`（含 rag_ask，rebuild 后安装即生效）。

### 测试
- v1.0 回归 21/21；v1.1 新增 17 条全过（含 512 维护栏 409 实证、控制台真浏览器 e2e、深浅色截图）。
- 用例库：继承 36 + 新增 17 = active 53。
