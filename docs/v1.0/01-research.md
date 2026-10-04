# 调研：Java RAG 服务（MCP + HTTP）与 ArkWork 集成

> 版本：v1.0 ｜ 日期：2026-10-04 ｜ 状态：已确认（用户指示"继续执行"，采纳 §6 全部推荐选型；后续修改走缺陷回溯）
> 调研范围：市面开源 RAG 系统与 Java RAG 生态、MCP 协议与官方 SDK、ArkWork 实地摸底（插件系统 / MCP 客户端 / 知识库现状）。

## 1. 调研目标

- 要解决的核心问题：**用 Java 自研一套 RAG 服务，同时提供 MCP 协议与 HTTP 接口，能直接对接 ArkWork（Electron 本地优先 Agent 工作台），并为其开发一个官方插件**。
- 期望借鉴的内容：
  - [x] 整体架构（检索管线分层、两阶段检索、混合检索）
  - [x] 数据模型（知识库/文档/分块 schema、引用溯源）
  - [x] 工程化方案（文档解析、切块策略、向量存储、协议接入）
  - [ ] 交互设计（本项目 UI 仅插件面板，轻量）

## 2. 检索记录

| 渠道类型 | 具体渠道 | 关键词 | 说明 |
|----------|----------|--------|------|
| 开源实现 | GitHub | RAGFlow / Dify / QAnything / AnythingLLM / MaxKB / Haystack / LangChain4j / Spring AI / mcp java-sdk | 主流候选逐个评估 |
| 开源合集 | awesome-rag / CSDN 框架盘点 | 7个GraphRAG+17个传统RAG | 补齐长尾候选 |
| 包仓库 | Maven Central | langchain4j / spring-ai / io.modelcontextprotocol.sdk | 确认 Java 依赖可用性 |
| **架构设计资料（重点）** | MCP 官方 spec（modelcontextprotocol.info）、掘金、腾讯开发者社区、51CTO、知乎、langcopilot.com | MCP 2025-06-18 streamable HTTP、两阶段检索、hybrid search RRF、chunking strategies | 协议与检索架构选型依据 |
| 系统设计资料 | Zilliz/ES 官方文档 | Milvus hybridSearch RRF/WeightedRanker、ES 8.x RRF retriever | 混合检索实现路径 |
| 产品与交互参考 | AnythingLLM 工作区式知识库、RAGFlow 引用溯源 UI | — | 插件面板交互参考 |

## 3. 候选开源项目评估

| 项目 | 地址 | Star（约） | 最近维护 | License | 技术栈 | 评估 |
|------|------|-----------|----------|---------|--------|------|
| RAGFlow | infiniflow/ragflow | ~60k+ | 活跃 | Apache-2.0 | Python | 深度文档理解 + 13+ chunk 模板 + 引用溯源，是检索质量标杆；但部署重（ES+MinIO+MySQL+Redis），Python 栈不匹配 → **借鉴设计** |
| Dify | langgenius/dify | ~100k+ | 活跃 | Dify OSS License（Apache-2.0 附加条款，待核实） | Python/Next.js | LLM 应用平台，知识库只是子模块；体量过大 → **借鉴知识库管道设计** |
| QAnything | netease-youdao/QAnything | ~13k | 维护放缓 | AGPL-3.0（待核实，只借鉴思路不抄代码） | Python | **两阶段检索**（embedding 召回 → rerank 精排）代表实现 → **借鉴两阶段设计** |
| AnythingLLM | Mintplex-Labs/anything-llm | ~40k+ | 活跃 | MIT | Node.js | 工作区式知识库组织清晰，桌面+服务双形态，交互成熟 → **借鉴产品形态** |
| Haystack | deepset-ai/haystack | ~20k+ | 活跃 | Apache-2.0 | Python | 管道组件化设计优秀，但 Python 栈 → **借鉴管线分层思想** |
| **LangChain4j** | langchain4j/langchain4j | ~8k+ | 活跃（月更） | Apache-2.0 | **Java** | Java 生态 RAG 组件最全：DocumentLoader/Splitter/EmbeddingStore 抽象（20+ 向量库实现）、Advanced RAG（QueryTransformer/QueryRouter/ContentRetriever/ScoringModel rerank）→ **直接使用** |
| **Spring AI** | spring-projects/spring-ai | ~5k+ | 活跃 | Apache-2.0 | **Java** | Spring 官方，MCP server/client starter 内置；RAG 抽象相对薄（Advisor 模式），rerank/查询改写需自建 → 备选 |
| **MCP Java SDK** | modelcontextprotocol/java-sdk | 官方 | 活跃 | MIT | **Java** | MCP 官方 Java 实现（与 Spring 团队协作），1.0.0 支持 STDIO + SSE + Streamable HTTP（spec 2025-06-18）→ **直接使用** |

