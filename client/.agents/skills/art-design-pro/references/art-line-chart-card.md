# ArtLineChartCard 折线图卡片

带有数值展示的折线图卡片组件，适合展示趋势数据和百分比变化。

## 基本信息

- **组件名称**: ArtLineChartCard
- **组件路径**: `@/components/core/cards/art-line-chart-card/index.vue`

## 功能特性

1. **数值展示** - 卡片顶部显示数值和标签
2. **百分比变化** - 显示同比/环比变化
3. **趋势图表** - 内置折线图展示数据趋势
4. **区域填充** - 支持显示区域填充效果

## 基本用法

```vue
<template>
  <ArtLineChartCard
    :value="8500"
    label="本月销售额"
    :percentage="12.5"
    date="2024-03"
    :chart-data="[120, 200, 150, 180, 250, 200]"
  />
</template>

<script setup lang="ts">
  import ArtLineChartCard from '@/components/core/cards/art-line-chart-card/index.vue'
</script>
```

## API

### Props

| 属性名        | 说明             | 类型       | 默认值   |
| ------------- | ---------------- | ---------- | -------- |
| value         | 数值             | `number`   | **必填** |
| label         | 标签文本         | `string`   | **必填** |
| percentage    | 百分比变化       | `number`   | **必填** |
| date          | 日期文本         | `string`   | `''`     |
| height        | 高度（rem单位）  | `number`   | `11`     |
| color         | 图表颜色         | `string`   | `''`     |
| showAreaColor | 是否显示区域填充 | `boolean`  | `false`  |
| chartData     | 图表数据数组     | `number[]` | **必填** |
| isMiniChart   | 是否为迷你图表   | `boolean`  | `false`  |

## 使用示例

### 基础使用

```vue
<ArtLineChartCard
  :value="8500"
  label="本月销售额"
  :percentage="12.5"
  :chart-data="[120, 200, 150, 180, 250, 200]"
/>
```

### 带日期和区域填充

```vue
<ArtLineChartCard
  :value="25890"
  label="访问量"
  :percentage="-5.2"
  date="近7日"
  show-area-color
  :chart-data="[820, 932, 901, 934, 1290, 1130, 980]"
/>
```

### 销售额卡片

```vue
<ArtLineChartCard
  :value="123456"
  label="总销售额"
  :percentage="15.8"
  date="2024年3月"
  color="#67c23a"
  show-area-color
  :chart-data="[4100, 5200, 6800, 7500, 9200, 10500]"
/>
```

### 订单量卡片

```vue
<ArtLineChartCard
  :value="856"
  label="订单总数"
  :percentage="8.3"
  date="本月"
  :chart-data="[120, 132, 101, 134, 90, 230]"
/>
```

## 注意事项

1. **必填属性**: `value`、`label`、`percentage`、`chartData` 为必填属性
2. **高度单位**: `height` 的单位是 rem，不是 px
3. **百分比显示**: 正数显示 `+`，负数显示 `-`
4. **颜色配置**: 可以通过 `color` 属性自定义图表颜色

## 典型应用场景

- 仪表盘销售数据展示
- 业务指标趋势分析
- 访问量/订单量监控
- 环比/同比增长展示
