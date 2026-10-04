# 功能测试报告 v1.0

> 日期：2026-10-04 ｜ 执行环境：macOS（darwin 24.6.0，x64）· JDK 22 · mock embedding（1024 维，离线）
> 被测物：`dist/arkrag-server.jar`（构建产物，非源码运行）+ `dist/arkrag-mcp-stdio.jar` + `dist/arkrag-plugin-1.0.0.zip`
> 执行器：`scripts/run-tests.sh`（可自动化用例）+ 手工管道脚本（MCP stdio/Streamable）

## 1. 执行顺序与结果总览

| 阶段 | 结果 |
|------|------|
| 冒烟（10 条，01-smoke-suite.md） | **10/10 全绿** |
| 详测（可自动化 21 断言） | **21/21 全绿**（`scripts/run-tests.sh` 输出 PASS=21 FAIL=0） |
| MCP stdio / Streamable / SSE（tc-mcp 9 条） | **9/9 全绿**（含服务不可达、token 错误两条负路径） |
| 手工/静态核对（tc-plugin 4 条） | **通过**（见 §3） |
| 未实跑 | TC-PLUGIN-005（ArkWork 实机内联）→ 遗留清单，待用户确认 |

**门禁结论：冒烟全绿 + P0 全过 + 阻塞/严重缺陷清零 ✓**

## 2. 关键验证点摘录

### 2.1 摄入与检索引擎
- 状态机 PENDING→PARSING→EMBEDDING→READY 全程 docs.json 可见；READY 回填 chunkCount、KB dims 回填 1024。
- 切块：重复 60 次的说明文（≈3900 字符，chunkSize=1000/overlap=150）切出 ≥2 块 ✓。
- 语义排序：mock 伪向量下"embedding 模型怎么配置维度"→ embedding 文块 0.764 分居首，无关块 0.0 ✓。
- 覆盖语义：同名重传后 chunk 总数不增长（4→4）、检索只返回覆盖后内容 ✓。
- 删除文档 204 且不再命中 ✓；删除 KB 级联（列表消失 + data 目录删除）✓。
- 重启持久化：杀进程重启后 KB 与向量可用 ✓。

### 2.2 HTTP API 与鉴权
- 无/错 token → 401 `UNAUTHORIZED`；`/health` 免鉴权 ✓。
- 建库校验（空名/重名）→ 400 VALIDATION；不存在 KB → 404 KB_NOT_FOUND ✓。
- multipart / JSON text / JSON base64 三种摄入通道全部 ✓。
- minScore 过滤、topK 边界 ✓。

### 2.3 MCP
- stdio 桥：initialize（协议 2025-06-18）+ tools/list 两工具；stdout 纯协议帧、日志走 stderr ✓。
- 桥 rag_search 返回带出处 hits；token 错误 → `isError:true` + "ARKRAG_TOKEN 与服务端…不一致" ✓。
- 桥在服务停止时：isError + "请先启动服务"，不悬挂 ✓。
- Streamable HTTP `/mcp`：initialize → session → tools/call（SSE 流式回包）✓；SSE `/mcp/sse` endpoint 事件 ✓；无 token 401 ✓。

### 2.4 ArkWork 插件（静态核对）
- zip 结构：plugin.json 包根 + 4 文件、13.7KB（限额内）✓。
- manifest：schemaVersion 1.1、id `arkrag.rag`（匹配宿主正则）、kind=tool、permissions 全在 `PLUGIN_PERMISSIONS` 白名单、icon=Book 已登记 ✓。
- Host 半：`ctx.ark.views.register / views.onCall / tools.register（provides.tools 双闸对应）/ storage.get/set / net.fetch` 与 ArkWork v0.45 runtime 签名一致；net.fetch 返回 `{status,headers,body}` 已适配 ✓。
- Client 半：桥报文（lifecycle/activate、host.call、reply、ui.ready）与 Git Manager 范例逐字段一致；主题令牌经 applyTheme 注入 ✓。

## 3. 缺陷记录与修复回溯

| 级别 | 缺陷 | 修复 | 回测 |
|------|------|------|------|
| 严重 | 摄入状态永不落盘（persistDocs 写回磁盘旧值，文档卡 PENDING）——编码自查发现 | IngestService 管线持有工作列表，状态变更即时落盘（缺陷回溯无文档影响：实现细节） | 重跑：READY + chunkCount 正确 ✓ |
| 一般 | 测试脚本自身两处缺陷（重名前置缺失、bash 算术展开） | 修脚本 | 重跑全绿 |
| 建议 | 0 维度在 /health 显示为 0（语义=未回填） | 不修（符合契约 dims 可为 0=待回填） | — |

## 4. 累积回归说明

v1.0 为首版：当前测试集 = 新增 36 条（无继承）。后续版本升级时按铁律③整目录复制 `testcases/` 后增量维护。

## 5. 遗留清单（非阻塞）

1. **TC-PLUGIN-005 ArkWork 实机内联**（安装→激活→Agent 调用全链路）：需要在本机 ArkWork 中实装，属用户走查项（见 06-ui-test-report.md 同项）。
2. 扫描件 PDF（空文本层）路径仅静态审查（Tika 空文本 → FAILED），未用真实扫描件实测。
3. TC-ENG-006 维度护栏（EMBEDDING_DIMENSION_MISMATCH → 409）依赖"换模型后不 rebuild"场景，冒烟以静态审查覆盖（rebuild 接口已单测 202）。
