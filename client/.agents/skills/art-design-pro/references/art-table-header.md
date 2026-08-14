# ArtTableHeader - 表格工具条

`ArtTable` 上方的工具条，组件路径：`@/components/core/tables/art-table-header/index.vue`。

它**不渲染标题、也不承载搜索表单**：左侧是业务动作插槽，右侧是一排内置工具（搜索开关、刷新、表格尺寸、全屏、列设置、其他设置）。搜索表单是页面里独立的 `ArtSearchBar` / `modules/*-search.vue`，本组件只提供切换它显隐的开关。

## Props

| 属性名 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `showZebra` | 「其他设置」里是否提供斑马纹开关 | `boolean` | `true` |
| `showBorder` | 「其他设置」里是否提供边框开关 | `boolean` | `true` |
| `showHeaderBackground` | 「其他设置」里是否提供表头背景开关 | `boolean` | `true` |
| `fullClass` | 全屏时加 `el-full-screen` 的目标元素 class | `string` | `'art-page-view'` |
| `layout` | 内置工具开关，逗号分隔 | `string` | `'search,refresh,size,fullscreen,columns,settings'` |
| `loading` | 刷新图标是否转圈 | `boolean` | 无 |
| `showSearchBar` | 搜索栏显隐，`v-model:showSearchBar` 双向 | `boolean` | `undefined` |

### v-model

- `v-model:columns="columnChecks"` — `defineModel<ColumnOption[]>('columns', { default: () => [] })`，列设置面板直接改这个数组（拖拽排序会写回顺序）。**这是本组件的主用法**，页面基本都传（如 `views/order/split/index.vue`、`views/order/autorule/index.vue`、`views/user/index.vue`）。
- `v-model:showSearchBar="showSearchBar"` — 搜索开关按钮**仅在 `showSearchBar != null` 时渲染**；不传就没有这个按钮。注意 `layout` 里的 `search` 项对它不起作用，别靠删 `layout` 的 `search` 来隐藏它。

## Events

| 事件名                 | 说明                                       | 回调参数           |
| ---------------------- | ------------------------------------------ | ------------------ |
| `refresh`              | 点刷新按钮触发                             | 无参数             |
| `search`               | 点搜索开关按钮触发                         | 无参数             |
| `update:showSearchBar` | 点搜索开关按钮时先于 `search` 触发，取反值 | `(value: boolean)` |

点一次开关按钮会**先 `emit('update:showSearchBar', !showSearchBar)` 再 `emit('search')`**，所以 `@search` 里不要再自己翻转显隐。

## Slots

| 插槽名  | 说明                                     |
| ------- | ---------------------------------------- |
| `left`  | 左侧业务动作区（新增、导入、批量操作等） |
| `right` | 右侧内置工具**之后**追加的自定义按钮     |

没有 `actions` / `search` 插槽。

## 基本用法

裁剪自 `client/src/views/order/splitconfig/index.vue`：

```vue
<ElCard class="art-table-card">
  <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
    <template #left>
      <ElButton
        v-if="hasPermission('finance:config:edit')"
        type="primary"
        @click="createVisible = true"
        v-ripple
        >新增生效版本</ElButton
      >
    </template>
  </ArtTableHeader>

  <ArtTable :loading="loading" :data="data" :columns="columns" :pagination="pagination" />
</ElCard>
```

## 带搜索开关

裁剪自 `client/src/views/system/role/index.vue`：搜索组件由页面自己 `v-show`，工具条只翻转状态。

```vue
<RoleSearch
  v-show="showSearchBar"
  v-model="searchForm"
  @search="handleSearch"
  @reset="resetSearchParams"
/>

<ElCard class="art-table-card" :style="{ 'margin-top': showSearchBar ? '12px' : '0' }">
  <ArtTableHeader
    v-model:columns="columnChecks"
    v-model:showSearchBar="showSearchBar"
    :loading="loading"
    @refresh="refreshData"
  />
</ElCard>
```

## 内置工具行为

`layout` 逗号串经 `shouldShow(name)` 逐项匹配，控制 `refresh`、`size`、`fullscreen`、`columns`、`settings` 五个按钮的渲染；`search` 项形同虚设（见上）。

- **刷新**：`refresh` 会置内部 `isManualRefresh = true`，**只有手动点过刷新后 `loading` 才会让图标转圈**，页面初次加载的 loading 不转。
- **表格尺寸**：小/默认/大三档，经 `useTableStore().setTableSize(TableSizeEnum)` 落到全局 store，不通过 emit 给页面。
- **列设置**：`VueDraggable` 拖拽排序，`filter=".fixed-column"` 且 `checkColumnMove` 禁止把列拖到 `item.fixed` 的列上；固定列显示 `ri:unpin-line` 且不可拖。列显隐读取 `getColumnVisibility`——**优先 `visible`，缺省回落 `checked ?? true`**；勾选时 `updateColumnVisibility` 同时写 `checked` 和 `visible` 保持兼容。`item.disabled` 的列复选框禁用。
- **其他设置**：斑马纹 / 边框 / 表头背景三项直接绑 `useTableStore` 的 `isZebra`、`isBorder`、`isHeaderBackground`，由 `showZebra` / `showBorder` / `showHeaderBackground` 决定各项是否出现。
- **全屏**：对 `document.querySelector('.' + fullClass)` 加 `el-full-screen` class，同时锁 `body.overflow` 并 `setIsFullScreen(true)`；支持 ESC 退出；组件在全屏态下卸载会兜底还原 `overflow` 与 class。默认目标 `.art-page-view` 由布局组件 `core/layouts/art-page-content` 渲染，业务页无需自己加；只有要全屏别的容器时才改 `fullClass`，且该 class 必须真实存在，否则 `querySelector` 取不到、按钮点了没反应。

## 响应式

工具条整体在 `max-sm` 下隐藏（`max-sm:!hidden`），左侧插槽仍在。移动端必需的动作不要只放在右侧工具区。
