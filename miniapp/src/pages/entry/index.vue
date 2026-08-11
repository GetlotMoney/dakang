<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { authApi, loginByTestPhone, readPhoneAuthorization, TEST_LOGIN_SWITCH_KEY, testLoginAccounts, testLoginPhone } from '@/api/auth'
import { ContractError } from '@/api/common'
import { buildRuntimeModes, currentMode, isPhoneComponentAvailable } from '@/api/runtime'
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
/** auth 域翻 real 即进入正式微信登录入口（uni.login/绑手机号），否则维持 C01 原型入口。 */
const isRealAuth = currentMode('auth') === 'real'
/**
 * 手机号组件是否可用。不可用时**绝不渲染**那个按钮：主体未过微信认证时平台在组件层禁用，
 * 点击不弹窗、回调也不触发，渲染出来只会得到一个「点了没反应」的死按钮。
 */
const phoneComponentAvailable = isPhoneComponentAvailable()
const buildFingerprint = __DAKANG_BUILD_FINGERPRINT__
// 统一走 buildRuntimeModes()：全仓唯一发射点，禁止在页面内手工拼接段。
const e2eApiModes = buildRuntimeModes()

const statusText = ref('正在恢复账号会话')

/** 登录阶段：隐私同意（平台指引已配置且未同意时）/ 登录中 / 待授权手机号 / 失败可重试 / 测试账号选择。 */
const authStage = ref<'PRIVACY_REQUIRED' | 'LOGGING_IN' | 'PHONE_REQUIRED' | 'FAILED' | 'CHOOSE_TEST_ACCOUNT'>('LOGGING_IN')
/** 平台配置的《用户隐私保护指引》名称（getPrivacySetting 下发；仅 PRIVACY_REQUIRED 阶段使用）。 */
const privacyContractName = ref('')
const authBusy = ref(false)
const bindTicket = ref('')
/** 平台侧手机号能力不可用时的原始 errMsg：真机无控制台，必须显示在界面上才可定位。 */
const phoneBlockedReason = ref('')

/**
 * 自适应隐私门：查询平台隐私授权状态。
 *
 * <p>三态出口：后台未配置《用户隐私保护指引》或基础库无此 API → 返回 null（无门可守，直接登录，
 * 行为与配置前完全一致）；已配置且用户未同意 → 返回指引名（进 PRIVACY_REQUIRED 停留）；
 * 已同意 → 返回 null 直接登录。门随后台配置自动出现/消失，代码不用改。</p>
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

onLoad(async () => {
  if (isRealAuth) {
    // 隐私门先于登录：后台配置了指引且用户未同意时停留；否则与既有动线完全一致。
    const contractName = await queryPrivacyGate()
    if (contractName) {
      privacyContractName.value = contractName
      authStage.value = 'PRIVACY_REQUIRED'
      statusText.value = '请先阅读并同意隐私说明'
      return
    }
    // 正式微信入口：直接静默登录，不依赖任何 Mock 账号或未实现的读会话接口。
    await runWechatLogin()
    return
  }
  if (testLoginPhone) {
    // 测试登录：建立的是**真实 KH_USER 会话**，因此不触犯"接真业务域不得用 Mock 账号进入"这条——
    // 那条禁的是拿原型身份打真实接口，而这里拿到的 token 与正式登录落地的是同一份。
    // 显式退出登录后停在账号选择（不自动重登）：一台真机切换下单方/配送员即可走通完整业务链；
    // 401 强制登出不带该标记，仍自动重登默认号，保证会话失效恢复路径与 e2e 行为不变。
    if (uni.getStorageSync(TEST_LOGIN_SWITCH_KEY) && testLoginAccounts.length > 1) {
      uni.removeStorageSync(TEST_LOGIN_SWITCH_KEY)
      authStage.value = 'CHOOSE_TEST_ACCOUNT'
      statusText.value = '请选择测试账号'
      return
    }
    await runTestLogin(testLoginPhone)
    return
  }
  // 既非正式微信入口也未配置测试账号：fail-closed 停在说明态（mock 原型入口已随基建退役）。
  accountStore.handleUnauthorized()
  statusText.value = '暂时无法登录，请联系运营'
})

/** 401/会话失效统一处理：清真实会话与失效上下文，回落到正式登录入口，绝不回退 Mock。 */
function onUnauthorized(message: string) {
  accountStore.handleUnauthorized()
  bindTicket.value = ''
  authStage.value = 'FAILED'
  statusText.value = message
}

