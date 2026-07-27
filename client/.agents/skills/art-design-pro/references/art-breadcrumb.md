# ArtBreadcrumb - 面包屑导航

自动从 `route.matched` 生成面包屑路径，支持点击跳转。

## 基本信息

- **组件名称**: ArtBreadcrumb
- **组件路径**: `@/components/core/layouts/art-breadcrumb/index.vue`

## 功能特性

1. **自动解析** - 自动从路由解析面包屑路径
2. **点击跳转** - 支持点击跳转至对应层级
3. **响应式** - 自动适配不同屏幕尺寸

## 特性

- 仅在非首页路由显示
- 自动过滤包裹容器（如 `/outside`）
- IFrame 页面特殊处理
- 支持多层级嵌套

## 基本用法

```vue
<ArtBreadcrumb />
```

## 典型应用场景

- 页面顶部导航
- 路径导航
- 位置指示
