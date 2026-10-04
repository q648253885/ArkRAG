# 系统设计：ArkRAG v1.0

> 版本：v1.0 ｜ 日期：2026-10-04 ｜ 状态：已确认（用户指示"继续执行"；编码中发现设计不可行先回溯本文档）
> 架构借鉴来源：RAG 管线分层借鉴 LangChain4j 官方 Advanced RAG（`01-research.md` §5）；知识库文件组织参考 AnythingLLM 工作区；插件契约对齐 ArkWork v0.45 插件系统（schemaVersion 1.1）。

## 1. 技术选型

| 层面 | 选型 | 理由 |
|------|------|------|
| 语言/运行时 | Java 21（编译 target 21，本机 JDK 22 构建） | LTS；Spring Boot 3.x / LangChain4j 基线；本机已装 JDK 22 |
| 后端框架 | Spring Boot 3.5.x（Web MVC，非 WebFlux） | 生态成熟、fat-jar 交付简单；stdio 桥不依赖容器 |
| RAG 框架 | LangChain4j 1.x（BOM） | DocumentParser/Splitter/EmbeddingModel/EmbeddingStore 抽象齐全，Advanced RAG 组件可平滑接入 P1 rerank |
| 文档解析 | dev.langchain4j:langchain4j-document-parser-apache-tika | 一个依赖覆盖 PDF/DOCX/PPTX/HTML/TXT/MD 文本层 |
| 向量存储（默认） | **自研内存暴力索引**（余弦相似度 + topK，volatile 不可变快照无锁搜索）+ 自管 JSON 持久化 | 缺陷回溯（2026-10-04）：LangChain4j InMemoryEmbeddingStore 的 Entry 字段包私有、无法枚举条目，且其自带序列化对自定义负载类型不可靠；自研实现 ~100 行、完全可控。接口抽象保留 Qdrant/Milvus（P1）可插拔 |
| MCP | io.modelcontextprotocol.sdk:mcp 1.0.x | 官方 SDK（spec 2025-06-18）；服务端内嵌 HTTP 传输（SSE + Streamable HTTP），stdio 桥用 SDK StdioServerTransportProvider |
| HTTP 文档 | springdoc-openapi（/v3/api-docs + swagger-ui） | 契约可自查，开发/联调成本低 |
| JSON | Jackson（Spring Boot 自带 + MCP SDK 共用） | 统一序列化 |
| 构建 | Maven 多模块（Maven 发行版随项目放置 `tools/maven`，仓库带 `mvnw`） | 本机无 mvn/gradle；Central 连通性已验证 |
| 插件（ArkWork 侧） | 原生 JS（CJS main.js + 静态 index.html），零 npm 依赖 | ArkWork 插件沙箱直接加载，zip 安装 |
| 第三方服务 | OpenAI 兼容 Embedding/Chat API（DeepSeek/通义/Ollama/vLLM） | 与 ArkWork models.json 惯例一致；不内置模型推理 |

## 2. 架构总览

```mermaid
flowchart LR
    subgraph clients[客户端]
        AW[ArkWork 插件<br/>kind=tool]
        AWMCP[ArkWork MCP 客户端<br/>stdio]
        OMCP[其他 MCP 客户端<br/>Claude/ZCode]
        CURL[curl / 脚本]
    end
    subgraph arkrag[ArkRAG 进程（Spring Boot 单体）]
        R[REST /api/v1/**<br/>Bearer 鉴权]
        M[MCP HTTP 端点<br/>/mcp (Streamable) + /mcp/sse (SSE)]
        S[服务层<br/>KbService / IngestService / SearchService]
        E[检索引擎 arkrag-core<br/>解析→切块→Embedding→Store]
        D[(数据目录<br/>kbs.json / kb/*/docs.json<br/>kb/*/vectors.json / files/)]
    end
    B[stdio 桥 arkrag-mcp-stdio<br/>JSON-RPC over stdio ↔ HTTP 代理]
    AW -->|net.fetch HTTP| R
    AWMCP -->|spawn 子进程| B -->|HTTP| R
    OMCP --> M
    CURL --> R
    R --> S; M --> S; S --> E --> D
```

- 三种接入共用同一服务层与索引，无第二真相源；stdio 桥是纯代理（无状态、无索引文件访问），避免双进程写同一索引。
- ArkWork 插件与 MCP stdio 是并列的两种接入（PRD §4.3 形态 A/B），互不依赖。

## 3. 模块划分

