<!-- 设备运营配置（REQ-063）：PC 维护配置与控制边界，不模拟用户扫码或设备执行。 -->
<template>
  <div class="operations-page">
    <BusinessModuleNav module-key="device" />

    <ElAlert
      class="mb-3"
      type="info"
      :closable="false"
      show-icon
      title="本页是 PC 侧配置与状态契约"
      description="设备心跳、遥测、ACK 和 result 必须由设备端上报；用户扫码前的展示与拦截必须由小程序按这里的契约读取，PC 不提供模拟执行按钮。"
    />

    <ElRow :gutter="16">
      <ElCol :xs="24" :xl="15">
        <ElCard shadow="never" class="section-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">水站位置与设备分布</div>
                <div class="card-subtitle"
                  >按档案经纬度展示相对分布；缺少坐标时必须先回水站管理补齐</div
                >
              </div>
              <ElTag type="success" effect="plain">真实水站数据</ElTag>
            </div>
          </template>

          <div v-loading="stationLoading" class="map-layout">
            <div class="map-board" aria-label="水站经纬度相对分布（非地图服务）">
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
              <div>
                <div class="card-title">用户端状态展示契约</div>
                <div class="card-subtitle"
                  >PC 可保存策略；小程序按版本读取，设备状态仍以上报为准</div
                >
              </div>
              <ElTag type="primary" effect="plain">Demo 可配置</ElTag>
            </div>
          </template>
          <ElForm label-position="top" v-loading="policyLoading">
            <div class="switch-grid">
              <ElFormItem label="展示在线状态">
                <ElSwitch v-model="policy.showOnlineStatus" />
              </ElFormItem>
              <ElFormItem label="展示水质 TDS">
                <ElSwitch v-model="policy.showTds" />
              </ElFormItem>
              <ElFormItem label="展示滤芯寿命">
                <ElSwitch v-model="policy.showFilterLife" />
              </ElFormItem>
            </div>
            <ElFormItem label="心跳离线阈值">
              <ElInputNumber v-model="policy.heartbeatTimeoutSeconds" :min="30" :max="600" />
              <span class="form-unit">秒（协议默认 90 秒）</span>
            </ElFormItem>
            <ElFormItem label="不可用设备下单入口">
              <ElRadioGroup v-model="policy.unavailableAction">
                <ElRadio value="disable_order">保留入口并禁用，展示原因</ElRadio>
                <ElRadio value="hide_order">隐藏入口</ElRadio>
              </ElRadioGroup>
            </ElFormItem>
            <ElFormItem label="修改原因" required>
              <ElInput
                v-model="policy.updateReason"
                maxlength="120"
                show-word-limit
                placeholder="策略变更原因必须留痕"
              />
            </ElFormItem>
            <div class="form-actions">
              <span class="card-subtitle">最近保存：{{ formatTime(policy.updateTime) }}</span>
              <ElButton
                v-if="hasPermission('device:operations:config')"
                type="primary"
                :loading="policySaving"
                @click="savePolicy"
              >
                保存策略
              </ElButton>
            </div>
          </ElForm>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElRow :gutter="16">
      <ElCol :xs="24" :lg="12">
        <ElCard shadow="never" class="section-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">公告投放契约</div>
                <div class="card-subtitle">PC 保存投放版本；跨端同步状态单独呈现</div>
              </div>
              <ElButton
                v-if="hasPermission('device:operations:config')"
                type="primary"
                @click="openAnnouncementDialog"
              >
                新建公告
              </ElButton>
            </div>
          </template>
          <ElTable :data="announcements" border v-loading="announcementLoading">
            <ElTableColumn prop="title" label="公告" min-width="180" show-overflow-tooltip />
            <ElTableColumn prop="scopeText" label="投放范围" min-width="130" />
            <ElTableColumn prop="version" label="配置版本" min-width="145" />
            <ElTableColumn label="PC 状态" width="90">
              <template #default="{ row }">
                <ElTag :type="row.pcStatus === '生效中' ? 'success' : 'warning'" size="small">
                  {{ row.pcStatus }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="跨端状态" width="110">
              <template #default="{ row }">
                <ElTag :type="row.clientSyncStatus === '已同步' ? 'success' : 'info'" size="small">
                  {{ row.clientSyncStatus }}
                </ElTag>
              </template>
            </ElTableColumn>
          </ElTable>
          <ElAlert
            class="mt-3"
            type="info"
            :closable="false"
            title="PC 生效不等于小程序已同步；只有收到跨端版本回执后才能显示“已同步”。"
          />
        </ElCard>
      </ElCol>

      <ElCol :xs="24" :lg="12">
        <ElCard shadow="never" class="section-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">远程控制安全边界</div>
                <div class="card-subtitle">PC 只创建指令意图，执行结果以设备 ACK/result 为准</div>
              </div>
              <ElTag type="danger" effect="plain">高风险受控</ElTag>
            </div>
          </template>
          <ElTable :data="commandPolicies" border>
            <ElTableColumn prop="command" label="指令" min-width="120" />
            <ElTableColumn prop="scope" label="允许范围" min-width="130" />
            <ElTableColumn prop="verification" label="确认要求" min-width="150" />
            <ElTableColumn prop="terminalState" label="终态证据" min-width="130" />
            <ElTableColumn label="当前状态" width="110">
              <template #default="{ row }">
                <ElTag :type="row.availability === '当前可下发' ? 'success' : 'info'" size="small">
                  {{ row.availability }}
                </ElTag>
              </template>
            </ElTableColumn>
          </ElTable>
          <div class="mt-3 flex justify-end">
            <ElButton type="primary" @click="router.push('/device/command')">
              进入指令记录与下发
            </ElButton>
          </div>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElDialog v-model="announcementVisible" title="新建用户端公告" width="600px" align-center>
      <ElAlert
        class="mb-4"
        type="info"
        :closable="false"
        title="保存后只生成 PC 配置版本，跨端读取与展示仍需小程序回执。"
      />
      <ElForm label-width="92px">
        <ElFormItem label="公告标题" required>
          <ElInput v-model="announcementForm.title" maxlength="40" show-word-limit />
        </ElFormItem>
        <ElFormItem label="公告正文" required>
          <ElInput
            v-model="announcementForm.content"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
          />
        </ElFormItem>
        <ElFormItem label="投放范围" required>
          <ElSelect v-model="announcementForm.scopeType" style="width: 150px">
            <ElOption label="全部水站" value="all" />
            <ElOption label="指定水站" value="station" />
            <ElOption label="指定设备" value="device" />
          </ElSelect>
          <ElInput
            v-model="announcementForm.scopeText"
            class="ml-2 flex-1"
            placeholder="填写可追溯的范围名称快照"
          />
        </ElFormItem>
        <ElFormItem label="生效时间" required>
          <ElInput v-model="announcementForm.startTime" placeholder="yyyyMMddHHmmss" />
        </ElFormItem>
        <ElFormItem label="失效时间" required>
          <ElInput v-model="announcementForm.endTime" placeholder="yyyyMMddHHmmss" />
        </ElFormItem>
        <ElFormItem label="发布原因" required>
          <ElInput v-model="announcementForm.publishReason" maxlength="120" show-word-limit />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="announcementVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="announcementSaving" @click="createAnnouncement">
          保存配置版本
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    fetchCreateDeviceAnnouncement,
    fetchDeviceAnnouncementList,
    fetchDeviceDisplayPolicy,
    fetchSaveDeviceDisplayPolicy,
    type DeviceAnnouncementForm,
    type DeviceAnnouncementItem,
    type DeviceDisplayPolicy
  } from '@/api/device'
  import { fetchStationList, type StationItem } from '@/api/station'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'DeviceOperations' })

  const router = useRouter()
  const userStore = useUserStore()
  const hasPermission = (permission: string) =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === permission)

  const stationLoading = ref(false)
  const stations = ref<StationItem[]>([])
  const locatedStations = computed(() =>
    stations.value.filter((item) => {
      const longitude = Number(item.stationLng)
      const latitude = Number(item.stationLat)
      return Number.isFinite(longitude) && Number.isFinite(latitude)
    })
  )

  const policyLoading = ref(false)
  const policySaving = ref(false)
  const policy = reactive<DeviceDisplayPolicy>({
    showOnlineStatus: true,
    showTds: true,
    showFilterLife: true,
    heartbeatTimeoutSeconds: 90,
    unavailableAction: 'disable_order',
    updateReason: '',
    updateTime: ''
  })

  const announcements = ref<DeviceAnnouncementItem[]>([])
  const announcementLoading = ref(false)
  const announcementSaving = ref(false)
  const announcementVisible = ref(false)
  const announcementForm = reactive<DeviceAnnouncementForm>({
    title: '',
    content: '',
    scopeType: 'all',
    scopeText: '全部水站',
    startTime: '20260715080000',
    endTime: '20260715200000',
    publishReason: ''
  })

  const commandPolicies = [
    {
      command: '查询/参数同步/重启',
      scope: '仅单设备',
      verification: '操作权限 + 参数校验',
      terminalState: 'ACK/result + 状态记录',
      availability: '当前可下发'
    },
    {
      command: '锁机',
      scope: '仅单设备',
      verification: '操作权限 + 显式二次确认',
      terminalState: 'ACK/result + 运行状态',
      availability: '当前可下发'
    },
    {
      command: '解锁',
      scope: '仅单设备',
      verification: '操作权限',
      terminalState: 'ACK/result + 运行状态',
      availability: '当前可下发'
    },
    {
      command: '价格同步/紧急停机',
      scope: '单设备/设备组/全部',
      verification: '协议确认 + 范围鉴权 + 二次验证',
      terminalState: '待协议确定',
      availability: '待协议确认'
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

  const formatTime = (time?: string) => {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
  }

  async function loadPolicy() {
    policyLoading.value = true
    try {
      Object.assign(policy, await fetchDeviceDisplayPolicy())
    } finally {
      policyLoading.value = false
    }
  }

  async function savePolicy() {
    if (!policy.updateReason.trim()) {
      ElMessage.warning('请填写策略修改原因')
      return
    }
    policySaving.value = true
    try {
      Object.assign(
        policy,
        await fetchSaveDeviceDisplayPolicy({
          showOnlineStatus: policy.showOnlineStatus,
          showTds: policy.showTds,
          showFilterLife: policy.showFilterLife,
          heartbeatTimeoutSeconds: policy.heartbeatTimeoutSeconds,
          unavailableAction: policy.unavailableAction,
          updateReason: policy.updateReason.trim()
        })
      )
      ElMessage.success('策略版本已保存，等待小程序读取')
    } finally {
      policySaving.value = false
    }
  }

  async function loadAnnouncements() {
    announcementLoading.value = true
    try {
      announcements.value = await fetchDeviceAnnouncementList()
    } finally {
      announcementLoading.value = false
    }
  }

  function openAnnouncementDialog() {
    Object.assign(announcementForm, {
      title: '',
      content: '',
      scopeType: 'all',
      scopeText: '全部水站',
      startTime: '20260715080000',
      endTime: '20260715200000',
      publishReason: ''
    })
    announcementVisible.value = true
  }

  async function createAnnouncement() {
    if (
      !announcementForm.title.trim() ||
      !announcementForm.content.trim() ||
      !announcementForm.scopeText.trim() ||
      !announcementForm.startTime.trim() ||
      !announcementForm.endTime.trim() ||
      !announcementForm.publishReason.trim()
    ) {
      ElMessage.warning('请完整填写公告、范围、时间和发布原因')
      return
    }
    if (announcementForm.startTime >= announcementForm.endTime) {
      ElMessage.warning('失效时间必须晚于生效时间')
      return
    }
    announcementSaving.value = true
    try {
      await fetchCreateDeviceAnnouncement({ ...announcementForm })
      announcementVisible.value = false
      await loadAnnouncements()
      ElMessage.success('公告配置版本已保存，跨端状态仍为待同步')
    } finally {
      announcementSaving.value = false
    }
  }

  onMounted(async () => {
    stationLoading.value = true
    try {
      const [stationList] = await Promise.all([
        fetchStationList(),
        loadPolicy(),
        loadAnnouncements()
      ])
      stations.value = stationList
    } finally {
      stationLoading.value = false
    }
  })
</script>

<style scoped lang="scss">
  .operations-page {
    padding-bottom: 16px;
  }

  .section-card {
    margin-bottom: 16px;
  }

  .card-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
  }

  .card-title {
    font-size: 16px;
    font-weight: 600;
  }

  .card-subtitle {
    margin-top: 4px;
    color: var(--art-text-gray-600);
    font-size: 13px;
  }

  .switch-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
  }

  .switch-grid :deep(.el-form-item) {
    padding: 12px;
    margin-bottom: 12px;
    border: 1px solid var(--art-border-color);
    border-radius: 8px;
  }

  .form-unit {
    margin-left: 8px;
    color: var(--art-text-gray-600);
  }

  .form-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .map-layout {
    display: grid;
    grid-template-columns: minmax(280px, 1fr) minmax(360px, 1.15fr);
    gap: 16px;
  }

  .map-board {
    position: relative;
    min-height: 320px;
    overflow: hidden;
    border: 1px solid var(--art-border-color);
    border-radius: 10px;
    background: linear-gradient(145deg, rgb(236 248 255), rgb(242 250 247));
  }

  .map-grid {
    position: absolute;
    inset: 0;
    opacity: 0.45;
    background-image:
      linear-gradient(rgb(88 141 255 / 14%) 1px, transparent 1px),
      linear-gradient(90deg, rgb(88 141 255 / 14%) 1px, transparent 1px);
    background-size: 42px 42px;
  }

  .map-marker {
    position: absolute;
    z-index: 1;
    width: 30px;
    height: 30px;
    border: 3px solid #fff;
    border-radius: 50%;
    background: var(--el-color-primary);
    color: #fff;
    line-height: 24px;
    text-align: center;
    box-shadow: 0 6px 14px rgb(64 116 255 / 28%);
  }

  .map-marker__label {
    position: absolute;
    top: 32px;
    left: 50%;
    width: max-content;
    max-width: 140px;
    transform: translateX(-50%);
    color: var(--art-text-gray-900);
    font-size: 12px;
    line-height: 18px;
  }

  @media (max-width: 1100px) {
    .map-layout {
      grid-template-columns: 1fr;
    }

    .switch-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
