<script setup lang="ts">
import type { EntryContractState, MockAccountSummary } from '@/api/account'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { accountApi } from '@/api/account'
import { authApi, loginByTestPhone, readPhoneAuthorization, testLoginPhone } from '@/api/auth'
import { ContractError } from '@/api/common'
import { buildRuntimeModes, currentMode, isAllMockBuild } from '@/api/runtime'
import AppPrototypeNotice from '@/components/prototype-notice.vue'
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
const isMockBuild = isAllMockBuild()
/** auth 域翻 real 即进入正式微信登录入口（uni.login/绑手机号），否则维持 C01 原型入口。 */
const isRealAuth = currentMode('auth') === 'real'
const buildFingerprint = __DAKANG_BUILD_FINGERPRINT__
// 统一走 buildRuntimeModes()：全仓唯一发射点，禁止在页面内手工拼接段。
const e2eApiModes = buildRuntimeModes()

const statusText = ref('正在恢复账号会话')
const entryState = ref<EntryContractState | null>(null)
const accounts = ref<MockAccountSummary[]>([])

/** 正式登录阶段（仅 real 入口使用）：登录中 / 待授权手机号 / 失败可重试。 */
const authStage = ref<'LOGGING_IN' | 'PHONE_REQUIRED' | 'FAILED'>('LOGGING_IN')
const authBusy = ref(false)
const bindTicket = ref('')

const context = computed(() => accountStore.context)

/** 登录/绑定契约状态的中文口径（蓝图 C01：五个阶段均有明确状态，不伪造微信成功）。 */
const ENTRY_STAGE_LABELS: Record<EntryContractState['stage'], string> = {
  UNAUTHENTICATED: '未登录',
  PRIVACY_REQUIRED: '待同意隐私说明',
  PHONE_REQUIRED: '待绑定手机号',
  READY: '会话已就绪',
  AUTHORIZATION_FAILED: '授权失败',
}

const CAPABILITY_STATE_LABELS: Record<EntryContractState['wechatLoginCapability'], string> = {
  unavailable: '待接入',
  denied: '已拒绝',
  canceled: '已取消',
  success: '成功',
}

const CAPABILITY_TITLES: Record<string, string> = {
  USER_BASE: '用水服务',
  COURIER_APPLY: '配送准入',
  COURIER_WORK: '配送工作',
  OWNER_VIEW: '经营查看',
  OWNER_SERVICE: '设备服务',
}

onLoad(async () => {
  if (isRealAuth) {
    // 正式微信入口：直接静默登录，不依赖任何 Mock 账号或未实现的读会话接口。
    await runWechatLogin()
    return
  }
  if (testLoginPhone) {
    // 测试登录：建立的是**真实 KH_USER 会话**，因此不触犯"接真业务域不得用 Mock 账号进入"这条——
    // 那条禁的是拿原型身份打真实接口，而这里拿到的 token 与正式登录落地的是同一份。
    await runTestLogin(testLoginPhone)
    return
  }
  if (!isMockBuild) {
    // 存在已接真的业务域但未开启正式登录入口：绝不能用 Mock 原型账号恢复会话进首页（复审 B）。
    // 会话失效 reLaunch 回本页时也走这里，保证"401 后不落 Mock 账号"无条件成立。
    accountStore.handleUnauthorized()
    statusText.value = '当前构建已接入真实业务接口，但未开启正式微信登录入口；请改用正式登录构建，不能以原型账号进入'
    return
  }
  try {
    await accountStore.restoreSession()
    entryState.value = await accountApi.getEntryState()
    statusText.value = '账号会话已恢复'
    accounts.value = await accountApi.listMockAccounts()
  }
  catch {
    statusText.value = accountStore.errorMessage ?? '账号会话恢复失败'
  }
})

function enterHome() {
  switchToTab('U01')
}

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
  statusText.value = '正在以测试账号登录（仅测试环境）'
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
      : '测试登录失败，请确认后端已开启 MINI_PAYSIM_ENABLED 且该手机号账号存在'
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
    // 拒绝 / 取消授权：可恢复，提示后停留在待绑定阶段，一次性票据不消费。
    toast.show('需要授权手机号才能完成注册，可重新授权')
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

async function selectAccount(accountId: string) {
  try {
    await accountStore.selectMockAccount(accountId)
    entryState.value = await accountApi.getEntryState()
    switchToTab('U01')
  }
  catch (error) {
    toast.show(error instanceof Error ? error.message : '切换原型账号失败')
  }
}

/** 重置原型数据到初始快照：演示复位与自动化走查幂等入口（页面承诺"刷新后重置"的显式版本）。 */
async function resetScenario() {
  try {
    await accountApi.resetMockScenario()
    await accountStore.restoreSession()
    entryState.value = await accountApi.getEntryState()
    accounts.value = await accountApi.listMockAccounts()
    toast.show('原型数据已重置为初始快照')
  }
  catch (error) {
    toast.show(error instanceof Error ? error.message : '重置失败')
  }
}

/** 模拟 PC 停用/恢复配送员（S01.5/S02.5 能力撤销演示），仅 Mock 构建可见。 */
async function toggleCourierWork(enabled: boolean) {
  const current = context.value
  if (!current) {
    return
  }
  try {
    await accountApi.setMockCourierWorkEnabled(current.accountId, enabled)
    await accountStore.selectMockAccount(current.accountId)
    accounts.value = await accountApi.listMockAccounts()
    toast.show(enabled ? '已恢复配送工作能力' : '已停用配送工作能力，入口即刻失效')
  }
  catch (error) {
    toast.show(error instanceof Error ? error.message : '操作失败')
  }
}
</script>