## 3.5 架构设计资料评估

| 资料 | 来源/链接 | 核心方案 | 与本项目的匹配度 | 可借鉴点 |
|------|-----------|----------|------------------|----------|
| MCP 规范 2025-06-18 | modelcontextprotocol.info / GitHub spec | Streamable HTTP 取代 HTTP+SSE；结构化工具输出；STDIO 仍为本地进程标准传输 | **完全匹配** | 双传输设计：内嵌 HTTP(SSE/Streamable) 端点 + stdio 桥接模块 |
| 两阶段检索（召回+精排） | QAnything / AI-Compass（SegmentFault） | 一阶段 embedding 召回 top-N，二阶段 rerank 精排 top-K，解决数据量增大检索退化 | 匹配，检索质量关键 | rerank 可插拔（BGE-Reranker/Jina/Cohere 等 OpenAI 兼容或专用 API） |
| 混合检索 BM25+向量 | Zilliz 官方文档、ES 8.x RRF retriever、Milvus hybridSearch | 多路召回（稀疏+稠密）→ RRF/加权融合 | 匹配（P1 增强） | 融合层用 RRF；BM25 依赖外部 ES/Milvus，默认部署不引入 |
| 切块策略 | langcopilot《Document Chunking: 9 Strategies》、RAGFlow chunk 模板 | 递归/语义切块，600–1000 token + 50–100 overlap，段落边界优先；按文档类型选模板 | 匹配 | 递归切块 + 可配置 chunk size/overlap；按类型模板列 P1 |
| RAGFlow 深度文档理解 | RAGFlow 官方 | 版面感知解析（表格/双栏/扫描件）→ 结构化 chunk | 重（需 OCR/版面模型） | 不引入；文档解析用 Apache Tika（PDF/DOCX/PPTX/HTML/TXT/MD） |
| Java 框架对比 | 腾讯开发者社区、葡萄城（2026-02） | Spring AI 重 Spring 生态集成；LangChain4j 重 RAG 工具链完整度 | 匹配 | RAG 管线选 LangChain4j，HTTP/配置/生命周期用 Spring Boot |

## 4. ArkWork 对接点分析（实地摸底结论，附证据）

> 由探索代理对 `/Users/gongzheng/ai/ArkWork` 全仓调查得出（61 次工具调用，结论均带 file:line 证据）。

### 4.1 ArkWork 是什么
Electron 33 本地优先 AI Agent 工作台（React 18 + TS，无后端服务器、无数据库），ReAct 循环为核心。启动 `cd ArkWork/app && npm install && npm run dev`。

### 4.2 决定对接方案的五个事实

1. **MCP 客户端只支持 stdio**：`app/src/main/mcp/client.ts:54-83` 实现了 spawn 子进程 + initialize 握手 + tools/list + tools/call；SSE 配置类型存在但连接即报 unsupportedTransport（`client.ts:63-65`）。→ **Java RAG 服务的 MCP 必须提供 stdio 形态才能被 ArkWork 直连**。
2. **插件系统成熟（VS Code 式）**：`plugin.json`（schemaVersion 1.1，id 规则 `^[a-z0-9-]+(\.[a-z0-9-]+)+$`，kind 含 `tool`）+ Host 半 main.js（utilityProcess）+ Client 半 index.html（iframe sandbox）；权限默认拒绝，经能力网关 `CAP_PERMISSION` 放行（`src/main/plugins/runtime/gateway.ts:30-52`）。
3. **插件可注册 Agent 工具**：`ctx.ark.tools.register({name, description, inputSchema, handler})`（`host-runtime.ts:443-470`），且必须在 plugin.json `provides.tools` 声明（双闸，`gateway.ts:161-168`）；工具名全局格式 `plugin__<pluginId>__<name>`。
4. **插件可发 HTTP**：`ctx.ark.net.fetch(url, init)` 主进程代发（`gateway.ts:233-240`），permission=`net`——**这是插件调用 Java RAG HTTP 接口的标准通道**。
5. **配置与秘密惯例**：秘密存 `{arkworkDir}/secrets.json`，MCP env 支持 `secret:` 前缀引用（`mcp/client.ts:396-406`）；现有知识库为 MiniSearch 全文检索、无向量无 Embedding（`src/main/kb/index.ts:130-139`）——Java RAG 可作为"更强的检索源"与其并行。

### 4.3 集成形态结论

