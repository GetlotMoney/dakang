# ArtSettingsPanel - 设置面板组件

提供系统设置功能的侧边面板。

## 基本信息

- **组件名称**: ArtSettingsPanel
- **组件路径**: `@/components/core/layouts/art-settings-panel/index.vue`

## 功能特性

1. **主题设置** - 亮色/暗色主题切换
2. **布局设置** - 菜单布局、标签页等设置
3. **颜色配置** - 自定义主题色
4. **其他设置** - 其他系统配置项

## 基本用法

```vue
<template>
  <ArtSettingsPanel v-model:visible="settingsVisible" />
</template>

<script setup lang="ts">
  import ArtSettingsPanel from '@/components/core/layouts/art-settings-panel/index.vue'

  const settingsVisible = ref(false)
</script>
```

## API

### Props

| 属性名            | 说明     | 类型      | 默认值  |
| ----------------- | -------- | --------- | ------- |
| visible (v-model) | 是否显示 | `boolean` | `false` |

## 典型应用场景

- 主题切换
- 布局设置
- 个性化配置
