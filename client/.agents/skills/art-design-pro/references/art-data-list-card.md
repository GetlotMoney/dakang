# ArtDataListCard - 数据列表卡片组件

用于展示数据列表的卡片组件，支持标题、描述和自定义内容。

## 基本信息

- **组件名称**: ArtDataListCard
- **组件路径**: `@/components/core/cards/art-data-list-card/index.vue`

## 功能特性

1. **标题描述** - 支持标题和描述
2. **数据展示** - 支持列表数据展示
3. **自定义内容** - 支持插槽自定义

## 基本用法

```vue
<template>
  <ArtDataListCard title="数据列表" :data="dataList" />
</template>

<script setup lang="ts">
  import ArtDataListCard from '@/components/core/cards/art-data-list-card/index.vue'

  const dataList = ref([
    { label: '用户数', value: 1234 },
    { label: '订单数', value: 5678 }
  ])
</script>
```

## API

### Props

| 属性名      | 说明     | 类型         | 默认值 |
| ----------- | -------- | ------------ | ------ |
| title       | 标题     | `string`     | `''`   |
| description | 描述     | `string`     | `''`   |
| data        | 数据列表 | `DataItem[]` | `[]`   |

### DataItem 类型

```typescript
interface DataItem {
  label: string // 标签
  value: any // 值
  icon?: string // 图标
}
```

## 典型应用场景

- 统计数据展示
- 列表信息展示
- 指标汇总展示
