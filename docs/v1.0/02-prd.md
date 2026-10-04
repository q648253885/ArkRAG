# 产品文档（PRD）：ArkRAG v1.0

> 版本：v1.0 ｜ 日期：2026-10-04 ｜ 状态：已确认（用户指示"继续执行"，按本版执行；后续功能变更先改本文档）
> 调研依据：`01-research.md`（功能设计参考 AnythingLLM 知识库组织、QAnything 两阶段检索、RAGFlow 引用溯源）。

## 1. 背景与目标

- **一句话概述**：ArkRAG 是一个本地优先的 Java RAG 检索服务，同时提供 **MCP 协议**与 **HTTP API** 两套接口，为 ArkWork Agent 工作台（经官方插件或 MCP stdio）及其他 MCP/HTTP 客户端提供"文档摄入 → 语义检索 → 引用溯源"能力。
- **目标用户**：
  1. ArkWork 用户：希望 Agent 能基于自己的文档（PDF/Word/Markdown…）做语义检索回答，而不仅是全文匹配；
  2. MCP 客户端用户：Claude Desktop / ZCode 等直连使用检索工具；
  3. 开发者：用 HTTP API 把 RAG 能力嵌入自己的脚本/服务。
- **核心问题**：ArkWork 内置知识库是 MiniSearch 纯全文检索（关键词匹配，无语义、无向量），同义改述即漏检；市面上成熟 RAG 全是 Python/Node 栈，与用户"Java 自研 + 对接 ArkWork"的诉求不匹配。
- **成功指标**：
  - ArkWork 中 Agent 通过 rag 工具检索知识库并给出带引用的回答，同义改述问题可命中；
  - 本项目测试用例库冒烟全绿、P0 全过；
  - 插件在 ArkWork 中完成 zip 安装 → 配置 → 激活 → Agent 调用全流程走通。

## 2. 用户场景

| 场景 | 触发 | 用户操作 | 期望结果 |
|------|------|----------|----------|
| S1 Agent 引用知识库回答 | 在 ArkWork 里就自己的文档提问 | 无需手工操作（Agent 自动调用 rag_search 工具） | 工具返回相关 chunk（带来源文档名/位置），Agent 生成带引用的回答；同义改述可命中 |
| S2 知识库管理 | 首次使用 / 文档更新 | 在 ArkWork 插件面板建库、上传文档、查看摄入状态；或用 curl 调 HTTP API | 文档异步完成解析-切块-向量化，状态可见，失败有原因 |
| S3 外部 MCP 客户端接入 | 在 Claude Desktop / ZCode 等配置 MCP | 配 HTTP(SSE/Streamable) 端点，或在同机用 stdio 桥 | tools 列表出现 rag_search 等工具，可直接调用检索 |
| S4 服务未就绪的容错 | RAG 服务未启动时 Agent 调用工具 | — | 工具返回明确错误信息（"服务未启动，请先启动 arkrag-server"），不悬挂 |

## 3. 功能清单

### 3.1 RAG 服务端（arkrag-server，Spring Boot 单体）

| 编号 | 功能 | 优先级 | 描述 | 验收标准 |
|------|------|--------|------|----------|
| F1 | 知识库管理 | P0 | HTTP：创建/列出/删除知识库（KB 为一等实体，含名称/描述）；删除级联清理文档与向量 | 建-列-删全通过；删除后检索不再返回该 KB 内容 |
| F2 | 文档摄入 | P0 | 上传文件（PDF/DOCX/PPTX/TXT/MD/HTML）或提交纯文本 → Apache Tika 解析 → 递归切块（默认 ~500 token、overlap ~50、段落边界优先，可配）→ Embedding → 入库；异步执行，任务状态可查 | 同一文档重复上传同 KB = 覆盖旧版本；摄入后立即可检索到；解析失败返回文件级错误与原因 |
| F3 | 语义检索 | P0 | topK 向量检索（默认 topK=5，可调）；返回 chunk 文本、分数、引用元数据（kbId/docId/docName/chunkIndex） | 与 query 语义相关的 chunk 排前；同义改述命中；引用元数据完整 |
| F4 | HTTP API | P0 | REST `/api/v1/**`，Bearer token 鉴权（token 可配置，默认启用），接口有契约文档 | 无 token/错 token → 401；契约测试全过 |
| F5 | MCP over HTTP | P0 | 内嵌 MCP 端点：SSE + Streamable HTTP（官方 Java SDK，spec 2025-06-18），暴露工具 `rag_search`/`rag_list_kbs` | MCP 客户端（ZCode/Claude）能完成 initialize→tools/list→tools/call |
| F6 | MCP stdio 桥 | P0 | 独立可执行入口（`arkrag-mcp-stdio`）：stdio JSON-RPC ↔ 服务 HTTP 的代理；服务不可达时返回明确 isError | ArkWork mcp-servers.json 配 command 即用；服务未启动时工具返回明确错误不悬挂 |
| F7 | 服务配置 | P0 | application.yml + 环境变量：端口、数据目录、Bearer token、Embedding 模型（OpenAI-compatible：baseURL/apiKey/model/维度）、chunk 参数 | 改配置重启生效；未配 Embedding 模型时摄入/检索返回明确错误码 |
| F8 | 两阶段检索（rerank） | P1 | 可插拔 RerankModel（OpenAI 兼容 / Jina 等专用 API），召回 topN → 精排 topK，默认关闭 | 开启后检索质量（人工评估）不降；关闭时零依赖 |
| F9 | RAG 答案生成 `rag_ask` | P1 | 可选配置 chat LLM（OpenAI-compatible），基于检索结果生成带引用编号的答案；HTTP 与 MCP 同时暴露 | 答案附引用编号且编号可对应到 chunk；未配 LLM 时工具返回明确错误 |
| F10 | 外部向量库适配 | P1 | 经 LangChain4j 现成模块接入 Qdrant / Milvus（配置切换，默认仍为嵌入式） | 切换配置后摄入+检索全流程通过 |

