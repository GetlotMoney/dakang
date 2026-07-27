# ArtVideoPlayer - 视频播放器

基于字节跳动的 **西瓜视频播放器（xgplayer）** 实现，支持截图、流式布局、播放速率控制。

## 基本信息

- **组件名称**: ArtVideoPlayer
- **组件路径**: `@/components/core/media/art-video-player/index.vue`
- **使用示例**: `src/views/widgets/video/index.vue`

> 官网: https://hplayer.docmore.cn/

## 功能特性

1. **多种格式支持** - 支持 MP4、HLS 等常见视频格式
2. **自定义封面** - 支持设置视频封面图片
3. **播放控制** - 支持播放、暂停、音量控制、进度拖动等
4. **倍速播放** - 支持自定义播放速率
5. **全屏支持** - 支持全屏播放模式

## API

### Props

| 属性            | 类型               | 必填 | 说明                      |
| --------------- | ------------------ | ---- | ------------------------- |
| `playerId`      | `string`           | 是   | 播放器容器 ID（页面唯一） |
| `videoUrl`      | `string`           | 是   | 视频源 URL                |
| `posterUrl`     | `string`           | —    | 视频封面图 URL            |
| `autoplay`      | `boolean`          | —    | 是否自动播放              |
| `volume`        | `number`           | —    | 音量大小（0-1）           |
| `playbackRates` | `number[]`         | —    | 可选的播放速率列表        |
| `loop`          | `boolean`          | —    | 是否循环播放              |
| `muted`         | `boolean`          | —    | 是否静音                  |
| `commonStyle`   | `VideoPlayerStyle` | —    | 自定义样式配置            |

### VideoPlayerStyle

```ts
interface VideoPlayerStyle {
  progressColor?: string // 进度条背景色
  playedColor?: string // 已播放部分颜色
  cachedColor?: string // 缓存部分颜色
  sliderBtnStyle?: Record<string, string> // 滑块按钮样式
  volumeColor?: string // 音量控制器颜色
}
```

## 基本用法

```vue
<ArtVideoPlayer
  player-id="video-player-1"
  video-url="https://example.com/video.mp4"
  poster-url="https://example.com/cover.jpg"
  :autoplay="false"
  :volume="0.8"
  :playback-rates="[0.5, 1, 1.5, 2]"
/>
```

## 完整示例

```vue
<template>
  <div class="max-w-150">
    <ArtVideoPlayer
      playerId="my-video-1"
      :videoUrl="videoUrl"
      :posterUrl="posterUrl"
      :autoplay="false"
      :volume="1"
      :playbackRates="[0.5, 1, 1.5, 2]"
    />
  </div>
</template>

<script setup lang="ts">
  import lockImg from '@imgs/lock/bg_dark.webp'

  const videoUrl = ref('//example.com/video.mp4')
  const posterUrl = ref(lockImg)
</script>
```

## 生命周期

播放器实例在 `onMounted` 时初始化，在 `onBeforeUnmount` 时自动调用 `destroy()` 释放资源。

## 典型应用场景

- 视频详情页
- 培训视频页
- 视频列表播放
- 后台管理系统视频管理
