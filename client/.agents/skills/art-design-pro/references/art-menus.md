# ArtMixedMenu / ArtSidebarMenu / ArtHorizontalMenu - 菜单组件

提供多种布局形式的导航菜单组件。

## 基本信息

- **组件名称**: ArtMixedMenu, ArtSidebarMenu, ArtHorizontalMenu
- **组件路径**:
  - `@/components/core/layouts/art-menus/art-mixed-menu/index.vue`
  - `@/components/core/layouts/art-menus/art-sidebar-menu/index.vue`
  - `@/components/core/layouts/art-menus/art-horizontal-menu/index.vue`

## 功能特性

1. **混合菜单** - 侧边栏和水平菜单组合
2. **侧边菜单** - 垂直布局的侧边栏菜单
3. **水平菜单** - 水平布局的顶部菜单
4. **路由集成** - 与 Vue Router 集成
5. **权限控制** - 支持菜单权限过滤

## ArtSidebarMenu

### 基本用法

```vue
<template>
  <ArtSidebarMenu :menu-data="menuData" />
</template>

<script setup lang="ts">
  import ArtSidebarMenu from '@/components/core/layouts/art-menus/art-sidebar-menu/index.vue'

  const menuData = ref([
    { path: '/dashboard', title: '首页', icon: 'ri:home-line' },
    {
      path: '/user',
      title: '用户管理',
      icon: 'ri:user-line',
      children: [
        { path: '/user/list', title: '用户列表' },
        { path: '/user/add', title: '新增用户' }
      ]
    }
  ])
</script>
```

## 典型应用场景

- 后台管理系统导航
- 多级菜单导航
- 权限菜单展示
