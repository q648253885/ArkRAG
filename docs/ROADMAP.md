# ROADMAP：升级目标与版本规划

## v1.0（当前）——可用性闭环
P0：知识库管理、文档摄入、语义检索、REST + MCP 双接口（HTTP/stdio）、Bearer 鉴权、ArkWork 插件（工具 + 面板）。
P1（随 v1.0 或 v1.1）：rerank 两阶段检索、rag_ask 答案生成、Qdrant/Milvus 适配、插件面板完善。

## v1.1+（候选，不排期）
- chunk 模板（按文档类型：表格/论文/手册）
- BM25 混合检索（依托外部向量库或内置 Lucene）
- jpackage 自带 JRE 分发（消除 Java 21 依赖门槛）
- ArkWork 内置 KB 一键迁移（PRD F16）
- OCR / 版面感知解析（RAGFlow 式深度文档理解）

## 明确不做（永久或远期）
多租户 / 用户体系；分布式索引；Web 管理控制台。
