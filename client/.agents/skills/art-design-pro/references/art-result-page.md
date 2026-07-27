# ArtResultPage - 结果页面组件

用于展示操作结果的页面组件。

## 基本信息

- **组件名称**: ArtResultPage
- **组件路径**: `@/components/core/views/result/ArtResultPage.vue`

## 功能特性

1. **结果展示** - 展示操作成功或失败的结果
2. **状态图标** - 成功、失败等状态图标
3. **描述信息** - 提供详细的结果描述
4. **操作按钮** - 支持自定义操作按钮

## 基本用法

```vue
<template>
  <ArtResultPage
    status="success"
    title="提交成功"
    description="您的申请已提交成功，我们会尽快处理。"
  >
    <template #extra>
      <ElButton type="primary" @click="handleBack">返回列表</ElButton>
      <ElButton @click="handleContinue">继续操作</ElButton>
    </template>
  </ArtResultPage>
</template>

<script setup lang="ts">
  import ArtResultPage from '@/components/core/views/result/ArtResultPage.vue'

  const handleBack = () => {}
  const handleContinue = () => {}
</script>
```

## API

### Props

| 属性名      | 说明       | 类型                                          | 默认值      |
| ----------- | ---------- | --------------------------------------------- | ----------- |
| status      | 结果状态   | `'success' \| 'error' \| 'warning' \| 'info'` | `'success'` |
| title       | 结果标题   | `string`                                      | `-`         |
| description | 结果描述   | `string`                                      | `-`         |
| icon        | 自定义图标 | `string`                                      | `-`         |

### Slots

| 插槽名 | 说明           |
| ------ | -------------- |
| icon   | 自定义图标区域 |
| extra  | 额外操作区域   |

## 典型应用场景

- 提交成功页面
- 操作失败页面
- 支付结果页面
- 注册结果页面
