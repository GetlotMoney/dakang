# useTableColumns - 表格列配置管理 Hook

提供动态的表格列配置管理能力，支持运行时灵活控制列的显示、隐藏、排序等操作。

## 基本信息

- **Hook 名称**: useTableColumns
- **文件路径**: `@/hooks/core/useTableColumns.ts`
- **配套使用**: 通常与 useTable 配合使用

## 功能特性

1. **列显示控制** - 动态显示/隐藏列，支持批量操作
2. **列排序** - 拖拽或编程方式重新排列列顺序
3. **列配置管理** - 新增、删除、更新列配置
4. **特殊列支持** - 自动处理 selection、expand、index 等特殊列
5. **状态持久化** - 保持列的显示状态，支持重置到初始状态

## 基本用法

```typescript
import { useTableColumns } from '@/hooks/core/useTableColumns'

const { columns, columnChecks, toggleColumn, reorderColumns } = useTableColumns(() => [
  { prop: 'name', label: '姓名', visible: true },
  { prop: 'email', label: '邮箱', visible: true },
  { prop: 'status', label: '状态', visible: false }
])
```

## API

### 参数

| 参数           | 说明           | 类型                   |
| -------------- | -------------- | ---------------------- |
| columnsFactory | 列配置工厂函数 | `() => ColumnOption[]` |

### 返回值

| 属性/方法          | 说明             |
| ------------------ | ---------------- |
| columns            | 当前显示的列配置 |
| columnChecks       | 所有列的检查状态 |
| addColumn          | 新增列           |
| removeColumn       | 删除列           |
| toggleColumn       | 切换列显示状态   |
| updateColumn       | 更新列配置       |
| batchUpdateColumns | 批量更新列配置   |
| reorderColumns     | 重新排序列       |
| getColumnConfig    | 获取指定列配置   |
| getAllColumns      | 获取所有列配置   |
| resetColumns       | 重置所有列配置   |

## ColumnOption 类型

```typescript
interface ColumnOption<T = any> {
  type?: 'selection' | 'expand' | 'index' | string
  prop?: string
  label?: string
  visible?: boolean
  checked?: boolean
  // 其他 Element Plus Table Column Props
  [key: string]: any
}
```

## 使用示例

### 基础用法

```typescript
const { columns, columnChecks, toggleColumn } = useTableColumns(() => [
  { type: 'index', label: '序号', visible: true },
  { prop: 'name', label: '姓名', visible: true },
  { prop: 'email', label: '邮箱', visible: true },
  { prop: 'status', label: '状态', visible: false }
])
```

### 切换列显示

```typescript
// 切换单列
toggleColumn('email', false)

// 批量切换
toggleColumn(['name', 'email'], true)

// 无参数切换（取反）
toggleColumn('status')
```

### 重新排序

```typescript
// 将第一列移动到最后
reorderColumns(0, columns.value.length - 1)
```

### 更新列配置

```typescript
// 单个更新
updateColumn('email', { label: '电子邮箱', visible: true })

// 批量更新
updateColumn([
  { prop: 'name', updates: { label: '用户名' } },
  { prop: 'status', updates: { visible: true } }
])
```

### 新增/删除列

```typescript
// 新增列
addColumn({ prop: 'actions', label: '操作', visible: true })

// 在指定位置新增
addColumn({ prop: 'avatar', label: '头像' }, 1)

// 删除列
removeColumn('email')

// 批量删除
removeColumn(['name', 'email'])
```

### 获取配置

```typescript
// 获取指定列
const nameColumn = getColumnConfig('name')

// 获取所有列
const allColumns = getAllColumns()

// 重置到初始状态
resetColumns()
```

## 与 useTable 配合使用

```typescript
const table = useTable({
  core: {
    apiFn: fetchUsers,
    columnsFactory: () => [
      { type: 'index', label: '序号', visible: true },
      { prop: 'name', label: '姓名', visible: true },
      { prop: 'email', label: '邮箱', visible: true },
      { prop: 'actions', label: '操作', visible: true }
    ]
  }
})

// 在模板中使用
// <ArtTable :data="table.data">
//   <el-table-column v-for="col in table.columns" :key="col.prop" v-bind="col">
```

## 典型应用场景

- 表格列显示自定义
- 用户偏好列配置保存
- 动态表格列管理
- 可配置的表格视图
