# ArtTextScroll - 文字滚动

支持水平和垂直方向的文字滚动，鼠标悬停暂停，无缝循环。

## 基本信息

- **组件名称**: ArtTextScroll
- **组件路径**: `@/components/core/text-effect/art-text-scroll/index.vue`
- **使用示例**: `src/views/widgets/text-scroll/index.vue`

## 功能特性

1. **多方向滚动** - 支持 left、right、up、down 四个方向
2. **多种类型** - 支持 primary、success、warning、danger、info 五种类型
3. **可关闭** - 支持显示关闭按钮
4. **速度控制** - 支持自定义滚动速度
5. **条件滚动** - 可配置仅在文字溢出时滚动

## API

### Props

| 属性           | 类型                                  | 默认值    | 说明                   |
| -------------- | ------------------------------------- | --------- | ---------------------- |
| `text`         | `string`                              | `''`      | 滚动文本内容           |
| `type`         | `ThemeType`                           | `'theme'` | 主题色类型             |
| `direction`    | `'left' \| 'right' \| 'up' \| 'down'` | `'left'`  | 滚动方向               |
| `speed`        | `number`                              | `80`      | 滚动速度（像素/秒）    |
| `width`        | `string`                              | `'100%'`  | 容器宽度               |
| `height`       | `string`                              | `'36px'`  | 容器高度               |
| `pauseOnHover` | `boolean`                             | `true`    | 悬停时暂停             |
| `showClose`    | `boolean`                             | `false`   | 显示关闭按钮           |
| `alwaysScroll` | `boolean`                             | `true`    | 始终滚动（即使未溢出） |

### ThemeType

`'theme' | 'primary' | 'secondary' | 'error' | 'info' | 'success' | 'warning' | 'danger'`

### Events

| 事件    | 参数 | 说明               |
| ------- | ---- | ------------------ |
| `close` | —    | 点击关闭按钮时触发 |

### 插槽

| 插槽名   | 说明                                 |
| -------- | ------------------------------------ |
| 默认插槽 | 覆盖 `text` 属性，直接插入 HTML 内容 |

## 基本用法

```vue
<!-- 基础用法 -->
<ArtTextScroll text="欢迎使用 Art Design Pro 系统" />

<!-- 带关闭按钮 -->
<ArtTextScroll
  text="公告：明天上午9点进行系统维护"
  direction="left"
  :speed="60"
  show-close
  type="warning"
  @close="onClose"
/>

<!-- 自定义插槽内容 -->
<ArtTextScroll>
  <a href="/notice/1">重要通知：系统升级公告</a>
  <span class="mx-4">|</span>
  <a href="/notice/2">新功能上线</a>
</ArtTextScroll>
```

## 完整示例

```vue
<template>
  <div class="space-y-5">
    <ArtTextScroll text="Art Design Pro 是一款兼具设计美学与高效开发的后台系统" showClose />

    <ArtTextScroll type="success" text="这是一条成功类型的滚动公告" />
    <ArtTextScroll type="warning" text="这是一条警告类型的滚动公告" />
    <ArtTextScroll type="danger" text="这是一条危险类型的滚动公告" />
    <ArtTextScroll type="info" text="这是一条信息类型的滚动公告" />

    <!-- 向右滚动，速度较慢 -->
    <ArtTextScroll
      type="warning"
      text="这是一条速度较慢、向右滚动的公告"
      :speed="30"
      direction="right"
    />

    <!-- 向上滚动 -->
    <ArtTextScroll type="danger" direction="up" :speed="30" text="这是一条向上滚动的公告" />
  </div>
</template>
```

## 文字滚动原理

`ArtTextScroll` 使用 `useRafFn`（基于 `requestAnimationFrame`）实现流畅动画：

- 文本溢出时自动克隆内容实现无缝循环
- 支持触摸屏滑动检测（`useElementHover` 检测悬停）
- 方向键支持：从左/右/上/下四个方向滚动

## 典型应用场景

- 系统公告通知
- 活动信息展示
- 新闻滚动播报
- 重要提示信息
