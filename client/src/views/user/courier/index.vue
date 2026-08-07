<!-- 配送员审核（准入状态机 REQ-079：待审核→启用/驳回，启用↔停用；未过审不能接单） -->
<template>
  <div class="courier-page art-full-height">
    <BusinessModuleNav module-key="user" />

    <CourierSearch v-model="searchForm" @search="handleSearch" @reset="handleReset"></CourierSearch>

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElButton v-if="hasPermission('user:courier:add')" @click="dialogVisible = true" v-ripple
            >新增配送员</ElButton
          >
        </template>
      </ArtTableHeader>

      <ArtTable
        row-key="id"
        :loading="loading"
        :columns="columns"
        :data="data"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>

      <!-- 人工创建弹窗 -->
      <CourierAddDialog v-model:visible="dialogVisible" @submit="refreshData" />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import { ElMessage, ElMessageBox, ElTag, ElButton } from 'element-plus'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchCourierPage, fetchAuditCourier, type CourierItem } from '@/api/user'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'
  import CourierSearch from './modules/courier-search.vue'
  import CourierAddDialog from './modules/courier-add-dialog.vue'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'

  defineOptions({ name: 'WsUserCourier' })

  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  const dialogVisible = ref(false)

  const route = useRoute()
  const router = useRouter()

  const readQueryValue = (value: unknown): string | undefined => {
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' && raw.trim() ? raw : undefined
  }

  const readQueryNumber = (value: unknown): number | undefined => {
    const parsed = Number(readQueryValue(value))
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
  }

  interface CourierSearchForm {
    courierName?: string
    courierPhone?: string
    courierStatus?: number
  }

  const searchForm = ref<CourierSearchForm>({
    courierName: readQueryValue(route.query.courierName),
    courierPhone: readQueryValue(route.query.courierPhone),
    // 支持总览待办卡片带参直达（?courierStatus=1 待审核）
    courierStatus: readQueryNumber(route.query.courierStatus)
  })

  const courierStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    courierStatusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.配送员状态))
  })

  const statusLabel = (v: number) =>
    courierStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const statusTagType = (v: number) =>
    v === 2 ? 'success' : v === 1 ? 'primary' : v === 3 ? 'info' : 'danger'

  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}`
  }

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
      apiFn: fetchCourierPage,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      columnsFactory: () => [
        { prop: 'courierName', label: '姓名', minWidth: 110 },
        { prop: 'courierPhone', label: '联系电话', minWidth: 130 },
        {
          prop: 'userName',
          label: '关联用户',
          minWidth: 110,
          formatter: (row: CourierItem) => row.userName || `用户#${row.userId}`
        },
        {
          prop: 'stationNames',
          label: '服务水站',
          minWidth: 160,
          showOverflowTooltip: true,
          formatter: (row: CourierItem) => row.stationNames || '未配置，不可接单'
        },
        {
          prop: 'serviceRegion',
          label: '服务区域',
          minWidth: 130,
          formatter: (row: CourierItem) => row.serviceRegion || '-'
        },
        {
          prop: 'courierStatus',
          label: '准入状态',
          width: 100,
          formatter: (row: CourierItem) =>
            h(ElTag, { type: statusTagType(row.courierStatus) }, () =>
              statusLabel(row.courierStatus)
            )
        },
        {
          prop: 'auditRemark',
          label: '审核备注',
          minWidth: 140,
          showOverflowTooltip: true,
          formatter: (row: CourierItem) => row.auditRemark || '-'
        },
        {
          prop: 'createTime',
          label: '提交时间',
          minWidth: 140,
          formatter: (row: CourierItem) => formatTime(row.createTime)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 190,
          fixed: 'right',
          formatter: (row: CourierItem) => {
            if (!hasPermission('user:courier:audit')) return h('span', '-')
            const buttons: ReturnType<typeof h>[] = []
            // 状态机动作按当前状态渲染（与后端 checkTransition 严格一致）
            if (row.courierStatus === 1) {
              buttons.push(
                h(
                  ElButton,
                  { type: 'success', size: 'small', onClick: () => doAudit(row, 2, '通过') },
                  () => '通过'
                ),
                h(
                  ElButton,
                  { type: 'danger', size: 'small', onClick: () => doAudit(row, 4, '驳回') },
                  () => '驳回'
                )
              )
            } else if (row.courierStatus === 2) {
              buttons.push(
                h(
                  ElButton,
                  { type: 'warning', size: 'small', onClick: () => doAudit(row, 3, '停用') },
                  () => '停用'
                )
              )
            } else {
              // 3停用/4驳回 → 恢复启用（驳回复审通过）
              buttons.push(
                h(
                  ElButton,
                  { type: 'success', size: 'small', onClick: () => doAudit(row, 2, '恢复启用') },
                  () => '恢复启用'
                )
              )
            }
            return h('div', buttons)
          }
        }
      ]
    }
  })

  watch(
    () => route.fullPath,
    () => {
      if (route.path !== '/user/courier') return
      const query = {
        courierName: readQueryValue(route.query.courierName),
        courierPhone: readQueryValue(route.query.courierPhone),
        courierStatus: readQueryNumber(route.query.courierStatus)
      }
      searchForm.value = query
      replaceSearchParams(query)
      void getData()
    }
  )

  const handleSearch = (params: CourierSearchForm) => {
    replaceSearchParams(params)
    void getData()
  }

  const handleReset = async () => {
    searchForm.value = {}
    replaceSearchParams({})
    if (Object.keys(route.query).length) {
      await router.replace({ path: route.path, query: {} })
    } else {
      await getData()
    }
  }

  /** 审核动作：驳回/停用必填备注（后端强校验，前端同步拦截） */
  const doAudit = (row: CourierItem, targetStatus: number, action: string): void => {
    const remarkRequired = targetStatus === 3 || targetStatus === 4
    if (remarkRequired) {
      ElMessageBox.prompt(
        `${action}配送员「${row.courierName}」，请填写${action}原因（将展示给配送员）`,
        `${action}确认`,
        {
          confirmButtonText: `确认${action}`,
          cancelButtonText: '取消',
          type: 'warning',
          inputPlaceholder: `请输入${action}原因（必填）`,
          inputValidator: (val: string) => (val && val.trim() ? true : `${action}原因不能为空`)
        }
      ).then(async ({ value }) => {
        await fetchAuditCourier(row.id, targetStatus, value.trim())
        ElMessage.success(`${action}成功`)
        getData()
      })
    } else {
      ElMessageBox.confirm(
        `确认${action}配送员「${row.courierName}」？通过后可接单。`,
        `${action}确认`,
        {
          confirmButtonText: `确认${action}`,
          cancelButtonText: '取消',
          type: 'info'
        }
      ).then(async () => {
        await fetchAuditCourier(row.id, targetStatus)
        ElMessage.success(`${action}成功`)
        getData()
      })
    }
  }
</script>
