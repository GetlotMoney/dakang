<!-- 水卡详情抽屉：卡信息 + 套餐快照 + 授权成员（一卡多人 REQ-009） -->
<template>
  <ElDrawer v-model="drawerVisible" title="水卡详情" size="560px">
    <div v-loading="loading">
      <ElDescriptions :column="1" border label-width="96px">
        <ElDescriptionsItem label="卡号">{{ detail.cardNo || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="卡类型">{{
          detail.cardType ? cardTypeLabel(detail.cardType) : '-'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          <ElTag v-if="detail.cardStatus" :type="cardStatusTagType(detail.cardStatus)">
            {{ cardStatusLabel(detail.cardStatus) }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="持卡人">
          {{ detail.userName ? `${detail.userName}（${detail.userPhone || '-'}）` : '-' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="余额"
          >{{ fenToYuan(detail.balanceAmount) }} 元</ElDescriptionsItem
        >
        <ElDescriptionsItem label="剩余水量"
          >{{ mlToLiter(detail.balanceMl) }} L</ElDescriptionsItem
        >
        <ElDescriptionsItem label="到期时间">
          {{ detail.expireTime ? formatTime(detail.expireTime) : '永久' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="套餐快照">
          <template v-if="detail.packageSnapshotState === 'valid' && detail.packageSnapshot">
            {{ detail.packageSnapshot.packageName }}（售价
            {{ fenToYuan(detail.packageSnapshot.payAmountFen) }} 元 /
            {{ mlToLiter(detail.packageSnapshot.waterMl) }} L）
          </template>
          <ElTag
            v-else-if="detail.packageSnapshotState === 'invalid'"
            type="danger"
            size="small"
            effect="plain"
          >
            数据异常
          </ElTag>
          <template v-else>未记录</template>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="授权范围">
          <ElTag v-if="scopeContract.scopeType === 'all'" type="success" size="small">
            全场通用
          </ElTag>
          <ElTag v-else-if="scopeContract.scopeType === 'unconfigured'" type="warning" size="small">
            未配置，不可用卡
          </ElTag>
          <template v-else>{{ scopeContract.scopeText }}</template>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="备注">{{ detail.cardRemark || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="创建时间">{{
          formatTime(detail.createTime)
        }}</ElDescriptionsItem>
      </ElDescriptions>

      <div class="mt-4 mb-2 font-medium">授权成员（消费计入本卡）</div>
      <ElTable :data="detail.memberList || []" border>
        <ElTableColumn label="成员" min-width="140">
          <template #default="{ row }">
            {{ row.memberUserName || `用户#${row.memberUserId}` }}
            <span v-if="row.memberName" class="text-gray-400">（{{ row.memberName }}）</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="手机号" width="130">
          <template #default="{ row }">{{ row.memberUserPhone || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="单日限额" width="100">
          <template #default="{ row }">
            {{ row.dayLimitMl ? `${mlToLiter(row.dayLimitMl)} L` : '不限' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }">
            <ElTag :type="row.memberStatus === 1 ? 'success' : 'info'">
              {{ memberStatusLabel(row.memberStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty
        v-if="!loading && !(detail.memberList || []).length"
        description="暂无授权成员"
        :image-size="60"
      />
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { fetchCardDetail, type CardItem } from '@/api/user'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'

  interface Props {
    visible: boolean
    cardId?: number
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
  }

  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()

  const drawerVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const loading = ref(false)
  const detail = ref<Partial<CardItem>>({})

  const cardTypeOptions = ref<{ label: string; value: number }[]>([])
  const cardStatusOptions = ref<{ label: string; value: number }[]>([])
  const memberStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses, memberStatuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.水卡类型),
      fetchDictOptions(DictTypeEnum.水卡状态),
      fetchDictOptions(DictTypeEnum.成员授权状态)
    ])
    cardTypeOptions.value = toDictOptions(types)
    cardStatusOptions.value = toDictOptions(statuses)
    memberStatusOptions.value = toDictOptions(memberStatuses)
  })

  const cardTypeLabel = (v: number) =>
    cardTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const cardStatusLabel = (v: number) =>
    cardStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const memberStatusLabel = (v: number) =>
    memberStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const cardStatusTagType = (v: number) =>
    v === 1 ? 'success' : v === 2 ? 'warning' : v === 3 ? 'info' : 'danger'

  const fenToYuan = (fen?: number) => ((fen ?? 0) / 100).toFixed(2)
  const mlToLiter = (ml?: number) => ((ml ?? 0) / 1000).toFixed(1)

  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}:${t.slice(12, 14)}`
  }

  /** 授权范围合同：空值必须 fail-closed；只有 scopeType=all 才表示全场。 */
  const scopeContract = computed(() => {
    if (!detail.value.scopeJson) {
      return { scopeType: 'unconfigured', scopeText: '未配置授权范围，不可用卡' }
    }
    try {
      const scope = JSON.parse(detail.value.scopeJson) as {
        scopeType?: string
        stationIds?: number[]
        deviceIds?: number[]
        outletIds?: number[]
        stationNames?: string[]
        deviceNames?: string[]
        outletLabels?: string[]
      }
      if (scope.scopeType === 'all') {
        return { scopeType: 'all', scopeText: '全场通用' }
      }
      const parts = [
        scope.stationIds?.length
          ? `水站 ${scope.stationNames?.join('、') || scope.stationIds.join('、')}`
          : '',
        scope.deviceIds?.length
          ? `设备 ${scope.deviceNames?.join('、') || scope.deviceIds.join('、')}`
          : '',
        scope.outletIds?.length
          ? `出水口 ${scope.outletLabels?.join('、') || scope.outletIds.join('、')}`
          : ''
      ].filter(Boolean)
      return {
        scopeType: scope.scopeType || 'specified',
        scopeText: parts.join('；') || '范围为空，不可用卡'
      }
    } catch {
      return { scopeType: 'invalid', scopeText: '范围配置异常，不可用卡' }
    }
  })

  watch(
    () => props.visible,
    async (visible) => {
      if (visible && props.cardId) {
        loading.value = true
        try {
          detail.value = await fetchCardDetail(props.cardId)
        } finally {
          loading.value = false
        }
      }
    }
  )
</script>
