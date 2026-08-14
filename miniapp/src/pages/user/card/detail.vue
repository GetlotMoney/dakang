<script setup lang="ts">
import type { CardDetail, CardMember } from '@/api/card'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { ContractError } from '@/api/common'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
import {
  CARD_STATUS_LABELS,
  fenParts,
  formatBizTime,
  formatFen,
  formatMl,
  mlParts,
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
        <AppPageState state="error" :message="loadError">
          <template #actions>
            <wd-button plain @click="backOr('U03')">
              返回
            </wd-button>
          </template>
        </AppPageState>
      </view>
    </template>
    <view v-else-if="!detail" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
    </view>
    <template v-else>
      <!-- 卡面：左侧深色实底承载读数，右侧铺品牌涟漪插画。
           插画的主体（水滴落点与涟漪）本来就在画面右侧，左侧是深色留白——顺着构图用，
           而不是把文字压在涟漪上。底色取素材左缘中位色（--art-card-navy），接缝看不出来，
           白字对比 20:1，13px 卡号也稳。 -->
      <view class="card-face">
        <image class="card-face__art" src="/static/brand/card-ripples-wide.jpg" mode="aspectFill" />
        <view class="card-face__head">
          <text class="card-face__label">
            我的水卡
          </text>
          <text class="card-face__status" :class="{ 'card-face__status--alert': detail.cardStatus !== 1 }">
            {{ CARD_STATUS_LABELS[detail.cardStatus] }}
          </text>
        </view>
        <!-- 卡面读数：与首页水卡区同一套 .readout。深底上单位不用水青（压蓝对比不足），
             靠 .readout__unit 自带的 currentColor + 0.65 不透明度跟随反白。 -->
        <view class="card-face__figures">
          <view class="card-face__figure">
            <text class="readout readout--l card-face__reading">
              <text class="readout__sigil">
                {{ fenParts(detail.balanceFen).sigil }}
              </text><text class="readout__value">
                {{ fenParts(detail.balanceFen).value }}
              </text><text class="readout__minor">
                {{ fenParts(detail.balanceFen).minor }}
              </text>
            </text>
            <text class="card-face__unit">
              余额
            </text>
          </view>
          <view class="card-face__figure">
            <text class="readout readout--l card-face__reading">
              <text class="readout__value">
                {{ mlParts(detail.balanceMl).value }}
              </text><text v-if="mlParts(detail.balanceMl).minor" class="readout__minor">
                {{ mlParts(detail.balanceMl).minor }}
              </text><text class="readout__unit">
                {{ mlParts(detail.balanceMl).unit }}
              </text>
            </text>
            <text class="card-face__unit">
              剩余水量
            </text>
          </view>
        </view>
        <view class="card-face__foot">
          <text class="card-face__no num">
            {{ detail.cardNo }}
          </text>
          <text class="card-face__copy" @click.stop="copyCardNo">
            复制
          </text>
        </view>
      </view>

      <view class="info-list">
        <view class="info-row">
          <text class="info-row__label">
            套餐
          </text>
          <text class="info-row__value">
            {{ detail.packageName ?? '—' }}
          </text>
        </view>
        <view class="info-row">
          <text class="info-row__label">
            可用范围
          </text>
          <text class="info-row__value">
            {{ detail.scopeDescription }}
          </text>
        </view>
        <view class="info-row">
          <text class="info-row__label">
            有效期
          </text>
          <text class="info-row__value">
            {{ formatBizTime(detail.expireTime) }}
          </text>
        </view>
        <view v-for="(bundle, index) in detail.expiringBundles" :key="index" class="info-row">
          <text class="info-row__label">
            {{ index === 0 ? '权益到期' : '' }}
          </text>
          <text class="info-row__value">
            {{ formatFen(bundle.remainFen) }} + {{ formatMl(bundle.remainMl) }} · {{ formatBizTime(bundle.expireTime) }}前有效
          </text>
        </view>
      </view>

      <!-- 合并按钮下方不再挂说明小字：确认弹窗里带着真实余额、水量和到期日，
           比一句概括更有用，而这一屏本来就点不下去别的东西。 -->
      <view v-if="detail.canMergeToPaidCard" class="merge-action">
        <wd-button block size="large" :loading="merging" @click="confirmMerge">
          合并入正式水卡
        </wd-button>
      </view>

      <view class="page-section">
        <view class="section-title-row">
          <text class="section-title">
            授权成员
          </text>
          <text class="section-action" @click="goTo('U12', { cardId })">
            新增成员
          </text>
        </view>
        <view v-if="detail.members.length" class="member-list">
          <view
            v-for="member in detail.members"
            :key="member.memberId"
            class="member-row pressable"
            @click="goTo('U12', { cardId, memberId: member.memberId })"
          >
            <view class="member-row__main">
              <text class="member-row__name">
                {{ member.memberName }}
              </text>
              <text class="member-row__meta">
                {{ member.maskedPhone }} · 单日限额 {{ memberLimitText(member) }}
              </text>
              <text class="member-row__meta">
                {{ memberPeriodText(member) }}
              </text>
            </view>
            <!-- 状态 tag 与箭头同为行级兄弟、同随行垂直居中：原来 tag 被塞在主信息内部的
                 姓名行里，贴着行顶，而箭头在行中心，右侧空出一块 L 形。 -->
            <wd-tag :type="member.enabled ? 'success' : 'default'" plain>
              {{ member.enabled ? '生效' : '已解除' }}
            </wd-tag>
            <wd-icon name="arrow-right" size="16px" color="var(--app-text-tertiary)" />
          </view>
        </view>
        <AppPageState v-else state="empty" title="暂无授权成员" />
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
.card-face {
  position: relative;
  overflow: hidden;
  margin-top: var(--gap-hero);
  padding: var(--sp-4);
  border-radius: var(--r-lg);
  color: var(--app-text-inverse);
  // 卡底 = 插画自身的底色，插画右贴，两者接缝因此不可见
  background: var(--art-card-navy);

  &__art {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
  }

  &__head,
  &__figures,
  &__foot {
    position: relative;
    z-index: 1;
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__label {
    font-size: var(--fs-body);
    font-weight: 600;
    opacity: 0.9;
  }

  // 状态在卡面上用文字承担：plain tag 是白底描边，压在实色卡面上既看不清也不成体系。
  // 非正常状态加深不透明度并加粗，冻结/过期不会被当成装饰读过去。
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

  // 与首页水卡区同一处置：余额与水量是并列指标，等宽两列 + 同字号，
  // 层级交给字重与不透明度。原来的 align-items:baseline + 24/17 两档字号
  // 会让「剩余水量」的标签比「余额」低约 8px。
  &__figures {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    margin-top: var(--sp-4);
  }

  // 与首页水卡区同一处置：分栏关系要被看见，否则第二个读数像漂在卡面中间。
  // 深底上的分界用半透明白，不用中性线色。
  &__figure + &__figure {
    padding-left: var(--sp-4);
    border-left: 1px solid var(--tint-inverse);
  }

  &__figure {
    min-width: 0;
  }

  // 读数本体的字号字重气口全部由全局 .readout 负责，这里只管它在格子里的行为。
  // display:block 不能省：<text> 默认 inline，inline 不吃 overflow/ellipsis。
  &__reading,
  &__unit {
    display: block;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__unit {
    margin-top: var(--sp-1);
    font-size: var(--fs-note);
    opacity: 0.75;
  }

  &__foot {
    display: flex;
    gap: var(--sp-3);
    align-items: center;
    justify-content: space-between;
    margin-top: var(--sp-4);
    padding-top: var(--sp-3);
    border-top: 1px solid var(--tint-inverse);
  }

  // 卡号长度由发卡规则决定，单行截断，别把「复制」挤出卡面
  &__no {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    font-size: var(--fs-caption);
    letter-spacing: 1px;
    white-space: nowrap;
    text-overflow: ellipsis;
    opacity: 0.9;
  }

  &__copy {
    flex: none;
    padding: 2px 10px;
    border-radius: var(--r-pill);
    background: var(--tint-inverse);
    font-size: var(--fs-note);
  }
}

.info-list {
  margin-top: var(--gap-block);
  padding: var(--sp-2) var(--sp-4);
  border-radius: var(--r-md);
  background: var(--app-bg-card);
}

.info-row {
  display: flex;
  gap: var(--sp-4);
  align-items: flex-start;
  justify-content: space-between;
  padding: var(--sp-2) 0;
  font-size: var(--fs-caption);

  &__label {
    flex: none;
    color: var(--app-text-secondary);
  }

  &__value {
    flex: 1;
    text-align: right;
    word-break: break-all;
  }
}

.merge-action {
  margin-top: var(--gap-group);
}

.section-action {
  color: var(--app-color-primary);
  font-size: var(--fs-caption);
}

.member-list {
  margin-top: var(--sp-3);
  overflow: hidden;
  border-radius: var(--r-md);
  background: var(--app-bg-card);
}

.member-row {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  padding: var(--sp-4);
  border-bottom: 1px solid var(--line-1);

  &:last-child {
    border-bottom: none;
  }

  &__main {
    flex: 1;
    min-width: 0;
  }

  &__name {
    display: block;
    overflow: hidden;
    font-size: var(--fs-body);
    font-weight: 600;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__meta {
    display: block;
    overflow: hidden;
    margin-top: var(--sp-1);
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
    white-space: nowrap;
    text-overflow: ellipsis;
  }
}
</style>
