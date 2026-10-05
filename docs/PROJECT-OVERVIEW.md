# 项目总览（PROJECT-OVERVIEW）

> 更新：2026-10-04 ｜ 当前版本：v1.1.0（已交付）

## 定位

ArkRAG：本地优先的 Java RAG **独立服务**——自带 Web 管理控制台（`/admin`）、可在线配置 Embedding 与 Chat 大模型（热生效）、可直接作为独立 RAG 生成带引用答案。对外四通道：HTTP REST、MCP（Streamable/SSE/stdio 桥）、ArkWork 官方插件、管理控制台。

## 技术栈

Java 21（JDK 22 构建（target 17，兼容 JDK 17+））· Spring Boot 3.5.16 · LangChain4j 1.21（Tika 解析、OpenAI 兼容 Embedding/Chat）· MCP 官方 Java SDK 2.0.1 · Maven 多模块（自带 Wrapper）· 控制台/插件零依赖原生 JS。

## 进展

- [x] v1.0（冻结于 docs/v1.0/）：检索服务 + MCP 双传输 + ArkWork 插件（2 工具+面板）
- [x] v1.1（docs/v1.1/）：管理控制台 /admin + 模型热配置（settings.json）+ rag_ask 独立问答（HTTP/MCP/插件三面）
- [ ] 用户实机走查：ArkWork 内安装插件 → Agent 调用（沿 v1.0 遗留）

## 核心流程

摄入：上传 → 状态机 PENDING→PARSING→EMBEDDING→READY/FAILED（同 KB 串行、提交登记纳入 KB 锁）→ 索引快照防抖落盘。
检索：鉴权 → 查询向量化 → 维度护栏 → volatile 快照无锁余弦检索 → 合并重排。
问答（v1.1）：检索 topK → 中文提示词（仅依据资料+[n] 标注+拒答规则）→ Chat 生成 → citations（服务端生成，n 严格对齐）。
模型热配置：控制台保存 → settings.json 原子落盘 → 变更广播 → Embedding/Chat 模型实例按指纹重建（下次调用生效）。

## 关键决策（含被否方案）

1. 向量索引自研（LangChain4j InMemoryEmbeddingStore Entry 不可枚举）。
2. MCP = HTTP 内嵌 + 独立 stdio 桥（ArkWork 客户端仅支持 stdio；桥为纯代理避免双进程写索引）。
3. **v1.1 控制台零依赖单页**（无前端框架/构建链，fat-jar 内嵌 classpath:/admin/；token 存 localStorage，静态资源免鉴权、数据 API 全鉴权）。
4. **模型配置 runtime(settings.json) > yml**；PUT 缺省组=清除回落（契约语义）；apiKey 脱敏回显；换 Embedding 模型靠维度护栏 + rebuild 迁移（复用 v1.0 机制）。
5. **rag_ask 的 citations 由服务端生成**（非模型输出），保证 n 与引用严格对齐。
6. 不做（累计）：对话历史、多用户、apiKey 加密、控制台移动端、OCR、BM25、分布式。

## 技术债 / 遗留

- ArkWork 实机内联走查待用户确认（桥契约静态核对 + 模拟器实测均通过）。
- 控制台 <1024px 导航折叠 P1；摄入失败行重试按钮 P1（插件面板）。
- mock chat 为 canned 回答；真实 LLM 答案质量不在测试范围。

## 如何跑起来

```bash
# 独立使用（浏览器全流程：配模型 → 建库 → 上传 → 问答）
ARKRAG_TOKEN=<≥16位> java -jar dist/arkrag-server.jar
open http://127.0.0.1:8964/admin   # 控制台里在线配置 Embedding/Chat

# ArkWork：设置→插件→安装 dist/arkrag-plugin-1.1.2.zip；或 MCP stdio 指 scripts/arkrag-mcp-stdio.sh
```

详见 README.md；离线冒烟（无外部 API）：`node scripts/mock-embedding-server.mjs 8965 1024`。
