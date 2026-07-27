# useAuth - 权限验证管理 Hook

提供统一的权限验证功能，支持前端和后端两种权限模式。

## 基本信息

- **Hook 名称**: useAuth
- **文件路径**: `@/hooks/core/useAuth.ts`

## 功能特性

1. **权限检查** - 检查用户是否拥有指定的权限标识
2. **双模式支持** - 自动适配前端模式和后端模式的权限验证
3. **前端模式** - 从用户信息中获取按钮权限列表
4. **后端模式** - 从路由 meta 配置中获取权限列表

## 基本用法

```typescript
import { useAuth } from '@/hooks/core/useAuth'

const { hasAuth } = useAuth()

// 检查是否有新增权限
if (hasAuth('add')) {
  // 显示新增按钮
}
```

## API

### 返回值

| 方法    | 说明               | 参数             | 返回值    |
| ------- | ------------------ | ---------------- | --------- |
| hasAuth | 检查是否有指定权限 | `(auth: string)` | `boolean` |

## 权限模式

### 前端模式 (VITE_ACCESS_MODE=frontend)

权限由前端路由配置控制，适合小型项目或演示环境。

```typescript
// 按钮权限列表
const frontendAuthList = ['add', 'edit', 'delete']

// hasAuth 检查
hasAuth('add') // true
hasAuth('delete') // true
```

### 后端模式 (VITE_ACCESS_MODE=backend)

权限由后端接口返回的菜单数据控制，适合企业级应用。

```typescript
// 路由 meta 配置的权限列表
const backendAuthList = [{ authMark: 'add' }, { authMark: 'edit' }, { authMark: 'delete' }]

// hasAuth 检查
hasAuth('add') // true
hasAuth('delete') // true
```

## 使用示例

### 按钮权限控制

```vue
<template>
  <div>
    <ElButton v-if="hasAuth('add')" type="primary" @click="handleAdd"> 新增 </ElButton>
    <ElButton v-if="hasAuth('edit')" @click="handleEdit"> 编辑 </ElButton>
    <ElButton v-if="hasAuth('delete')" type="danger" @click="handleDelete"> 删除 </ElButton>
  </div>
</template>

<script setup lang="ts">
  import { useAuth } from '@/hooks/core/useAuth'

  const { hasAuth } = useAuth()

  const handleAdd = () => {
    // 新增逻辑
  }

  const handleEdit = () => {
    // 编辑逻辑
  }

  const handleDelete = () => {
    // 删除逻辑
  }
</script>
```

## 典型应用场景

- 页面按钮权限控制
- 表格操作列权限
- 菜单项权限
- API 级别权限验证
