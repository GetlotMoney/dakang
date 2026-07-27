# ArtCardBanner - 卡片横幅组件

用于展示重要信息或公告的卡片式横幅组件。

## 基本信息

- **组件名称**: ArtCardBanner
- **组件路径**: `@/components/core/banners/art-card-banner/index.vue`

## 功能特性

1. **卡片样式** - 以卡片形式展示信息
2. **操作区域** - 支持操作按钮
3. **自定义内容** - 支持插槽自定义内容

## 基本用法

```vue
<template>
  <ArtCardBanner title="温馨提示" description="请及时更新您的个人信息。">
    <template #action>
      <ElButton size="small" type="primary">立即更新</ElButton>
    </template>
  </ArtCardBanner>
</template>

<script setup lang="ts">
  import ArtCardBanner from '@/components/core/banners/art-card-banner/index.vue'
</script>
```

## API

### Props

| 属性名      | 说明     | 类型     | 默认值 |
| ----------- | -------- | -------- | ------ |
| title       | 标题     | `string` | `''`   |
| description | 描述文字 | `string` | `''`   |

### Slots

| 插槽名 | 说明       |
| ------ | ---------- |
| icon   | 自定义图标 |
| action | 操作区域   |

## 典型应用场景

- 引导操作
- 重要提示
- 行动号召
