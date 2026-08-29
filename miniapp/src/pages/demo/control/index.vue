<script setup lang="ts">
import type { DemoControl } from '@/api/identity'
import { onShow } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { identityApi } from '@/api/identity'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'

definePage({ style: { navigationStyle: 'custom', navigationBarTitleText: '演示控制台' } })
const toast = useToast()
const state = ref<'loading' | 'ready' | 'error'>('loading')
const errorMessage = ref('')
const saving = ref(false)
const data = ref<DemoControl | null>(null)
const model = reactive({ nextPayResult: 'SUCCESS' as DemoControl['nextPayResult'], nextDeviceResult: 'NORMAL' as DemoControl['nextDeviceResult'], deliveryAuto: 1 as 1 | 2 })
const payOptions: { value: DemoControl['nextPayResult'], label: string }[] = [{ value: 'SUCCESS', label: '成功' }, { value: 'CANCEL', label: '取消' }, { value: 'INSUFFICIENT', label: '余额不足' }, { value: 'TIMEOUT', label: '超时' }, { value: 'DUPLICATE', label: '重复回调' }]
const deviceOptions: { value: DemoControl['nextDeviceResult'], label: string }[] = [{ value: 'NORMAL', label: '正常出水' }, { value: 'SHORT', label: '少出水' }, { value: 'REJECT', label: '设备拒绝' }, { value: 'TIMEOUT', label: '超时' }, { value: 'DUPLICATE', label: '重复上报' }]
onShow(load)
async function load() {
  state.value = 'loading'
  try {
    data.value = await identityApi.demoControl()
    model.nextPayResult = data.value.nextPayResult
    model.nextDeviceResult = data.value.nextDeviceResult
    model.deliveryAuto = data.value.deliveryAuto
    state.value = 'ready'
  }
  catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '演示控制加载失败'
    state.value = 'error'
  }
}
async function save() {
  saving.value = true
  try {
    data.value = await identityApi.updateDemoControl(model)
    toast.success('下一次模拟结果已保存')
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '保存失败')
  }
  finally {
    saving.value = false
  }
}
function copy(value: string) {
  uni.setClipboardData({ data: value, success: () => toast.success('已复制') })
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="演示控制台" back-to="I01" /><wd-toast />
    <view v-if="state === 'loading'" class="page-section">
      <AppPageState state="loading" />
    </view><view v-else-if="state === 'error'" class="page-section">
      <AppPageState state="error" :message="errorMessage" @retry="load" />
    </view>
    <template v-else-if="data">
      <view v-if="!data.demoMode" class="page-section">
        <AppPageState state="empty" message="正式环境未开启任何模拟能力" />
      </view>
      <template v-else>
        <view class="page-section surface-card demo-warning">
          这里只配置外部微信和硬件的下一次结果；订单、余额、流水、设备消息仍全部真实落库。
        </view>
        <view class="page-section">
          <wd-card title="固定取水二维码">
            <view class="qr-wrap">
              <image class="qr-image" src="/static/demo/fixed-water-qr.png" mode="aspectFit" /><view class="qr-code">
                {{ data.fixedQrContent }}
              </view><wd-button size="small" plain @click="copy(data.fixedQrContent)">
                复制码值
              </wd-button>
            </view>
          </wd-card>
        </view>
        <view class="page-section">
          <wd-card title="下一笔微信支付">
            <view class="choice-row">
              <view v-for="item in payOptions" :key="item.value" class="choice-pill" :class="{ active: model.nextPayResult === item.value }" @click="model.nextPayResult = item.value">
                {{ item.label }}
              </view>
            </view>
          </wd-card>
        </view>
        <view class="page-section">
          <wd-card title="下一次设备执行">
            <view class="choice-row">
              <view v-for="item in deviceOptions" :key="item.value" class="choice-pill" :class="{ active: model.nextDeviceResult === item.value }" @click="model.nextDeviceResult = item.value">
                {{ item.label }}
              </view>
            </view>
          </wd-card>
        </view>
        <view class="page-section">
          <wd-card title="本人配送订单">
            <view class="choice-row">
              <view class="choice-pill" :class="{ active: model.deliveryAuto === 1 }" @click="model.deliveryAuto = 1">
                虚拟配送员自动推进
              </view><view class="choice-pill" :class="{ active: model.deliveryAuto === 2 }" @click="model.deliveryAuto = 2">
                暂停自动推进
              </view>
            </view>
          </wd-card>
        </view>
        <view class="page-section">
          <wd-card title="演示申请邀请码">
            <wd-cell title="渠道" :value="data.channelInviteCode" clickable @click="copy(data.channelInviteCode)" /><wd-cell title="省级代理" :value="data.regionProvinceInviteCode" clickable @click="copy(data.regionProvinceInviteCode)" /><wd-cell title="市级代理" :value="data.regionCityInviteCode" clickable @click="copy(data.regionCityInviteCode)" /><wd-cell title="区县代理" :value="data.regionCountyInviteCode" clickable @click="copy(data.regionCountyInviteCode)" />
          </wd-card>
        </view>
        <view class="save-row">
          <wd-button block size="large" :loading="saving" @click="save">
            保存下一次模拟结果
          </wd-button>
        </view>
      </template>
    </template>
  </view>
</template>

<style scoped lang="scss">
.demo-warning{color:var(--app-color-warning-text);font-size:var(--fs-caption);line-height:1.6;background:var(--tint-warning)}.qr-wrap{display:flex;flex-direction:column;align-items:center;gap:var(--sp-2)}.qr-image{width:220px;height:220px}.qr-code{font-family:monospace;font-size:var(--fs-caption)}.choice-row{display:flex;gap:var(--sp-2);flex-wrap:wrap}.choice-pill{padding:7px 12px;border:1px solid var(--line-2);border-radius:var(--r-pill);color:var(--app-text-secondary);font-size:var(--fs-caption)}.choice-pill.active{border-color:var(--app-color-primary);color:var(--app-color-primary);background:var(--tint-primary);font-weight:600}.save-row{padding:var(--sp-5) 0}
</style>
