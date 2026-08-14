<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { authApi, readPhoneAuthorization } from '@/api/auth'
import { ContractError } from '@/api/common'
import { inviteApi } from '@/api/invite'
import { isPhoneComponentAvailable } from '@/api/runtime'
import { useAccountStore } from '@/store/account'
import { switchToTab } from '@/utils/navigation'

definePage({
  type: 'home',
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '启动',
  },
})

const accountStore = useAccountStore()
const toast = useToast()
/**
 * 手机号组件是否可用。不可用时绝不渲染按钮：平台在组件层禁用时点击无弹窗无回调；
 * 除未认证外额度用尽/未开通同样会禁用，故按开关渲染而不是写死为可用。
 */
const phoneComponentAvailable = isPhoneComponentAvailable()
const buildFingerprint = __DAKANG_BUILD_FINGERPRINT__

const statusText = ref('正在恢复账号会话')

/** 登录阶段：隐私同意（平台指引已配置且未同意时）/ 登录中 / 待授权手机号 / 失败可重试。 */
const authStage = ref<'PRIVACY_REQUIRED' | 'LOGGING_IN' | 'PHONE_REQUIRED' | 'FAILED'>('LOGGING_IN')
/** 平台配置的《用户隐私保护指引》名称（getPrivacySetting 下发；仅 PRIVACY_REQUIRED 阶段使用）。 */
const privacyContractName = ref('')
const authBusy = ref(false)
const bindTicket = ref('')
/** 平台侧手机号能力不可用时的原始 errMsg。只作开关用（区分用户拒绝与平台不可用）；原文不上屏。 */
const phoneBlockedReason = ref('')

/**
 * 自适应隐私门三态：后台未配置指引或基础库无此 API → null 直接登录；已配置且未同意 → 返回指引名停留；
 * 已同意 → null。门随后台配置自动出现/消失。
 */
function queryPrivacyGate(): Promise<string | null> {
  const wxApi = (globalThis as Record<string, any>).wx
  if (typeof wxApi?.getPrivacySetting !== 'function') {
    return Promise.resolve(null)
  }
  return new Promise((resolve) => {
    wxApi.getPrivacySetting({
      success: (res: { needAuthorization?: boolean, privacyContractName?: string }) =>
        resolve(res?.needAuthorization ? (res.privacyContractName || '用户隐私保护指引') : null),
      // 查询失败不拦路：隐私门是增强，不是登录的前置依赖
      fail: () => resolve(null),
    })
  })
}

/** 查看指引全文（平台渲染，非我方页面）。 */
function openPrivacyContract() {
  const wxApi = (globalThis as Record<string, any>).wx
  wxApi?.openPrivacyContract?.({})
}

/** 用户点了「同意并继续」（open-type=agreePrivacyAuthorization 回调）：平台已记录授权，进静默登录。 */
async function onPrivacyAgreed() {
  authStage.value = 'LOGGING_IN'
  statusText.value = '正在登录'
  await runWechatLogin()
}

/** 待归属的推荐码存储位：游客扫码进来晚登录时暂存 inviteCode（页面参数活不过冷启，绑定接口需要会话；D-406）。 */
const PENDING_INVITE_KEY = 'dakang-pending-invite-code'

/**
 * 登录成功后补绑推荐人：所有建会话点都要过这里，漏掉任一处该动线的推荐码就永久丢失。
 * 失败一律静默：绑定是一次性 CAS，「已绑过」被拒不是错误，也不该让用户登不进去。
 */
async function consumePendingInvite() {
  let code = ''
  try {
    code = String(uni.getStorageSync(PENDING_INVITE_KEY) || '')
  }
  catch {
    return
  }
  if (!code) {
    return
  }
  // 先清后调、失败不重试：推荐关系一旦绑定不可改，重试没有价值
  try {
    uni.removeStorageSync(PENDING_INVITE_KEY)
  }
  catch {}
  try {
    await inviteApi.bind(code)
  }
  catch {}
}

onLoad(async (query) => {
  // 推荐码只在这里落盘，不在别处解析：入口页是小程序唯一的冷启动落点。
  const inviteCode = typeof query?.inviteCode === 'string' ? query.inviteCode.trim() : ''
  if (inviteCode) {
    try {
      uni.setStorageSync(PENDING_INVITE_KEY, inviteCode)
    }
    catch {}
  }

  // 正式微信登录是唯一入口，不留构建期开关或测试登录旁路（静默旁路会让正式登录不执行且不报错）。
  // 隐私门先于登录：后台配置了指引且用户未同意时停留
  const contractName = await queryPrivacyGate()
  if (contractName) {
    privacyContractName.value = contractName
    authStage.value = 'PRIVACY_REQUIRED'
    statusText.value = '请先阅读并同意隐私说明'
    return
  }
  await runWechatLogin()
})

