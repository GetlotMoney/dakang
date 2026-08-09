<script setup lang="ts">
import type { DeviceSummary, OwnerServiceRequest, OwnerServiceType } from '@/api/device'
import { onLoad } from '@dcloudio/uni-app'
import { computed, reactive, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { newDeliveryRequestId, uploadDeliveryMedia } from '@/api/delivery'
import { deviceApi } from '@/api/device'
import { currentMode } from '@/api/runtime'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import {
  SERVICE_STATUS_LABELS,
  SERVICE_STATUS_TONES,
  SERVICE_TYPE_LABELS,
  SERVICE_TYPE_TONES,
} from '@/pages/owner/owner-labels'
import { formatBizTimeShort } from '@/utils/format'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '报修与配件',
  },
})

const SERVICE_TYPE_COLUMNS = [
  { label: '报修', value: 'REPAIR' },
  { label: '配件', value: 'PART' },
]
const MAX_EVIDENCE_COUNT = 3

const message = useMessage()
const toast = useToast()

/** device 域接真时：申报生成真实工单、凭证真实上传（文案与行为同步分流，不再是原型请求）。 */
const isRealDevice = currentMode('device') === 'real'

const loading = ref(true)
const errorMessage = ref('')
const devices = ref<DeviceSummary[]>([])
const requests = ref<OwnerServiceRequest[]>([])
const highlightRequestId = ref('')

const formRef = ref<{ validate: () => Promise<{ valid: boolean }> }>()
const model = reactive({
  serviceType: 'REPAIR' as OwnerServiceType,
  deviceNo: '',
  description: '',
  contactPhone: '',
})
/** mock：本地选图记录（9.5 mock-recorded）；real：提交时逐张上传受控媒体换 mediaKey。 */
const evidences = ref<{ localPath: string }[]>([])
const submitting = ref(false)
/** real 申报幂等键：一次提交意图内持有、失败重试复用、成功后清空（后端 uk_wo_request 收敛重复） */
const submissionRequestId = ref('')
/** real 详情展开：requestId → 含轨迹的申请详情 */
const expandedDetail = ref<{ requestId: string, trace: { eventTime: string, actorLabel: string, detail: string }[] } | null>(null)

const deviceColumns = computed(() =>
  devices.value.map(item => ({
    label: `${item.deviceNo} · ${item.deviceName}`,
    value: item.deviceNo,
  })),
)

onLoad(async (options) => {
  const serviceType = String(options?.serviceType ?? '')
  if (serviceType === 'REPAIR' || serviceType === 'PART') {
    model.serviceType = serviceType
  }
  highlightRequestId.value = String(options?.requestId ?? '')
  const presetDeviceNo = String(options?.deviceNo ?? '')
  await refresh()
  // 设备预选只在授权范围内生效，路由参数不授予访问权。
  if (presetDeviceNo && devices.value.some(item => item.deviceNo === presetDeviceNo)) {
    model.deviceNo = presetDeviceNo
  }
})

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [deviceList, requestList] = await Promise.all([
      deviceApi.listOwnerDevices(),
      deviceApi.listOwnerServiceRequests(),
    ])
    devices.value = deviceList
    requests.value = requestList
  }
  catch (error) {
    devices.value = []
    requests.value = []
    errorMessage.value = error instanceof ContractError ? error.message : '服务请求加载失败，请稍后重试'
  }
  finally {
    loading.value = false
  }
}

function chooseEvidence() {
  const remaining = MAX_EVIDENCE_COUNT - evidences.value.length
  if (remaining <= 0) {
    return
  }
  uni.chooseImage({
    count: remaining,
    success: (res) => {
      const paths = Array.isArray(res.tempFilePaths) ? res.tempFilePaths : [res.tempFilePaths]
      for (const path of paths.slice(0, remaining)) {
        evidences.value.push({ localPath: String(path) })
      }
    },
  })
}

function removeEvidence(index: number) {
  evidences.value.splice(index, 1)
}

