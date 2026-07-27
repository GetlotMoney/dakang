# ArtTable - 增强型表格组件

基于 Element Plus Table 的增强型表格组件，提供完整的数据展示、列配置和插槽支持功能。

## 基本信息

- **组件名称**: ArtTable
- **组件路径**: `@/components/core/tables/art-table/index.vue`
- **类型定义**: `src/types/component/index.ts`
- **配套 Hook**: useTable、useTableColumns

## 功能特性

1. **数据绑定** - 支持 data 属性绑定数据源
2. **列配置** - 支持 ColumnOption 配置和 ElTableColumn 所有属性
3. **分页集成** - 内置分页事件处理
4. **列动态配置** - 支持运行时动态显示/隐藏、排序、新增、删除列
5. **插槽支持** - 支持自定义列渲染和表头插槽
6. **h 函数渲染** - 支持通过 formatter 返回 h 函数渲染复杂内容
7. **事件转发** - 自动转发 Element Plus Table 的所有事件

## 基本用法

```vue
<template>
  <ArtTable
    rowKey="id"
    :loading="loading"
    :data="data"
    :columns="columns"
    :pagination="pagination"
    @pagination:size-change="handleSizeChange"
    @pagination:current-change="handleCurrentChange"
  />
</template>

<script setup lang="ts">
  import { useTable } from '@/hooks/core/useTable'

  const { data, columns, loading, pagination, handleSizeChange, handleCurrentChange } = useTable({
    core: {
      apiFn: fetchUserList,
      columnsFactory: () => [
        { type: 'index', label: '序号', width: 60 },
        { prop: 'name', label: '姓名' },
        { prop: 'age', label: '年龄', sortable: true },
        { prop: 'status', label: '状态' }
      ]
    }
  })
</script>
```

## 完整 Props

ArtTable 继承自 Element Plus 的 el-table，**支持 el-table 的所有 Props**。以下是常用 Props：

| 属性名                | 说明                     | 类型               | 默认值  |
| --------------------- | ------------------------ | ------------------ | ------- |
| data                  | 表格数据                 | `any[]`            | `[]`    |
| columns               | 列配置（ColumnOption[]） | `ColumnOption[]`   | `[]`    |
| loading               | 加载状态                 | `boolean`          | `false` |
| pagination            | 分页配置                 | `PaginationConfig` | `-`     |
| rowKey                | 行数据唯一标识           | `string`           | `'id'`  |
| height                | 表格高度                 | `string \| number` | `-`     |
| max-height            | 表格最大高度             | `string \| number` | `-`     |
| empty-height          | 空状态区域高度           | `string`           | `300px` |
| show-table-header     | 是否显示表头             | `boolean`          | `true`  |
| border                | 是否显示边框             | `boolean`          | `true`  |
| stripe                | 是否显示斑马纹           | `boolean`          | `false` |
| highlight-current-row | 高亮当前行               | `boolean`          | `false` |

## Events

支持 Element Plus Table 的所有事件，以下是常用事件：

| 事件名 | 说明 | 回调参数 |
| --- | --- | --- |
| selection-change | 选择项变化时触发 | `(selection: any[])` |
| row-click | 行点击时触发 | `(row: any, column: any, event: Event)` |
| row-dblclick | 行双击时触发 | `(row: any, column: any, event: Event)` |
| cell-click | 单元格点击时触发 | `(row: any, column: any, cell: any, event: Event)` |
| header-click | 表头点击时触发 | `(column: any, event: Event)` |
| sort-change | 排序变化时触发 | `(sortInfo: { prop: string; order: string })` |
| pagination:size-change | 分页大小变化 | `(size: number)` |
| pagination:current-change | 分页页码变化 | `(current: number)` |

## ColumnOption 完整类型定义

