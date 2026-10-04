# CHANGELOG：ArkRAG v1.0

> 本版变更日志 ｜ 项目级总览见 docs/CHANGELOG.md

## v1.0.0（2026-10-04）

### 新增
- **arkrag-server**（Spring Boot 3.5 fat-jar）：REST `/api/v1`（KB 管理/文档摄入/语义检索/健康自检）、Bearer+X-Api-Key 鉴权（token <16 位拒绝启动）、MCP 双端点（`/mcp` Streamable HTTP + `/mcp/sse` SSE，官方 MCP Java SDK 2.0.1，spec 2025-06-18）。
- **arkrag-core** 检索引擎：Tika 解析（PDF/DOCX/PPTX/HTML/TXT/MD）→ 递归切块（1000/150 字符可配）→ OpenAI 兼容 Embedding（批 32×3 重试）→ 自研内存向量索引（余弦 topK、volatile 快照无锁搜索）→ JSON 原子落盘；摄入状态机 PENDING→PARSING→EMBEDDING→READY/FAILED（同 KB 串行）。
- **arkrag-mcp-stdio** 桥：stdio JSON-RPC ↔ HTTP 代理（initialize/tools/list 不依赖服务在线；tools/call 实时代理，失败 isError + 人话指引）。
- **ArkWork 插件**（arkrag-plugin-1.0.0.zip）：rag_search / rag_list_kbs 两个 Agent 工具 + dock 管理面板（连接设置/知识库管理/文档上传/摄入状态/检索试用），深浅色跟随宿主主题契约。
- 脚本：start-server（sh/bat）、arkrag-mcp-stdio（sh/bat）、build-dist、run-tests、mock-embedding-server（离线冒烟）。
- 文档体系：调研/PRD/交互/系统设计/原型/用例库（36 条）/测试报告（05/06/06b/07）+ 全局三件套。

### 测试
- 冒烟 10/10 绿；自动化详测 21/21 绿；MCP 9/9 绿；面板 UI 实测 23 项点击清单全过、深浅色截图检查阻塞清零。
- 遗留：ArkWork 实机内联走查（TC-PLUGIN-005）待用户确认。

### 关键决策（详见 01/04 文档）
- LangChain4j（管线）+ Spring Boot（装配）+ MCP 官方 SDK；向量索引自研（InMemoryEmbeddingStore Entry 包私有不可枚举，缺陷回溯）。
- MCP 双传输：HTTP 内嵌 + 独立 stdio 桥（ArkWork 客户端仅支持 stdio）。
- 插件面板由 P1 升 P0（无 UI 则连接配置不可用）。
