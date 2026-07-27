# ArtScatterChart - 散点图

用于展示两个变量之间关系的图表。

## 基本信息

- **组件名称**: ArtScatterChart
- **组件路径**: `@/components/core/charts/art-scatter-chart/index.vue`

## 功能特性

1. **数据关系** - 展示两个变量之间的相关性
2. **坐标轴** - 支持自定义 X/Y 轴配置
3. **主题适配** - 自动适配亮色/暗色主题

## API

### Props（除通用外）

| 属性            | 类型                | 默认值 | 说明       |
| --------------- | ------------------- | ------ | ---------- |
| `data`          | `ScatterDataItem[]` | `[]`   | 散点数据   |
| `symbolSize`    | `number`            | `14`   | 散点大小   |
| `showAxisLabel` | `boolean`           | `true` | 显示轴标签 |
| `showAxisLine`  | `boolean`           | `true` | 显示轴线   |
| `showSplitLine` | `boolean`           | `true` | 显示分割线 |

### ScatterDataItem

```ts
interface ScatterDataItem {
  value: [number, number] // [x, y] 坐标
}
```

## 基本用法

```vue
<ArtScatterChart
  :data="[
    { value: [10, 8.04] },
    { value: [8, 6.95] },
    { value: [13, 7.58] },
    { value: [9, 8.81] },
    { value: [11, 8.33] }
  ]"
  :symbol-size="14"
/>
```

## 典型应用场景

- 数据相关性分析
- 分布密度展示
- 异常值检测
