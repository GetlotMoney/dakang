# ArtSvgIcon - SVG 图标组件

基于 Iconify 的 SVG 图标组件，支持多种图标库。

## 基本信息

- **组件名称**: ArtSvgIcon
- **组件路径**: `@/components/core/base/art-svg-icon/index.vue`
- **使用示例**: `src/views/widgets/icon/index.vue`

## 功能特性

1. **多图标库支持** - 支持 Remix Icon、Solar、Tabler Icons 等多种图标库
2. **动态颜色** - 支持通过 CSS 类动态设置颜色
3. **大小控制** - 支持通过 CSS 类设置大小
4. **按需加载** - 图标按需加载，优化性能

## 图标库说明

项目全部采用 **Remix Icon** 图标库，可在以下网址搜索：

- [Iconify](https://icones.js.org/collection/ri)
- [Remix Icon 官网](https://remixicon.com/)

## 基本用法

```vue
<template>
  <!-- 基础用法 -->
  <ArtSvgIcon icon="ri:home-line" />

  <!-- 自定义大小 -->
  <ArtSvgIcon icon="ri:user-line" class="text-2xl" />

  <!-- 自定义颜色 -->
  <ArtSvgIcon icon="ri:heart-fill" class="text-red-500" />
</template>

<script setup lang="ts">
  import ArtSvgIcon from '@/components/core/base/art-svg-icon/index.vue'
</script>
```

## API

### Props

| 属性名 | 说明                        | 类型     | 默认值 |
| ------ | --------------------------- | -------- | ------ |
| icon   | 图标名称（格式：库:图标名） | `string` | `必填` |

### 图标名称格式

```
库名:图标名
例如：ri:home-line（Remix Icon 的 home-line 图标）
```

## 常用图标库前缀

| 前缀            | 图标库              | 说明              |
| --------------- | ------------------- | ----------------- |
| `ri:`           | Remix Icon          | 项目默认使用      |
| `svg-spinners:` | SVG Spinners        | 加载动画          |
| `line-md:`      | Material Line Icons | Material 设计风格 |
| `tabler:`       | Tabler Icons        | 线性风格图标      |

## 使用示例

### Remix Icon 示例

```vue
<div class="flex items-center gap-6">
  <ArtSvgIcon icon="ri:github-fill" class="text-2xl" />
  <ArtSvgIcon icon="ri:copilot-line" class="text-2xl text-theme" />
  <ArtSvgIcon icon="ri:edge-line" class="text-2xl text-secondary" />
  <ArtSvgIcon icon="ri:planet-line" class="text-2xl text-warning" />
  <ArtSvgIcon icon="ri:windows-line" class="text-2xl text-info" />
  <ArtSvgIcon icon="ri:thumb-up-line" class="text-2xl text-danger" />
  <ArtSvgIcon icon="ri:gift-2-line" class="text-2xl text-success" />
  <ArtSvgIcon icon="ri:apple-line" class="text-2xl text-secondary" />
</div>
```

### SVG Spinners 加载动画

```vue
<div class="flex items-center gap-6">
  <ArtSvgIcon icon="svg-spinners:3-dots-fade" class="text-2xl text-red-400" />
  <ArtSvgIcon icon="svg-spinners:3-dots-bounce" class="text-2xl text-blue-400" />
  <ArtSvgIcon icon="svg-spinners:3-dots-move" class="text-2xl text-orange-400" />
  <ArtSvgIcon icon="svg-spinners:3-dots-rotate" class="text-2xl text-purple-400" />
  <ArtSvgIcon icon="svg-spinners:clock" class="text-2xl text-yellow-500" />
</div>
```

### Material Line Icons

```vue
<div class="flex items-center gap-6">
  <ArtSvgIcon icon="line-md:phone-call-twotone-loop" class="text-2xl text-blue-500" />
  <ArtSvgIcon icon="line-md:switch-off" class="text-2xl text-green-500" />
  <ArtSvgIcon icon="line-md:sun-rising-filled-loop" class="text-2xl text-yellow-400" />
  <ArtSvgIcon icon="line-md:github-twotone" class="text-2xl text-gray-700" />
  <ArtSvgIcon icon="line-md:telegram" class="text-2xl text-sky-500" />
</div>
```

### 组合使用

```vue
<!-- 图标 + 文字 -->
<div class="flex items-center gap-2">
  <ArtSvgIcon icon="ri:user-line" />
  <span>用户中心</span>
</div>

<!-- 大图标 -->
<ArtSvgIcon icon="ri:star-fill" class="text-4xl text-yellow-500" />

<!-- 主题色图标 -->
<ArtSvgIcon icon="ri:heart-line" class="text-xl text-theme" />
```

## 查找图标

1. 访问 [Iconify](https://icones.js.org/)
2. 选择需要的图标库（如 Remix Icon）
3. 搜索图标名称
4. 复制图标名称（格式：库:图标名）

## 典型应用场景

- 导航菜单图标
- 按钮图标
- 表单图标
- 状态指示图标
