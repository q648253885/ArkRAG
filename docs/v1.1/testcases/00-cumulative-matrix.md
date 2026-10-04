# 测试用例库 v1.1（累积矩阵）

> 版本：v1.1.0 ｜ 日期：2026-10-04 ｜ 继承 v1.0 全部 active 用例 + 本版新增 17 条
> 用例 ID 规则：TC-<模块>-<序号>；新增模块文件：tc-settings.md、tc-ask.md、tc-admin.md

## 累积矩阵

| 版本 | 继承 | 新增 | 修改 | 废弃 | active 合计 |
|------|------|------|------|------|-------------|
| v1.0 | 0 | 36 | 0 | 0 | 36 |
| v1.1 | 36 | 17 | 0 | 0 | 53 |

## v1.1 新增用例

### tc-settings.md（模型配置，F18）

| ID | 用例 | 优先级 | smoke | 预期 |
|----|------|--------|-------|------|
| TC-SET-001 | GET 脱敏视图 + 来源标注（yaml/runtime/unset） | P0 | Y | apiKey 只回 ****尾4 位 |
| TC-SET-002 | Chat 未配置 ask → 409 CHAT_NOT_CONFIGURED | P0 | Y | 错误码 + 配置指引文案 |
| TC-SET-003 | PUT chat 配置 → 热生效（ask 即可用，无需重启） | P0 | Y | source=runtime |
| TC-SET-004 | test embedding → ok + 实际维度 | P1 | - | dims=1024 |
| TC-SET-005 | test chat → ok + 模型回复 | P1 | - | ok=true |
| TC-SET-006 | PUT 缺省组 = 清除该组回落 yml（契约语义） | P1 | - | source 变化 |
| TC-SET-007 | 换 Embedding 模型（512 维）→ 检索/问答 409 DIMENSION_MISMATCH | P0 | - | 带 rebuild 指引 |

### tc-ask.md（rag_ask，F19）

| ID | 用例 | 优先级 | smoke | 预期 |
|----|------|--------|-------|------|
| TC-ASK-001 | POST /ask 返回带 [n] 答案 + 驼峰 citations | P0 | Y | n 与 index 对齐 |
| TC-ASK-002 | MCP Streamable tools/list 含 rag_ask | P0 | - | 3 工具 |
| TC-ASK-003 | stdio 桥 rag_ask 返回答案 + 引用 | P0 | Y | isError=false |
| TC-ASK-004 | 空输入 → 400；无命中 → 固定拒答文案 | P1 | - | — |
| TC-ASK-005 | 插件 main.js 注册 rag_ask（provides.tools 双闸一致） | P0 | - | node --check + 契约核对 |

### tc-admin.md（控制台，F17）

| ID | 用例 | 优先级 | smoke | 预期 |
|----|------|--------|-------|------|
| TC-ADMIN-001 | /admin 302 → index.html 200，静态资源免鉴权 | P0 | Y | 200 |
| TC-ADMIN-002 | 错 token → 显式红字"token 无效或未设置"；正确 token → 登录进列表 | P0 | Y | — |
| TC-ADMIN-003 | 控制台真实链路：建库 → 真实文件上传 → 状态 READY → 检索 → 问答（对真服务+mock 模型） | P0 | Y | 全链路绿 |
| TC-ADMIN-004 | 模型配置页：读脱敏值、测试连通、保存热生效 | P0 | - | toast + ask 立即可用 |
| TC-ADMIN-005 | 深浅色切换渲染（截图检查，见 06b） | P1 | - | 阻塞清零 |
