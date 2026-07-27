# ArtPageContent - 页面内容容器组件

用于包装页面主内容区域的容器组件。

## 基本信息

- **组件名称**: ArtPageContent
- **组件路径**: `@/components/core/layouts/art-page-content/index.vue`

## 功能特性

1. **布局容器** - 提供标准页面内容容器
2. **内边距** - 统一的内边距设置
3. **响应式** - 自动适配不同屏幕尺寸

## 基本用法

```vue
<template>
  <ArtPageContent>
    <h1>页面标题</h1>
    <div>页面内容</div>
  </ArtPageContent>
</template>

<script setup lang="ts">
  import ArtPageContent from '@/components/core/layouts/art-page-content/index.vue'
</script>
```

## 典型应用场景

- 页面内容包装
- 统一页面布局
