# useTheme - 系统主题管理 Hook

提供完整的主题切换和管理功能，支持亮色、暗色和自动模式。

## 基本信息

- **Hook 名称**: useTheme
- **文件路径**: `@/hooks/core/useTheme.ts`

## 功能特性

1. **主题切换** - 支持亮色、暗色、自动三种主题模式
2. **自动模式** - 根据系统偏好自动切换主题
3. **颜色适配** - 自动调整主题色的明暗变体（9 个层级）
4. **过渡优化** - 切换时临时禁用过渡效果，避免闪烁
5. **状态持久化** - 主题设置自动保存到 store

## 基本用法

```typescript
import { useTheme } from '@/hooks/core/useTheme'
import { SystemThemeEnum } from '@/enums/appEnum'

const { switchThemeStyles } = useTheme()

// 切换到暗色主题
switchThemeStyles(SystemThemeEnum.DARK)

// 切换到亮色主题
switchThemeStyles(SystemThemeEnum.LIGHT)

// 切换到自动模式（跟随系统）
switchThemeStyles(SystemThemeEnum.AUTO)
```

## API

### 返回值

| 方法               | 说明                       |
| ------------------ | -------------------------- |
| setSystemTheme     | 设置系统主题               |
| setSystemAutoTheme | 设置自动主题（跟随系统）   |
| switchThemeStyles  | 切换主题样式               |
| prefersDark        | 系统是否偏好暗色（响应式） |

### SystemThemeEnum 枚举

```typescript
enum SystemThemeEnum {
  LIGHT = 'light', // 亮色主题
  DARK = 'dark', // 暗色主题
  AUTO = 'auto' // 自动模式
}
```

## 使用示例

### 主题切换按钮

```vue
<template>
  <ElButton @click="toggleTheme">
    {{ isDark ? '切换到亮色' : '切换到暗色' }}
  </ElButton>
</template>

<script setup lang="ts">
  import { storeToRefs } from 'pinia'
  import { useSettingStore } from '@/store/modules/setting'
  import { useTheme } from '@/hooks/core/useTheme'
  import { SystemThemeEnum } from '@/enums/appEnum'

  const settingStore = useSettingStore()
  const { isDark } = storeToRefs(settingStore)
  const { switchThemeStyles } = useTheme()

  const toggleTheme = () => {
    switchThemeStyles(isDark.value ? SystemThemeEnum.LIGHT : SystemThemeEnum.DARK)
  }
</script>
```

### 初始化主题

```typescript
import { initializeTheme } from '@/hooks/core/useTheme'

// 在应用启动时调用
initializeTheme()
```

## 主题配置

主题配置位于 `src/config/` 目录，包含：

- 亮色主题类名
- 暗色主题类名
- 主题色变量

## 典型应用场景

- 主题切换设置
- 用户偏好保存
- 系统级主题管理
