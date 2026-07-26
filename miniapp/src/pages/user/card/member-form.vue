<script setup lang="ts">
import type { CardMember } from '@/api/card'
import type { FormInstance, FormItemRule } from 'wot-design-uni/components/wd-form/types'
import { onLoad } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { ContractError } from '@/api/common'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '成员授权',
  },
})

const toast = useToast()
const message = useMessage()

const cardId = ref('')
const memberId = ref('')
const loading = ref(true)
const loadError = ref('')
const editingMember = ref<CardMember | null>(null)
const submitting = ref(false)
const revoking = ref(false)

const form = ref<FormInstance>()

const model = reactive<{
  memberName: string
  phone: string
  dayLimitL: string
  effectiveTs: number | ''
  expireTs: number | ''
}>({
  memberName: '',
  phone: '',
  dayLimitL: '',
  effectiveTs: '',
  expireTs: '',
})

const nameRules: FormItemRule[] = [{ required: true, message: '请填写成员姓名' }]

const phoneRules: FormItemRule[] = [
  { required: true, message: '请填写手机号' },
  { required: false, pattern: /^1\d{10}$/, message: '手机号需为 1 开头的 11 位数字' },
]

const dayLimitRules: FormItemRule[] = [
  {
    required: false,
    message: '单日限额需为大于 0 的数字（升）',
    validator: (value) => {
      if (value === '' || value === undefined || value === null) {
        return true
      }
      const liters = Number(value)
      return Number.isFinite(liters) && liters > 0 && Math.round(liters * 1000) >= 1
    },
  },
]

const expireRules: FormItemRule[] = [
  {
    required: false,
    message: '失效时间需晚于生效时间',
    validator: () =>
      model.effectiveTs === '' || model.expireTs === '' || model.expireTs > model.effectiveTs,
  },
]

/** wd-datetime-picker 返回本地毫秒时间戳，页面负责与契约 yyyyMMddHHmmss 互转（秒位补 00）。 */
function timestampToBizTime(timestamp: number): string {
  const value = new Date(timestamp)
  const pad = (input: number) => String(input).padStart(2, '0')
  return (
    `${value.getFullYear()}${pad(value.getMonth() + 1)}${pad(value.getDate())}`
    + `${pad(value.getHours())}${pad(value.getMinutes())}${pad(value.getSeconds())}`
  )
}

function bizTimeToTimestamp(time?: string): number | '' {
  const match = time?.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})$/)
  if (!match) {
    return ''
  }
  return new Date(
    Number(match[1]),
    Number(match[2]) - 1,
    Number(match[3]),
    Number(match[4]),
    Number(match[5]),
    Number(match[6]),
  ).getTime()
}

onLoad((query) => {
  cardId.value = query?.cardId ?? ''
  memberId.value = query?.memberId ?? ''
  if (!cardId.value) {
    loadError.value = '缺少必填参数 cardId，无法进入成员授权'
    loading.value = false
    return
  }
  load()
})

async function load() {
  loading.value = true
  try {
    const detail = await cardApi.getCardDetail(cardId.value)
    if (memberId.value) {
      const member = detail.members.find(item => item.memberId === memberId.value) ?? null
      if (!member) {
        loadError.value = '授权成员不存在或已被移除'
        return
      }
      editingMember.value = member
      model.memberName = member.memberName
      // 契约仅回传脱敏手机号，无法回填完整号码，保存时须重新填写（见页面提示）。
      model.dayLimitL = member.dayLimitMl !== undefined ? String(member.dayLimitMl / 1000) : ''
      model.effectiveTs = bizTimeToTimestamp(member.effectiveTime)
      model.expireTs = bizTimeToTimestamp(member.expireTime)
    }
  }
  catch (error) {
    loadError.value = error instanceof ContractError ? error.message : '成员授权数据加载失败'
  }
  finally {
    loading.value = false
  }
}

async function handleSubmit() {
  if (submitting.value || !form.value) {
    return
  }
  const result = await form.value.validate().catch(() => ({ valid: false }))
  if (!result.valid) {
    return
  }
  submitting.value = true
  try {
    await cardApi.saveCardMember({
      cardId: cardId.value,
      memberId: memberId.value || undefined,
      memberName: model.memberName.trim(),
      phone: model.phone.trim(),
      dayLimitMl: model.dayLimitL === '' ? undefined : Math.round(Number(model.dayLimitL) * 1000),
      effectiveTime: model.effectiveTs === '' ? undefined : timestampToBizTime(model.effectiveTs),
      expireTime: model.expireTs === '' ? undefined : timestampToBizTime(model.expireTs),
    })
    toast.success(memberId.value ? '已保存成员授权' : '已新增成员授权')
    // U11 通过 onShow 刷新成员列表（S05.2）；延时返回让提示可见，submitting 保持防重复。
    setTimeout(() => backOr('U11'), 900)
  }
  catch (error) {
    toast.error(error instanceof ContractError ? error.message : '保存成员授权失败，请重试')
    submitting.value = false
  }
}

