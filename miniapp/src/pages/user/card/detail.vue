<script setup lang="ts">
import type { CardDetail, CardMember } from '@/api/card'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { ContractError } from '@/api/common'
import AppNavbar from '@/components/app-navbar.vue'
import {
  CARD_STATUS_LABELS,
  CARD_STATUS_TONES,
  formatBizTime,
  formatFen,
  formatMl,
} from '@/utils/format'
import { backOr, goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '水卡详情',
  },
})

const toast = useToast()
const message = useMessage()

const cardId = ref('')
const loading = ref(true)
const loadError = ref('')
const detail = ref<CardDetail | null>(null)
const merging = ref(false)

onLoad((query) => {
  cardId.value = query?.cardId ?? ''
  if (!cardId.value) {
    loadError.value = '未指定水卡，无法展示详情'
    loading.value = false
  }
})

// onShow 刷新：从 U12 保存/撤销授权返回后立即回看成员变化（S05.2）。
onShow(() => {
  if (cardId.value) {
    refresh()
  }
})

async function refresh() {
  loading.value = true
  try {
    detail.value = await cardApi.getCardDetail(cardId.value)
    loadError.value = ''
  }
  catch (error) {
    detail.value = null
    loadError.value = error instanceof ContractError ? error.message : '水卡详情加载失败'
  }
  finally {
    loading.value = false
  }
}

function copyCardNo() {
  if (!detail.value) {
    return
  }
  uni.setClipboardData({
    data: detail.value.cardNo,
    success: () => toast.success('卡号已复制'),
  })
}

/** D-415：赠卡合并入正式水卡。二次确认后调用；成功即刷新（赠卡已注销，余额到期以刷新结果为准）。 */
async function confirmMerge() {
  if (!detail.value || merging.value) {
    return
  }
  const d = detail.value
  const msg = `将把本卡余额 ${formatFen(d.balanceFen)}、水量 ${formatMl(d.balanceMl)} 并入正式水卡，`
    + `并入部分沿用本卡有效期（${formatBizTime(d.expireTime)} 前有效），本卡随后注销。是否继续？`
  const agreed = await message.confirm({ title: '合并入正式水卡', msg }).then(() => true).catch(() => false)
  if (!agreed) {
    return
  }
  merging.value = true
  try {
    const result = await cardApi.mergeGiftCard(d.cardId)
    if (result.expiredCleared) {
      toast.show('赠卡已过期，权益作废并完成注销')
    }
    else {
      toast.success(`已并入正式水卡${result.mainCardNo ? ` ${result.mainCardNo}` : ''}`)
    }
    await refresh()
  }
  catch (error) {
    toast.error(error instanceof ContractError ? error.message : '合并失败，请稍后重试')
  }
  finally {
    merging.value = false
  }
}

function memberLimitText(member: CardMember) {
  return member.dayLimitMl !== undefined ? formatMl(member.dayLimitMl) : '不限'
}

