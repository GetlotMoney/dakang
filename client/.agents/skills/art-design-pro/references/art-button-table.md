# ArtButtonTable - 表格按钮组件

用于表格操作列的按钮组件集合，支持权限控制和下拉展示。

## 基本信息

- **组件名称**: ArtButtonTable
- **组件路径**: `@/components/core/forms/art-button-table/index.vue`

## 功能特性

1. **按钮组展示** - 水平或下拉展示多个按钮
2. **权限控制** - 支持权限过滤
3. **尺寸适配** - 适配表格行的紧凑布局
4. **下拉折叠** - 按钮过多时收起至下拉菜单

## 基本用法

```vue
<template>
  <ArtButtonTable :actions="actions" @action="handleAction" />
</template>

<script setup lang="ts">
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'

  const actions = [
    { key: 'view', label: '查看', icon: 'ri:eye-line' },
    { key: 'edit', label: '编辑', icon: 'ri:edit-line' },
    { key: 'delete', label: '删除', icon: 'ri:delete-line', type: 'danger' }
  ]

  const handleAction = (key: string, row: any) => {
    console.log('操作:', key, row)
  }
</script>
```

## API

### Props

| 属性名    | 说明         | 类型                  | 默认值   |
| --------- | ------------ | --------------------- | -------- |
| actions   | 按钮配置数组 | `TableAction[]`       | `[]`     |
| more-text | 更多按钮文字 | `string`              | `'更多'` |
| type      | 按钮类型     | `'text' \| 'default'` | `'text'` |

### TableAction 类型

```typescript
interface TableAction {
  key: string           // 唯一标识
  label: string         // 按钮文字
  icon?: string          // 图标
  type?: string         // 按钮类型
  auth?: string         // 权限标识
  disabled?: boolean    // 是否禁用
  show?: boolean | (row: any) => boolean  // 是否显示
}
```

### Events

| 事件名 | 说明           | 回调参数                  |
| ------ | -------------- | ------------------------- |
| action | 点击按钮时触发 | `(key: string, row: any)` |

## 使用示例

### 基础用法

```vue
<ArtButtonTable
  :actions="[
    { key: 'view', label: '查看' },
    { key: 'edit', label: '编辑' },
    { key: 'delete', label: '删除', type: 'danger' }
  ]"
  @action="handleAction"
/>
```

### 带条件显示

```vue
<ArtButtonTable
  :actions="[
    { key: 'view', label: '查看' },
    { key: 'edit', label: '编辑', show: (row) => row.status === 1 },
    { key: 'delete', label: '删除', type: 'danger' }
  ]"
/>
```

### 在表格中使用

```vue
<el-table-column label="操作" width="180">
  <template #default="{ row }">
    <ArtButtonTable
      :actions="getActions(row)"
      @action="(key) => handleAction(key, row)"
    />
  </template>
</el-table-column>

<script setup>
  const getActions = (row) => [
    { key: 'view', label: '查看' },
    { key: 'edit', label: '编辑', show: row.status !== 0 },
    { key: 'delete', label: '删除', type: 'danger', show: row.status === 1 }
  ]
</script>
```

## 典型应用场景

- 表格操作列
- 列表行操作
- 数据管理操作