async function toggleDetail(requestId: string) {
  if (expandedDetail.value?.requestId === requestId) {
    expandedDetail.value = null
    return
  }
  try {
    const detail = await deviceApi.getOwnerServiceDetail(requestId)
    expandedDetail.value = { requestId, trace: detail.trace ?? [] }
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '轨迹加载失败')
  }
}

async function handleSubmit() {
  if (submitting.value) {
    return
  }
  const result = await formRef.value?.validate()
  if (!result?.valid) {
    return
  }
  try {
    await message.confirm({
      title: '确认提交',
      msg: `将提交「${SERVICE_TYPE_LABELS[model.serviceType]}」申报（设备 ${model.deviceNo}）`,
    })
  }
  catch {
    return
  }
  submitting.value = true
  try {
    let evidenceRefs: string[]
    if (isRealDevice) {
      // 逐张真实上传换受控 mediaKey；任一失败整体失败，绝不提交半套证据
      evidenceRefs = []
      for (const item of evidences.value) {
        evidenceRefs.push(await uploadDeliveryMedia(item.localPath, 'workorder'))
      }
      if (!submissionRequestId.value) {
        submissionRequestId.value = newDeliveryRequestId()
      }
    }
    else {
      evidenceRefs = evidences.value.map((_, index) => `OSR-EV-${index + 1}`)
    }
    const created = await deviceApi.createOwnerServiceRequest({
      requestId: isRealDevice ? submissionRequestId.value : undefined,
      deviceNo: model.deviceNo,
      serviceType: model.serviceType,
      description: model.description,
      evidenceRefs,
      contactPhone: model.contactPhone,
    })
    highlightRequestId.value = created.requestId
    submissionRequestId.value = ''
    toast.success('申报已提交')
    model.description = ''
    model.contactPhone = ''
    evidences.value = []
    await refresh()
  }
  catch (error) {
    // 提交失败保留全部输入（9.5 表单 failed 状态）。
    toast.error(error instanceof Error ? error.message : '提交失败，请重试')
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="报修与配件" back-to="O01" />
    <wd-toast />
    <wd-message-box />
    <AppPrototypeNotice domain="device" />

    <view v-if="loading" class="page-section muted-text">
      加载中…
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button plain size="small" @click="backOr('O01')">
              返回
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <template v-else>
      <view class="page-section">
        <wd-card title="服务请求记录">
          <template v-if="requests.length">
            <view
              v-for="item in requests"
              :key="item.requestId"
              class="service-item"
              :class="{ 'service-item--highlight': item.requestId === highlightRequestId }"
              @click="toggleDetail(item.requestId)"
            >
              <view class="service-item-head">
                <view class="service-item-tags">
                  <wd-tag :type="SERVICE_TYPE_TONES[item.serviceType]" plain>
                    {{ SERVICE_TYPE_LABELS[item.serviceType] }}
                  </wd-tag>
                  <text class="service-device">
                    {{ item.deviceNo }}
                  </text>
                </view>
                <wd-tag :type="SERVICE_STATUS_TONES[item.status]" plain>
                  {{ SERVICE_STATUS_LABELS[item.status] }}
                </wd-tag>
              </view>
              <view class="service-desc">
                {{ item.description }}
              </view>
              <view class="service-meta muted-text">
                <text v-if="item.workOrderNo">
                  工单 {{ item.workOrderNo }}
                </text>
                <text v-else>
                  {{ item.requestId }}
                </text>
                <text>联系 {{ item.maskedContactPhone }}</text>
                <text v-if="item.evidenceRefs.length">
                  凭证 {{ item.evidenceRefs.length }} 张
                </text>
                <text>{{ formatBizTimeShort(item.createTime) }}</text>
              </view>
              <view v-if="item.rejectReason" class="service-desc muted-text">
                驳回原因：{{ item.rejectReason }}
              </view>
              <view v-if="item.finishResult" class="service-desc muted-text">
                处理结果：{{ item.finishResult }}
              </view>
              <view v-if="expandedDetail?.requestId === item.requestId" class="service-trace">
                <view
                  v-for="(traceItem, traceIndex) in expandedDetail.trace"
                  :key="traceIndex"
                  class="service-trace-item muted-text"
                >
                  <text>{{ formatBizTimeShort(traceItem.eventTime) }}</text>
                  <text>[{{ traceItem.actorLabel }}]</text>
                  <text>{{ traceItem.detail }}</text>
                </view>
                <view v-if="!expandedDetail.trace.length" class="muted-text">
                  暂无处理轨迹
                </view>
              </view>
            </view>
          </template>
          <wd-status-tip v-else image="content" tip="暂无报修或配件请求" />
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card title="新建报修/配件请求">
          <wd-form ref="formRef" :model="model">
            <wd-cell-group border>
              <wd-picker
                v-model="model.serviceType"
                label="服务类型"
                prop="serviceType"
                required
                :columns="SERVICE_TYPE_COLUMNS"
                :rules="[{ required: true, message: '请选择服务类型' }]"
              />
              <wd-picker
                v-model="model.deviceNo"
                label="授权设备"
                prop="deviceNo"
                required
                placeholder="请选择设备"
                :columns="deviceColumns"
                :rules="[{ required: true, message: '请选择授权设备' }]"
              />
              <wd-textarea
                v-model="model.description"
                label="问题说明"
                prop="description"
                placeholder="请描述设备问题或所需配件"
                :maxlength="200"
                show-word-limit
                auto-height
                required
                :rules="[{ required: true, message: '请填写问题说明' }]"
              />
              <wd-input
                v-model="model.contactPhone"
                label="联系电话"
                prop="contactPhone"
                type="number"
                required
                :maxlength="11"
                placeholder="请输入 11 位手机号"
                :rules="[{ required: true, pattern: /^1\d{10}$/, message: '请输入 11 位手机号' }]"
              />
              <wd-cell
                title="凭证图片"
                label="最多 3 张，可不上传"
                vertical
              >
                <view class="evidence-strip">
                  <view
                    v-for="(item, index) in evidences"
                    :key="`${item.localPath}-${index}`"
                    class="evidence-item"
                  >
                    <image :src="item.localPath" mode="aspectFill" class="evidence-image" />
                    <view class="evidence-remove" @click="removeEvidence(index)">
                      <wd-icon name="close" size="12px" color="#fff" />
                    </view>
                  </view>
                  <view
                    v-if="evidences.length < MAX_EVIDENCE_COUNT"
                    class="evidence-add"
                    @click="chooseEvidence"
                  >
                    <wd-icon name="camera" size="20px" color="var(--app-text-secondary)" />
                  </view>
                </view>
              </wd-cell>
            </wd-cell-group>
            <view class="form-submit">
              <wd-button block :loading="submitting" @click="handleSubmit">
                提交服务请求
              </wd-button>
            </view>
          </wd-form>
        </wd-card>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  width: 100%;
}

.service-trace {
  margin-top: 8px;
  padding: 8px;
  background: #f7f8fa;
  border-radius: 6px;
}

.service-trace-item {
  display: flex;
  gap: 6px;
  font-size: 12px;
  line-height: 1.8;
}

.service-item {
  padding: 12px;
  border-radius: 8px;
  border: 1px solid transparent;

  & + & {
    margin-top: 8px;
  }
}

.service-item--highlight {
  border-color: var(--wot-color-theme, var(--app-color-primary));
  background: rgba(93, 135, 255, 0.08);
}

.service-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.service-item-tags {
  display: flex;
  align-items: center;
  gap: 8px;
}

.service-device {
  font-size: 14px;
  font-weight: 600;
}

.service-desc {
  margin-top: 8px;
  font-size: 14px;
  line-height: 1.5;
}

.service-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-top: 8px;
}

.evidence-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 4px 0;
}

.evidence-item {
  position: relative;
  width: 72px;
}

.evidence-image {
  display: block;
  width: 72px;
  height: 72px;
  border-radius: 8px;
}

.evidence-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.6);
}

.evidence-add {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border: 1px dashed rgba(100, 106, 115, 0.4);
  border-radius: 8px;
}

.form-submit {
  margin-top: 12px;
}
</style>
