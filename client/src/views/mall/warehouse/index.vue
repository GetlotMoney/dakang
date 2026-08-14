<!-- 商城前置仓管理（E2E-09 S1，R1-P1-1/P2-2 改仓库规范组件）：档案、行政区、履约范围、启停；电话脱敏，范围服务端唯一校验 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="mall" />

    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      auto-search
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElButton v-if="canEdit" type="primary" @click="openCreate" v-ripple>
            新增前置仓
          </ElButton>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>
    </ElCard>

    <ElDialog v-model="dialogVisible" :title="editing ? '编辑前置仓' : '新增前置仓'" width="600px">
      <ElForm ref="formRef" :model="form" :rules="rules" label-width="100px">
        <ElFormItem label="仓库编号" prop="warehouseNo">
          <ElInput
            v-model="form.warehouseNo"
            :disabled="editing"
            placeholder="唯一编号，创建后不可改"
          />
        </ElFormItem>
        <ElFormItem label="仓库名称" prop="warehouseName">
          <ElInput v-model="form.warehouseName" />
        </ElFormItem>
        <ElFormItem label="联系人" prop="contactName">
          <ElInput v-model="form.contactName" />
        </ElFormItem>
        <ElFormItem label="联系电话" prop="contactPhone">
          <ElInput
            v-model="form.contactPhone"
            :placeholder="editing ? '重新填写完整手机号' : '11 位手机号'"
          />
        </ElFormItem>
        <ElFormItem label="行政区码" required>
          <div class="flex gap-2">
            <ElInput v-model="form.provinceCode" placeholder="省(6位)" style="width: 120px" />
            <ElInput v-model="form.cityCode" placeholder="市(6位)" style="width: 120px" />
            <ElInput v-model="form.districtCode" placeholder="区(6位)" style="width: 120px" />
          </div>
        </ElFormItem>
        <ElFormItem label="详细地址" prop="warehouseAddress">
          <ElInput v-model="form.warehouseAddress" />
        </ElFormItem>
        <ElFormItem label="履约区县码" prop="districtCodesText">
          <ElInput
            v-model="form.districtCodesText"
            type="textarea"
            :rows="2"
            placeholder="6 位行政区码，多个用逗号分隔（如 420111,420114）"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="submit">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchMallWarehousePage,
    fetchMallWarehouseDetail,
    fetchMallWarehouseSave,
    fetchMallWarehouseUpdate,
    fetchMallWarehouseChangeStatus,
    type MallWarehouseItem
  } from '@/api/mall'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { hasPermission } from '@/utils/permission'
  import {
    ElButton,
    ElMessage,
    ElMessageBox,
    ElTag,
    type FormInstance,
    type FormRules
  } from 'element-plus'

  defineOptions({ name: 'MallWarehouse' })

  /** 建仓、改履约范围与启停同属前置仓维护：停用会让该仓库存退出小程序可售聚合。 */
  const canEdit = computed(() => hasPermission('mall:warehouse:edit'))

  const searchForm = ref<{ keyword?: string; status?: number }>({})
  const statusOptions = ref<{ label: string; value: number }[]>([])

  const searchItems = computed(() => [
    {
      label: '名称/编号',
      key: 'keyword',
      type: 'input',
      placeholder: '仓库名称或编号',
      clearable: true
    },
    {
      label: '状态',
      key: 'status',
      type: 'select',
      placeholder: '全部',
      clearable: true,
      options: statusOptions.value
    }
  ])

  onMounted(async () => {
    statusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.前置仓状态))
  })

  const statusLabel = (v: number) =>
    statusOptions.value.find((o) => o.value === v)?.label ?? String(v)

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchMallWarehousePage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'warehouseNo', label: '仓库编号', width: 140 },
        { prop: 'warehouseName', label: '仓库名称', minWidth: 150 },
        { prop: 'contactName', label: '联系人', width: 100 },
        { prop: 'maskedPhone', label: '联系电话', width: 130 },
        {
          prop: 'districtCodes',
          label: '履约区县',
          minWidth: 160,
          formatter: (row: MallWarehouseItem) => (row.districtCodes || []).join('、') || '—'
        },
        {
          prop: 'warehouseStatus',
          label: '状态',
          width: 100,
          align: 'center',
          formatter: (row: MallWarehouseItem) =>
            h(ElTag, { type: row.warehouseStatus === 1 ? 'success' : 'info' }, () =>
              statusLabel(row.warehouseStatus)
            )
        },
        {
          prop: 'operation',
          label: '操作',
          width: 180,
          fixed: 'right',
          formatter: (row: MallWarehouseItem) =>
            h('div', [
              canEdit.value &&
                h(
                  ElButton,
                  { link: true, type: 'primary', onClick: () => openEdit(row) },
                  () => '编辑'
                ),
              canEdit.value &&
                h(
                  ElButton,
                  {
                    link: true,
                    type: row.warehouseStatus === 1 ? 'warning' : 'success',
                    onClick: () => toggleStatus(row)
                  },
                  () => (row.warehouseStatus === 1 ? '停用' : '启用')
                )
            ])
        }
      ]
    }
  })

  const handleSearch = () => {
    replaceSearchParams({ ...searchForm.value, keyword: searchForm.value.keyword || undefined })
    getData()
  }

  const handleReset = () => {
    searchForm.value = {}
    replaceSearchParams({})
    getData()
  }

  interface WarehouseForm {
    id?: string
    version?: number
    warehouseNo: string
    warehouseName: string
    contactName: string
    contactPhone: string
    provinceCode: string
    cityCode: string
    districtCode: string
    warehouseAddress: string
    districtCodesText: string
  }

  const dialogVisible = ref(false)
  const editing = ref(false)
  const saving = ref(false)
  const formRef = ref<FormInstance>()
  const emptyForm = (): WarehouseForm => ({
    warehouseNo: '',
    warehouseName: '',
    contactName: '',
    contactPhone: '',
    provinceCode: '',
    cityCode: '',
    districtCode: '',
    warehouseAddress: '',
    districtCodesText: ''
  })
  const form = ref<WarehouseForm>(emptyForm())
  const rules: FormRules = {
    warehouseNo: [{ required: true, message: '请填写仓库编号', trigger: 'blur' }],
    warehouseName: [{ required: true, message: '请填写仓库名称', trigger: 'blur' }],
    contactName: [{ required: true, message: '请填写联系人', trigger: 'blur' }],
    contactPhone: [{ required: true, message: '请填写联系电话', trigger: 'blur' }],
    warehouseAddress: [{ required: true, message: '请填写详细地址', trigger: 'blur' }],
    districtCodesText: [{ required: true, message: '请填写履约区县码', trigger: 'blur' }]
  }

  const openCreate = () => {
    editing.value = false
    form.value = emptyForm()
    dialogVisible.value = true
  }

  const openEdit = async (row: MallWarehouseItem) => {
    editing.value = true
    // 详情重取范围码集与版本；电话原文不出接口，编辑时需重新填写
    const detail = await fetchMallWarehouseDetail(row.id)
    form.value = {
      id: detail.id,
      version: detail.version,
      warehouseNo: detail.warehouseNo,
      warehouseName: detail.warehouseName,
      contactName: detail.contactName,
      contactPhone: '',
      provinceCode: detail.provinceCode,
      cityCode: detail.cityCode,
      districtCode: detail.districtCode,
      warehouseAddress: detail.warehouseAddress,
      districtCodesText: (detail.districtCodes || []).join(',')
    }
    dialogVisible.value = true
  }

  const parseDistrictCodes = (text: string) =>
    text
      .split(/[,，\s]+/)
      .map((s) => s.trim())
      .filter((s) => s.length > 0)

  const submit = async () => {
    await formRef.value?.validate()
    const districtCodes = parseDistrictCodes(form.value.districtCodesText)
    if (districtCodes.length === 0) {
      ElMessage.warning('请至少填写一个履约区县码')
      return
    }
    saving.value = true
    try {
      const base = {
        warehouseName: form.value.warehouseName.trim(),
        contactName: form.value.contactName.trim(),
        contactPhone: form.value.contactPhone.trim(),
        provinceCode: form.value.provinceCode.trim(),
        cityCode: form.value.cityCode.trim(),
        districtCode: form.value.districtCode.trim(),
        warehouseAddress: form.value.warehouseAddress.trim(),
        districtCodes
      }
      if (editing.value && form.value.id) {
        await fetchMallWarehouseUpdate({ id: form.value.id, ...base })
      } else {
        await fetchMallWarehouseSave({ warehouseNo: form.value.warehouseNo.trim(), ...base })
      }
      ElMessage.success('保存成功')
      dialogVisible.value = false
      refreshData()
    } finally {
      saving.value = false
    }
  }

  const toggleStatus = async (row: MallWarehouseItem) => {
    const target = row.warehouseStatus === 1 ? 2 : 1
    await ElMessageBox.confirm(
      target === 2 ? '停用后该仓库库存不参与小程序可售聚合，确认停用？' : '确认启用该前置仓？',
      '提示',
      { type: 'warning' }
    )
    await fetchMallWarehouseChangeStatus({ id: row.id, targetStatus: target, version: row.version })
    ElMessage.success('操作成功')
    refreshData()
  }
</script>
