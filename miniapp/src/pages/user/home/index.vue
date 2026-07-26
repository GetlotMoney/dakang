<script setup lang="ts">
import type { HomeFace } from '@/utils/home-face'
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { deliveryApi } from '@/api/delivery'
import { deviceApi } from '@/api/device'
import { messageApi } from '@/api/message'
import { buildRuntimeModes, currentMode } from '@/api/runtime'
import HomeFaceConsumer from '@/components/home-face-consumer.vue'
import HomeFaceCourier from '@/components/home-face-courier.vue'
import HomeFaceOwner from '@/components/home-face-owner.vue'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
import { homeCourierNoticeText } from '@/components/runtime-notice'
import { useAccountStore } from '@/store/account'
import {
  availableHomeFaces,
  deriveDefaultHomeFace,
  HOME_FACE_LABELS,
} from '@/utils/home-face'
import { goTo } from '@/utils/navigation'
import { getWxSafeHeader } from '@/utils/safe-area'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '首页',
  },
})

const accountStore = useAccountStore()
const context = computed(() => accountStore.context)
const safeHeader = getWxSafeHeader()

const unreadCount = ref(0)
const face = ref<HomeFace>('life')
const refreshTick = ref(0)
const buildFingerprint = __DAKANG_BUILD_FINGERPRINT__
const waterModes = [currentMode('device'), currentMode('order'), currentMode('card')]
const isRealWaterFlow = waterModes.every(mode => mode === 'real')
// 统一走 buildRuntimeModes()：与入口页同一发射点，杜绝两页各写一份导致段数不一致（E1b 安全闸曾因此恒失败）。
const runtimeContract = buildRuntimeModes()

const faces = computed(() => availableHomeFaces(context.value))

/**
 * 按面提示（D3）：生活（消费者）面卡余额、订单已接真且持久，去除“刷新后重置”失真描述；
 * 配送面按 delivery 构建期模式分流（条目在 runtime-notice，real 下接单/履约/申诉真实生效）；
 * 机主面 owner 域未接真，保留原型演示提示，不误标成真。
 */
const noticeText = computed(() =>
  face.value === 'life'
    ? isRealWaterFlow
      ? '扫码取水、订单与水卡余额来自真实接口；真实操作必须建立正式微信会话，商城/健康等入口仍为后续演示。'
      : '扫码取水、订单与水卡余额均为 Mock/固定快照；页面用于业务流转定型，不发生真实扣款或设备出水。'
    : face.value === 'courier'
      ? homeCourierNoticeText(currentMode('delivery'))
      : '原型演示数据：不触发真实支付、设备指令或微信消息，刷新后重置。',
)

function storageKey() {
  return `dakang-home-face:${context.value?.accountId ?? 'anonymous'}`
}

function savedFace(): HomeFace | null {
  const value = uni.getStorageSync(storageKey())
  return value === 'life' || value === 'courier' || value === 'owner' ? value : null
}

onShow(async () => {
  if (!accountStore.restored) {
    await accountStore.restoreSession().catch(() => null)
  }
  if (!accountStore.context) {
    return
  }
  await deriveFace()
  refreshTick.value += 1
  unreadCount.value = (await messageApi.listMessages().catch(() => []))
    .filter(item => item.unread)
    .length
})

/**
 * 主态判定（2026-07-16 决策）：记忆视角优先；否则按 进行中任务＞待关注设备＞单一工作能力＞生活。
 * 能力被撤销（如 PC 停用配送）时记忆视角自动失效并回退。
 */
async function deriveFace() {
  const saved = savedFace()
  if (saved && faces.value.includes(saved)) {
    face.value = saved
    return
  }
  const [activeTaskCount, attentionDeviceCount] = await Promise.all([
    faces.value.includes('courier')
      ? deliveryApi
          .listTasks('active')
          .then(list => list.filter(item => [2, 3, 4].includes(item.taskStatus)).length)
          .catch(() => 0)
      : Promise.resolve(0),
    faces.value.includes('owner')
      ? deviceApi
          .listOwnerDevices()
          .then(list => list.filter(item => item.onlineStatus === 'OFFLINE' || item.runStatus === 'FAULT').length)
          .catch(() => 0)
      : Promise.resolve(0),
  ])
  face.value = deriveDefaultHomeFace(context.value, { activeTaskCount, attentionDeviceCount })
}

/** 视角切换是 U01 局部 UI 偏好：仅换首屏形态并本地记忆，不改变任何权限、路由或审计口径。 */
function selectFace(next: HomeFace) {
  if (face.value === next) {
    return
  }
  face.value = next
  refreshTick.value += 1
  uni.setStorageSync(storageKey(), next)
}
</script>

<template>
  <view class="page-shell top-level-page" :style="{ paddingTop: safeHeader.pageTopPadding }">
    <wd-toast />
    <view class="e2e-build-fingerprint" aria-hidden="true">
      BUILD={{ buildFingerprint }};{{ runtimeContract }}
    </view>
    <view class="home-header">
      <view>
        <view class="home-title">
          六维达康智慧水站
        </view>
        <view v-if="context" class="muted-text">
          {{ context.userName }} · {{ context.userPhone.slice(0, 3) }}****{{ context.userPhone.slice(-4) }}
        </view>
      </view>
      <view class="home-header-actions" :style="{ marginRight: safeHeader.capsuleAvoidWidth }">
        <view v-if="faces.length > 1" class="face-capsule">
          <view
            v-for="item in faces"
            :key="item"
            class="face-pill"
            :class="{ 'face-pill-active': face === item }"
            @click="selectFace(item)"
          >
            {{ HOME_FACE_LABELS[item] }}
          </view>
        </view>
        <view class="home-message" @click="goTo('C02')">
          <wd-badge :model-value="unreadCount" :hidden="unreadCount === 0" :max="99">
            <wd-icon name="notification" size="24px" />
          </wd-badge>
        </view>
      </view>
    </view>

    <AppPrototypeNotice :text="noticeText" />

    <template v-if="context">
      <HomeFaceCourier v-if="face === 'courier'" :refresh-tick="refreshTick" @switch-face="selectFace" />
      <HomeFaceOwner v-else-if="face === 'owner'" :refresh-tick="refreshTick" @switch-face="selectFace" />
      <HomeFaceConsumer v-else :refresh-tick="refreshTick" />
    </template>
  </view>
</template>

<style scoped lang="scss">
.top-level-page {
  padding-top: calc(env(safe-area-inset-top) + 20px);
}

.e2e-build-fingerprint {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

.home-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.home-title {
  font-size: 22px;
  font-weight: 600;
}

.home-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.face-capsule {
  display: flex;
  align-items: center;
  padding: 2px;
  border-radius: 999px;
  background: rgba(93, 135, 255, 0.1);
}

.face-pill {
  padding: 4px 10px;
  border-radius: 999px;
  color: var(--app-text-secondary);
  font-size: 12px;
}

.face-pill-active {
  background: var(--app-color-primary);
  color: #fff;
  font-weight: 600;
}

.home-message {
  padding: 6px;
}
</style>