function memberPeriodText(member: CardMember) {
  if (!member.effectiveTime && !member.expireTime) {
    return '长期有效'
  }
  const start = member.effectiveTime ? formatBizTime(member.effectiveTime) : '即时生效'
  const end = member.expireTime ? formatBizTime(member.expireTime) : '长期有效'
  return `${start} 至 ${end}`
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="水卡详情" back-to="U03" />
    <wd-toast />
    <wd-message-box />

    <template v-if="loadError">
      <view class="page-section">
        <wd-status-tip image="content" :tip="loadError">
          <template #bottom>
            <view class="status-actions">
              <wd-button plain @click="backOr('U03')">
                返回
              </wd-button>
            </view>
          </template>
        </wd-status-tip>
      </view>
    </template>
    <view v-else-if="!detail" class="page-section muted-text">
      加载中…
    </view>
    <template v-else>
      <view class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view>我的水卡</view>
              <wd-tag :type="CARD_STATUS_TONES[detail.cardStatus]" plain>
                {{ CARD_STATUS_LABELS[detail.cardStatus] }}
              </wd-tag>
            </view>
          </template>
          <view class="card-no-row">
            <view class="card-no">
              <wd-icon name="creditcard" size="16px" />
              <view>{{ detail.cardNo }}</view>
            </view>
            <wd-button size="small" plain @click="copyCardNo">
              复制
            </wd-button>
          </view>
          <view class="card-metrics">
            <view class="card-metric">
              <view class="card-metric-value">
                {{ formatFen(detail.balanceFen) }}
              </view>
              <view class="muted-text">
                余额
              </view>
            </view>
            <view class="card-metric">
              <view class="card-metric-value">
                {{ formatMl(detail.balanceMl) }}
              </view>
              <view class="muted-text">
                剩余水量
              </view>
            </view>
          </view>
          <view class="card-info-row">
            <view class="muted-text">
              套餐
            </view>
            <view class="card-info-value">
              {{ detail.packageName ?? '—' }}
            </view>
          </view>
          <view class="card-info-row">
            <view class="muted-text">
              可用范围
            </view>
            <view class="card-info-value">
              {{ detail.scopeDescription }}
            </view>
          </view>
          <view class="card-info-row">
            <view class="muted-text">
              有效期
            </view>
            <view class="card-info-value">
              {{ formatBizTime(detail.expireTime) }}
            </view>
          </view>
          <view v-for="(bundle, index) in detail.expiringBundles" :key="index" class="card-info-row">
            <view class="muted-text">
              {{ index === 0 ? '权益到期' : '' }}
            </view>
            <view class="card-info-value">
              {{ formatFen(bundle.remainFen) }} + {{ formatMl(bundle.remainMl) }} · {{ formatBizTime(bundle.expireTime) }}前有效
            </view>
          </view>
          <view v-if="detail.canMergeToPaidCard" class="merge-action">
            <wd-button block :loading="merging" @click="confirmMerge">
              合并入正式水卡
            </wd-button>
            <view class="muted-text merge-hint">
              余额与水量并入正式水卡，沿用本卡有效期，合并后本卡注销
            </view>
          </view>
        </wd-card>
      </view>

      <view class="page-section">
        <wd-card custom-class="block-card">
          <template #title>
            <view class="card-title-row">
              <view>授权成员</view>
              <wd-button size="small" plain icon="usergroup-add" @click="goTo('U12', { cardId })">
                新增成员
              </wd-button>
            </view>
          </template>
          <template v-if="detail.members.length">
            <wd-cell-group border>
              <wd-cell
                v-for="member in detail.members"
                :key="member.memberId"
                is-link
                @click="goTo('U12', { cardId, memberId: member.memberId })"
              >
                <template #title>
                  <view>{{ member.memberName }}</view>
                </template>
                <template #label>
                  <view>{{ member.maskedPhone }} · 单日限额 {{ memberLimitText(member) }}</view>
                  <view>{{ memberPeriodText(member) }}</view>
                </template>
                <wd-tag :type="member.enabled ? 'success' : 'default'" plain>
                  {{ member.enabled ? '生效' : '已解除' }}
                </wd-tag>
              </wd-cell>
            </wd-cell-group>
          </template>
          <wd-status-tip v-else image="content" tip="暂无授权成员" />
        </wd-card>
      </view>

      <view class="page-section">
        <wd-cell-group border>
          <wd-cell title="充值" icon="wallet" is-link @click="goTo('U10', { cardId })" />
          <wd-cell title="家庭资料" icon="usergroup" is-link @click="goTo('U13')" />
        </wd-cell-group>
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

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.card-no-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-no {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}

.card-metrics {
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 10px 0;
}

.card-metric-value {
  font-size: 20px;
  font-weight: 600;
}

.card-info-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 4px 0;
  font-size: 14px;
}

.card-info-value {
  flex: 1;
  text-align: right;
  word-break: break-all;
}

.merge-action {
  margin-top: 12px;
}

.merge-hint {
  margin-top: 6px;
  font-size: 12px;
  text-align: center;
}
</style>
