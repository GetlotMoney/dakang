<script setup lang="ts">
import type { StationSummary } from '@/api/catalog'
import type { TagTone } from '@/utils/format'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { catalogApi } from '@/api/catalog'
import { ContractError } from '@/api/common'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import { setDraftStation } from '@/store/delivery-draft'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '水站目录',
  },
})

const toast = useToast()
const keyword = ref('')

/** 水站营业状态中文口径（utils/format 暂无该映射，页面内先行冻结）。 */
const STATION_STATUS_LABELS: Record<StationSummary['status'], string> = {
  OPEN: '营业中',
  MAINTENANCE: '检修中',
  CLOSED: '已关闭',
}

const STATION_STATUS_TONES: Record<StationSummary['status'], TagTone> = {
  OPEN: 'success',
  MAINTENANCE: 'warning',
  CLOSED: 'default',
}

const pageState = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')

const stations = ref<StationSummary[]>([])
const targetStationId = ref('')
const selectMode = ref(false)

/**
 * 关键词过滤：只在已取回的列表上按水站名与地址做包含匹配，
 * 不改 listStations 的请求参数，也不引入分页/远程搜索。
 */
const matchedStations = computed(() => {
  const key = keyword.value.trim()
  if (!key) {
    return stations.value
  }
  return stations.value.filter(
    item => item.stationName.includes(key) || item.address.includes(key),
  )
})

/** stationId 参数存在时目标水站排首位并加“目标水站”标识。 */
const sortedStations = computed(() => {
  if (!targetStationId.value) {
    return matchedStations.value
  }
  const target = matchedStations.value.filter(item => item.id === targetStationId.value)
  const rest = matchedStations.value.filter(item => item.id !== targetStationId.value)
  return [...target, ...rest]
})

/**
 * 距离只在接口真实给出时才渲染。
 *
 * <p>原实现缺省返回「未定位」，等于给每个水站摆一个恒定为空的指标格，还要在页顶
 * 再挂一条「暂不获取定位，无法显示距离。」去解释它。没有的东西就不占位——
 * 指标格整格不渲染，那条解释也随之不需要存在。</p>
 */
function distanceText(meters?: number): string {
  return meters === undefined ? '' : `${(meters / 1000).toFixed(1)}km`
}

onLoad((query?: Record<string, string | undefined>) => {
  targetStationId.value = query?.stationId ?? ''
  selectMode.value = query?.selectMode === 'delivery'
  void load()
})

async function load() {
  pageState.value = 'loading'
  try {
    stations.value = await catalogApi.listStations()
    pageState.value = 'ready'
  }
  catch (error) {
    pageState.value = 'error'
    errorMessage.value = error instanceof ContractError ? error.message : '水站列表加载失败，请重试'
  }
}

/**
 * 选择模式（U07 → U08 合同）：点选水站写入配送草稿并返回下单页；
 * 非营业中的水站原位说明原因，不写入草稿。浏览模式点按无跳转。
 */
