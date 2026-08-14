<script setup lang="ts">
import type { MallAfterSale } from '@/api/mall-aftersale'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { ContractError } from '@/api/common'
import { mallAfterSaleApi } from '@/api/mall-aftersale'
import AppBottomActionBar from '@/components/app-bottom-action-bar.vue'
import AppNavbar from '@/components/app-navbar.vue'
import WechatContactEntry from '@/components/wechat-contact-entry.vue'
import AppPageState from '@/components/app-page-state.vue'
import BizTimeline from '@/components/biz-timeline.vue'
import MallGoodsLine from '@/components/mall-goods-line.vue'
import { formatBizTime, formatFen, MALL_AFTER_SALE_STATUS_TONES } from '@/utils/format'
import { goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '售后详情',
  },
})

/**
 * 售后详情：进度、明细、退款结果与换货补发单。
 *
 * 退款一栏只在退款成功事实存在时才写「已退款」；售后单走到完成而退款事实还没到，
 * 显示的是「退款处理中」——把两者混为一谈会让用户以为钱已经在账上了。
 */

const toast = useToast()
const message = useMessage()

const loading = ref(true)
const errorMessage = ref('')
const acting = ref(false)
const detail = ref<MallAfterSale | null>(null)
let afterSaleNo = ''

onLoad((query) => {
  afterSaleNo = typeof query?.afterSaleNo === 'string' ? query.afterSaleNo : ''
})

onShow(refresh)

async function refresh() {
  if (!afterSaleNo) {
    loading.value = false
    errorMessage.value = '缺少售后单参数'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await mallAfterSaleApi.detail(afterSaleNo)
  }
  catch (error) {
    detail.value = null
    errorMessage.value = error instanceof ContractError ? error.message : '售后详情加载失败，请重试'
  }
  finally {
    loading.value = false
  }
}

/** 轨迹节点转时间线：只重排服务端已有字段，不补节点、不推导状态。 */
const timelineNodes = computed(() =>
  (detail.value?.timeline ?? []).map(node => ({
    title: node.traceNodeName,
    time: formatBizTime(node.traceTime),
    description: node.traceText,
    actor: node.actorTypeName,
  })),
)

/** 只有待审核可撤销；能否真正撤销仍由服务端按归属与状态再判一次。 */
const canCancel = computed(() => detail.value?.afterSaleStatus === 1)

/**
 * 退款结果文案（退款单状态 1待退款 2成功 3失败 4已关闭）。
 *
 * 「已退回」只在状态为成功且带到账时间时才写：状态成功而时间缺失说明证据不齐，
 * 这时告诉用户钱已经到账，用户会去银行找一笔并不存在的入账。
 */
const refundText = computed(() => {
  const row = detail.value
  if (!row || row.refundAmountFen <= 0) {
    return ''
  }
  if (row.refundStatus === undefined) {
    return '待前置仓处理后发起'
  }
  if (row.refundStatus === 2) {
    return row.refundSuccessTime
      ? `已于 ${formatBizTime(row.refundSuccessTime)} 原路退回`
      : '退款已成功，到账时间以银行为准'
  }
  if (row.refundStatus === 3) {
    return '退款未成功，前置仓将重新处理'
  }
  if (row.refundStatus === 4) {
    return '退款已关闭'
  }
  return '退款处理中'
})

function handleCancel() {
  if (acting.value || !canCancel.value) {
    return
  }
  message
    .confirm({ title: '撤销申请', msg: '撤销后需要重新提交，确认撤销？' })
    .then(async () => {
      acting.value = true
      try {
        detail.value = await mallAfterSaleApi.cancel(afterSaleNo)
        toast.success('已撤销申请')
      }
      catch (error) {
        toast.show(error instanceof ContractError ? error.message : '撤销失败，请重试')
        await refresh()
      }
      finally {
        acting.value = false
      }
    })
    .catch(() => null)
}
</script>

