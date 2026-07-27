# ArtStatsCard - 统计卡片组件

用于展示关键业务指标的卡片组件，支持数值动画和趋势展示。

## 基本信息

- **组件名称**: ArtStatsCard
- **组件路径**: `@/components/core/cards/art-stats-card/index.vue`

## 功能特性

1. **数值展示** - 支持大数字展示和格式化（使用 ArtCountTo 组件）
2. **图标支持** - 支持自定义图标和图标样式
3. **颜色区分** - 支持不同状态的颜色展示
4. **描述文本** - 支持显示描述信息

## 基本用法

```vue
<template>
  <ArtStatsCard
    title="总销售额"
    :count="123456"
    description="元"
    icon="ri:money-cny-circle-line"
    icon-style="bg-primary"
  />
</template>

<script setup lang="ts">
  import ArtStatsCard from '@/components/core/cards/art-stats-card/index.vue'
</script>
```

## API

### Props

| 属性名      | 说明             | 类型      | 默认值      |
| ----------- | ---------------- | --------- | ----------- |
| boxStyle    | 盒子额外样式类   | `string`  | `''`        |
| icon        | 图标名称         | `string`  | `''`        |
| iconStyle   | 图标样式类       | `string`  | `''`        |
| title       | 标题             | `string`  | `''`        |
| count       | 数值             | `number`  | `undefined` |
| decimals    | 小数位数         | `number`  | `0`         |
| separator   | 千分位分隔符     | `string`  | `','`       |
| description | 描述文本（必填） | `string`  | `''`        |
| textColor   | 文本颜色         | `string`  | `''`        |
| showArrow   | 是否显示箭头     | `boolean` | `false`     |

## 使用示例

### 基础统计卡片

```vue
<ArtStatsCard
  title="总用户数"
  :count="10000"
  description="人"
  icon="ri:user-line"
  icon-style="bg-primary"
/>
```

### 带小数位数

```vue
<ArtStatsCard
  title="完成率"
  :count="85.5"
  :decimals="1"
  description="%"
  icon="ri:pie-chart-line"
  icon-style="bg-success"
/>
```

### 卡片网格布局

```vue
<ElRow :gutter="20">
  <ElCol :span="6">
    <ArtStatsCard
      title="总销售额"
      :count="123456"
      description="元"
      icon="ri:money-cny-circle-line"
      icon-style="bg-primary"
    />
  </ElCol>
  <ElCol :span="6">
    <ArtStatsCard
      title="订单总数"
      :count="856"
      description="笔"
      icon="ri:shopping-cart-line"
      icon-style="bg-success"
    />
  </ElCol>
  <ElCol :span="6">
    <ArtStatsCard
      title="访问量"
      :count="25890"
      description="次"
      icon="ri:eye-line"
      icon-style="bg-warning"
    />
  </ElCol>
  <ElCol :span="6">
    <ArtStatsCard
      title="转化率"
      :count="68.5"
      :decimals="1"
      description="%"
      icon="ri:pie-chart-line"
      icon-style="bg-info"
    />
  </ElCol>
</ElRow>
```

### 图标样式类参考

组件支持使用 Tailwind CSS 的 bg-color 类来设置图标背景色：

```vue
<!-- 使用 Element Plus 主题色 -->
icon-style="bg-primary"
<!-- 主色 -->
icon-style="bg-success"
<!-- 成功色 -->
icon-style="bg-warning"
<!-- 警告色 -->
icon-style="bg-danger"
<!-- 危险色 -->
icon-style="bg-info"
<!-- 信息色 -->

<!-- 使用自定义颜色 -->
icon-style="bg-[#FF6B6B]"
<!-- 自定义颜色 -->
```

## 典型应用场景

- 仪表盘数据展示
- 业务数据概览
- 数据对比卡片
- 实时数据监控