| 形态 | 说明 | 采用 |
|------|------|------|
| A. MCP stdio 直连 | ArkWork 在 mcp-servers.json 配 command 拉起 RAG 的 stdio 桥接进程，工具自动进模型工具表，零代码改动 | **采用**（RAG 服务提供 stdio 桥接模块，代理到 HTTP 服务） |
| B. 官方插件（kind=tool） | 插件 Host 半 `net.fetch` 调 RAG HTTP API + `tools.register` 注册 rag 工具；API token 走 secrets.json | **采用**（用户明确要求插件；附带轻量管理面板） |
| C. 改造 ArkWork 内核 | 扩展其 SSE MCP 客户端 / 替换 kb-search 后端 | 不采用（不动用户仓库，保持解耦） |

## 5. 可借鉴点提炼

| 来源 | 借鉴内容 | 如何应用到本项目 |
|------|----------|------------------|
| LangChain4j | RAG 管线组件化（Loader→Splitter→EmbeddingModel→EmbeddingStore→ContentRetriever→RetrievalAugmentor） | 作为核心框架直接使用；EmbeddingStore 抽象保证向量库可插拔 |
| MCP Java SDK | 官方协议实现，双传输 | 直接使用：内嵌 HTTP(SSE/Streamable) 端点 + 独立 stdio 桥接模块 |
| QAnything | 两阶段检索（召回+rerank） | 检索管线预留 RerankModel 钩子，可插拔、默认关闭 |
| RAGFlow | 引用溯源（chunk 级 citation）、chunk 模板化 | 返回结构携带 chunk 定位信息（文档名/页码/序号）；chunk 模板列 P1 |
| AnythingLLM | 工作区=知识库的组织方式 | HTTP API 以 KB 为一等实体管理 |
| ArkWork | secrets.json + `secret:` env、插件双闸工具注册、zip 安装约束（≤2000 条目/64MB） | 插件照此实现；HTTP 服务 token 可存 secrets.json |

## 6. 调研结论

- [ ] 直接使用：无——市面成熟 RAG 系统均为 Python/Node 栈，无法满足"Java + MCP/HTTP 双接口"要求。
- [x] **借鉴设计 + 站在成熟组件上自研**：
  - **框架层直接使用**：Spring Boot 3.x（Web/配置/生命周期）+ LangChain4j（RAG 管线与向量库抽象）+ MCP 官方 Java SDK（协议）+ Apache Tika（文档解析）。
  - **设计借鉴**：两阶段检索、RRF 混合检索（P1）、引用溯源、KB 一等实体（出处见 §5）。
  - **自研部分**：检索服务层、知识库管理、MCP/HTTP 双入口装配、stdio 桥接模块、ArkWork 官方插件。
- [ ] 确认自研（不采用）：——

**默认技术选型（请确认或修改）**：

| 决策点 | 建议 | 备选 |
|--------|------|------|
| Java 版本 | 21（LTS，LangChain4j/Spring Boot 3 基线） | 17 |
| Web 框架 | Spring Boot 3.5（Web MVC） | Quarkus |
| RAG 框架 | **LangChain4j** | Spring AI |
| 向量存储（默认） | **嵌入式文件持久化**（LangChain4j InMemoryEmbeddingStore + 序列化落盘，零外部依赖，匹配 local-first） | Qdrant/Milvus/pgvector（可插拔，P1 提供） |
| Embedding 模型 | OpenAI-compatible API（baseURL+apiKey+model，兼容 DeepSeek/Qwen/Ollama/vLLM，与 ArkWork models.json 惯例一致） | 内置本地模型（重） |
| MCP 传输 | HTTP（SSE+Streamable）内嵌 + **stdio 桥接模块**（代理到 HTTP 服务） | 仅 stdio / 仅 HTTP |
| 文档解析 | Apache Tika | POI/PDFBox 组合（Tika 已封装） |
| HTTP 鉴权 | Bearer token（可配置，默认启用） | 无鉴权 |

## 7. 对后续文档的影响

- 对 PRD 的输入：功能边界参考 AnythingLLM（KB 管理）+ QAnything（检索质量）；MCP 工具集与 HTTP API 是双一等接口。
- 对交互文档的输入：插件面板轻量（KB 状态/连接测试/工具开关），交互参考 ArkWork Git Manager 插件范例。
- 对系统设计的输入：管线分层借鉴 LangChain4j 官方 Advanced RAG 架构；MCP 双传输借鉴 spec 2025-06-18；stdio 桥接为独立模块（代理到 HTTP，避免双进程写同一索引）。

## 变更记录

| 日期 | 原因 | 改动点 | 影响范围 |
|------|------|--------|----------|
| 2026-10-04 | 首版调研 | 全文 | — |

---

**门禁**：本调研经用户确认后，进入产品文档阶段。