/**
 * 测试登录（仅隔离测试环境）：以配置的手机号换取真实 KH_USER 会话。
 * 失败时如实停在失败态，不回退 Mock 账号——否则会掩盖"后端开关没开"这类真实故障。
 */
async function runTestLogin(phone: string) {
  if (authBusy.value) {
    return
  }
  authBusy.value = true
  authStage.value = 'LOGGING_IN'
  statusText.value = '正在以测试账号登录'
  try {
    const ctx = await loginByTestPhone(phone)
    accountStore.establishRealSession(ctx)
    statusText.value = `测试登录成功：${ctx.userName}`
    switchToTab('U01')
  }
  catch (error) {
    accountStore.handleUnauthorized()
    authStage.value = 'FAILED'
    statusText.value = error instanceof ContractError
      ? `测试登录失败：${error.message}`
      // 原文把 MINI_PAYSIM_ENABLED 这个后端环境变量名甩给用户看：用户既改不了它，
      // 也无从判断是不是自己的问题。排障线索留在错误码与日志里。
      : '测试登录失败，请稍后重试'
    if (testLoginAccounts.length > 1) {
      // 多账号构建：失败后停回选择页，允许直接换号重试
      authStage.value = 'CHOOSE_TEST_ACCOUNT'
    }
  }
  finally {
    authBusy.value = false
  }
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
    statusText.value = error instanceof Error ? error.message : '登录失败，请重试'
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
    // 拒绝 / 取消授权：可恢复，停留在待绑定阶段，一次性票据不消费。
    // 平台侧不可用（未开通手机号验证组件、额度不足、主体未认证）与用户主动拒绝
    // 需给不同出路——前者用户再点一百次也没用，必须让运营去后台处理。
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
    statusText.value = error instanceof Error ? error.message : '绑定失败，请重新登录'
  }
  finally {
    authBusy.value = false
  }
}
</script>

