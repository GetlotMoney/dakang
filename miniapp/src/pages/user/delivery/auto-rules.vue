<script setup lang="ts">
import type { AutoRefillRule } from '@/api/delivery'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { deliveryApi } from '@/api/delivery'
import AppNavbar from '@/components/app-navbar.vue'
import { formatBizTimeShort } from '@/utils/format'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '自动补货规则',
  },
})

const RULE_STATUS_LABELS: Record<number, string> = {
  1: '进行中',
  2: '已暂停',
  3: '已取消',
}

const loading = ref(true)
const errorMessage = ref('')
const rules = ref<AutoRefillRule[]>([])
const message = useMessage()
const toast = useToast()

let acting = false

onShow(refresh)

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    rules.value = await deliveryApi.listAutoRules()
  }
  catch (error) {
    rules.value = []
    errorMessage.value = error instanceof ContractError ? error.message : '规则加载失败，请稍后重试'
  }
  finally {
    loading.value = false
  }
}

async function act(kind: 'pause' | 'resume' | 'cancel', rule: AutoRefillRule) {
  if (acting) {
    return
  }
  if (kind === 'cancel') {
    const agreed = await message.confirm({
      title: '取消自动补货',
      msg: '取消后本规则不再自动下单，且无法恢复。',
    }).then(() => true).catch(() => false)
    if (!agreed) {
      return
    }
  }
  acting = true
  try {
    if (kind === 'pause') {
      await deliveryApi.pauseAutoRule(rule.ruleId)
    }
    else if (kind === 'resume') {
      await deliveryApi.resumeAutoRule(rule.ruleId)
    }
    else {
      await deliveryApi.cancelAutoRule(rule.ruleId)
    }
    await refresh()
  }
  catch (error) {
    toast.error(error instanceof ContractError ? error.message : '操作失败，请稍后重试')
  }
  finally {
    acting = false
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="自动补货规则" back-to="U03" />

    <view v-if="loading" class="page-section muted-text">
      加载中…
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button plain size="small" @click="backOr('U03')">
              返回
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <view v-else-if="!rules.length" class="page-section">
      <wd-status-tip image="content" tip="暂无自动补货规则，下配送单时可选择按周期自动补货" />
    </view>

    <template v-else>
      <view v-for="rule in rules" :key="rule.ruleId" class="page-section">
        <wd-card>
          <template #title>
            <view class="rule-title">
              <text>{{ rule.waterTypeName }} · {{ rule.containerSpec }} × {{ rule.deliveryCount }}</text>
              <wd-tag :type="rule.ruleStatus === 1 ? 'success' : rule.ruleStatus === 2 ? 'warning' : 'default'" plain>
                {{ RULE_STATUS_LABELS[rule.ruleStatus] }}
              </wd-tag>
            </view>
          </template>
          <view class="rule-line muted-text">
            每 {{ rule.intervalDays }} 天一期 · {{ rule.receiveAddress }}
          </view>
          <view v-if="rule.nextDueTime" class="rule-line muted-text">
            下次补货 {{ formatBizTimeShort(rule.nextDueTime) }}
          </view>
          <view v-if="rule.lastResult" class="rule-line muted-text">
            最近一期：{{ rule.lastResult }}（{{ formatBizTimeShort(rule.lastResultTime) }}）
          </view>
          <template #footer>
            <view v-if="rule.ruleStatus !== 3" class="rule-actions">
              <wd-button v-if="rule.ruleStatus === 1" size="small" plain @click="act('pause', rule)">
                暂停
              </wd-button>
              <wd-button v-if="rule.ruleStatus === 2" size="small" type="primary" @click="act('resume', rule)">
                恢复
              </wd-button>
              <wd-button size="small" plain type="error" @click="act('cancel', rule)">
                取消
              </wd-button>
            </view>
          </template>
        </wd-card>
      </view>
    </template>
    <wd-message-box />
    <wd-toast />
  </view>
</template>

<style scoped lang="scss">
.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  width: 100%;
}

.rule-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.rule-line {
  padding: 2px 0;
  line-height: 1.6;
}

.rule-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
