# ArtDragVerify - 滑动验证组件

用于验证用户是否为真实人类的滑动验证组件。

## 基本信息

- **组件名称**: ArtDragVerify
- **组件路径**: `@/components/core/forms/art-drag-verify/index.vue`

## 功能特性

1. **滑动验证** - 用户需滑动滑块完成验证
2. **成功回调** - 验证成功时触发回调
3. **重置功能** - 支持重置验证状态
4. **自定义内容** - 支持自定义验证区域内容

## 基本用法

```vue
<template>
  <ArtDragVerify v-model:isPassing="isPassing" @success="handleSuccess" />
</template>

<script setup lang="ts">
  import ArtDragVerify from '@/components/core/forms/art-drag-verify/index.vue'

  const isPassing = ref(false)

  const handleSuccess = () => {
    ElMessage.success('验证成功')
  }
</script>
```

## API

### Props

| 属性名              | 说明         | 类型      | 默认值             |
| ------------------- | ------------ | --------- | ------------------ |
| isPassing (v-model) | 验证是否通过 | `boolean` | `false`            |
| text                | 验证提示文字 | `string`  | `'请按住滑块拖动'` |
| successText         | 成功提示文字 | `string`  | `'验证通过'`       |
| barText             | 滑块文字     | `string`  | `'»'`              |

### Events

| 事件名  | 说明           | 回调参数 |
| ------- | -------------- | -------- |
| success | 验证成功时触发 | `()`     |
| reset   | 重置时触发     | `()`     |

### Methods

| 方法名 | 说明         |
| ------ | ------------ |
| reset  | 重置验证状态 |

## 使用示例

### 基础用法

```vue
<ArtDragVerify v-model:isPassing="isPassing" @success="handleSuccess" />
```

### 自定义文本

```vue
<ArtDragVerify
  v-model:isPassing="isPassing"
  text="拖动滑块完成验证"
  success-text="验证成功！"
  bar-text="→"
/>
```

### 带重置按钮

```vue
<template>
  <div>
    <ArtDragVerify ref="verifyRef" v-model:isPassing="isPassing" />
    <ElButton v-if="isPassing" @click="handleReset">重新验证</ElButton>
  </div>
</template>

<script setup lang="ts">
  const verifyRef = ref()
  const isPassing = ref(false)

  const handleReset = () => {
    verifyRef.value?.reset()
  }
</script>
```

## 典型应用场景

- 登录验证
- 注册验证
- 表单提交验证
- 防止机器操作
