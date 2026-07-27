# ArtTimelineListCard - 时间线卡片组件

用于展示时间线列表的卡片组件。

## 基本信息

- **组件名称**: ArtTimelineListCard
- **组件路径**: `@/components/core/cards/art-timeline-list-card/index.vue`

## 功能特性

1. **时间线展示** - 按时间顺序展示数据
2. **节点样式** - 支持自定义节点样式
3. **时间显示** - 支持显示时间戳

## 基本用法

```vue
<template>
  <ArtTimelineListCard title="活动时间线" :data="timelineData" />
</template>

<script setup lang="ts">
  import ArtTimelineListCard from '@/components/core/cards/art-timeline-list-card/index.vue'

  const timelineData = ref([
    { time: '2024-01-01', title: '活动开始', description: '活动正式上线' },
    { time: '2024-01-15', title: '活动进行中', description: '参与人数突破1000' },
    { time: '2024-01-31', title: '活动结束', description: '活动圆满结束' }
  ])
</script>
```

## API

### Props

| 属性名 | 说明       | 类型             | 默认值 |
| ------ | ---------- | ---------------- | ------ |
| title  | 标题       | `string`         | `''`   |
| data   | 时间线数据 | `TimelineItem[]` | `[]`   |

### TimelineItem 类型

```typescript
interface TimelineItem {
  time: string // 时间
  title: string // 标题
  description?: string // 描述
  color?: string // 节点颜色
}
```

## 典型应用场景

- 活动时间线
- 操作日志
- 流程进度
