# 其他图表组件

包含 ArtHBarChart、ArtRingChart、ArtRadarChart、ArtScatterChart、ArtKLineChart、ArtMapChart、ArtDualBarCompareChart 等图表组件。

## 基本信息

这些组件都基于 ECharts 实现，支持亮色/暗色主题自动适配。

### 组件路径

- `art-h-bar-chart`: `@/components/core/charts/art-h-bar-chart/index.vue` - 水平柱状图
- `art-ring-chart`: `@/components/core/charts/art-ring-chart/index.vue` - 环形图
- `art-radar-chart`: `@/components/core/charts/art-radar-chart/index.vue` - 雷达图
- `art-scatter-chart`: `@/components/core/charts/art-scatter-chart/index.vue` - 散点图
- `art-k-line-chart`: `@/components/core/charts/art-k-line-chart/index.vue` - K 线图
- `art-map-chart`: `@/components/core/charts/art-map-chart/index.vue` - 地图图表
- `art-dual-bar-compare-chart`: `@/components/core/charts/art-dual-bar-compare-chart/index.vue` - 双柱对比图

## 功能特性

1. **主题适配** - 自动适配亮色/暗色主题
2. **响应式** - 自动响应容器大小变化
3. **动画支持** - 支持入场动画
4. **空状态** - 支持空数据展示

## ArtHBarChart - 水平柱状图

```vue
<ArtHBarChart
  :y-axis-data="['北京', '上海', '广州', '深圳']"
  :data="[{ name: '销量', value: [1200, 2000, 1500, 800] }]"
  height="300px"
/>
```

## ArtRingChart - 环形图

```vue
<ArtRingChart
  :data="[
    { name: '直接访问', value: 335 },
    { name: '邮件营销', value: 310 },
    { name: '联盟广告', value: 234 }
  ]"
  height="300px"
/>
```

## ArtRadarChart - 雷达图

```vue
<ArtRadarChart
  :indicator="[
    { name: '销量', max: 6500 },
    { name: '用户', max: 16000 },
    { name: '评分', max: 30000 }
  ]"
  :data="[{ name: '预算分配', value: [4300, 10000, 14000] }]"
  height="300px"
/>
```

## ArtScatterChart - 散点图

```vue
<ArtScatterChart
  :data="[
    [10.0, 8.04],
    [8.0, 6.95],
    [13.0, 7.58]
  ]"
  height="300px"
/>
```

## ArtDualBarCompareChart - 双柱对比图

```vue
<ArtDualBarCompareChart
  :x-axis-data="['1月', '2月', '3月', '4月']"
  :data1="[{ name: '今年', value: [120, 200, 150, 180] }]"
  :data2="[{ name: '去年', value: [100, 180, 130, 160] }]"
  height="300px"
/>
```

## 通用 API

### Props

| 属性名  | 说明     | 类型      | 默认值    |
| ------- | -------- | --------- | --------- |
| data    | 图表数据 | `any`     | `-`       |
| height  | 图表高度 | `string`  | `'300px'` |
| loading | 加载状态 | `boolean` | `false`   |

## 典型应用场景

- 数据对比分析
- 分布统计
- 趋势分析
- 地域分布
