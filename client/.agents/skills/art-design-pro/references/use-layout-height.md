# useLayoutHeight - 页面布局高度管理 Hook

自动计算和管理页面内容区域的高度，确保内容区域能够正确填充剩余空间。

## 基本信息

- **Hook 名称**: useLayoutHeight, useAutoLayoutHeight
- **文件路径**: `@/hooks/core/useLayoutHeight.ts`

## 功能特性

1. **动态高度计算** - 根据头部元素高度自动计算内容区域高度
2. **响应式监听** - 自动监听元素尺寸变化并更新高度
3. **CSS 变量同步** - 自动更新 CSS 变量，方便全局使用
4. **灵活配置** - 支持自定义间距、CSS 变量名等
5. **自动查找模式** - 提供通过 ID 自动查找元素的便捷方式

## 基本用法

```typescript
import { useLayoutHeight } from '@/hooks/core/useLayoutHeight'

const { containerMinHeight, headerRef, contentHeaderRef } = useLayoutHeight()
```

## API

### useLayoutHeight

#### 参数 (LayoutHeightOptions)

| 参数         | 说明                  | 类型      | 默认值                |
| ------------ | --------------------- | --------- | --------------------- |
| extraSpacing | 额外的间距(px)        | `number`  | `15`                  |
| updateCssVar | 是否自动更新 CSS 变量 | `boolean` | `true`                |
| cssVarName   | CSS 变量名称          | `string`  | `'--art-full-height'` |

#### 返回值

| 属性                | 说明                   | 类型                  |
| ------------------- | ---------------------- | --------------------- |
| containerMinHeight  | 容器最小高度（响应式） | `ComputedRef<string>` |
| headerRef           | 头部元素引用           | `Ref<HTMLElement>`    |
| contentHeaderRef    | 内容头部元素引用       | `Ref<HTMLElement>`    |
| headerHeight        | 头部高度（响应式）     | `Ref<number>`         |
| contentHeaderHeight | 内容头部高度（响应式） | `Ref<number>`         |

### useAutoLayoutHeight

通过 ID 自动查找元素的布局高度管理，适用于无法直接获取元素引用的场景。

```typescript
import { useAutoLayoutHeight } from '@/hooks/core/useLayoutHeight'

const { containerMinHeight } = useAutoLayoutHeight([
  'app-header', // 头部元素 ID
  'app-content-header' // 内容头部元素 ID
])
```

## 使用示例

### 基础用法

```vue
<template>
  <div class="layout">
    <header ref="headerRef">
      <AppHeader />
    </header>
    <div ref="contentHeaderRef">
      <ContentHeader />
    </div>
    <main :style="{ minHeight: containerMinHeight }">
      <slot />
    </main>
  </div>
</template>

<script setup lang="ts">
  import { useLayoutHeight } from '@/hooks/core/useLayoutHeight'

  const { containerMinHeight, headerRef, contentHeaderRef } = useLayoutHeight()
</script>
```

### 自定义配置

```typescript
const { containerMinHeight } = useLayoutHeight({
  extraSpacing: 20, // 额外间距 20px
  updateCssVar: true, // 更新 CSS 变量
  cssVarName: '--custom-height' // 自定义变量名
})
```

### 自动查找模式

```typescript
import { useAutoLayoutHeight } from '@/hooks/core/useLayoutHeight'

// 自动查找 ID 为 app-header 和 app-content-header 的元素
const { containerMinHeight } = useAutoLayoutHeight(['app-header', 'app-content-header'], {
  extraSpacing: 15,
  cssVarName: '--art-full-height'
})
```

### 在样式中使用

```css
.container {
  min-height: var(--art-full-height);
}

.full-height-card {
  height: var(--art-full-height);
}
```

## 典型应用场景

- 后台管理系统布局
- 表格容器高度自适应
- 全屏内容区域
- 响应式布局高度计算
