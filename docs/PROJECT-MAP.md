# 项目地图（PROJECT-MAP）

> 更新：2026-10-04（v1.1 交付）｜ 维护规则：文件/模块增删改名时必须同步本表；路径不存在即为缺陷。

## 目录树（二级 + 一句话职责）

```
ArkRAG/
├── pom.xml                  # Maven 父 POM（多模块，v1.1.0）
├── mvnw / .mvn/             # Maven Wrapper
├── README.md                # 快速开始 + 接入方式（控制台/插件/MCP/HTTP）+ API/配置速查
├── tools/maven/             # 自举用 Maven 发行版（不进发布产物）
├── arkrag-core/             # 检索引擎：模型/摄入/检索/自研向量索引 + settings/chat/ask（v1.1）
├── arkrag-server/           # Spring Boot：REST + MCP HTTP 端点 + 鉴权 + /admin 控制台
├── arkrag-mcp-stdio/        # stdio 桥：JSON-RPC ↔ HTTP 代理（含 rag_ask）
├── plugin/arkrag/           # ArkWork 插件（3 工具 + dock 面板）
├── scripts/                 # start-server / arkrag-mcp-stdio（sh+bat）/ build-dist /
│                            # run-tests / mock-embedding-server（含 mock chat）
├── test-ui/host-mock.html   # 插件面板 UI 验收宿主模拟器
├── dist/                    # 交付产物：arkrag-server.jar / arkrag-mcp-stdio.jar / arkrag-plugin-1.1.2.zip
└── docs/
    ├── PROJECT-MAP.md / PROJECT-OVERVIEW.md / CHANGELOG.md / ROADMAP.md
    ├── USAGE.md             # ★ 详细使用文档（安装/控制台/插件/MCP/API/FAQ/安全）
    ├── ARCHITECTURE.md      # ★ 架构文档（模块/时序/并发/配置/协议/ADR/扩展指南）
    ├── v1.0/                # v1.0 冻结版（调研/PRD/交互/设计/原型/36 用例/报告）
    └── v1.1/                # v1.1 增量版（00-release-goal / 02-prd-delta / 03-interaction-delta /
                             #   04-system-design-delta / 05 报告 / 06 UI 验收 / CHANGELOG）
        └── testcases/       # 53 条（继承 36 + 新增 17：tc-settings/tc-ask/tc-admin）
```

## 模块地图（v1.1 新增部分加粗）

| 模块 | 入口/关键文件 | 职责 |
|------|----------------|------|
| arkrag-core | `core/pipeline/IngestService.java`、`core/search/SearchService.java`、`core/store/{IndexRegistry,VectorIndex}.java` | 摄入状态机、无锁检索、自研向量索引与持久化 |
| arkrag-core | **`core/settings/{SettingsService,ModelSettings}.java`** | 运行时模型配置（settings.json、热生效） |
| arkrag-core | **`core/chat/ChatService.java`、`core/ask/AskService.java`** | Chat 封装（指纹缓存热生效）、检索+引用答案（提示词在 AskService） |
| arkrag-server | `server/api/*Controller.java`、`server/mcp/{McpHttpConfig,McpTools}.java`、`server/security/AuthFilter.java` | REST `/api/v1`（**含 settings/model、ask**）、MCP 双端点（**3 工具**）、鉴权 |
| arkrag-server | **`resources/admin/index.html` + `server/api/AdminWebConfig.java`** | /admin 管理控制台（零依赖单页，深浅色） |
| arkrag-mcp-stdio | `stdio/BridgeMain.java` + `stdio/ArkRagClient.java` | stdio 桥（3 工具；ask 120s 超时） |
| ArkWork 插件 | `plugin/arkrag/plugin.json`、`main.js`、`index.html` | 3 工具注册 + dock 面板 |

## 常见任务定位表

| 任务 | 看哪里 |
|------|--------|
| 改 HTTP 接口 | `docs/v1.0/04-system-design.md` §5 + `docs/v1.1/04-system-design-delta.md`（契约正本）→ `server/api/` |
| 改检索/切块/问答 | `core/pipeline`、`core/search`、`core/ask/AskService`（提示词）、`core/store/VectorIndex` |
| 改 MCP 工具面 | 契约 §5.2 + v1.1 delta → `server/mcp/McpTools` + `stdio/BridgeMain` + `plugin/arkrag/main.js`（三处同步！） |
| 改控制台 UI | `arkrag-server/resources/admin/index.html`（token 契约同插件面板） |
| 改模型热配置 | `core/settings/SettingsService.java`（合成顺序/掩码/合并语义集中在此） |
| 改插件面板 UI | `docs/v1.0/prototype/page-01-panel.html`（基准）→ `plugin/arkrag/index.html` |
| 跑测试 | 起 mock+server（README 离线冒烟）→ `bash scripts/run-tests.sh` |
| 面板 UI 回归 | 开 `test-ui/host-mock.html`（Python http.server 8899）逐 Tab 点击 |
| 加测试用例 | `docs/v1.1/testcases/`（累积，先读 00-cumulative-matrix.md） |
| 重新打包交付 | `./mvnw -DskipTests package && bash scripts/build-dist.sh` |
