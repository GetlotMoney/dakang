<script setup lang="ts">
import type { CardSummary, UsableCard } from '@/api/card'
import type { CourierAdmission } from '@/api/delivery'
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { deliveryApi } from '@/api/delivery'
import { inviteApi } from '@/api/invite'
import { authApi, readPhoneAuthorization, TEST_LOGIN_SWITCH_KEY, testLoginAccounts, testLoginPhone } from '@/api/auth'
import { ContractError } from '@/api/common'
import { avatarMimeFromPath, profileApi, readFileAsBase64 } from '@/api/profile'
import { resolveServerPath } from '@/api/request'
import { isPhoneComponentAvailable } from '@/api/runtime'
import { useAccountStore } from '@/store/account'
import { ADMISSION_STATUS_LABELS, CARD_STATUS_LABELS, maskPhone } from '@/utils/format'
import { goTo } from '@/utils/navigation'
import { getWxSafeHeader } from '@/utils/safe-area'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '我的',
  },
})

const safeHeader = getWxSafeHeader()
const toast = useToast()
const accountStore = useAccountStore()
const context = computed(() => accountStore.context)
const hasOwnerView = computed(() => accountStore.hasCapability('OWNER_VIEW'))
const serviceNotice = '取水、充值、配送会实际扣减水卡余额。'
const primaryCard = ref<CardSummary | null>(null)
const ownCards = ref<UsableCard[]>([])
const admission = ref<CourierAdmission | null>(null)

const maskedPhone = computed(() => maskPhone(context.value?.userPhone))
const avatarSrc = computed(() => resolveServerPath(context.value?.userAvatar))

// ---------- 编辑资料（头像/昵称，微信「资料填写能力」动线） ----------
const editingProfile = ref(false)
const editName = ref('')
/** 已选待上传的头像临时文件；保存时才读 base64 上传，取消即弃。 */
const pickedAvatar = ref<{ path: string, mime: string } | null>(null)
const savingProfile = ref(false)

const editAvatarPreview = computed(() => pickedAvatar.value?.path || avatarSrc.value)

function openProfileEdit() {
  editName.value = context.value?.userName ?? ''
  pickedAvatar.value = null
  editingProfile.value = true
}

/**
 * chooseAvatar 回调。未配置《用户隐私保护指引》时平台可能不回传 avatarUrl，
 * 必须给出可执行的指引而不是让按钮"点了没反应"（产物污染事故同款教训：沉默即死角）。
 */
function onChooseAvatar(event: unknown) {
  // uni 的 _ButtonOnChooseavatarEvent 类型声明与运行时 detail 结构不符，按运行时真实结构窄化
  const path = (event as { detail?: { avatarUrl?: string } })?.detail?.avatarUrl
  if (!path) {
    toast.show('未获取到头像，请重试')
    return
  }
  pickedAvatar.value = { path, mime: avatarMimeFromPath(path) }
}

async function saveProfile() {
  const name = editName.value.trim()
  const nameChanged = name.length > 0 && name !== context.value?.userName
  if (!nameChanged && !pickedAvatar.value) {
    toast.show('没有需要保存的修改')
    return
  }
  if (savingProfile.value) {
    return
  }
  savingProfile.value = true
  try {
    const payload: Parameters<typeof profileApi.update>[0] = {}
    if (nameChanged) {
      payload.userName = name
    }
    if (pickedAvatar.value) {
      payload.avatarBase64 = await readFileAsBase64(pickedAvatar.value.path)
      payload.avatarMimeType = pickedAvatar.value.mime
    }
    const ctx = await profileApi.update(payload)
    accountStore.establishRealSession(ctx)
    editingProfile.value = false
    pickedAvatar.value = null
    toast.success('资料已更新')
  }
  catch (error) {
    if (error instanceof ContractError && error.code === 'UNAUTHORIZED') {
      accountStore.handleUnauthorized()
      toast.show('登录状态已失效，请重新进入')
      return
    }
    toast.error(error instanceof Error ? error.message : '保存失败，请稍后重试')
  }
  finally {
    savingProfile.value = false
  }
}

/**
 * 组件不可用时只显示说明、不渲染按钮：主体未过微信认证，平台在组件层拦住 getPhoneNumber，
 * 点击不弹窗、回调也不触发——渲染出来就是个永远没反应的按钮。
 */
const phoneComponentAvailable = isPhoneComponentAvailable()
const bindingPhone = ref(false)

