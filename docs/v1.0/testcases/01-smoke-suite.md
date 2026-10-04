# 冒烟套件 v1.0（P0 happy path 子集）

> 执行前置：`node scripts/mock-embedding-server.mjs 8965 1024 &`，server 以 mock embedding 启动（见 README 离线冒烟节）。
> 本版 10 条全部来自新增，无继承。

| ID | 用例 | 优先级 | smoke |
|----|------|--------|-------|
| TC-REST-001 | 无 token 访问 → 401 | P0 | Y |
| TC-REST-002 | /health 自检返回 UP + embedding 配置 | P0 | Y |
| TC-REST-004 | 建库 → 列表可见 | P0 | Y |
| TC-REST-009 | 纯文本摄入 → 状态 READY + chunkCount>0 | P0 | Y |
| TC-REST-012 | 语义检索命中且带出处 | P0 | Y |
| TC-MCP-001 | stdio 桥 initialize + tools/list | P0 | Y |
| TC-MCP-004 | stdio 桥 tools/call rag_search 返回结果 | P0 | Y |
| TC-MCP-005 | stdio 桥 token 错误 → isError + 人话 | P0 | Y |
| TC-MCP-006 | MCP Streamable HTTP initialize + tools/call | P0 | Y |
| TC-PLUGIN-001 | 插件 zip 结构合法（plugin.json 包根 + 4 文件） | P0 | Y |