/** 401/会话失效统一处理：清真实会话与失效上下文，回落到正式登录入口，绝不回退 Mock。 */
function onUnauthorized(message: string) {
  accountStore.handleUnauthorized()
  bindTicket.value = ''
  authStage.value = 'FAILED'
  statusText.value = message
}

/**
 * 正式微信静默登录：BOUND 建会话进首页；UNBOUND 转手机号绑定阶段。
 * 401 走 onUnauthorized；其它失败置 FAILED，可点击“重新登录”重试。
 */
async function runWechatLogin() {
  if (authBusy.value) {
    return
  }
  authBusy.value = true
  authStage.value = 'LOGGING_IN'
  statusText.value = '正在微信登录'
  try {
    const result = await authApi.login()
    if (result.stage === 'BOUND') {
      accountStore.establishRealSession(result.context)
      await consumePendingInvite()
      statusText.value = '登录成功'
      switchToTab('U01')
      return
    }
    bindTicket.value = result.bindTicket
    authStage.value = 'PHONE_REQUIRED'
    statusText.value = '请授权手机号完成注册'
  }
  catch (error) {
    if (error instanceof ContractError && error.code === 'UNAUTHORIZED') {
      onUnauthorized('登录状态异常，请重新登录')
      return
    }
    authStage.value = 'FAILED'
    // 只透传 ContractError（本端登记过的用户可读文案）；其余错误一律收成固定句，不印原文
    statusText.value = error instanceof ContractError ? error.message : '登录失败，请重试'
  }
  finally {
    authBusy.value = false
  }
}

/**
 * 手机号授权回调：拿到 phoneCode 后以一次性 bindTicket 绑定并建会话。
 * 用户拒绝授权 → 停留当前阶段可重试；票据失效 → 回到登录阶段需重新 login。
 */
async function onGetPhoneNumber(event: { detail?: { code?: string, errMsg?: string } }) {
  const authorization = readPhoneAuthorization(event.detail)
  if (!authorization.authorized) {
    // 拒绝/取消可恢复：停留待绑定阶段，一次性票据不消费；平台侧不可用另给出路（须运营处理，重点无用）
    const reason = String(authorization.reason ?? '')
    const userDeclined = reason.includes('cancel') || reason.includes('deny') || reason === ''
    if (userDeclined) {
      toast.show('需要授权手机号才能完成注册，可重新授权')
    }
    else {
      phoneBlockedReason.value = reason
      toast.show('手机号授权暂不可用，请联系运营')
    }
    return
  }
  if (authBusy.value) {
    return
  }
  authBusy.value = true
  statusText.value = '正在绑定手机号'
  try {
    const ctx = await authApi.bindPhone(bindTicket.value, authorization.phoneCode)
    accountStore.establishRealSession(ctx)
    await consumePendingInvite()
    bindTicket.value = ''
    statusText.value = '绑定成功'
    switchToTab('U01')
  }
  catch (error) {
    if (error instanceof ContractError && error.code === 'UNAUTHORIZED') {
      onUnauthorized('登录状态异常，请重新登录')
      return
    }
    // 票据一次性：任何绑定失败都作废票据，退回登录阶段重新获取。
    bindTicket.value = ''
    authStage.value = 'FAILED'
    statusText.value = error instanceof ContractError ? error.message : '绑定失败，请重新登录'
  }
  finally {
    authBusy.value = false
  }
}
</script>

