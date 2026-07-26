<script setup lang="ts">
import type { CardSummary } from '@/api/card'
import type { CourierAdmission } from '@/api/delivery'
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { cardApi } from '@/api/card'
import { deliveryApi } from '@/api/delivery'
import { currentMode, isAllMockBuild } from '@/api/runtime'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { appServiceNoticeText } from '@/components/runtime-notice'
import { useAccountStore } from '@/store/account'
import { ADMISSION_STATUS_LABELS, CARD_STATUS_LABELS } from '@/utils/format'
import { goTo } from '@/utils/navigation'
import { getWxSafeHeader } from '@/utils/safe-area'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '我的',
  },
})

const safeHeader = getWxSafeHeader()
const accountStore = useAccountStore()
const context = computed(() => accountStore.context)
const hasOwnerView = computed(() => accountStore.hasCapability('OWNER_VIEW'))
const isMockBuild = isAllMockBuild()
const serviceNotice = appServiceNoticeText(isMockBuild)
/**
 * 「我的」页聚合两个业务域的边界口径：水卡属 card 域、配送员申请属 delivery 域，各按构建期
 * 模式如实陈述，已接真的域不得继续标注为演示；家庭资料与配送地址暂无后端域，恒为演示口径。
 */
const isCardReal = currentMode('card') === 'real'
const isDeliveryReal = currentMode('delivery') === 'real'
const profileNotice = isCardReal
  ? isDeliveryReal
    ? '水卡来自真实 card 接口，配送员申请状态来自真实配送接口；家庭资料、配送地址仍为演示。'
    : '水卡来自真实 card 接口；家庭资料、配送地址、配送员申请等仍为演示。'
  : isDeliveryReal
    ? '配送员申请状态来自真实配送接口；水卡、家庭资料与配送地址均为 Mock/固定快照。'
    : '水卡、家庭资料、配送地址与配送员申请均为 Mock/固定快照，不构成真实账户结果。'

const primaryCard = ref<CardSummary | null>(null)
const admission = ref<CourierAdmission | null>(null)

const maskedPhone = computed(() => {
  const phone = context.value?.userPhone ?? ''
  return phone ? `${phone.slice(0, 3)}****${phone.slice(-4)}` : ''
})

const ownerScopeSummary = computed(() => {
  const scope = context.value?.ownerScope
  if (!scope) {
    return ''
  }
  return `${scope.stationIds.length} 个水站 / ${scope.deviceNos.length} 台设备`
})

onShow(refresh)

async function refresh() {
  if (!accountStore.restored) {
    await accountStore.restoreSession().catch(() => null)
  }
  if (!accountStore.context) {
    return
  }
  primaryCard.value = await cardApi.getPrimaryCard()
  // 配送准入状态所有账号均可查看（COURIER_APPLY 属基础投影），查询失败按无记录降级。
  admission.value = await deliveryApi.getCourierAdmission().catch(() => null)
}

function handleCardTap() {
  if (primaryCard.value) {
    goTo('U11', { cardId: primaryCard.value.cardId })
  }
  else {
    goTo('U10')
  }
}

function showPrivacyNote() {
  uni.showModal({
    title: '隐私与授权',
    content:
      '微信登录、手机号绑定、定位与订阅消息均为契约占位，未接入真实微信能力；家庭资料等自愿信息仅存于本机原型数据，刷新后重置。',
    showCancel: false,
  })
}

function showServiceNote() {
  uni.showModal({
    title: '服务说明',
    content: serviceNotice,
    showCancel: false,
  })
}

/** 开发适配器入口：账号切换只存在于 C01（蓝图 S06.5），此处仅提供返回通道。 */
function backToEntry() {
  uni.reLaunch({ url: '/pages/entry/index' })
}

/** 退出登录（契约占位）：清会话回统一入口；微信静默登录下次进入会自动重新登录。 */
function handleLogout() {
  uni.showModal({
    title: '退出登录',
    content: '将清除本端会话并返回统一入口。原型说明：微信小程序为静默登录，下次进入会自动重新登录。',
    confirmText: '退出',
    success: (result) => {
      if (!result.confirm) {
        return
      }
      accountStore.logout()
      uni.reLaunch({ url: '/pages/entry/index' })
    },
  })
}
</script>

<template>
  <view class="page-shell top-level-page" :style="{ paddingTop: safeHeader.pageTopPadding }">
    <view v-if="context" class="profile-header">
      <view class="profile-avatar">
        <wd-icon name="user" size="28px" color="#5d87ff" />
      </view>
      <view>
        <view class="profile-name">
          {{ context.userName }}
        </view>
        <view class="muted-text">
          {{ maskedPhone }}
        </view>
      </view>
    </view>

    <AppPrototypeNotice :text="profileNotice" />

    <view class="page-section">
      <wd-cell-group title="用水账户" border>
        <wd-cell
          title="水卡"
          :value="primaryCard ? `${CARD_STATUS_LABELS[primaryCard.cardStatus]} · ${primaryCard.cardNo}` : '暂无水卡'"
          icon="creditcard"
          is-link
          @click="handleCardTap"
        />
        <wd-cell title="家庭资料" icon="usergroup" is-link @click="goTo('U13')" />
        <wd-cell title="水配送地址" icon="location" is-link @click="goTo('U14')" />
      </wd-cell-group>
    </view>

    <view class="page-section">
      <wd-cell-group title="我的业务能力" border>
        <wd-cell
          title="配送员"
          :value="admission ? ADMISSION_STATUS_LABELS[admission.status] : '未开通'"
          icon="goods"
          is-link
          @click="goTo('D02')"
        />
        <wd-cell
          v-if="hasOwnerView"
          title="机主授权"
          :value="ownerScopeSummary"
          icon="dashboard"
          is-link
          @click="goTo('O01')"
        />
      </wd-cell-group>
      <view v-if="!hasOwnerView" class="muted-text owner-hint">
        机主授权由平台按水站/设备归属确定，暂不支持自助申请。
      </view>
    </view>

    <view class="page-section">
      <wd-cell-group title="账号与服务" border>
        <wd-cell title="消息中心" icon="notification" is-link @click="goTo('C02')" />
        <wd-cell title="隐私与授权" icon="secured" is-link @click="showPrivacyNote" />
        <wd-cell title="服务说明" icon="tips" is-link @click="showServiceNote" />
        <wd-cell
          v-if="isMockBuild"
          title="切换原型账号"
          label="全域 Mock 开发面板：回统一入口选择原型账号"
          icon="setting"
          is-link
          @click="backToEntry"
        />
        <wd-cell title="退出登录" icon="logout" is-link @click="handleLogout" />
      </wd-cell-group>
    </view>
  </view>
</template>

<style scoped lang="scss">
.top-level-page {
  padding-top: calc(env(safe-area-inset-top) + 20px);
}

.profile-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.profile-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: rgba(93, 135, 255, 0.12);
}

.profile-name {
  font-size: 18px;
  font-weight: 600;
}

.owner-hint {
  margin-top: 8px;
  padding: 0 4px;
}
</style>
