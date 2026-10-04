# 用例：模型配置（tc-settings，v1.1）

> 对应 PRD F18 ｜ 执行方式：curl（对真服务 + mock 模型）

| ID | 用例 | 优先级 | smoke | 预期 |
|----|------|--------|-------|------|
| TC-SET-001 | GET 脱敏视图 + 来源标注 | P0 | Y | apiKey 只回 ****尾4 位 |
| TC-SET-002 | Chat 未配置 ask → 409 CHAT_NOT_CONFIGURED | P0 | Y | 配置指引文案 |
| TC-SET-003 | PUT chat 配置 → 热生效 | P0 | Y | source=runtime，ask 即可用 |
| TC-SET-004 | test embedding → ok + 实际维度 | P1 | - | dims=1024 |
| TC-SET-005 | test chat → ok + 模型回复 | P1 | - | ok=true |
| TC-SET-006 | PUT 缺省组 = 清除该组回落 yml | P1 | - | source 变化 |
| TC-SET-007 | 换 512 维模型 → 检索/问答 409 DIMENSION_MISMATCH | P0 | - | 带 rebuild 指引 |

## 执行记录（v1.1，2026-10-04）

- 全部 **通过**（curl 实测）。
- TC-SET-003 证据：PUT chat 后立即 POST /ask 返回 mock 答案，无需重启。
- TC-SET-007 证据：PUT 512 维模型（mock 8966 实例）→ `POST /search` 与 `/ask` 均 409
  "向量维度(1024)与当前查询维度(512)不一致——…请对该库执行 POST /api/v1/kb/{id}/rebuild"。
