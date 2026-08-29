<script setup lang="ts">
import type { CardSummary } from '@/api/card'
import AppPageState from '@/components/app-page-state.vue'
import type { CourierAdmission } from '@/api/delivery'
import { computed, ref, watch } from 'vue'
import { ENTRY_ICONS, listPublishedEntries, projectFeatureEntries, projectNotice } from '@/api/entry'
import type { PublishedEntry } from '@/api/entry'
import { useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { ContractError } from '@/api/common'
import { deliveryApi } from '@/api/delivery'
import { useAccountStore } from '@/store/account'
import {
  CARD_STATUS_LABELS,
  fenParts,
  mlParts,
} from '@/utils/format'
import { goTo } from '@/utils/navigation'
import { scanWaterCode } from '@/utils/scan'

/** 生活用水态：U01 的消费者视角（主态自适应三张脸之一）。 */
const props = defineProps<{ refreshTick: number }>()

// S6 入口配置投影（R1-7 fail-closed）：宫格只渲染已发布配置；读取异常回放最后一次
// 成功配置（含空），从未成功=宫格为空——固定 Tabbar 保证首页/订单/我的核心可用
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

// 配送入口仍以运营发布配置为准；这里只把已发布的 U08 提升为首页主入口，
// 避免同一能力在主入口与「更多服务」里重复出现。配置撤回后两处都会同时消失。
const deliveryEntry = computed(() => featureEntries.value.find(entry => entry.routeId === 'U08') ?? null)
const secondaryEntries = computed(() => featureEntries.value.filter(entry => entry.routeId !== 'U08'))

const loading = ref(true)
const loadError = ref('')
const primaryCard = ref<CardSummary | null>(null)
const admission = ref<CourierAdmission | null>(null)
const scanning = ref(false)

/**
 * 无配送能力时的"成为配送员"引导（2026-07-16 决策）：
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

/** 首屏只取「用户此刻要用的资产与入口」；订单入口由固定 Tabbar 承担，首页不拉订单列表。 */
async function refresh() {
  loading.value = true
  loadError.value = ''
  try {
    const [card, admissionRecord] = await Promise.all([
      cardApi.getPrimaryCard(),
      deliveryApi.getCourierAdmission().catch(() => null),
    ])
    primaryCard.value = card
    admission.value = admissionRecord
  }
  catch (error) {
    primaryCard.value = null
    admission.value = null
    loadError.value = error instanceof ContractError ? error.message : '用水信息加载失败，请重试'
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
</script>

<template>
  <view>
    <!-- 产品实景横幅：真实水站产品图是首屏唯一视觉焦点，文案排在画面左侧的白墙留白上，
         与画面构成一个整体。不叠白色文案卡、不加彩色竖边、不铺渐变遮罩——那三样都是
         用装饰冒充产品。文字始终由节点渲染，不烘焙进图片，小屏与改名都能独立调整。 -->
    <view class="hero">
      <image
        class="hero__image"
        src="/static/brand/home-water-station.jpg"
        mode="aspectFill"
      />
      <view class="hero__copy">
        <text class="hero__brand">
          六维达康
        </text>
        <text class="hero__title">
          社区智慧水站
        </text>
        <text class="hero__desc">
          便捷取水 · 配送到家
        </text>
      </view>
    </view>

    <!-- 两个高频动作用实色块承担，不再是同权宫格里的两个图标。
         配送块只在 U08 已发布时出现，运营撤回配置不会被视觉改版绕过。 -->
    <view class="entries" :class="{ 'entries--single': !deliveryEntry }">
      <!-- 两个主入口铺品牌插画：扫码块是六股水流汇聚成扫码框，配送块是水路通向水桶——
           插画本身就在讲这个动作是什么，不是拿纹理装饰。
           主体一律靠右，文案压在左侧的插画自有底色上（--art-scan-blue / --art-delivery-teal），
           接缝看不出来，白字对比 15.6:1 / 11.4:1。 -->
      <view class="entry entry--scan pressable" @click="handleScan">
        <image class="entry__art" src="/static/brand/entry-scan-wide.jpg" mode="aspectFill" />
        <view class="entry__top">
          <view class="entry__icon">
            <wd-icon name="scan" size="26px" color="var(--app-text-inverse)" />
          </view>
          <!-- 扫码等待用 currentColor 转圈：wd-loading 会把 color 内联进 base64 SVG 并对它
               做 gradient() 取色，只吃 16 进制字面量——传 CSS 变量得到的是坏色，传字面量又
               会在实色块上写死一个与 token 不同源的白。这里跟随块内文字色，两头都躲开。 -->
          <view v-if="scanning" class="entry__spinner" />
        </view>
        <text class="entry__title">
          扫码取水
        </text>
        <text class="entry__desc">
          扫描水机二维码
        </text>
      </view>
      <view
        v-if="deliveryEntry"
        class="entry entry--delivery pressable"
        @click="goTo(deliveryEntry.routeId!)"
      >
        <image class="entry__art" src="/static/brand/entry-delivery-wide.jpg" mode="aspectFill" />
        <view class="entry__top">
          <view class="entry__icon">
            <wd-icon name="goods" size="24px" />
          </view>
        </view>
        <text class="entry__title">
          {{ deliveryEntry.entryName }}
        </text>
        <text class="entry__desc">
          桶装水配送到家
        </text>
      </view>
    </view>

    <view v-if="maintenanceNotice" class="notice">
      <wd-notice-bar :text="maintenanceNotice" prefix="warn-bold" wrapable :scrollable="false" />
    </view>

    <!-- 水卡资产：全屏唯一的白色内容面。余额与水量是并列的两个指标，
         用等宽两列摆平，层级交给字重与色阶，不靠彩色边线。 -->
    <view class="asset" :class="{ 'asset--card': primaryCard && !loadError }">
      <image
        v-if="primaryCard && !loadError"
        class="asset__art"
        src="/static/brand/card-ripples-wide.jpg"
        mode="aspectFill"
      />
      <view class="asset__head">
        <text class="asset__title">
          我的水卡
        </text>
        <text v-if="primaryCard" class="asset__status" :class="{ 'asset__status--alert': primaryCard.cardStatus !== 1 }">
          {{ CARD_STATUS_LABELS[primaryCard.cardStatus] }}
        </text>
        <wd-button v-else-if="loadError" plain size="small" @click="refresh">
          重试
        </wd-button>
      </view>

      <view v-if="loadError" class="asset__message">
        {{ loadError }}
      </view>
      <view v-else-if="primaryCard" class="asset__body pressable" @click="handleCardTap">
        <view class="asset__figures">
          <!-- 读数写法：¥ 是前缀符不是单位、小数位只降字号不降色、L 走水青。
               余额与水量都不加 .money——它们是资产不是价格。 -->
          <view class="asset__figure">
            <text class="readout readout--l asset__reading">
              <text class="readout__sigil">
                {{ fenParts(primaryCard.balanceFen).sigil }}
              </text><text class="readout__value">
                {{ fenParts(primaryCard.balanceFen).value }}
              </text><text class="readout__minor">
                {{ fenParts(primaryCard.balanceFen).minor }}
              </text>
            </text>
            <text class="readout-label">
              卡内余额
            </text>
          </view>
          <view class="asset__figure">
            <text class="readout readout--l asset__reading">
              <text class="readout__value">
                {{ mlParts(primaryCard.balanceMl).value }}
              </text><text v-if="mlParts(primaryCard.balanceMl).minor" class="readout__minor">
                {{ mlParts(primaryCard.balanceMl).minor }}
              </text><text class="readout__unit readout__unit--water">
                {{ mlParts(primaryCard.balanceMl).unit }}
              </text>
            </text>
            <text class="readout-label">
              可用水量
            </text>
          </view>
        </view>
        <wd-icon name="arrow-right" size="18px" color="var(--app-text-inverse)" />
      </view>
      <view v-if="primaryCard && !loadError" class="asset__foot">
        <text class="asset__no num">
          {{ primaryCard.cardNo }}
        </text>
        <text class="asset__link">
          查看卡片
        </text>
      </view>
      <view v-else-if="!loading" class="asset__body pressable" @click="handleCardTap">
        <view>
          <text class="asset__empty-title">
            还没有水卡
          </text>
          <!-- 空态第二行给去处，不给解释：原来写「选购套餐后即可使用水站服务」，
               那是在讲水卡是干什么的，用户已经点进来了，需要的是下一步 -->
          <text class="asset__empty-desc">
            去开卡
          </text>
        </view>
        <wd-icon name="arrow-right" size="18px" />
      </view>
      <AppPageState v-else state="loading" :row-col="[{ width: '42%' }, 1]" />
    </view>

    <!-- 更多服务：次级功能用无外框的图形化菜单，不再包一层白卡与投影，
         与上面的水卡资产形成密度差。 -->
    <view class="services">
      <text class="services__title">
        更多服务
      </text>
      <view class="services__grid">
        <view
          v-for="entry in secondaryEntries"
          :key="entry.entryKey"
          class="services__item pressable"
          @click="goTo(entry.routeId!)"
        >
          <view class="services__icon">
            <wd-icon :name="ENTRY_ICONS[entry.entryKey]" size="24px" />
          </view>
          <text class="services__name">
            {{ entry.entryName }}
          </text>
        </view>
        <view class="services__item pressable" @click="goTo('M01')">
          <view class="services__icon">
            <wd-icon name="shop" size="24px" />
          </view>
          <text class="services__name">
            商城
          </text>
        </view>
      </view>

      <!-- 配送准入是「更多服务」的身份入口，不再另起一张页尾卡片。
           独立卡会把低频申请误画成首页主任务，并在短页面末尾形成悬空块。 -->
      <view v-if="courierGuidance" class="services__admission pressable" @click="goTo('D02')">
        <view class="services__admission-main">
          <text class="services__admission-title">
            {{ courierGuidance.title }}
          </text>
          <text class="services__admission-desc">
            {{ courierGuidance.desc }}
          </text>
        </view>
        <view class="services__admission-enter">
          {{ courierGuidance.action }}
          <wd-icon name="arrow-right" size="14px" />
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
// 首屏媒体焦点：全页唯一带投影的块，圆角走最大一档，与下方的功能块拉开层级。
.hero {
  position: relative;
  height: 208px;
  margin-top: var(--gap-hero);
  overflow: hidden;
  border-radius: var(--r-lg);
  box-shadow: var(--sh-card);

  &__image {
    width: 100%;
    height: 100%;
  }

  &__copy {
    position: absolute;
    top: var(--sp-5);
    left: var(--sp-4);
    display: flex;
    flex-direction: column;
    width: 46%;
  }

  &__brand {
    color: var(--app-color-primary);
    font-size: var(--fs-caption);
    font-weight: 700;
    letter-spacing: 3px;
  }

  &__title {
    margin-top: var(--sp-2);
    color: var(--app-text-primary);
    font-size: var(--fs-metric);
    font-weight: 700;
    line-height: 1.2;
  }

  &__desc {
    margin-top: var(--sp-2);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }
}

.entries {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-3);
  margin-top: var(--gap-block);

  &--single {
    grid-template-columns: 1fr;
  }
}

// 主操作块：实色、无投影、无描边。颜色即语义——品牌蓝是取水主动作，
// 水青是饮水配送的辅助识别。
//
// 图标在上、文案在下的竖排：横排（图标+文案+箭头同行）在 375 两栏下只剩 67px 给标题，
// 「扫码取水」实测折成两行；竖排把整块宽度让给文案，320 两栏也放得下，
// 配置下发的配送入口名再长一点也只是换行、不会挤压图标。
.entry {
  position: relative;
  display: flex;
  overflow: hidden;
  min-height: 118px;
  flex-direction: column;
  padding: var(--sp-3) var(--sp-4) var(--sp-4);
  border-radius: var(--r-md);
  color: var(--app-text-inverse);

  // 块底 = 插画自身底色，插画右贴，接缝不可见；文案永远压在纯色上
  &--scan {
    background: var(--art-scan-blue);
  }

  &--delivery {
    background: var(--art-delivery-teal);
  }

  &__art {
    position: absolute;
    top: 0;
    right: 0;
    width: 62%;
    height: 100%;
  }

  &__top,
  &__title,
  &__desc {
    position: relative;
    z-index: 1;
  }

  &__top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--sp-3);
  }

  &__icon {
    display: flex;
    flex: none;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: var(--r-sm);
    background: var(--tint-inverse);
  }

  &__title,
  &__desc {
    display: block;
  }

  &__title {
    font-size: var(--fs-title);
    font-weight: 700;
    line-height: 1.25;
  }

  &__desc {
    overflow: hidden;
    margin-top: var(--sp-1);
    font-size: var(--fs-note);
    white-space: nowrap;
    text-overflow: ellipsis;
    opacity: 0.8;
  }

  &__spinner {
    flex: none;
    box-sizing: border-box;
    width: 18px;
    height: 18px;
    border: 2px solid currentColor;
    border-top-color: transparent;
    border-radius: 50%;
    animation: home-entry-spin 0.8s linear infinite;
  }
}