/** 补绑手机号：号码由服务端凭 phoneCode 换取，成功后就地刷新上下文，不换发会话、不跳登录页。 */
async function onGetPhoneNumber(event: { detail?: { code?: string, errMsg?: string } }) {
  const authorization = readPhoneAuthorization(event.detail)
  if (!authorization.authorized) {
    const reason = String(authorization.reason ?? '')
    const userDeclined = reason.includes('cancel') || reason.includes('deny') || reason === ''
    toast.show(userDeclined ? '已取消绑定' : '手机号授权暂不可用，请联系运营')
    return
  }
  if (bindingPhone.value) {
    return
  }
  bindingPhone.value = true
  try {
    const ctx = await authApi.bindPhoneSelf(authorization.phoneCode)
    accountStore.establishRealSession(ctx)
    toast.success('手机号已绑定')
  }
  catch (error) {
    if (error instanceof ContractError && error.code === 'UNAUTHORIZED') {
      accountStore.handleUnauthorized()
      toast.show('登录状态已失效，请重新进入')
      return
    }
    // 号码已归属他人等业务拒绝由后端给出确切原因，原样透出，不糊成"绑定失败"。
    toast.error(error instanceof Error ? error.message : '绑定失败，请稍后重试')
  }
  finally {
    bindingPhone.value = false
  }
}

const ownerScopeSummary = computed(() => {
  const scope = context.value?.ownerScope
  if (!scope) {
    return ''
  }
  return `${scope.stationIds.length} 个水站 / ${scope.deviceNos.length} 台设备`
})

onShow(refresh)

async function refresh() {
  if (!accountStore.restored) {
    await accountStore.restoreSession().catch(() => null)
  }
  if (!accountStore.context) {
    return
  }
  try {
    primaryCard.value = await cardApi.getPrimaryCard()
  }
  catch (error) {
    // 1401～1405 已由全局会话守卫清理上下文并返回入口；页面只终止本轮刷新，
    // 避免 onShow Promise 继续向框架抛出未处理异常。其它失败清空派生数据并如实提示。
    if (error instanceof ContractError && error.code === 'UNAUTHORIZED') {
      return
    }
    primaryCard.value = null
    ownCards.value = []
    admission.value = null
    toast.error(error instanceof Error ? error.message : '账户信息加载失败，请稍后重试')
    return
  }
  // 本人全部持卡（含冻结/赠卡）：只挂主卡入口会让第二张卡在小程序内零入口——
  // 卡详情与成员授权都要 cardId，进不去就查不到余额/有效期、也管不了授权成员。
  // 成员授权卡（accessRole=MEMBER）不在此列：那是别人的卡，管理权属卡主。
  ownCards.value = await cardApi.listUsableCards()
    .then(list => list.filter(item => item.accessRole === 'OWNER'))
    .catch(() => [])
  // 配送准入状态所有账号均可查看（COURIER_APPLY 属基础投影），查询失败按无记录降级。
  admission.value = await deliveryApi.getCourierAdmission().catch(() => null)
}

function handleCardTap() {
  if (primaryCard.value) {
    goTo('U11', { cardId: primaryCard.value.cardId })
  }
  else {
    goTo('U10')
  }
}

function showPrivacyNote() {
  uni.showModal({
    title: '隐私与授权',
    content: '登录与手机号绑定使用微信授权，当前不获取你的位置。',
    showCancel: false,
  })
}

function showServiceNote() {
  uni.showModal({
    title: '服务说明',
    content: serviceNotice,
    showCancel: false,
  })
}

/** 退出登录：清会话回统一入口；测试登录多账号构建下停在账号选择，支持一台真机切换角色。 */
function handleLogout() {
  const canSwitchTestAccount = Boolean(testLoginPhone) && testLoginAccounts.length > 1
  uni.showModal({
    title: '退出登录',
    content: '确认退出当前账号？',
    confirmText: '退出',
    success: (result) => {
      if (!result.confirm) {
        return
      }
      if (canSwitchTestAccount) {
        // 显式退出才写切换标记；401 强制登出不写，入口仍自动重登默认号
        uni.setStorageSync(TEST_LOGIN_SWITCH_KEY, '1')
      }
      accountStore.logout()
      uni.reLaunch({ url: '/pages/entry/index' })
    },
  })
}

// ---------------------------------------------------------------------------
// 邀请码（E2E-08 归因）：取码惰性、填码一次性；real 才建立真实关系
// ---------------------------------------------------------------------------
const inviteCode = ref('')
const inviteInput = ref('')
const inviteBusy = ref(false)

async function loadInviteCode() {
  try {
    inviteCode.value = await inviteApi.myCode()
  }
  catch {
    inviteCode.value = ''
  }
}

async function copyInviteCode() {
  if (!inviteCode.value) {
    await loadInviteCode()
  }
  if (inviteCode.value) {
    uni.setClipboardData({ data: inviteCode.value })
  }
}

