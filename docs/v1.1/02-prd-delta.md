# PRD 增量：ArkRAG v1.1（v1.0 正本的修订页）

> 版本：v1.1.0 ｜ 日期：2026-10-04 ｜ 状态：已确认（用户提出）
> 修订声明：本页只登记 v1.0 → v1.1 的功能变更；v1.0 功能清单（F1–F16）继续有效。
> **原 §4"明确不做"中"不做 Web 管理控制台"与"rag_ask 列 P1"两项由用户裁决废止。**

## 新增/变更功能

| 编号 | 功能 | 优先级 | 描述 | 验收标准 |
|------|------|--------|------|----------|
| F17 | Web 管理控制台 | P0 | 服务内嵌管理页 `GET /admin`（零依赖单页）：知识库管理（建/删/列表/统计）、文档管理（上传/列表/状态/删除）、检索试用、问答试用、模型配置中心；token 登录（localStorage） | 五个区块可用；未配 token/错 token 时数据请求明确报 401 并引导输入 |
| F18 | 运行时模型配置 | P0 | `GET/PUT /api/v1/settings/model`（embedding + chat 两组：baseUrl/apiKey/model/dims）；`POST /api/v1/settings/model/test` 分路测试连通性；配置持久化 data-dir/settings.json，**保存热生效**（无需重启）；apiKey 读取接口脱敏返回 | 改配置后立即用新模型摄入/问答；测试连接区分 embedding/chat 与具体错误 |
| F19 | 独立 RAG 问答 `rag_ask`（原 F9 提前） | P0 | `POST /api/v1/ask`：检索 topK → Chat LLM 基于命中块生成带 [n] 引用编号的答案 → 返回 {answer, citations[], tookMs}；MCP 工具 `rag_ask`（HTTP + stdio 桥）；ArkWork 插件同步注册 | 答案中的 [n] 可对应 citations；无命中时明确回答"知识库中没有找到相关内容"；Chat 未配置 → 409 |

## 边界规则（新增）

- **模型热切换护栏**：更换 Embedding 模型/维度后，旧 KB 检索触发 EMBEDDING_DIMENSION_MISMATCH（沿用 v1.0 规则），控制台在模型页显示提示"更换后需对既有库执行重建"；Chat 模型切换即时生效无迁移问题。
- **安全边界**：settings.json 含明文 apiKey（local-first 与 ArkWork models.json 同级）；GET 脱敏（只回 `sk-***` 尾 4 位）；写接口要求 ≥16 位 token（沿用）。
- 控制台静态资源免鉴权（HTML/JS 无敏感数据），数据 API 全部走既有鉴权。

## 不做的范围（本版新增部分）

对话历史、多用户/组织、apiKey 加密存储、控制台国际化、控制台移动端适配。

## 成功指标

不启动 ArkWork、不装插件，仅浏览器 + curl 即可完成"配模型 → 建库 → 上传 → 问答"全流程。
