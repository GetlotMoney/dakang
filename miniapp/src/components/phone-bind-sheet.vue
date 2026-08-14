<script setup lang="ts">
import { ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { authApi, readPhoneAuthorization } from '@/api/auth'
import { ContractError } from '@/api/common'
import { isPhoneComponentAvailable } from '@/api/runtime'
import { useAccountStore } from '@/store/account'

/**
 * 绑号引导半屏。
 *
 * 游客态下账号可以没有手机号，只有动钱与落履约归属的动作会被服务端拒绝（错误码 627）。
 * 页面撞到该码时弹本组件：用户在这里一次绑完就能接着做刚才那件事，
 * 不必被踢回登录页、也不用自己去「我的」里找入口。
 *
 * 组件只负责「把手机号绑上」，绑完通过 success 事件把控制权还给调用方——
 * 是重试刚才的动作还是刷新页面，由业务页自己决定，组件不替它做主。
 */

const emit = defineEmits<{ success: [], close: [] }>()
const visible = defineModel<boolean>({ default: false })

const toast = useToast()
const accountStore = useAccountStore()
const binding = ref(false)

/** 平台在组件层禁用 getPhoneNumber 时点击不弹窗也无回调，故不渲染按钮而是给出路。 */
const componentAvailable = isPhoneComponentAvailable()

function close() {
  visible.value = false
  emit('close')
}

async function onGetPhoneNumber(event: { detail?: { code?: string, errMsg?: string } }) {
  const authorization = readPhoneAuthorization(event.detail)
  if (!authorization.authorized) {
    const reason = String(authorization.reason ?? '')
    // 用户主动拒绝与平台侧不可用要给不同出路：前者再点一次就行，后者点一百次也没用
    const declined = reason.includes('cancel') || reason.includes('deny') || reason === ''
    toast.show(declined ? '已取消，可重新授权' : '手机号授权暂不可用，请联系运营')
    return
  }
  if (binding.value) {
    return
  }
  binding.value = true
  try {
    const ctx = await authApi.bindPhoneSelf(authorization.phoneCode)
    accountStore.establishRealSession(ctx)
    toast.success('已绑定')
    visible.value = false
    emit('success')
  }
  catch (error) {
    toast.show(error instanceof ContractError ? error.message : '绑定失败，请重试')
  }
  finally {
    binding.value = false
  }
}
</script>

<template>
  <wd-popup v-model="visible" position="bottom" closable custom-style="border-radius:24rpx 24rpx 0 0;" @close="close">
    <view class="bind-sheet">
      <view class="bind-title">
        绑定手机号后继续
      </view>
      <view class="bind-desc">
        用于订单联系与账号找回
      </view>
      <template v-if="componentAvailable">
        <button
          class="bind-primary"
          open-type="getPhoneNumber"
          :disabled="binding"
          @getphonenumber="onGetPhoneNumber"
        >
          微信手机号一键绑定
        </button>
        <view class="bind-later" @click="close">
          暂不绑定
        </view>
      </template>
      <template v-else>
        <view class="bind-desc">
          手机号绑定暂不可用，请联系运营
        </view>
        <view class="bind-later" @click="close">
          知道了
        </view>
      </template>
    </view>
  </wd-popup>
</template>

<style scoped>
.bind-sheet {
  padding: 48rpx 40rpx 64rpx;
  text-align: center;
}

.bind-title {
  font-size: 34rpx;
  font-weight: 600;
  color: var(--app-text-primary);
}

.bind-desc {
  margin-top: 16rpx;
  font-size: 26rpx;
  color: var(--app-text-secondary);
}

.bind-primary {
  margin-top: 40rpx;
  background: var(--app-brand);
  color: var(--app-text-inverse);
  border-radius: 999rpx;
  font-size: 30rpx;
  line-height: 88rpx;
}

.bind-later {
  margin-top: 28rpx;
  font-size: 28rpx;
  color: var(--app-text-secondary);
}
</style>
