# 用例：MCP 双传输与 stdio 桥（tc-mcp）

> 对应 PRD F5/F6 ｜ 执行方式：管道 JSON-RPC + curl ｜ 协议版本：2025-06-18

| ID | 用例 | 优先级 | smoke | 预期 |
|----|------|--------|-------|------|
| TC-MCP-001 | 桥 initialize（不依赖服务在线）+ tools/list 两工具 | P0 | Y | serverInfo=arkrag 1.0.0 |
| TC-MCP-002 | 桥日志走 stderr，stdout 仅协议帧 | P1 | - | stdout 可被 JSON 逐行解析 |
| TC-MCP-003 | 桥 rag_list_kbs 返回 KB 列表 | P1 | - | JSON 含 knowledge_bases |
| TC-MCP-004 | 桥 rag_search 返回带出处 hits | P0 | Y | isError=false |
| TC-MCP-005 | 桥 token 错误 → isError + 修复指引 | P0 | Y | "ARKRAG_TOKEN 与服务端…不一致" |
| TC-MCP-006 | Streamable HTTP：initialize → tools/call（带 session） | P0 | Y | 200 + 结果 |
| TC-MCP-007 | Streamable/SSE 无 token → 401 | P0 | - | 401 |
| TC-MCP-008 | SSE 端点建立事件流（endpoint 事件） | P1 | - | event: endpoint |
| TC-MCP-009 | 桥服务不可达 → isError + "请先启动服务" | P1 | - | 不悬挂，秒回错误 |

## 执行记录（v1.0，2026-10-04）

- 全部 **通过**。TC-MCP-009 通过停止服务后调用验证：返回"无法连接 ArkRAG 服务…请先启动"。
- TC-MCP-002：stdout 三行均为合法 JSON-RPC（python json.loads 逐行验证）。
