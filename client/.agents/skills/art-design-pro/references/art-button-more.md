# ArtButtonMore - 按钮组组件

用于展示多个操作按钮的组件，支持下拉菜单和权限控制。

## 基本信息

- **组件名称**: ArtButtonMore
- **组件路径**: `@/components/core/forms/art-button-more/index.vue`

## 功能特性

1. **按钮下拉** - 将多个按钮收起到下拉菜单
2. **权限过滤** - 支持根据权限显示/隐藏按钮
3. **自定义按钮** - 支持自定义按钮类型和样式
4. **事件转发** - 自动转发按钮点击事件

## 基本用法

```vue
<template>
  <ArtButtonMore :buttons="buttonList" @click="handleButtonClick" />
</template>

<script setup lang="ts">
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'

  const buttonList = [
    { key: 'add', label: '新增', type: 'primary' },
    { key: 'edit', label: '编辑' },
    { key: 'delete', label: '删除', type: 'danger' }
  ]

  const handleButtonClick = (key: string) => {
    console.log('点击了:', key)
  }
</script>
```

## API

### Props

| 属性名       | 说明                 | 类型             | 默认值 |
| ------------ | -------------------- | ---------------- | ------ |
| buttons      | 按钮配置数组         | `ButtonConfig[]` | `[]`   |
| collapse     | 是否折叠按钮         | `boolean`        | `true` |
| collapse-min | 折叠阈值（按钮数量） | `number`         | `3`    |

### ButtonConfig 类型

```typescript
interface ButtonConfig {
  key: string // 唯一标识
  label: string // 按钮文字
  type?: string // 按钮类型 primary/success/warning/danger/info
  icon?: string // 图标
  auth?: string // 权限标识
  disabled?: boolean // 是否禁用
  [key: string]: any // 其他 el-button 属性
}
```

### Events

| 事件名 | 说明           | 回调参数        |
| ------ | -------------- | --------------- |
| click  | 点击按钮时触发 | `(key: string)` |

## 使用示例

### 基础用法

```vue
<ArtButtonMore
  :buttons="[
    { key: 'add', label: '新增', type: 'primary' },
    { key: 'edit', label: '编辑' },
    { key: 'delete', label: '删除', type: 'danger' }
  ]"
/>
```

### 带图标

```vue
<ArtButtonMore
  :buttons="[
    { key: 'add', label: '新增', icon: 'ri:add-line', type: 'primary' },
    { key: 'export', label: '导出', icon: 'ri:download-line' },
    { key: 'delete', label: '删除', icon: 'ri:delete-line', type: 'danger' }
  ]"
/>
```

### 带权限控制

```vue
<ArtButtonMore
  :buttons="[
    { key: 'add', label: '新增', type: 'primary', auth: 'user:add' },
    { key: 'edit', label: '编辑', auth: 'user:edit' },
    { key: 'delete', label: '删除', type: 'danger', auth: 'user:delete' }
  ]"
/>
```

## 典型应用场景

- 表格操作列
- 列表页批量操作
- 详情页工具栏
