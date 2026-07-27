# ArtChatWindow - 聊天窗口组件

提供实时聊天功能界面。

## 基本信息

- **组件名称**: ArtChatWindow
- **组件路径**: `@/components/core/layouts/art-chat-window/index.vue`

## 功能特性

1. **消息展示** - 展示聊天消息列表
2. **消息发送** - 支持发送文本消息
3. **历史记录** - 支持加载历史消息
4. **实时通信** - 支持 WebSocket 实时通信

## 基本用法

```vue
<template>
  <ArtChatWindow v-model:visible="chatVisible" :user-info="currentUser" />
</template>

<script setup lang="ts">
  import ArtChatWindow from '@/components/core/layouts/art-chat-window/index.vue'

  const chatVisible = ref(false)
  const currentUser = ref({ id: '1', name: '张三' })
</script>
```

## 典型应用场景

- 客服聊天
- 在线沟通
- 实时咨询
