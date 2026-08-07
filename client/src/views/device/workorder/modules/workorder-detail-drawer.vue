<!-- 工单详情抽屉（E2E-05 包D）：来源/设备/水站/告警/申请人/处理人/证据/完整状态轨迹 + 按当前状态收敛的动作区。
     状态迁移合法性以后端 WorkOrderTransitions 为准；这里只按状态决定展示哪些按钮，后端拒绝时如实提示。 -->
<template>
  <ElDrawer v-model="visible" title="工单详情" size="560px" @open="loadDetail">
    <div v-loading="loading">
      <ElDescriptions v-if="order.id" :column="1" border>
        <ElDescriptionsItem label="工单号">{{ order.orderNo }}</ElDescriptionsItem>
        <ElDescriptionsItem label="类型">{{ typeLabel(order.workType) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="来源">
          {{ WORK_ORDER_SOURCE_LABELS[(order.sourceType ?? 0) - 1] || '-' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          <ElTag :type="workOrderStatusTagType(order.orderStatus!) as any" size="small">
            {{ statusLabel(order.orderStatus) }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="order.deviceNo" label="设备">
          {{ order.deviceNo }}
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="order.stationName" label="水站">
          {{ order.stationName }}
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="order.alarmId" label="来源告警">
          #{{ order.alarmId }}
          <template v-if="order.alarmContent">：{{ order.alarmContent }}</template>
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="order.applicantUserId" label="申报人">
          用户 {{ order.applicantUserId }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="标题">{{ order.orderTitle }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="order.orderContent" label="问题描述">
          {{ order.orderContent }}
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="applyPhotoKeys.length" label="申报证据">
          <div class="media-keys">
            <ElTag v-for="key in applyPhotoKeys" :key="key" size="small" type="info">{{
              key
            }}</ElTag>
          </div>
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="order.assigneeName || order.assigneeId" label="处理人">
          {{ order.assigneeName || order.assigneeId }}
          <template v-if="order.assignTime">（{{ formatTime(order.assignTime) }} 分配）</template>
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="order.finishResult" label="处理结果">
          {{ order.finishResult }}
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="resultPhotoKeys.length" label="处理证据">
          <div class="media-keys">
            <ElTag v-for="key in resultPhotoKeys" :key="key" size="small" type="info">{{
              key
            }}</ElTag>
          </div>
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="order.reviewRemark" label="复核意见">
          {{ order.reviewRemark }}
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="order.rejectReason" label="驳回原因">
          {{ order.rejectReason }}
        </ElDescriptionsItem>
        <ElDescriptionsItem v-if="order.closeTime" label="关闭时间">
          {{ formatTime(order.closeTime) }}
        </ElDescriptionsItem>
      </ElDescriptions>

      <!-- 动作区：按当前状态收敛 -->
      <div v-if="order.id && canHandle" class="drawer-actions">
        <template v-if="order.orderStatus === 1">
          <ElButton type="primary" :loading="acting" @click="doConfirm">确认受理</ElButton>
          <ElButton type="danger" :loading="acting" @click="rejectVisible = true">驳回</ElButton>
        </template>
        <template v-else-if="order.orderStatus === 2">
          <ElButton type="primary" :loading="acting" @click="openAssign">分配处理人</ElButton>
        </template>
        <template v-else-if="order.orderStatus === 3">
          <ElButton type="primary" :loading="acting" @click="submitVisible = true">
            提交处理结果
          </ElButton>
        </template>
        <template v-else-if="order.orderStatus === 4">
          <ElButton type="success" :loading="acting" @click="doReviewPass">复核通过并关闭</ElButton>
          <ElButton type="warning" :loading="acting" @click="returnVisible = true">
            复核退回
          </ElButton>
        </template>
      </div>

      <!-- 完整状态轨迹 -->
      <template v-if="order.trace?.length">
        <ElDivider content-position="left">状态轨迹</ElDivider>
        <ElTimeline>
          <ElTimelineItem
            v-for="(item, index) in order.trace"
            :key="index"
            :timestamp="formatTime(item.eventTime)"
            placement="top"
          >
            <div class="trace-item">
              <ElTag size="small" type="info">
                {{ ACTOR_PORTAL_LABELS[item.actorPortal ?? 0] || '未知端口' }}
              </ElTag>
              <span class="trace-payload">{{ tracePayloadText(item.payload) }}</span>
            </div>
          </ElTimelineItem>
        </ElTimeline>
      </template>
    </div>

    <!-- 驳回 -->
    <ElDialog v-model="rejectVisible" title="驳回工单" width="480px" append-to-body>
      <ElInput
        v-model="rejectReason"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="驳回原因（必填）"
      />
      <template #footer>
        <ElButton @click="rejectVisible = false">取消</ElButton>
        <ElButton type="danger" :loading="acting" @click="doReject">确认驳回</ElButton>
      </template>
    </ElDialog>

    <!-- 分配 -->
    <ElDialog v-model="assignVisible" title="分配处理人" width="480px" append-to-body>
      <ElSelect
        v-model="assigneeId"
        filterable
        placeholder="选择处理人（有效员工）"
        style="width: 100%"
        :loading="employeeLoading"
      >
        <ElOption
          v-for="item in employees"
          :key="item.id"
          :label="`${item.employeeName}（${item.loginName}）`"
          :value="Number(item.id)"
        />
      </ElSelect>
      <template #footer>
        <ElButton @click="assignVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="acting" @click="doAssign">确认分配</ElButton>
      </template>
    </ElDialog>

    <!-- 提交处理结果 -->
    <ElDialog v-model="submitVisible" title="提交处理结果" width="520px" append-to-body>
      <ElForm label-position="top">
        <ElFormItem label="处理结果" required>
          <ElInput
            v-model="finishResult"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="处理结果说明（必填）"
          />
        </ElFormItem>
        <ElFormItem label="处理证据（图片，可选，最多9张）">
          <input
            ref="fileInputRef"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            class="file-input"
            @change="handleFilesSelected"
          />
          <div v-if="resultPhotos.length" class="media-keys mt-2">
            <ElTag
              v-for="key in resultPhotos"
              :key="key"
              size="small"
              closable
              @close="resultPhotos = resultPhotos.filter((item) => item !== key)"
            >
              {{ key }}
            </ElTag>
          </div>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="submitVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="acting || uploading" @click="doSubmitResult">
          提交并转待复核
        </ElButton>
      </template>
    </ElDialog>

    <!-- 复核退回 -->
    <ElDialog v-model="returnVisible" title="复核退回" width="480px" append-to-body>
      <ElInput
        v-model="reviewRemark"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="退回意见（必填）"
      />
      <template #footer>
        <ElButton @click="returnVisible = false">取消</ElButton>
        <ElButton type="warning" :loading="acting" @click="doReviewReturn">确认退回</ElButton>
      </template>
    </ElDialog>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    fetchAssignWorkOrder,
    fetchConfirmWorkOrder,
    fetchRejectWorkOrder,
    fetchReviewPassWorkOrder,
    fetchReviewReturnWorkOrder,
    fetchSubmitWorkOrderResult,
    fetchUploadWorkOrderMedia,
    fetchWorkOrderDetail,
    type WorkOrderItem
  } from '@/api/device'
  import { fetchGetUserList } from '@/api/system-manage'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import {
    ACTOR_PORTAL_LABELS,
    WORK_ORDER_SOURCE_LABELS,
    workOrderStatusTagType
  } from './workorder-meta'

  defineOptions({ name: 'WorkOrderDetailDrawer' })

  const props = defineProps<{
    workOrderId?: number
    canHandle: boolean
  }>()
  const emit = defineEmits<{ changed: [] }>()
  const visible = defineModel<boolean>({ required: true })

  const loading = ref(false)
  const acting = ref(false)
  const uploading = ref(false)
  const order = ref<Partial<WorkOrderItem>>({})

  const rejectVisible = ref(false)
  const rejectReason = ref('')
  const assignVisible = ref(false)
  const assigneeId = ref<number>()
  const submitVisible = ref(false)
  const finishResult = ref('')
  const resultPhotos = ref<string[]>([])
  const returnVisible = ref(false)
  const reviewRemark = ref('')
  const fileInputRef = ref<HTMLInputElement>()

  const employees = ref<Api.SystemManage.UserListItem[]>([])
  const employeeLoading = ref(false)

  const statusOptions = ref<{ label: string; value: number }[]>([])
  const typeOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [statuses, types] = await Promise.all([
      fetchDictOptions(DictTypeEnum.工单状态),
      fetchDictOptions(DictTypeEnum.工单类型)
    ])
    statusOptions.value = toDictOptions(statuses)
    typeOptions.value = toDictOptions(types)
  })

  const statusLabel = (v?: number) =>
    statusOptions.value.find((o) => o.value === v)?.label || String(v ?? '-')
  const typeLabel = (v?: number) =>
    typeOptions.value.find((o) => o.value === v)?.label || String(v ?? '-')

  const applyPhotoKeys = computed(() => parseKeys(order.value.orderPhotos))
  const resultPhotoKeys = computed(() => parseKeys(order.value.resultPhotos))

  function parseKeys(raw?: string): string[] {
    if (!raw) return []
    try {
      const arr = JSON.parse(raw)
      return Array.isArray(arr) ? arr.map(String) : []
    } catch {
      return []
    }
  }

  async function loadDetail() {
    if (!props.workOrderId) return
    loading.value = true
    try {
      order.value = await fetchWorkOrderDetail(props.workOrderId)
    } finally {
      loading.value = false
    }
  }

  async function afterAction(message: string) {
    ElMessage.success(message)
    await loadDetail()
    emit('changed')
  }

  async function doConfirm() {
    acting.value = true
    try {
      await fetchConfirmWorkOrder(order.value.id!)
      await afterAction('已确认')
    } finally {
      acting.value = false
    }
  }

  async function doReject() {
    if (!rejectReason.value.trim()) {
      ElMessage.warning('驳回必须填写原因')
      return
    }
    acting.value = true
    try {
      await fetchRejectWorkOrder(order.value.id!, rejectReason.value.trim())
      rejectVisible.value = false
      rejectReason.value = ''
      await afterAction('已驳回')
    } finally {
      acting.value = false
    }
  }

  async function openAssign() {
    assignVisible.value = true
    if (employees.value.length) return
    employeeLoading.value = true
    try {
      const page = await fetchGetUserList({ current: 1, size: 100, disabledFlag: 1 })
      employees.value = page.list ?? []
    } finally {
      employeeLoading.value = false
    }
  }

  async function doAssign() {
    if (!assigneeId.value) {
      ElMessage.warning('请选择处理人')
      return
    }
    acting.value = true
    try {
      await fetchAssignWorkOrder(order.value.id!, assigneeId.value)
      assignVisible.value = false
      await afterAction('已分配')
    } finally {
      acting.value = false
    }
  }

  /** 图片本地转 base64 → 后端登记换受控 mediaKey；页面只持键，不持文件路径 */
  async function handleFilesSelected(event: Event) {
    const files = Array.from((event.target as HTMLInputElement).files ?? [])
    if (!files.length) return
    if (resultPhotos.value.length + files.length > 9) {
      ElMessage.warning('处理证据最多9张')
      return
    }
    uploading.value = true
    try {
      for (const file of files) {
        const base64 = await fileToBase64(file)
        const mediaKey = await fetchUploadWorkOrderMedia({
          mimeType: file.type,
          contentBase64: base64
        })
        if (!resultPhotos.value.includes(mediaKey)) {
          resultPhotos.value.push(mediaKey)
        }
      }
      ElMessage.success('已上传')
    } finally {
      uploading.value = false
      if (fileInputRef.value) fileInputRef.value.value = ''
    }
  }

  function fileToBase64(file: File): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => {
        const result = String(reader.result)
        resolve(result.slice(result.indexOf(',') + 1))
      }
      reader.onerror = () => reject(reader.error)
      reader.readAsDataURL(file)
    })
  }

  async function doSubmitResult() {
    if (!finishResult.value.trim()) {
      ElMessage.warning('处理结果必填')
      return
    }
    acting.value = true
    try {
      await fetchSubmitWorkOrderResult(
        order.value.id!,
        finishResult.value.trim(),
        resultPhotos.value.length ? resultPhotos.value : undefined
      )
      submitVisible.value = false
      finishResult.value = ''
      resultPhotos.value = []
      await afterAction('已提交')
    } finally {
      acting.value = false
    }
  }

  async function doReviewPass() {
    await ElMessageBox.confirm('复核通过后工单将关闭，确认？', '复核通过', { type: 'warning' })
    acting.value = true
    try {
      await fetchReviewPassWorkOrder(order.value.id!)
      await afterAction('已关闭')
    } finally {
      acting.value = false
    }
  }

  async function doReviewReturn() {
    if (!reviewRemark.value.trim()) {
      ElMessage.warning('复核退回必须填写意见')
      return
    }
    acting.value = true
    try {
      await fetchReviewReturnWorkOrder(order.value.id!, reviewRemark.value.trim())
      returnVisible.value = false
      reviewRemark.value = ''
      await afterAction('已退回')
    } finally {
      acting.value = false
    }
  }

  function tracePayloadText(payload?: string) {
    if (!payload) return '-'
    try {
      const obj = JSON.parse(payload)
      if (obj && typeof obj === 'object') {
        const oldValue = obj.old ?? obj.oldValue
        const newValue = obj.new ?? obj.newValue
        if (newValue !== undefined) {
          return oldValue ? `${oldValue} → ${newValue}` : String(newValue)
        }
      }
    } catch {
      // 非 JSON 原样展示
    }
    return payload
  }

  function formatTime(time?: string) {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
  }
</script>

<style scoped lang="scss">
  .drawer-actions {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
    margin-top: 16px;
  }

  .media-keys {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .trace-item {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .trace-payload {
    font-size: 13px;
  }

  .file-input {
    width: 100%;
  }
</style>
