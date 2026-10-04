# 用例：ArkWork 插件（tc-plugin）

> 对应 PRD F11/F12/F13/F14 ｜ 执行方式：结构校验（自动化）+ 浏览器 mock 桥（UI）+ ArkWork 实机（待用户走查）

| ID | 用例 | 优先级 | smoke | 预期 |
|----|------|--------|-------|------|
| TC-PLUGIN-001 | zip 结构合法：plugin.json 包根、4 文件、≤64MB | P0 | Y | 与 ArkWork install.ts 校验一致 |
| TC-PLUGIN-002 | plugin.json 契约：schemaVersion 1.1、id 正则、kind=tool、权限在白名单内 | P0 | - | 校验通过（与 shared/types/plugin.ts 对照） |
| TC-PLUGIN-003 | main.js 语法合法（node --check）+ 桥方法/工具注册双闸一致 | P0 | - | provides.tools 与 register 一一对应 |
| TC-PLUGIN-004 | 面板 UI（浏览器 + mock 桥）：三 Tab 可切换、KB 列表/上传/检索/设置交互正常、五态可见、无控制台报错 | P0 | - | 见 06-ui-test-report.md |
| TC-PLUGIN-005 | ArkWork 实机：安装 → 激活 → Agent 可见并调用工具 | P0 | - | **未实跑，待用户确认**（见遗留清单） |

## 契约静态核对记录（2026-10-04）

- manifest 字段与 ArkWork `shared/types/plugin.ts:206-235` 一致；id `arkrag.rag` 匹配 `^[a-z0-9-]+(\.[a-z0-9-]+)+$`
- permissions 均在 `PLUGIN_PERMISSIONS` 白名单（net / tools.register / storage / views.register）
- 视图 viewRef `view:arkrag` 与 provides.views 一致；icon=Book 在 `renderer/icons.tsx` 已登记
- Host 半报文：`ctx.ark.views.register / onCall / tools.register / storage.get/set / net.fetch` 与
  `runtime/host-runtime.ts` `runtime/gateway.ts` 签名一致（net.fetch 返回 {status,headers,body} 已适配）
- Client 半报文：`lifecycle/activate` 握手、`{kind:'call',method:'host.call'}` 调用、`{kind:'reply'}` 回执、
  `ui.ready` 上报——与 `sample-plugins.ts`（Git Manager）逐字段一致