<template>
  <view class="entry-page screen-c01">
    <wd-toast />
    <view class="e2e-runtime-contract" aria-hidden="true">
      BUILD={{ buildFingerprint }};{{ e2eApiModes }}
    </view>
    <!-- 品牌区：水滴意象 + 品牌渐变；登录页是产品第一屏，不放调试信息 -->
    <view class="brand-hero">
      <view class="brand-mark">
        <view class="brand-drop">
          <view class="brand-drop-gloss" />
        </view>
        <view class="brand-ripple brand-ripple-1" />
        <view class="brand-ripple brand-ripple-2" />
      </view>
      <view class="brand-title">
        六维达康智慧水站
      </view>
      <view class="brand-slogan">
        扫码即取，好水随行
      </view>
    </view>

    <view v-if="authStage === 'CHOOSE_TEST_ACCOUNT'" class="login-card entry-test-accounts">
      <view class="login-headline">
        选择测试账号
      </view>
      <wd-button
        v-for="account in testLoginAccounts"
        :key="account.phone"
        block
        size="large"
        :loading="authBusy"
        @click="runTestLogin(account.phone)"
      >
        {{ account.label }}（{{ account.phone }}）
      </wd-button>
    </view>

    <!--
      登录卡：同页两步（微信授权 → 手机号），不跳独立状态页——
      原实现登录成功后整屏换成一句"请授权手机号"，用户会误以为登录失败了。
    -->
    <view v-if="isRealAuth" class="login-card">
      <!--
        步骤指示：两步都可见，当前步高亮，让用户知道还剩几步。
        手机号组件不可用时整条隐藏——那时登录只有「微信授权」一步（服务端直接以微信身份建号），
        再画一个永远走不到的第 2 步只会让用户以为流程没走完。
      -->
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
            <wd-icon v-if="authStage === 'PHONE_REQUIRED'" name="check" size="12px" color="#ffffff" />
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
        <!--
          标题必须跟着能力走：组件不可用时这一步根本走不到，
          再顶着「还差一步」就是在承诺一个做不到的下一步——和下面「暂未开放」自相矛盾。
        -->
        <view class="login-headline">
          {{ phoneComponentAvailable ? '还差一步' : '暂时无法完成注册' }}
        </view>
        <view class="login-sub">
          {{ phoneComponentAvailable
            ? '绑定手机号后即可使用取水、配送与水卡服务'
            : '手机号绑定暂未开放，请联系运营开通账号' }}
        </view>
        <template v-if="phoneComponentAvailable">
          <button
            class="login-primary-btn"
            open-type="getPhoneNumber"
            :disabled="authBusy"
            @getphonenumber="onGetPhoneNumber"
          >
            <wd-icon name="phone" size="18px" color="#ffffff" />
            <text>微信手机号一键绑定</text>
          </button>
          <view v-if="phoneBlockedReason" class="login-note">
            手机号授权暂不可用，请联系运营
          </view>
          <view v-else class="login-note">
            仅用于订单联系与账号找回，不会对外展示
          </view>
        </template>
        <!--
          组件不可用时不渲染按钮：主体未过微信认证，平台在组件层拦截，点击既不弹窗也不回调，
          放个按钮在这里只会让用户反复点一个永远没反应的东西。
        -->
      </template>

      <!-- 隐私同意：平台《用户隐私保护指引》已配置且未同意时的强制停留（配置前本分支永不出现） -->
      <template v-else-if="authStage === 'PRIVACY_REQUIRED'">
        <view class="login-headline">
          隐私保护提示
        </view>
        <view class="login-sub">
          使用前请阅读并同意《{{ privacyContractName }}》
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
        <view class="login-note">
          不同意将无法使用取水与配送服务
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
        <view class="login-note">
          多次失败请确认网络，或联系运营人员
        </view>
      </template>

      <!-- 第一步进行中：骨架占位，避免白屏 -->
      <template v-else>
        <view class="login-headline">
          正在登录
        </view>
        <view class="login-sub">
          微信授权中，请稍候
        </view>
        <view class="login-skeleton">
          <view class="login-skeleton-bar" />
        </view>
      </template>
    </view>

    <!-- 身份说明：替代"选择角色"——身份由平台数据决定，不由用户自选 -->
    <view v-if="isRealAuth" class="login-identity-tip">
      <wd-icon name="info-circle" size="14px" color="#646a73" />
      <text>若你是机主或配送员，请先联系运营开通。</text>
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
  padding: calc(env(safe-area-inset-top) + 56px) 24px 40px;
  overflow: hidden;
  /* 顶部品牌渐变 → 页面底色：水的通透感，避免纯白登录页的廉价感 */
  background:
    radial-gradient(120% 60% at 50% 0%, rgba(46, 124, 246, 0.16) 0%, rgba(14, 165, 183, 0.06) 45%, rgba(245, 247, 250, 0) 70%),
    var(--app-bg-page);
}

/* ---------------- 品牌区 ---------------- */

.brand-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-bottom: 8px;
}

.brand-mark {
  position: relative;
  display: flex;
  width: 76px;
  height: 76px;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
}

/* 水滴：左上角取直角后顺时针 45°，尖角正朝上；纯 CSS 不依赖图标字体 */
.brand-drop {
  position: relative;
  z-index: 2;
  width: 62px;
  height: 62px;
  border-radius: 0 50% 50% 50%;
  background: linear-gradient(140deg, #5b9bff 0%, #2e7cf6 52%, #0ea5b7 100%);
  box-shadow:
    0 10px 24px rgba(46, 124, 246, 0.3),
    inset 0 -6px 12px rgba(0, 0, 0, 0.06);
  transform: rotate(45deg);
}

/* 高光：水的反光，让纯色块有体积感 */
.brand-drop-gloss {
  position: absolute;
  top: 30%;
  left: 22%;
  width: 16px;
  height: 22px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.42);
  filter: blur(2px);
  transform: rotate(-45deg);
}

