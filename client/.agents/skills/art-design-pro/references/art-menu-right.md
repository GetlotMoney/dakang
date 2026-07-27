# ArtMenuRight - 右键菜单

功能完整的上下文菜单组件，支持子菜单、权限控制、边界检测。

## 基本信息

- **组件名称**: ArtMenuRight
- **组件路径**: `@/components/core/others/art-menu-right/index.vue`
- **使用示例**: `src/views/widgets/context-menu/index.vue`

## 功能特性

1. **多级菜单** - 支持无限层级的子菜单
2. **自定义内容** - 支持自定义菜单项图标、分隔线
3. **禁用状态** - 支持禁用特定菜单项
4. **事件回调** - 提供显示、隐藏、选中等事件
5. **灵活配置** - 支持自定义宽度、圆角等样式

## API

### Props

| 属性                | 类型             | 默认值 | 说明                   |
| ------------------- | ---------------- | ------ | ---------------------- |
| `menuItems`         | `MenuItemType[]` | —      | 菜单项列表（必填）     |
| `menuWidth`         | `number`         | `120`  | 菜单宽度（px）         |
| `submenuWidth`      | `number`         | `150`  | 子菜单宽度（px）       |
| `itemHeight`        | `number`         | `32`   | 菜单项高度（px）       |
| `boundaryDistance`  | `number`         | `10`   | 边界距离（px）         |
| `menuPadding`       | `number`         | `5`    | 菜单内边距（px）       |
| `itemPaddingX`      | `number`         | `6`    | 菜单项水平内边距（px） |
| `borderRadius`      | `number`         | `6`    | 圆角（px）             |
| `animationDuration` | `number`         | `100`  | 动画时长（ms）         |

### MenuItemType

```ts
interface MenuItemType {
  key: string // 唯一标识
  label: string // 显示文本
  icon?: string // 图标名称
  disabled?: boolean // 是否禁用
  showLine?: boolean // 是否显示分隔线
  children?: MenuItemType[] // 子菜单
  [key: string]: any // 允许其他属性
}
```

### Emits

| 事件     | 参数                 | 说明       |
| -------- | -------------------- | ---------- |
| `select` | `item: MenuItemType` | 点击菜单项 |
| `show`   | —                    | 菜单显示   |
| `hide`   | —                    | 菜单隐藏   |

### defineExpose

```ts
const menuRef = ref()
menuRef.value.show(e) // 显示菜单（传入 MouseEvent）
menuRef.value.hide() // 隐藏菜单
menuRef.value.visible // 当前可见性（ComputedRef<boolean>）
```

## 基本用法

```vue
<script setup lang="ts">
  import type { MenuItemType } from '@/components/core/others/art-menu-right'

  const menuRef = ref()

  const items: MenuItemType[] = [
    { key: 'copy', label: '复制', icon: 'ri:file-copy-line' },
    { key: 'paste', label: '粘贴', icon: 'ri:clipboard-line' },
    { key: 'divider1', showLine: true },
    { key: 'edit', label: '编辑', icon: 'ri:pencil-line' },
    {
      key: 'more',
      label: '更多操作',
      icon: 'ri:more-2-fill',
      children: [
        { key: 'share', label: '分享', icon: 'ri:share-line' },
        { key: 'download', label: '下载', icon: 'ri:download-line' }
      ]
    }
  ]

  const handleContextMenu = (e: MouseEvent) => {
    e.preventDefault()
    menuRef.value.show(e)
  }

  const handleSelect = (item: MenuItemType) => {
    console.log('选中:', item.key)
  }
</script>

<template>
  <div @contextmenu="handleContextMenu"> 右键点击这里 </div>

  <ArtMenuRight ref="menuRef" :menu-items="items" :menu-width="140" @select="handleSelect" />
</template>
```

## 边界检测

组件自动检测屏幕边界：鼠标在屏幕右侧时菜单自动显示在左侧，底部空间不足时向上展开。

## 典型应用场景

- 表格行右键操作
- 文件管理器右键菜单
- 自定义上下文操作
- 多级功能菜单
