# ArtIconButton - 图标按钮组件

用于展示图标形式的操作按钮。

## 基本信息

- **组件名称**: ArtIconButton
- **组件路径**: `@/components/core/widget/art-icon-button/index.vue`

## 功能特性

1. **图标按钮** - 纯图标展示的操作按钮
2. **多种状态** - 支持正常、悬停、禁用等状态
3. **工具提示** - 支持显示工具提示

## 基本用法

```vue
<template>
  <ArtIconButton icon="ri:add-line" @click="handleClick" />
</template>

<script setup lang="ts">
  import ArtIconButton from '@/components/core/widget/art-icon-button/index.vue'

  const handleClick = () => {
    console.log('点击')
  }
</script>
```

## API

### Props

| 属性名   | 说明         | 类型                              | 默认值      |
| -------- | ------------ | --------------------------------- | ----------- |
| icon     | 图标名称     | `string`                          | `必填`      |
| tooltip  | 工具提示文字 | `string`                          | `''`        |
| type     | 按钮类型     | `string`                          | `'default'` |
| size     | 按钮大小     | `'small' \| 'default' \| 'large'` | `'default'` |
| disabled | 是否禁用     | `boolean`                         | `false`     |

## 典型应用场景

- 工具栏按钮
- 表格操作列
- 表单项附加按钮
