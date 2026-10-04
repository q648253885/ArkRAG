# 系统设计增量：ArkRAG v1.1

> 版本：v1.1.0 ｜ 日期：2026-10-04 ｜ 状态：已确认（用户提出）
> 契约正本：`docs/v1.0/04-system-design.md`；本页只登记新增/变更项，未提及者不变。

## 1. 新增数据模型

**运行时模型配置**（`{dataDir}/settings.json`；优先于 application.yml 初始值，保存即热生效）

| 字段 | 类型 | 说明 |
|------|------|------|
| embedding | object? | `{baseUrl, apiKey, model, dims?}`，缺省回落 yml |
| chat | object? | `{baseUrl, apiKey, model, temperature?}`，缺省未配置 |
| updatedAt | long | |

## 2. 新增/变更接口（鉴权同 v1.0）

**GET /api/v1/settings/model** — 读当前生效配置；`apiKey` 脱敏为 `***尾4位`；同时回 `{source: "runtime"|"yaml"|"unset"}`。
**PUT /api/v1/settings/model** — body `{embedding?:{baseUrl,apiKey,model,dims?}, chat?:{baseUrl,apiKey,model,temperature?}}`；校验 URL/model 非空；写 settings.json + 热生效（重建内部 model 实例）；返回同 GET。传 `null` 字段=删除该组（回落 yml）。
**POST /api/v1/settings/model/test** — body `{target:"embedding"|"chat", baseUrl, apiKey, model, dims?}`；embedding=发 1 条真实 embed，chat=发 1 条 1-token 对话；返回 `{ok, message, dims?}`（不落盘）。
**POST /api/v1/ask** — body 同 `/search`；响应 `{"answer":"…[1]…[2]","citations":[{"index":1,"kbId","kbName","docId","docName","chunkIndex","score"}],"tookMs"}`；Chat 未配置 → 409 `CHAT_NOT_CONFIGURED`；无命中 → answer 为固定文案、citations 空。

**MCP 工具新增 `rag_ask`**（HTTP 两端点 + stdio 桥同步）：input `{query(必填), kb_ids?, top_k?}` → text JSON `{answer, citations, took_ms}`；Chat 未配置 → isError + 配置指引。
**静态资源**：`GET /admin` → classpath `/admin/index.html`（免鉴权，数据全走鉴权 API）。

## 3. 新增模块与热生效机制

| 模块 | 职责 |
|------|------|
| core `SettingsService` | settings.json 读写（原子替换）+ 变更监听回调；配置合成顺序 runtime > yml |
| core `ChatService` | OpenAI 兼容 Chat 封装（懒构建 + 配置失效重建）；`isConfigured()/chat(prompt)` |
| core `AskService` | SearchService 取 topK → 组装中文提示词（上下文+引用编号+拒答规则）→ ChatService → 解析答案与引用对齐 |
| server `SettingsController` / `AskController` / 静态资源映射 | 见 §2 |

- EmbeddingService 改造：配置来源改为"合成配置"，SettingsService 变更时清空缓存的 model 实例（下次调用重建）→ 热生效。
- KB.dims 快照与维度护栏逻辑不变（换 embedding 模型后经 /rebuild 迁移）。
- 提示词模板（AskService 内置，代码注释附"为什么"）：仅基于给定上下文回答、标注 [n]、无相关内容时固定拒答文案、禁止编造。

## 4. 目录/资产增量

- `arkrag-core/…/{settings,chat,ask}` 包
- `arkrag-server/…/api/{SettingsController,AskController}.java`、`resources/admin/index.html`（单文件控制台，零依赖）
- `scripts/mock-embedding-server.mjs` 增加 `/chat/completions`（测试用 canned 回答，引用请求上下文中的 [n] 编号）
- 插件 `plugin/arkrag/`：plugin.json provides.tools + main.js 注册 `rag_ask`

## 变更记录

| 日期 | 原因 | 改动点 | 影响范围 |
|------|------|--------|----------|
| 2026-10-04 | 用户提出独立后台与独立大模型配置 | 全文 | server/core/插件/MCP 工具面 |
