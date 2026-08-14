<script setup lang="ts">
import { computed } from 'vue'
import { isContactAvailable } from '@/api/runtime'

/**
 * 微信客服入口。会话卡片只带页面路径与脱敏业务编号——不带 token/openid/手机号/原始支付报文（D-212）。
 * 未开通客服时 open-type="contact" 点击无反应无回调：此时不渲染微信按钮，改为展示普通联系方式。
 */
const props = defineProps<{
  /** 业务场景，进会话卡片标题，帮客服一眼知道用户在哪出的问题。 */
  scene: string
  /** 脱敏后的业务编号（订单号/售后号）。调用方负责脱敏，本组件不做二次处理。 */
  bizNo?: string
  /** 出问题的页面路径，供客服引导用户回到现场。 */
  pagePath?: string
  /** 未配客服时展示的普通联系方式。 */
  fallbackPhone?: string
}>()

const available = isContactAvailable()

/** 卡片标题：场景 + 编号。绝不拼入任何身份或凭据。 */
const cardTitle = computed(() =>
  props.bizNo ? `${props.scene}（${props.bizNo}）` : props.scene,
)

function callFallback() {
  if (!props.fallbackPhone) {
    return
  }
  uni.makePhoneCall({ phoneNumber: props.fallbackPhone })
}
</script>

<template>
  <view class="contact-entry">
    <!-- 微信客服：会话卡片只带受控路径与脱敏编号 -->
    <wd-button
      v-if="available"
      open-type="contact"
      :send-message-title="cardTitle"
      :send-message-path="pagePath"
      show-message-card
      plain
      size="small"
    >
      联系客服
    </wd-button>

    <!-- 未开通客服：给普通联系方式，不出现任何技术报错 -->
    <wd-button
      v-else-if="fallbackPhone"
      plain
      size="small"
      @click="callFallback"
    >
      拨打客服电话
    </wd-button>

    <!-- 两者都没有：什么都不显示 -->
  </view>
</template>

<style lang="scss" scoped>
.contact-entry {
  display: inline-flex;
}
</style>
