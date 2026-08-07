<!-- 配送履约监控（REQ-015/016/059，E2E-03 包C 已接真）：PC 只负责监控、证据核验与申诉协同。 -->
<template>
  <div class="delivery-page art-full-height">
    <BusinessModuleNav module-key="order" />

    <ElCard class="art-table-card" shadow="never">
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <ElRadioGroup v-model="statusFilter" @change="applyFilters">
          <ElRadioButton :value="0">全部</ElRadioButton>
          <ElRadioButton v-for="opt in taskStatusOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </ElRadioButton>
        </ElRadioGroup>
        <ElInput
          v-model="keyword"
          placeholder="订单号/任务号/用户"
          clearable
          style="width: 210px"
          @input="applyFiltersDebounced"
        />
        <ElButton @click="handleReset" v-ripple>重置</ElButton>
      </div>

      <ElTable :data="list" row-key="taskId" border v-loading="loading">
        <ElTableColumn label="配送订单 / 任务号" min-width="200" fixed="left">
          <template #default="{ row }">
            <div>{{ row.orderNo || '-' }}</div>
            <div class="text-xs text-secondary">{{ row.taskNo }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="收货用户" min-width="150">
          <template #default="{ row }">
            {{ row.userName || '-' }}
            <template v-if="row.userMaskedPhone">（{{ row.userMaskedPhone }}）</template>
          </template>
        </ElTableColumn>
        <ElTableColumn label="水种 / 规格 / 数量" min-width="160">
          <template #default="{ row }">
            {{ row.waterTypeName || '-' }} · {{ row.containerSpec || '-' }} ×
            {{ row.deliveryCount ?? '-' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="实签" width="110">
          <template #default="{ row }">
            <template v-if="row.actualDeliveryCount != null">
              {{ row.actualDeliveryCount }} 桶
              <ElTag
                v-if="row.deliveryCount != null && row.actualDeliveryCount < row.deliveryCount"
                type="danger"
                size="small"
              >
                少 {{ row.deliveryCount - row.actualDeliveryCount }}
              </ElTag>
            </template>
            <span v-else class="text-secondary">待签收</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="金额(元)" width="150" align="right">
          <template #default="{ row }">
            <template v-if="row.totalAmountFen != null">
              {{ fenToYuan(row.totalAmountFen) }}
              <!-- D-214：payWay=3 水费以水量抵扣（金额恒 0），标注抵扣而不是渲染 0 元水费 -->
              <div class="text-xs text-secondary">
                <template v-if="row.payWay === 3">
                  水量抵扣单 · 配送费 {{ fenToYuan(row.deliveryFeeFen) }}
                </template>
                <template v-else>
                  水费 {{ fenToYuan(row.waterAmountFen) }} + 配送费
                  {{ fenToYuan(row.deliveryFeeFen) }}
                </template>
              </div>
            </template>
            <template v-else>-</template>
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="receiveAddress"
          label="收水地址"
          min-width="200"
          show-overflow-tooltip
        />
        <ElTableColumn label="配送员" min-width="140">
          <template #default="{ row }">
            <template v-if="row.courierName">
              {{ row.courierName }}
              <template v-if="row.courierMaskedPhone">（{{ row.courierMaskedPhone }}）</template>
            </template>
            <span v-else class="text-secondary">待接单</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="任务状态" width="130">
          <template #default="{ row }">
            <ElTag :type="taskStatusTagType(row.taskStatus)">
              {{ taskStatusLabel(row.taskStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="预约时间" width="140">
          <template #default="{ row }">
            {{ row.scheduledTime ? formatTime(row.scheduledTime) : '即时单' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="签收时间" width="140">
          <template #default="{ row }">{{
            row.signTime ? formatTime(row.signTime) : '-'
          }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <ElButton type="primary" size="small" link @click="showDetail(row)">履约详情</ElButton>
            <ElButton size="small" link @click="goOrder(row)">查看订单</ElButton>
            <ElButton
              v-if="row.taskStatus === 7"
              type="warning"
              size="small"
              link
              @click="goAppeal(row)"
            >
              处理申诉
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

      <ElDrawer v-model="detailVisible" title="配送履约详情" size="620px" destroy-on-close>
        <div v-loading="detailLoading">
          <template v-if="detail">
            <!-- fail-closed：共键/状态矩阵不一致时后端只返回标识与原因，页面如实呈现数据异常 -->
            <ElAlert
              v-if="detail.linkStatus === 'mismatch'"
              type="error"
              :closable="false"
              show-icon
              title="配送履约链数据异常"
              :description="`${detail.linkReason || '任务与订单的数据不一致'}。请人工核查。`"
            />
            <ElDescriptions :column="1" border label-width="92px">
              <ElDescriptionsItem label="配送订单">{{ detail.orderNo || '-' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="任务号">{{ detail.taskNo }}</ElDescriptionsItem>
              <ElDescriptionsItem label="任务状态">
                <ElTag :type="taskStatusTagType(detail.taskStatus)" size="small">
                  {{ taskStatusLabel(detail.taskStatus) }}
                </ElTag>
              </ElDescriptionsItem>
              <template v-if="detail.linkStatus === 'ok'">
                <ElDescriptionsItem label="收货用户">
                  {{ detail.userName || '-' }}
                  <template v-if="detail.userMaskedPhone"
                    >（{{ detail.userMaskedPhone }}）</template
                  >
                </ElDescriptionsItem>
                <ElDescriptionsItem label="收货电话">{{
                  detail.receiveMaskedPhone || '-'
                }}</ElDescriptionsItem>
                <ElDescriptionsItem label="收水地址">{{
                  detail.receiveAddress || '-'
                }}</ElDescriptionsItem>
                <ElDescriptionsItem label="配送内容">
                  {{ detail.waterTypeName || '-' }} · {{ detail.containerSpec || '-' }} ×
                  {{ detail.deliveryCount ?? '-' }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="实际签收">
                  <template v-if="detail.actualDeliveryCount != null">
                    {{ detail.actualDeliveryCount }} 桶（回收
                    {{ detail.actualReturnCount ?? 0 }} 桶）
                  </template>
                  <template v-else>未签收</template>
                </ElDescriptionsItem>
                <ElDescriptionsItem label="价格快照">
                  <template v-if="detail.payWay === 3">
                    水量抵扣单：配送费 {{ fenToYuan(detail.deliveryFeeFen) }} 元 = 应扣
                    {{ fenToYuan(detail.totalAmountFen) }} 元
                  </template>
                  <template v-else>
                    水费 {{ fenToYuan(detail.waterAmountFen) }} 元 + 配送费
                    {{ fenToYuan(detail.deliveryFeeFen) }} 元 =
                    {{ fenToYuan(detail.totalAmountFen) }} 元
                  </template>
                </ElDescriptionsItem>
                <ElDescriptionsItem label="配送员">
                  <template v-if="detail.courierName">
                    {{ detail.courierName }}
                    <template v-if="detail.courierMaskedPhone"
                      >（{{ detail.courierMaskedPhone }}）</template
                    >
                  </template>
                  <template v-else>待接单</template>
                </ElDescriptionsItem>
                <ElDescriptionsItem label="预约时间">
                  {{ detail.scheduledTime ? formatTime(detail.scheduledTime) : '即时单' }}
                </ElDescriptionsItem>
                <ElDescriptionsItem v-if="detail.appealDeadline" label="申诉截止">
                  {{ formatTime(detail.appealDeadline) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem v-if="detail.locationStatus" label="签收定位">
                  {{ detail.locationStatus === 1 ? '定位已记录' : '定位未记录' }}
                </ElDescriptionsItem>
              </template>
            </ElDescriptions>

            <template v-if="detail.linkStatus === 'ok'">
              <div class="section-title">履约节点</div>
              <ElSteps :active="timelineActive" finish-status="success" align-center class="mb-4">
                <ElStep
                  v-for="node in detail.timeline || []"
                  :key="node.node"
                  :title="node.nodeLabel"
                  :description="nodeDescription(node)"
                />
              </ElSteps>

              <div class="section-title">签收三照（门牌 / 水品 / 摆放）</div>
              <template v-if="detail.signPhotos && detail.signPhotos.length">
                <div class="photo-grid">
                  <div v-for="photo in detail.signPhotos" :key="photo.mediaKey" class="photo-card">
                    <div class="photo-card__title">
                      {{ photo.typeLabel || `类型${photo.type}` }}
                      <ElTag :type="photo.mediaStatus === 'ok' ? 'success' : 'danger'" size="small">
                        {{ mediaStatusLabel(photo.mediaStatus) }}
                      </ElTag>
                    </div>
                    <div class="photo-card__meta">拍摄 {{ formatTime(photo.time) }}</div>
                    <div class="photo-card__meta">
                      GPS
                      {{
                        photo.latitude != null && photo.longitude != null
                          ? `${photo.latitude}, ${photo.longitude}`
                          : '未记录'
                      }}
                    </div>
                    <div v-if="photo.mediaStatus === 'ok'" class="photo-card__meta">
                      {{ photo.mimeType }} · {{ formatSize(photo.sizeBytes) }}
                    </div>
                    <div v-else class="photo-card__meta photo-card__meta--danger">
                      {{ photo.mediaReason || '媒体核验未通过' }}
                    </div>
                    <div class="photo-card__key" :title="photo.mediaKey">{{ photo.mediaKey }}</div>
                  </div>
                </div>
              </template>
              <ElEmpty v-else description="未签收，暂无三照记录" :image-size="60" />

              <div class="section-title">配送异常记录</div>
              <ElTable
                v-if="detail.exceptions && detail.exceptions.length"
                :data="detail.exceptions"
                border
                size="small"
              >
                <ElTableColumn prop="reasonLabel" label="异常原因" width="130" />
                <ElTableColumn
                  prop="description"
                  label="异常说明"
                  min-width="220"
                  show-overflow-tooltip
                />
                <ElTableColumn label="举证" width="90">
                  <template #default="{ row }">{{ row.evidenceRefs.length }} 张</template>
                </ElTableColumn>
                <ElTableColumn label="上报时间" width="150">
                  <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
                </ElTableColumn>
              </ElTable>
              <ElEmpty v-else description="无配送异常上报" :image-size="50" />
            </template>

            <div class="mt-4 flex justify-end gap-2">
              <ElButton @click="goOrderFromDetail">查看关联订单</ElButton>
              <ElButton v-if="detail.taskStatus === 7" type="warning" @click="goAppealFromDetail">
                进入申诉处理
              </ElButton>
            </div>
          </template>
        </div>
      </ElDrawer>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    fetchDeliveryTaskPage,
    fetchDeliveryTaskDetail,
    type DeliveryTaskAdminItem,
    type DeliveryTaskAdminDetail,
    type DeliveryTimelineNode,
    type AdminMediaRef
  } from '@/api/order'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'

  defineOptions({ name: 'OrderDelivery' })

  const route = useRoute()
  const router = useRouter()

  const readQueryValue = (value: unknown): string => {
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' ? raw : ''
  }
  const readQueryNumber = (value: unknown): number => {
    const parsed = Number(readQueryValue(value))
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
  }

  const loading = ref(false)
  const list = ref<DeliveryTaskAdminItem[]>([])
  const total = ref(0)
  const pageParams = reactive({ current: 1, size: 20 })
  let applyingFilters = false
  const statusFilter = ref(readQueryNumber(route.query.taskStatus))
  const keyword = ref(readQueryValue(route.query.keyword))
  const detailVisible = ref(false)
  const detailLoading = ref(false)
  const detail = ref<DeliveryTaskAdminDetail | null>(null)

  const taskStatusOptions = ref<{ label: string; value: number }[]>([
    { label: '待接单', value: 1 },
    { label: '已接单', value: 2 },
    { label: '配送中', value: 3 },
    { label: '已送达待确认', value: 4 },
    { label: '已签收', value: 5 },
    { label: '已取消', value: 6 },
    { label: '申诉中', value: 7 }
  ])

  onMounted(async () => {
    try {
      const remoteOptions = toDictOptions(await fetchDictOptions(DictTypeEnum.配送任务状态))
      if (remoteOptions.length) taskStatusOptions.value = remoteOptions
    } catch {
      // 字典接口暂不可用时保留本地兜底选项，列表仍可查询。
    }
    await loadData()
  })

  watch(
    () => route.fullPath,
    () => {
      if (route.path !== '/order/delivery') return
      statusFilter.value = readQueryNumber(route.query.taskStatus)
      keyword.value = readQueryValue(route.query.keyword)
      pageParams.current = 1
      if (applyingFilters) return
      loadData()
    }
  )

  const taskStatusLabel = (value: number) =>
    taskStatusOptions.value.find((item) => item.value === value)?.label || String(value)
  const taskStatusTagType = (value: number) =>
    value === 5
      ? 'success'
      : value === 7
        ? 'danger'
        : value === 6
          ? 'info'
          : value === 1
            ? 'warning'
            : 'primary'
  const formatTime = (time?: string) => {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
  }
  const formatSize = (bytes?: number) => {
    if (bytes == null || !Number.isFinite(bytes)) return '-'
    if (bytes < 1024) return `${bytes}B`
    return `${(bytes / 1024).toFixed(0)}KB`
  }
  const mediaStatusLabel = (status: AdminMediaRef['mediaStatus']) =>
    status === 'ok' ? '媒体已核验' : status === 'missing' ? '登记缺失' : '核验不符'
  const nodeDescription = (node: DeliveryTimelineNode) => {
    const time = node.time ? formatTime(node.time) : ''
    return node.detail ? `${time} ${node.detail}`.trim() : time
  }

  /** done 数即已完成节点数：由服务端按落库时间判定，页面不自行推导状态。 */
  const timelineActive = computed(
    () => (detail.value?.timeline || []).filter((node) => node.done).length
  )

  async function loadData() {
    loading.value = true
    try {
      const result = await fetchDeliveryTaskPage({
        current: pageParams.current,
        size: pageParams.size,
        taskStatus: statusFilter.value || undefined,
        keyword: keyword.value || undefined
      })
      list.value = result.list
      total.value = result.total
    } finally {
      loading.value = false
    }
  }

  async function applyFilters() {
    pageParams.current = 1
    const query = {
      ...(statusFilter.value ? { taskStatus: String(statusFilter.value) } : {}),
      ...(keyword.value ? { keyword: keyword.value } : {})
    }
    const target = router.resolve({ path: route.path, query }).fullPath
    if (target === route.fullPath) return loadData()
    applyingFilters = true
    try {
      await router.replace({ path: route.path, query })
      await nextTick()
      await loadData()
    } finally {
      applyingFilters = false
    }
  }

  const applyFiltersDebounced = useDebounceFn(() => applyFilters(), 350)

  async function handleReset() {
    statusFilter.value = 0
    keyword.value = ''
    await applyFilters()
  }

  async function showDetail(row: DeliveryTaskAdminItem) {
    detail.value = null
    detailVisible.value = true
    detailLoading.value = true
    try {
      detail.value = await fetchDeliveryTaskDetail(row.taskId)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载履约详情失败')
    } finally {
      detailLoading.value = false
    }
  }

  function openOrderTrace(orderId?: string, orderNo?: string) {
    if (!orderId) {
      ElMessage.warning('该任务未关联有效订单，无法打开追溯')
      return
    }
    // 真实数据源：追溯走管理端 /order/order/trace，不再进入 Mock 分流
    router.push({
      path: '/order/index',
      query: { ...(orderNo ? { orderNo } : {}), traceOrderId: orderId }
    })
  }

  function goOrder(row: DeliveryTaskAdminItem) {
    openOrderTrace(row.orderId, row.orderNo)
  }

  function goOrderFromDetail() {
    if (!detail.value) return
    detailVisible.value = false
    openOrderTrace(detail.value.orderId, detail.value.orderNo)
  }

  function goAppeal(row: DeliveryTaskAdminItem) {
    router.push({ path: '/order/appeal', query: row.orderNo ? { orderNo: row.orderNo } : {} })
  }

  function goAppealFromDetail() {
    if (!detail.value) return
    detailVisible.value = false
    goAppeal(detail.value)
  }
</script>

<style scoped>
  .section-title {
    display: flex;
    align-items: center;
    margin: 18px 0 10px;
    font-size: 14px;
    font-weight: 600;
  }

  .section-title::before {
    width: 3px;
    height: 14px;
    margin-right: 8px;
    content: '';
    background: var(--el-color-primary);
    border-radius: 2px;
  }

  .text-secondary {
    color: var(--el-text-color-secondary);
  }

  .photo-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
    gap: 12px;
  }

  .photo-card {
    padding: 10px 12px;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .photo-card__title {
    display: flex;
    gap: 6px;
    align-items: center;
    justify-content: space-between;
    font-size: 13px;
    font-weight: 600;
  }

  .photo-card__meta {
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .photo-card__meta--danger {
    color: var(--el-color-danger);
  }

  .photo-card__key {
    margin-top: 6px;
    overflow: hidden;
    font-size: 11px;
    color: var(--el-text-color-placeholder);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