/* 涟漪：两层错峰扩散，暗示"活水"，静态页面里加一点生命感 */
.brand-ripple {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 68px;
  height: 68px;
  border: 1px solid rgba(46, 124, 246, 0.4);
  border-radius: 50%;
  animation: brand-ripple 2.8s ease-out infinite;
  transform: translate(-50%, -50%);
}

.brand-ripple-2 {
  animation-delay: 1.4s;
}

@keyframes brand-ripple {
  0% {
    opacity: 0.55;
    transform: translate(-50%, -50%) scale(1);
  }

  100% {
    opacity: 0;
    transform: translate(-50%, -50%) scale(1.9);
  }
}

.brand-title {
  color: var(--app-text-primary);
  font-size: 23px;
  font-weight: 600;
  letter-spacing: 1px;
}

.brand-slogan {
  margin-top: 8px;
  color: var(--app-text-secondary);
  font-size: 13px;
  letter-spacing: 3px;
}

/* ---------------- 登录卡 ---------------- */

.login-card {
  margin-top: 36px;
  padding: 28px 24px 24px;
  border-radius: 20px;
  background: #ffffff;
  /* 双层阴影：近距柔和 + 远距扩散，比单层更有浮起感 */
  box-shadow:
    0 2px 8px rgba(31, 35, 41, 0.04),
    0 12px 32px rgba(31, 35, 41, 0.07);
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
  background: #e5e6eb;
  color: #8f959e;
  font-size: 12px;
  font-weight: 600;
  transition: all 0.25s ease;
}

.login-step.is-active .login-step-dot {
  background: var(--app-color-primary);
  color: #ffffff;
  box-shadow: 0 0 0 4px rgba(46, 124, 246, 0.12);
}

.login-step.is-done .login-step-dot {
  background: var(--app-color-success);
  color: #ffffff;
}

.login-step.is-error .login-step-dot {
  background: var(--app-color-danger);
  color: #ffffff;
  box-shadow: 0 0 0 4px rgba(255, 59, 48, 0.12);
}

.login-step.is-error .login-step-label {
  color: var(--app-color-danger);
}

.login-step-label {
  color: #8f959e;
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
  background: #e5e6eb;
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
  color: #8f959e;
}

/* 主按钮：微信绿不适合品牌页，用品牌蓝渐变；按压有回弹 */
.login-primary-btn {
  display: flex;
  height: 48px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 24px;
  margin: 0;
  background: linear-gradient(135deg, #4d93ff 0%, #2e7cf6 100%);
  box-shadow: 0 6px 16px rgba(46, 124, 246, 0.28);
  color: #ffffff;
  font-size: 16px;
  font-weight: 500;
  gap: 8px;
  line-height: 48px;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.login-primary-btn::after {
  border: none;
}

.login-primary-btn:active {
  box-shadow: 0 3px 10px rgba(46, 124, 246, 0.24);
  transform: scale(0.98);
}

.login-primary-btn[disabled] {
  background: #c9cdd4;
  box-shadow: none;
}

.login-note {
  margin-top: 14px;
  color: #8f959e;
  font-size: 12px;
  text-align: center;
}

.login-privacy-link {
  color: #5d87ff;
}

/* 加载骨架：比一行"登录中"更少白屏感 */
.login-skeleton {
  height: 48px;
  overflow: hidden;
  border-radius: 24px;
  background: #f2f3f5;
}

.login-skeleton-bar {
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, #f2f3f5 25%, #e8eaed 37%, #f2f3f5 63%);
  background-size: 400% 100%;
  animation: login-shimmer 1.4s ease infinite;
}

@keyframes login-shimmer {
  0% {
    background-position: 100% 50%;
  }

  100% {
    background-position: 0 50%;
  }
}

/* 身份说明 */
.login-identity-tip {
  display: flex;
  align-items: flex-start;
  padding: 0 4px;
  margin-top: 20px;
  color: var(--app-text-secondary);
  font-size: 12px;
  gap: 6px;
  line-height: 1.6;
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
