<!-- 用户详情抽屉：基础信息 + 名下水卡 + 配送员准入（聚合根动线，避免拆散多页跳转） -->
<template>
  <ElDrawer v-model="drawerVisible" title="用户详情" size="560px">
    <div v-loading="loading">
      <ElDescriptions :column="1" border label-width="96px">
        <ElDescriptionsItem label="姓名">{{ detail.userName || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="性别">
          {{ detail.userGender === 1 ? '男' : detail.userGender === 2 ? '女' : '-' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="手机号">{{ detail.userPhone || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="注册推送码">{{ detail.promoCode || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="注册时间">{{
          formatTime(detail.createTime)
        }}</ElDescriptionsItem>
      </ElDescriptions>

      <!-- 名下水卡 -->
      <div class="mt-4 mb-2 font-medium">名下水卡</div>
      <ElTable :data="cards" border v-loading="cardLoading">
        <ElTableColumn prop="cardNo" label="卡号" min-width="150" />
        <ElTableColumn label="类型" width="80">
          <template #default="{ row }">{{ cardTypeLabel(row.cardType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="余额" width="90">
          <template #default="{ row }">{{ fenToYuan(row.balanceAmount) }} 元</template>
        </ElTableColumn>
        <ElTableColumn label="剩余水量" width="100">
          <template #default="{ row }">{{ mlToLiter(row.balanceMl) }} L</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }">
            <ElTag :type="cardStatusTagType(row.cardStatus)">{{
              cardStatusLabel(row.cardStatus)
            }}</ElTag>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-if="!cardLoading && !cards.length" description="暂无水卡" :image-size="60" />

      <!-- 配送员准入 -->
      <div class="mt-4 mb-2 font-medium">配送员准入</div>
      <template v-if="courier">
        <ElDescriptions :column="1" border label-width="96px">
          <ElDescriptionsItem label="配送员姓名">{{ courier.courierName }}</ElDescriptionsItem>
          <ElDescriptionsItem label="联系电话">{{ courier.courierPhone }}</ElDescriptionsItem>
          <ElDescriptionsItem label="准入状态">
            <ElTag :type="courierStatusTagType(courier.courierStatus)">
              {{ courierStatusLabel(courier.courierStatus) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="服务水站">{{
            courier.stationNames || '未配置（默认不可接单）'
          }}</ElDescriptionsItem>
        </ElDescriptions>
      </template>
      <ElEmpty v-else-if="!courierLoading" description="暂无配送员准入记录" :image-size="60" />
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
  import {
    fetchWsUserDetail,
    fetchCardListByUser,
    fetchCourierByUser,
    type WsUserItem,
    type CardItem,
    type CourierItem
  } from '@/api/user'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'

  interface Props {
    visible: boolean
    userId?: number
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
  const cardLoading = ref(false)
  const courierLoading = ref(false)
  const detail = ref<Partial<WsUserItem>>({})
  const cards = ref<CardItem[]>([])
  const courier = ref<CourierItem | null>(null)

  // 字典
  const cardTypeOptions = ref<{ label: string; value: number }[]>([])
  const cardStatusOptions = ref<{ label: string; value: number }[]>([])
  const courierStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses, courierStatuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.水卡类型),
      fetchDictOptions(DictTypeEnum.水卡状态),
      fetchDictOptions(DictTypeEnum.配送员状态)
    ])
    cardTypeOptions.value = toDictOptions(types)
    cardStatusOptions.value = toDictOptions(statuses)
    courierStatusOptions.value = toDictOptions(courierStatuses)
  })

  const cardTypeLabel = (v: number) =>
    cardTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const cardStatusLabel = (v: number) =>
    cardStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const courierStatusLabel = (v: number) =>
    courierStatusOptions.value.find((o) => o.value === v)?.label || String(v)

  const cardStatusTagType = (v: number) =>
    v === 1 ? 'success' : v === 2 ? 'warning' : v === 3 ? 'info' : 'danger'
  const courierStatusTagType = (v: number) =>
    v === 2 ? 'success' : v === 1 ? 'primary' : v === 3 ? 'info' : 'danger'

  const fenToYuan = (fen?: number) => ((fen ?? 0) / 100).toFixed(2)
  const mlToLiter = (ml?: number) => ((ml ?? 0) / 1000).toFixed(1)

  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}:${t.slice(12, 14)}`
  }

  watch(
    () => props.visible,
    async (visible) => {
      if (visible && props.userId) {
        loading.value = true
        cardLoading.value = true
        courierLoading.value = true
        try {
          const [userDetail, cardList, courierIdentity] = await Promise.all([
            fetchWsUserDetail(props.userId),
            fetchCardListByUser(props.userId),
            fetchCourierByUser(props.userId)
          ])
          detail.value = userDetail
          cards.value = cardList || []
          courier.value = courierIdentity
        } finally {
          loading.value = false
          cardLoading.value = false
          courierLoading.value = false
        }
      }
    }
  )
</script>
