# ArtTableHeader - 表格头部组件

用于包装表格操作区域的组件，提供统一的操作按钮区域。

## 基本信息

- **组件名称**: ArtTableHeader
- **组件路径**: `@/components/core/tables/art-table-header/index.vue`

## 功能特性

1. **标题区域** - 显示表格标题
2. **操作区域** - 显示操作按钮
3. **搜索区域** - 显示搜索表单

## 基本用法

```vue
<template>
  <ArtTableHeader title="用户列表">
    <template #actions>
      <ElButton type="primary">新增</ElButton>
      <ElButton>导出</ElButton>
    </template>
  </ArtTableHeader>
</template>

<script setup lang="ts">
  import ArtTableHeader from '@/components/core/tables/art-table-header/index.vue'
</script>
```

## API

### Props

| 属性名      | 说明 | 类型     | 默认值 |
| ----------- | ---- | -------- | ------ |
| title       | 标题 | `string` | `''`   |
| description | 描述 | `string` | `''`   |

### Slots

| 插槽名  | 说明         |
| ------- | ------------ |
| actions | 操作按钮区域 |
| search  | 搜索表单区域 |

## 典型应用场景

- 表格页面头部
- 列表页标题区域
- 操作按钮区域
