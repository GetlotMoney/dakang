# useCommon - 通用功能集合 Hook

提供常用的页面操作功能，包括首页路径获取、页面刷新、滚动控制等。

## 基本信息

- **Hook 名称**: useCommon
- **文件路径**: `@/hooks/core/useCommon.ts`

## 功能特性

1. **首页路径** - 获取系统配置的首页路径
2. **页面刷新** - 刷新当前页面内容
3. **滚动控制** - 提供多种滚动到顶部和指定位置的方法
4. **平滑滚动** - 支持平滑滚动动画效果

## 基本用法

```typescript
import { useCommon } from '@/hooks/core/useCommon'

const { homePath, refresh, scrollToTop } = useCommon()
```

## API

### 返回值

| 属性/方法         | 说明                   | 类型                                      |
| ----------------- | ---------------------- | ----------------------------------------- |
| homePath          | 首页路径               | `ComputedRef<string>`                     |
| refresh           | 刷新当前页面           | `() => void`                              |
| scrollToTop       | 滚动到页面顶部（立即） | `() => void`                              |
| smoothScrollToTop | 平滑滚动到页面顶部     | `() => void`                              |
| scrollTo          | 滚动到指定位置         | `(top: number, smooth?: boolean) => void` |

## 使用示例

### 获取首页路径

```typescript
import { useRouter } from 'vue-router'
import { useCommon } from '@/hooks/core/useCommon'

const router = useRouter()
const { homePath } = useCommon()

// 跳转到首页
const goHome = () => {
  router.push(homePath.value)
}
```

### 页面刷新

```typescript
const { refresh } = useCommon()

// 刷新按钮点击
const handleRefresh = () => {
  refresh()
}
```

### 滚动控制

```typescript
const { scrollToTop, smoothScrollToTop, scrollTo } = useCommon()

// 立即滚动到顶部
const goTop = () => {
  scrollToTop()
}

// 平滑滚动到顶部
const goTopSmooth = () => {
  smoothScrollToTop()
}

// 滚动到指定位置
const scrollToPosition = (position: number) => {
  scrollTo(position)
}

// 平滑滚动到指定位置
const scrollToPositionSmooth = (position: number) => {
  scrollTo(position, true)
}
```

## 典型应用场景

- 返回首页按钮
- 页面刷新功能
- 回到顶部按钮
- 内容区域滚动控制