async function handleRevoke() {
  if (revoking.value || !editingMember.value) {
    return
  }
  try {
    await message.confirm({
      title: '撤销授权',
      msg: `撤销后「${editingMember.value.memberName}」将失去本卡取水授权；仅更新平台契约，不写入设备白名单。`,
    })
  }
  catch {
    return
  }
  revoking.value = true
  try {
    await cardApi.revokeCardMember(cardId.value, memberId.value)
    toast.success('已撤销成员授权')
    setTimeout(() => backOr('U11'), 900)
  }
  catch (error) {
    toast.error(error instanceof ContractError ? error.message : '撤销授权失败，请重试')
    revoking.value = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="成员授权" back-to="U11" />
    <wd-toast />
    <wd-message-box />
    <AppPrototypeNotice />

    <template v-if="loadError">
      <view class="page-section">
        <wd-status-tip image="content" :tip="loadError">
          <template #bottom>
            <view class="status-actions">
              <wd-button plain @click="backOr('U11')">
                返回
              </wd-button>
            </view>
          </template>
        </wd-status-tip>
      </view>
    </template>
    <view v-else-if="loading" class="page-section muted-text">
      加载中…
    </view>
    <template v-else>
      <view v-if="editingMember" class="page-section member-summary">
        <view class="member-summary-name">
          {{ editingMember.memberName }}
        </view>
        <wd-tag :type="editingMember.enabled ? 'success' : 'default'" plain>
          {{ editingMember.enabled ? '生效' : '已解除' }}
        </wd-tag>
      </view>
      <view v-if="editingMember" class="muted-text form-hint">
        契约仅返回脱敏手机号（{{ editingMember.maskedPhone }}），保存修改需重新填写完整手机号。
      </view>

      <view class="page-section">
        <wd-form ref="form" :model="model">
          <wd-cell-group border>
            <wd-input
              v-model="model.memberName"
              label="成员姓名"
              label-width="96px"
              prop="memberName"
              required
              clearable
              :maxlength="20"
              placeholder="请输入成员姓名"
              :rules="nameRules"
            />
            <wd-input
              v-model="model.phone"
              label="手机号"
              label-width="96px"
              prop="phone"
              required
              clearable
              type="number"
              :maxlength="11"
              placeholder="请输入 11 位手机号"
              :rules="phoneRules"
            />
            <wd-input
              v-model="model.dayLimitL"
              label="单日限额"
              label-width="96px"
              prop="dayLimitL"
              clearable
              type="digit"
              placeholder="选填，单位升，留空为不限"
              :rules="dayLimitRules"
            />
            <wd-datetime-picker
              v-model="model.effectiveTs"
              label="生效时间"
              label-width="96px"
              prop="effectiveTs"
              clearable
              placeholder="选填，留空为即时生效"
            />
            <wd-datetime-picker
              v-model="model.expireTs"
              label="失效时间"
              label-width="96px"
              prop="expireTs"
              clearable
              placeholder="选填，留空为长期有效"
              :rules="expireRules"
            />
          </wd-cell-group>
        </wd-form>
      </view>

      <view class="page-section">
        <wd-button block size="large" :loading="submitting" @click="handleSubmit">
          保存成员授权
        </wd-button>
      </view>
      <view v-if="editingMember" class="page-section">
        <wd-button block size="large" type="error" plain :loading="revoking" @click="handleRevoke">
          撤销授权
        </wd-button>
      </view>

      <view class="muted-text boundary-note">
        成员授权只影响平台契约：不写入设备白名单，设备端离线授权不在本期范围；手机号提交后接口返回脱敏值。
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 20px;
  width: 100%;
}

.member-summary {
  display: flex;
  align-items: center;
  gap: 8px;
}

.member-summary-name {
  font-size: 18px;
  font-weight: 600;
}

.form-hint {
  margin-top: 8px;
  padding: 0 4px;
  line-height: 1.6;
}

.boundary-note {
  margin-top: 16px;
  padding: 0 4px;
  line-height: 1.6;
}
</style>
