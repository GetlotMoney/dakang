<!-- 申诉处理（REQ-017，E2E-03 包C 已接真）：PC 核验证据后登记裁决，事务由后端包A 收口。 -->
<template>
  <div class="appeal-page art-full-height">
    <BusinessModuleNav module-key="order" />

    <ElAlert
      class="mb-3"
      type="info"
      :closable="false"
      show-icon
      title="申诉由用户端在签收后 24 小时内发起"
      description="裁决结果只有三种：不成立驳回 / 补送待执行 / 成立待补偿。资金补偿仅登记待处理，真实退款不在本页发生；照片以受控媒体元数据呈现。"
    />

    <ElCard class="art-table-card" shadow="never">
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <ElRadioGroup v-model="statusFilter" @change="applyFilters">
          <ElRadioButton :value="0">全部</ElRadioButton>
          <ElRadioButton v-for="opt in appealStatusOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </ElRadioButton>
        </ElRadioGroup>
        <ElInput
          v-model="orderKeyword"
          placeholder="关联订单号"
          clearable
          style="width: 210px"
          @input="applyFiltersDebounced"
        />
        <ElButton @click="handleReset" v-ripple>重置</ElButton>
        <ElTag type="success" effect="plain" class="ml-auto">申诉数据来自真实后端</ElTag>
      </div>

      <ElTable :data="list" row-key="appealId" border v-loading="loading">
        <ElTableColumn label="关联订单 / 任务号" min-width="200" fixed="left">
          <template #default="{ row }">
            <div>{{ row.orderNo || '-' }}</div>
            <div class="text-xs text-secondary">{{ row.taskNo || '-' }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="申诉用户" min-width="150">
          <template #default="{ row }">
            {{ row.userName || '-' }}
            <template v-if="row.userMaskedPhone">（{{ row.userMaskedPhone }}）</template>
          </template>
        </ElTableColumn>
        <ElTableColumn label="申诉原因" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <ElTag size="small" effect="plain">{{
              row.appealReasonLabel || row.appealReason || '-'
            }}</ElTag>
            <span v-if="row.appealDesc" class="ml-1">{{ row.appealDesc }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="实收" width="80">
          <template #default="{ row }">
            {{ row.receivedCount != null ? `${row.receivedCount} 桶` : '-' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag :type="statusTagType(row.appealStatus)">{{
              statusLabel(row.appealStatus)
            }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="申诉时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </ElTableColumn>
        <ElTableColumn label="处理人 / 结果" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.handleResult">
              {{ row.handleByName || '-' }}：{{ row.handleResult }}
            </template>
            <template v-else>-</template>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <ElButton type="primary" size="small" link @click="showEvidence(row)">
              {{
                row.appealStatus === 1 && hasPermission('order:appeal:handle')
                  ? '查看并裁决'
                  : '查看证据'
              }}
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
    </ElCard>

    <ElDrawer v-model="evidenceVisible" title="申诉证据与配送履约" size="720px" destroy-on-close>
      <div v-loading="evidenceLoading">
        <template v-if="evidence">
          <ElDescriptions :column="2" border label-width="96px">
            <ElDescriptionsItem label="关联订单">{{
              evidence.appeal.orderNo || '-'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="配送任务">{{
              evidence.appeal.taskNo || '-'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="申诉用户">
              {{ evidence.appeal.userName || '-' }}（{{ evidence.appeal.userMaskedPhone || '-' }}）
            </ElDescriptionsItem>
            <ElDescriptionsItem label="申诉状态">
              <ElTag :type="statusTagType(evidence.appeal.appealStatus)" size="small">
                {{ statusLabel(evidence.appeal.appealStatus) }}
              </ElTag>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="申诉原因">
              {{ evidence.appeal.appealReasonLabel || evidence.appeal.appealReason || '-' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="用户实收">
              {{
                evidence.appeal.receivedCount != null ? `${evidence.appeal.receivedCount} 桶` : '-'
              }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="申诉时间">{{
              formatTime(evidence.appeal.createTime)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="处理人">{{
              evidence.appeal.handleByName || '待处理'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="申诉说明" :span="2">{{
              evidence.appeal.appealDesc || '-'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem v-if="evidence.appeal.handleResult" label="处理结果" :span="2">
              {{ evidence.appeal.handleResult }}
            </ElDescriptionsItem>
          </ElDescriptions>

          <!-- fail-closed：申诉-任务-订单共键错位时不展示任务与举证，如实呈现数据异常 -->
          <ElAlert
            v-if="evidence.linkStatus === 'mismatch'"
            class="mt-3"
            type="error"
            :closable="false"
            show-icon
            title="申诉关联数据异常"
            :description="`${evidence.linkReason || '申诉与任务/订单共键核验未通过'}。已隐藏关联任务与举证媒体，禁止在异常数据上裁决，请人工核查。`"
          />

          <template v-else>
            <div class="section-title">用户申诉举证（受控媒体元数据）</div>
            <div v-if="evidence.appealPhotos && evidence.appealPhotos.length" class="media-grid">
              <MediaRefCard v-for="ref in evidence.appealPhotos" :key="ref.mediaKey" :media="ref" />
            </div>
            <ElEmpty v-else description="用户未提交申诉举证" :image-size="56" />

            <div class="section-title">配送员举证</div>
            <template v-if="evidence.courierEvidences && evidence.courierEvidences.length">
              <div
                v-for="(item, index) in evidence.courierEvidences"
                :key="index"
                class="courier-evidence"
              >
                <div class="flex items-center justify-between">
                  <span class="font-medium">举证 {{ index + 1 }}</span>
                  <span class="text-xs text-secondary">{{ formatTime(item.time) }}</span>
                </div>
                <div class="mt-1 text-sm">{{ item.description || '-' }}</div>
                <div v-if="item.evidenceRefs.length" class="media-grid mt-2">
                  <MediaRefCard v-for="ref in item.evidenceRefs" :key="ref.mediaKey" :media="ref" />
                </div>
              </div>
            </template>
            <ElEmpty v-else description="配送员暂未追加举证" :image-size="56" />

            <template v-if="evidence.task">
              <div class="section-title section-title--between">
                <span>关联配送任务</span>
                <ElButton
                  v-if="evidence.task.orderId"
                  type="primary"
                  size="small"
                  link
                  @click="openOrderTrace"
                >
                  进入订单全链路追溯
                </ElButton>
              </div>
              <ElAlert
                v-if="evidence.task.linkStatus === 'mismatch'"
                type="error"
                :closable="false"
                show-icon
                title="履约任务数据异常"
                :description="`${evidence.task.linkReason || '任务共键核验未通过'}。已隐藏履约正向证据。`"
              />
              <template v-else>
                <ElDescriptions :column="2" border label-width="96px">
                  <ElDescriptionsItem label="任务号">{{ evidence.task.taskNo }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="任务状态">{{
                    taskStatusLabel(evidence.task.taskStatus)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="配送员">
                    {{ evidence.task.courierName || '未分配' }}
                    <template v-if="evidence.task.courierMaskedPhone"
                      >（{{ evidence.task.courierMaskedPhone }}）</template
                    >
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="水品 / 数量">
                    {{ evidence.task.waterTypeName || '-' }} ·
                    {{ evidence.task.containerSpec || '-' }} ×
                    {{ evidence.task.deliveryCount ?? '-' }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="实际签收">
                    <template v-if="evidence.task.actualDeliveryCount != null">
                      {{ evidence.task.actualDeliveryCount }} 桶
                      <ElTag
                        v-if="
                          evidence.task.deliveryCount != null &&
                          evidence.task.actualDeliveryCount < evidence.task.deliveryCount
                        "
                        type="danger"
                        size="small"
                      >
                        少
                        {{ evidence.task.deliveryCount - evidence.task.actualDeliveryCount }} 桶
                      </ElTag>
                    </template>
                    <template v-else>未形成签收数量</template>
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="价格快照">
                    水费 {{ fenToYuan(evidence.task.waterAmountFen) }} 元 + 配送费
                    {{ fenToYuan(evidence.task.deliveryFeeFen) }} 元
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="签收时间">{{
                    formatTime(evidence.task.signTime)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="申诉截止">{{
                    formatTime(evidence.task.appealDeadline)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="收货地址" :span="2">{{
                    evidence.task.receiveAddress || '-'
                  }}</ElDescriptionsItem>
                </ElDescriptions>

                <div class="section-title">配送签收三照元数据</div>
                <div
                  v-if="evidence.task.signPhotos && evidence.task.signPhotos.length"
                  class="media-grid"
                >
                  <div
                    v-for="photo in evidence.task.signPhotos"
                    :key="photo.mediaKey"
                    class="media-card"
                  >
                    <div class="media-card__title">
                      {{ photo.typeLabel || `类型${photo.type}` }}
                      <ElTag :type="photo.mediaStatus === 'ok' ? 'success' : 'danger'" size="small">
                        {{ mediaStatusLabel(photo.mediaStatus) }}
                      </ElTag>
                    </div>
                    <div class="media-card__meta">拍摄 {{ formatTime(photo.time) }}</div>
                    <div class="media-card__meta">
                      GPS
                      {{
                        photo.latitude != null && photo.longitude != null
                          ? `${photo.latitude}, ${photo.longitude}`
                          : '未记录'
                      }}
                    </div>
                    <div v-if="photo.mediaStatus === 'ok'" class="media-card__meta">
                      {{ photo.mimeType }} · {{ formatSize(photo.sizeBytes) }}
                    </div>
                    <div v-else class="media-card__meta media-card__meta--danger">
                      {{ photo.mediaReason || '媒体核验未通过' }}
                    </div>
                    <div class="media-card__key" :title="photo.mediaKey">{{ photo.mediaKey }}</div>
                  </div>
                </div>
                <ElEmpty v-else description="当前任务没有签收三照" :image-size="56" />
              </template>
            </template>
          </template>

          <div
            v-if="
              evidence.appeal.appealStatus === 1 &&
              evidence.linkStatus === 'ok' &&
              hasPermission('order:appeal:handle')
            "
            class="decision-bar"
          >
            <div>
              <div class="font-medium">确认已完成证据核验</div>
              <div class="text-xs text-secondary mt-1">
                裁决写入后端申诉状态机并通知用户；资金补偿仅登记待处理，本页不发生退款。
              </div>
            </div>
            <ElButton type="primary" @click="openDecision(evidence.appeal)">登记裁决</ElButton>
          </div>
        </template>
      </div>
    </ElDrawer>

    <ElDialog v-model="decisionVisible" title="申诉裁决登记" width="540px" align-center>
      <ElAlert
        class="mb-4"
        type="warning"
        :closable="false"
        show-icon
        title="裁决只有三种确定结果，均不产生退款成功"
        description="不成立驳回=任务归档；补送待执行=登记补送（后续端执行）；成立待补偿=资金进入待处理（退款审批属后续链路）。"
      />
      <ElForm label-width="96px">
        <ElFormItem label="关联订单">
          <ElInput :model-value="decisionTarget?.orderNo || '-'" disabled />
        </ElFormItem>
        <ElFormItem label="裁决结果" required>
          <!-- 与后端包A 白名单一字不差：只允许 3 / 5 / 2，无其他伪造终态 -->
          <ElRadioGroup v-model="decisionForm.outcome">
            <ElRadio :value="3">不成立驳回</ElRadio>
            <ElRadio :value="5">补送待执行</ElRadio>
            <ElRadio :value="2">成立待补偿</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem label="裁决依据" required>
          <ElInput
            v-model="decisionForm.handleResult"
            type="textarea"
            :rows="4"
            maxlength="300"
            show-word-limit
            placeholder="请填写照片、时间、GPS、数量等核验依据"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="decisionVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="decisionSubmitting" @click="submitDecision">
          确认登记
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { defineComponent, h, type PropType } from 'vue'
  import { ElMessage, ElTag } from 'element-plus'
  import {
    fetchAppealEvidence,
    fetchAppealPage,
    fetchDecideAppeal,
    type AdminMediaRef,
    type AppealAdminEvidence,
    type AppealAdminItem
  } from '@/api/order'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'OrderAppeal' })

  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()
  const hasPermission = (permission: string): boolean =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === permission)

  const readQueryValue = (value: unknown): string => {
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' ? raw : ''
  }
  const readQueryNumber = (value: unknown): number => {
    const parsed = Number(readQueryValue(value))
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
  }

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

  /** 受控媒体元数据卡片（页内局部组件：一期无媒体下载出口，只展示核验元数据）。 */
  const MediaRefCard = defineComponent({
    name: 'MediaRefCard',
    props: { media: { type: Object as PropType<AdminMediaRef>, required: true } },
    setup(props) {
      return () =>
        h('div', { class: 'media-card' }, [
          h('div', { class: 'media-card__title' }, [
            h('span', '举证媒体'),
            h(
              ElTag,
              { type: props.media.mediaStatus === 'ok' ? 'success' : 'danger', size: 'small' },
              { default: () => mediaStatusLabel(props.media.mediaStatus) }
            )
          ]),
          h(
            'div',
            { class: 'media-card__meta' },
            props.media.mediaStatus === 'ok'
              ? `${props.media.mimeType || '-'} · ${formatSize(props.media.sizeBytes)} · 登记 ${formatTime(props.media.uploadTime)}`
              : props.media.mediaReason || '媒体核验未通过'
          ),
          h('div', { class: 'media-card__key', title: props.media.mediaKey }, props.media.mediaKey)
        ])
    }
  })

  const loading = ref(false)
  const list = ref<AppealAdminItem[]>([])
  const total = ref(0)
  const pageParams = reactive({ current: 1, size: 20 })
  let applyingFilters = false
  const statusFilter = ref(readQueryNumber(route.query.appealStatus))
  const orderKeyword = ref(readQueryValue(route.query.orderNo))
  const evidenceVisible = ref(false)
  const evidenceLoading = ref(false)
  const evidence = ref<AppealAdminEvidence | null>(null)
  const decisionVisible = ref(false)
  const decisionSubmitting = ref(false)
  const decisionTarget = ref<AppealAdminItem | null>(null)
  const decisionForm = reactive<{ outcome: 2 | 3 | 5; handleResult: string }>({
    outcome: 3,
    handleResult: ''
  })

  const appealStatusOptions = ref<{ label: string; value: number }[]>([
    { label: '待处理', value: 1 },
    { label: '成立待补偿', value: 2 },
    { label: '不成立驳回', value: 3 },
    { label: '已撤销', value: 4 },
    { label: '补送待执行', value: 5 }
  ])
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
      const [appealStatuses, taskStatuses] = await Promise.all([
        fetchDictOptions(DictTypeEnum.申诉状态),
        fetchDictOptions(DictTypeEnum.配送任务状态)
      ])
      const remoteAppealStatuses = toDictOptions(appealStatuses)
      const remoteTaskStatuses = toDictOptions(taskStatuses)
      if (remoteAppealStatuses.length) appealStatusOptions.value = remoteAppealStatuses
      if (remoteTaskStatuses.length) taskStatusOptions.value = remoteTaskStatuses
    } catch {
      // 字典接口暂不可用时保留本地兜底选项，列表仍可查询。
    }
    await loadData()
  })

  watch(
    () => route.fullPath,
    () => {
      if (route.path !== '/order/appeal') return
      statusFilter.value = readQueryNumber(route.query.appealStatus)
      orderKeyword.value = readQueryValue(route.query.orderNo)
      pageParams.current = 1
      if (applyingFilters) return
      loadData()
    }
  )

  const statusLabel = (value?: number) =>
    appealStatusOptions.value.find((item) => item.value === value)?.label ||
    (value == null ? '-' : String(value))
  const statusTagType = (value?: number) =>
    value === 1
      ? 'warning'
      : value === 2 || value === 5
        ? 'success'
        : value === 3
          ? 'danger'
          : 'info'
  const taskStatusLabel = (value?: number) =>
    taskStatusOptions.value.find((item) => item.value === value)?.label ||
    (value == null ? '-' : String(value))

  async function loadData() {
    loading.value = true
    try {
      const result = await fetchAppealPage({
        current: pageParams.current,
        size: pageParams.size,
        appealStatus: statusFilter.value || undefined,
        orderNo: orderKeyword.value || undefined
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
      ...(statusFilter.value ? { appealStatus: String(statusFilter.value) } : {}),
      ...(orderKeyword.value ? { orderNo: orderKeyword.value } : {})
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
    orderKeyword.value = ''
    await applyFilters()
  }

  async function showEvidence(row: AppealAdminItem) {
    evidence.value = null
    evidenceVisible.value = true
    evidenceLoading.value = true
    try {
      evidence.value = await fetchAppealEvidence(row.appealId)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载申诉证据失败')
    } finally {
      evidenceLoading.value = false
    }
  }

  function openOrderTrace() {
    const orderId = evidence.value?.task?.orderId
    if (!orderId) {
      ElMessage.warning('当前记录未关联有效订单，无法打开追溯')
      return
    }
    // 真实数据源：追溯走管理端 /order/order/trace，不再进入 Mock 分流
    router.push({
      path: '/order/index',
      query: {
        ...(evidence.value?.appeal.orderNo ? { orderNo: evidence.value.appeal.orderNo } : {}),
        traceOrderId: orderId
      }
    })
  }

  function openDecision(row: AppealAdminItem) {
    if (!hasPermission('order:appeal:handle')) {
      ElMessage.warning('当前账号没有申诉裁决权限')
      return
    }
    decisionTarget.value = row
    decisionForm.outcome = 3
    decisionForm.handleResult = ''
    decisionVisible.value = true
  }

  async function submitDecision() {
    if (!decisionTarget.value) return
    if (!decisionForm.handleResult.trim()) {
      ElMessage.warning('请填写裁决依据')
      return
    }
    decisionSubmitting.value = true
    try {
      await fetchDecideAppeal({
        id: decisionTarget.value.appealId,
        outcome: decisionForm.outcome,
        handleResult: decisionForm.handleResult.trim()
      })
      ElMessage.success('裁决已登记')
      decisionVisible.value = false
      evidenceVisible.value = false
      await loadData()
    } finally {
      decisionSubmitting.value = false
    }
  }
</script>

<style scoped>
  .section-title {
    display: flex;
    align-items: center;
    margin: 20px 0 10px;
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

  .section-title--between {
    justify-content: space-between;
  }

  .section-title--between > span {
    margin-right: auto;
  }

  .media-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
    gap: 12px;
  }

  :deep(.media-card) {
    padding: 10px 12px;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  :deep(.media-card__title) {
    display: flex;
    gap: 6px;
    align-items: center;
    justify-content: space-between;
    font-size: 13px;
    font-weight: 600;
  }

  :deep(.media-card__meta) {
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  :deep(.media-card__meta--danger) {
    color: var(--el-color-danger);
  }

  :deep(.media-card__key) {
    margin-top: 6px;
    overflow: hidden;
    font-size: 11px;
    color: var(--el-text-color-placeholder);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .courier-evidence {
    padding: 10px 12px;
    margin-bottom: 10px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .decision-bar {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
    padding: 14px 16px;
    margin-top: 20px;
    background: var(--el-color-warning-light-9);
    border: 1px solid var(--el-color-warning-light-5);
    border-radius: 8px;
  }

  .text-secondary {
    color: var(--el-text-color-secondary);
  }

  @media (width <= 768px) {
    .decision-bar {
      flex-direction: column;
      align-items: flex-start;
    }
  }
</style>
