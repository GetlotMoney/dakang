# useAppMode - 应用模式管理 Hook

提供应用访问模式的判断和管理功能，支持前端和后端两种权限控制模式。

## 基本信息

- **Hook 名称**: useAppMode
- **文件路径**: `@/hooks/core/useAppMode.ts`

## 功能特性

1. **模式识别** - 自动识别前端模式或后端模式
2. **前端模式** - 权限由前端路由配置控制
3. **后端模式** - 权限由后端接口返回的菜单数据控制
4. **响应式状态** - 提供响应式的模式判断

## 基本用法

```typescript
import { useAppMode } from '@/hooks/core/useAppMode'

const { isFrontendMode, isBackendMode, currentMode } = useAppMode()
```

## API

### 返回值

| 属性           | 说明               | 类型                   |
| -------------- | ------------------ | ---------------------- |
| isFrontendMode | 是否为前端控制模式 | `ComputedRef<boolean>` |
| isBackendMode  | 是否为后端控制模式 | `ComputedRef<boolean>` |
| currentMode    | 当前应用模式       | `ComputedRef<string>`  |

## 模式说明

### 前端模式 (VITE_ACCESS_MODE=frontend)

权限由前端路由配置控制，适合小型项目或演示环境。

### 后端模式 (VITE_ACCESS_MODE=backend)

权限由后端接口返回的菜单数据控制，适合企业级应用。

## 使用示例

### 根据模式做不同处理

```typescript
import { useAppMode } from '@/hooks/core/useAppMode'

const { isFrontendMode, isBackendMode } = useAppMode()

if (isFrontendMode.value) {
  // 前端模式处理
  console.log('当前为前端权限模式')
} else {
  // 后端模式处理
  console.log('当前为后端权限模式')
}
```

### 与 useAuth 配合

```typescript
import { useAppMode } from '@/hooks/core/useAppMode'
import { useAuth } from '@/hooks/core/useAuth'

const { isFrontendMode } = useAppMode()
const { hasAuth } = useAuth()

// 根据模式选择不同的权限检查方式
const checkPermission = (auth: string) => {
  if (isFrontendMode.value) {
    return hasAuth(auth)
  }
  // 后端模式权限检查逻辑
  return true
}
```

## 典型应用场景

- 权限模式判断
- 不同模式的差异化处理
- 权限检查逻辑适配
