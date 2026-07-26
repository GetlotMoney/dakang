---
name: art-design-pro
description: 六维达康 PC 管理端 Vue 3 + Element Plus + TypeScript 开发规范。创建页面、路由、表格、搜索、弹窗和字典交互时使用。
---

# 六维达康 PC 前端规范

## 强制前置门

1. 先读根目录 `AGENTS.md`、`docs/development-workflow.md`、对应 REQ 与 `docs/requirements/demo-business-chain-matrix.md`。
2. 当前默认是 Demo 定型：业务故事 → 页面/API 契约 → Mock → 用户验收。未过验收门不得提前接真。
3. PC 只实现后台职责。扫码、用户下单、配送员接单/送达、设备上报等跨端动作只能展示配置、状态合同和证据，禁止放“模拟成功”按钮。

## 项目结构

- 路由：`client/src/router/modules/<module>.ts`
- 路由注册：`client/src/router/modules/index.ts`
- API：`client/src/api/<module>.ts`
- 页面：`client/src/views/<module>/`
- 菜单国际化：`client/src/locales/langs/zh.json`、`en.json`
- 业务页内导航：`client/src/config/businessNavigation.ts`
- 最终后端菜单：`deploy/mysql/init/03-demo-baseline.sql`

## 当前导航

PC Demo 使用 5+1 一级业务入口：运营总览、水站管理、设备中控、水种套餐、用户管理、订单中心，另保留系统管理底座。多页面模块使用 `BusinessModuleNav` 页内切换；不得恢复多页签、快速入口或第二套侧栏结构。

路由、页面组件、后端菜单三者必须同步。页面最终验收入口固定为 `http://localhost:8081/#/...`，13321 只用于 HMR。

## 页面实现

- 列表页优先使用 `ArtTable` + `useTable`。
- 搜索使用 `ArtSearchBar auto-search`；输入或选择后自动查询，不重复提供“查询”按钮。
- 业务搜索组件放 `modules/*-search.vue`，弹窗/抽屉放 `modules/`。
- 状态、类型等枚举必须从字典加载，禁止硬编码下拉选项。
- 金额显示时由“分”转“元”，水量由“毫升”转“升”；接口仍保留整数原单位。
- 所有 Mock 字段需与后续 DTO 对齐，且明确数据 owner、状态来源和终态证据。
- 外部依赖未接入时展示“待接入/待确认”，不得显示虚假的成功率、备份成功、发布成功或支付成功。
- 高风险资金和设备操作必须二次确认、原因必填、权限受控，并等待真实后端状态机。

## 新页面流程

1. 对照 REQ 写页面责任、起点、终态和证据。
2. 定义 TypeScript DTO/Mock 契约。
3. 创建路由并注册。
4. 若模块有多个页面，同步 `businessNavigation.ts`。
5. 同步中英文菜单键。
6. 后端菜单模式下同步 `03-demo-baseline.sql` 的路径、组件和权限。
7. 实现页面；跨端状态只读展示，不伪造推进。
8. 执行检查并回到 8081 验收。

## 完成检查

```bash
cd client
pnpm exec vue-tsc --noEmit
pnpm run lint:prettier
pnpm run build
```

还要检查：深链刷新、一级菜单高亮、自动查询、空态、加载态、错误态、权限、Mock 标识、卡片对齐和常见桌面分辨率。
