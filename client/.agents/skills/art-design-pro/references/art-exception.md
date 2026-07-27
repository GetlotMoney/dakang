# ArtException - 异常页面组件

用于展示系统异常状态的页面组件。

## 基本信息

- **组件名称**: ArtException
- **组件路径**: `@/components/core/views/exception/ArtException.vue`

## 功能特性

1. **异常类型** - 支持 403、404、500 等异常状态
2. **友好提示** - 提供友好的错误提示信息
3. **操作入口** - 提供返回首页等操作

## 基本用法

```vue
<template>
  <ArtException type="404" @back="handleBack" />
</template>

<script setup lang="ts">
  import ArtException from '@/components/core/views/exception/ArtException.vue'
  import { useRouter } from 'vue-router'

  const router = useRouter()

  const handleBack = () => {
    router.push('/')
  }
</script>
```

## API

### Props

| 属性名      | 说明     | 类型                                 | 默认值  |
| ----------- | -------- | ------------------------------------ | ------- |
| type        | 异常类型 | `'403' \| '404' \| '500' \| 'error'` | `'404'` |
| title       | 异常标题 | `string`                             | `-`     |
| description | 异常描述 | `string`                             | `-`     |

### Events

| 事件名 | 说明               | 回调参数 |
| ------ | ------------------ | -------- |
| back   | 点击返回按钮时触发 | `()`     |

## 典型应用场景

- 404 页面
- 403 无权限页面
- 500 服务器错误页面
- 自定义错误页面
