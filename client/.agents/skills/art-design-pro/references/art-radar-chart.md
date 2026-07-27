# ArtRadarChart - 雷达图

多维度数据对比分析图表。

## 基本信息

- **组件名称**: ArtRadarChart
- **组件路径**: `@/components/core/charts/art-radar-chart/index.vue`

## 功能特性

1. **多维度** - 支持任意数量的分析维度
2. **多组对比** - 支持多组数据同时展示
3. **区域填充** - 自动显示数据覆盖区域
4. **主题适配** - 自动适配亮色/暗色主题

## API

### Props（除通用外）

| 属性        | 类型                              | 默认值 | 说明       |
| ----------- | --------------------------------- | ------ | ---------- |
| `indicator` | `{ name: string; max: number }[]` | `[]`   | 雷达轴配置 |
| `data`      | `RadarDataItem[]`                 | `[]`   | 雷达数据   |

### RadarDataItem

```ts
interface RadarDataItem {
  name: string // 系列名称
  value: number[] // 数据值
}
```

## 基本用法

```vue
<ArtRadarChart
  :indicator="[
    { name: '销售额', max: 100 },
    { name: '订单量', max: 100 },
    { name: '客户满意度', max: 100 },
    { name: '复购率', max: 100 },
    { name: '响应速度', max: 100 }
  ]"
  :data="[
    { name: '门店A', value: [85, 90, 80, 85, 75] },
    { name: '门店B', value: [70, 75, 90, 70, 80] }
  ]"
  show-legend
/>
```

## 典型应用场景

- 多维度能力评估
- 产品/用户综合评价
- 竞品对比分析
