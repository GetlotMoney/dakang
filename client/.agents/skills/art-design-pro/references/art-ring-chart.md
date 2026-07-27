# ArtRingChart - 环形图

优雅的环形图表，支持中心文字和数据标签显示。

## 基本信息

- **组件名称**: ArtRingChart
- **组件路径**: `@/components/core/charts/art-ring-chart/index.vue`

## 功能特性

1. **环形展示** - 中空环形设计，视觉更轻盈
2. **中心文本** - 支持显示中心汇总文字
3. **数据标签** - 支持显示/隐藏数据标签
4. **多组数据** - 支持多组数据同时展示

## API

### Props（除通用外）

| 属性           | 类型            | 默认值           | 说明         |
| -------------- | --------------- | ---------------- | ------------ |
| `data`         | `PieDataItem[]` | `[]`             | 图表数据     |
| `radius`       | `string[]`      | `['50%', '80%']` | 内外半径     |
| `borderRadius` | `number`        | `10`             | 饼块圆角     |
| `centerText`   | `string`        | `''`             | 中心文本     |
| `showLabel`    | `boolean`       | `false`          | 是否显示标签 |

### PieDataItem

```ts
interface PieDataItem {
  value: number // 数据值
  name: string // 数据名称
}
```

## 基本用法

```vue
<ArtRingChart
  :data="[
    { value: 1048, name: '搜索引擎' },
    { value: 735, name: '直接访问' },
    { value: 580, name: '邮件营销' },
    { value: 484, name: '联盟广告' }
  ]"
  center-text="总访问量"
  show-legend
  legend-position="right"
/>
```

## 典型应用场景

- 数据占比展示
- 分类统计
- 流量来源分析
