# ArtBackToTop - 返回顶部组件

提供页面滚动返回顶部的功能组件。

## 基本信息

- **组件名称**: ArtBackToTop
- **组件路径**: `@/components/core/base/art-back-to-top/index.vue`

## 功能特性

1. **滚动检测** - 自动检测滚动位置，显示/隐藏按钮
2. **平滑滚动** - 支持平滑滚动动画效果
3. **自定义位置** - 支持自定义显示阈值和位置
4. **自定义内容** - 支持自定义图标和样式

## 基本用法

```vue
<template>
  <ArtBackToTop />
</template>

<script setup lang="ts">
  import ArtBackToTop from '@/components/core/base/art-back-to-top/index.vue'
</script>
```

## API

### Props

| 属性名            | 说明             | 类型                    | 默认值 |
| ----------------- | ---------------- | ----------------------- | ------ |
| visibility-height | 显示阈值(px)     | `number`                | `300`  |
| right             | 距右边距离       | `number`                | `40`   |
| bottom            | 距底部距离       | `number`                | `40`   |
| duration          | 滚动持续时间(ms) | `number`                | `1000` |
| target            | 目标容器         | `string \| HTMLElement` | `-`    |

## 使用示例

### 基础用法

```vue
<ArtBackToTop />
```

### 自定义位置

```vue
<ArtBackToTop :right="60" :bottom="100" />
```

### 自定义显示阈值

```vue
<!-- 滚动超过 500px 才显示 -->
<ArtBackToTop :visibility-height="500" />
```

### 指定滚动容器

```vue
<ArtBackToTop target="#scroll-container" />
```

### 自定义图标

```vue
<ArtBackToTop>
  <template #default>
    <ArtSvgIcon icon="ri:arrow-up-s-line" />
  </template>
</ArtBackToTop>
```

## 典型应用场景

- 长页面滚动导航
- 列表页面快速返回
- 内容较长的详情页
