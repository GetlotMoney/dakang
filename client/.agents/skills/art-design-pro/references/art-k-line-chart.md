# ArtKLineChart - K线图（股票蜡烛图）

用于展示金融数据、股票走势等。

## 基本信息

- **组件名称**: ArtKLineChart
- **组件路径**: `@/components/core/charts/art-k-line-chart/index.vue`

## 功能特性

1. **OHLC 数据** - 支持开盘价、收盘价、最高价、最低价
2. **缩放功能** - 支持数据区域缩放
3. **颜色配置** - 支持自定义涨跌颜色

## API

### Props（除通用外）

| 属性            | 类型              | 默认值  | 说明            |
| --------------- | ----------------- | ------- | --------------- |
| `data`          | `KLineDataItem[]` | `[]`    | K线数据         |
| `showDataZoom`  | `boolean`         | `false` | 显示缩放控件    |
| `dataZoomStart` | `number`          | `0`     | 缩放起始位置(%) |
| `dataZoomEnd`   | `number`          | `100`   | 缩放结束位置(%) |

### KLineDataItem

```ts
interface KLineDataItem {
  time: string // 时间标签
  open: number // 开盘价
  close: number // 收盘价
  high: number // 最高价
  low: number // 最低价
}
```

## 基本用法

```vue
<ArtKLineChart
  :data="[
    { time: '2024-01', open: 100, close: 110, high: 115, low: 98 },
    { time: '2024-02', open: 110, close: 105, high: 118, low: 102 },
    { time: '2024-03', open: 105, close: 120, high: 125, low: 103 }
  ]"
  :colors="['#4C87F3', '#8BD8FC']"
/>
```

## 典型应用场景

- 股票走势展示
- 金融数据分析
- 期货/外汇行情
