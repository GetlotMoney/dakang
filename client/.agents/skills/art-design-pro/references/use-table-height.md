# useTableHeight - 表格高度自动计算 Hook

自动计算表格容器的最佳高度，确保表格在不同布局场景下都能正确显示。

## 基本信息

- **Hook 名称**: useTableHeight
- **文件路径**: `@/hooks/core/useTableHeight.ts`

## 功能特性

1. **动态高度计算** - 根据表格头部、分页器高度自动计算容器高度
2. **响应式更新** - 配置变化时自动重新计算高度
3. **灵活配置** - 支持自定义各部分高度和间距
4. **智能适配** - 无额外元素时自动使用 100% 高度

## 基本用法

```typescript
import { useTableHeight } from '@/hooks/core/useTableHeight'

const { containerHeight } = useTableHeight({
  showTableHeader: showHeader,
  paginationHeight,
  tableHeaderHeight,
  paginationSpacing
})
```

## API

### 参数 (TableHeightOptions)

| 参数              | 说明             | 类型           |
| ----------------- | ---------------- | -------------- |
| showTableHeader   | 是否显示表格头部 | `Ref<boolean>` |
| paginationHeight  | 分页器高度       | `Ref<number>`  |
| tableHeaderHeight | 表格头部高度     | `Ref<number>`  |
| paginationSpacing | 分页器间距       | `Ref<number>`  |

### 返回值

| 属性            | 说明             | 类型                 |
| --------------- | ---------------- | -------------------- |
| containerHeight | 容器高度样式对象 | `{ height: string }` |

## 使用示例

### 基础用法

```vue
<template>
  <div class="table-container" :style="containerHeight">
    <ArtTable :data="data" />
    <div ref="paginationRef" class="pagination">
      <ElPagination ... />
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ref } from 'vue'
  import { useTableHeight } from '@/hooks/core/useTableHeight'

  const showHeader = ref(true)
  const tableHeaderHeight = ref(44)
  const paginationRef = ref()
  const paginationHeight = ref(52)
  const paginationSpacing = ref(12)

  const { containerHeight } = useTableHeight({
    showTableHeader: showHeader,
    paginationHeight,
    tableHeaderHeight,
    paginationSpacing
  })
</script>
```

### 动态高度调整

```vue
<template>
  <div :style="containerHeight">
    <ArtTable v-if="showHeader" :data="data">
      <!-- 表格内容 -->
    </ArtTable>
    <ElPagination v-if="showPagination" ... />
  </div>
</template>

<script setup lang="ts">
  const showHeader = ref(true)
  const showPagination = ref(true)

  const { containerHeight } = useTableHeight({
    showTableHeader: computed(() => showHeader.value),
    paginationHeight: computed(() => (showPagination.value ? 52 : 0)),
    tableHeaderHeight: ref(44),
    paginationSpacing: ref(12)
  })
</script>
```

## 典型应用场景

- 自适应高度表格
- 内容区域自适应
- 嵌套表格布局
- 弹性布局表格