| 模块 | 职责 | 依赖 | 对应 PRD 功能 |
|------|------|------|----------------|
| `arkrag-core` | 检索引擎纯 Java 库：KB/文档领域模型、摄入管线（解析/切块/嵌入/落库）、检索、索引注册表与持久化、统一异常与错误码 | langchain4j、tika-parser、Jackson | F1 F2 F3 F7 |
| `arkrag-server` | Spring Boot 装配：REST API、鉴权过滤器、MCP HTTP 端点（SSE + Streamable）、/health、springdoc、配置绑定 | core、mcp SDK、springdoc | F4 F5 F7 |
| `arkrag-mcp-stdio` | stdio 桥：官方 SDK stdio server，静态工具清单，tools/call 代理到服务 HTTP；连接失败映射为 isError | mcp SDK、JDK HttpClient | F6 |
| `plugin/`（非 Maven） | ArkWork 插件：plugin.json + main.js（Host 半）+ index.html（Client 半） | ArkWork ctx API | F11 F12 F13 F14 |
| `dist/`、`scripts/` | 打包产物：server fat-jar、stdio 桥 jar+启动脚本、插件 zip、示例配置、快速开始 | — | 交付 |

## 4. 数据模型

**知识库 KB**（`{dataDir}/kbs.json`，数组整体读写）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | string | PK，UUID | |
| name | string | 必填，≤64，安装内唯一 | 显示名 |
| description | string | ≤256 | |
| embeddingModel | string | 建库时快照 | 该库向量所用模型名 |
| dims | int | >0 | 向量维度（维度一致性校验依据） |
| chunkSize / chunkOverlap | int | 默认 1000 / 150（字符） | 建库时可配 |
| createdAt / updatedAt | long | epoch ms | |

**文档 Doc**（`{dataDir}/kb/{kbId}/docs.json`）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | string | PK，UUID | |
| kbId | string | FK → KB | 级联删除 |
| name | string | 同 KB 内唯一（覆盖语义） | 原文件名或文本标题 |
| ext | string | 白名单：pdf/docx/pptx/html/txt/md | |
| size | long | ≤50MB | |
| contentHash | string | sha256 | 去重提示用 |
| chunkCount | int | | READY 后回填 |
| status | enum | PENDING→PARSING→EMBEDDING→READY \| FAILED | 摄入状态机 |
| error | string? | | FAILED 原因 |
| createdAt / updatedAt | long | | |

**向量索引**（`{dataDir}/kb/{kbId}/vectors.json`）：自管 JSON（`[{id, vector[], meta:{kbId,docId,docName,chunkIndex,text}}]`），加载为内存 List 快照（volatile 换引用，搜索无锁）；每条 embedding 的元数据负载：`{kbId, docId, docName, chunkIndex, text}`。原始上传文件存 `{dataDir}/kb/{kbId}/files/{docId}_{sanitizedName}`（重建索引用）。容量边界：单库 ≤10 万块（暴力余弦 p95 < 200ms 量级）。

- 生命周期：KB 删除 → 级联删 docs.json/vectors.json/files/；同名文档覆盖 → 删旧 chunks（按 docId 前缀匹配）再入库。
- 索引常驻内存，启动全量加载；写路径单写者线程 500ms 防抖 + 临时文件原子替换；读路径读 volatile 快照引用（写时构建新快照换引用，搜索无锁）。

## 5. 接口定义

通用约定：除 `/api/v1/health` 外全部要求 `Authorization: Bearer <token>`（兼容 `X-Api-Key`）；错误统一 `{"error":{"code":"...","message":"...","details":...}}`；时间均为 epoch ms。

### 5.1 REST（/api/v1）

**GET /health** — 存活与配置自检（免鉴权，供插件"测试连接"）。
响应 `{"status":"UP","version":"1.0.0","embedding":{"configured":true,"model":"bge-m3","dims":1024},"store":{"type":"inmemory"}}`

**POST /kb** — 建库。请求 `{name, description?, chunkSize?, chunkOverlap?}`。400 VALIDATION（名称空/超长/重名）。→ 200 KB。

**GET /kb** — 列表（含 `documentCount/chunkCount` 统计）。→ 200 `[KB]`。

**GET /kb/{kbId}** — 详情 + 全部文档及摄入状态。404 KB_NOT_FOUND。

**DELETE /kb/{kbId}** — 级联删除。→ 204。

**POST /kb/{kbId}/documents** — 摄入。`multipart/form-data`（`files` 多文件）或 `application/json` `{title, text}`（纯文本）或 `{title, base64}`（二进制，插件桥路径用，面板侧建议 ≤20MB）。异步：→ 202 `[{docId, name, status:"PENDING"}]`。
错误：404 KB_NOT_FOUND；415 UNSUPPORTED_FILE_TYPE；413 FILE_TOO_LARGE；409 EMBEDDING_NOT_CONFIGURED。

