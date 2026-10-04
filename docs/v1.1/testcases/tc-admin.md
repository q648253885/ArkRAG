# 用例：管理控制台（tc-admin，v1.1）

> 对应 PRD F17 ｜ 执行方式：ZCode 内置 Chromium 对**真服务**（mock 模型）实测 + 截图

| ID | 用例 | 优先级 | smoke | 预期 |
|----|------|--------|-------|------|
| TC-ADMIN-001 | /admin 302 → index.html 200，静态免鉴权 | P0 | Y | 200 |
| TC-ADMIN-002 | 错 token 显式报错；正确 token 登录 | P0 | Y | 红字"token 无效或未设置" |
| TC-ADMIN-003 | 控制台全链路：建库→真实文件上传→READY→检索→问答 | P0 | Y | 全绿 |
| TC-ADMIN-004 | 模型配置页：读脱敏值/测试连通/保存热生效 | P0 | - | toast + ask 即可用 |
| TC-ADMIN-005 | 深浅色截图检查 | P1 | - | 阻塞清零 |

## 执行记录（v1.1，2026-10-04，对真服务实测）

- TC-ADMIN-001/002 **通过**（错 token → 红字 + conn"token 无效"；修正过程中发现并修复了
  "token 验证探活误用免鉴权 /health"的 UX 缺陷——改用 GET /kb 验证）。
- TC-ADMIN-003 **通过**：控制台建"控制台验收库"→ DataTransfer 合成 2 个真实 File → FormData multipart
  上传 → 状态行"完成 1 块"×2 → 检索 0.918 分命中 → 问答返回带 [1][2] 引用的答案。
- TC-ADMIN-004 **通过**：模型页回显 yaml 来源与脱敏 key；chat 测试连通 ✓；保存 → toast"模型配置已保存并热生效"。
- TC-ADMIN-005 **通过**：深色（系统跟随）与浅色（手动切换）截图各一张，阻塞清零（见 06b）。

### 过程中修复的真实缺陷（回归后关闭）

| 级别 | 缺陷 | 修复 | 回测 |
|------|------|------|------|
| 严重 | 并发上传两文件互踩 `docs.json.tmp`（同名临时文件 + 原子 move 竞争）→ 一文件 FAILED"元数据文件写入失败" | MetaFiles/VectorIndex 临时文件名加 UUID；提交登记纳入 KB 锁串行 | 控制台并发上传 2 文件全部 READY ✓ |
| 一般 | token 门用免鉴权 /health 探活，错 token 也能通过 | 改用受保护 GET /kb 验证 | 错 token 显式红字 ✓ |
| 建议 | /health 版本号读 pom 实现版本（Boot 嵌套 jar 返回 null） | pom 全模块升 1.1.0 + 回退常量同步 | health 返回 1.1.0 ✓ |