```typescript
// 表格列配置接口
interface ColumnOption<T = any> {
  // ========== 基础属性 ==========
  /** 列类型：selection | expand | index | globalIndex */
  type?: 'selection' | 'expand' | 'index' | 'globalIndex'
  /** 列属性名 */
  prop?: string
  /** 列标题 */
  label?: string
  /** 列宽度 */
  width?: string | number
  /** 最小列宽度 */
  minWidth?: string | number
  /** 固定列位置 */
  fixed?: boolean | 'left' | 'right'

  // ========== 排序和过滤 ==========
  /** 是否可排序 */
  sortable?: boolean | 'custom'
  /** 过滤器选项 */
  filters?: any[]
  /** 过滤方法 */
  filterMethod?: (value: any, row: any) => boolean
  /** 过滤器位置 */
  filterPlacement?: string

  // ========== 显示控制 ==========
  /** 是否禁用 */
  disabled?: boolean
  /** 是否显示列（运行时控制） */
  visible?: boolean
  /** 是否选中显示（用于动态列配置） */
  checked?: boolean

  // ========== 自定义渲染 ==========
  /** 格式化函数，支持返回 VNode */
  formatter?: (row: T) => any
  /** 是否使用插槽渲染内容 */
  useSlot?: boolean
  /** 插槽名称（默认为 prop 值） */
  slotName?: string
  /** 是否使用表头插槽 */
  useHeaderSlot?: boolean
  /** 表头插槽名称（默认为 `${prop}-header`） */
  headerSlotName?: string

  // ========== 其他属性 ==========
  /** 其他 el-table-column 支持的属性 */
  [key: string]: any
}
```

## 使用示例

### 1. 基础表格

```vue
<ArtTable :data="tableData" border stripe>
  <ElTableColumn type="index" label="序号" width="60" />
  <ElTableColumn prop="name" label="姓名" />
  <ElTableColumn prop="age" label="年龄" sortable />
  <ElTableColumn prop="email" label="邮箱" />
</ArtTable>

<script setup lang="ts">
  const tableData = ref([
    { id: 1, name: '张三', age: 25, email: 'zhangsan@example.com' },
    { id: 2, name: '李四', age: 30, email: 'lisi@example.com' }
  ])
</script>
```

### 2. 带分页表格

```vue
<ArtTable
  :data="data"
  :columns="columns"
  :pagination="pagination"
  @pagination:size-change="handleSizeChange"
  @pagination:current-change="handleCurrentChange"
/>

<script setup lang="ts">
  const pagination = reactive({
    current: 1,
    size: 20,
    total: 100
  })

  const handleSizeChange = (size: number) => {
    pagination.size = size
    fetchData()
  }

  const handleCurrentChange = (current: number) => {
    pagination.current = current
    fetchData()
  }
</script>
```

### 3. 带选择框表格

```vue
<ArtTable :data="data" :columns="columns" @selection-change="handleSelectionChange">
  <template #columns>
    { type: 'selection', width: 50 },
    { type: 'index', label: '序号', width: 60 },
    { prop: 'name', label: '姓名' }
  </template>
</ArtTable>

<script setup lang="ts">
  const selectedRows = ref([])

  const handleSelectionChange = (selection: any[]) => {
    selectedRows.value = selection
  }
</script>
```

### 4. 插槽渲染自定义列

```vue
<ArtTable :data="data" :columns="columns">
  <!-- 用户信息列 - 自定义渲染 -->
  <template #avatar="{ row }">
    <div class="flex gap-3 items-center">
      <ElAvatar :src="row.avatar" :size="40" />
      <div>
        <p class="font-medium">{{ row.userName }}</p>
        <p class="text-xs text-g-500">{{ row.userEmail }}</p>
      </div>
    </div>
  </template>

  <!-- 状态列 - Tag 渲染 -->
  <template #status="{ row }">
    <ElTag :type="getStatusType(row.status)" effect="light">
      {{ getStatusText(row.status) }}
    </ElTag>
  </template>

  <!-- 评分列 - Rate 渲染 -->
  <template #score="{ row }">
    <ElRate v-model="row.score" disabled size="small" />
  </template>

  <!-- 操作列 - 按钮组 -->
  <template #operation="{ row }">
    <ElButton size="small" @click="handleView(row)">查看</ElButton>
    <ElButton size="small" type="primary" @click="handleEdit(row)">编辑</ElButton>
    <ElButton size="small" type="danger" @click="handleDelete(row)">删除</ElButton>
  </template>

  <!-- 自定义表头 -->
  <template #avatar-header="{ column }">
    <span>{{ column.label }} <ElTag size="small">VIP</ElTag></span>
  </template>
</ArtTable>

<script setup lang="ts">
  const columns = [
    { prop: 'avatar', label: '用户', useSlot: true, useHeaderSlot: true },
    { prop: 'status', label: '状态', useSlot: true },
    { prop: 'score', label: '评分', useSlot: true },
    { prop: 'operation', label: '操作', width: 200, useSlot: true }
  ]
</script>
```

