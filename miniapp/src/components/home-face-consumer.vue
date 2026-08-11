<script setup lang="ts">
import type { CardSummary, FamilyProfile } from '@/api/card'
import type { StationSummary } from '@/api/catalog'
import type { CourierAdmission } from '@/api/delivery'
import type { PublishedEntry } from '@/api/entry'
import type { OrderItem } from '@/api/order'
import { computed, ref, watch } from 'vue'
import { useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { catalogApi } from '@/api/catalog'
import { deliveryApi } from '@/api/delivery'
import { listPublishedEntries, projectFeatureEntries, projectNotice } from '@/api/entry'
import { orderApi } from '@/api/order'
import { useAccountStore } from '@/store/account'
import {
  CARD_STATUS_LABELS,
  formatBizTimeShort,
  formatFen,
  ORDER_STATUS_LABELS,
  ORDER_TYPE_LABELS,
} from '@/utils/format'
import { goTo } from '@/utils/navigation'
import { scanWaterCode } from '@/utils/scan'

/** C-V2 生活用水首页：只重排已有真实能力，不引入健康目标、配送时效等虚构数据。 */
const props = defineProps<{ refreshTick: number }>()

// 保留远程最新版的入口配置：后台停用某个入口后，C-V2 首页也必须同步隐藏。
const featureEntries = ref<PublishedEntry[]>([])
const maintenanceNotice = ref<string | null>(null)

async function refreshEntries() {
  const published = await listPublishedEntries()
  featureEntries.value = projectFeatureEntries(published)
  maintenanceNotice.value = projectNotice(published)
}

const accountStore = useAccountStore()
const toast = useToast()

const hasCourierWork = computed(() => accountStore.hasCapability('COURIER_WORK'))

const loading = ref(true)
const loadError = ref('')
const primaryCard = ref<CardSummary | null>(null)
const familyProfile = ref<FamilyProfile | null>(null)
const stations = ref<StationSummary[]>([])
const recentOrders = ref<OrderItem[]>([])
const admission = ref<CourierAdmission | null>(null)
const scanning = ref(false)

const balanceLitres = computed(() => {
  if (loading.value || loadError.value || !primaryCard.value) {
    return '--'
  }
  return (primaryCard.value.balanceMl / 1000).toFixed(1)
})

const cardStateText = computed(() => {
  if (loading.value) {
    return '正在读取水卡'
  }
  if (loadError.value) {
    return '水卡数据暂不可用'
  }
  if (!primaryCard.value) {
    return '当前未开通水卡'
  }
  return `${CARD_STATUS_LABELS[primaryCard.value.cardStatus]} · 余额 ${formatFen(primaryCard.value.balanceFen)}`
})

const familyTitle = computed(() => {
  const count = familyProfile.value?.memberCount
  return count && count > 0 ? `${count} 位家庭成员` : '家庭资料'
})

const familyMeta = computed(() => {
  if (primaryCard.value) {
    return `${formatFen(primaryCard.value.balanceFen)} 水卡余额`
  }
  return familyProfile.value ? '查看家庭用水资料' : '去完善家庭资料'
})

const nearbyStation = computed(() => {
  const sorted = [...stations.value].sort((left, right) => {
    const statusRank = { OPEN: 0, MAINTENANCE: 1, CLOSED: 2 }
    const statusDelta = statusRank[left.status] - statusRank[right.status]
    if (statusDelta !== 0) {
      return statusDelta
    }
    return (left.distanceMeters ?? Number.MAX_SAFE_INTEGER)
      - (right.distanceMeters ?? Number.MAX_SAFE_INTEGER)
  })
  return sorted[0] ?? null
})

const stationMeta = computed(() => {
  const station = nearbyStation.value
  if (!station) {
    return '查看附近水站'
  }
  const distance = station.distanceMeters == null
    ? '距离待获取'
    : station.distanceMeters < 1000
      ? `${station.distanceMeters} m`
      : `${(station.distanceMeters / 1000).toFixed(1)} km`
  if (station.status !== 'OPEN') {
    return `${distance} · 暂不可用`
  }
  return `${distance} · ${station.availableOutletCount} 个可用出水口`
})

const deliveryMeta = computed(() => nearbyStation.value
  ? `可从${nearbyStation.value.stationName}下单`
  : '选择水站与收货地址')

/**
 * 无配送能力时的“成为配送员”引导（2026-07-16 决策）：
 * 配送身份可自助获取，按准入状态给出下一步；机主身份不可自助获取，无授权不显示任何入口。
 */
const courierGuidance = computed(() => {
  if (hasCourierWork.value) {
    return null
  }
  const status = admission.value?.status ?? 0
  if (status === 1) {
    return { title: '配送员申请审核中', desc: '等待运营审核，通过后可接单', action: '查看进度' }
  }
  if (status === 3) {
    return { title: '配送能力已停用', desc: '无法接单，如需恢复请联系运营', action: '查看详情' }
  }
  if (status === 4) {
    return { title: '配送员申请被驳回', desc: admission.value?.rejectReason || '可完善资料后重新提交', action: '重新申请' }
  }
  return { title: '成为配送员', desc: '审核通过后可在服务范围内接单', action: '去申请' }
})

watch(() => props.refreshTick, refresh, { immediate: true })
watch(() => props.refreshTick, refreshEntries, { immediate: true })

async function refresh() {
  loading.value = true
  loadError.value = ''
  try {
    const [card, orders, admissionRecord, family, stationList] = await Promise.all([
      cardApi.getPrimaryCard(),
      orderApi.listMyOrders({ size: 3 }),
      deliveryApi.getCourierAdmission().catch(() => null),
      cardApi.getFamilyProfile().catch(() => null),
      catalogApi.listStations().catch(() => []),
    ])
    primaryCard.value = card
    recentOrders.value = orders.list
    admission.value = admissionRecord
    familyProfile.value = family
    stations.value = stationList
  }
  catch (error) {
    primaryCard.value = null
    recentOrders.value = []
    loadError.value = error instanceof Error ? error.message : '首页数据暂不可用'
  }
  finally {
    loading.value = false
  }
}

/** U01 原位扫码：失败原位提示并停留本页，成功以短期会话进入 U04。 */
async function handleScan() {
  if (scanning.value) {
    return
  }
  scanning.value = true
  try {
    const session = await scanWaterCode()
    if (session) {
      goTo('U04', { scanSessionId: session.scanSessionId })
    }
  }
  catch (error) {
    toast.show(error instanceof Error ? error.message : '扫码解析失败，请重试')
  }
  finally {
    scanning.value = false
  }
}

function handleCardTap() {
  if (primaryCard.value) {
    goTo('U11', { cardId: primaryCard.value.cardId })
  }
  else {
    goTo('U10')
  }
}

function handleFutureEntry(name: string) {
  toast.show(`${name}暂未开放`)
}
</script>

<template>
  <view class="cv2-home">
    <view class="water-hero">
      <view class="hero-copy" @click="handleCardTap">
        <view class="hero-kicker">
          可用水量
        </view>
        <view class="hero-balance-row">
          <text class="hero-balance">
            {{ balanceLitres }}
          </text>
          <text class="hero-unit">
            L
          </text>
        </view>
        <view class="hero-divider" />
        <view class="hero-meta">
          {{ cardStateText }}
        </view>
      </view>

      <view class="hero-rings" aria-hidden="true">
        <view class="hero-ring hero-ring-outer" />
        <view class="hero-ring hero-ring-middle" />
        <view class="hero-ring hero-ring-inner" />
        <view class="hero-water-mark">
          水
        </view>
      </view>

      <button class="scan-button" :disabled="scanning" @click.stop="handleScan">
        <view v-if="scanning" class="scan-spinner" />
        <text>{{ scanning ? '识别中…' : '扫码取水' }}</text>
      </button>
    </view>

    <view class="section-heading">
      <view class="section-title">
        家里的水
      </view>
      <view class="section-subtitle">
        水卡、水站和配送都在这里
      </view>
    </view>

    <view class="family-service-grid">
      <view class="family-card" @click="goTo('U13')">
        <view class="family-orbs" aria-hidden="true">
          <view class="family-orb family-orb-large" />
          <view class="family-orb family-orb-small" />
        </view>
        <view class="family-card-content">
          <view class="family-label">
            家庭共享
          </view>
          <view class="family-title">
            {{ familyTitle }}
          </view>
          <view class="family-meta">
            {{ familyMeta }}
          </view>
        </view>
        <view class="family-arrow">
          ↗
        </view>
      </view>

      <view class="service-stack">
        <view class="service-card station-card" @click="goTo('U07')">
          <view class="station-number">
            01
          </view>
          <view class="service-copy">
            <view class="service-eyebrow">
              附近水站
            </view>
            <view class="service-title service-title-ellipsis">
              {{ nearbyStation?.stationName || '查找附近水站' }}
            </view>
            <view class="service-meta service-title-ellipsis">
              {{ stationMeta }}
            </view>
          </view>
          <view class="service-dot station-dot" />
        </view>

        <view class="service-card delivery-card" @click="goTo('U08')">
          <view class="delivery-bottle" aria-hidden="true">
            <view class="delivery-bottle-neck" />
          </view>
          <view class="service-copy">
            <view class="service-eyebrow">
              配送服务
            </view>
            <view class="service-title">
              配送到家
            </view>
            <view class="service-meta service-title-ellipsis">
              {{ deliveryMeta }}
            </view>
          </view>
          <view class="service-dot delivery-dot">
            →
          </view>
        </view>
      </view>
    </view>

    <view class="more-section">
      <view class="more-section-head">
        <view class="more-section-title">
          常用服务
        </view>
        <view class="more-section-link" @click="goTo('U02')">
          全部订单 →
        </view>
      </view>
      <view class="quick-grid">
        <view
          v-for="entry in featureEntries"
          :key="entry.entryKey"
          class="quick-item"
          @click="goTo(entry.routeId!)"
        >
          <view class="quick-icon quick-icon-card">
            {{ entry.entryName.slice(0, 1) }}
          </view>
          <view>{{ entry.entryName }}</view>
        </view>
        <view class="quick-item" @click="handleFutureEntry('商城')">
          <view class="quick-icon quick-icon-future">
            店
          </view>
          <view>商城（后续）</view>
        </view>
        <view class="quick-item" @click="handleFutureEntry('健康')">
          <view class="quick-icon quick-icon-health">
            心
          </view>
          <view>健康（后续）</view>
        </view>
      </view>
    </view>

    <view v-if="maintenanceNotice" class="guidance-card">
      <view class="guidance-desc">
        {{ maintenanceNotice }}
      </view>
    </view>

    <view v-if="courierGuidance" class="guidance-card" @click="goTo('D02')">
      <view>
        <view class="guidance-title">
          {{ courierGuidance.title }}
        </view>
        <view class="guidance-desc">
          {{ courierGuidance.desc }}
        </view>
      </view>
      <view class="guidance-action">
        {{ courierGuidance.action }} →
      </view>
    </view>

    <view class="orders-section">
      <view class="more-section-head">
        <view class="more-section-title">
          最近订单
        </view>
        <view class="more-section-link" @click="goTo('U02')">
          查看全部 →
        </view>
      </view>

      <view v-if="recentOrders.length" class="order-list">
        <view
          v-for="item in recentOrders"
          :key="item.orderNo"
          class="order-item"
          @click="goTo('U06', { orderNo: item.orderNo })"
        >
          <view class="order-mark" />
          <view class="order-main">
            <view class="order-title-row">
              <view class="order-title">
                {{ ORDER_TYPE_LABELS[item.orderType] }}
              </view>
              <view class="order-amount">
                {{ formatFen(item.orderAmountFen) }}
              </view>
            </view>
            <view class="order-meta-row">
              <view>{{ formatBizTimeShort(item.createTime) }}</view>
              <view class="order-status">
                {{ ORDER_STATUS_LABELS[item.orderStatus] }}
              </view>
            </view>
          </view>
          <view class="order-arrow">
            ›
          </view>
        </view>
      </view>
      <view v-else class="order-empty">
        {{ loading ? '正在读取订单…' : loadError || '暂无消费订单' }}
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.cv2-home {
  box-sizing: border-box;
  padding: 0 40rpx 44rpx;
  color: #173f32;
}

.water-hero {
  position: relative;
  box-sizing: border-box;
  height: 476rpx;
  padding: 40rpx 34rpx;
  overflow: hidden;
  border-radius: 62rpx;
  background: #173f32;
  color: #fffdf7;
}

.hero-copy {
  position: relative;
  z-index: 2;
  width: 360rpx;
}

.hero-kicker {
  color: #cce0b7;
  font-size: 26rpx;
  font-weight: 600;
  letter-spacing: 2rpx;
}

.hero-balance-row {
  display: flex;
  align-items: flex-end;
  height: 104rpx;
  margin-top: 8rpx;
}

.hero-balance {
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 78rpx;
  font-weight: 700;
  line-height: 1;
}

.hero-unit {
  padding: 0 0 10rpx 10rpx;
  color: #e4efd7;
  font-size: 28rpx;
  font-weight: 600;
}

.hero-divider {
  width: 344rpx;
  height: 8rpx;
  margin-top: 18rpx;
  border-radius: 999rpx;
  background: #456f45;
}

.hero-meta {
  margin-top: 14rpx;
  overflow: hidden;
  color: rgba(255, 253, 247, 0.72);
  font-size: 23rpx;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-rings {
  position: absolute;
  top: 82rpx;
  right: -82rpx;
  width: 330rpx;
  height: 330rpx;
}

.hero-ring {
  position: absolute;
  top: 50%;
  left: 50%;
  box-sizing: border-box;
  border-radius: 50%;
  transform: translate(-50%, -50%);
}

.hero-ring-outer {
  width: 330rpx;
  height: 330rpx;
  background: rgba(69, 111, 69, 0.34);
}

.hero-ring-middle {
  width: 226rpx;
  height: 226rpx;
  background: #456f45;
}

.hero-ring-inner {
  width: 132rpx;
  height: 132rpx;
  background: #e4efd7;
}

.hero-water-mark {
  position: absolute;
  top: 50%;
  left: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80rpx;
  height: 80rpx;
  border-radius: 50%;
  background: #fffdf7;
  color: #456f45;
  font-family: 'STKaiti', 'KaiTi', serif;
  font-size: 28rpx;
  font-weight: 700;
  transform: translate(-50%, -50%);
}

.scan-button {
  position: absolute;
  bottom: 38rpx;
  left: 34rpx;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 196rpx;
  height: 60rpx;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 999rpx;
  background: #d9ea7a;
  color: #173f32;
  font-size: 24rpx;
  font-weight: 700;
  line-height: 1;
}

.scan-button::after {
  border: 0;
}

.scan-button[disabled] {
  opacity: 0.72;
}

.scan-spinner {
  box-sizing: border-box;
  width: 24rpx;
  height: 24rpx;
  margin-right: 10rpx;
  border: 3rpx solid rgba(23, 63, 50, 0.25);
  border-top-color: #173f32;
  border-radius: 50%;
  animation: cv2-spin 0.8s linear infinite;
}

@keyframes cv2-spin {
  to {
    transform: rotate(360deg);
  }
}

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin: 54rpx 8rpx 24rpx;
}

.section-title {
  font-size: 38rpx;
  font-weight: 700;
}

.section-subtitle {
  color: rgba(23, 63, 50, 0.56);
  font-size: 21rpx;
}

.family-service-grid {
  display: grid;
  grid-template-columns: 0.94fr 1.06fr;
  gap: 24rpx;
  height: 342rpx;
}

.family-card,
.service-card,
.more-section,
.guidance-card,
.orders-section {
  box-sizing: border-box;
  border: 1rpx solid rgba(23, 63, 50, 0.05);
  box-shadow: 0 18rpx 40rpx rgba(23, 63, 50, 0.05);
}

.family-card {
  position: relative;
  padding: 28rpx 24rpx;
  overflow: hidden;
  border-radius: 38rpx;
  background: #e4efd7;
}

.family-orbs {
  position: absolute;
  top: 22rpx;
  left: 18rpx;
  width: 150rpx;
  height: 112rpx;
}

.family-orb {
  position: absolute;
  border-radius: 50%;
}

.family-orb-large {
  top: 0;
  left: 0;
  width: 86rpx;
  height: 86rpx;
  background: #cce0b7;
}

.family-orb-small {
  top: 34rpx;
  left: 58rpx;
  width: 68rpx;
  height: 68rpx;
  background: #fffdf7;
}

.family-card-content {
  position: absolute;
  right: 24rpx;
  bottom: 30rpx;
  left: 24rpx;
}

.family-label,
.service-eyebrow {
  color: rgba(23, 63, 50, 0.58);
  font-size: 20rpx;
  font-weight: 600;
}

.family-title {
  margin-top: 6rpx;
  font-size: 29rpx;
  font-weight: 700;
}

.family-meta {
  margin-top: 8rpx;
  overflow: hidden;
  color: #456f45;
  font-size: 21rpx;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.family-arrow {
  position: absolute;
  top: 24rpx;
  right: 24rpx;
  color: #456f45;
  font-size: 30rpx;
}

.service-stack {
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}

.service-card {
  position: relative;
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
  padding: 22rpx 22rpx;
  overflow: hidden;
  border-radius: 32rpx;
  background: #fffdf7;
}

.station-number {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 66rpx;
  height: 66rpx;
  margin-right: 18rpx;
  border-radius: 50%;
  background: #e4efd7;
  color: #456f45;
  font-family: Georgia, serif;
  font-size: 25rpx;
  font-weight: 700;
}

.service-copy {
  min-width: 0;
  padding-right: 22rpx;
}

.service-title {
  margin-top: 3rpx;
  font-size: 25rpx;
  font-weight: 700;
}

.service-title-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-meta {
  margin-top: 5rpx;
  color: rgba(23, 63, 50, 0.56);
  font-size: 18rpx;
}

.service-dot {
  position: absolute;
  right: 18rpx;
  bottom: 18rpx;
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
}

.station-dot {
  background: #e6b84f;
}

.delivery-card {
  background: #cce0b7;
}

.delivery-bottle {
  position: relative;
  flex: 0 0 auto;
  width: 56rpx;
  height: 72rpx;
  margin-right: 22rpx;
  border-radius: 22rpx 22rpx 18rpx 18rpx;
  background: #456f45;
}

.delivery-bottle-neck {
  position: absolute;
  top: -10rpx;
  left: 18rpx;
  width: 20rpx;
  height: 14rpx;
  border-radius: 5rpx 5rpx 0 0;
  background: #456f45;
}

.delivery-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38rpx;
  height: 38rpx;
  background: #d9ea7a;
  color: #173f32;
  font-size: 22rpx;
}

.more-section,
.orders-section {
  margin-top: 30rpx;
  padding: 30rpx;
  border-radius: 38rpx;
  background: #fffdf7;
}

.more-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.more-section-title {
  font-size: 30rpx;
  font-weight: 700;
}

.more-section-link {
  color: #456f45;
  font-size: 21rpx;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12rpx;
  margin-top: 26rpx;
}

.quick-item {
  display: flex;
  align-items: center;
  flex-direction: column;
  min-width: 0;
  color: rgba(23, 63, 50, 0.72);
  font-size: 19rpx;
  text-align: center;
}

.quick-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 68rpx;
  height: 68rpx;
  margin-bottom: 12rpx;
  border-radius: 24rpx;
  font-size: 23rpx;
  font-weight: 700;
}