**GET /kb/{kbId}/documents** — 文档列表（含 status/error/chunkCount）。

**DELETE /kb/{kbId}/documents/{docId}** — 删文档及其 chunks。404 DOC_NOT_FOUND。→ 204。

**POST /kb/{kbId}/rebuild** — 用已存原始文件重建全部向量（换 embedding 模型后用）。→ 202。

**POST /search** — 检索。请求 `{query(必填,1..2000), kbIds?(默认全部), topK?(1..50,默认5), minScore?(0..1,默认0)}`。
响应 `{"hits":[{"kbId","kbName","docId","docName","chunkIndex","text","score"}],"tookMs":212}`
错误：400 VALIDATION；409 EMBEDDING_NOT_CONFIGURED / EMBEDDING_DIMENSION_MISMATCH（提示 rebuild）。

**POST /ask**（P1）— `{query, kbIds?, topK?}` → `{"answer","citations":[{index,docName,chunkIndex,score}],"tookMs"}`；未配 chat LLM → 409 CHAT_NOT_CONFIGURED。

### 5.2 MCP 工具（HTTP 端点与 stdio 桥同契约）

- 端点：`/mcp`（Streamable HTTP，spec 2025-06-18）与 `/mcp/sse`（SSE 兼容旧客户端）；鉴权同 REST。
- serverInfo：`{name:"arkrag", version:"1.0.0"}`。

| 工具 | inputSchema | 输出（content[0].text = JSON 字符串） |
|------|-------------|----------------------------------------|
| `rag_search` | `{query: string(必填), kb_ids?: string[], top_k?: int 1..50}` | `{"hits":[{kb_id,kb_name,doc_id,doc_name,chunk_index,text,score}],"took_ms"}` |
| `rag_list_kbs` | `{}` | `[{"id","name","description","document_count","chunk_count"}]` |

调用失败（服务不可达/鉴权失败/参数错误）→ `isError:true` + 人话 message；**stdio 桥的 initialize/tools/list 永不依赖服务在线**（静态清单），仅 tools/call 实时代理。

### 5.3 stdio 桥环境约定

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `ARKRAG_URL` | `http://127.0.0.1:8964` | 服务地址 |
| `ARKRAG_TOKEN` / `ARKRAG_TOKEN_FILE` | 必填其一 | token 或 token 文件路径（配合 ArkWork `secret:` env 惯例） |

ArkWork 侧 `mcp-servers.json` 示例：`{"id":"arkrag","transport":"stdio","command":"/path/bin/arkrag-mcp-stdio","env":{"ARKRAG_TOKEN":"secret:arkragToken"}}`。

### 5.4 错误码总表

| code | HTTP | 含义 |
|------|------|------|
| UNAUTHORIZED | 401 | token 缺失/错误 |
| VALIDATION | 400 | 参数校验失败 |
| KB_NOT_FOUND / DOC_NOT_FOUND | 404 | 资源不存在 |
| UNSUPPORTED_FILE_TYPE | 415 | 扩展名不在白名单 |
| FILE_TOO_LARGE | 413 | 单文件 >50MB |
| EMBEDDING_NOT_CONFIGURED | 409 | 服务未配 Embedding 模型 |
| EMBEDDING_DIMENSION_MISMATCH | 409 | 查询向量维度 ≠ KB.dims（提示 rebuild） |
| INGEST_FAILED | 500 | 摄入失败（details 带阶段与原因） |
| SERVER_ERROR | 500 | 其他内部错误 |

## 6. 关键流程实现

**流程 1：文档摄入（异步状态机 + 覆盖语义）**
1. REST 收文件 → 校验扩展名/大小 → 落盘 `files/{docId}_{name}` → 建 Doc(PENDING) 写 docs.json → 入队即返回 202。
2. 工作线程（全局 2 并发，**同 KB 串行**）取任务：PENDING→PARSING（Tika 抽文本，空文本 → FAILED"文本层为空"）→ 按 KB 的 chunkSize/overlap 递归切块 → EMBEDDING（批 32 条，失败退避重试 ×3）→ 以 `Embedding{vector, metadata{text,kbId,docId,docName,chunkIndex}}` 批量写入该 KB 工作索引 → READY 回填 chunkCount。
3. 每状态变迁更新 docs.json；索引写入后触发 500ms 防抖持久化（临时文件 + 原子 move）。
4. 覆盖：同 KB 出现同名 name → 先删旧 doc 的 chunks 与旧文件再走步骤 1（docId 变化，语义=替换版本）。
5. 服务关闭走 shutdown hook：等待在途任务 ≤10s → 强制持久化 → 退出。

