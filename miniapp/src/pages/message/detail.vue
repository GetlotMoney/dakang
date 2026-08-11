<script setup lang="ts">
import type { MessageItem } from '@/api/message'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { messageApi } from '@/api/message'
import { orderApi } from '@/api/order'
import AppNavbar from '@/components/app-navbar.vue'
import {
  formatBizTime,
  MESSAGE_DOMAIN_LABELS,
  SEND_STATUS_LABELS,
  SEND_STATUS_TONES,
} from '@/utils/format'
import { backOr, goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '消息详情',
  },
})

const CHANNEL_LABELS: Record<MessageItem['channel'], string> = {
  'wechat-subscribe': '微信订阅',
  'in-app': '站内',
}

const OBJECT_TYPE_LABELS: Record<NonNullable<MessageItem['objectType']>, string> = {
  order: '订单',
  task: '配送任务',
  device: '设备',
  appeal: '配送申诉',
  service: '服务请求',
}

const toast = useToast()

const messageId = ref('')
const loading = ref(true)
const errorMessage = ref('')
const message = ref<MessageItem | null>(null)
const opening = ref(false)

onLoad((query) => {
  messageId.value = typeof query?.messageId === 'string' ? query.messageId : ''
})

// onShow 重取详情：从关联对象页返回时按最新能力快照重算 objectAccess（S10.4）。
onShow(refresh)

async function refresh() {
  if (!messageId.value) {
    loading.value = false
    message.value = null
    errorMessage.value = '无法打开该消息'
    return
  }
  loading.value = true
  try {
    message.value = await messageApi.getMessageDetail(messageId.value)
    errorMessage.value = ''
  }
  catch (error) {
    message.value = null
    errorMessage.value = error instanceof ContractError ? error.message : '消息详情加载失败'
  }
  finally {
    loading.value = false
  }
}

/**
 * S10.5 类型化回跳：跳转后全局路由守卫按当前能力再次校验，被拒弹"无法进入"属预期，
 * 页面不重复拦截。
 */
async function openRelatedObject() {
  const current = message.value
  if (!current?.objectType || !current.objectId || opening.value) {
    return
  }
  opening.value = true
  try {
    switch (current.objectType) {
      case 'order':
        goTo('U06', { orderNo: current.objectId })
        break
      case 'task':
        goTo('D03', { taskNo: current.objectId })
        break
      case 'device':
        goTo('O03', { deviceNo: current.objectId })
        break
      case 'service':
        goTo('O05', { requestId: current.objectId })
        break
      case 'appeal': {
        // 申诉消息只携带 appealId：先经本人申诉契约换取 orderNo，再落到订单申诉区。
        const appeal = await orderApi.getMyDeliveryAppeal(current.objectId)
        goTo('U06', { orderNo: appeal.orderNo, focus: 'appeal' })
        break
      }
    }
  }
  catch (error) {
    toast.show(error instanceof Error ? error.message : '无法打开关联对象')
  }
  finally {
    opening.value = false
  }
}

function handleBack() {
  backOr('C02')
}
</script>

<template>
  <view class="message-page screen-c03">
    <AppNavbar title="消息详情" back-to="C02" />
    <view class="page-shell">
      <wd-toast />

      <view v-if="loading" class="muted-text detail-placeholder">
        加载中…
      </view>

      <template v-else-if="message">
        <view class="page-section detail-card">
          <view class="detail-title">
            {{ message.title }}
          </view>
          <view class="detail-tags">
            <wd-tag plain>
              {{ MESSAGE_DOMAIN_LABELS[message.domain] }}
            </wd-tag>
            <wd-tag plain>
              {{ CHANNEL_LABELS[message.channel] }}
            </wd-tag>
            <wd-tag :type="SEND_STATUS_TONES[message.sendStatus]" plain>
              {{ SEND_STATUS_LABELS[message.sendStatus] }}
            </wd-tag>
          </view>
          <view class="detail-content">
            {{ message.content }}
          </view>
        </view>

        <view class="page-section">
          <wd-cell-group title="发送信息" border>
            <wd-cell title="发送时间" :value="formatBizTime(message.sendTime)" />
          </wd-cell-group>
        </view>

        <!-- S10.4：能力撤销只保留安全摘要，不显示对象详情、不提供跳转按钮。 -->
        <view v-if="message.objectAccess === 'capability-revoked'" class="page-section">
          <wd-cell-group title="关联对象" border>
            <wd-cell title="你已无权查看关联内容" />
          </wd-cell-group>
        </view>
        <view
          v-else-if="message.objectAccess === 'allowed' && message.objectType && message.objectId"
          class="page-section"
        >
          <wd-cell-group title="关联对象" border>
            <wd-cell
              :title="OBJECT_TYPE_LABELS[message.objectType]"
              :value="message.objectId"
              ellipsis
            />
          </wd-cell-group>
          <wd-button block icon="view" :loading="opening" @click="openRelatedObject">
            查看关联对象
          </wd-button>
        </view>
      </template>

      <view v-else class="detail-error">
        <wd-status-tip image="content" :tip="errorMessage || '消息不存在或无权访问'" />
        <view class="detail-error-action">
          <wd-button plain @click="handleBack">
            返回消息中心
          </wd-button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.detail-placeholder {
  padding: 32px 0;
  text-align: center;
}

.detail-card {
  padding: 16px;
  border-radius: 12px;
  background: #fff;
}

.detail-title {
  color: var(--app-text-primary);
  font-size: 17px;
  font-weight: 600;
}

.detail-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.detail-content {
  margin-top: 12px;
  color: var(--app-text-primary);
  font-size: 15px;
  line-height: 1.7;
}

.detail-error {
  padding-top: 48px;
}

.detail-error-action {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
</style>
