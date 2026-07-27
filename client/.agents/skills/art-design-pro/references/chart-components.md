# Chart Components - 图表组件

所有图表组件基于 **ECharts**（`@/plugins/echarts`），共享统一的主题适配、自动动画和响应式行为。

## 通用说明

### 基础 Props（所有图表）

| 属性             | 类型                                     | 默认值         | 说明             |
| ---------------- | ---------------------------------------- | -------------- | ---------------- |
| `height`         | `string`                                 | 项目默认高度   | 图表容器高度     |
| `loading`        | `boolean`                                | `false`        | 是否显示加载状态 |
| `isEmpty`        | `boolean`                                | `false`        | 是否为空数据状态 |
| `colors`         | `string[]`                               | 项目默认颜色组 | 自定义颜色数组   |
| `showTooltip`    | `boolean`                                | `true`         | 是否显示提示框   |
| `showLegend`     | `boolean`                                | `false`        | 是否显示图例     |
| `legendPosition` | `'top' \| 'bottom' \| 'left' \| 'right'` | `'bottom'`     | 图例位置         |

### 数据格式

所有图表均支持**单组数据**和**多组数据**两种格式：

```ts
// 单组数据
:data="[120, 200, 150, 80, 70]"

// 多组数据（带名称）
:data="[
  { name: '销售额', data: [120, 200, 150, 80, 70] },
  { name: '利润', data: [100, 180, 130, 60, 50] }
]"
```

### 通用 Hooks

所有图表内部使用 `useChartComponent` 和 `useChartOps` 管理：

```ts
// useChartOps — 获取全局图表配置
const { chartHeight, colors, fontSize, fontColor } = useChartOps()

// useChartComponent — 图表实例管理
// 提供: initChart, chartRef, isDark, getAxisLineStyle, getAxisLabelStyle,
//       getAxisTickStyle, getSplitLineStyle, getTooltipStyle, getLegendStyle,
//       getGridWithLegend, getAnimationConfig, isEmpty
```
