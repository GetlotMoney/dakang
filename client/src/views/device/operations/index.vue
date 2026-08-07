<!-- 设备运营配置（REQ-063）：PC 侧运营视图与控制边界说明。
     E2E-05 收敛：原「展示策略」内存 Mock 假保存与「公告投放」假操作已删除——
     展示口径的唯一权威是后端 DeviceAvailability 判定与协议阈值（本页只读陈述）；
     公告触达属于 E2E-07 通用消息中心，不在本页提供半成品入口。 -->
<template>
  <div class="operations-page">
    <BusinessModuleNav module-key="device" />

    <ElRow :gutter="16">
      <ElCol :xs="24" :xl="15">
        <ElCard shadow="never" class="section-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">水站位置与设备分布</div>
                <div class="card-subtitle">缺少坐标的水站需先在水站管理补齐</div>
              </div>
            </div>
          </template>

          <div v-loading="stationLoading" class="map-layout">
            <div class="map-board" aria-label="水站位置分布">
              <div class="map-grid"></div>
              <div
                v-for="(station, index) in locatedStations"
                :key="station.id"
                class="map-marker"
                :style="markerStyle(station)"
              >
                <span>{{ index + 1 }}</span>
                <div class="map-marker__label">{{ station.stationName }}</div>
              </div>
              <ElEmpty
                v-if="!stationLoading && locatedStations.length === 0"
                description="暂无有效坐标"
                :image-size="64"
              />
            </div>

            <ElTable :data="stations" border size="small" max-height="320">
              <ElTableColumn type="index" width="52" />
              <ElTableColumn prop="stationName" label="水站" min-width="150" />
              <ElTableColumn label="坐标" min-width="145">
                <template #default="{ row }">
                  <span v-if="row.stationLng && row.stationLat">
                    {{ row.stationLng }}, {{ row.stationLat }}
                  </span>
                  <ElTag v-else type="warning" size="small">待补齐</ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn label="状态" width="80">
                <template #default="{ row }">
                  <ElTag :type="row.stationStatus === 1 ? 'success' : 'info'" size="small">
                    {{ row.stationStatus === 1 ? '正常' : '禁用' }}
                  </ElTag>
                </template>
              </ElTableColumn>
            </ElTable>
          </div>
        </ElCard>
      </ElCol>

      <ElCol :xs="24" :xl="9">
        <ElCard shadow="never" class="section-card">
          <template #header>
            <div class="card-header">
              <div class="card-title">用户端状态展示规则</div>
            </div>
          </template>
          <ElDescriptions :column="1" border size="small">
            <ElDescriptionsItem label="心跳离线阈值">90 秒</ElDescriptionsItem>
            <ElDescriptionsItem label="阻断条件">
              离线、严重故障、未知故障、维护中、锁定、出水口停用
            </ElDescriptionsItem>
          </ElDescriptions>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElRow :gutter="16">
      <ElCol :span="24">
        <ElCard shadow="never" class="section-card">
          <template #header>
            <div class="card-header">
              <div class="card-title">远程控制安全边界</div>
            </div>
          </template>
          <ElTable :data="commandPolicies" border>
            <ElTableColumn prop="command" label="指令" min-width="140" />
            <ElTableColumn prop="scope" label="允许范围" min-width="140" />
            <ElTableColumn prop="verification" label="确认要求" min-width="170" />
            <ElTableColumn label="当前状态" width="130">
              <template #default="{ row }">
                <ElTag :type="row.availability === '当前可下发' ? 'success' : 'info'" size="small">
                  {{ row.availability }}
                </ElTag>
              </template>
            </ElTableColumn>
          </ElTable>
          <div class="mt-3 flex justify-end gap-2">
            <ElButton @click="router.push('/device/command')">单设备指令与记录</ElButton>
            <ElButton type="primary" @click="router.push('/device/batch')">批量控制</ElButton>
          </div>
        </ElCard>
      </ElCol>
    </ElRow>
  </div>
</template>

