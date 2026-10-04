# 功能测试报告 v1.1

> 日期：2026-10-04 ｜ 环境：macOS · JDK 22 · mock embedding（1024/512 双实例）+ mock chat
> 被测物：dist/arkrag-server.jar v1.1.0（含 /admin 控制台）+ arkrag-mcp-stdio.jar v1.1.0 + arkrag-plugin-1.1.0.zip
> 回归范围：v1.0 全部 36 条 active 用例（自动化 21 断言脚本 + MCP 管道）+ v1.1 新增 17 条

## 1. 结果总览

| 项 | 结果 |
|----|------|
| v1.0 累积回归（scripts/run-tests.sh） | **21/21 全绿** |
| v1.1 模型配置 TC-SET-001~007 | **7/7 通过**（含 512 维护栏 409 实证） |
| v1.1 rag_ask TC-ASK-001~005 | **5/5 通过**（HTTP/桥/插件三面一致） |
| v1.1 控制台 TC-ADMIN-001~005 | **5/5 通过**（真实浏览器对真服务 e2e） |
| 冒烟套件（v1.0 10 条 + v1.1 新 P0 6 条） | **16/16 全绿** |
| 阻塞/严重缺陷 | **清零**（过程中 2 缺陷已修复并回测，见 §3） |

## 2. 关键验证点

### 2.1 模型热配置（F18）
- GET 脱敏（****尾4 位）+ 来源三元（unset/yaml/runtime）✓；
- PUT chat → **无需重启**，`/ask` 立即可用（控制台 UI 保存 → 立即问答成功，全链截图为证）；
- `POST /settings/model/test` 分路：embedding 返回实际维度 1024；chat 返回模型回复 ✓；
- PUT 缺省组=清除回落 yml（契约语义，已文档化）✓；
- **换 Embedding 模型护栏实证**：PUT 512 维模型后 `/search` 与 `/ask` 均 409 `EMBEDDING_DIMENSION_MISMATCH`，
  消息携带"请对该库执行 POST /api/v1/kb/{id}/rebuild"✓。

### 2.2 独立 RAG 问答（F19）
- `/ask`：答案带 [n]、citations 驼峰且 n 与 index 对齐 ✓；
- MCP 三工具（rag_search/rag_list_kbs/**rag_ask**）在 Streamable 与 stdio 桥同契约 ✓；
- 插件 main.js/plugin.json 注册第三工具，node --check 与双闸核对通过 ✓；
- 无命中拒答、Chat 未配置 409 + 指引文案 ✓。

### 2.3 管理控制台（F17）
- `/admin` 302→index.html 200；静态免鉴权、数据 API 全鉴权 ✓；
- 真实浏览器 e2e（对真服务）：错 token 红字 → 正确 token 登录 → 建库 → **真实文件**上传（FormData multipart）→
  状态轮询"完成 1 块" → 检索（0.918 分命中）→ 模型页配置 chat（测试连通 ✓ 保存 ✓）→ 问答（答案 + 引用 + 用时）✓；
- 深色（跟随系统）/浅色（手动）截图检查阻塞清零（06b 增量节）。

## 3. 缺陷记录与修复回溯

| 级别 | 缺陷 | 修复 | 回测 |
|------|------|------|------|
| 严重 | 并发上传互踩 `docs.json.tmp`（同名临时文件竞争）→ 一文件 FAILED"元数据文件写入失败"（控制台 e2e 发现） | 临时文件名加 UUID（MetaFiles/VectorIndex）；提交登记纳入 KB 锁串行 | 控制台并发上传 2 文件全部 READY；run-tests 21/21 重跑绿 ✓ |
| 一般 | 控制台 token 门用免鉴权 /health 探活，错 token 也能"登录" | 改用受保护 GET /kb 验证 | 错 token 显式红字"token 无效或未设置" ✓ |
| 一般 | 旧 dist jar 未刷新导致误判"新接口 404" | build-dist 纳入流程（过程性问题，非产品缺陷） | — |
| 建议 | /health 版本号来自 pom（Boot 嵌套 jar 读到 1.0.0 旧值） | pom 全模块升 1.1.0 | health 返回 1.1.0 ✓ |

## 4. 遗留清单（非阻塞）

1. ArkWork 实机内联（含新 rag_ask 工具在 Agent 工具表的出现）仍待用户走查（沿 v1.0 遗留项）。
2. 控制台窄屏（<1024px）侧边栏折叠为 P1（交互文档已注明）。
3. mock chat 为 canned 回答；真实 LLM 的答案质量取决于模型本身，非本项目测试范围。