<template>
  <view class="entry-page">
    <wd-toast />
    <view class="e2e-runtime-contract" aria-hidden="true">
      BUILD={{ buildFingerprint }}
    </view>
    <!-- 品牌区：真实水站产品图 + 品牌名；文案由节点渲染，不烘焙进图片 -->
    <view class="brand-hero">
      <image
        class="brand-hero__image"
        src="/static/brand/home-water-station.jpg"
        mode="aspectFill"
      />
      <view class="brand-hero__copy">
        <text class="brand-hero__title">
          六维达康
        </text>
        <text class="brand-hero__slogan">
          社区智慧水站
        </text>
      </view>
    </view>

    <!-- 登录卡：同页两步（微信授权 → 手机号），不跳独立状态页 -->
    <view class="login-card">
      <!-- 步骤指示：手机号组件不可用时整条隐藏（那时登录只有微信授权一步，第 2 步永远走不到） -->
      <view v-if="phoneComponentAvailable" class="login-steps">
        <view
          class="login-step"
          :class="{
            'is-done': authStage === 'PHONE_REQUIRED',
            'is-error': authStage === 'FAILED',
            'is-active': authStage === 'LOGGING_IN',
          }"
        >
          <view class="login-step-dot">
            <wd-icon v-if="authStage === 'PHONE_REQUIRED'" name="check" size="12px" color="var(--app-text-inverse)" />
            <text v-else-if="authStage === 'FAILED'">
              !
            </text>
            <text v-else>
              1
            </text>
          </view>
          <text class="login-step-label">
            微信授权
          </text>
        </view>
        <view class="login-step-line" :class="{ 'is-done': authStage === 'PHONE_REQUIRED' }" />
        <view class="login-step" :class="{ 'is-active': authStage === 'PHONE_REQUIRED' }">
          <view class="login-step-dot">
            <text>2</text>
          </view>
          <text class="login-step-label">
            绑定手机号
          </text>
        </view>
      </view>

      <!-- 第二步：手机号授权 -->
      <template v-if="authStage === 'PHONE_REQUIRED'">
        <!-- 标题跟着能力走：组件不可用时不得顶着「还差一步」承诺做不到的下一步 -->
        <view class="login-headline">
          {{ phoneComponentAvailable ? '还差一步' : '暂时无法完成注册' }}
        </view>
        <view class="login-sub">
          {{ phoneComponentAvailable
            ? '绑定后即可下单取水'
            : '暂未开放，请联系运营' }}
        </view>
        <template v-if="phoneComponentAvailable">
          <button
            class="login-primary-btn"
            open-type="getPhoneNumber"
            :disabled="authBusy"
            @getphonenumber="onGetPhoneNumber"
          >
            <wd-icon name="phone" size="18px" color="var(--app-text-inverse)" />
            <text>微信手机号一键绑定</text>
          </button>
          <view v-if="phoneBlockedReason" class="login-note">
            手机号授权暂不可用，请联系运营
          </view>
          <view v-else class="login-note">
            用于订单联系与账号找回
          </view>
        </template>
      </template>

      <!-- 隐私同意：平台《用户隐私保护指引》已配置且未同意时的强制停留（配置前本分支永不出现） -->
      <template v-else-if="authStage === 'PRIVACY_REQUIRED'">
        <view class="login-headline">
          隐私保护提示
        </view>
        <view class="login-sub">
          使用前请阅读并同意
        </view>
        <view class="login-contract-name">
          《{{ privacyContractName }}》
        </view>
        <button
          class="login-primary-btn"
          open-type="agreePrivacyAuthorization"
          @agreeprivacyauthorization="onPrivacyAgreed"
        >
          <text>同意并继续</text>
        </button>
        <view class="login-note login-privacy-link" @click="openPrivacyContract">
          查看《{{ privacyContractName }}》全文
        </view>
      </template>

      <!-- 失败态：按原因分类给出可执行的下一步，而不是笼统的"重新登录" -->
      <template v-else-if="authStage === 'FAILED'">
        <view class="login-headline login-headline-error">
          登录未完成
        </view>
        <view class="login-sub login-sub-error">
          {{ statusText }}
        </view>
        <wd-button block size="large" type="primary" :loading="authBusy" @click="runWechatLogin">
          重试
        </wd-button>
      </template>

      <!-- 第一步进行中：骨架占位，避免白屏 -->
      <template v-else>
        <view class="login-headline">
          正在登录
        </view>
        <view class="login-skeleton">
          <view class="login-skeleton-bar" />
        </view>
      </template>
    </view>
  </view>
</template>

<style scoped lang="scss">
.entry-page {
  position: relative;
  display: flex;
  box-sizing: border-box;
  min-height: 100vh;
  flex-direction: column;
  align-items: stretch;
  /* 横向留白与其余页面（page-shell）对齐到 16px；纵向不再为居中的水滴图形留 56px */
  padding: calc(env(safe-area-inset-top) + 20px) var(--sp-4) 40px;
  overflow: hidden;
  background: var(--app-bg-page);
}

/* ---------------- 品牌区 ---------------- */

/* 首屏媒体焦点：本页唯一允许带投影的块 */
.brand-hero {
  position: relative;
  height: 200px;
  overflow: hidden;
  border-radius: var(--r-lg);
  box-shadow: var(--sh-card);

  &__image {
    width: 100%;
    height: 100%;
  }

  /* 文案压在画面左侧白墙留白上，不叠白色文案卡、不铺遮罩 */
  &__copy {
    position: absolute;
    top: var(--sp-5);
    left: var(--sp-4);
    display: flex;
    width: 50%;
    flex-direction: column;
  }

  &__title {
    color: var(--app-text-primary);
    font-size: var(--fs-metric);
    font-weight: 700;
    letter-spacing: 2px;
  }

  &__slogan {
    margin-top: var(--sp-2);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
    letter-spacing: 1px;
  }
}

