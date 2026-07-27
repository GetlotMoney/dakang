# 六维达康字典使用指南

字典是枚举字段的唯一数据源。数据库使用 `api_dict_type` / `api_dict_data`，前端常量集中在 `client/src/constants/dict.ts`。

## 强制流程

1. 读取 `server/src/main/java/com/jbk/tool/consts/ApiEnum.java`，选择未占用 type。
2. 在领域 SQL 中以 `INSERT IGNORE INTO` 写字典类型和数据。
3. 在独立验证库执行 SQL，确认接口可返回数据。
4. 后端 Po/Bo/Vo 的说明与 SQL COMMENT 保持一致。
5. 在 `DictTypeEnum` 注册常量。
6. 页面 `onMounted` 只加载一次，经 `toDictOptions` 传给搜索、弹窗和表格。

## 示例

```sql
INSERT IGNORE INTO `api_dict_type`
(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
VALUES ('设备在线状态', '1300', '设备心跳判定结果');

INSERT IGNORE INTO `api_dict_data`
(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
VALUES
(NULL, 1, '1300', 1, 1, '在线'),
(NULL, 1, '1300', 2, 2, '离线'),
(NULL, 1, '1300', 3, 3, '未激活');
```

```ts
const onlineOptions = ref<{ label: string; value: number }[]>([])

onMounted(async () => {
  onlineOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.设备在线状态))
})
```

```ts
const onlineLabel = (value: number) =>
  onlineOptions.value.find((item) => item.value === value)?.label || String(value)
```

搜索组件通过 props 或同一页面作用域复用 `onlineOptions`，不得再维护一套硬编码选项。

## 常见问题

- “字典不存在”：SQL 没有实际执行，或 type 未注册。
- 下拉为空：加载时机错误，或没有把响应转为 `{label,value}`。
- 表格显示数字：formatter 未使用同一字典集合。
- SQL 重复：没有使用 `INSERT IGNORE INTO` 或缺少幂等条件。
- type 冲突：未先检查 `ApiEnum.DictType`。
