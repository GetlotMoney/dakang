# ArtLineChart - 折线图

支持多组数据、阶梯式动画、区域填充，平滑曲线。

## 基本信息

- **组件名称**: ArtLineChart
- **组件路径**: `@/components/core/charts/art-line-chart/index.vue`

## 功能特性

1. **多组数据** - 支持多条折线同时展示
2. **平滑曲线** - 支持平滑和非平滑曲线切换
3. **区域填充** - 支持显示区域填充效果
4. **主题适配** - 自动适配亮色/暗色主题

## API

### Props（除通用外）

| 属性             | 类型                         | 默认值      | 说明               |
| ---------------- | ---------------------------- | ----------- | ------------------ |
| `data`           | `number[] \| LineDataItem[]` | `[0,0,...]` | 图表数据           |
| `xAxisData`      | `string[]`                   | `[]`        | X轴标签            |
| `lineWidth`      | `number`                     | `2.5`       | 线条宽度           |
| `showAreaColor`  | `boolean`                    | `false`     | 是否显示区域填充   |
| `smooth`         | `boolean`                    | `true`      | 是否平滑曲线       |
| `symbol`         | `SymbolType`                 | `'none'`    | 数据点符号         |
| `symbolSize`     | `number`                     | `6`         | 数据点大小         |
| `animationDelay` | `number`                     | `200`       | 多数据动画延迟(ms) |
| `showAxisLabel`  | `boolean`                    | `true`      | 显示轴标签         |
| `showAxisLine`   | `boolean`                    | `true`      | 显示轴线           |
| `showSplitLine`  | `boolean`                    | `true`      | 显示分割线         |

### LineDataItem

```ts
interface LineDataItem {
  name: string // 系列名称
  data: number[] // 数据值
  lineWidth?: number // 线条宽度
  showAreaColor?: boolean // 显示区域填充
  areaStyle?: {
    // 区域样式配置
    startOpacity?: number
    endOpacity?: number
    custom?: any // 自定义 ECharts areaStyle
  }
  smooth?: boolean // 是否平滑
  symbol?: SymbolType // 数据点符号
  symbolSize?: number // 数据点大小
}
```

## 基本用法

```vue
<!-- 单条折线 -->
<ArtLineChart
  :data="[120, 132, 101, 134, 90, 230, 210]"
  :x-axis-data="['周一', '周二', '周三', '周四', '周五', '周六', '周日']"
  :line-width="3"
  show-area-color
/>

<!-- 多条折线 -->
<ArtLineChart
  :data="[
    { name: '销售额', data: [120, 132, 101, 134, 90, 230, 210] },
    { name: '订单量', data: [80, 92, 71, 94, 60, 130, 110] }
  ]"
  :x-axis-data="['周一', '周二', '周三', '周四', '周五', '周六', '周日']"
  show-legend
  legend-position="bottom"
/>
```

## 典型应用场景

- 数据趋势展示
- 多指标对比
- 时间序列分析
