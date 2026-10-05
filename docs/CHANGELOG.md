# 变更日志（CHANGELOG）

> 从新到旧，一条一行。此前无文档记录（新项目，2026-10-04 建）。

## 2026-10-05（v1.1.2）

- **修复插件安装被 ArkWork VP2 校验拦截**：kind=`tool` 要求 `provides.tool`（单数对象，校验闸）与 `provides.tools`（非空数组，运行时消费）**双声明**——此前只写了数组。插件版本 1.1.2，产物 arkrag-plugin-1.1.2.zip。
- 同步 ARCHITECTURE.md §9 双声明契约说明；全模块版本升 1.1.2。

## 2026-10-04（v1.1.1）

- **JDK 17 支持**（用户要求）：编译目标 21→17；代码替换 Java 21 API（List.getFirst 等 4 处）；依赖基线核验（MCP SDK 2.0.1 / LangChain4j 1.21 / Boot 3.5.16 字节码均为 61=Java 17，无需降级）；真机 JDK 17.0.16 全链路实测（health/admin/摄入 READY/检索命中）通过。产物全量重打（jar/plugin zip 1.1.1）。
- 新增两份正式交付文档：docs/USAGE.md（详细使用文档）、docs/ARCHITECTURE.md（架构文档，含 ADR 决策记录与扩展指南）。

## 2026-10-04（v1.1.0 交付）

- 用户新需求落地：管理控制台 /admin（知识库/文档/检索试用/问答试用/模型配置五区块）+ 运行时模型热配置（settings.json，Embedding/Chat）+ 独立 RAG 问答 rag_ask（HTTP/MCP 桥/插件三面同步，版本全模块升 1.1.0）。
- 测试：v1.0 回归 21/21；v1.1 新增 17 条全过（含 512 维维度护栏 409 实证、控制台真浏览器 e2e、深浅色截图）。用例库 53 条。
- 过程修复：并发上传互踩 tmp 文件（UUID 临时名 + KB 锁）；控制台 token 门改受保护接口验证；pom 版本 1.1.0。
- 新增两份正式交付文档：docs/USAGE.md（详细使用文档：安装/控制台/模型/插件/MCP/API/FAQ/安全）、docs/ARCHITECTURE.md（架构文档：模块/时序/并发模型/配置体系/MCP 协议层/ADR 决策记录/扩展指南）。

## 2026-10-04（v1.0.0 交付）

- 阶段四~八完成：编码（core/server/stdio 桥/ArkWork 插件/脚本）、测试（用例库 36 条，冒烟 10/10 + 自动化 21/21 + MCP 9/9 全绿）、UI 验收 A/B（23 项点击清单 + 深浅色截图，阻塞清零）、UX 校验、dist 打包。
- 缺陷修复回测：摄入状态不落盘（管线持有工作列表）；向量索引改自研（04 文档同步修订）。
- 遗留：ArkWork 实机内联走查（TC-PLUGIN-005）待用户确认。

## 2026-10-04（立项）

- v1.0 立项：完成调研（01）、PRD（02）、交互文档（03）、HTML 原型（prototype/）、系统设计（04）与全局三件套。
- 关键决策：Spring Boot 3.5 + LangChain4j + MCP 官方 Java SDK；嵌入式向量存储；MCP 双传输（HTTP + stdio 桥）；插件面板升 P0。
- 缺陷回溯 1：向量索引由 InMemoryEmbeddingStore 改为自研内存暴力索引（Entry 包私有不可枚举 + 序列化对自定义负载不可靠），04 文档已修订。
- 缺陷回溯 2：MCP SDK 升 2.0.1（mcp-core 拆分 + Jackson3）；Spring Boot 定 3.5.16；放弃 springdoc（Boot 版本兼容风险），API 契约以 04 文档为准。
