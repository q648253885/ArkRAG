# 升级目标：ArkRAG v1.1

> 版本：v1.1.0 ｜ 日期：2026-10-04 ｜ 状态：已确认（用户明确提出："ArkRAG 作为独立服务应有自己的后台管理；可独立配置大模型，作为独立 RAG 使用"）
> 与 v1.0 的关系：**纯增量**（新增控制台/模型配置/rag_ask，不重构 v1.0 模块）；文档采用增量式——接口契约正本仍为 `docs/v1.0/04-system-design.md`，本目录存放增量修订。

## 本版要解决什么

1. v1.0 的管理动作只能靠 curl 或 ArkWork 插件，作为**独立服务**缺一个自带的后台管理入口；
2. v1.0 的 Embedding 配置只能改 yml 重启，且**没有 Chat LLM**——无法作为独立 RAG 直接生成带引用的答案。

## 验收标准（可勾选）

- [ ] 浏览器打开 `http://127.0.0.1:8964/admin` 出现控制台，token 登录后可完成：建库/删库、上传文档、看摄入状态、检索试用、问答试用
- [ ] 控制台"模型配置"页可在线配置 Embedding 与 Chat LLM（base-url/api-key/model/dims），测试连通后保存**热生效**（无需重启）
- [ ] `POST /api/v1/ask` 返回带引用编号的答案；引用可对应到具体文档块；未配 Chat 模型返回 409 CHAT_NOT_CONFIGURED
- [ ] MCP 双传输与 ArkWork 插件同步出现 `rag_ask` 工具且可用
- [ ] v1.0 全部 36 条用例回归通过 + 本版新增用例通过

## 明确不做（本版）

- 控制台不做登录体系/多用户（沿用 token，浏览器 localStorage 保存）
- 不做对话历史持久化（问答即问即答）
- 不做配置的组织/多租户，apiKey 明文存 data-dir（与 ArkWork models.json 同级风险，local-first 可接受，文档注明）
- 不改造 ArkWork 插件面板 UI（仅追加 rag_ask 工具注册）

## 继承关系

testcases/ 自 v1.0 整目录复制（继承 36 条），本版新增 TC-SET-*、TC-ASK-*、TC-ADMIN-* 系列。