function handleStationTap(station: StationSummary) {
  if (!selectMode.value) {
    return
  }
  if (station.status !== 'OPEN') {
    toast.show(`该水站${STATION_STATUS_LABELS[station.status]}，暂不可选`)
    return
  }
  setDraftStation(station.id)
  uni.navigateBack({
    fail: () => backOr('U08'),
  })
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="水站目录" back-to="U01" />
    <wd-toast />
    <view class="station-hero">
      <image
        class="station-hero__art"
        src="/static/brand/station-network.jpg"
        mode="aspectFill"
      />
      <view class="station-hero__copy">
        <text class="station-hero__title">
          六维水站网络
        </text>
        <text class="station-hero__desc">
          查看水站营业与设备状态
        </text>
      </view>
    </view>
    <!-- 搜索置顶：水站名与地址就地过滤 -->
    <view class="search">
      <wd-search v-model="keyword" placeholder="水站名称或地址" placeholder-left hide-cancel light />
    </view>

    <view v-if="pageState === 'loading'" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, 1, { width: '70%' }]" />
    </view>

    <view v-else-if="pageState === 'error'" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain @click="load">
            重新加载
          </wd-button>
          <wd-button plain @click="backOr('U01')">
            返回首页
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else>
      <view v-if="!sortedStations.length" class="page-section">
        <AppPageState state="empty" :title="keyword.trim() ? '没有匹配的水站' : '暂无水站数据'">
          <template #actions>
            <wd-button v-if="keyword.trim()" plain @click="keyword = ''">
              清空搜索
            </wd-button>
            <wd-button v-else plain @click="backOr('U01')">
              返回首页
            </wd-button>
          </template>
        </AppPageState>
      </view>

      <!-- 一张连续的白底表，不是一摞阴影卡：水站名是主信息，状态与营业情况为辅。
           选择模式下目标水站用品牌蓝浅底标出当前选中，不再只靠一枚小 tag。 -->
      <view v-else class="station-list">
        <view
          v-for="station in sortedStations"
          :key="station.id"
          class="station-row"
          :class="{
            'station-row--target': station.id === targetStationId,
            'pressable': selectMode,
          }"
          @click="handleStationTap(station)"
        >
          <view class="station-row__head">
            <text class="station-row__name">
              {{ station.stationName }}
            </text>
            <wd-tag :type="STATION_STATUS_TONES[station.status]" plain>
              {{ STATION_STATUS_LABELS[station.status] }}
            </wd-tag>
          </view>
          <text class="station-row__address">
            {{ station.address }}
          </text>
          <view class="station-row__meta">
            <text class="station-row__metric">
              在线设备 {{ station.onlineDeviceCount }}
            </text>
            <text class="station-row__metric">
              可用出水口 {{ station.availableOutletCount }}
            </text>
            <text v-if="distanceText(station.distanceMeters)" class="station-row__metric num">
              {{ distanceText(station.distanceMeters) }}
            </text>
          </view>
          <view v-if="selectMode" class="station-row__select">
            {{ station.id === targetStationId ? '当前选择' : '选择该水站' }}
            <wd-icon name="arrow-right" size="14px" />
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.station-hero {
  position: relative;
  height: 146px;
  margin-top: var(--gap-hero);
  overflow: hidden;
  border-radius: var(--r-md);
  background: var(--app-bg-card);

  &__art {
    width: 100%;
    height: 100%;
  }

  &__copy {
    position: absolute;
    top: var(--sp-4);
    left: var(--sp-4);
    display: flex;
    flex-direction: column;
    max-width: 42%;
  }

  &__title {
    color: var(--app-text-primary);
    font-size: var(--fs-title);
    font-weight: 700;
  }

  &__desc {
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-note);
    line-height: 1.45;
  }
}

.search {
  margin-top: var(--gap-block);
  overflow: hidden;
  border-radius: var(--r-sm);
}

.station-list {
  margin-top: var(--gap-block);
  overflow: hidden;
  border-radius: var(--r-md);
  background: var(--app-bg-card);
}

.station-row {
  padding: var(--sp-4);
  border-bottom: 1px solid var(--line-1);

  &:last-child {
    border-bottom: none;
  }

  // 当前选中：品牌蓝浅底承担「选中」语义，不加描边也不加投影
  &--target {
    background: var(--tint-primary);
  }

  &__head {
    display: flex;
    gap: var(--sp-2);
    align-items: center;
  }

  // 水站名是这一行的主信息，长名单行截断而不是把状态 tag 挤下去
  &__name {
    display: block;
    flex: 1;
    min-width: 0;
    overflow: hidden;
    font-size: var(--fs-title);
    font-weight: 600;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__address {
    display: block;
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
    line-height: 1.4;
  }

  &__meta {
    display: flex;
    gap: var(--sp-4);
    align-items: baseline;
    margin-top: var(--sp-2);
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
  }

  &__metric {
    flex: none;
  }

  &__select {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 2px;
    margin-top: var(--sp-3);
    padding-top: var(--sp-3);
    border-top: 1px solid var(--line-1);
    color: var(--app-color-primary);
    font-size: var(--fs-caption);
    font-weight: 600;
  }
}
</style>
