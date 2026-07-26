<script setup lang="ts">
import type { MessageDomain, MessageItem } from '@/api/message'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { messageApi } from '@/api/message'
import AppNavbar from '@/components/app-navbar.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import {
  formatBizTimeShort,
  MESSAGE_DOMAIN_LABELS,
  SEND_STATUS_LABELS,
  SEND_STATUS_TONES,
} from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '消息中心',
  },
})

interface DomainTab {
  name: 'all' | MessageDomain
  title: string
}

/** 业务域筛选：全部 + 路由合同 domain 枚举（C02：water/card/delivery/owner/system）。 */
const DOMAIN_TABS: DomainTab[] = [
  { name: 'all', title: '全部' },
  { name: 'water', title: '用水' },
  { name: 'card', title: '水卡' },
  { name: 'delivery', title: '配送' },
  { name: 'owner', title: '经营' },
  { name: 'system', title: '系统' },
]

const CHANNEL_LABELS: Record<MessageItem['channel'], string> = {
  'wechat-subscribe': '微信订阅',
  'in-app': '站内',
}

const toast = useToast()

const activeTab = ref<string>('all')
const loading = ref(true)
const errorMessage = ref('')
const messages = ref<MessageItem[]>([])
/** 请求序号：Tab 快速切换与 onShow 并发时只采纳最后一次结果（蓝图 9.5 重复请求去重）。 */
let requestSeq = 0

onLoad((query) => {
  const domain = typeof query?.domain === 'string' ? query.domain : ''
  if (DOMAIN_TABS.some(tab => tab.name !== 'all' && tab.name === domain)) {
    activeTab.value = domain
  }
})

// onShow 刷新：从 C03 返回时同步未读状态；列表始终按账号统一读取（S10.1）。
onShow(refresh)

async function refresh() {
  const seq = ++requestSeq
  loading.value = true
  const domain = activeTab.value === 'all' ? undefined : (activeTab.value as MessageDomain)
  try {
    const list = await messageApi.listMessages(domain)
    if (seq !== requestSeq) {
      return
    }
    messages.value = list
    errorMessage.value = ''
  }
  catch (error) {
    if (seq !== requestSeq) {
      return
    }
    messages.value = []
    errorMessage.value = error instanceof Error ? error.message : '消息加载失败'
  }
  finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

/** 切换即查询（蓝图 6.7）：以事件携带的 name 为准，避免依赖 v-model 同步时序。 */
function handleTabChange({ name }: { index: number, name: string | number }) {
  activeTab.value = String(name)
  refresh()
}

/** 打开消息：先按契约标记已读，再进入 C03；失败原位提示，不伪装成功。 */
async function openMessage(item: MessageItem) {
  try {
    await messageApi.markRead(item.messageId)
    item.unread = false
    goTo('C03', { messageId: item.messageId })
  }
  catch (error) {
    toast.show(error instanceof Error ? error.message : '打开消息失败')
  }
}
</script>

<template>
  <view class="message-page">
    <AppNavbar title="消息中心" back-to="U01" />
    <view class="page-shell">
      <wd-toast />
      <AppPrototypeNotice />

      <view class="page-section domain-tabs">
        <wd-tabs v-model="activeTab" @change="handleTabChange">
          <wd-tab
            v-for="tab in DOMAIN_TABS"
            :key="tab.name"
            :name="tab.name"
            :title="tab.title"
          />
        </wd-tabs>
      </view>

      <view class="muted-text inbox-note">
        消息按账号统一接收，能力撤销不影响收件（S10）
      </view>

      <view class="page-section">
        <view v-if="errorMessage" class="list-error">
          <wd-status-tip image="network" :tip="errorMessage" />
          <view class="list-error-action">
            <wd-button size="small" plain @click="refresh">
              重新加载
            </wd-button>
          </view>
        </view>
        <view v-else-if="messages.length" class="message-list">
          <wd-cell-group border>
            <wd-cell
              v-for="item in messages"
              :key="item.messageId"
              clickable
              @click="openMessage(item)"
            >
              <template #title>
                <view class="message-title-row">
                  <view v-if="item.unread" class="unread-dot" />
                  <view class="message-title">
                    {{ item.title }}
                  </view>
                </view>
              </template>
              <template #label>
                <view class="message-summary">
                  {{ item.summary }}
                </view>
                <view class="message-meta">
                  <wd-tag plain>
                    {{ MESSAGE_DOMAIN_LABELS[item.domain] }}
                  </wd-tag>
                  <wd-tag :type="SEND_STATUS_TONES[item.sendStatus]" plain>
                    {{ SEND_STATUS_LABELS[item.sendStatus] }}
                  </wd-tag>
                  <view class="muted-text">
                    {{ CHANNEL_LABELS[item.channel] }} · {{ formatBizTimeShort(item.sendTime) }}
                  </view>
                </view>
              </template>
            </wd-cell>
          </wd-cell-group>
        </view>
        <view v-else-if="loading" class="muted-text list-placeholder">
          加载中…
        </view>
        <wd-status-tip v-else image="message" tip="暂无消息" />
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.domain-tabs {
  border-radius: 12px;
  overflow: hidden;
}

.inbox-note {
  margin-top: 8px;
  padding: 0 4px;
}

.message-list {
  border-radius: 12px;
  overflow: hidden;
}

.message-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.unread-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--wot-color-danger, #fa4350);
}

.message-title {
  color: var(--app-text-primary);
  font-size: 15px;
  font-weight: 600;
}

.message-summary {
  margin-top: 4px;
}

.message-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.list-placeholder {
  padding: 24px 0;
  text-align: center;
}

.list-error-action {
  display: flex;
  justify-content: center;
  margin-top: 12px;
}
</style>