<template>
  <view class="page-shell" :class="{ 'page-shell--with-bar': canCancel }">
    <AppNavbar title="售后详情" back-to="M10" />
    <wd-toast />
    <wd-message-box />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button plain size="small" @click="refresh">
            重新加载
          </wd-button>
          <wd-button plain size="small" @click="goTo('M10')">
            返回售后列表
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <template v-else-if="detail">
      <view class="page-section info-card">
        <view class="status-head">
          <text class="status-text">
            {{ detail.afterSaleStatusName }}
          </text>
          <wd-tag :type="MALL_AFTER_SALE_STATUS_TONES[detail.afterSaleStatus] || 'default'" plain>
            {{ detail.afterSaleTypeName }}
          </wd-tag>
        </view>
        <text v-if="detail.rejectReason" class="muted-text">
          处理说明：{{ detail.rejectReason }}
        </text>
      </view>

      <view class="page-section info-card">
        <view class="info-title">
          售后商品
        </view>
        <MallGoodsLine
          v-for="line in detail.lines"
          :key="line.orderItemId"
          :name="line.productName"
          :spec="line.skuName"
          :price="formatFen(line.unitPriceFen)"
          :quantity="line.quantity"
        />
      </view>

      <view class="page-section info-card">
        <view class="info-row">
          <text class="muted-text">
            售后单号
          </text>
          <text>{{ detail.afterSaleNo }}</text>
        </view>
        <view class="info-row">
          <text class="muted-text">
            原订单号
          </text>
          <text>{{ detail.orderNo }}</text>
        </view>
        <view class="info-row">
          <text class="muted-text">
            申请时间
          </text>
          <text>{{ formatBizTime(detail.applyTime) }}</text>
        </view>
        <view class="info-row">
          <text class="muted-text">
            申请原因
          </text>
          <text>{{ detail.applyReason }}</text>
        </view>
        <view v-if="detail.inspectResultName" class="info-row">
          <text class="muted-text">
            质检结论
          </text>
          <text>{{ detail.inspectResultName }}</text>
        </view>
        <view v-if="detail.inspectRemark" class="info-row">
          <text class="muted-text">
            质检说明
          </text>
          <text>{{ detail.inspectRemark }}</text>
        </view>
      </view>

      <view v-if="detail.refundAmountFen > 0" class="page-section info-card">
        <view class="info-title">
          退款结果
        </view>
        <view class="info-row">
          <text class="muted-text">
            退款金额
          </text>
          <text class="money">
            {{ formatFen(detail.refundAmountFen) }}
          </text>
        </view>
        <view class="info-row">
          <text class="muted-text">
            退款状态
          </text>
          <text>{{ refundText }}</text>
        </view>
        <!-- 客服入口就放在退款结果旁：用户对退款有疑问的那一刻就在这一屏，
             让他读完就能点，而不是读完再去别处翻。只带售后号，不带任何身份或凭据。 -->
        <view class="aftersale-contact">
          <WechatContactEntry
            scene="售后与退款咨询"
            :biz-no="detail.afterSaleNo"
            page-path="/pages/mall/aftersale-detail"
          />
        </view>
      </view>

      <view v-if="detail.exchangeOrderNo" class="page-section info-card">
        <view class="info-title">
          换货补发
        </view>
        <view class="info-row">
          <text class="muted-text">
            补发订单号
          </text>
          <text>{{ detail.exchangeOrderNo }}</text>
        </view>
        <wd-button
          plain
          size="small"
          @click="goTo('M06', { orderNo: detail.exchangeOrderNo })"
        >
          查看补发物流
        </wd-button>
      </view>

      <view v-if="timelineNodes.length" class="page-section info-card">
        <view class="info-title">
          处理进度
        </view>
        <BizTimeline :nodes="timelineNodes" />
      </view>

      <AppBottomActionBar v-if="canCancel">
        <template #summary>
          <text class="muted-text">
            审核前可自行撤销
          </text>
        </template>
        <template #primary>
          <wd-button type="primary" plain :loading="acting" @click="handleCancel">
            撤销申请
          </wd-button>
        </template>
      </AppBottomActionBar>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.aftersale-contact {
  padding-top: 8px;
  text-align: right;
}

.info-card {
  padding: 12px;
  background: var(--app-bg-card);
  border-radius: var(--r-md);
}

.status-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.status-text {
  font-size: var(--fs-metric);
  font-weight: 700;
}

.info-title {
  margin-bottom: 8px;
  font-size: var(--fs-title);
  font-weight: 600;
}

.info-row {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  font-size: var(--fs-body);
  line-height: 1.9;
}
</style>
