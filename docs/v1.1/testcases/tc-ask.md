# 用例：rag_ask（tc-ask，v1.1）

> 对应 PRD F19 ｜ 执行方式：curl + 管道 JSON-RPC + 静态核对

| ID | 用例 | 优先级 | smoke | 预期 |
|----|------|--------|-------|------|
| TC-ASK-001 | POST /ask 返回带 [n] 答案 + 驼峰 citations | P0 | Y | n 与 index 对齐 |
| TC-ASK-002 | MCP tools/list 含 rag_ask（3 工具） | P0 | - | server+桥一致 |
| TC-ASK-003 | stdio 桥 rag_ask 返回答案 + 引用 | P0 | Y | isError=false |
| TC-ASK-004 | 空输入 → 400；无命中 → 固定拒答文案 | P1 | - | — |
| TC-ASK-005 | 插件 main.js 注册 rag_ask（双闸一致） | P0 | - | node --check 通过 |

## 执行记录（v1.1，2026-10-04）

- TC-ASK-001 **通过**：answer="（mock 回答）关于「检索接口怎么用？」：根据知识库资料 [1] 可以得到对应结论。"，
  citations=[(1, 检索说明.md, 0, 0.915)]（camelCase）。
- TC-ASK-002/003 **通过**：桥 tools/list = [rag_search, rag_list_kbs, rag_ask]；桥 tools/call rag_ask
  返回同源答案与 1 条引用。
- TC-ASK-004 **通过**（空输入 400 VALIDATION；mock 无命中时拒答文案在模型配置页测试中观察）。
- TC-ASK-005 **通过**：node --check main.js OK；plugin.json provides.tools 三工具与 register 调用一一对应。
