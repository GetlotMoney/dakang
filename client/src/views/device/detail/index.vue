<!-- 设备中控——设备详情（档案 + 出水口管理 + 遥测/告警/二维码只读，REQ-020/038/039/062/064 骨架） -->
<template>
  <div class="device-detail art-full-height" v-loading="pageLoading">
    <!-- 档案卡 -->
    <ElCard class="mb-3" shadow="never">
      <div class="flex items-center justify-between mb-4">
        <div class="flex items-center gap-3">
          <ElButton @click="router.back()" :icon="ArrowLeft" circle />
          <span class="text-lg font-semibold">
            {{ device.deviceName }}（{{ device.deviceNo }}）
          </span>
          <ElTag :type="onlineTagType(device.onlineStatus!) as any">
            {{ onlineLabel(device.onlineStatus!) }}
          </ElTag>
          <ElTag :type="runTagType(device.runStatus!) as any">
            {{ runLabel(device.runStatus!) }}
          </ElTag>
        </div>
        <ElButton
          v-if="hasPermission('device:command:send')"
          type="primary"
          @click="sendVisible = true"
          v-ripple
          >下发指令</ElButton
        >
      </div>
      <ElDescriptions :column="4" border>
        <ElDescriptionsItem label="设备型号">{{ device.deviceModel || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="所属水站">{{ device.stationName || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="机主">{{ device.ownerUserName || '未绑定' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="固件版本">{{
          device.firmwareVersion || '-'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="最后心跳">{{
          formatTime(device.lastHeartbeat)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="最近故障码">{{
          device.lastFaultCode || '无'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="信号强度">
          {{ device.signalStrength != null ? `${device.signalStrength} dBm` : '-' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="SIM">
          {{ device.simCarrier || '-' }} {{ device.simIccid || '' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="SIM 状态">
          {{ device.simStatus != null ? simStatusLabel(device.simStatus) : '未配置' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="SIM 到期">
          {{ formatTime(device.simExpireTime) }}
        </ElDescriptionsItem>
      </ElDescriptions>
      <div class="order-eligibility">
        <ElAlert
          :type="orderEligibility.allowed ? 'success' : 'error'"
          :closable="false"
          show-icon
          :title="orderEligibility.allowed ? '用户侧下单资格：允许' : '用户侧下单资格：阻断'"
          :description="orderEligibility.description"
        />
      </div>
    </ElCard>

    <!-- 业务区 -->
    <ElCard shadow="never" class="art-table-card">
      <ElTabs v-model="activeTab">
        <!-- 出水口 -->
        <ElTabPane label="出水口" name="outlet">
          <div class="mb-3">
            <ElButton
              v-if="hasPermission('device:device:update')"
              @click="showOutletDialog('add')"
              v-ripple
              >新增出水口</ElButton
            >
          </div>
          <ElTable :data="outlets" row-key="id" border>
            <ElTableColumn prop="outletNo" label="口位" width="80" />
            <ElTableColumn prop="waterType" label="水种" min-width="120" />
            <ElTableColumn label="单价" min-width="110">
              <template #default="{ row }">{{ formatPrice(row.outletPrice) }} 元/升</template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="90">
              <template #default="{ row }">
                <ElTag :type="row.outletStatus === 1 ? 'success' : 'danger'">
                  {{ row.outletStatus === 1 ? '正常' : '禁用' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn v-if="hasPermission('device:device:update')" label="操作" width="140">
              <template #default="{ row }">
                <ElButton link type="primary" @click="showOutletDialog('edit', row)">编辑</ElButton>
                <ElButton link type="danger" @click="handleDeleteOutlet(row)">删除</ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElTabPane>

        <!-- 本机指令（设备详情内的对象级记录，非模块导航） -->
        <ElTabPane label="本机指令" name="command">
          <ElTable :data="commands" row-key="id" border v-loading="commandLoading">
            <ElTableColumn prop="cmdNo" label="指令号" min-width="190" />
            <ElTableColumn label="类型" width="100">
              <template #default="{ row }">{{ cmdTypeLabel(row.cmdType) }}</template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="100">
              <template #default="{ row }">
                <ElTag :type="cmdStatusTagType(row.cmdStatus) as any">
                  {{ cmdStatusLabel(row.cmdStatus) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="下发时间" width="160">
              <template #default="{ row }">{{ formatTime(row.sentTime) }}</template>
            </ElTableColumn>
            <ElTableColumn label="回执时间" width="160">
              <template #default="{ row }">{{ formatTime(row.ackTime) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="failReason" label="失败原因" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.failReason || '-' }}</template>
            </ElTableColumn>
          </ElTable>
          <ElPagination
            class="mt-3 justify-end"
            layout="total, prev, pager, next"
            :total="commandTotal"
            :page-size="commandQuery.size"
            :current-page="commandQuery.current"
            @current-change="onCommandPageChange"
          />
        </ElTabPane>

        <!-- 遥测（最近一条） -->
        <ElTabPane label="水质遥测" name="telemetry">
          <ElEmpty v-if="!telemetry" description="暂无遥测数据" />
          <template v-else>
            <ElDescriptions :column="4" border>
              <ElDescriptionsItem label="出水 TDS">
                {{ telemetry.tdsValue != null ? `${telemetry.tdsValue} ppm` : '-' }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="原水 TDS">
                {{ telemetry.rawTdsValue != null ? `${telemetry.rawTdsValue} ppm` : '-' }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="水温">
                {{ telemetry.waterTemp != null ? `${telemetry.waterTemp} ℃` : '-' }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="上报时间">{{
                formatTime(telemetry.reportTime)
              }}</ElDescriptionsItem>
            </ElDescriptions>
            <div class="mt-4" v-if="filterList.length">
              <div class="mb-2 font-medium">滤芯状态</div>
              <ElTable :data="filterList" border>
                <ElTableColumn prop="no" label="滤芯位" width="90" />
                <ElTableColumn prop="restDay" label="剩余天数" width="110" />
                <ElTableColumn label="状态">
                  <template #default="{ row }">
                    <ElTag
                      :type="row.status === 1 ? 'success' : row.status === 2 ? 'warning' : 'danger'"
                    >
                      {{ filterStatusLabel(row.status) }}
                    </ElTag>
                  </template>
                </ElTableColumn>
              </ElTable>
            </div>
          </template>
        </ElTabPane>

        <!-- 告警（只读提醒，REQ-062 一期口径） -->
        <ElTabPane label="告警记录" name="alarm">
          <ElTable :data="alarms" row-key="id" border v-loading="alarmLoading">
            <ElTableColumn label="类型" width="110">
              <template #default="{ row }">{{ alarmTypeLabel(row.alarmType) }}</template>
            </ElTableColumn>
            <ElTableColumn label="等级" width="90">
              <template #default="{ row }">
                <ElTag
                  :type="
                    row.alarmLevel === 3 ? 'danger' : row.alarmLevel === 2 ? 'warning' : 'info'
                  "
                >
                  {{ alarmLevelLabel(row.alarmLevel) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="alarmContent" label="内容" min-width="240" show-overflow-tooltip />
            <ElTableColumn label="状态" width="100">
              <template #default="{ row }">
                <ElTag :type="row.alarmStatus === 1 ? 'danger' : 'info'">
                  {{ alarmStatusLabel(row.alarmStatus) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="产生时间" width="160">
              <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
            </ElTableColumn>
            <ElTableColumn label="恢复时间" width="160">
              <template #default="{ row }">{{ formatTime(row.recoverTime) }}</template>
            </ElTableColumn>
          </ElTable>
          <ElPagination
            class="mt-3 justify-end"
            layout="total, prev, pager, next"
            :total="alarmTotal"
            :page-size="alarmQuery.size"
            :current-page="alarmQuery.current"
            @current-change="onAlarmPageChange"
          />
        </ElTabPane>

        <!-- 二维码（一期只读，完整管理为商业一期 REQ-064） -->
        <ElTabPane label="二维码" name="qrcode">
          <ElTable :data="qrcodes" row-key="id" border>
            <ElTableColumn
              prop="qrcodeContent"
              label="码内容"
              min-width="240"
              show-overflow-tooltip
            />
            <ElTableColumn label="类型" width="120">
              <template #default="{ row }">
                {{ ['', '新版设备码', '旧版设备码', '万能码'][row.qrcodeType] || row.qrcodeType }}
              </template>
            </ElTableColumn>
            <ElTableColumn label="绑定口位" width="100">
              <template #default="{ row }">{{
                row.outletNo ? `${row.outletNo} 号口` : '整机'
              }}</template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="90">
              <template #default="{ row }">
                <ElTag :type="row.qrcodeStatus === 1 ? 'success' : 'danger'">
                  {{ row.qrcodeStatus === 1 ? '正常' : '禁用' }}
                </ElTag>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElTabPane>
      </ElTabs>
    </ElCard>

    <!-- 出水口弹窗 -->
    <ElDialog
      v-model="outletDialogVisible"
      :title="outletDialogType === 'add' ? '新增出水口' : '编辑出水口'"
      width="480px"
      align-center
    >
      <ElForm ref="outletFormRef" :model="outletForm" :rules="outletRules" label-width="90px">
        <ElFormItem label="口位编号" prop="outletNo">
          <ElInputNumber v-model="outletForm.outletNo" :min="1" :max="99" />
        </ElFormItem>
        <ElFormItem label="水种" prop="waterTypeId">
          <ElSelect v-model="outletForm.waterTypeId" placeholder="仅可选启用水种">
            <ElOption
              v-for="water in waterOptions"
              :key="water.id"
              :label="water.waterName"
              :value="water.id"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="单价" prop="outletPriceYuan">
          <!-- 上限与后端 WaterBillingMath.UNIT_PRICE_MAX_FEN_PER_LITER（100000 分/升）对齐。
               此前这里放到 9999 元/升，运营能填出一个后端读侧必拒的值，保存还提示成功。 -->
          <ElInputNumber
            v-model="outletForm.outletPriceYuan"
            :min="0.01"
            :max="1000"
            :precision="2"
            :step="0.1"
          />
          <span class="ml-2 text-secondary">元/升（上限 1000）</span>
        </ElFormItem>
        <ElFormItem label="状态" prop="outletStatus">
          <ElRadioGroup v-model="outletForm.outletStatus">
            <ElRadio :value="1">正常</ElRadio>
            <ElRadio :value="2">禁用</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <div class="dialog-footer">
          <ElButton @click="outletDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="outletSubmitLoading" @click="handleOutletSubmit"
            >提交</ElButton
          >
        </div>
      </template>
    </ElDialog>

    <!-- 下发指令弹窗 -->
    <CommandSendDialog
      v-model:visible="sendVisible"
      :device-id="device.id"
      :device-no="device.deviceNo"
      :device-name="device.deviceName"
      @submit="loadCommands"
    />
  </div>
</template>

<script setup lang="ts">
  import { useRoute, useRouter } from 'vue-router'
  import { ArrowLeft } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    fetchDeviceDetail,
    fetchOutletListByDevice,
    fetchAddOutlet,
    fetchUpdateOutlet,
    fetchDeleteOutlet,
    fetchCommandPage,
    fetchDeviceAlarmPage,
    fetchDeviceQrcodeList,
    fetchDeviceLatestTelemetry,
    type DeviceItem,
    type OutletItem,
    type CommandItem,
    type AlarmItem,
    type QrcodeItem,
    type TelemetryItem
  } from '@/api/device'
  import { fetchWaterTypeListEnabled, type WaterTypeItem } from '@/api/product'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'
  import CommandSendDialog from '../modules/command-send-dialog.vue'

  defineOptions({ name: 'DeviceDetail' })

  const route = useRoute()
  const router = useRouter()
  const deviceId = computed(() => Number(route.query.id))

  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  const pageLoading = ref(false)
  const activeTab = ref('outlet')
  const sendVisible = ref(false)

  const device = ref<Partial<DeviceItem>>({})
  const outlets = ref<OutletItem[]>([])
  const qrcodes = ref<QrcodeItem[]>([])
  const telemetry = ref<TelemetryItem | null>(null)

  // ==================== 字典 ====================
  const cmdTypeOptions = ref<{ label: string; value: number }[]>([])
  const cmdStatusOptions = ref<{ label: string; value: number }[]>([])
  const alarmTypeOptions = ref<{ label: string; value: number }[]>([])
  const alarmStatusOptions = ref<{ label: string; value: number }[]>([])
  const alarmLevelOptions = ref<{ label: string; value: number }[]>([])
  const filterStatusOptions = ref<{ label: string; value: number }[]>([])
  const simStatusOptions = ref<{ label: string; value: number }[]>([])

  const cmdTypeLabel = (v: number) =>
    cmdTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const cmdStatusLabel = (v: number) =>
    cmdStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const alarmTypeLabel = (v: number) =>
    alarmTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const alarmStatusLabel = (v: number) =>
    alarmStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const alarmLevelLabel = (v: number) =>
    alarmLevelOptions.value.find((o) => o.value === v)?.label || String(v)
  const filterStatusLabel = (v: number) =>
    filterStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const simStatusLabel = (v: number) =>
    simStatusOptions.value.find((o) => o.value === v)?.label || String(v)

  const cmdStatusTagType = (v: number) =>
    v === 4 ? 'success' : v === 5 || v === 6 ? 'danger' : v === 7 ? 'warning' : 'primary'
  const onlineTagType = (v?: number) => (v === 1 ? 'success' : v === 2 ? 'danger' : 'info')
  const onlineLabel = (v?: number) => (v === 1 ? '在线' : v === 2 ? '离线' : '未激活')
  const runTagType = (v?: number) =>
    v === 1 ? 'success' : v === 2 ? 'primary' : v === 3 ? 'danger' : v === 4 ? 'warning' : 'info'
  const runLabel = (v?: number) =>
    ({ 1: '空闲', 2: '出水中', 3: '故障', 4: '维护中', 5: '锁机' })[v ?? 0] || '-'

  /** REQ-028/063：PC 只展示后端 DeviceAvailability 结论，不复制安全判定。 */
  const orderEligibility = computed(() => {
    return {
      allowed: device.value.orderAvailable === true,
      description: device.value.orderAvailabilityReason || ''
    }
  })

  // ==================== 指令（本设备分页） ====================
  const commands = ref<CommandItem[]>([])
  const commandTotal = ref(0)
  const commandLoading = ref(false)
  const commandQuery = reactive({ current: 1, size: 10 })

  async function loadCommands() {
    commandLoading.value = true
    try {
      const res = await fetchCommandPage({ ...commandQuery, deviceId: deviceId.value })
      commands.value = res.list
      commandTotal.value = res.total
    } finally {
      commandLoading.value = false
    }
  }

  function onCommandPageChange(page: number) {
    commandQuery.current = page
    loadCommands()
  }

  // ==================== 告警（本设备分页） ====================
  const alarms = ref<AlarmItem[]>([])
  const alarmTotal = ref(0)
  const alarmLoading = ref(false)
  const alarmQuery = reactive({ current: 1, size: 10 })

  async function loadAlarms() {
    alarmLoading.value = true
    try {
      const res = await fetchDeviceAlarmPage({ ...alarmQuery, deviceId: deviceId.value })
      alarms.value = res.list
      alarmTotal.value = res.total
    } finally {
      alarmLoading.value = false
    }
  }

  function onAlarmPageChange(page: number) {
    alarmQuery.current = page
    loadAlarms()
  }

  // ==================== 出水口 ====================
  const outletDialogVisible = ref(false)
  const outletDialogType = ref<'add' | 'edit'>('add')
  const outletSubmitLoading = ref(false)
  const outletFormRef = ref<FormInstance>()
  const waterOptions = ref<WaterTypeItem[]>([])

  const outletForm = reactive({
    id: undefined as number | undefined,
    outletNo: 1,
    waterTypeId: undefined as number | undefined,
    outletPriceYuan: 0.2,
    outletStatus: 1
  })

  const outletRules: FormRules = {
    outletNo: [{ required: true, message: '请输入口位编号', trigger: 'blur' }],
    waterTypeId: [{ required: true, message: '请选择水种', trigger: 'change' }],
    outletPriceYuan: [{ required: true, message: '请输入单价', trigger: 'blur' }]
  }

  async function showOutletDialog(type: 'add' | 'edit', row?: OutletItem) {
    outletDialogType.value = type
    if (!waterOptions.value.length) {
      waterOptions.value = await fetchWaterTypeListEnabled()
    }
    if (type === 'edit' && row) {
      outletForm.id = row.id
      outletForm.outletNo = row.outletNo
      outletForm.waterTypeId = row.waterTypeId
      outletForm.outletPriceYuan = Number(row.outletPrice) / 100
      outletForm.outletStatus = row.outletStatus
    } else {
      outletForm.id = undefined
      outletForm.outletNo = (outlets.value[outlets.value.length - 1]?.outletNo || 0) + 1
      outletForm.waterTypeId = undefined
      outletForm.outletPriceYuan = 0.2
      outletForm.outletStatus = 1
    }
    outletDialogVisible.value = true
    nextTick(() => outletFormRef.value?.clearValidate())
  }

  async function handleOutletSubmit() {
    await outletFormRef.value?.validate()
    outletSubmitLoading.value = true
    try {
      const payload = {
        id: outletForm.id,
        deviceId: deviceId.value,
        outletNo: outletForm.outletNo,
        waterTypeId: outletForm.waterTypeId!,
        // 元 → 分（整数字符串入库，资金字段不允许小数漂移）
        outletPrice: String(Math.round(outletForm.outletPriceYuan * 100)),
        outletStatus: outletForm.outletStatus
      }
      if (outletDialogType.value === 'add') {
        await fetchAddOutlet(payload)
        ElMessage.success('新增成功')
      } else {
        await fetchUpdateOutlet(payload)
        ElMessage.success('修改成功')
      }
      outletDialogVisible.value = false
      loadOutlets()
    } finally {
      outletSubmitLoading.value = false
    }
  }

  function handleDeleteOutlet(row: OutletItem) {
    ElMessageBox.confirm(`确认删除 ${row.outletNo} 号出水口（${row.waterType}）？`, '删除确认', {
      type: 'warning'
    }).then(async () => {
      await fetchDeleteOutlet(row.id)
      ElMessage.success('删除成功')
      loadOutlets()
    })
  }

  async function loadOutlets() {
    outlets.value = await fetchOutletListByDevice(String(deviceId.value))
  }

  // ==================== 遥测滤芯解析 ====================
  const filterList = computed(() => {
    if (!telemetry.value?.filterLifeJson) return []
    try {
      return JSON.parse(telemetry.value.filterLifeJson) as {
        no: number
        restDay: number
        status: number
      }[]
    } catch {
      return []
    }
  })

  // ==================== 工具 ====================
  function formatTime(time?: string) {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}:${time.slice(12, 14)}`
  }

  function formatPrice(priceFen?: string) {
    if (!priceFen) return '-'
    return (Number(priceFen) / 100).toFixed(2)
  }

  // ==================== 初始化 ====================
  async function initializeDevice() {
    if (!deviceId.value) {
      ElMessage.error('缺少设备参数')
      router.replace('/device/index')
      return
    }
    pageLoading.value = true
    try {
      const [detail, dicts] = await Promise.all([
        fetchDeviceDetail(deviceId.value),
        Promise.all([
          fetchDictOptions(DictTypeEnum.指令类型),
          fetchDictOptions(DictTypeEnum.指令状态),
          fetchDictOptions(DictTypeEnum.告警类型),
          fetchDictOptions(DictTypeEnum.告警状态),
          fetchDictOptions(DictTypeEnum.故障等级),
          fetchDictOptions(DictTypeEnum.滤芯状态),
          fetchDictOptions(DictTypeEnum.SIM状态)
        ])
      ])
      device.value = detail
      cmdTypeOptions.value = toDictOptions(dicts[0])
      cmdStatusOptions.value = toDictOptions(dicts[1])
      alarmTypeOptions.value = toDictOptions(dicts[2])
      alarmStatusOptions.value = toDictOptions(dicts[3])
      alarmLevelOptions.value = toDictOptions(dicts[4])
      filterStatusOptions.value = toDictOptions(dicts[5])
      simStatusOptions.value = toDictOptions(dicts[6])
      // 并行加载各 tab 数据（量小，一次性拉齐避免切 tab 闪加载）
      await Promise.all([
        loadOutlets(),
        loadCommands(),
        loadAlarms(),
        fetchDeviceQrcodeList(deviceId.value).then((list) => (qrcodes.value = list)),
        fetchDeviceLatestTelemetry(deviceId.value).then((t) => (telemetry.value = t))
      ])
    } finally {
      pageLoading.value = false
    }
  }

  onMounted(initializeDevice)

  watch(
    () => route.query.id,
    (nextId, previousId) => {
      if (nextId === previousId) return
      activeTab.value = 'outlet'
      commandQuery.current = 1
      alarmQuery.current = 1
      initializeDevice()
    }
  )
</script>

<style scoped lang="scss">
  .device-detail {
    display: flex;
    flex-direction: column;
    overflow: auto;
  }

  .order-eligibility {
    margin-top: 16px;
  }
</style>
