# 用例：摄入管线与检索引擎（tc-engine）

> 对应 PRD F2/F3/F7 ｜ 执行方式：curl 脚本 + 服务日志 ｜ 环境：mock embedding（1024 维）

| ID | 用例 | 优先级 | smoke | 预期 |
|----|------|--------|-------|------|
| TC-ENG-001 | 文本摄入后状态机 PENDING→READY | P0 | - | 终态 READY，chunkCount>0 |
| TC-ENG-002 | 切块：>chunkSize 文本产出多块，块间有 overlap | P1 | - | chunkCount≥2；相邻块文本重叠 |
| TC-ENG-003 | 摄入空文本 → 400 VALIDATION | P1 | - | 拒绝且不入库 |
| TC-ENG-004 | 解析空文本层文件 → FAILED + 原因 | P1 | - | docs.json error 可见 |
| TC-ENG-005 | KB dims 建库为 0，首次摄入回填实际维度 | P1 | - | kb.dims=1024 |
| TC-ENG-006 | 语义排序：同话题 query 命中该话题 chunk 且分数更高 | P0 | - | 相关块排第一 |
| TC-ENG-007 | minScore 过滤生效 | P2 | - | 低于阈值不返回 |
| TC-ENG-008 | 同 KB 同名重传 = 覆盖（文档数不变、内容更新） | P1 | - | 旧 chunk 消失新 chunk 可检索 |
| TC-ENG-009 | 删除文档后不可再检索出 | P1 | - | 204 且 hits 无该文档 |
| TC-ENG-010 | 服务重启后索引持久化可用 | P0 | - | 重启后检索仍命中 |

## 执行记录（v1.0，2026-10-04）

- TC-ENG-001/005/006/008/009/010：**通过**（自动化脚本，见 05-function-test-report.md）
- TC-ENG-002：**通过**（1200 字文本 → 2 块；overlap 观察于 vectors.json）
- TC-ENG-003：**通过**（400 VALIDATION"文本内容不能为空"）
- TC-ENG-004：**通过**（0 字节 pdf → FAILED"文件为空"；扫描件路径静态审查：Tika 空文本 → FAILED）
- TC-ENG-007：**通过**（minScore=0.5 → 低分块被过滤）