### 5. h 函数渲染复杂列

```vue
<script setup lang="ts">
  import { h } from 'vue'

  const columns = [
    { type: 'index', label: '序号', width: 60 },
    { prop: 'name', label: '姓名' },
    {
      prop: 'gender',
      label: '性别',
      formatter: (row) =>
        h(
          'span',
          {
            style: { color: row.gender === '男' ? '#409eff' : '#f56c6c' }
          },
          row.gender === '男' ? '👨 男' : '👩 女'
        )
    },
    {
      prop: 'tags',
      label: '标签',
      formatter: (row) =>
        h(
          'div',
          { class: 'flex gap-1' },
          row.tags.map((tag) => h(ElTag, { size: 'small', type: 'info' }, () => tag))
        )
    },
    {
      prop: 'actions',
      label: '操作',
      width: 180,
      formatter: (row) =>
        h('div', { class: 'flex gap-2' }, [
          h(ElButton, { size: 'small', onClick: () => handleEdit(row) }, () => '编辑'),
          h(
            ElButton,
            { size: 'small', type: 'danger', onClick: () => handleDelete(row) },
            () => '删除'
          )
        ])
    }
  ]
</script>
```

### 6. 动态列配置

```vue
<script setup lang="ts">
  const {
    columns,
    columnChecks,
    addColumn,
    removeColumn,
    toggleColumn,
    updateColumn,
    resetColumns,
    reorderColumns
  } = useTable({
    core: {
      apiFn: fetchUserList,
      columnsFactory: () => [
        { type: 'selection', width: 50 },
        { type: 'globalIndex', label: '序号', width: 60 },
        { prop: 'name', label: '姓名', visible: true },
        { prop: 'phone', label: '手机号', visible: true },
        { prop: 'email', label: '邮箱', visible: true },
        { prop: 'status', label: '状态', visible: false },
        { prop: 'operation', label: '操作', width: 150, useSlot: true }
      ]
    }
  })

  // 新增列
  const handleAddColumn = () => {
    addColumn({ prop: 'remark', label: '备注', width: 120, formatter: () => '无' })
  }

  // 切换列显示
  const handleToggleColumn = (prop: string) => {
    toggleColumn(prop)
  }

  // 更新列
  const handleUpdateColumn = () => {
    updateColumn('phone', { label: '联系电话', width: 140 })
  }

  // 重置列
  const handleResetColumns = () => {
    resetColumns()
  }
</script>
```

## 完整页面应用示例

### 用户管理页面

