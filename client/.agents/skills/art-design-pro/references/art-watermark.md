# ArtWatermark - 水印组件

基于 `ElWatermark` 的全屏水印组件，支持文字水印和图片水印。

## 基本信息

- **组件名称**: ElWatermark (Element Plus 内置)
- **使用示例**: `src/views/widgets/watermark/index.vue`

## 功能特性

1. **文字水印** - 支持单行和多行文字水印
2. **图片水印** - 支持图片作为水印
3. **自定义样式** - 支持字体、颜色、大小等样式配置
4. **旋转角度** - 支持自定义水印旋转角度
5. **间距控制** - 支持水平和垂直间距配置
6. **透明度控制** - 支持自定义水印透明度

## API

### Props

| 属性      | 类型                 | 默认值       | 说明                               |
| --------- | -------------------- | ------------ | ---------------------------------- |
| `content` | `string \| string[]` | `''`         | 水印文字内容                       |
| `image`   | `string`             | `''`         | 水印图片地址（优先级高于 content） |
| `width`   | `number`             | `120`        | 水印宽度                           |
| `height`  | `number`             | `64`         | 水印高度                           |
| `opacity` | `number`             | `0.15`       | 透明度                             |
| `rotate`  | `number`             | `-22`        | 旋转角度（度）                     |
| `font`    | `FontConfig`         | `{}`         | 字体样式                           |
| `gap`     | `[number, number]`   | `[100, 100]` | 水印间距                           |
| `offset`  | `[number, number]`   | `[0, 0]`     | 水印偏移                           |

### FontConfig 类型

```typescript
interface FontConfig {
  color?: string
  fontSize?: number
  fontFamily?: string
  fontWeight?: 'normal' | 'light' | 'weight' | number
  textAlign?: 'start' | 'end' | 'left' | 'right' | 'center'
}
```

## 基本用法

```vue
<!-- 基础文字水印 -->
<ElWatermark content="Art Design Pro">
  <div style="height: 200px"></div>
</ElWatermark>

<!-- 多行文字水印 -->
<ElWatermark :content="['Art Design Pro', '专注用户体验']">
  <div style="height: 200px"></div>
</ElWatermark>
```

## 完整示例

```vue
<!-- 图片水印 -->
<ElWatermark :image="watermarkImage" :opacity="0.2" :width="80" :height="20">
  <div style="height: 200px"></div>
</ElWatermark>

<!-- 自定义样式水印 -->
<ElWatermark
  content="Art Design Pro"
  :font="{
    fontSize: 20,
    fontFamily: 'Arial',
    color: 'rgba(255, 0, 0, 0.3)'
  }"
  :rotate="-22"
  :gap="[100, 100]"
>
  <div style="height: 200px"></div>
</ElWatermark>
```

## 典型应用场景

- 文档水印保护
- 截图溯源
- 版权保护
- 敏感信息提示
