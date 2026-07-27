# ArtWangEditor - 富文本编辑器组件

基于 wangEditor 的富文本编辑器组件，提供完整的编辑功能和灵活的配置选项。

## 基本信息

- **组件名称**: ArtWangEditor
- **组件路径**: `@/components/core/forms/art-wang-editor/index.vue`
- **使用示例**: `src/views/widgets/wang-editor/index.vue`

## 功能特性

1. **完整工具栏** - 支持所有编辑功能
2. **简化工具栏** - 支持自定义显示的工具
3. **高度配置** - 支持自定义编辑器高度
4. **占位文本** - 支持自定义 placeholder
5. **方法控制** - 支持清空、聚焦、获取内容等方法

## 工具栏配置

### 完整工具栏包含

- 文本格式：加粗、斜体、下划线、字体颜色、背景色
- 段落格式：标题、引用、对齐方式、缩进
- 列表：有序列表、无序列表、待办事项
- 插入：链接、图片、表格、分割线、表情
- 代码：代码块、行内代码
- 操作：撤销、重做、全屏、清除格式

### 简化工具栏示例

```typescript
const simpleToolbarKeys = [
  'bold',
  'italic',
  'underline',
  '|',
  'bulletedList',
  'numberedList',
  '|',
  'insertLink',
  'insertImage',
  '|',
  'undo',
  'redo'
]
```

## 基本用法

```vue
<template>
  <ArtWangEditor v-model="content" />
</template>

<script setup lang="ts">
  import ArtWangEditor from '@/components/core/forms/art-wang-editor/index.vue'

  const content = ref('<p>初始内容</p>')
</script>
```

## API

### Props

| 属性名               | 说明                         | 类型           | 默认值    |
| -------------------- | ---------------------------- | -------------- | --------- |
| modelValue (v-model) | 编辑器内容（HTML）           | `string`       | `''`      |
| height               | 编辑器高度                   | `string`       | `'300px'` |
| placeholder          | 占位文本                     | `string`       | `''`      |
| toolbarKeys          | 工具栏配置（空数组显示全部） | `string[]`     | `[]`      |
| excludeKeys          | 排除的工具项                 | `string[]`     | `[]`      |
| uploadConfig         | 上传配置                     | `UploadConfig` | `{}`      |

### UploadConfig 类型

```typescript
interface UploadConfig {
  maxFileSize?: number // 最大文件大小（字节）
  maxNumberOfFiles?: number // 最大文件数量
}
```

### Methods

通过 `ref` 获取组件实例后调用：

| 方法名                | 说明           |
| --------------------- | -------------- |
| clear()               | 清空编辑器内容 |
| focus()               | 聚焦编辑器     |
| getHtml()             | 获取 HTML 内容 |
| setHtml(html: string) | 设置 HTML 内容 |

## 使用示例

### 完整工具栏

```vue
<ArtWangEditor
  ref="fullEditorRef"
  v-model="fullEditorHtml"
  height="400px"
  placeholder="请输入内容..."
  :exclude-keys="[]"
/>
```

### 简化工具栏

```vue
<ArtWangEditor
  ref="simpleEditorRef"
  v-model="simpleEditorHtml"
  height="400px"
  placeholder="请输入内容..."
  :toolbar-keys="simpleToolbarKeys"
/>

<script setup>
  const simpleToolbarKeys = [
    'bold',
    'italic',
    'underline',
    '|',
    'bulletedList',
    'numberedList',
    '|',
    'insertLink',
    'insertImage',
    '|',
    'undo',
    'redo'
  ]
</script>
```

### 方法调用

```vue
<template>
  <ArtWangEditor ref="editorRef" v-model="content" />
  <ElButton @click="handleClear">清空</ElButton>
  <ElButton @click="handleFocus">聚焦</ElButton>
  <ElButton @click="handleGetContent">获取内容</ElButton>
</template>

<script setup lang="ts">
  const editorRef = ref()
  const content = ref('')

  const handleClear = () => {
    editorRef.value?.clear()
  }

  const handleFocus = () => {
    editorRef.value?.focus()
  }

  const handleGetContent = () => {
    const html = editorRef.value?.getHtml()
    console.log('编辑器内容:', html)
  }
</script>
```

### 自定义配置

```vue
<ArtWangEditor
  v-model="content"
  height="600px"
  placeholder="请输入您的内容..."
  :toolbar-keys="['bold', 'italic', 'underline', '|', 'insertLink', 'insertImage']"
  :upload-config="{
    maxFileSize: 5 * 1024 * 1024,
    maxNumberOfFiles: 5
  }"
/>
```

## 典型应用场景

- 文章编辑器
- 评论回复
- 简历编辑
- 内容管理系统