.quick-icon-card,
.quick-icon-health {
  background: #e4efd7;
}

.quick-icon-recharge,
.quick-icon-future {
  background: #f5e9c8;
  color: #8d6728;
}

.quick-icon-message {
  background: #173f32;
  color: #fffdf7;
}

.guidance-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 30rpx;
  padding: 28rpx 30rpx;
  border-radius: 32rpx;
  background: #e4efd7;
}

.guidance-title {
  font-size: 25rpx;
  font-weight: 700;
}

.guidance-desc {
  margin-top: 6rpx;
  color: rgba(23, 63, 50, 0.58);
  font-size: 20rpx;
}

.guidance-action {
  flex: 0 0 auto;
  margin-left: 18rpx;
  color: #456f45;
  font-size: 21rpx;
  font-weight: 700;
}

.order-list {
  margin-top: 22rpx;
}

.order-item {
  display: flex;
  align-items: center;
  padding: 22rpx 0;
  border-top: 1rpx solid rgba(23, 63, 50, 0.08);
}

.order-mark {
  flex: 0 0 auto;
  width: 8rpx;
  height: 62rpx;
  margin-right: 18rpx;
  border-radius: 999rpx;
  background: #cce0b7;
}

.order-main {
  flex: 1;
  min-width: 0;
}

.order-title-row,
.order-meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.order-title,
.order-amount {
  font-size: 24rpx;
  font-weight: 700;
}

.order-meta-row {
  margin-top: 8rpx;
  color: rgba(23, 63, 50, 0.5);
  font-size: 19rpx;
}

.order-status {
  color: #456f45;
}

.order-arrow {
  margin-left: 14rpx;
  color: #456f45;
  font-size: 38rpx;
}

.order-empty {
  margin-top: 22rpx;
  padding: 36rpx 0 20rpx;
  color: rgba(23, 63, 50, 0.5);
  font-size: 22rpx;
  text-align: center;
}
</style>