// 关键帧名在 WXSS 里是全局的，加页面前缀避免与其他页面同名帧互相覆盖。
@keyframes home-entry-spin {
  to {
    transform: rotate(360deg);
  }
}

.notice {
  margin-top: var(--gap-block);
  overflow: hidden;
  border-radius: var(--r-sm);
}

// 白色内容面：本屏唯一的白卡，因此不再叠投影——白底与页底 #f5f7fa 已足够分离。
.asset {
  position: relative;
  overflow: hidden;
  margin-top: var(--gap-group);
  padding: var(--sp-4);
  border-radius: var(--r-md);
  background: var(--app-bg-card);

  &--card {
    color: var(--app-text-inverse);
    background: var(--art-card-navy);
  }

  &__art {
    position: absolute;
    z-index: 0;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }

  &__head,
  &__body,
  &__foot {
    position: relative;
    z-index: 1;
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__title {
    font-size: var(--fs-title);
    font-weight: 700;
  }

  &__status {
    font-size: var(--fs-caption);
    opacity: 0.85;

    &--alert {
      padding: 2px 8px;
      border-radius: var(--r-pill);
      background: var(--tint-inverse);
      font-weight: 700;
      opacity: 1;
    }
  }

  &__body {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--sp-4);
  }

  // 两个指标是并列关系，用等宽两列而不是 baseline 对齐的一大一小。
  //
  // 原实现 align-items:baseline + 24px/17px 两档字号：值盒高 27.6 与 19.6，
  // 各自的 label 用 margin-top 挂在自己值盒下方，于是两个 label 纵向差约 8px——
  // 「可用水量」整组看着比「卡内余额」往下掉一截；24 与 17 的落差也让两个本该并列的
  // 指标看着像「一个主角一个附注」。
  //
  // 改成同字号等宽两列：label 天然落在同一水平线，层级交给字重与色阶
  // （余额 700 主色 / 水量 600 次级色），留白由等宽列自己提供。
  &__figures {
    display: grid;
    flex: 1;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    min-width: 0;
  }

  // 两个指标之间加一条中性竖线。等宽两列但没有分界时，第二个读数看着像
  // 「漂在卡片中间」而不是「一对指标里的第二个」——分栏关系要被看见才成立。
  &__figure {
    min-width: 0;

    & + & {
      padding-left: var(--sp-4);
      border-left: 1px solid var(--line-1);
    }
  }

  // 读数本体：字号字重单位气口全部由全局 .readout 负责，这里只管它在格子里的行为。
  // 必须 display:block——它是 <text>（默认 inline），inline 元素不吃 overflow/ellipsis，
  // 也不会被网格列约束住，超长读数会直接画到隔壁格子上。
  &__reading {
    display: block;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__empty-title {
    display: block;
    overflow: hidden;
    font-size: var(--fs-body);
    font-weight: 600;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__empty-desc {
    margin-top: var(--sp-1);
    color: var(--app-color-primary);
    font-size: var(--fs-caption);
  }

  &__message {
    margin-top: var(--sp-3);
    color: var(--app-color-danger);
    font-size: var(--fs-caption);
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--sp-4);
    padding-top: var(--sp-3);
    border-top: 1px solid var(--tint-inverse);
  }

  &__no {
    overflow: hidden;
    font-size: var(--fs-note);
    letter-spacing: 1px;
    white-space: nowrap;
    text-overflow: ellipsis;
    opacity: 0.8;
  }

  &__link {
    flex: none;
    font-size: var(--fs-note);
    opacity: 0.85;
  }
}

.asset--card .readout-label {
  color: var(--app-text-inverse);
  opacity: 0.68;
}

.asset--card .asset__reading,
.asset--card .asset__body,
.asset--card .asset__foot {
  color: var(--app-text-inverse);
}

.asset--card .readout__sigil,
.asset--card .readout__value,
.asset--card .readout__minor,
.asset--card .readout__unit,
.asset--card .asset__no,
.asset--card .asset__link {
  color: var(--app-text-inverse);
}

// 次级菜单：无容器、无描边框，只有浅色实底图标 + 名称。
.services {
  margin-top: var(--gap-group);

  &__title {
    display: block;
    font-size: var(--fs-title);
    font-weight: 600;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: var(--sp-4) var(--sp-2);
    margin-top: var(--sp-3);
  }

  &__item {
    display: flex;
    flex-direction: column;
    align-items: center;
    min-width: 0;
  }

  &__icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 46px;
    height: 46px;
    color: var(--app-color-primary);
    border-radius: var(--r-sm);
    background: var(--tint-primary);

    &--muted {
      color: var(--app-text-tertiary);
      background: var(--tint-neutral);
    }
  }

  &__name {
    overflow: hidden;
    width: 100%;
    margin-top: var(--sp-2);
    font-size: var(--fs-caption);
    text-align: center;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__admission {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--sp-4);
    padding: var(--sp-3) 0 0;
    border-top: 1px solid var(--line-1);
  }

  &__admission-main {
    min-width: 0;
    padding-right: var(--sp-3);
  }

  &__admission-title,
  &__admission-desc {
    display: block;
  }

  &__admission-title {
    font-size: var(--fs-body);
    font-weight: 600;
  }

  &__admission-desc {
    margin-top: var(--sp-1);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }

  &__admission-enter {
    display: flex;
    flex: none;
    align-items: center;
    gap: 2px;
    color: var(--app-color-primary);
    font-size: var(--fs-caption);
  }
}

// 窄屏只压缩横幅高度并给文案让宽；两个主入口竖排后在 320 下（每栏 138px）
// 仍放得下标题与说明，不再退化成上下堆叠——那会把水卡资产整体挤出首屏。
@media (max-width: 360px) {
  .hero {
    height: 184px;

    &__copy {
      width: 54%;
    }

    &__title {
      font-size: var(--fs-title);
    }
  }

  .services__grid {
    gap: var(--sp-3) var(--sp-1);
  }
}
</style>
