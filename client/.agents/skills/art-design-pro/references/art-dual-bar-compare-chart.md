# ArtDualBarCompareChart - 双向对比柱状图

上下对称的双向柱状图，适合对比分析，如人口金字塔。

## 基本信息

- **组件名称**: ArtDualBarCompareChart
- **组件路径**: `@/components/core/charts/art-dual-bar-compare-chart/index.vue`

## 功能特性

1. **双向对比** - 左右/上下对称的数据对比
2. **数据标签** - 支持显示数据标签
3. **圆角配置** - 支持自定义柱子圆角
4. **Y轴配置** - 支持自定义 Y 轴范围

## API

### Props

| 属性                   | 类型       | 默认值        | 说明                  |
| ---------------------- | ---------- | ------------- | --------------------- |
| `positiveData`         | `number[]` | `[]`          | 正向数据（向上/向右） |
| `negativeData`         | `number[]` | `[]`          | 负向数据（向下/向左） |
| `xAxisData`            | `string[]` | `[]`          | X轴标签               |
| `positiveName`         | `string`   | `'正向数据'`  | 正向图例名            |
| `negativeName`         | `string`   | `'负向数据'`  | 负向图例名            |
| `barWidth`             | `number`   | `16`          | 柱子宽度              |
| `yAxisMin`             | `number`   | `-100`        | Y轴最小值             |
| `yAxisMax`             | `number`   | `100`         | Y轴最大值             |
| `showDataLabel`        | `boolean`  | `false`       | 显示数据标签          |
| `positiveBorderRadius` | `number[]` | `[10,10,0,0]` | 正向圆角              |
| `negativeBorderRadius` | `number[]` | `[0,0,10,10]` | 负向圆角              |
| `showAxisLabel`        | `boolean`  | `true`        | 显示轴标签            |
| `showAxisLine`         | `boolean`  | `false`       | 显示轴线              |
| `showSplitLine`        | `boolean`  | `false`       | 显示分割线            |

## 基本用法

```vue
<ArtDualBarCompareChart
  :positive-data="[45, 52, 38, 65, 72, 48, 55]"
  :negative-data="[30, 25, 40, 20, 15, 35, 28]"
  :x-axis-data="['0-18', '18-30', '30-45', '45-60', '60-75', '75+']"
  positive-name="男性"
  negative-name="女性"
  :show-data-label="true"
/>
```

## 典型应用场景

- 人口年龄分布
- 正负数据对比
- 收支对比分析