<template>
  <view class="entry-page">
    <wd-toast />
    <view class="e2e-runtime-contract" aria-hidden="true">
      BUILD={{ buildFingerprint }};{{ e2eApiModes }}
    </view>
    <view class="entry-brand">
      六维达康智慧水站
    </view>
    <view class="entry-status">
      {{ statusText }}
    </view>

    <view v-if="isRealAuth" class="entry-auth">
      <view v-if="authStage === 'PHONE_REQUIRED'" class="entry-auth-phone">
        <view class="muted-text entry-auth-tip">
          登录成功，请授权手机号完成账号注册。
        </view>
        <button
          class="entry-auth-btn"
          open-type="getPhoneNumber"
          :disabled="authBusy"
          @getphonenumber="onGetPhoneNumber"
        >
          授权手机号并进入
        </button>
      </view>
      <view v-else-if="authStage === 'FAILED'" class="entry-auth-retry">
        <wd-button block size="large" :loading="authBusy" @click="runWechatLogin">
          重新登录
        </wd-button>
      </view>
      <view v-else class="muted-text entry-auth-tip">
        正在通过微信为你登录…
      </view>
    </view>

    <view v-if="!isRealAuth && entryState" class="entry-contract">
      <view class="entry-contract-row">
        <view class="muted-text">
          会话阶段
        </view>
        <wd-tag type="success" plain>
          {{ ENTRY_STAGE_LABELS[entryState.stage] }}
        </wd-tag>
      </view>
      <view class="entry-contract-row">
        <view class="muted-text">
          微信登录
        </view>
        <wd-tag plain>
          {{ CAPABILITY_STATE_LABELS[entryState.wechatLoginCapability] }}
        </wd-tag>
      </view>
      <view class="entry-contract-row">
        <view class="muted-text">
          手机号绑定
        </view>
        <wd-tag plain>
          {{ CAPABILITY_STATE_LABELS[entryState.phoneBindingCapability] }}
        </wd-tag>
      </view>
      <view class="muted-text entry-contract-note">
        未登录、待同意、待绑定、授权失败等状态在接入真实微信能力后呈现；原型不伪造微信授权成功。
      </view>
    </view>

    <view v-if="!isRealAuth && context" class="entry-actions">
      <wd-button block size="large" @click="enterHome">
        以 {{ context.userName }} 进入首页
      </wd-button>
    </view>

    <view v-if="isMockBuild && accounts.length" class="entry-dev-panel">
      <AppPrototypeNotice text="全域 Mock 开发面板：只在不触达真实业务接口的构建中提供原型账号与场景重置。" />
      <wd-cell-group title="切换原型账号" border>
        <wd-cell
          v-for="item in accounts"
          :key="item.accountId"
          :title="item.userName"
          :label="item.capabilities.map(code => CAPABILITY_TITLES[code] ?? code).join(' / ')"
          :value="item.accountId === context?.accountId ? '当前' : ''"
          clickable
          @click="selectAccount(item.accountId)"
        />
      </wd-cell-group>
      <view v-if="context?.courierScope" class="entry-dev-toggle">
        <wd-button
          v-if="accountStore.hasCapability('COURIER_WORK')"
          plain
          size="small"
          @click="toggleCourierWork(false)"
        >
          模拟停用配送能力
        </wd-button>
        <wd-button v-else plain size="small" @click="toggleCourierWork(true)">
          模拟恢复配送能力
        </wd-button>
      </view>
      <view class="entry-dev-toggle">
        <wd-button plain size="small" type="warning" @click="resetScenario">
          重置原型数据
        </wd-button>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.entry-page {
  display: flex;
  box-sizing: border-box;
  min-height: 100vh;
  flex-direction: column;
  align-items: stretch;
  padding: calc(env(safe-area-inset-top) + 64px) 24px 48px;
  background: var(--app-bg-page);
}

.e2e-runtime-contract {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

.entry-brand {
  color: var(--app-text-primary);
  font-size: 24px;
  font-weight: 600;
  text-align: center;
}

.entry-status {
  margin-top: 12px;
  color: var(--app-text-secondary);
  font-size: 14px;
  text-align: center;
}

.entry-contract {
  margin-top: 24px;
  padding: 16px;
  border-radius: 12px;
  background: #fff;
}

.entry-contract-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
}

.entry-contract-note {
  margin-top: 8px;
  line-height: 1.6;
}

.entry-actions {
  margin-top: 24px;
}

.entry-auth {
  margin-top: 32px;
}

.entry-auth-tip {
  margin-bottom: 12px;
  line-height: 1.6;
  text-align: center;
}

.entry-auth-btn {
  width: 100%;
  height: 44px;
  border-radius: 8px;
  background: var(--app-brand, #07c160);
  color: #fff;
  font-size: 16px;
  line-height: 44px;
}

.entry-auth-btn[disabled] {
  opacity: 0.6;
}

.entry-dev-panel {
  margin-top: 32px;
}

.entry-dev-toggle {
  display: flex;
  justify-content: center;
  margin-top: 12px;
}
</style>
