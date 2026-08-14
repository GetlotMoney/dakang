# ArtButtonTable - 表格操作图标按钮

表格操作列里的**单个**图标按钮，组件路径：`@/components/core/forms/art-button-table/index.vue`。

它不是按钮组：一次只渲染一个 `<ArtSvgIcon>`，没有 slot、没有下拉折叠、没有权限过滤。要多个操作就并排放多个 `ArtButtonTable`，权限判断在父页面做。

## Props

| 属性名 | 说明 | 类型 | 默认值 |
| --- | --- | --- | --- |
| `type` | 按钮类型，决定默认图标与配色 | `'add' \| 'edit' \| 'delete' \| 'more' \| 'view'` | 无 |
| `icon` | 自定义图标，优先级高于 `type` 的默认图标 | `string` | 无 |
| `iconClass` | 自定义样式类，优先级高于 `type` 的默认类 | `string` | 无 |
| `iconColor` | 图标颜色，写入内联 `color` | `string` | 无 |
| `buttonBgColor` | 背景色，写入内联 `background-color` | `string` | 无 |
| `label` | 可访问名称；为空时按 `type` 派生 | `string` | 无 |

`withDefaults(defineProps<Props>(), {})` 未设任何默认值——不传 `type` 也不传 `icon` 时按钮内没有图标。

## Events

| 事件名  | 说明       | 回调参数 |
| ------- | ---------- | -------- |
| `click` | 点击时触发 | 无参数   |

行数据要靠父页面闭包带入（见下方示例），组件不会回传 row。

## Slots

无。模板固定渲染 `<ArtSvgIcon :icon="iconContent" />`。

## 默认图标与配色映射

| `type`   | 默认图标               | 默认样式类                       |
| -------- | ---------------------- | -------------------------------- |
| `add`    | `ri:add-fill`          | `bg-theme/12 text-theme`         |
| `edit`   | `ri:pencil-line`       | `bg-secondary/12 text-secondary` |
| `delete` | `ri:delete-bin-5-line` | `bg-error/12 text-error`         |
| `view`   | `ri:eye-line`          | `bg-info/12 text-info`           |
| `more`   | `ri:more-2-fill`       | 无                               |

## 可访问性契约

根元素带 `role="button"`、`tabindex="0"`，`aria-label` 与 `title` 都取 `label`；`label` 缺省时按 `type` 派生为「新增 / 编辑 / 删除 / 查看详情 / 更多操作」，无 `type` 时为「操作」。键盘 `Enter` 与 `Space` 均 `preventDefault` 后触发 `click`，与鼠标点击等价。因此**不要给它套一层带 `role`/`tabindex` 的容器**，也不要靠图标本身表意而不给 `label`。

## 基本用法（单按钮）

裁剪自 `client/src/views/user/index.vue` 操作列：

```ts
{
  prop: 'operation',
  label: '操作',
  width: 100,
  fixed: 'right',
  formatter: (row: WsUserItem) =>
    h('div', [
      h(ArtButtonTable, {
        type: 'view',
        onClick: () => showDrawer(row)
      })
    ])
}
```

## 多操作 + 权限门

裁剪自 `client/src/views/system/menu/index.vue`：权限用 `hasPermission` 在外层短路，组件本身不认 `auth`。

```ts
{
  label: '操作',
  width: 180,
  align: 'right',
  fixed: 'right',
  formatter: (row: any) =>
    h('div', { style: 'text-align: right' }, [
      hasPermission('api:menu:add') &&
        h(ArtButtonTable, { type: 'add', onClick: () => handleAddChild(row) }),
      hasPermission('api:menu:update') &&
        h(ArtButtonTable, { type: 'edit', onClick: () => handleEdit(row) }),
      hasPermission('api:menu:delete') &&
        h(ArtButtonTable, { type: 'delete', onClick: () => handleDelete(row) })
    ])
}
```

## 自定义图标

```ts
h(ArtButtonTable, {
  icon: 'ri:refresh-line',
  label: '重新下发指令',
  onClick: () => resend(row)
})
```

删除等高风险动作按仓库铁律仍需二次确认与原因必填，由父页面的 `handleDelete` 承担，组件不提供确认能力。
