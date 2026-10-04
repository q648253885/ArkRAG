# ArkRAG —— 本地优先的 Java RAG 独立服务（管理控制台 + MCP + HTTP）

一个单 jar 的语义检索与知识问答服务：文档摄入（PDF/DOCX/PPTX/HTML/TXT/MD）→ 切块 → Embedding → 语义检索 / 带引用问答 → 引用溯源。
自带**管理控制台**（`/admin`：知识库、文档上传、检索/问答试用、模型在线配置热生效），同时提供 **MCP 协议**（Streamable HTTP / SSE / stdio 桥）与 **HTTP API**，为 [ArkWork](../ArkWork) 提供官方插件（rag_search / rag_list_kbs / rag_ask 三工具）。

```
┌ 管理控制台 /admin ─┐  ┌ ArkWork 插件 ─┐  ┌ ArkWork MCP(stdio) ┐  ┌ Claude/ZCode ┐  ┌ curl ┐
│   配模型/建库/上传  │  │ net.fetch+面板 │  │   stdio 桥          │  │  MCP HTTP    │  │ REST │
└─────────┬─────────┘  └──────┬────────┘  └─────────┬─────────┘  └──────┬───────┘  └──┬───┘
          └────────────────────┴─────────┬──────────┴───────────────────┴─────────────┘
                                arkrag-server（Spring Boot 单体，Bearer 鉴权）
                     摄入管线 / 自研向量索引 / 模型热配置 / rag_ask 带引用问答
```

## 快速开始（独立使用，浏览器全流程）

前置：JDK 17+。Embedding/Chat 模型**可在控制台在线配置，无需改配置文件**。

```bash
./mvnw -q -DskipTests package && bash scripts/build-dist.sh   # 或直接用 dist/ 现成产物
ARKRAG_TOKEN=my-secret-token-2026 java -jar dist/arkrag-server.jar
open http://127.0.0.1:8964/admin      # 输入 token → 模型配置页填 Embedding/Chat → 保存（热生效）→ 建库上传 → 问答
```

离线冒烟（无外部 API）：`node scripts/mock-embedding-server.mjs 8965 1024`，控制台模型页把两个 baseUrl 都指到 `http://127.0.0.1:8965/v1`（key/model 随意）。

## 接入 ArkWork

**方式 A：官方插件（推荐）**
1. ArkWork → 设置 → 插件 → 从 zip 安装 → 选 `dist/arkrag-plugin-1.0.0.zip`；
2. 侧边栏出现 **Book 图标的 RAG 面板** → 连接设置里填服务地址与 token → 测试连接 → 保存；
3. 面板里建知识库、上传文档，Agent 对话时自动可用 `rag_search` / `rag_list_kbs` 工具。

**方式 B：MCP stdio 直连**（设置 → MCP 服务器 → 添加）

```json
{ "id": "arkrag", "transport": "stdio",
  "command": "<本项目路径>/scripts/arkrag-mcp-stdio.sh",
  "env": { "ARKRAG_TOKEN": "secret:arkragToken" } }
```

`secrets.json`（`{arkworkDir}/secrets.json`）登记：`{ "arkragToken": "my-secret-token-2026" }`。

**方式 C：其他 MCP 客户端**：端点 `http://127.0.0.1:8964/mcp`（Streamable）或 `/mcp/sse`（兼容），请求头带 token。

## HTTP API 一览（鉴权：`Authorization: Bearer <token>` 或 `X-Api-Key`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin` | 管理控制台（静态页免鉴权，数据 API 全鉴权） |
| GET | `/api/v1/health` | 免鉴权探活 + 配置自检 |
| POST/GET | `/api/v1/kb` | 建 / 列知识库 |
| GET/DELETE | `/api/v1/kb/{id}` | 详情（含摄入状态）/ 级联删除 |
| POST | `/api/v1/kb/{id}/rebuild` | 换 Embedding 模型后重建向量 |
| POST | `/api/v1/kb/{id}/documents` | 摄入：multipart 文件 / JSON `{title,text}` / `{title,base64}` |
| GET | `/api/v1/kb/{id}/documents` | 文档列表与状态轮询 |
| DELETE | `/api/v1/kb/{id}/documents/{docId}` | 删文档 |
| POST | `/api/v1/search` | `{query, kbIds?, topK?, minScore?}` → hits（带出处与分数） |
| GET/PUT | `/api/v1/settings/model` | 运行时模型配置（脱敏回显；保存热生效，PUT 缺省组=清除回落 yml） |
| POST | `/api/v1/settings/model/test` | 分路连通性测试（target=embedding/chat，不落盘） |
| POST | `/api/v1/ask` | 独立 RAG 问答：`{query, kbIds?, topK?}` → 带引用答案（Chat 未配置 409） |

MCP 工具：`rag_search` / `rag_list_kbs` / `rag_ask`（stdio 桥与 HTTP 端点同契约）。

## 配置（application.yml / 环境变量）

| 配置 | 环境变量 | 默认 | 说明 |
|------|----------|------|------|
| `arkrag.server.token` | `ARKRAG_TOKEN` | —（必填，<16 位拒绝启动） | API token |
| `arkrag.server.port` | — | 8964 | 监听端口（默认绑定 127.0.0.1） |
| `arkrag.data-dir` | `ARKRAG_DATA_DIR` | `~/.arkrag/data` | 索引与文档存储目录 |
| `arkrag.embedding.base-url` | `ARKRAG_EMBEDDING_BASE_URL` | — | OpenAI 兼容端点 |
| `arkrag.embedding.model` | `ARKRAG_EMBEDDING_MODEL` | — | 模型名 |
| `arkrag.embedding.dims` | `ARKRAG_EMBEDDING_DIMS` | 首个向量回填 | 向量维度 |
| `arkrag.chat.base-url/api-key/model` | `ARKRAG_CHAT_*` | —（控制台可在线配置） | Chat LLM 启动初值（rag_ask 用） |
| `arkrag.chunk.size/overlap` | — | 1000/150 | 切块字符数 |

> 运行时在控制台保存的模型配置存于 `{dataDir}/settings.json`，优先于上表初值。

## 离线冒烟（无外部 API）

```bash
node scripts/mock-embedding-server.mjs 8965 1024 &   # 伪语义 mock
ARKRAG_TOKEN=test-token-1234567890 ARKRAG_EMBEDDING_BASE_URL=http://127.0.0.1:8965/v1 \
ARKRAG_EMBEDDING_API_KEY=mock ARKRAG_EMBEDDING_MODEL=mock-emb java -jar dist/arkrag-server.jar
```

## 文档

| 文档 | 内容 |
|------|------|
| **[docs/USAGE.md](docs/USAGE.md)** | **详细使用文档**：安装/启动/控制台/模型配置/插件与 MCP 接入/API 参考/FAQ/安全 |
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | **架构文档**：模块划分/时序/并发模型/配置体系/协议层/ADR 决策记录/扩展指南 |
| [docs/PROJECT-OVERVIEW.md](docs/PROJECT-OVERVIEW.md) | 项目现状速览（技术栈/进展/决策/遗留） |
| [docs/PROJECT-MAP.md](docs/PROJECT-MAP.md) | 项目地图（东西在哪 + 常见任务定位表） |
| [docs/v1.0/](docs/v1.0/) · [docs/v1.1/](docs/v1.1/) | 版本化过程文档（PRD/设计/原型/用例库/测试报告/CHANGELOG） |
