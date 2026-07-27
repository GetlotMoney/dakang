# ArtBarChartCard 柱状图卡片

柱状图卡片组件，将柱状图与卡片包装结合使用。

## 基本信息

- **组件名称**: ArtBarChartCard
- **组件路径**: `@/components/core/cards/art-bar-chart-card/index.vue`

## 功能特性

1. **卡片包装** - 图表与卡片结合
2. **标题操作** - 支持标题和操作按钮
3. **加载状态** - 支持 loading 状态
4. **空状态** - 支持空数据展示

## 基本用法

```vue
<template>
  <ArtBarChartCard
    title="销售统计"
    :x-axis-data="['1月', '2月', '3月', '4月']"
    :data="[{ name: '销量', value: [120, 200, 150, 180] }]"
  />
</template>

<script setup lang="ts">
  import ArtBarChartCard from '@/components/core/cards/art-bar-chart-card/index.vue'
</script>
```

## API

### Props

| 属性名      | 说明     | 类型                                       | 默认值    |
| ----------- | -------- | ------------------------------------------ | --------- |
| title       | 卡片标题 | `string`                                   | `''`      |
| height      | 图表高度 | `string`                                   | `'300px'` |
| loading     | 加载状态 | `boolean`                                  | `false`   |
| x-axis-data | X 轴数据 | `string[]`                                 | `[]`      |
| data        | 图表数据 | `Array<{ name: string; value: number[] }>` | `[]`      |

## 典型应用场景

- 仪表盘图表展示
- 业务数据统计
- 数据对比分析
