# ArkRAG 使用文档

> 版本：v1.1.0 ｜ 更新：2026-10-04 ｜ 适用产物：`dist/arkrag-server.jar`、`dist/arkrag-mcp-stdio.jar`、`dist/arkrag-plugin-1.1.2.zip`
> 架构与实现原理见 [ARCHITECTURE.md](ARCHITECTURE.md)；接口契约正本见 [v1.0/04-system-design.md](v1.0/04-system-design.md) 与 [v1.1/04-system-design-delta.md](v1.1/04-system-design-delta.md)。

## 目录

1. [系统要求与安装](#1-系统要求与安装)
2. [启动与停止](#2-启动与停止)
3. [控制台使用（推荐入口）](#3-控制台使用)
4. [模型配置详解](#4-模型配置详解)
5. [知识库与文档管理](#5-知识库与文档管理)
6. [检索与问答](#6-检索与问答)
7. [接入 ArkWork（插件）](#7-接入-arkwork插件)
8. [接入其他 MCP 客户端](#8-接入其他-mcp-客户端)
9. [HTTP API 参考](#9-http-api-参考)
10. [配置项总表](#10-配置项总表)
11. [数据目录与备份迁移](#11-数据目录与备份迁移)
12. [常见问题排查（FAQ）](#12-常见问题排查)
13. [安全注意事项](#13-安全注意事项)

---

## 1. 系统要求与安装

| 项 | 要求 |
|----|------|
| 运行环境 | JDK 17 及以上（17/21/22/23/24/25 均可，已实测 17 与 22）；`java -version` 自查 |
| 操作系统 | macOS / Linux / Windows（脚本提供 sh + bat） |
| 外部模型 | 一个 **OpenAI 兼容** Embedding 端点（必需）；一个 OpenAI 兼容 Chat 端点（使用独立问答时需要）。DeepSeek、通义千问、Ollama、vLLM、硅基流动等均适用 |
| 磁盘 | 数据目录预留：约等于原始文档体积 × 1.5（向量 JSON）+ 原始文件 |

**安装**（二选一）：

```bash
# A. 直接使用现成产物（仓库已带）
ls dist/
# arkrag-server.jar / arkrag-mcp-stdio.jar / arkrag-plugin-1.1.2.zip

# B. 从源码构建
./mvnw -q -DskipTests package        # 首次构建会自举 Maven（仓库自带 tools/maven）
bash scripts/build-dist.sh           # 组装 dist/（jar×2 + 插件 zip）
```

无外部 API 也能跑：`node scripts/mock-embedding-server.mjs 8965 1024` 提供"伪语义"离线模型，用于体验与测试（见 §12）。

## 2. 启动与停止

**最小启动**（模型稍后在控制台里配）：

```bash
ARKRAG_TOKEN=my-secret-token-2026 java -jar dist/arkrag-server.jar
```

> ⚠️ `ARKRAG_TOKEN` 必须 ≥16 位，否则**拒绝启动**（安全红线）。控制台与所有 API 都用它。

**启动时即带模型配置**（可选，之后仍可在控制台在线修改）：

```bash
ARKRAG_TOKEN=my-secret-token-2026 \
ARKRAG_DATA_DIR=/data/arkrag \
ARKRAG_EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1 \
ARKRAG_EMBEDDING_API_KEY=ollama \
ARKRAG_EMBEDDING_MODEL=bge-m3 \
ARKRAG_EMBEDDING_DIMS=1024 \
ARKRAG_CHAT_BASE_URL=https://api.deepseek.com/v1 \
ARKRAG_CHAT_API_KEY=sk-xxx \
ARKRAG_CHAT_MODEL=deepseek-chat \
java -jar dist/arkrag-server.jar
```

- 默认监听 `http://127.0.0.1:8964`（仅本机回环）。改端口：`--server.port=9000` 或 application.yml。
- 停止：`Ctrl+C`（优雅停机，自动把内存索引落盘；在途摄入任务最多等 10 秒）。
- 也可以用脚本：`scripts/start-server.sh`（自动检查 java 版本、定位 dist jar）。

## 3. 控制台使用

浏览器打开 **`http://127.0.0.1:8964/admin`**。

**首次进入**：页面弹出"访问令牌"输入条 → 粘贴 `ARKRAG_TOKEN` → 验证并保存（存浏览器 localStorage，之后免输）。token 错误时显示红字"token 无效或未设置"；换 token 点右上角 `Token` 按钮。

五个区块（左侧导航）：

| 区块 | 能做什么 |
|------|----------|
| 📚 知识库 | 知识库表格（文档数/块数/向量模型/维度）；新建；删除（确认弹窗，级联清理）；点击行查看该库文档 |
| ⬆ 文档卡 | 上传文件（多选，PDF/DOCX/PPTX/HTML/TXT/MD，≤50MB）；摄入状态轮询（排队→解析→向量化→完成/失败+原因）；删除文档；重建索引（换 Embedding 模型后用） |
| 🔍 检索试用 | 输入问题 + 选知识库 → 返回最相关文本块（分数徽标 + 来源"文档·第 N 块·库名"） |
| 💬 问答试用 | 输入问题 → Chat 生成的答案，`[n]` 高亮，下方"引用来源"列表（可对应到具体块）；需先配置 Chat |
| 🧠 模型配置 | 在线配置 Embedding 与 Chat（详见 §4）；测试连通；保存热生效 |

右上角：`🌗 主题`（深/浅色切换，默认跟随系统）、`Token`（重新输入令牌）。

**三分钟上手路径**：模型配置页填 Embedding → 测试连通 → 保存 → 知识库新建"测试库" → 上传文档等状态"完成" → 问答试用提问。

## 4. 模型配置详解

### 两组模型

| 组 | 用途 | 字段 |
|----|------|------|
| Embedding | 文档切块后的向量化 + 查询向量化（检索必需） | Base URL（以 `/v1` 结尾的 OpenAI 兼容根）、API Key、Model、维度（可选） |
| Chat | 独立问答 `rag_ask` 的答案生成（检索不需要） | Base URL、API Key、Model、Temperature |

- **Ollama 示例**：Base URL `http://127.0.0.1:11434/v1`，Key 任意（如 `ollama`），Embedding 模型 `bge-m3`（维度 1024），Chat 模型 `qwen2.5`。
- **DeepSeek 示例**：Chat Base URL `https://api.deepseek.com/v1`，模型 `deepseek-chat`。

### 热生效规则（重要）

1. 控制台"保存配置"写入 `{数据目录}/settings.json`，**立即生效，无需重启**；
2. **更换 Embedding 模型/维度后**，旧知识库的向量与新查询不可混用——检索/问答会返回 409 `EMBEDDING_DIMENSION_MISMATCH`，并对相关库执行"重建索引"（文档卡上的 ↻ 按钮，用已上传的原始文件重新切块嵌入）；
3. Chat 模型切换无迁移成本，立即生效；
4. API Key 保存后界面只显示 `****尾4位`；原样回显不会误清空；清空某组并保存 = 回落 application.yml 初始值。

### 维度建议

不确定模型维度时**留空**：首次摄入成功后会用真实向量自动回填知识库的 dims 并显示在知识库表格里。显式填错维度会导致服务端请求失败（OpenAI 兼容端点按 dimensions 参数截断时除外）。

## 5. 知识库与文档管理

- **知识库** = 独立的文档集合与向量索引（名称安装内唯一，≤64 字符）。
- **支持的文件类型**：PDF / DOCX / PPTX / HTML / TXT / MD（Tika 抽取文本层；**扫描件/纯图片 PDF 无文本层，会失败**并在状态里注明）。
- **同名覆盖**：同一知识库内上传同名文件 = 覆盖旧版本（旧文本块全部移除，重新入库）。
- **摄入是异步的**：上传立即返回，状态轮询（控制台自动 2 秒刷新；API 用 `GET /api/v1/kb/{id}/documents`）。状态：`PENDING → PARSING → EMBEDDING → READY`，任一步失败 `FAILED`（带原因，如"解析得到的文本为空（扫描件？）"）。
- **删除**：删文档移除其全部文本块；删知识库级联清理文档、向量、原始文件（均需确认）。
- **切块规则**：默认按字符递归切块（chunk=1000、overlap=150，段落边界优先），建库时可自定义（200..4000）。

## 6. 检索与问答

| 能力 | 入口 | 说明 |
|------|------|------|
| 语义检索 | 控制台"检索试用" / `POST /api/v1/search` / MCP `rag_search` | 返回 topK 个最相关文本块（余弦相似度，降序），每条带出处 |
| 带引用问答 | 控制台"问答试用" / `POST /api/v1/ask` / MCP `rag_ask` | 先检索 topK（默认 5），再由 Chat 基于这些资料生成答案；答案中的 `[n]` 与返回的 `citations[].index` 一一对应 |
| 列知识库 | `GET /api/v1/kb` / MCP `rag_list_kbs` | 供模型/用户先确认目标库 id |

**检索参数**：`query`（1..2000 字符，必填）、`kbIds`（缺省全部库）、`topK`（1..50，缺省 5）、`minScore`（0..1，检索接口可选）。

**问答的拒答规则**：提示词强约束"仅依据资料回答 + [n] 标注"，资料不足时固定回答"知识库中没有找到相关内容。"——宁可拒答不编造。

## 7. 接入 ArkWork（插件）

1. ArkWork → 设置 → 插件 → 从 zip 安装 → 选择 `dist/arkrag-plugin-1.1.2.zip`；
2. 确认 ArkRAG 服务已启动且**服务地址可达**（ArkWork 与服务同机时默认 `http://127.0.0.1:8964` 即可）；
3. 侧边栏出现 **Book 图标的 RAG 面板** → "连接设置"填服务地址与 API Token → 测试连接（应显示"✓ 连接成功"）→ 保存；
4. 面板内可建库、上传、看摄入状态、检索试用——与控制台同源；
5. Agent 对话时自动可用三个工具：`rag_search`（检索片段）、`rag_list_kbs`（列库）、`rag_ask`（带引用问答）。

Token 存放在插件私有 storage（ArkWork 数据目录内），只随请求发往 ArkRAG 服务。

## 8. 接入其他 MCP 客户端

**Streamable HTTP（推荐，远程/跨机可用）**：

```json
{ "mcpServers": { "arkrag": {
    "url": "http://127.0.0.1:8964/mcp",
    "headers": { "X-Api-Key": "my-secret-token-2026" }
} } }
```

**SSE 兼容**（旧客户端）：端点 `http://127.0.0.1:8964/mcp/sse`。

**stdio（同机直连，如 ArkWork 的 mcp-servers.json）**：

```json
{ "id": "arkrag", "transport": "stdio",
  "command": "<项目路径>/scripts/arkrag-mcp-stdio.sh",
  "env": { "ARKRAG_TOKEN": "secret:arkragToken" } }
```

- 桥通过环境变量找服务：`ARKRAG_URL`（默认 `http://127.0.0.1:8964`）、`ARKRAG_TOKEN` 或 `ARKRAG_TOKEN_FILE`（配合 ArkWork `secret:` 秘密引用惯例）；
- 桥的 `initialize`/`tools/list` **不依赖服务在线**（静态清单）；只有实际调用工具时才转发到服务，服务不可达时返回 isError + "请先启动 arkrag-server"；
- Windows 用 `scripts/arkrag-mcp-stdio.bat`。

## 9. HTTP API 参考

通用约定：除 `/api/v1/health` 与 `/admin` 静态页外，全部要求 `Authorization: Bearer <token>` 或 `X-Api-Key: <token>`；错误统一 `{"error":{"code":"...","message":"人话","details":...}}`。

### 9.1 探活

```
GET /api/v1/health
→ 200 {"status":"UP","version":"1.1.0","embedding":{"configured":true,"model":"bge-m3","dims":1024},"store":{"type":"inmemory"}}
```

### 9.2 知识库

```
POST /api/v1/kb            {"name":"我的库","description":"可选","chunkSize":1000,"chunkOverlap":150}
→ 200 KB 对象（id/name/.../embeddingModel/dims/documentCount/chunkCount）
GET  /api/v1/kb            → 200 [KB]（含统计）
GET  /api/v1/kb/{id}       → 200 {"kb":{...},"documents":[Doc]}   404 KB_NOT_FOUND
DELETE /api/v1/kb/{id}     → 204（级联删除文档/向量/原始文件）
POST /api/v1/kb/{id}/rebuild → 202 {"queued":N}（换 Embedding 模型后重建）
```

### 9.3 文档摄入

```
POST /api/v1/kb/{id}/documents          → 202 [Doc]（异步；轮询状态）
  · multipart/form-data：files=@a.pdf（可多文件）
  · application/json：{"title":"笔记.md","text":"纯文本内容"}
  · application/json：{"title":"报告.pdf","base64":"<base64>"}（二进制通道）
GET    /api/v1/kb/{id}/documents        → 200 [Doc]（status/chunkCount/error）
DELETE /api/v1/kb/{id}/documents/{docId} → 204
```

### 9.4 检索 / 问答

```
POST /api/v1/search  {"query":"...","kbIds":["id"],"topK":5,"minScore":0.0}
→ 200 {"hits":[{"kbId","kbName","docId","docName","chunkIndex","text","score"}],"tookMs":212}

POST /api/v1/ask     {"query":"...","kbIds":null,"topK":5}
→ 200 {"answer":"…[1]…","citations":[{"index":1,"kbId","kbName","docId","docName","chunkIndex","score"}],"tookMs":1520}
→ 409 CHAT_NOT_CONFIGURED（未配 Chat 模型）
```

### 9.5 模型配置

```
GET  /api/v1/settings/model
→ 200 {"embedding":{"configured":true,"baseUrl":"…","apiKeyMasked":"****ab12","model":"bge-m3","dims":1024,"source":"yaml|runtime|unset"},
       "chat":{...同构...}}
PUT  /api/v1/settings/model
   {"embedding":{"baseUrl":"…","apiKey":"…","model":"…","dims":1024},
    "chat":{"baseUrl":"…","apiKey":"…","model":"…","temperature":0.3}}
→ 200 脱敏视图（任一组传 null=清除并回落 yml；apiKey 传空/掩码=保留原值）
POST /api/v1/settings/model/test
   {"target":"embedding","baseUrl":"…","apiKey":"…","model":"…","dims":1024}
→ 200 {"ok":true,"message":"连接成功","dims":1024}   失败 → {"ok":false,"message":"…"}（不落盘）
```

### 9.6 错误码总表

| code | HTTP | 含义 / 处置 |
|------|------|-------------|
| UNAUTHORIZED | 401 | token 缺失/错误 → 检查 `ARKRAG_TOKEN` 与请求头 |
| VALIDATION | 400 | 参数不合法（空 query、topK 越界、重名等） |
| KB_NOT_FOUND / DOC_NOT_FOUND | 404 | 资源不存在 |
| FILE_TOO_LARGE | 413 | 单文件 >50MB |
| UNSUPPORTED_FILE_TYPE | 415 | 扩展名不在白名单 |
| EMBEDDING_NOT_CONFIGURED | 409 | 未配 Embedding → 控制台模型页配置 |
| EMBEDDING_DIMENSION_MISMATCH | 409 | 换过模型 → 对相关库执行 rebuild |
| CHAT_NOT_CONFIGURED | 409 | 未配 Chat → 控制台模型页配置 |
| INGEST_FAILED / SERVER_ERROR | 500 | 看服务日志；摄入失败原因也会写在文档状态里 |

### 9.7 curl 速查

```bash
T="X-Api-Key: my-secret-token-2026"; B=http://127.0.0.1:8964/api/v1
curl -s $B/health | jq
curl -s -X POST $B/kb -H "$T" -H 'Content-Type: application/json' -d '{"name":"我的库"}' | jq
curl -s -X POST $B/kb/<id>/documents -H "$T" -F files=@报告.pdf
curl -s -X POST $B/search -H "$T" -H 'Content-Type: application/json' \
  -d '{"query":"报销流程是什么","topK":5}' | jq
curl -s -X POST $B/ask -H "$T" -H 'Content-Type: application/json' -d '{"query":"报销流程是什么"}' | jq
```

## 10. 配置项总表

| application.yml | 环境变量 | 默认 | 说明 |
|------|----------|------|------|
| `arkrag.server.token` | `ARKRAG_TOKEN` | —（**必填**，<16 位拒绝启动） | API token |
| `arkrag.server.port` | — | 8964 | 监听端口（默认仅绑定 127.0.0.1） |
| `arkrag.data-dir` | `ARKRAG_DATA_DIR` | `~/.arkrag/data` | 数据目录 |
| `arkrag.embedding.base-url` | `ARKRAG_EMBEDDING_BASE_URL` | — | OpenAI 兼容端点根 |
| `arkrag.embedding.api-key` | `ARKRAG_EMBEDDING_API_KEY` | — | |
| `arkrag.embedding.model` | `ARKRAG_EMBEDDING_MODEL` | — | 模型名 |
| `arkrag.embedding.dims` | `ARKRAG_EMBEDDING_DIMS` | 首个向量回填 | 显式维度 |
| `arkrag.chat.base-url / api-key / model` | `ARKRAG_CHAT_*` | — | Chat 初值（rag_ask） |
| `arkrag.chunk.size / overlap` | — | 1000 / 150 | 切块字符数 |
| `arkrag.ingest.max-file-size-mb / workers` | — | 50 / 2 | 上限与并发 |
| `server.port` | — | 8964 | Spring 端口 |

运行时在控制台保存的配置存于 `{dataDir}/settings.json`，**优先于上表**。

## 11. 数据目录与备份迁移

```
{dataDir}/                          # 默认 ~/.arkrag/data
├── kbs.json                        # 全部知识库元数据
├── settings.json                   # 运行时模型配置（含明文 apiKey，注意权限）
└── kb/{kbId}/
    ├── docs.json                   # 文档元数据与摄入状态
    ├── vectors.json                # 向量索引（自管 JSON，原子写入）
    └── files/{docId}_{文件名}       # 原始上传文件（重建索引用）
```

- **备份**：停服后整目录拷贝即可（或运行中拷贝， vectors.json 为原子替换、最坏丢最近一次防抖窗口 ≤500ms 的增量）。
- **迁移**：新机器装 JDK 17+ → 拷贝数据目录 → 用同版本 jar 启动。Embedding 模型配置随 settings.json 一起迁移。
- **彻底重置**：停服 → 删除数据目录 → 重启（知识库需重建）。

## 12. 常见问题排查

| 现象 | 原因 | 处置 |
|------|------|------|
| 服务起不来，提示 token | 未设置或 <16 位 | 设 `ARKRAG_TOKEN`（≥16 位） |
| 控制台一直"token 无效" | 浏览器存的 token 与服务端不一致 | 点右上角 Token 重新输入 |
| 摄入失败"文本为空（扫描件？）" | PDF 无文本层 | 换可复制文本的版本，或先 OCR |
| 摄入失败"元数据文件写入失败" | 数据目录无写权限/磁盘满 | 修权限/清磁盘后重传 |
| 检索 409 DIMENSION_MISMATCH | 换过 Embedding 模型 | 对相关知识库点"重建索引" |
| ask 409 CHAT_NOT_CONFIGURED | 未配 Chat | 模型配置页配置后保存 |
| 检索结果不相关 | 切块不合理/模型不适配语言 | 调 chunk 参数重建；换多语种 embedding（如 bge-m3） |
| MCP 客户端连不上 stdio 桥 | java 不在 PATH / 路径错 | 命令行直接跑 `scripts/arkrag-mcp-stdio.sh` 看报错 |
| 桥工具调用报"无法连接 ArkRAG 服务" | 服务未启动 / URL 或 token 错 | 启动服务；检查 `ARKRAG_URL`、`ARKRAG_TOKEN` |
| Ollama 连接失败 | 端点未带 `/v1` | Base URL 必须是 `http://127.0.0.1:11434/v1` |

## 13. 安全注意事项

1. **默认仅监听 127.0.0.1**；如需局域网访问改 `server.address: 0.0.0.0`，并务必使用强 token + 反向代理 TLS；
2. token 是唯一凭据：控制台、API、MCP、桥共用；泄露即全权——定期轮换（改配置重启即可）；
3. `{dataDir}/settings.json` 含**明文 apiKey**（local-first 口径，与 ArkWork models.json 同级）；请控制该目录的文件权限（`chmod 700`），不要把数据目录提交进仓库；
4. 上传有白名单（6 种扩展名）与 50MB 上限，文件名净化防目录穿越；API Key 不落日志；
5. MCP over HTTP 与 REST 同鉴权；客户端不支持自定义请求头时可用 `X-Api-Key`（部分客户端）或改走 stdio 桥（token 走进程环境变量，不经网络）。

---

*更多背景：[ARCHITECTURE.md](ARCHITECTURE.md)（架构与原理）· [PROJECT-OVERVIEW.md](PROJECT-OVERVIEW.md)（项目现状）· [测试报告 v1.1](v1.1/05-function-test-report.md)*