### 3.2 ArkWork 插件（arkrag-plugin，kind='tool'）

| 编号 | 功能 | 优先级 | 描述 | 验收标准 |
|------|------|--------|------|----------|
| F11 | Agent 工具注册 | P0 | Host 半 `tools.register` 注册 `rag_search`（检索）、`rag_list_kbs`（列知识库）；plugin.json `provides.tools` 同步声明 | ArkWork Agent 工具表出现 plugin__arkrag.rag__* 工具且可成功调用 |
| F12 | 服务连接配置 | P0 | 服务地址 + Bearer token 配置（token 沿 ArkWork 惯例存 secrets.json / 插件 storage）；net.fetch 调用带上 | 改配置后立即生效；token 错误时工具返回明确错误 |
| F13 | zip 安装包 | P0 | 符合 ArkWork zip 安装约束（plugin.json 包根、≤2000 条目/64MB），提供安装与配置说明 | 在 ArkWork 中 zip 安装成功并通过校验 |
| F14 | 管理面板 | P1 | Client 半（iframe）面板：知识库列表、文档上传、摄入状态、连接测试（Host 半代理 HTTP） | 面板可打开、可操作，五态齐全（默认/加载/空/错误/成功） |
| F15 | rag_ask 工具 | P1 | 对应 F9，服务端开启后插件同步注册 | 同 F9 |
| F16 | 内置 KB 迁移 | P2 | 把 ArkWork `.arkwork/kb` 文档批量导入 RAG 服务 | 仅记录不排期 |

## 4. 明确不做的范围

1. **不做 Web 管理控制台**：服务端无 UI；管理动作走 HTTP API + 插件面板（P1）。
2. **不做用户体系/多租户**：单用户本地服务，Bearer token 仅为最基本的访问控制。
3. **不做 OCR / 版面感知解析**：Tika 只取文本层，扫描件/复杂版面 PDF 不保证质量（RAGFlow 式深度文档理解列为远期）。
4. **不做 Embedding 模型本地推理**：一律调 OpenAI-compatible API（Ollama 亦走该协议）。
5. **不做 Java 侧 BM25 / 混合检索**：P1 混合检索依托外部向量库（Qdrant/Milvus）能力，默认嵌入式形态不实现。
6. **不修改 ArkWork 仓库任何代码**：只交付外部服务与插件，保持解耦。
7. **不做分布式/集群、索引分片**：单机单进程持有索引。
8. **不做文档增量更新 diff**：重复上传 = 覆盖。

## 5. 关键业务规则

- **分块规则**：默认按 token 递归切块（chunk≈500 token，overlap≈50，段落边界优先），KB 级可配；chunk 携带 `kbId/docId/docName/chunkIndex` 及字符偏移。
- **嵌入维度一致性**：入库时记录模型与维度；检索时模型/维度不一致 → 返回明确错误并提示重建索引，禁止静默混用。
- **覆盖语义**：同 KB 内同名文档再次上传 → 删除旧 chunks 后重建（docId 变化）。
- **删除级联**：删除文档删其 chunks；删除 KB 删其文档与 chunks。
- **鉴权边界**：HTTP 全部接口要求 `Authorization: Bearer <token>`（token 空配置=拒绝启动并在日志提示，避免裸奔）；MCP over HTTP 同样校验；stdio 桥用本地 token 转发。
- **容错边界**：服务未启动/不可达 → stdio 桥与插件工具返回 `isError` + 人话提示；Embedding 未配置 → 摄入/检索返回结构化错误码（如 `EMBEDDING_NOT_CONFIGURED`）。
- **MCP 工具面（P0）**：`rag_search(query, kb_ids?, top_k?)`、`rag_list_kbs()`；摄入走 HTTP（MCP 侧不做文件上传，避免大负载走工具调用）。

## 6. 依赖与风险

- **外部依赖**：JDK 21 运行环境；Embedding API（网络可达、账号有效）；ArkWork 现版本（v0.45 插件契约 schemaVersion 1.1、MCP stdio 客户端）。
- **风险1：用户机器缺 Java 21 / stdio 桥起不来** → 对策：交付一键启动脚本 + `java` 版本自检与明确报错；文档写清安装指引；（后续版本可用 jpackage 出自带 JRE 的应用）。
- **风险2：Embedding API 不稳定/维度变更** → 对策：请求重试与超时；维度校验拦截；提供"重建索引"HTTP 动作。
- **风险3：ArkWork 插件契约后续升级导致不兼容** → 对策：插件只用稳定面（plugin.json 1.1、net.fetch、tools.register、storage），参照 bundled Git Manager 范例实现；契约断言失败时给出可读日志。

## 7. 里程碑

| 阶段 | 产出 | 目标时间 |
|------|------|----------|
| M1 | P0 全量：服务端 F1–F7 + 插件 F11–F13 | 本版本内 |
| M2 | P1：F8–F10、F14–F15 | 后续小版本 |
| — | P2：仅记录（F16、chunk 模板、GraphRAG、管理控制台） | 不排期 |

## 变更记录

| 日期 | 原因 | 改动点 | 影响范围 |
|------|------|--------|----------|
| 2026-10-04 | 首版 | 全文 | — |

---

**门禁**：本 PRD 经用户确认（用户指示"继续执行"，按本版执行）后，进入交互文档阶段。
