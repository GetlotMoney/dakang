# ArtScreenLock - 屏幕锁组件

提供屏幕锁定功能，防止未授权访问。

## 基本信息

- **组件名称**: ArtScreenLock
- **组件路径**: `@/components/core/layouts/art-screen-lock/index.vue`

## 功能特性

1. **锁屏功能** - 一键锁定屏幕
2. **密码解锁** - 输入密码解锁
3. **用户信息** - 显示当前用户信息
4. **自动锁屏** - 支持无操作自动锁屏

## 基本用法

```vue
<template>
  <ArtScreenLock />
</template>

<script setup lang="ts">
  import ArtScreenLock from '@/components/core/layouts/art-screen-lock/index.vue'
</script>
```

## 触发锁屏

```typescript
import { mittBus } from '@/utils/sys'

// 触发锁屏
mittBus.emit('triggerScreenLock')
```

## 典型应用场景

- 离开时保护
- 隐私保护
- 临时离开锁定
