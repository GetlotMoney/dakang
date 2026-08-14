<script setup lang="ts">
import type { DeviceSummary, OwnerOverview } from '@/api/device'
import AppPageState from '@/components/app-page-state.vue'
import { computed, ref, watch } from 'vue'
import { ContractError } from '@/api/common'
import { deviceApi } from '@/api/device'
import { formatBizTimeShort, formatFen, formatMl } from '@/utils/format'
import { goTo } from '@/utils/navigation'

/** 经营态：U01 的机主视角（主态自适应三张脸之一，只读监控，无任何控制入口）。 */
const props = defineProps<{ refreshTick: number }>()
const emit = defineEmits<{ switchFace: [face: 'life'] }>()

const loading = ref(true)
const blockedReason = ref('')
const overview = ref<OwnerOverview | null>(null)
const attentionDevices = ref<DeviceSummary[]>([])

const attentionTop = computed(() => attentionDevices.value.slice(0, 2))

/** 故障优先于离线：一台既离线又报故障的机器，故障码才是要先看的那条。 */
function attentionLabel(device: DeviceSummary): string {
  if (device.runStatus === 'FAULT') {
    return device.lastFaultCode ? `故障 ${device.lastFaultCode}` : '故障'
  }
  return '离线'
}

watch(() => props.refreshTick, refresh, { immediate: true })

async function refresh() {
  loading.value = true
  blockedReason.value = ''
  try {
    const [summary, devices] = await Promise.all([
      deviceApi.getOwnerOverview(),
      deviceApi.listOwnerDevices(),
    ])
    overview.value = summary
    attentionDevices.value = devices.filter(
      item => item.onlineStatus === 'OFFLINE' || item.runStatus === 'FAULT',
    )
  }
  catch (error) {
    overview.value = null
    attentionDevices.value = []
    blockedReason.value = error instanceof ContractError ? error.message : '经营数据暂不可用'
  }
  finally {
    loading.value = false
  }
}
</script>

<template>
  <view>
    <!-- 待处理设备排在最前：机主打开首页要先看到哪台机器停了，而不是先看总数。
         正常设备不上底色，只有离线/故障这一组用警示浅底，避免整屏都在喊。 -->
    <view v-if="attentionTop.length" class="alerts">
      <view class="alerts__head">
        <text class="alerts__title">
          待关注设备
        </text>
        <text class="alerts__count num">
          {{ attentionDevices.length }}
        </text>
      </view>
      <view
        v-for="item in attentionTop"
        :key="item.deviceNo"
        class="alerts__row pressable"
        @click="goTo('O03', { deviceNo: item.deviceNo })"
      >
        <view class="alerts__main">
          <text class="alerts__device">
            {{ item.deviceName || item.deviceNo }}
          </text>
          <text class="alerts__meta">
            {{ item.stationName }} · 心跳 {{ formatBizTimeShort(item.lastHeartbeat) }}
          </text>
        </view>
        <view class="alerts__state">
          <text class="alerts__badge">
            {{ attentionLabel(item) }}
          </text>
          <wd-icon name="arrow-right" size="16px" />
        </view>
      </view>
      <view
        v-if="attentionDevices.length > attentionTop.length"
        class="alerts__more pressable"
        @click="goTo('O02')"
      >
        查看全部设备
        <wd-icon name="arrow-right" size="14px" />
      </view>
    </view>

    <template v-if="overview">
      <!-- 设备在位情况：四个计数分栏，离线与故障非零时才转警示色 -->
      <view class="metrics">
        <view class="metrics__head">
          <!-- 不挂「只读监控」tag：这一面本来就没有任何控制入口，
               看不到开关就是不能控制，不需要再声明一次 -->
          <text class="metrics__title">
            设备状态
          </text>
        </view>
        <view class="metrics__row">
          <view class="metrics__cell">
            <text class="metrics__value num">
              {{ overview.deviceCount }}
            </text>
            <text class="metrics__label">
              设备
            </text>
          </view>
          <view class="metrics__cell">
            <text class="metrics__value num">
              {{ overview.onlineCount }}
            </text>
            <text class="metrics__label">
              在线
            </text>
          </view>
          <view class="metrics__cell">
            <text class="metrics__value num" :class="{ 'metrics__value--warn': overview.offlineCount > 0 }">
              {{ overview.offlineCount }}
            </text>
            <text class="metrics__label">
              离线
            </text>
          </view>
          <view class="metrics__cell">
            <text class="metrics__value num" :class="{ 'metrics__value--warn': overview.faultCount > 0 }">
              {{ overview.faultCount }}
            </text>
            <text class="metrics__label">
              故障
            </text>
          </view>
        </view>
      </view>

      <!-- 经营指标与设备计数分成两组：金额是订单成交额，与可提现收益不是一个口径，
           标签写明「成交额」，钱包入口另走 O06。 -->
      <view class="revenue pressable" @click="goTo('O04')">
        <view class="revenue__head">
          <text class="revenue__title">
            本期经营
          </text>
          <wd-icon name="arrow-right" size="16px" />
        </view>
        <view class="revenue__amount num">
          {{ formatFen(overview.orderAmountFen) }}
        </view>
        <text class="revenue__label">
          订单成交额
        </text>
        <view class="revenue__row">
          <view class="revenue__item">
            <text class="revenue__item-label">
              订单
            </text>
            <text class="revenue__item-value num">
              {{ overview.orderCount }} 单
            </text>
          </view>
          <view class="revenue__item">
            <text class="revenue__item-label">
              出水
            </text>
            <text class="revenue__item-value num">
              {{ formatMl(overview.actualVolumeMl) }}
            </text>
          </view>
        </view>
      </view>
    </template>
    <view v-else-if="blockedReason" class="blocked">
      {{ blockedReason }}
    </view>
    <AppPageState v-else state="loading" :row-col="[1, { width: '70%' }]" />

    <view class="nav">
      <wd-cell-group border>
        <wd-cell title="设备列表" icon="computer" is-link @click="goTo('O02')" />
        <wd-cell title="交易快照" icon="chart" is-link @click="goTo('O04')" />
        <!-- 收益钱包必须在这里：本期经营给的是订单成交额，机主看完要问「我能拿的钱在哪」，
             原来这一屏没有任何去处，只有一条「非可提现金额」的免责横幅。用入口代替解释。 -->
        <wd-cell title="收益钱包" icon="money-circle" is-link @click="goTo('O06')" />
        <wd-cell title="报修与配件" icon="tools" is-link @click="goTo('O05')" />
        <wd-cell
          title="生活用水服务"
          icon="user"
          is-link
          @click="emit('switchFace', 'life')"
        />
      </wd-cell-group>
    </view>
  </view>