async function bindInvite() {
  const code = inviteInput.value.trim().toUpperCase()
  if (!code || inviteBusy.value) {
    return
  }
  inviteBusy.value = true
  try {
    await inviteApi.bind(code)
    uni.showToast({ title: '绑定成功', icon: 'success' })
    inviteInput.value = ''
  }
  catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '绑定失败', icon: 'none' })
  }
  finally {
    inviteBusy.value = false
  }
}

onShow(loadInviteCode)
</script>

<template>
  <view class="page-shell top-level-page" :style="{ paddingTop: safeHeader.pageTopPadding }">
    <wd-toast />
    <view v-if="context" class="profile-header" @click="openProfileEdit">
      <view class="profile-avatar">
        <image v-if="avatarSrc" class="profile-avatar-img" :src="avatarSrc" mode="aspectFill" />
        <wd-icon v-else name="user" size="28px" color="var(--app-color-primary)" />
      </view>
      <view class="profile-header-main">
        <view class="profile-name">
          {{ context.userName }}
        </view>
        <view class="muted-text">
          {{ maskedPhone || '未绑定手机号' }}
        </view>
      </view>
      <view class="profile-edit-hint">
        <text>编辑资料</text>
        <wd-icon name="arrow-right" size="14px" color="#9aa0a6" />
      </view>
    </view>

    <!-- 编辑资料：微信资料填写能力（chooseAvatar + nickname 输入）——2022 年底后唯一合规取头像昵称路径 -->
    <view v-if="context && editingProfile" class="page-section">
      <view class="profile-edit-card">
        <view class="profile-edit-row">
          <text class="profile-edit-label">
            头像
          </text>
          <button class="profile-edit-avatar-btn" open-type="chooseAvatar" @chooseavatar="onChooseAvatar">
            <image v-if="editAvatarPreview" class="profile-edit-avatar-img" :src="editAvatarPreview" mode="aspectFill" />
            <view v-else class="profile-edit-avatar-empty">
              <wd-icon name="user" size="24px" color="#9aa0a6" />
            </view>
            <text class="profile-edit-avatar-tip">
              点击更换
            </text>
          </button>
        </view>
        <view class="profile-edit-row">
          <text class="profile-edit-label">
            昵称
          </text>
          <input
            v-model="editName"
            class="profile-edit-input"
            type="nickname"
            :maxlength="50"
            placeholder="输入昵称（键盘可一键填入微信昵称）"
          >
        </view>
        <view class="profile-edit-actions">
          <wd-button size="small" plain :disabled="savingProfile" @click.stop="editingProfile = false">
            取消
          </wd-button>
          <wd-button size="small" type="primary" :loading="savingProfile" @click.stop="saveProfile">
            保存
          </wd-button>
        </view>
      </view>
    </view>

    <!-- 仅微信身份建号的账号：手机号是唯一的人工找回凭据，未绑就把补绑摆在最显眼处 -->
    <view v-if="context && !context.phoneBound" class="page-section">
      <view class="bind-phone-card">
        <view class="bind-phone-title">
          <wd-icon name="phone" size="16px" color="#d97706" />
          <text>绑定手机号</text>
        </view>
        <view class="bind-phone-desc">
          绑定后可用于订单联系与换机找回账号；不绑定不影响当前使用。
        </view>
        <button
          v-if="phoneComponentAvailable"
          class="bind-phone-btn"
          open-type="getPhoneNumber"
          :disabled="bindingPhone"
          @getphonenumber="onGetPhoneNumber"
        >
          {{ bindingPhone ? '绑定中…' : '微信手机号一键绑定' }}
        </button>
        <view v-else class="bind-phone-hint">
          暂未开放一键绑定，请联系运营。
        </view>
      </view>
    </view>

    <view class="page-section">
      <wd-cell-group title="用水账户" border>
        <template v-if="ownCards.length > 1">
          <wd-cell
            v-for="card in ownCards"
            :key="card.cardId"
            :title="card.cardId === primaryCard?.cardId ? '水卡（主卡）' : '水卡'"
            :value="`${CARD_STATUS_LABELS[card.cardStatus]} · ${card.cardNo}`"
            icon="creditcard"
            is-link
            @click="goTo('U11', { cardId: card.cardId })"
          />
        </template>
        <wd-cell
          v-else
          title="水卡"
          :value="primaryCard ? `${CARD_STATUS_LABELS[primaryCard.cardStatus]} · ${primaryCard.cardNo}` : '暂无水卡'"
          icon="creditcard"
          is-link
          @click="handleCardTap"
        />
        <wd-cell title="家庭资料" icon="usergroup" is-link @click="goTo('U13')" />
        <wd-cell title="水配送地址" icon="location" is-link @click="goTo('U14')" />
        <wd-cell title="自动补货规则" icon="refresh" is-link @click="goTo('U16')" />
      </wd-cell-group>
    </view>

    <view class="page-section">
      <wd-cell-group title="我的业务能力" border>
        <wd-cell
          title="配送员"
          :value="admission ? ADMISSION_STATUS_LABELS[admission.status] : '未开通'"
          icon="goods"
          is-link
          @click="goTo('D02')"
        />
        <wd-cell
          v-if="hasOwnerView"
          title="机主授权"
          :value="ownerScopeSummary"
          icon="dashboard"
          is-link
          @click="goTo('O01')"
        />
        <!-- 收益钱包对所有登录用户开放：分账收款方含配送员与预留的推荐人/区域服务商，
             入口若只挂机主概览，拿了分润的配送员将无处查看自己的钱 -->
        <wd-cell
          title="收益钱包"
          value="分润净额"
          icon="money-circle"
          is-link
          @click="goTo('O06')"
        />
      </wd-cell-group>
      <view v-if="!hasOwnerView" class="muted-text owner-hint">
        机主授权暂不支持自助申请，请联系运营。
      </view>
    </view>

    <view class="page-section">
      <wd-card title="邀请好友">
        <view class="invite-line">
          <text>我的邀请码：{{ inviteCode || '—' }}</text>
          <wd-button size="small" plain @click="copyInviteCode">
            复制
          </wd-button>
        </view>
        <view class="invite-line invite-bind">
          <wd-input v-model="inviteInput" placeholder="填写他人邀请码（仅可绑定一次）" no-border custom-class="invite-input" />
          <wd-button size="small" :loading="inviteBusy" @click="bindInvite">
            绑定
          </wd-button>
        </view>
        <view class="muted-text">
          绑定后的新订单计入推荐人，此前订单不回溯。
        </view>
      </wd-card>
    </view>

    <view class="page-section">
      <wd-cell-group title="账号与服务" border>
        <wd-cell title="消息中心" icon="notification" is-link @click="goTo('C02')" />
        <wd-cell title="隐私与授权" icon="secured" is-link @click="showPrivacyNote" />
        <wd-cell title="服务说明" icon="tips" is-link @click="showServiceNote" />
        <wd-cell title="退出登录" icon="logout" is-link @click="handleLogout" />
      </wd-cell-group>
    </view>
  </view>
