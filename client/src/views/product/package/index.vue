<!-- 水种套餐——套餐管理（REQ-061，真实 ws_package）。
     历史订单按下单时 PACKAGE_SNAP 快照结算，调价/下架不影响已成交订单；
     使用范围不直编 SCOPE_JSON 原文：选择器构造，提交时组装，服务端 WaterCardScope 终审。 -->
<template>
  <div class="package-page art-full-height">
    <BusinessModuleNav module-key="product" />

    <ElCard class="art-table-card" shadow="never">
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <ElInput
          v-model="searchForm.packageName"
          placeholder="套餐名称"
          clearable
          style="width: 200px"
          @input="loadDataDebounced"
        />
        <ElSelect
          v-model="searchForm.packageStatus"
          placeholder="状态"
          clearable
          style="width: 140px"
          @change="handleFilterChange"
        >
          <ElOption
            v-for="opt in statusOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </ElSelect>
        <ElButton v-if="hasPermission('product:package:add')" @click="showDialog()" v-ripple
          >新增套餐</ElButton
        >
        <div class="ml-auto flex items-center gap-2">
          <ElTag type="warning" effect="plain">修改套餐不影响历史订单</ElTag>
        </div>
      </div>

      <ElTable :data="list" row-key="id" border v-loading="loading">
        <ElTableColumn prop="packageName" label="套餐名称" min-width="170" fixed="left" />
        <ElTableColumn label="售价" width="110" align="right">
          <template #default="{ row }">￥{{ fenToYuan(row.payAmount) }}</template>
        </ElTableColumn>
        <ElTableColumn label="兑换水量" width="110">
          <template #default="{ row }">
            {{ row.waterMl > 0 ? mlToLiter(row.waterMl) : '纯余额' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="赠送余额" width="100">
          <template #default="{ row }">
            {{ row.bonusAmount > 0 ? '￥' + fenToYuan(row.bonusAmount) : '-' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="折算单价" width="110">
          <template #default="{ row }">
            {{ row.unitPriceSnap !== '0' ? row.unitPriceSnap + ' 分/升' : '-' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="有效期" width="100">
          <template #default="{ row }">{{
            row.expireDays ? row.expireDays + ' 天' : '永久'
          }}</template>
        </ElTableColumn>
        <ElTableColumn label="使用范围" min-width="200">
          <template #default="{ row }">
            <ElTag v-if="!row.scopeValid" type="warning" effect="plain" size="small" class="mr-1">
              不可购卡
            </ElTag>
            <span>{{ row.scopeSummary }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }">
            <ElTag :type="row.packageStatus === 1 ? 'success' : 'info'">
              {{ statusLabel(row.packageStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="packageRemark" label="备注" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.packageRemark || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn v-if="canManagePackage" label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <ElButton
              v-if="hasPermission('product:package:update')"
              type="primary"
              size="small"
              link
              @click="showDialog(row)"
              >编辑</ElButton
            >
            <ElButton
              v-if="hasPermission('product:package:shelf')"
              :type="row.packageStatus === 1 ? 'warning' : 'success'"
              size="small"
              link
              @click="toggleShelf(row)"
            >
              {{ row.packageStatus === 1 ? '下架' : '上架' }}
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="mt-3 flex justify-end">
        <ElPagination
          v-model:current-page="pageParams.current"
          v-model:page-size="pageParams.size"
          :total="total"
          layout="total, prev, pager, next"
          @change="loadData"
        />
      </div>

      <!-- 新增/编辑弹窗 -->
      <ElDialog
        v-model="dialogVisible"
        :title="formData.id ? '编辑套餐' : '新增套餐'"
        width="560px"
        align-center
      >
        <ElForm ref="formRef" :model="formData" :rules="rules" label-width="100px">
          <ElFormItem label="套餐名称" prop="packageName">
            <ElInput v-model="formData.packageName" placeholder="如 100元500升卡" maxlength="50" />
          </ElFormItem>
          <ElFormItem label="售价(元)" prop="payYuan">
            <ElInputNumber v-model="formData.payYuan" :min="0.01" :precision="2" :step="10" />
          </ElFormItem>
          <ElFormItem label="兑换水量(L)" prop="waterLiter">
            <ElInputNumber v-model="formData.waterLiter" :min="0" :step="50" />
            <span class="ml-2 text-xs text-secondary">0 = 纯余额充值套餐</span>
          </ElFormItem>
          <ElFormItem label="赠送余额(元)" prop="bonusYuan">
            <ElInputNumber v-model="formData.bonusYuan" :min="0" :precision="2" :step="5" />
          </ElFormItem>
          <ElFormItem label="折算单价">
            <span class="text-xs text-secondary">{{ unitPricePreview }}</span>
          </ElFormItem>
          <ElFormItem label="有效期(天)" prop="expireDays">
            <ElInputNumber v-model="formData.expireDays" :min="0" :step="30" />
            <span class="ml-2 text-xs text-secondary">0 = 永久</span>
          </ElFormItem>
          <ElFormItem label="使用范围" prop="scopeMode">
            <div class="w-full">
              <ElRadioGroup v-model="formData.scopeMode">
                <ElRadio value="none">不配置</ElRadio>
                <ElRadio value="all">全场通用</ElRadio>
                <ElRadio value="specified">指定范围</ElRadio>
              </ElRadioGroup>
              <div class="text-xs text-secondary">
                不配置则首次购卡不可选此套餐；指定范围至少选一项
              </div>
              <template v-if="formData.scopeMode === 'specified'">
                <ElSelect
                  v-model="formData.scopeStationIds"
                  multiple
                  filterable
                  clearable
                  collapse-tags
                  collapse-tags-tooltip
                  placeholder="限定水站（可多选）"
                  class="mt-2 w-full"
                  :loading="scopeOptionsLoading"
                >
                  <ElOption
                    v-for="opt in stationOptions"
                    :key="opt.value"
                    :label="opt.label"
                    :value="opt.value"
                  />
                </ElSelect>
                <ElSelect
                  v-model="formData.scopeDeviceIds"
                  multiple
                  filterable
                  clearable
                  collapse-tags
                  collapse-tags-tooltip
                  placeholder="限定设备（可多选）"
                  class="mt-2 w-full"
                  :loading="scopeOptionsLoading"
                >
                  <ElOption
                    v-for="opt in deviceOptions"
                    :key="opt.value"
                    :label="opt.label"
                    :value="opt.value"
                  />
                </ElSelect>
                <ElSelect
                  v-model="formData.scopeOutletIds"
                  multiple
                  filterable
                  clearable
                  collapse-tags
                  collapse-tags-tooltip
                  placeholder="限定出水口（可多选）"
                  class="mt-2 w-full"
                  :loading="scopeOptionsLoading"
                >
                  <ElOption
                    v-for="opt in outletOptions"
                    :key="opt.value"
                    :label="opt.label"
                    :value="opt.value"
                  />
                </ElSelect>
                <div class="text-xs text-secondary"> 多项同时配置需同时满足；不选的项不设限 </div>
              </template>
              <ElAlert
                v-if="editingInvalidScope"
                type="warning"
                :closable="false"
                show-icon
                class="mt-2"
                title="当前范围未配置或非法，保存后按本次选择覆盖"
              />
            </div>
          </ElFormItem>
          <ElFormItem v-if="!formData.id" label="初始状态" prop="packageStatus">
            <ElRadioGroup v-model="formData.packageStatus">
              <ElRadio v-for="opt in statusOptions" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </ElRadio>
            </ElRadioGroup>
          </ElFormItem>
          <ElFormItem v-else label="状态">
            <span class="text-xs text-secondary">
              {{ statusLabel(formData.packageStatus) }}（上下架请在列表操作）
            </span>
          </ElFormItem>
          <ElFormItem label="备注" prop="packageRemark">
            <ElInput
              v-model="formData.packageRemark"
              type="textarea"
              :rows="2"
              maxlength="500"
              placeholder="选填"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="submitLoading" @click="handleSubmit">提交</ElButton>
        </template>
      </ElDialog>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    fetchPackagePage,
    fetchAddPackage,
    fetchUpdatePackage,
    fetchShelfPackage,
    type PackageItem,
    type PackageScopeType
  } from '@/api/product'
  import { fetchStationList } from '@/api/station'
  import { fetchDevicePage, fetchOutletListByDevice } from '@/api/device'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'ProductPackage' })

  const userStore = useUserStore()
  const hasPermission = (permission: string): boolean =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === permission)
  const canManagePackage = computed(
    () => hasPermission('product:package:update') || hasPermission('product:package:shelf')
  )

  const loading = ref(false)
  const list = ref<PackageItem[]>([])
  const total = ref(0)
  const pageParams = reactive({ current: 1, size: 20 })
  const searchForm = reactive({
    packageName: '',
    packageStatus: undefined as number | undefined
  })

  const statusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    statusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.套餐状态))
    loadData()
  })

  const statusLabel = (v: number) =>
    statusOptions.value.find((o) => o.value === v)?.label || String(v)

  async function loadData() {
    loading.value = true
    try {
      const res = await fetchPackagePage({
        current: pageParams.current,
        size: pageParams.size,
        packageName: searchForm.packageName || undefined,
        packageStatus: searchForm.packageStatus
      })
      list.value = res.list
      total.value = res.total
    } finally {
      loading.value = false
    }
  }

  const loadDataDebounced = useDebounceFn(() => {
    pageParams.current = 1
    loadData()
  }, 350)

  function handleFilterChange() {
    pageParams.current = 1
    loadData()
  }

  // ==================== 范围选择器数据源（复用现有水站/设备/出水口接口） ====================
  const scopeOptionsLoading = ref(false)
  const stationOptions = ref<{ label: string; value: string }[]>([])
  const deviceOptions = ref<{ label: string; value: string }[]>([])
  const outletOptions = ref<{ label: string; value: string }[]>([])
  let scopeOptionsLoaded = false

  /** 选项 value 统一用字符串 ID：后端 Long 序列化为字符串，避免与回填的范围 ID 类型错配 */
  async function ensureScopeOptions() {
    if (scopeOptionsLoaded || scopeOptionsLoading.value) return
    scopeOptionsLoading.value = true
    try {
      const [stations, devicePage] = await Promise.all([
        fetchStationList(),
        fetchDevicePage({ current: 1, size: 500 })
      ])
      stationOptions.value = (stations ?? []).map((s) => ({
        label: s.stationName,
        value: String(s.id)
      }))
      const devices = devicePage.list ?? []
      deviceOptions.value = devices.map((d) => ({
        label: `${d.deviceName}（${d.deviceNo}）`,
        value: String(d.id)
      }))
      const outletLists = await Promise.all(
        devices.map((d) => fetchOutletListByDevice(String(d.id)))
      )
      outletOptions.value = devices.flatMap((d, i) =>
        (outletLists[i] ?? []).map((o) => ({
          label: `${d.deviceName}·${o.outletNo}号口${o.waterType ? '·' + o.waterType : ''}`,
          value: String(o.id)
        }))
      )
      scopeOptionsLoaded = true
    } finally {
      scopeOptionsLoading.value = false
    }
  }

  // ==================== 表单 ====================
  const dialogVisible = ref(false)
  const submitLoading = ref(false)
  const formRef = ref<FormInstance>()
  const formData = reactive({
    id: undefined as string | undefined,
    packageName: '',
    payYuan: 100,
    waterLiter: 500,
    bonusYuan: 0,
    expireDays: 365,
    packageStatus: 1,
    packageRemark: '',
    scopeMode: 'none' as 'none' | PackageScopeType,
    scopeStationIds: [] as string[],
    scopeDeviceIds: [] as string[],
    scopeOutletIds: [] as string[]
  })
  /** 编辑的套餐范围当前未配置/非法（服务端 scopeValid=false）时给出覆盖提示 */
  const editingInvalidScope = ref(false)

  const rules: FormRules = {
    packageName: [{ required: true, message: '请输入套餐名称', trigger: 'blur' }],
    payYuan: [{ required: true, message: '请输入售价', trigger: 'blur' }]
  }

  /** 展示预估；权威值由服务端派生（分/升，两位小数） */
  const unitPricePreview = computed(() => {
    if (formData.payYuan > 0 && formData.waterLiter > 0) {
      return `约 ${((formData.payYuan * 100) / formData.waterLiter).toFixed(2)} 分/升`
    }
    return '纯余额套餐不计单价'
  })

  function showDialog(row?: PackageItem) {
    const permission = row ? 'product:package:update' : 'product:package:add'
    if (!hasPermission(permission)) {
      ElMessage.warning('当前账号没有对应的套餐操作权限')
      return
    }

    Object.assign(formData, {
      id: row?.id,
      packageName: row?.packageName || '',
      payYuan: row ? row.payAmount / 100 : 100,
      waterLiter: row ? row.waterMl / 1000 : 500,
      bonusYuan: row ? row.bonusAmount / 100 : 0,
      expireDays: row?.expireDays || 0,
      packageStatus: row?.packageStatus || 1,
      packageRemark: row?.packageRemark || '',
      scopeMode: row?.scopeType ?? 'none',
      scopeStationIds: row ? [...row.stationIds] : [],
      scopeDeviceIds: row ? [...row.deviceIds] : [],
      scopeOutletIds: row ? [...row.outletIds] : []
    })
    editingInvalidScope.value = !!row && !row.scopeValid
    ensureScopeOptions()
    dialogVisible.value = true
    nextTick(() => formRef.value?.clearValidate())
  }

  /** 提交时组装范围 JSON（undefined=未配置）；结构由服务端 WaterCardScope 终审 */
  function buildScopeJson(): string | undefined | null {
    if (formData.scopeMode === 'all') {
      return JSON.stringify({ scopeType: 'all' })
    }
    if (formData.scopeMode === 'specified') {
      if (
        !formData.scopeStationIds.length &&
        !formData.scopeDeviceIds.length &&
        !formData.scopeOutletIds.length
      ) {
        ElMessage.warning('指定范围至少选择一个水站、设备或出水口')
        return null
      }
      const scope: Record<string, unknown> = { scopeType: 'specified' }
      if (formData.scopeStationIds.length) scope.stationIds = formData.scopeStationIds
      if (formData.scopeDeviceIds.length) scope.deviceIds = formData.scopeDeviceIds
      if (formData.scopeOutletIds.length) scope.outletIds = formData.scopeOutletIds
      return JSON.stringify(scope)
    }
    return undefined
  }

  async function handleSubmit() {
    if (!formRef.value) return
    const permission = formData.id ? 'product:package:update' : 'product:package:add'
    if (!hasPermission(permission)) {
      ElMessage.warning('当前账号没有对应的套餐操作权限')
      return
    }

    await formRef.value.validate(async (valid) => {
      if (!valid) return
      const scopeJson = buildScopeJson()
      if (scopeJson === null) return
      submitLoading.value = true
      try {
        // 金额传"分"，水量传"毫升"；单价快照不传，由服务端派生
        const payload = {
          id: formData.id,
          packageName: formData.packageName,
          payAmount: Math.round(formData.payYuan * 100),
          waterMl: Math.round(formData.waterLiter * 1000),
          bonusAmount: Math.round(formData.bonusYuan * 100),
          expireDays: formData.expireDays || undefined,
          scopeJson,
          packageStatus: formData.packageStatus,
          packageRemark: formData.packageRemark || undefined
        }
        if (formData.id) {
          await fetchUpdatePackage(payload)
          ElMessage.success('套餐信息已更新')
        } else {
          await fetchAddPackage(payload)
          ElMessage.success('套餐已新增')
        }
        dialogVisible.value = false
        loadData()
      } finally {
        submitLoading.value = false
      }
    })
  }

  /** 上下架：服务端 CAS（状态前置校验），并发冲突会明确报错并要求刷新 */
  function toggleShelf(row: PackageItem) {
    if (!hasPermission('product:package:shelf')) {
      ElMessage.warning('当前账号没有套餐上下架权限')
      return
    }

    const action = row.packageStatus === 1 ? '下架' : '上架'
    ElMessageBox.confirm(`${action}套餐「${row.packageName}」？`, `${action}确认`, {
      confirmButtonText: `确认${action}`,
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      try {
        await fetchShelfPackage(row.id, row.packageStatus === 1 ? 2 : 1)
        ElMessage.success(`套餐已${action}`)
      } catch {
        // http 层已统一提示错误；CAS 前置状态冲突时靠下方刷新回到数据库真实状态
      } finally {
        loadData()
      }
    })
  }
</script>

<style scoped>
  .text-secondary {
    color: var(--el-text-color-secondary);
  }
</style>
