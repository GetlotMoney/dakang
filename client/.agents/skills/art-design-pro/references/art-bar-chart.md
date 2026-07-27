# ArtBarChart - 柱状图

支持单组/多组数据、堆叠模式、渐变色。

## 基本信息

- **组件名称**: ArtBarChart
- **组件路径**: `@/components/core/charts/art-bar-chart/index.vue`

## 功能特性

1. **单组/多组数据** - 支持多种数据格式
2. **堆叠模式** - 支持柱状图堆叠展示
3. **圆角配置** - 支持自定义柱子圆角
4. **渐变颜色** - 支持渐变色配置

## API

### Props（除通用外）

| 属性            | 类型                        | 默认值      | 说明       |
| --------------- | --------------------------- | ----------- | ---------- |
| `data`          | `number[] \| BarDataItem[]` | `[0,0,...]` | 图表数据   |
| `xAxisData`     | `string[]`                  | `[]`        | X轴标签    |
| `barWidth`      | `string \| number`          | `'40%'`     | 柱子宽度   |
| `borderRadius`  | `number`                    | `4`         | 圆角       |
| `stack`         | `boolean`                   | `false`     | 是否堆叠   |
| `showAxisLabel` | `boolean`                   | `true`      | 显示轴标签 |
| `showAxisLine`  | `boolean`                   | `true`      | 显示轴线   |
| `showSplitLine` | `boolean`                   | `true`      | 显示分割线 |

### BarDataItem

```ts
interface BarDataItem {
  name: string // 系列名称
  data: number[] // 数据值
}
```

## 基本用法

```vue
<!-- 基础柱状图 -->
<ArtBarChart
  :data="[120, 200, 150, 80, 70, 110, 130]"
  :x-axis-data="['周一', '周二', '周三', '周四', '周五', '周六', '周日']"
  :bar-width="'35%'"
  :border-radius="4"
/>

<!-- 堆叠柱状图 -->
<ArtBarChart
  :data="[
    { name: '线上', data: [120, 132, 101, 134, 90, 230, 210] },
    { name: '线下', data: [80, 92, 71, 94, 60, 130, 110] }
  ]"
  :x-axis-data="['周一', '周二', '周三', '周四', '周五', '周六', '周日']"
  stack
/>
```

## 典型应用场景

- 数据对比分析
- 分类统计
- 堆叠数据展示
