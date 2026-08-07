<!-- 水卡管理（一期口径：只读 + 冻结/解冻；开卡/充值/迁移属商业一期 REQ-078） -->
<template>
  <div class="card-page art-full-height">
    <BusinessModuleNav module-key="user" />

    <CardSearch v-model="searchForm" @search="handleSearch" @reset="handleReset"></CardSearch>

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElButton
            v-if="hasPermission('user:card:issue')"
            type="success"
            size="small"
            @click="giftVisible = true"
            >赠卡发放</ElButton
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

      <!-- 详情抽屉（含授权成员） -->
      <CardDetailDrawer v-model:visible="drawerVisible" :card-id="currentRow.id" />

      <!-- 赠卡发放（E2E-08：D-213 口径带有效期不可充值；请求号幂等） -->
      <ElDialog v-model="giftVisible" title="运营赠卡发放" width="480px">
        <ElAlert
          type="warning"
          :closable="false"
          show-icon
          title="赠卡带有效期、不可充值，不占用户「一人一卡」名额"
          class="mb-3"
        />
        <ElForm label-width="100px">
          <ElFormItem label="收卡用户ID" required>
            <ElInput v-model="giftForm.userId" placeholder="请输入用户ID" />
          </ElFormItem>
          <ElFormItem label="赠送水量(L)">
            <ElInputNumber v-model="giftForm.liters" :min="0" :precision="1" style="width: 100%" />
          </ElFormItem>
          <ElFormItem label="赠送余额(元)">
            <ElInputNumber v-model="giftForm.yuan" :min="0" :precision="2" style="width: 100%" />
          </ElFormItem>
          <ElFormItem label="有效期(天)" required>
            <ElInputNumber v-model="giftForm.expireDays" :min="1" :max="3650" style="width: 100%" />
          </ElFormItem>
          <ElFormItem label="备注">
            <ElInput v-model="giftForm.remark" maxlength="100" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="giftVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="giftBusy" @click="submitGift">确认发放</ElButton>
        </template>
      </ElDialog>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h, watch } from 'vue'
  import {
    ElAlert,
    ElButton,
    ElDialog,
    ElForm,
    ElFormItem,
    ElInput,
    ElInputNumber,
    ElMessage,
    ElMessageBox,
    ElTag
  } from 'element-plus'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchCardPage, fetchChangeCardStatus, fetchGiftIssue, type CardItem } from '@/api/user'
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
      `${action}水卡「${row.cardNo}」${freeze ? '，冻结后立即不可取水' : ''}，请填写${action}原因`,
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

  // -------------------------------------------------------------------------
  // 赠卡发放（E2E-08）：请求号在对话框打开期间持有，提交重试不翻倍发卡
  // -------------------------------------------------------------------------
  const giftVisible = ref(false)
  const giftBusy = ref(false)
  const giftForm = ref<{
    userId?: string
    liters?: number
    yuan?: number
    expireDays?: number
    remark?: string
  }>({
    expireDays: 30
  })
  let giftRequestId = ''

  watch(giftVisible, (open) => {
    if (open) {
      giftRequestId = crypto.randomUUID()
    }
  })

  const submitGift = async () => {
    const form = giftForm.value
    const userId = String(form.userId ?? '').trim()
    if (!/^\d+$/.test(userId)) {
      ElMessage.warning('收卡用户ID必须是数字')
      return
    }
    const grantMl = Math.round((form.liters ?? 0) * 1000)
    const grantFen = Math.round((form.yuan ?? 0) * 100)
    if (grantMl <= 0 && grantFen <= 0) {
      ElMessage.warning('水量或余额至少填一项')
      return
    }
    if (!form.expireDays) {
      ElMessage.warning('有效期必填')
      return
    }
    giftBusy.value = true
    try {
      await fetchGiftIssue({
        requestId: giftRequestId,
        userId: Number(userId),
        grantMl,
        grantFen,
        expireDays: form.expireDays,
        remark: form.remark
      })
      ElMessage.success('赠卡已发放')
      giftVisible.value = false
      giftForm.value = { expireDays: 30 }
      refreshData()
    } finally {
      giftBusy.value = false
    }
  }
</script>
