# 用例：HTTP REST 与鉴权（tc-rest-api）

> 对应 PRD F1/F4/F7 ｜ 执行方式：curl ｜ 环境：mock embedding

| ID | 用例 | 优先级 | smoke | 预期 |
|----|------|--------|-------|------|
| TC-REST-001 | 无 token 访问受保护接口 → 401 | P0 | Y | code=UNAUTHORIZED |
| TC-REST-002 | /health 免鉴权返回 UP + 自检 | P0 | Y | status=UP，embedding.configured=true |
| TC-REST-003 | 错误 token → 401（非 500） | P0 | - | 401 |
| TC-REST-004 | 建库 → 列表可见且统计为 0 | P0 | Y | name/description 回显 |
| TC-REST-005 | 重名建库 → 400 VALIDATION | P1 | - | 错误码 VALIDATION |
| TC-REST-006 | 空名建库 → 400 | P1 | - | "名称不能为空" |
| TC-REST-007 | KB 详情含文档列表与状态 | P1 | - | documents 数组 |
| TC-REST-008 | 不存在 KB → 404 KB_NOT_FOUND | P0 | - | 404 |
| TC-REST-009 | 纯文本摄入 → 202 语义 + 状态轮询 READY | P0 | Y | PENDING→READY |
| TC-REST-010 | multipart 文件上传摄入 | P1 | - | 同 009 |
| TC-REST-011 | base64 二进制摄入（插件路径） | P1 | - | 同 009 |
| TC-REST-012 | 语义检索返回 hits（分数/出处/文本） | P0 | Y | 结构完整，分数降序 |
| TC-REST-013 | 删除 KB → 204 且级联清理（列表为空、目录删除） | P1 | - | 204 |

## 执行记录（v1.0，2026-10-04）

- 全部 **通过**（自动化脚本；TC-REST-013 另测：删除后 GET /kb 不再返回该库、data 目录目录消失）
- 补充验证：MCP HTTP 无 token → 401（同 TC-REST-001 通道）；X-Api-Key 与 Bearer 两种头均接受
