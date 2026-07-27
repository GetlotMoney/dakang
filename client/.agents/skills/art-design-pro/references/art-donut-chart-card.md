# ArtDonutChartCard 环形图卡片

带有数值展示的环形图卡片组件，适合展示占比数据和同比变化。

## 基本信息

- **组件名称**: ArtDonutChartCard
- **组件路径**: `@/components/core/cards/art-donut-chart-card/index.vue`

## 功能特性

1. **数值展示** - 卡片显示数值和标题
2. **百分比变化** - 显示同比/环比变化
3. **环形图表** - 内置环形图展示数据占比
4. **对比数据** - 支持当前值和去年值对比

## 基本用法

```vue
<template>
  <ArtDonutChartCard
    :value="8500"
    title="本月销售额"
    :percentage="12.5"
    percentage-label="较上月"
    current-value="今年"
    previous-value="去年"
    :data="[6500, 2000]"
  />
</template>

<script setup lang="ts">
  import ArtDonutChartCard from '@/components/core/cards/art-donut-chart-card/index.vue'
</script>
```

## API

### Props

| 属性名          | 说明                      | 类型               | 默认值           |
| --------------- | ------------------------- | ------------------ | ---------------- |
| value           | 数值                      | `number`           | **必填**         |
| title           | 标题                      | `string`           | **必填**         |
| percentage      | 百分比变化                | `number`           | **必填**         |
| percentageLabel | 百分比标签                | `string`           | `''`             |
| currentValue    | 当前年份/期间标签         | `string`           | `''`             |
| previousValue   | 去年/上一期标签           | `string`           | `''`             |
| height          | 高度（rem单位）           | `number`           | `9`              |
| color           | 图表颜色                  | `string`           | `''`             |
| radius          | 环形半径                  | `[string, string]` | `['70%', '90%']` |
| data            | 数据数组 [当前值, 去年值] | `[number, number]` | `[0, 0]`         |

## 使用示例

### 基础使用

```vue
<ArtDonutChartCard
  :value="8500"
  title="本月销售额"
  :percentage="12.5"
  percentage-label="较上月"
  :data="[6500, 2000]"
/>
```

### 带年份对比

```vue
<ArtDonutChartCard
  :value="98500"
  title="年销售额"
  :percentage="15.8"
  percentage-label="较去年"
  current-value="今年"
  previous-value="去年"
  color="#67c23a"
  :data="[85000, 13500]"
/>
```

### 自定义半径

```vue
<ArtDonutChartCard
  :value="2568"
  title="订单总数"
  :percentage="8.3"
  percentage-label="较上月"
  :radius="['60%', '85%']"
  :data="[2200, 368]"
/>
```

## 注意事项

1. **必填属性**: `value`、`title`、`percentage`、`data` 为必填属性
2. **data 格式**: `data` 是一个包含两个数字的数组，第一个是当前值，第二个是对比值（去年）
3. **高度单位**: `height` 的单位是 rem，不是 px
4. **百分比显示**: 正数显示 `+`，负数显示 `-`

## 典型应用场景

- 仪表盘销售数据展示
- 年度数据对比分析
- 订单量统计
- 占比分析
