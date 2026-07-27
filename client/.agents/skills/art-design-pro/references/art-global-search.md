# ArtGlobalSearch - 全局搜索组件

提供全局搜索功能，支持搜索菜单、页面等。

## 基本信息

- **组件名称**: ArtGlobalSearch
- **组件路径**: `@/components/core/layouts/art-global-search/index.vue`

## 功能特性

1. **快速搜索** - 快捷键打开搜索
2. **搜索建议** - 显示搜索结果列表
3. **路由跳转** - 选择结果后跳转至对应页面

## 基本用法

```vue
<template>
  <ArtGlobalSearch />
</template>

<script setup lang="ts">
  import ArtGlobalSearch from '@/components/core/layouts/art-global-search/index.vue'
</script>
```

## 快捷键

- `Ctrl + K` 或 `Cmd + K`：打开搜索
- `Enter`：确认选择
- `Escape`：关闭搜索

## 典型应用场景

- 全局快速导航
- 页面搜索