**流程 2：检索（无锁热路径 + 维度护栏）**
1. 鉴权 → 参数校验 → 解析目标 KB 集合（缺省全部）。
2. 取查询向量（Embedding 未配 → 409）；逐 KB 校验 `queryDims == kb.dims`（不等 → 409 + 提示 rebuild，禁止混用）。
3. 读 volatile 索引快照，逐 KB `search(embedding, topK)` → 合并重排 → minScore 过滤 → 组装 hits（附 kbName/docName 元数据）。
4. 全程零锁（快照不可变）；写入方换引用，正在进行的搜索用旧快照自然完成。

**流程 3：插件工具调用链（Host 半）**
1. onStartup 激活 → main.js 从 `ctx.ark.storage` 读 `{baseUrl, token}` → `ctx.ark.tools.register(rag_search/rag_list_kbs)`（plugin.json `provides.tools` 已声明，双闸）。
2. Agent 调 `plugin__arkrag.rag__rag_search` → handler `ctx.ark.net.fetch(baseUrl + /api/v1/search, {headers:{Authorization:Bearer token}})` → 解析 JSON → 格式化为模型友好的文本块（编号引用列表）返回。
3. 未配置/不可达/401 → 返回带修复指引的 isError 文本（"打开 ArkRAG 面板 → 连接设置"）。
4. 面板 iframe 经 host.call 桥调用 Host 半的 `getConfig/saveConfig/testConnection/listKbs/uploadDocs/deleteKb`（实现按 Git Manager 范例桥协议）。

## 7. 非功能设计

- **性能**：检索热路径无锁纯内存，10 万块级 p95 < 200ms（不含 embedding API 往返）；摄入吞吐受限于外部 Embedding API，批 32 并发 2 worker；docs.json/vectors.json 写防抖 500ms。
- **安全**：默认绑定 `127.0.0.1`；token 未设置（或 <16 字符）→ **拒绝启动**并日志说明；token 比较用常量时间；上传文件名净化（UUID 前缀，去路径分隔符）；扩展名白名单 + 50MB 上限；CORS 关闭；日志不打印 token 与全文（打印 docName/长度）。
- **异常处理**：统一错误码（§5.4）；Embedding API 退避重试 ×3；stdio 桥 HTTP 超时 30s，服务不可达 → isError"服务未启动，请先运行 arkrag-server"；优雅停机持久化。
- **可观测**：结构化日志（ingest 任务各阶段、搜索耗时分布、索引持久化）；`/health` 暴露版本与配置自检，供插件测试连接与运维排查。

## 8. 接口/Service 文档与注释规范

- 注释风格：**Java 用 Javadoc**（类/公共方法必填：职责一句话、参数含义与约束、返回结构、错误码与触发场景）；插件 JS 用 JSDoc 风格块注释。
- Service 公共方法注释须写关键业务规则（如"同 KB 内同名文档=覆盖"）与副作用（写文件/调外部 API）。
- 复杂逻辑注释写"为什么"（如：为何快照换引用而非加锁、为何 initialize 不依赖服务在线）。
- 同步规则：接口行为变更 → 同一提交内更新本文档 §5 + 方法 Javadoc + 测试用例，三者不允许不一致。

| 日期 | 接口 | 变更内容 | 影响端 |
|------|------|----------|--------|
| — | — | — | — |

## 9. 目录结构约定

```
ArkRAG/
├── pom.xml                      # Maven 父 POM
├── mvnw, .mvn/                  # Maven Wrapper（自举，无需本机装 Maven）
├── tools/maven/                 # 首次构建用 Maven 发行版（生成 wrapper 前自举用）
├── arkrag-core/                 # 检索引擎（纯 Java）
│   └── src/main/java/com/arkrag/core/{model,pipeline,search,store,config,exception}
├── arkrag-server/
│   └── src/main/java/com/arkrag/server/{api,mcp,security,config}
│   └── src/main/resources/application.yml
├── arkrag-mcp-stdio/
│   └── src/main/java/com/arkrag/stdio/…
├── plugin/arkrag/               # ArkWork 插件（随交付打 zip）
│   ├── plugin.json  ├── main.js  ├── index.html  └── package.json
├── scripts/                     # start-server.sh/.bat、install-plugin.md
├── dist/                        # 构建产物（fat-jar、插件 zip）
├── docs/                        # 本文档体系 + 全局三件套
└── testcases→ docs/v1.0/testcases/
```

## 变更记录

| 日期 | 原因 | 改动点 | 影响范围 |
|------|------|--------|----------|
| 2026-10-04 | 首版 | 全文 | — |

---

**门禁**：本文档经用户确认（用户指示"继续执行"）后，进入编码阶段。
