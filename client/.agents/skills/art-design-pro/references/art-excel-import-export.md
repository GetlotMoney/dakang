# ArtExcelImport & ArtExcelExport - Excel 导入导出组件

基于 xlsx 库的 Excel 导入导出组件，支持灵活的数据配置和事件处理。

## 基本信息

- **组件名称**: ArtExcelImport, ArtExcelExport
- **组件路径**:
  - `@/components/core/forms/art-excel-import/index.vue`
  - `@/components/core/forms/art-excel-export/index.vue`
- **使用示例**: `src/views/widgets/excel/index.vue`

## 功能特性

### ArtExcelImport

1. **文件选择** - 提供文件上传按钮
2. **数据解析** - 自动解析 Excel 文件内容
3. **事件回调** - 提供成功、失败、进度等事件
4. **插槽支持** - 支持自定义上传按钮内容

### ArtExcelExport

1. **数据导出** - 支持导出 JSON 数据为 Excel
2. **表头配置** - 支持自定义表头映射
3. **列配置** - 支持列宽、格式化等配置
4. **自动索引** - 支持自动添加序号列
5. **进度回调** - 提供导出进度事件

## ArtExcelImport 基本用法

```vue
<template>
  <ArtExcelImport @import-success="handleImportSuccess" @import-error="handleImportError">
    <template #import-text>上传 Excel</template>
  </ArtExcelImport>
</template>

<script setup lang="ts">
  import ArtExcelImport from '@/components/core/forms/art-excel-import/index.vue'

  const handleImportSuccess = (data: Array<Record<string, unknown>>) => {
    console.log('导入成功:', data)
  }

  const handleImportError = (error: Error) => {
    console.error('导入失败:', error)
  }
</script>
```

## ArtExcelExport 基本用法

```vue
<template>
  <ArtExcelExport
    :data="tableData"
    filename="用户数据"
    sheetName="用户列表"
    type="success"
    :headers="headers"
    auto-index
    :columns="columnConfig"
    @export-success="handleExportSuccess"
  >
    导出 Excel
  </ArtExcelExport>
</template>

<script setup lang="ts">
  import ArtExcelExport from '@/components/core/forms/art-excel-export/index.vue'

  const tableData = ref([
    { name: '李四', age: 20, city: '上海' },
    { name: '张三', age: 25, city: '北京' }
  ])

  const headers = {
    name: '姓名',
    age: '年龄',
    city: '城市'
  }

  const columnConfig = {
    name: { title: '姓名', width: 20 },
    age: { title: '年龄', width: 10, formatter: (v) => `${v}岁` },
    city: { title: '城市', width: 12, formatter: (v) => `${v}市` }
  }
</script>
```

## ArtExcelImport API

### Props

| 属性名 | 说明           | 类型     | 默认值          |
| ------ | -------------- | -------- | --------------- |
| accept | 接受的文件类型 | `string` | `'.xlsx, .xls'` |

### Events

| 事件名         | 说明           | 回调参数                                 |
| -------------- | -------------- | ---------------------------------------- |
| import-success | 导入成功时触发 | `(data: Array<Record<string, unknown>>)` |
| import-error   | 导入失败时触发 | `(error: Error)`                         |

## ArtExcelExport API

### Props

| 属性名     | 说明                 | 类型                     | 默认值      |
| ---------- | -------------------- | ------------------------ | ----------- |
| data       | 要导出的数据数组     | `any[]`                  | `[]`        |
| filename   | 文件名（不含扩展名） | `string`                 | `'export'`  |
| sheetName  | 工作表名称           | `string`                 | `'Sheet1'`  |
| type       | 按钮类型             | `string`                 | `'primary'` |
| headers    | 表头映射配置         | `Record<string, string>` | `{}`        |
| auto-index | 是否自动添加序号列   | `boolean`                | `false`     |
| columns    | 列配置               | `ColumnConfig`           | `{}`        |

### ColumnConfig 类型

```typescript
interface ColumnConfig {
  [key: string]: {
    title?: string // 列标题
    width?: number // 列宽
    formatter?: (value: unknown) => string // 格式化函数
  }
}
```

### Events

| 事件名          | 说明           | 回调参数             |
| --------------- | -------------- | -------------------- |
| export-success  | 导出成功时触发 | `()`                 |
| export-error    | 导出失败时触发 | `(error: Error)`     |
| export-progress | 导出进度回调   | `(progress: number)` |

## 完整示例

```vue
<template>
  <ArtExcelImport @import-success="handleImportSuccess" @import-error="handleImportError">
    <template #import-text>上传 Excel</template>
  </ArtExcelImport>

  <ArtExcelExport
    :data="tableData"
    filename="用户数据-1"
    sheetName="用户列表"
    type="success"
    :headers="headers"
    auto-index
    :columns="columnConfig"
    @export-success="handleExportSuccess"
    @export-error="handleExportError"
    @export-progress="handleProgress"
  >
    导出 Excel
  </ArtExcelExport>

  <ElButton type="danger" @click="handleClear">清除数据</ElButton>

  <ArtTable :data="tableData">
    <ElTableColumn
      v-for="key in Object.keys(headers)"
      :key="key"
      :prop="key"
      :label="headers[key]"
    />
  </ArtTable>
</template>

<script setup lang="ts">
  const tableData = ref([
    { name: '李四', age: 20, city: '上海' },
    { name: '张三', age: 25, city: '北京' }
  ])

  const headers = { name: '姓名', age: '年龄', city: '城市' }

  const columnConfig = {
    name: { title: '姓名', width: 20, formatter: (v) => String(v) },
    age: { title: '年龄', width: 10, formatter: (v) => `${v}岁` },
    city: { title: '城市', width: 12, formatter: (v) => `${v}市` }
  }

  const handleImportSuccess = (data) => {
    const formattedData = data.map((item) => ({
      name: String(item['姓名'] || ''),
      age: Number(item['年龄']) || 0,
      city: String(item['城市'] || '')
    }))
    tableData.value = formattedData
    ElMessage.success(`成功导入 ${formattedData.length} 条数据`)
  }

  const handleExportSuccess = () => {
    ElMessage.success('Excel 导出成功')
  }
</script>
```

## 典型应用场景

- 数据批量导入
- 数据导出备份
- 报表生成
- 数据迁移
