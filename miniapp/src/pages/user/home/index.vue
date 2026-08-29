<script setup lang="ts">
import type { HomeFace } from '@/utils/home-face'
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { deliveryApi } from '@/api/delivery'
import { deviceApi } from '@/api/device'
import { messageApi } from '@/api/message'
import HomeFaceConsumer from '@/components/home-face-consumer.vue'
import HomeFaceCourier from '@/components/home-face-courier.vue'
import HomeFaceOwner from '@/components/home-face-owner.vue'
import HomeFaceChannel from '@/components/home-face-channel.vue'
import HomeFaceRegion from '@/components/home-face-region.vue'
import { useAccountStore } from '@/store/account'
import {
  availableHomeFaces,
  deriveDefaultHomeFace,
  HOME_FACE_LABELS,
} from '@/utils/home-face'
import { maskPhone } from '@/utils/format'
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
const faceMenuOpen = ref(false)
const refreshTick = ref(0)
const buildFingerprint = __DAKANG_BUILD_FINGERPRINT__

const faces = computed(() => availableHomeFaces(context.value))
const faceMeta: Record<HomeFace, { label: string, icon: string }> = {
  life: { label: '生活服务', icon: 'home' },
  courier: { label: '配送工作台', icon: 'service' },
  owner: { label: '机主经营', icon: 'computer' },
  channel: { label: '渠道推广', icon: 'share' },
  region: { label: '区域运营', icon: 'location' },
}
const faceTips: Record<HomeFace, string> = {
  life: '扫码取水、订水和日常服务',
  courier: '接单、配送和履约处理',
  owner: '设备、经营与收益概览',
  channel: '直属机主和推广收益',
  region: '区域血缘和公域线索',
}
const faceOptions = computed(() => faces.value.map(item => ({
  label: HOME_FACE_LABELS[item],
  value: item,
  tip: faceTips[item],
})))

/** 副标题：已绑号显示脱敏号码，未绑号（仅微信身份建号）提示去补绑，绝不对空号码取子串。 */
const identityLine = computed(() => {
  const ctx = context.value
  if (!ctx) {
    return ''
  }
  const masked = maskPhone(ctx.userPhone)
  return masked ? `${ctx.userName} · ${masked}` : `${ctx.userName} · 未绑手机号`
})

function storageKey() {
  return `dakang-home-face:${context.value?.accountId ?? 'anonymous'}`
}

