# ArtCutterImg - 图片裁剪

基于 `vue-img-cutter` 封装的图片裁剪组件，支持预览、下载、水印。

## 基本信息

- **组件名称**: ArtCutterImg
- **组件路径**: `@/components/core/media/art-cutter-img/index.vue`
- **使用示例**: `src/views/widgets/image-crop/index.vue`

> 来源: https://github.com/acccccccb/vue-img-cutter

## 功能特性

1. **图片裁剪** - 支持自由裁剪指定尺寸的图片
2. **预览功能** - 支持裁剪前后的预览
3. **水印支持** - 支持添加文字水印
4. **质量控制** - 支持输出图片质量设置
5. **事件回调** - 提供加载、完成、错误等事件

## API

### Props

| 属性                | 类型                        | 默认值      | 说明            |
| ------------------- | --------------------------- | ----------- | --------------- |
| `isModal`           | `boolean`                   | `false`     | 是否模态框模式  |
| `tool`              | `boolean`                   | `true`      | 是否显示工具栏  |
| `toolBgc`           | `string`                    | `'#fff'`    | 工具栏背景色    |
| `title`             | `string`                    | `''`        | 标题            |
| `previewTitle`      | `string`                    | `''`        | 预览标题        |
| `showPreview`       | `boolean`                   | `true`      | 是否显示预览    |
| `boxWidth`          | `number`                    | `700`       | 容器宽度        |
| `boxHeight`         | `number`                    | `458`       | 容器高度        |
| `cutWidth`          | `number`                    | `470`       | 裁剪宽度        |
| `cutHeight`         | `number`                    | `270`       | 裁剪高度        |
| `sizeChange`        | `boolean`                   | `true`      | 允许大小调整    |
| `moveAble`          | `boolean`                   | `true`      | 允许移动        |
| `imgMove`           | `boolean`                   | `true`      | 允许图片移动    |
| `scaleAble`         | `boolean`                   | `true`      | 允许缩放        |
| `originalGraph`     | `boolean`                   | `true`      | 显示原始图片    |
| `crossOrigin`       | `boolean`                   | `true`      | 允许跨域        |
| `fileType`          | `'png' \| 'jpeg' \| 'webp'` | `'png'`     | 输出文件类型    |
| `quality`           | `number`                    | `0.9`       | 图片质量（0-1） |
| `watermarkText`     | `string`                    | `''`        | 水印文本        |
| `watermarkFontSize` | `number`                    | `20`        | 水印字体大小    |
| `watermarkColor`    | `string`                    | `'#ffffff'` | 水印颜色        |
| `saveCutPosition`   | `boolean`                   | `true`      | 保存裁剪位置    |
| `previewMode`       | `boolean`                   | `true`      | 预览模式        |
| `imgUrl` (v-model)  | `string`                    | —           | 初始图片 URL    |

### Emits

| 事件                | 参数              | 说明                          |
| ------------------- | ----------------- | ----------------------------- |
| `update:imgUrl`     | `dataURL: string` | 裁剪完成，返回 Base64 DataURL |
| `error`             | `Error`           | 图片加载失败                  |
| `imageLoadComplete` | `any`             | 图片加载完成                  |
| `imageLoadError`    | `any`             | 图片加载错误                  |

### CutterResult

```ts
interface CutterResult {
  fileName: string // 文件名
  file: File // File 对象
  blob: Blob // Blob 对象
  dataURL: string // Base64 DataURL
}
```

## 基本用法

```vue
<template>
  <ArtCutterImg
    v-model:imgUrl="imageUrl"
    :cutWidth="400"
    :cutHeight="300"
    :showPreview="true"
    watermarkText="Art Design Pro"
  />
</template>

<script setup lang="ts">
  const imageUrl = ref('')
</script>
```

## 完整示例

```vue
<ArtCutterImg
  v-model:imgUrl="imageUrl"
  :boxWidth="530"
  :boxHeight="300"
  :cutWidth="360"
  :cutHeight="200"
  :quality="1"
  :tool="true"
  :watermarkText="'My Watermark'"
  watermarkColor="#ff0000"
  :showPreview="true"
  :originalGraph="false"
  :title="'图片裁剪'"
  :previewTitle="'预览效果'"
  @error="handleError"
  @imageLoadComplete="handleLoadComplete"
  @imageLoadError="handleLoadError"
/>

<script setup>
  const imageUrl = ref('')

  const handleError = (error: Error) => {
    console.error('裁剪错误:', error)
    ElMessage.error('图片裁剪失败')
  }

  const handleLoadComplete = (result) => {
    console.log('图片加载完成:', result)
  }

  const handleLoadError = (error: Error) => {
    console.error('图片加载失败:', error)
    ElMessage.error('图片加载失败')
  }
</script>
```

## 典型应用场景

- 用户头像裁剪
- 商品图片处理
- 证件照片裁剪
- 图片压缩裁剪
