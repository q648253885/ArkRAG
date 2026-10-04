# ArkRAG 架构文档

> 版本：v1.1.0 ｜ 更新：2026-10-04 ｜ 面向：维护者与二次开发者
> 配套：[USAGE.md](USAGE.md)（使用）· [接口契约正本](v1.0/04-system-design.md) + [v1.1 增量](v1.1/04-system-design-delta.md) · [决策与调研](v1.0/01-research.md)

## 目录

1. [系统总览](#1-系统总览)
2. [模块划分](#2-模块划分)
3. [核心数据模型与存储布局](#3-核心数据模型与存储布局)
4. [关键流程时序](#4-关键流程时序)
5. [并发与一致性模型](#5-并发与一致性模型)
6. [配置体系](#6-配置体系)
7. [MCP 协议层](#7-mcp-协议层)
8. [管理控制台](#8-管理控制台)
9. [ArkWork 插件](#9-arkwork-插件)
10. [安全设计](#10-安全设计)
11. [性能与容量边界](#11-性能与容量边界)
12. [关键设计决策记录（ADR 摘要）](#12-关键设计决策记录)
13. [扩展指南](#13-扩展指南)

---

## 1. 系统总览

ArkRAG 是**单进程、单 jar** 的本地优先 RAG 服务：所有状态落在一个数据目录（无数据库、无外部中间件），对外暴露四个通道，内部共享同一引擎。

```
┌ 管理控制台(/admin) ┐ ┌ ArkWork 插件 ┐ ┌ ArkWork MCP(stdio) ┐ ┌ 任意 MCP 客户端 ┐ ┌ curl/脚本 ┐
│  浏览器静态页        │ │ net.fetch    │ │  stdio 桥进程       │ │ Streamable/SSE │ │  REST    │
└─────────┬─────────┘ └──────┬───────┘ └─────────┬─────────┘ └───────┬────────┘ └────┬─────┘
          │        X-Api-Key │（net.fetch 主进程代发）│ JSON-RPC/stdio      │ HTTP+SSE      │
          ▼                  ▼                      ▼ HTTP                ▼               ▼
┌───────────────────────── arkrag-server（Spring Boot 单体，端口 8964）─────────────────────────┐
│  AuthFilter（常量时间 token 比较）                                                              │
│  ┌ REST Controllers ┐  ┌ MCP 双端点（McpServer×2）┐  ┌ 静态资源 /admin/** ┐                    │
│  │ Kb/Document/     │  │ /mcp (Streamable)      │  └────────────────────┘                    │
│  │ Search/Ask/      │  │ /mcp/sse + /mcp/message│                                            │
│  │ Settings/Health  │  │ 3 工具：rag_search/     │                                            │
│  └────────┬─────────┘  │        rag_list_kbs/   │                                            │
│           │            │        rag_ask         │                                            │
│  ─────────┴──────────── 服务层 ──────────────────┴──────────────                              │
│  KnowledgeBaseService   IngestService        SearchService        AskService                 │
│         │                     │                    │                  │                       │
│  IndexRegistry（快照/防抖落盘）  EmbeddingService    VectorIndex       ChatService             │
│         │                     （指纹缓存热生效）      （暴力余弦）        （指纹缓存）             │
│  SettingsService（runtime settings.json > yml，变更广播）                                      │
└──────────────┬───────────────────────────────────────────────────────────────────────────────┘
               ▼
   {dataDir}/  kbs.json · kb/{id}/docs.json · kb/{id}/vectors.json · kb/{id}/files/ · settings.json
               + 外部 OpenAI 兼容 Embedding / Chat 端点（HTTP）
```

**设计基调**（详见 [§12 ADR](#12-关键设计决策记录)）：零外部依赖的嵌入式形态、文件即数据库、写入原子化、读路径无锁、配置热生效、协议多入口共享单一引擎。

## 2. 模块划分

Maven 多模块（父 POM `com.arkrag:arkrag-parent:1.1.0`，Spring Boot 3.5.16 parent）：

| 模块 | 打包 | 职责 | 关键依赖 |
|------|------|------|----------|
| `arkrag-core` | 普通 jar | 检索引擎（无 Spring）：领域模型、摄入管线、向量索引、检索、问答、模型配置 | langchain4j 1.21（DocumentParser/Splitter/EmbeddingModel/ChatModel）、langchain4j-open-ai、langchain4j-document-parser-apache-tika、jackson-databind |
| `arkrag-server` | Spring Boot fat-jar（81MB） | HTTP/MCP 装配、鉴权、控制台静态资源 | arkrag-core、spring-boot-starter-web、io.modelcontextprotocol.sdk:mcp 2.0.1 |
| `arkrag-mcp-stdio` | assembly fat-jar（8.4MB） | stdio 桥（纯代理，零 Spring） | mcp 2.0.1（含 mcp-json-jackson3）、jackson-databind、slf4j-simple |
| `plugin/arkrag` | zip（ArkWork 插件） | 工具注册 + dock 面板 | ArkWork ctx API（无 npm 依赖） |

**包结构（core）**：

```
com.arkrag.core
├── config/      ArkRagConfig（yml 初值载体；Embedding/Chunking/Ingest/Chat record）
├── model/       KnowledgeBase / DocInfo / DocStatus / ChunkMeta / SearchHit
├── exception/   ErrorCode（统一错误码+HTTP 映射）/ ArkRagException
├── settings/    SettingsService（配置合成+热生效广播）/ ModelSettings
├── embedding/   EmbeddingService（OpenAI 兼容嵌入，指纹缓存）
├── chat/        ChatService（OpenAI 兼容对话，指纹缓存）
├── pipeline/    IngestService（摄入状态机 + 同 KB 串行执行器）
├── search/      SearchService（检索编排 + 维度护栏）
├── ask/         AskService（提示词组装 + 引用对齐）
├── service/     KnowledgeBaseService（KB CRUD + 文档元数据持久化）
└── store/       IndexRegistry（快照持有者）/ VectorIndex（索引算法+持久化）/ MetaFiles / StoredVector
```

**包结构（server）**：`api/`（Health/Kb/Document/Search/Ask/Settings Controller + Dto + GlobalExceptionHandler + AdminWebConfig）、`mcp/`（McpHttpConfig + McpTools）、`security/`（AuthFilter）、`config/`（ArkRagServerProperties + CoreConfig 装配）。

## 3. 核心数据模型与存储布局

### 3.1 实体关系

```
KnowledgeBase 1 ──── n DocInfo 1 ──── n StoredVector(vector, ChunkMeta)
      │                    │
      └─ embeddingModel    └─ status: PENDING→PARSING→EMBEDDING→READY|FAILED
         dims（快照）          name 同 KB 唯一（覆盖语义）
```

- **KB**：`id(UUID)/name/description/embeddingModel/dims/chunkSize/chunkOverlap/createdAt/updatedAt`。`embeddingModel/dims` 建库时从当前 Embedding 配置快照（dims=0 表示待首次摄入回填真实维度）——这是维度护栏的依据。
- **Doc**：`id/kbId/name/ext/size/contentHash/chunkCount/status/error/时间戳`。
- **ChunkMeta**（向量负载）：`kbId/docId/docName/chunkIndex/text` —— 引用溯源的最小完备集，检索/问答命中后原样带回。

### 3.2 文件布局（`{dataDir}` 默认 `~/.arkrag/data`）

| 文件 | 内容 | 写入方式 |
|------|------|----------|
| `kbs.json` | KB 列表 | 整文件读 + 整文件原子替换（写锁串行） |
| `kb/{id}/docs.json` | 文档列表（含状态） | 同上；状态每次变迁立即落盘 |
| `kb/{id}/vectors.json` | `[{id, vector[], meta{…}}]` | 单写者防抖 500ms；唯一临时文件 + `ATOMIC_MOVE` |
| `kb/{id}/files/{docId}_{name}` | 原始上传文件 | 重建索引的数据源 |
| `settings.json` | 运行时模型配置（明文 apiKey） | 保存即原子替换 + 变更广播 |

**为什么是文件不是数据库**：单机单进程、总量 ≤ 数万块级别，整文件读写足够；换来"拷目录=备份、删目录=重置、零运维"（ADR-2）。所有 JSON 写入都是"唯一临时文件 → 原子 move"，进程任意时刻被杀不会留下半截文件。

## 4. 关键流程时序

### 4.1 文档摄入（异步状态机）

```
POST /documents ──► 校验(扩展名白名单/≤50MB/非空) ──► 落盘 files/{docId}_{name}
                     ──► upsertDocMeta(PENDING)【持有 KB 锁】──► 入队即 202

工作线程（每 KB 单线程队列，保证同 KB 串行）：
  PENDING → PARSING   Tika 抽文本（空文本层 → FAILED）
          → 切块       DocumentSplitters.recursive(chunkSize, overlap)（段落边界优先）
          → EMBEDDING  按 batch=32 调 Embedding API（退避重试 ×3）
                       工作副本 = snapshot 拷贝 + 新 StoredVector 追加
          → 换快照     LoadedIndex.replaceSnapshot(List.copyOf(working))   ← 读路径无锁的切换点
          → 防抖落盘   requestPersist(500ms)
          → READY      chunkCount 回填；docs.json 即时写盘
  任一步异常 → FAILED(error=人话原因) 落盘
同名覆盖：管线开头移除同 KB 同名旧 Doc 的向量与文件（读改写全程持 KB 锁）
停机：beginShutdown → awaitTermination(≤10s) → IndexRegistry.close()（全量落盘）
```

### 4.2 检索（热路径，全程无锁）

```
POST /search ─► AuthFilter ─► 校验(query 1..2000 / topK 1..50 / minScore)
  ─► 解析目标 KB（缺省全部）
  ─► EmbeddingService.embedOne(query)            （未配置 → 409）
  ─► 逐 KB 维度护栏：queryDims != kb.dims → 409 + rebuild 指引（禁止混用新旧向量）
  ─► VectorIndex.search(volatile 快照, kbName, queryVec, topK, minScore)
       暴力余弦：遍历快照逐点计算，阈值过滤，排序取前 K
  ─► 合并多库结果按分数降序 → hits（kbName/docName/chunkIndex 元数据齐备）
```

### 4.3 问答（rag_ask）

```
POST /ask ─► ChatService.isConfigured()? 否 → 409 CHAT_NOT_CONFIGURED
  ─► SearchService.search(topK)                 （空命中 → 固定拒答文案, citations=[]）
  ─► AskService.buildPrompt：中文提示词 = 角色 + "仅依据资料回答 + [n] 标注 + 资料不足固定拒答"
      + 每条资料带（来源：docName · 库 · 第 N 块）
  ─► ChatService.chat(prompt)（重试 ×2）
  ─► citations 由服务端从 hits 生成（index=1..N 与提示词编号一致）——模型不负责编号，杜绝错位
```

### 4.4 模型热配置

```
PUT /settings/model ─► 校验(URL http/https、model 非空；空/掩码 apiKey=保留旧值)
  ─► 合并写 settings.json（原子替换）─► 广播 onChange
  ─► 下一次 embedAll/chat() 时指纹比对（baseUrl|key|model|dims）不一致 → 重建模型实例
换 Embedding 模型的迁移路径：老 KB.dims 不匹配新查询 → 409 → POST /kb/{id}/rebuild
  （rebuild 用 files/ 里留存的原始文件重走摄入管线，逐文档重新排队）
```

## 5. 并发与一致性模型

| 临界区 | 策略 | 理由 |
|--------|------|------|
| 向量索引读（搜索热路径） | **无锁**：读 volatile 不可变快照 | 搜索是最高频操作；写入方换引用，在途搜索用旧快照自然完成 |
| 向量索引写（摄入/删除/覆盖） | **KB 锁内**：拷贝工作副本 → 变更 → `List.copyOf` 换引用 | 单写者消除竞态；拷贝代价 O(n) 引用，可接受 |
| docs.json / kbs.json 读改写 | KB 锁（docs）+ 全局写锁（kbs）串行 | 提交登记与管线共用同一把 KB 锁（v1.1 修复并发上传丢登记） |
| 磁盘原子写 | 唯一临时文件名（UUID 后缀）+ `ATOMIC_MOVE` | 并发写不互踩 tmp（v1.1 修复）；崩溃不留半截文件 |
| 索引落盘 | 单线程调度器防抖 500ms + 停机强制 persistAll | 批内多次变更一次 IO；停机兜底 |
| 同 KB 摄入 | 每 KB 单线程执行器队列 | 两文档交错写同一快照无法合并，串行是正确性前提 |
| 跨 KB 摄入 | 全局 worker 池（默认 2） | 吞吐受外部 Embedding API 限制，多 worker 仅提高排队深度 |

## 6. 配置体系

```
生效配置 = settings.json（运行时，控制台写）  >  application.yml / 环境变量（部署初值）
```

- `SettingsService` 是唯一合成点：`effectiveEmbedding()` / `effectiveChat()`；缺组回落 yml；yml 组为空 → 未配置。
- **热生效**：EmbeddingService/ChatService 持"配置指纹 → 模型实例"缓存；`onChange` 广播后下一次调用指纹不符即重建。无需重启。
- **apiKey 保护**：GET 一律脱敏（`****尾4位`）；PUT 收到空串/含 `*` 的串视为"未修改"保留旧值——控制台回显脱敏值不会误清配置。
- **契约语义**：PUT 的某组传 `null` = 清除该组回落 yml（控制台保存时两组都显式提交，不受影响）。

## 7. MCP 协议层

- **实现**：MCP 官方 Java SDK 2.0.1（`mcp-core` + `mcp-json-jackson3`，spec 2025-06-18）。
- **双端点**：`/mcp`（Streamable HTTP，`HttpServletStreamableServerTransportProvider`）与 `/mcp/sse`+`/mcp/message`（SSE，`HttpServletSseServerTransportProvider`）。两个传输各自构建 `McpSyncServer`，但共享**同一份** `SyncToolSpecification` 列表（`McpTools.all()`）——工具行为单一真相源。
- **工具面**：

| 工具 | 输入 | 行为 |
|------|------|------|
| `rag_search` | query(必填), kb_ids?, top_k? | 检索 → JSON hits（含出处/分数） |
| `rag_list_kbs` | — | KB 列表 + embedding_configured |
| `rag_ask` | query(必填), kb_ids?, top_k? | 检索+Chat → {answer, citations, took_ms} |

- **错误语义**：业务失败返回 `CallToolResult(isError=true)` + 分错误码的人话（`McpTools.humanize`），模型可读可行动；意外异常记日志返回通用文案。
- **stdio 桥**（`arkrag-mcp-stdio`）：`StdioServerTransportProvider` + 静态工具清单。设计要点：
  - `initialize/tools/list` **不依赖服务在线**——ArkWork 启动即拉桥握手，若桥因服务离线而握手失败，工具永远进不了模型；
  - 仅 `tools/call` 实时代理（JDK HttpClient，search 60s / ask 120s 超时），连接失败/401/业务错误统一映射 isError + 修复指引；
  - 桥是纯代理：不持有索引、不读写数据目录，杜绝双进程写同一索引；
  - **stdout 是协议帧通道**：日志全部走 stderr（slf4j-simple），main 不打印任何 stdout。

## 8. 管理控制台

- **形态**：单文件 `arkrag-server/src/main/resources/admin/index.html`（零依赖、零构建），经 `AdminWebConfig` 挂在 classpath `/admin/`（`/admin` 302 到 index.html），随 fat-jar 分发。
- **鉴权边界**：静态 HTML/JS 免鉴权（无敏感数据）；全部数据请求走鉴权 API。token 存 localStorage，`api()` 封装统一带 `X-Api-Key` 并在 401 时弹出令牌条。token 验证用**受保护接口** `GET /kb`（`/health` 免鉴权探不出 token 对错）。
- **主题**：消费与插件面板同一套 CSS 变量契约（`--bg-*/--text-*/--accent/...`）；本地默认跟随 `prefers-color-scheme`，右上角手动切换（`data-mode` 覆盖）。
- **能力对齐**：知识库/文档/检索与插件面板同源（同一 REST API），另多出"问答试用"与"模型配置"两块（插件面板不含，避免 ArkWork 侧存 apiKey 的扩散）。

## 9. ArkWork 插件

- **清单**（plugin.json，schemaVersion 1.1）：id `arkrag.rag`、kind `tool`、activation `onStartup`、permissions `["net","tools.register","storage","views.register"]`（全部在宿主白名单内）；`provides.tools` 声明三工具（**双闸**：声明 + 运行期 `ctx.ark.tools.register` 缺一不可）。
- **Host 半（main.js，utilityProcess）**：注册视图 `view:arkrag`（Book 图标 dock 面板）；工具 handler 经 `ctx.ark.net.fetch` 调服务 REST（返回 `{status,headers,body}` 已适配），模型结果格式化为"编号引用列表"文本；桥方法 `arkrag` 按 `op` 分发面板操作（getConfig/saveConfig/testConnection/listKbs/uploadFiles/...）；配置存 `ctx.ark.storage`。
- **Client 半（index.html，iframe 沙箱）**：`lifecycle/activate` 握手（sessionId + 主题令牌注入 `applyTheme`）→ `host.call` 桥调用 → `reply` 回执；视觉仅消费宿主白名单 token（`var(--x, 回退值)`）。
- **上传通道**：iframe FileReader 读文件为 base64 → Host 半 JSON `POST {title,base64}`（服务端解码走与 multipart 相同的 `submitFile`）——规避沙箱桥上的 multipart 不确定性；面板建议 ≤20MB。

## 10. 安全设计

| 面 | 措施 |
|----|------|
| 鉴权 | 全 API（含 MCP）要求 Bearer/X-Api-Key；`MessageDigest.isEqual` 常量时间比较；token <16 位拒绝启动 |
| 网络面 | 默认绑定 127.0.0.1；CORS 关闭 |
| 免鉴权面 | 仅 `/api/v1/health`（无敏感数据）与 `/admin` 静态资源（HTML/JS） |
| 上传 | 扩展名白名单（6 种）、≤50MB、文件名净化（UUID 前缀 + 去路径/控制字符）防目录穿越 |
| 敏感数据 | apiKey 明文仅存 `settings.json`（local-first 口径）与插件 storage；接口回显脱敏；日志不打印 token 与文档全文 |
| 注入面 | 检索 query 长度限制；提示词内容由服务端组装（模型输出不回注）；页面渲染统一 `esc()` HTML 转义 |

## 11. 性能与容量边界

| 维度 | 边界 | 依据 |
|------|------|------|
| 单库块数 | ≤10 万块 | 自研暴力余弦（O(n) 扫描）实测路径 p95<200ms；超出建议拆库或接外部向量库（扩展点） |
| 检索延迟 | 10 万块内 p95 <200ms（不含 Embedding API 往返） | 纯内存 float 点积 + 排序 |
| 摄入吞吐 | 受外部 Embedding API 主导（批 32 × 2 worker） | 引擎侧仅 CPU 轻载 |
| 启动时间 | 快照全量加载，秒级（十万块内） | JSON 解析为主 |
| 内存 | ≈ 块数 × dims × 4B（向量）+ 文本 | 10 万块 × 1024 维 ≈ 400MB 量级，需预留堆 |
| 文件数 | 无上限（文件系统约束内） | 每库一目录 |

## 12. 关键设计决策记录

| # | 决策 | 背景/备选 | 理由 |
|---|------|-----------|------|
| ADR-1 | RAG 管线用 **LangChain4j**（非 Spring AI / 手写） | Spring AI MCP starter 顺、但 RAG 抽象薄 | 解析/切块/EmbeddingModel/ChatModel 抽象全；Advanced RAG 组件（rerank 等）可平滑接入 |
| ADR-2 | **文件即数据库**（无 DB/ES/MinIO） | 市面 RAG 普遍 ES+MySQL+Redis | local-first 单 jar 交付；拷目录=备份；量级内性能足够 |
| ADR-3 | **自研向量索引**（弃 InMemoryEmbeddingStore） | 其 Entry 包私有不可枚举（删除/统计/重建都需要枚举）、自定义负载序列化不可靠 | ~100 行可控实现；接口边界保留外部向量库（P1 Qdrant/Milvus） |
| ADR-4 | **MCP = HTTP 内嵌 + 独立 stdio 桥** | ArkWork 客户端仅支持 stdio；桥若进程内跑引擎则双进程写同一索引 | 桥为纯代理：initialize 不依赖服务在线（否则 ArkWork 启动握手失败工具就没了） |
| ADR-5 | **控制台零依赖单页**（无 React/Vite） | 静态资源随 jar 分发 | 无构建链、无 CDN、离线可用；与插件面板共用 token 契约 |
| ADR-6 | **模型配置 runtime > yml + 热生效** | 用户要求"独立配置大模型" | 控制台改配置不重启；settings.json 属用户数据，升级部署不丢 |
| ADR-7 | **citations 服务端生成** | 模型自行编号会错位/幻觉 | 提示词编号与服务端 citations 同源（hits 顺序），n 严格对齐 |
| ADR-8 | **插件上传走 base64 JSON** | 沙箱桥上 multipart 行为不可控 | 同一服务端代码路径；面板限 20MB，大文件走 API |
| ADR-9 | **不做**：OCR/版面解析、BM25 混合（默认形态）、多租户、对话历史 | 范围控制 | 见 v1.0 PRD §4 与 v1.1 release-goal |

## 13. 扩展指南

**新增一个 MCP 工具**（三处同步，缺一即契约分叉）：
1. `arkrag-server/mcp/McpTools.all()` 加 `SyncToolSpecification`（tool + callHandler）；
2. `arkrag-mcp-stdio/BridgeMain` 加同名静态 Tool + handler 分支（若需桥代理）；
3. 若需暴露给 Agent 工具表：`plugin/arkrag/plugin.json` `provides.tools` + `main.js` `tools.register`。
   同步更新契约文档 §5.2 与测试用例。

**接入外部向量库（Qdrant/Milvus，预留点）**：`VectorIndex` 的调用面收敛在 `IndexRegistry.LoadedIndex`（snapshot 换引用）与 `SearchService`/`IngestService`。实现一个同语义的 `VectorStore` 接口（`search/removeDoc/addAll/load/save`）并按 `arkrag.store.type` 分派即可；快照换引用模型对外部库同样成立（换连接/集合引用）。

**接入两阶段检索（rerank，预留点）**：`SearchService.search` 返回前插入 `RerankModel`（LangChain4j `ScoringModel` 语义：召回 topN → 精排 topK）；配置挂在 settings 的 embedding 组旁；默认关闭零依赖。

**新增文档类型**：`IngestService.ALLOWED_EXT` 加扩展名（Tika 支持即生效）；同步 USAGE.md §5 白名单文案与测试。

**修改提示词**：`core/ask/AskService.buildPrompt`（含拒答文案常量 `NO_HIT_ANSWER`）；改动需回归 TC-ASK-*。

---

*变更记录见 [CHANGELOG.md](CHANGELOG.md)；测试证据见 [v1.1/05-function-test-report.md](v1.1/05-function-test-report.md)；UI 验收基线见 [v1.0/prototype](v1.0/prototype/index.html)。*
