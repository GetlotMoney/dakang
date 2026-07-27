# ArtProgressCard - 进度卡片组件

用于展示进度信息的卡片组件。

## 基本信息

- **组件名称**: ArtProgressCard
- **组件路径**: `@/components/core/cards/art-progress-card/index.vue`

## 功能特性

1. **进度展示** - 环形或线性进度展示
2. **自定义颜色** - 支持进度条颜色
3. **数值显示** - 支持显示当前值和目标值

## 基本用法

```vue
<template>
  <ArtProgressCard title="完成率" :value="75" suffix="%" type="circle" />
</template>

<script setup lang="ts">
  import ArtProgressCard from '@/components/core/cards/art-progress-card/index.vue'
</script>
```

## API

### Props

| 属性名 | 说明       | 类型                 | 默认值   |
| ------ | ---------- | -------------------- | -------- |
| title  | 标题       | `string`             | `''`     |
| value  | 当前值     | `number`             | `0`      |
| total  | 目标值     | `number`             | `100`    |
| suffix | 后缀       | `string`             | `''`     |
| type   | 类型       | `'line' \| 'circle'` | `'line'` |
| color  | 进度条颜色 | `string`             | `''`     |

## 典型应用场景

- 任务完成度
- 销售目标
- 转化率展示