function savedFace(): HomeFace | null {
  const value = uni.getStorageSync(storageKey())
  return value === 'life' || value === 'courier' || value === 'owner' || value === 'channel' || value === 'region' ? value : null
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

function toggleFaceMenu() {
  faceMenuOpen.value = !faceMenuOpen.value
}

function chooseFace(next: HomeFace) {
  faceMenuOpen.value = false
  selectFace(next)
}
</script>

<template>
  <view class="page-shell top-level-page" :style="{ paddingTop: safeHeader.pageTopPadding }">
    <image class="home-watermark" src="/static/brand/page-watermark.jpg" mode="scaleToFill" />
    <wd-toast />
    <view v-if="faceMenuOpen" class="face-dropdown-backdrop" @click="faceMenuOpen = false" />
    <view class="e2e-build-fingerprint" aria-hidden="true">
      BUILD={{ buildFingerprint }}
    </view>
    <!-- 紧凑品牌头：品牌名在这里只做身份标识，首屏的品牌陈述由下方产品横幅承担，
         所以这一行压到标题字阶，不与横幅争视觉权重。
         分两行是被 320 逼出来的：微信胶囊要避让掉右侧约 1/3 宽，视角分段控件再占一段，
         三样挤在同一行时 320 上「六维达康」自己都会折行、手机号被截成「138****1…」。
         第一行只留品牌名与消息入口（它必须避让胶囊），第二行落在胶囊下方，
         可用整幅宽度放身份与视角切换。 -->
    <view class="home-header">
      <view class="home-title">
        六维达康
      </view>
      <view class="home-message" :style="{ marginRight: safeHeader.capsuleAvoidWidth }" @click="goTo('C02')">
        <wd-badge :model-value="unreadCount" :hidden="unreadCount === 0" :max="99">
          <wd-icon name="notification" size="24px" />
        </wd-badge>
      </view>
    </view>
    <view
      v-if="identityLine || faces.length > 1"
      class="home-subheader"
      :class="{ 'is-face-menu-open': faceMenuOpen }"
    >
      <view v-if="identityLine" class="home-identity-line">
        {{ identityLine }}
      </view>
      <view v-if="faces.length > 1" class="face-switch-card">
        <view class="face-switch-icon">
          <wd-icon :name="faceMeta[face].icon" size="18px" />
        </view>
        <view class="face-switch-copy">
          <text class="face-switch-eyebrow">
            当前工作台
          </text>
          <text class="face-switch-note">
            切换只改变首页视角
          </text>
        </view>
        <view class="face-switch-control">
          <view
            class="face-picker-trigger"
            :class="{ 'is-open': faceMenuOpen }"
            @click.stop="toggleFaceMenu"
          >
            <text class="face-picker-label">
              {{ faceMeta[face].label }}
            </text>
            <view class="face-picker-chevron" :class="{ 'is-open': faceMenuOpen }">
              <wd-icon name="chevron-down" size="15px" />
            </view>
          </view>
          <view v-if="faceMenuOpen" class="face-dropdown-panel">
            <view
              v-for="option in faceOptions"
              :key="option.value"
              class="face-dropdown-option"
              :class="{ 'is-selected': option.value === face }"
              @click.stop="chooseFace(option.value)"
            >
              <view class="face-dropdown-copy">
                <text class="face-dropdown-label">
                  {{ option.label }}
                </text>
                <text class="face-dropdown-tip">
                  {{ option.tip }}
                </text>
              </view>
              <wd-icon v-if="option.value === face" name="check" size="16px" />
            </view>
          </view>
        </view>
      </view>
    </view>

    <!-- U01 不挂常驻提示条。原来两条都不合格：机主那条把「订单成交额」标签复述一遍再加否定句，
         是免责声明而非事实，且首页没有提现动作可做错——真正缺的是收益钱包入口，已补进机主面导航；
         配送那条警告的接单/离站/送达三个动作都发生在任务详情，那里各自有二次确认，
         首页挂一条「你在这里做不了的动作」的横幅只是噪音。 -->
    <template v-if="context">
      <HomeFaceCourier v-if="face === 'courier'" class="home-face" :refresh-tick="refreshTick" @switch-face="selectFace" />
      <HomeFaceOwner v-else-if="face === 'owner'" class="home-face" :refresh-tick="refreshTick" @switch-face="selectFace" />
      <HomeFaceChannel v-else-if="face === 'channel'" class="home-face" :refresh-tick="refreshTick" />
      <HomeFaceRegion v-else-if="face === 'region'" class="home-face" :refresh-tick="refreshTick" />
      <HomeFaceConsumer v-else class="home-face" :refresh-tick="refreshTick" />
    </template>
  </view>
</template>

<style scoped lang="scss">
.top-level-page {
  position: relative;
  overflow: hidden;
  padding-top: calc(env(safe-area-inset-top) + 20px);
}

.home-watermark {
  position: absolute;
  z-index: 0;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.home-header,
.home-subheader,
.home-face {
  position: relative;
  z-index: 1;
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
  flex: none;
  font-size: var(--fs-title);
  font-weight: 700;
  letter-spacing: 1px;
  white-space: nowrap;
}

.home-subheader {
  margin-top: var(--sp-2);
}

.home-subheader.is-face-menu-open {
  z-index: 20;
}

.face-dropdown-backdrop {
  position: fixed;
  z-index: 10;
  inset: 0;
}

.face-switch-card {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
  padding: 8px 8px 8px 10px;
  border: 1px solid rgb(93 135 255 / 12%);
  border-radius: var(--r-lg);
  background: rgb(255 255 255 / 76%);
  box-shadow: 0 10px 28px rgb(42 91 178 / 9%);
  backdrop-filter: blur(12px);
}

.home-identity-line {
  overflow: hidden;
  min-width: 0;
  color: var(--app-text-secondary);
  font-size: var(--fs-note);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.face-switch-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 12px;
  background: var(--tint-primary);
  color: var(--app-color-primary);
}

.face-switch-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.face-switch-eyebrow {
  color: var(--app-text-primary);
  font-size: var(--fs-note);
  font-weight: 650;
}

.face-switch-note {
  margin-top: 2px;
  color: var(--app-text-tertiary);
  font-size: 10px;
  white-space: nowrap;
}

.face-switch-control {
  position: relative;
  flex: 0 0 148px;
  min-width: 0;
}

.face-picker-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border-radius: var(--r-pill);
  background: var(--tint-primary);
  color: var(--app-color-primary);
}

.face-picker-trigger.is-open {
  box-shadow: inset 0 0 0 1px rgb(46 124 246 / 20%);
}

.face-picker-label {
  overflow: hidden;
  min-width: 0;
  font-size: var(--fs-body);
  font-weight: 650;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.face-picker-chevron {
  display: flex;
  flex: none;
  transition: transform 160ms ease;
}

.face-picker-chevron.is-open {
  transform: rotate(180deg);
}

.face-dropdown-panel {
  position: absolute;
  z-index: 30;
  top: calc(100% + 8px);
  right: 0;
  overflow: hidden;
  width: 236px;
  padding: 6px;
  border: 1px solid rgb(66 101 158 / 12%);
  border-radius: var(--r-lg);
  background: #fff;
  box-shadow: 0 18px 42px rgb(35 66 120 / 20%);
}

.face-dropdown-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 48px;
  padding: 5px 10px;
  border-radius: var(--r-md);
  color: var(--app-text-secondary);
}

.face-dropdown-option.is-selected {
  background: var(--tint-primary);
  color: var(--app-color-primary);
}

.face-dropdown-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.face-dropdown-label {
  font-size: var(--fs-body);
  font-weight: 650;
}

.face-dropdown-tip {
  overflow: hidden;
  margin-top: 2px;
  color: var(--app-text-tertiary);
  font-size: 10px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.home-message {
  padding: var(--sp-1);
}
</style>
