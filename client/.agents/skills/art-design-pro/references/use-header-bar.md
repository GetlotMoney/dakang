# useHeaderBar - 顶部栏功能管理 Hook

统一管理顶部栏各个功能模块的显示状态和配置信息。

## 基本信息

- **Hook 名称**: useHeaderBar
- **文件路径**: `@/hooks/core/useHeaderBar.ts`

## 功能特性

1. **功能开关控制** - 统一管理菜单按钮、刷新按钮等功能的显示状态
2. **配置信息获取** - 获取各个功能模块的详细配置信息
3. **功能列表查询** - 快速获取所有启用或禁用的功能列表
4. **响应式状态** - 所有状态自动响应配置和 store 变化

## 基本用法

```typescript
import { useHeaderBar } from '@/hooks/core/useHeaderBar'

const { shouldShowMenuButton, shouldShowRefreshButton, shouldShowBreadcrumb, isFeatureEnabled } =
  useHeaderBar()
```

## API

### 显示状态计算属性

| 属性                    | 说明             |
| ----------------------- | ---------------- |
| shouldShowMenuButton    | 是否显示菜单按钮 |
| shouldShowRefreshButton | 是否显示刷新按钮 |
| shouldShowBreadcrumb    | 是否显示面包屑   |
| shouldShowGlobalSearch  | 是否显示全局搜索 |
| shouldShowFullscreen    | 是否显示全屏按钮 |
| shouldShowNotification  | 是否显示通知中心 |
| shouldShowChat          | 是否显示聊天功能 |
| shouldShowLanguage      | 是否显示语言切换 |
| shouldShowSettings      | 是否显示设置面板 |
| shouldShowThemeToggle   | 是否显示主题切换 |

### 方法

| 方法                | 说明                   | 参数        |
| ------------------- | ---------------------- | ----------- |
| isFeatureEnabled    | 检查功能是否启用       | `(feature)` |
| getFeatureConfig    | 获取功能配置信息       | `(feature)` |
| getEnabledFeatures  | 获取所有启用的功能列表 | `()`        |
| getDisabledFeatures | 获取所有禁用的功能列表 | `()`        |

## 使用示例

### 条件渲染

```vue
<template>
  <div class="header">
    <ElButton v-if="shouldShowMenuButton" @click="toggleMenu">
      <ArtSvgIcon icon="ri:menu-line" />
    </ElButton>

    <Breadcrumb v-if="shouldShowBreadcrumb" />

    <div v-if="shouldShowGlobalSearch">
      <GlobalSearch />
    </div>

    <FullscreenButton v-if="shouldShowFullscreen" />
    <ThemeToggle v-if="shouldShowThemeToggle" />
    <SettingsButton v-if="shouldShowSettings" />
  </div>
</template>

<script setup lang="ts">
  import { useHeaderBar } from '@/hooks/core/useHeaderBar'

  const {
    shouldShowMenuButton,
    shouldShowRefreshButton,
    shouldShowBreadcrumb,
    shouldShowGlobalSearch,
    shouldShowFullscreen,
    shouldShowThemeToggle,
    shouldShowSettings
  } = useHeaderBar()
</script>
```

### 检查功能状态

```typescript
const { isFeatureEnabled, getFeatureConfig, getEnabledFeatures } = useHeaderBar()

// 检查特定功能是否启用
if (isFeatureEnabled('refreshButton')) {
  console.log('刷新按钮已启用')
}

// 获取所有启用的功能
const enabledList = getEnabledFeatures()
console.log('启用的功能:', enabledList)
```

## 典型应用场景

- 顶部栏功能动态显示
- 功能模块按需加载
- 响应式顶部栏布局