<script setup lang="ts">
  import { fetchStationList, type StationItem } from '@/api/station'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'

  defineOptions({ name: 'DeviceOperations' })

  const router = useRouter()

  const stationLoading = ref(false)
  const stations = ref<StationItem[]>([])
  const locatedStations = computed(() =>
    stations.value.filter((item) => {
      const longitude = Number(item.stationLng)
      const latitude = Number(item.stationLat)
      return Number.isFinite(longitude) && Number.isFinite(latitude)
    })
  )

  /** 边界口径与后端一致：出水类只能由订单链路触发；批量/紧急停止/价格同步走两步确认。 */
  const commandPolicies = [
    {
      command: '查询/参数同步/重启',
      scope: '单设备或批量',
      verification: '操作权限；批量需两步确认',
      availability: '当前可下发'
    },
    {
      command: '锁机/解锁',
      scope: '单设备或批量',
      verification: '操作权限 + 二次确认',
      availability: '当前可下发'
    },
    {
      command: '价格同步',
      scope: '单设备/水站/全部（批量）',
      verification: '两步确认',
      availability: '当前可下发'
    },
    {
      command: '紧急停止',
      scope: '仅单设备',
      verification: '两步确认；需有进行中的出水订单',
      availability: '当前可下发'
    },
    {
      command: '开始出水',
      scope: '禁止人工下发',
      verification: '由用户下单自动触发',
      availability: '禁止'
    }
  ]

  const coordinateBounds = computed(() => {
    const longitudes = locatedStations.value.map((item) => Number(item.stationLng))
    const latitudes = locatedStations.value.map((item) => Number(item.stationLat))
    return {
      minLng: Math.min(...longitudes),
      maxLng: Math.max(...longitudes),
      minLat: Math.min(...latitudes),
      maxLat: Math.max(...latitudes)
    }
  })

  /** 不依赖地图服务时，按真实经纬度在面板内做等比例归一化；纬度越大位置越靠上。 */
  const markerStyle = (station: StationItem) => {
    const { minLng, maxLng, minLat, maxLat } = coordinateBounds.value
    const longitude = Number(station.stationLng)
    const latitude = Number(station.stationLat)
    const xRatio = maxLng === minLng ? 0.5 : (longitude - minLng) / (maxLng - minLng)
    const yRatio = maxLat === minLat ? 0.5 : (maxLat - latitude) / (maxLat - minLat)
    return {
      left: `${12 + xRatio * 76}%`,
      top: `${14 + yRatio * 68}%`
    }
  }

  onMounted(async () => {
    stationLoading.value = true
    try {
      stations.value = await fetchStationList()
    } finally {
      stationLoading.value = false
    }
  })
</script>

<style scoped lang="scss">
  .operations-page {
    display: flex;
    flex-direction: column;

    .section-card {
      margin-bottom: 16px;
    }

    .card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .card-title {
      font-size: 15px;
      font-weight: 600;
    }

    .card-subtitle {
      margin-top: 2px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    .map-layout {
      display: grid;
      grid-template-columns: 1fr;
      gap: 12px;

      @media (width >= 1200px) {
        grid-template-columns: 1.1fr 1fr;
      }
    }

    .map-board {
      position: relative;
      min-height: 280px;
      overflow: hidden;
      background: var(--el-fill-color-lighter);
      border: 1px solid var(--el-border-color-lighter);
      border-radius: 8px;
    }

    .map-grid {
      position: absolute;
      inset: 0;
      background-image:
        linear-gradient(var(--el-border-color-lighter) 1px, transparent 1px),
        linear-gradient(90deg, var(--el-border-color-lighter) 1px, transparent 1px);
      background-size: 32px 32px;
      opacity: 0.6;
    }

    .map-marker {
      position: absolute;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 26px;
      height: 26px;
      font-size: 12px;
      font-weight: 600;
      color: #fff;
      background: var(--el-color-primary);
      border-radius: 50% 50% 50% 0;
      transform: translate(-50%, -100%) rotate(-45deg);

      span {
        transform: rotate(45deg);
      }

      &__label {
        position: absolute;
        top: 30px;
        left: 50%;
        width: max-content;
        max-width: 120px;
        padding: 1px 6px;
        font-size: 11px;
        font-weight: 400;
        color: var(--el-text-color-regular);
        background: var(--el-bg-color);
        border: 1px solid var(--el-border-color-lighter);
        border-radius: 4px;
        transform: translateX(-50%) rotate(45deg);
        transform-origin: top left;
      }
    }
  }
</style>
