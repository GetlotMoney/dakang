<template>
  <ElDrawer v-model="drawerVisible" title="水站详情" size="420px">
    <div v-loading="loading">
      <ElDescriptions :column="1" border label-width="96px">
        <ElDescriptionsItem label="水站名称">{{ detail.stationName || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="水站编码">{{ detail.stationCode || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          <ElTag :type="detail.stationStatus === 1 ? 'success' : 'danger'">
            {{ detail.stationStatus === 1 ? '正常' : '禁用' }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="所属区域">{{ detail.stationRegion || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="详细地址">{{ detail.stationAddress || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="经纬度">
          {{
            detail.stationLng && detail.stationLat
              ? `${detail.stationLng}, ${detail.stationLat}`
              : '-'
          }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="机主">
          {{
            detail.ownerUserName
              ? `${detail.ownerUserName}（${detail.ownerUserPhone || '-'}）`
              : '未绑定'
          }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="站内设备数">
          <ElLink type="primary" :underline="false" @click="goDeviceList">
            {{ detail.deviceCount ?? 0 }} 台
          </ElLink>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="备注">{{ detail.stationRemark || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="创建时间">{{
          formatTime(detail.createTime)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="更新时间">{{
          formatTime(detail.updateTime)
        }}</ElDescriptionsItem>
      </ElDescriptions>
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { fetchStationDetail, type StationItem } from '@/api/station'

  interface Props {
    visible: boolean
    stationId?: number
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
  }

  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()
  const router = useRouter()

  const drawerVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const loading = ref(false)
  const detail = ref<Partial<StationItem>>({})

  /** varchar(14) 时间串 → 展示格式 */
  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}:${t.slice(12, 14)}`
  }

  watch(
    () => props.visible,
    async (visible) => {
      if (visible && props.stationId) {
        loading.value = true
        try {
          detail.value = await fetchStationDetail(props.stationId)
        } finally {
          loading.value = false
        }
      }
    }
  )

  /** 跳设备中控列表并按本站过滤 */
  const goDeviceList = () => {
    if (!props.stationId) return
    router.push({ path: '/device/index', query: { stationId: String(props.stationId) } })
  }
</script>
