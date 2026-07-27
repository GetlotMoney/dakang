# ArtImageCard - 图片卡片组件

用于展示图片的卡片组件。

## 基本信息

- **组件名称**: ArtImageCard
- **组件路径**: `@/components/core/cards/art-image-card/index.vue`

## 功能特性

1. **图片展示** - 展示单张图片
2. **悬停效果** - 支持悬停遮罩效果
3. **操作按钮** - 支持自定义操作按钮

## 基本用法

```vue
<template>
  <ArtImageCard src="https://example.com/image.jpg" alt="图片描述" />
</template>

<script setup lang="ts">
  import ArtImageCard from '@/components/core/cards/art-image-card/index.vue'
</script>
```

## API

### Props

| 属性名 | 说明         | 类型                                                       | 默认值    |
| ------ | ------------ | ---------------------------------------------------------- | --------- |
| src    | 图片地址     | `string`                                                   | `''`      |
| alt    | 图片描述     | `string`                                                   | `''`      |
| fit    | 图片填充方式 | `'fill' \| 'contain' \| 'cover' \| 'none' \| 'scale-down'` | `'cover'` |

## 典型应用场景

- 图片展示
- 图片画廊
- 产品展示
