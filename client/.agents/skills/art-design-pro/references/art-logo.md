# ArtLogo - Logo 组件

用于展示系统 Logo 的组件。

## 基本信息

- **组件名称**: ArtLogo
- **组件路径**: `@/components/core/base/art-logo/index.vue`

## 功能特性

1. **Logo 展示** - 支持图片和文字两种形式
2. **点击跳转** - 支持点击跳转至首页
3. **暗色适配** - 支持亮色/暗色主题不同 Logo

## 基本用法

```vue
<template>
  <ArtLogo />
</template>

<script setup lang="ts">
  import ArtLogo from '@/components/core/base/art-logo/index.vue'
</script>
```

## API

### Props

| 属性名     | 说明          | 类型      | 默认值     |
| ---------- | ------------- | --------- | ---------- |
| logo       | Logo 图片地址 | `string`  | `-`        |
| title      | Logo 文字     | `string`  | `系统名称` |
| show-title | 是否显示标题  | `boolean` | `true`     |
| to         | 点击跳转链接  | `string`  | `/`        |

## 典型应用场景

- 系统顶部 Logo
- 登录页 Logo
- 侧边栏 Logo