</template>

<style scoped lang="scss">
.alerts {
  margin-top: var(--gap-hero);
  padding: var(--sp-3) var(--sp-4) var(--sp-2);
  border-radius: var(--r-md);
  background: var(--tint-warning);

  &__head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    padding-bottom: var(--sp-2);
  }

  &__title {
    color: var(--app-color-warning-text);
    font-size: var(--fs-title);
    font-weight: 700;
  }

  &__count {
    color: var(--app-color-warning-text);
    font-size: var(--fs-title);
    font-weight: 700;
  }

  // 每行都挂上边线，行与行、行与标题之间的分隔一次说清；
  // 不用 :first-of-type——同级兄弟里 head 和 more 也是 view，of-type 会选错元素。
  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--sp-3) 0;
    border-top: 1px solid var(--line-1);
  }

  &__main {
    min-width: 0;
    padding-right: var(--sp-3);
  }

  &__device,
  &__meta {
    display: block;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__device {
    font-size: var(--fs-body);
    font-weight: 600;
  }

  &__meta {
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-note);
  }

  &__state {
    display: flex;
    flex: none;
    align-items: center;
    gap: var(--sp-1);
  }

  &__badge {
    color: var(--app-color-danger);
    font-size: var(--fs-caption);
    font-weight: 600;
  }

  &__more {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 2px;
    padding: var(--sp-2) 0;
    border-top: 1px solid var(--line-1);
    color: var(--app-color-warning-text);
    font-size: var(--fs-caption);
  }
}

.metrics {
  margin-top: var(--gap-group);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__title {
    font-size: var(--fs-title);
    font-weight: 600;
  }

  &__row {
    display: flex;
    margin-top: var(--sp-3);
  }

  &__cell {
    flex: 1;
    min-width: 0;
    text-align: center;

    & + & {
      border-left: 1px solid var(--line-1);
    }
  }

  &__value {
    display: block;
    font-size: var(--fs-metric);
    font-weight: 700;

    &--warn {
      color: var(--app-color-danger);
    }
  }

  &__label {
    display: block;
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }
}

// 经营金额是本面唯一的白色内容面：它要被单独读，不与设备计数混在一张卡里。
.revenue {
  margin-top: var(--gap-block);
  padding: var(--sp-4);
  border-radius: var(--r-md);
  background: var(--app-bg-card);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__title {
    font-size: var(--fs-title);
    font-weight: 600;
  }

  &__amount {
    margin-top: var(--sp-2);
    font-size: var(--fs-metric);
    font-weight: 700;
    line-height: 1.15;
  }

  &__label {
    display: block;
    margin-top: var(--sp-1);
    color: var(--app-text-tertiary);
    font-size: var(--fs-caption);
  }

  &__row {
    display: flex;
    gap: var(--sp-5);
    margin-top: var(--sp-3);
    padding-top: var(--sp-3);
    border-top: 1px solid var(--line-1);
  }

  &__item {
    display: flex;
    gap: var(--sp-1);
    align-items: baseline;
    min-width: 0;
  }

  &__item-label {
    color: var(--app-text-tertiary);
    font-size: var(--fs-caption);
  }

  &__item-value {
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
    font-weight: 600;
  }
}

.blocked {
  margin-top: var(--gap-group);
  color: var(--app-color-danger);
  font-size: var(--fs-caption);
}

.nav {
  margin-top: var(--gap-group);
  overflow: hidden;
  border-radius: var(--r-md);
}
</style>
