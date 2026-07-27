# useTable - 企业级表格数据管理 Hook

提供完整的表格解决方案，包括数据获取、缓存、分页、搜索、刷新策略等功能。

## 基本信息

- **Hook 名称**: useTable
- **文件路径**: `@/hooks/core/useTable.ts`
- **配套 Hook**: useTableColumns - 表格列配置管理

## 功能特性

1. **数据管理** - 自动处理 API 请求、响应转换、加载状态和错误处理
2. **分页控制** - 自动同步分页状态、移动端适配、智能页码边界处理
3. **搜索功能** - 防抖搜索优化、参数管理、一键重置、参数过滤
4. **缓存系统** - 智能请求缓存、多种清理策略、自动过期管理
5. **刷新策略** - 提供 5 种刷新方法适配不同业务场景
6. **列配置管理** - 动态显示/隐藏列、列排序、配置持久化

## 基本用法

```typescript
import { useTable } from '@/hooks/core/useTable'

// 定义 API 函数
const fetchUserList = async (params: any) => {
  return await get('/api/users', params)
}

// 使用 useTable
const { data, loading, pagination, searchParams, getData, resetSearchParams } = useTable({
  core: {
    apiFn: fetchUserList,
    apiParams: { status: 1 },
    immediate: true
  },
  performance: {
    enableCache: true,
    cacheTime: 5 * 60 * 1000
  },
  hooks: {
    onSuccess: (data) => console.log('数据加载成功', data),
    onError: (error) => console.error('加载失败', error)
  }
})
```

## API

### 配置参数 (UseTableConfig)

```typescript
interface UseTableConfig<TApiFn> {
  // 核心配置
  core: {
    apiFn: TApiFn // API 请求函数
    apiParams?: Partial<TParams> // 默认请求参数
    excludeParams?: string[] // 排除的参数
    immediate?: boolean // 是否立即加载
    columnsFactory?: () => ColumnOption[] // 列配置工厂
    paginationKey?: {
      // 分页字段映射
      current?: string
      size?: string
    }
  }

  // 数据处理
  transform?: {
    dataTransformer?: (data: TRecord[]) => TRecord[] // 数据转换
    responseAdapter?: (response: TResponse) => ApiResponse // 响应适配器
  }

  // 性能优化
  performance?: {
    enableCache?: boolean // 启用缓存
    cacheTime?: number // 缓存时间(ms)
    debounceTime?: number // 防抖时间(ms)
    maxCacheSize?: number // 最大缓存数
  }

  // 生命周期钩子
  hooks?: {
    onSuccess?: (data, response) => void
    onError?: (error) => void
    onCacheHit?: (data, response) => void
    onLoading?: (loading) => void
    resetFormCallback?: () => void
  }

  // 调试配置
  debug?: {
    enableLog?: boolean
    logLevel?: 'info' | 'warn' | 'error'
  }
}
```

### 返回值

| 属性/方法           | 说明           | 类型                                 |
| ------------------- | -------------- | ------------------------------------ |
| **数据相关**        |                |                                      |
| data                | 表格数据       | `Ref<TRecord[]>`                     |
| loading             | 加载状态       | `Readonly<Ref<boolean>>`             |
| error               | 错误状态       | `Ref<TableError \| null>`            |
| isEmpty             | 是否为空       | `ComputedRef<boolean>`               |
| hasData             | 是否有数据     | `ComputedRef<boolean>`               |
| **分页相关**        |                |                                      |
| pagination          | 分页状态       | `Readonly<Reactive>`                 |
| paginationMobile    | 移动端分页配置 | `ComputedRef`                        |
| handleSizeChange    | 页大小变化处理 | `(size: number) => Promise<void>`    |
| handleCurrentChange | 页码变化处理   | `(current: number) => Promise<void>` |
| **搜索相关**        |                |                                      |
| searchParams        | 搜索参数       | `Reactive<TParams>`                  |
| replaceSearchParams | 替换搜索参数   | `(params?) => void`                  |
| resetSearchParams   | 重置搜索参数   | `() => Promise<void>`                |
| **数据操作**        |                |                                      |
| fetchData / getData | 获取数据       | `(params?) => Promise`               |
| getDataDebounced    | 防抖获取数据   | `(params?) => void`                  |
| clearData           | 清空数据       | `() => void`                         |
| **刷新策略**        |                |                                      |
| refreshData         | 全量刷新       | `() => Promise<void>`                |
| refreshSoft         | 轻量刷新       | `() => Promise<void>`                |
| refreshCreate       | 新增后刷新     | `() => Promise<void>`                |
| refreshUpdate       | 更新后刷新     | `() => Promise<void>`                |
| refreshRemove       | 删除后刷新     | `() => Promise<void>`                |
| **缓存控制**        |                |                                      |
| cacheInfo           | 缓存统计       | `ComputedRef`                        |
| clearCache          | 清除缓存       | `(strategy, context?) => void`       |
| clearExpiredCache   | 清除过期缓存   | `() => number`                       |
| **请求控制**        |                |                                      |
| cancelRequest       | 取消请求       | `() => void`                         |

### 缓存清理策略 (CacheInvalidationStrategy)

```typescript
import { CacheInvalidationStrategy } from '@/hooks/core/useTable'

// 清除所有缓存
clearCache(CacheInvalidationStrategy.CLEAR_ALL, '手动刷新')

// 只清除当前搜索条件的缓存
clearCache(CacheInvalidationStrategy.CLEAR_CURRENT, '搜索数据')

// 清除分页相关缓存
clearCache(CacheInvalidationStrategy.CLEAR_PAGINATION, '新增数据')

// 不清理任何缓存
clearCache(CacheInvalidationStrategy.KEEP_ALL, '保持缓存')
```

## 使用示例

### 基础表格

```typescript
const fetchUsers = async (params) => {
  return await api.get('/users', { params })
}

const table = useTable({
  core: {
    apiFn: fetchUsers,
    apiParams: { page: 1, pageSize: 10 }
  }
})

// 在模板中使用
// <el-table :data="table.data.value">
```

### 带搜索表单

```typescript
const searchForm = reactive({
  name: '',
  status: ''
})

const table = useTable({
  core: {
    apiFn: fetchUsers,
    apiParams: { status: 1 }
  }
})

// 搜索
const handleSearch = () => {
  table.getData({ ...searchForm })
}

// 重置
const handleReset = async () => {
  searchForm.name = ''
  searchForm.status = ''
  await table.resetSearchParams()
}
```

### 带列配置

```typescript
import { useTableColumns } from '@/hooks/core/useTable'

const table = useTable({
  core: {
    apiFn: fetchUsers,
    columnsFactory: () => [
      { prop: 'name', label: '姓名', visible: true },
      { prop: 'email', label: '邮箱', visible: true },
      { prop: 'status', label: '状态', visible: false }
    ]
  }
})

// 使用列配置
// <el-table :data="table.data">
//   <el-table-column v-for="col in table.columns" :key="col.prop" :prop="col.prop" :label="col.label">
```

### 刷新策略场景

```typescript
// 新增数据后 - 回到第一页并清空分页缓存
await table.refreshCreate()

// 更新数据后 - 保持当前页
await table.refreshUpdate()

// 删除数据后 - 智能处理页码，避免空页面
await table.refreshRemove()

// 手动刷新 - 清空所有缓存
await table.refreshData()

// 定时刷新 - 仅清空当前缓存，保持分页
await table.refreshSoft()
```

## 典型应用场景

- 后台管理系统表格
- 数据列表页面
- 搜索筛选表格
- 带分页的数据展示
