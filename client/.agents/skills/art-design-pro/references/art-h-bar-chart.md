# ArtHBarChart - 横向柱状图

与 `ArtBarChart` 类似，但柱子水平显示。

## 基本信息

- **组件名称**: ArtHBarChart
- **组件路径**: `@/components/core/charts/art-h-bar-chart/index.vue`

## 功能特性

1. **水平布局** - 柱子水平显示，适合分类标签较长的场景
2. **数据格式** - 与 ArtBarChart 相同
3. **主题适配** - 自动适配亮色/暗色主题

## API

与 ArtBarChart 相同，支持以下额外配置：

| 属性           | 类型                        | 默认值  | 说明     |
| -------------- | --------------------------- | ------- | -------- |
| `data`         | `number[] \| BarDataItem[]` | `[]`    | 图表数据 |
| `yAxisData`    | `string[]`                  | `[]`    | Y轴标签  |
| `barHeight`    | `string \| number`          | `'40%'` | 柱子高度 |
| `borderRadius` | `number`                    | `4`     | 圆角     |

## 基本用法

```vue
<ArtHBarChart
  :data="[120, 200, 150, 80, 70]"
  :y-axis-data="['衬衫', '毛衣', '雪纺衫', '裤子', '高跟鞋']"
/>
```

## 典型应用场景

- 排行榜展示
- 分类数据对比
- 标签较长的分类展示
