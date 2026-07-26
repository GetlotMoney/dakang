<!-- 水卡管理（一期口径：只读 + 冻结/解冻；开卡/充值/迁移属商业一期 REQ-078） -->
<template>
  <div class="card-page art-full-height">
    <BusinessModuleNav module-key="user" />

    <CardSearch v-model="searchForm" @search="handleSearch" @reset="handleReset"></CardSearch>

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElText type="info" size="small">
            当前支持水卡状态、成员和授权范围核验；开卡/充值/迁移属商业一期并需财务审核
          </ElText>
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

      <!-- 详情抽屉（含授权成员） -->
      <CardDetailDrawer v-model:visible="drawerVisible" :card-id="currentRow.id" />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import { ElMessage, ElMessageBox, ElTag } from 'element-plus'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchCardPage, fetchChangeCardStatus, type CardItem } from '@/api/user'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'
  import CardSearch from './modules/card-search.vue'
  import CardDetailDrawer from './modules/card-detail-drawer.vue'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'

  defineOptions({ name: 'WsUserCard' })

  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  const drawerVisible = ref(false)
  const currentRow = ref<Partial<CardItem>>({})

  const searchForm = ref({
    cardNo: undefined as string | undefined,
    cardType: undefined as number | undefined,
    cardStatus: undefined as number | undefined
  })

  // 字典（页面加载时拉取一次，传给搜索组件与表格翻译共用）
  const cardTypeOptions = ref<{ label: string; value: number }[]>([])
  const cardStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.水卡类型),
      fetchDictOptions(DictTypeEnum.水卡状态)
    ])
    cardTypeOptions.value = toDictOptions(types)
    cardStatusOptions.value = toDictOptions(statuses)
  })

  const cardTypeLabel = (v: number) =>
    cardTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const cardStatusLabel = (v: number) =>
    cardStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const cardStatusTagType = (v: number) =>
    v === 1 ? 'success' : v === 2 ? 'warning' : v === 3 ? 'info' : 'danger'

  const fenToYuan = (fen?: number) => ((fen ?? 0) / 100).toFixed(2)
  const mlToLiter = (ml?: number) => ((ml ?? 0) / 1000).toFixed(1)

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    resetSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchCardPage,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      columnsFactory: () => [
        { prop: 'cardNo', label: '卡号', minWidth: 160 },
        {
          prop: 'cardType',
          label: '类型',
          width: 90,
          formatter: (row: CardItem) =>
            h(ElTag, { type: row.cardType === 1 ? 'primary' : 'warning' }, () =>
              cardTypeLabel(row.cardType)
            )
        },
        {
          prop: 'userName',
          label: '持卡人',
          minWidth: 150,
          formatter: (row: CardItem) =>
            row.userName ? `${row.userName}（${row.userPhone || '-'}）` : `用户#${row.userId}`
        },
        {
          prop: 'balanceAmount',
          label: '余额(元)',
          width: 100,
          formatter: (row: CardItem) => fenToYuan(row.balanceAmount)
        },
        {
          prop: 'balanceMl',
          label: '剩余水量(L)',
          width: 110,
          formatter: (row: CardItem) => mlToLiter(row.balanceMl)
        },
        {
          prop: 'memberCount',
          label: '授权成员',
          width: 90,
          formatter: (row: CardItem) => `${row.memberCount ?? 0} 人`
        },
        {
          prop: 'expireTime',
          label: '到期时间',
          minWidth: 120,
          formatter: (row: CardItem) =>
            row.expireTime && row.expireTime.length === 14
              ? `${row.expireTime.slice(0, 4)}-${row.expireTime.slice(4, 6)}-${row.expireTime.slice(6, 8)}`
              : '永久'
        },
        {
          prop: 'cardStatus',
          label: '状态',
          width: 90,
          formatter: (row: CardItem) =>
            h(ElTag, { type: cardStatusTagType(row.cardStatus) }, () =>
              cardStatusLabel(row.cardStatus)
            )
        },
        {
          prop: 'operation',
          label: '操作',
          width: 160,
          fixed: 'right',
          formatter: (row: CardItem) => {
            const buttons = [
              h(ArtButtonTable, {
                type: 'view',
                onClick: () => showDrawer(row)
              })
            ]
            // 状态机：仅 正常↔冻结 开放手动流转（已过期/已注销为系统终态）
            if (
              hasPermission('user:card:status') &&
              (row.cardStatus === 1 || row.cardStatus === 2)
            ) {
              buttons.push(
                h(
                  ElTag,
                  {
                    type: row.cardStatus === 1 ? 'warning' : 'success',
                    style: 'cursor:pointer;margin-left:6px',
                    onClick: () => changeStatus(row)
                  },
                  () => (row.cardStatus === 1 ? '冻结' : '解冻')
                )
              )
            }
            return h('div', { style: 'display:flex;align-items:center' }, buttons)
          }
        }
      ]
    }
  })

  const handleSearch = (params: Record<string, any>) => {
    replaceSearchParams(params)
    void getData()
  }

  const handleReset = () => {
    resetSearchParams()
  }

  const showDrawer = (row: CardItem): void => {
    currentRow.value = row
    nextTick(() => {
      drawerVisible.value = true
    })
  }

  /** 冻结/解冻（高风险：影响刷卡授权，必填原因入操作日志） */
  const changeStatus = (row: CardItem): void => {
    const freeze = row.cardStatus === 1
    const action = freeze ? '冻结' : '解冻'
    ElMessageBox.prompt(
      `${action}水卡「${row.cardNo}」：${freeze ? '冻结后该卡立即不可取水/刷卡' : '解冻后恢复正常使用'}，请填写${action}原因`,
      `${action}水卡`,
      {
        confirmButtonText: `确认${action}`,
        cancelButtonText: '取消',
        type: 'warning',
        inputPlaceholder: `请输入${action}原因（必填）`,
        inputValidator: (val: string) => (val && val.trim() ? true : `${action}原因不能为空`)
      }
    ).then(async ({ value }) => {
      await fetchChangeCardStatus(row.id, freeze ? 2 : 1, value.trim())
      ElMessage.success(`${action}成功`)
      getData()
    })
  }
</script>
