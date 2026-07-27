# ArtBasicBanner - 基础横幅组件

用于展示重要信息或公告的横幅组件。

## 基本信息

- **组件名称**: ArtBasicBanner
- **组件路径**: `@/components/core/banners/art-basic-banner/index.vue`

## 功能特性

1. **信息展示** - 展示重要提示或公告
2. **可关闭** - 支持关闭横幅
3. **多种类型** - 支持不同类型（info/success/warning/error）

## 基本用法

```vue
<template>
  <ArtBasicBanner
    title="系统公告"
    description="系统将于今晚22:00进行升级维护。"
    type="warning"
    @close="handleClose"
  />
</template>

<script setup lang="ts">
  import ArtBasicBanner from '@/components/core/banners/art-basic-banner/index.vue'

  const handleClose = () => {
    console.log('横幅已关闭')
  }
</script>
```

## API

### Props

| 属性名      | 说明       | 类型                                          | 默认值   |
| ----------- | ---------- | --------------------------------------------- | -------- |
| title       | 标题       | `string`                                      | `''`     |
| description | 描述文字   | `string`                                      | `''`     |
| type        | 类型       | `'info' \| 'success' \| 'warning' \| 'error'` | `'info'` |
| closable    | 是否可关闭 | `boolean`                                     | `true`   |

### Events

| 事件名 | 说明               | 回调参数 |
| ------ | ------------------ | -------- |
| close  | 点击关闭按钮时触发 | `()`     |

## 典型应用场景

- 系统公告
- 维护通知
- 重要提醒