/* ---------------- 登录区 ---------------- */

/* 平面表单区：不套白卡、不投影 */
.login-card {
  margin-top: var(--gap-group);
  padding: var(--sp-2) var(--sp-1) 0;
}

/* 步骤条 */
.login-steps {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 26px;
}

.login-step {
  display: flex;
  min-width: 64px;
  flex-direction: column;
  align-items: center;
  gap: 7px;
}

.login-step-dot {
  display: flex;
  width: 24px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--line-2);
  color: var(--app-text-tertiary);
  font-size: 12px;
  font-weight: 600;
  transition: all 0.25s ease;
}

.login-step.is-active .login-step-dot {
  background: var(--app-color-primary);
  color: var(--app-text-inverse);
  box-shadow: 0 0 0 4px var(--tint-primary-strong);
}

.login-step.is-done .login-step-dot {
  background: var(--app-color-success);
  color: var(--app-text-inverse);
}

.login-step.is-error .login-step-dot {
  background: var(--app-color-danger);
  color: var(--app-text-inverse);
  box-shadow: 0 0 0 4px var(--tint-danger);
}

.login-step.is-error .login-step-label {
  color: var(--app-color-danger);
}

.login-step-label {
  color: var(--app-text-tertiary);
  font-size: 12px;
  transition: color 0.25s ease;
}

.login-step.is-active .login-step-label {
  color: var(--app-text-primary);
  font-weight: 500;
}

.login-step.is-done .login-step-label {
  color: var(--app-color-success);
}

.login-step-line {
  width: 48px;
  height: 2px;
  margin: 0 4px 20px;
  border-radius: 1px;
  background: var(--line-2);
  transition: background 0.25s ease;
}

.login-step-line.is-done {
  background: var(--app-color-success);
}

/* 文案区 */
.login-headline {
  color: var(--app-text-primary);
  font-size: 19px;
  font-weight: 600;
  text-align: center;
}

.login-headline-error {
  color: var(--app-color-danger);
}

.login-sub {
  margin-top: 8px;
  margin-bottom: 24px;
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.6;
  text-align: center;
}

.login-sub-error {
  color: var(--app-text-tertiary);
}

/* 主按钮使用单一品牌蓝实色。原来挂着一圈品牌色外发光（box-shadow 0 6px 16px），
   本轮阴影只留给首屏媒体、吸底动作栏和浮层，按钮不发光；按下改用位移反馈。
   胶囊圆角与全局 wd-button 保持一致——这是全应用主动作的统一形状。 */
.login-primary-btn {
  display: flex;
  height: 48px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: var(--r-sm);
  margin: 0;
  background: var(--app-color-primary);
  color: var(--app-text-inverse);
  font-size: 16px;
  font-weight: 500;
  gap: 8px;
  line-height: 48px;
  transition: transform 0.15s ease;
}

.login-primary-btn::after {
  border: none;
}

.login-primary-btn:active {
  transform: scale(0.98);
}

.login-primary-btn[disabled] {
  background: var(--app-text-disabled);
}

.login-note {
  margin-top: 14px;
  color: var(--app-text-tertiary);
  font-size: 12px;
  text-align: center;
}

.login-privacy-link {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  color: var(--app-color-primary);
}

/* 指引名由后台配置，长度不可控：单独一行并单行截断，不让书名号被折断 */
.login-contract-name {
  overflow: hidden;
  margin-top: var(--sp-1);
  margin-bottom: var(--sp-6);
  color: var(--app-text-secondary);
  font-size: 13px;
  text-align: center;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 加载骨架：比一行"登录中"更少白屏感 */
.login-skeleton {
  height: 48px;
  overflow: hidden;
  border-radius: var(--r-sm);
  background: var(--app-bg-page);
}

.login-skeleton-bar {
  width: 100%;
  height: 100%;
  background: var(--tint-neutral);
  animation: login-pulse 1.4s ease-in-out infinite;
}

@keyframes login-pulse {
  0%,
  100% {
    opacity: 0.45;
  }

  50% {
    opacity: 1;
  }
}

.e2e-runtime-contract {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

.entry-test-accounts {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 24px;
  padding: 0 8px;
}
</style>