</template>

<style scoped lang="scss">
.top-level-page {
  padding-top: calc(env(safe-area-inset-top) + 20px);
}

.profile-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.profile-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: rgba(93, 135, 255, 0.12);
  overflow: hidden;
}

.profile-avatar-img {
  width: 52px;
  height: 52px;
  border-radius: 50%;
}

.profile-header-main {
  flex: 1;
  min-width: 0;
}

.profile-edit-hint {
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: #9aa0a6;
}

.profile-edit-card {
  padding: 14px 16px;
  border-radius: 12px;
  background: #ffffff;
  border: 1px solid rgba(93, 135, 255, 0.18);
}

.profile-edit-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px 0;
}

.profile-edit-label {
  width: 44px;
  font-size: 14px;
  color: #646a73;
}

/* open-type 按钮必须去掉微信原生按钮皮：否则头像被包进灰色胶囊里 */
.profile-edit-avatar-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  padding: 0;
  background: transparent;
  border: none;
  line-height: 1;

  &::after {
    border: none;
  }
}

.profile-edit-avatar-img {
  width: 56px;
  height: 56px;
  border-radius: 50%;
}

.profile-edit-avatar-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: rgba(154, 160, 166, 0.12);
}

.profile-edit-avatar-tip {
  font-size: 12px;
  color: var(--app-color-primary);
}

.profile-edit-input {
  flex: 1;
  height: 40px;
  padding: 0 12px;
  border-radius: 8px;
  background: rgba(154, 160, 166, 0.08);
  font-size: 14px;
}

.profile-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 10px;
}

.profile-name {
  font-size: 18px;
  font-weight: 600;
}

.bind-phone-card {
  padding: 14px 16px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.22);
}

.bind-phone-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: #b45309;
}

.bind-phone-desc {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: #92400e;
}

.bind-phone-btn {
  margin-top: 12px;
  height: 40px;
  line-height: 40px;
  border-radius: 20px;
  font-size: 15px;
  color: #ffffff;
  background: linear-gradient(135deg, #f59e0b, #d97706);
  border: none;

  &::after {
    border: none;
  }
}

.bind-phone-hint {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #a16207;
}

.owner-hint {
  margin-top: 8px;
  padding: 0 4px;
}

.invite-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 0;
}

.invite-bind {
  border-top: 1px solid rgba(0, 0, 0, 0.05);
}
</style>
