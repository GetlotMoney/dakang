# ArtSearchBar - 列表筛选栏

用于后台列表页筛选，组件路径：`@/components/core/forms/art-search-bar/index.vue`。

## Demo 约定

- 业务和系统列表页默认开启 `auto-search`：文本输入停止 350ms 后查询，选择器等值变化后同样触发；组件自动隐藏“查询”按钮。
- 筛选控件必须提供清空能力；项目默认不显示独立“重置”按钮，避免与输入框或选择器的清除动作重复。
- 登录、表单提交、设备指令和其他写操作不能使用自动查询语义。
- 自动查询必须配合父页面的“回到第 1 页”逻辑；使用 `useTable` 时优先调用 `getDataDebounced` 或既有 `handleSearch`。

## 基本用法

```vue
<template>
  <ArtSearchBar
    ref="searchBarRef"
    v-model="searchForm"
    :items="searchItems"
    auto-search
    @search="handleSearch"
    @reset="handleReset"
  />
</template>

<script setup lang="ts">
  const searchForm = ref<{ keyword?: string; status?: number }>({})

  const searchItems = computed(() => [
    {
      label: '关键词',
      key: 'keyword',
      type: 'input',
      placeholder: '请输入关键词',
      clearable: true
    },
    {
      label: '状态',
      key: 'status',
      type: 'select',
      placeholder: '请选择状态',
      clearable: true,
      options: [
        { label: '启用', value: 1 },
        { label: '停用', value: 2 }
      ]
    }
  ])

  function handleSearch(params: Record<string, unknown>) {
    // 父页面在这里同步查询参数并回到第 1 页。
  }

  function handleReset() {
    // 父页面清理查询参数并立即重新加载。
  }
</script>
```

## Props

| 属性                | 说明                           | 类型                         | 默认值    |
| ------------------- | ------------------------------ | ---------------------------- | --------- |
| `items`             | 筛选项配置                     | `SearchFormItem[]`           | `[]`      |
| `v-model`           | 筛选表单对象                   | `Record<string, any>`        | `{}`      |
| `span`              | 每个筛选项栅格宽度             | `number`                     | `6`       |
| `gutter`            | 栅格间距                       | `number`                     | `12`      |
| `label-position`    | 标签位置                       | `'left' \| 'right' \| 'top'` | `'right'` |
| `label-width`       | 标签宽度                       | `string \| number`           | `'70px'`  |
| `is-expand`         | 是否固定展开全部筛选项         | `boolean`                    | `false`   |
| `default-expanded`  | 初始是否展开                   | `boolean`                    | `false`   |
| `show-expand`       | 是否显示展开/收起              | `boolean`                    | `true`    |
| `show-reset`        | 是否显示重置按钮               | `boolean`                    | `false`   |
| `show-search`       | 手动模式是否显示查询按钮       | `boolean`                    | `true`    |
| `auto-search`       | 是否在用户修改筛选值后自动查询 | `boolean`                    | `false`   |
| `auto-search-delay` | 自动查询防抖毫秒数             | `number`                     | `350`     |
| `disabled-search`   | 是否禁用手动查询按钮           | `boolean`                    | `false`   |

## SearchFormItem

```ts
interface SearchFormItem {
  key: string
  label: string | Component | (() => VNode)
  type?:
    | 'input'
    | 'inputTag'
    | 'number'
    | 'select'
    | 'switch'
    | 'checkbox'
    | 'checkboxgroup'
    | 'radiogroup'
    | 'date'
    | 'daterange'
    | 'datetime'
    | 'datetimerange'
    | 'rate'
    | 'slider'
    | 'cascader'
    | 'timepicker'
    | 'timeselect'
    | 'treeselect'
    | string
  hidden?: boolean
  span?: number
  labelWidth?: string | number
  render?: Component | (() => VNode)
  props?: Record<string, any>
  options?: Record<string, any>[]
  slots?: Record<string, (() => any) | undefined>
  placeholder?: string
}
```

未提供 `props` 时，`options`、`placeholder`、`clearable` 等非根字段会透传给表单控件；使用 `props` 时应把这些控件属性全部写入 `props`。

## Events 与暴露方法

| 名称             | 说明                                                           |
| ---------------- | -------------------------------------------------------------- |
| `search(params)` | 手动点击查询或自动查询防抖结束后触发；空字符串、空数组等已清洗 |
| `reset()`        | 恢复初始化筛选快照后触发                                       |
| `validate()`     | 通过组件 ref 调用表单校验                                      |
| `getOutput()`    | 获取清洗后的筛选参数                                           |

不要在父页面再叠加另一套 `watch + debounce` 监听同一个 `ArtSearchBar`，否则会产生重复请求。