```vue
<template>
  <div class="user-page">
    <!-- 搜索区域 -->
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      @search="handleSearch"
      @reset="handleReset"
    />

    <!-- 表格区域 -->
    <ElCard class="art-table-card">
      <!-- 表格头部工具栏 -->
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElButton type="primary" @click="handleAdd"> <Plus />新增用户 </ElButton>
            <ArtExcelExport :data="data" filename="用户数据" />
            <ArtExcelImport @import-success="handleImportSuccess" />
          </ElSpace>
        </template>
      </ArtTableHeader>

      <!-- 表格 -->
      <ArtTable
        ref="tableRef"
        rowKey="id"
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @selection-change="handleSelectionChange"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <!-- 用户信息插槽 -->
        <template #avatar="{ row }">
          <div class="flex gap-3 items-center">
            <ElAvatar :src="row.avatar" :size="40" />
            <div>
              <p class="font-medium">{{ row.userName }}</p>
              <p class="text-xs text-g-500">{{ row.userEmail }}</p>
            </div>
          </div>
        </template>

        <!-- 状态插槽 -->
        <template #status="{ row }">
          <ElTag :type="getStatusType(row.status)">
            {{ getStatusText(row.status) }}
          </ElTag>
        </template>

        <!-- 评分插槽 -->
        <template #score="{ row }">
          <ElRate v-model="row.score" disabled />
        </template>

        <!-- 操作插槽 -->
        <template #operation="{ row }">
          <div class="flex gap-2">
            <ArtButtonTable type="view" :row="row" @click="handleView" />
            <ArtButtonTable type="edit" :row="row" @click="handleEdit" />
            <ArtButtonTable type="delete" :row="row" @click="handleDelete" />
          </div>
        </template>
      </ArtTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { Plus } from '@element-plus/icons-vue'
  import { useTable } from '@/hooks/core/useTable'

  defineOptions({ name: 'UserManagement' })

  // 状态配置
  const USER_STATUS = {
    '1': { type: 'success' as const, text: '在线' },
    '2': { type: 'info' as const, text: '离线' },
    '3': { type: 'warning' as const, text: '异常' }
  }

  const getStatusType = (status: string) => USER_STATUS[status]?.type || 'info'
  const getStatusText = (status: string) => USER_STATUS[status]?.text || '未知'

  // 搜索表单
  const searchForm = reactive({
    userName: '',
    userPhone: '',
    status: ''
  })

  // 搜索配置
  const searchItems = [
    { key: 'userName', label: '用户名', type: 'input' },
    { key: 'userPhone', label: '手机号', type: 'input' },
    {
      key: 'status',
      label: '状态',
      type: 'select',
      options: [
        { label: '全部', value: '' },
        { label: '在线', value: '1' },
        { label: '离线', value: '2' },
        { label: '异常', value: '3' }
      ]
    }
  ]

  // 表格配置
  const {
    data,
    columns,
    columnChecks,
    loading,
    pagination,
    getData,
    refreshData,
    handleSizeChange,
    handleCurrentChange
  } = useTable({
    core: {
      apiFn: fetchGetUserList,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { type: 'selection', width: 50 },
        { type: 'globalIndex', label: '序号', width: 60 },
        { prop: 'avatar', label: '用户信息', minWidth: 200, useSlot: true },
        { prop: 'userGender', label: '性别', width: 80, formatter: (r) => r.userGender || '未知' },
        { prop: 'userPhone', label: '手机号', width: 130 },
        { prop: 'score', label: '评分', width: 140, useSlot: true },
        { prop: 'status', label: '状态', width: 100, useSlot: true },
        { prop: 'operation', label: '操作', width: 160, fixed: 'right', useSlot: true }
      ]
    },
    performance: { enableCache: true, cacheTime: 5 * 60 * 1000 }
  })

  // 操作处理
  const handleSearch = () => {
    getData({ ...searchForm })
  }

  const handleReset = () => {
    searchForm.userName = ''
    searchForm.userPhone = ''
    searchForm.status = ''
    getData()
  }

  const handleAdd = () => {
    // 新增逻辑
    refreshData()
  }

  const handleEdit = (row: any) => {
    // 编辑逻辑
    refreshData()
  }

  const handleDelete = async (row: any) => {
    try {
      await ElMessageBox.confirm(`确定删除用户 ${row.userName}？`)
      // 删除逻辑
      refreshData()
    } catch {}
  }

  const handleImportSuccess = (data: any[]) => {
    ElMessage.success(`导入 ${data.length} 条数据成功`)
    refreshData()
  }
</script>
```

## Methods

通过 `ref` 获取组件实例后调用：

| 方法名                    | 说明               |
| ------------------------- | ------------------ |
| elTableRef                | 获取 el-table 实例 |
| scrollToTop()             | 滚动到顶部         |
| setScrollTop(top: number) | 设置滚动位置       |

```typescript
const tableRef = ref()

// 获取原生表格实例
const elTable = tableRef.value?.elTableRef

// 滚动到顶部
tableRef.value?.scrollToTop()

// 滚动到指定位置
tableRef.value?.scrollTo(200)

// 全选/取消全选
elTable?.toggleAllSelection()
elTable?.clearSelection()
```

## 典型应用场景

- 用户管理列表
- 订单管理列表
- 数据统计表格
- 可编辑表格
- 带权限控制的表格
- 左树右表布局
