# 路由系统

## 目录结构

```
src/router/
├── routes/
│   ├── staticRoutes.ts     # 固定路由（登录、404 等）— 无需权限
│   └── asyncRoutes.ts      # 从 modules/index.ts 导出所有路由模块
├── modules/
│   ├── index.ts            # 聚合所有路由模块
│   ├── dashboard.ts        # 仪表盘路由模块
│   ├── system.ts           # 系统管理路由模块
│   ├── result.ts           # 结果页面（成功/失败）
│   └── exception.ts        # 异常页面（403/404/500）
├── guards/
│   ├── beforeEach.ts       # 全局前置导航守卫
│   └── afterEach.ts        # 全局后置导航守卫
├── core/
│   ├── RouteRegistry.ts    # 动态路由注册
│   ├── RouteTransformer.ts # 路由数据转换
│   ├── RouteValidator.ts   # 路由验证
│   ├── MenuProcessor.ts    # 菜单树生成
│   ├── ComponentLoader.ts  # 懒加载组件
│   └── IframeRouteManager.ts
├── index.ts                # Vue Router 配置
└── routesAlias.ts          # 路由路径常量
```

## 路由模块模式

`src/router/modules/` 目录下的每个文件定义一个路由树：

```typescript
// src/router/modules/yourModule.ts
import { AppRouteRecord } from '@/types/router'

export const yourModuleRoutes: AppRouteRecord = {
  path: '/your-module', // 父级绝对路径
  name: 'YourModule', // PascalCase 路由名称
  component: '/index/index', // 布局组件路径 — 父级路由始终使用此路径
  meta: {
    title: 'menus.yourModule.title', // i18n 国际化 key
    icon: 'ri:settings-3-line', // Iconify 图标名称
    roles: ['R_SUPER', 'R_ADMIN'] // 所需角色（可选）
  },
  children: [
    {
      path: 'page-a', // 相对路径（无前导 /）
      name: 'YourPageA',
      component: '/your-module/page-a', // 组件路径
      meta: {
        title: 'menus.yourModule.pageA',
        keepAlive: true, // 切换标签页时缓存组件
        fixedTab: true // 在标签栏中始终显示
      }
    },
    {
      path: 'page-b',
      name: 'YourPageB',
      component: '/your-module/page-b',
      meta: {
        title: 'menus.yourModule.pageB',
        isHide: true, // 在侧边栏菜单中隐藏（但仍可访问）
        isHideTab: true // 在标签栏中隐藏
      }
    }
  ]
}
```

## 添加新路由模块

### 步骤 1：创建模块文件

```typescript
// src/router/modules/product.ts
import { AppRouteRecord } from '@/types/router'

export const productRoutes: AppRouteRecord = {
  path: '/product',
  name: 'Product',
  component: '/index/index',
  meta: {
    title: 'menus.product.title',
    icon: 'ri:shopping-bag-line',
    roles: ['R_SUPER', 'R_ADMIN']
  },
  children: [
    {
      path: 'list',
      name: 'ProductList',
      component: '/product/list',
      meta: { title: 'menus.product.list', keepAlive: true }
    }
  ]
}
```

### 步骤 2：在 modules/index.ts 中注册

```typescript
// src/rocuter/modules/index.ts
import { AppRouteRecord } from '@/types/router'
import { dashboardRoutes } from './dashboard'
import { systemRoutes } from './system'
import { resultRoutes } from './result'
import { exceptionRoutes } from './exception'
import { productRoutes } from './product' // 添加

export const routeModules: AppRouteRecord[] = [
  dashboardRoutes,
  systemRoutes,
  resultRoutes,
  exceptionRoutes,
  productRoutes // 添加
]
```

### 步骤 3：添加国际化 key

```json
// src/locales/langs/zh.json
{
  "menus": {
    "product": {
      "title": "商品管理",
      "list": "商品列表"
    }
  }
}
```

```json
// src/locales/langs/en.json
{
  "menus": {
    "product": {
      "title": "Product Management",
      "list": "Product List"
    }
  }
}
```

### 步骤 4：创建视图页面

```
src/views/product/list/index.vue
```

```vue
<script setup lang="ts">
  // ...
</script>

<template>
  <div class="page-content">
    <!-- 页面内容 -->
  </div>
</template>
```

## 路由 Meta 选项

| 选项            | 类型                  | 说明                                     |
| --------------- | --------------------- | ---------------------------------------- |
| `title`         | `string`              | 菜单和标签页的国际化 key                 |
| `icon`          | `string`              | Iconify 图标名称（`ri:xxx`）             |
| `roles`         | `string[]`            | 所需角色：`R_SUPER`、`R_ADMIN`、`R_USER` |
| `keepAlive`     | `boolean`             | 切换标签页时缓存组件                     |
| `fixedTab`      | `boolean`             | 在标签栏中始终显示                       |
| `isHide`        | `boolean`             | 在侧边栏菜单中隐藏                       |
| `isHideTab`     | `boolean`             | 在标签栏中隐藏                           |
| `isFullPage`    | `boolean`             | 全屏页面（无侧边栏/头部）                |
| `isIframe`      | `boolean`             | 在 iframe 中加载外部 URL                 |
| `link`          | `string`              | 外部链接 URL                             |
| `authList`      | `{title, authMark}[]` | 按钮权限码列表                           |
| `activePath`    | `string`              | 覆盖当前激活的菜单路径                   |
| `showBadge`     | `boolean`             | 在菜单上显示徽章                         |
| `showTextBadge` | `string`              | 菜单上的文字徽章                         |

## 权限角色

```typescript
// 角色层级
R_SUPER // 超级管理员 — 访问所有内容
R_ADMIN // 管理员 — 访问大部分页面
R_USER // 普通用户 — 受限访问
```

没有定义 `roles` 的路由所有已认证用户都可以访问。

## 嵌套路由

```typescript
{
  path: '/system',
  component: '/index/index',
  meta: { title: 'menus.system.title' },
  children: [
    // 一级子路由
    { path: 'user', component: '/system/user', meta: { title: 'menus.system.user' } },
    // 二级容器路由 — 无组件
    {
      path: 'nested',
      component: '',  // 仅作为容器
      meta: { title: 'menus.system.nested' },
      children: [
        { path: 'menu1', component: '/system/nested/menu1', meta: { title: 'menus.system.menu1' } }
      ]
    }
  ]
}
```

## 路由别名

```typescript
// src/router/routesAlias.ts
export enum RoutesAlias {
  Layout = '/index/index',
  Login = '/auth/login'
}
```

## 静态路由

静态路由定义在 `src/router/routes/staticRoutes.ts` 中，支持动态导入：

```typescript
export const staticRoutes: AppRouteRecordRaw[] = [
  {
    path: '/auth/login',
    component: () => import('@views/auth/login/index.vue'), // 这里可以使用动态导入
    meta: { title: 'menus.login.title', isHideTab: true }
  }
]
```

## Iframe 页面

```typescript
{
  path: '/outside/element-plus',
  component: '',
  meta: {
    title: 'Element Plus',
    isIframe: true,
    link: 'https://element-plus.org/'
  }
}
```

## 权限控制模式

通过 `.env` 中的 `VITE_ACCESS_MODE` 设置：

- `frontend`：通过 `meta.roles` 过滤路由
- `backend`：根据后端菜单 API 响应生成路由
