# ArtNotification - 通知面板

顶部通知面板，包含通知/消息/待办三个标签页。

## 基本信息

- **组件名称**: ArtNotification
- **组件路径**: `@/components/core/layouts/art-notification/index.vue`

## 功能特性

1. **通知列表** - 展示系统通知列表
2. **未读标记** - 标识未读消息数量
3. **消息分类** - 支持不同类型的消息分类
4. **标签页切换** - 支持通知/消息/待办三个标签

## API

### Props

| 属性    | 类型      | 说明                |
| ------- | --------- | ------------------- |
| `value` | `boolean` | 是否显示（v-model） |

### Emits

| 事件           | 参数      | 说明         |
| -------------- | --------- | ------------ |
| `update:value` | `boolean` | 显示状态变化 |

### 数据结构

```ts
interface NoticeItem {
  title: string // 标题
  time: string // 时间
  type: 'email' | 'message' | 'collection' | 'user' | 'notice'
}

interface MessageItem {
  title: string // 标题
  time: string // 时间
  avatar: string // 头像 URL
}

interface PendingItem {
  title: string // 标题
  time: string // 时间
}
```

> 通知数据目前为 mock 数据，业务接入时替换 `useNotificationData` 中的 `ref` 值即可。

## 基本用法

```vue
<ArtNotification v-model:visible="notificationVisible" />
```

## 典型应用场景

- 系统通知
- 消息提醒
- 活动提醒
