<script setup lang="ts">
import type { CardSummary, UsableCard } from '@/api/card'
import type { CourierAdmission } from '@/api/delivery'
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { deliveryApi } from '@/api/delivery'
import { inviteApi } from '@/api/invite'
import { authApi, readPhoneAuthorization } from '@/api/auth'
import { ContractError } from '@/api/common'
import { avatarMimeFromPath, profileApi, readFileAsBase64 } from '@/api/profile'
import { resolveServerPath } from '@/api/request'
import { isPhoneComponentAvailable } from '@/api/runtime'
import WechatContactEntry from '@/components/wechat-contact-entry.vue'
import { useAccountStore } from '@/store/account'
import { ADMISSION_STATUS_LABELS, CARD_STATUS_LABELS, maskPhone } from '@/utils/format'
import { goTo, reLaunchTo } from '@/utils/navigation'
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
const primaryCard = ref<CardSummary | null>(null)
const ownCards = ref<UsableCard[]>([])
const admission = ref<CourierAdmission | null>(null)

/** 资产行顺序：主卡永远第一。纯展示排序，不改任何归属或权限判定。 */
const assetCards = computed(() => {
  const cards = ownCards.value ?? []
  const main = primaryCard.value?.cardId
  return [...cards].sort((a, b) => (a.cardId === main ? -1 : b.cardId === main ? 1 : 0))
})

const maskedPhone = computed(() => maskPhone(context.value?.userPhone))
const avatarSrc = computed(() => resolveServerPath(context.value?.userAvatar))

// ---------- 编辑资料（头像/昵称，微信「资料填写能力」动线） ----------
const editingProfile = ref(false)
const editName = ref('')
/** 已选待上传的头像临时文件；保存时才读 base64 上传，取消即弃。 */
const pickedAvatar = ref<{ path: string, mime: string } | null>(null)
const savingProfile = ref(false)

const editAvatarPreview = computed(() => pickedAvatar.value?.path || avatarSrc.value)

function _openProfileEdit() {
  editName.value = context.value?.userName ?? ''
  pickedAvatar.value = null
  editingProfile.value = true
}

/** chooseAvatar 回调。未配置《用户隐私保护指引》时平台可能不回传 avatarUrl，必须给出可执行的指引。 */
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

/** 组件不可用时只显示说明、不渲染按钮：平台在组件层拦住 getPhoneNumber 时点击无弹窗无回调。 */
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

/** 打开平台《用户隐私保护指引》全文（微信渲染，非我方页面）；与登录页 openPrivacyContract 同一平台能力。 */
function openPrivacyContract() {
  const wxApi = (globalThis as Record<string, any>).wx
  if (typeof wxApi?.openPrivacyContract !== 'function') {
    toast.show('当前环境无法打开隐私说明')
    return
  }
  wxApi.openPrivacyContract({
    fail: () => toast.show('隐私说明打开失败，请重试'),
  })
}

/** 退出登录：清会话回统一入口（运营规范 12.6 要求提供登出）。 */
function handleLogout() {
  uni.showModal({
    title: '退出登录',
    content: '确认退出当前账号？',
    confirmText: '退出',
    success: (result) => {
      if (!result.confirm) {
        return
      }
      accountStore.logout()
      reLaunchTo('C01')
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
    return
  }
  // 取码失败时 inviteCode 恒为空串，必须提示而不是静默 return
  toast.show('邀请码获取失败，请稍后重试')
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
    <image class="profile-watermark" src="/static/brand/page-watermark.jpg" mode="scaleToFill" />
    <wd-toast />
    <view v-if="context" class="profile-header profile-hero pressable" @click="goTo('I01')">
      <image class="profile-hero__art" src="/static/brand/section-network.jpg" mode="aspectFill" />
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
        <text>身份中心</text>
        <wd-icon name="arrow-right" size="14px" color="var(--app-text-tertiary)" />
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
              <wd-icon name="user" size="24px" color="var(--app-text-tertiary)" />
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
            placeholder="昵称"
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
          <wd-icon name="phone" size="16px" color="var(--app-color-warning)" />
          <text>绑定手机号</text>
        </view>
        <view class="bind-phone-desc">
          用于订单联系与换机找回账号
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

    <view class="page-section identity-entry surface-card pressable" @click="goTo('I01')">
      <view>
        <view class="identity-entry__title">
          身份与能力
        </view>
        <view class="muted-text">
          申请并管理配送员、机主、渠道和区域代理
        </view>
      </view>
      <text class="identity-entry__arrow">
        ›
      </text>
    </view>

    <view class="page-section">
      <view class="section-title-row">
        <text class="section-title">
          我的水卡
        </text>
      </view>

      <!-- 卡是资产，与设置类入口分开展示；主卡排第一 -->
      <view class="asset-list">
        <view
          v-for="card in assetCards"
          :key="card.cardId"
          class="asset-row pressable"
          :class="{ 'asset-row--muted': card.cardStatus !== 1 }"
          @click="goTo('U11', { cardId: card.cardId })"
        >
          <image class="asset-row__art" src="/static/brand/card-ripples-wide.jpg" mode="aspectFill" />
          <view class="asset-row__main">
            <text class="asset-row__title">
              {{ card.cardId === primaryCard?.cardId ? '水卡（主卡）' : '水卡' }}
            </text>
            <text class="asset-row__no num">
              {{ card.cardNo }}
            </text>
          </view>
          <text class="asset-row__status" :class="{ 'asset-row__status--alert': card.cardStatus !== 1 }">
            {{ CARD_STATUS_LABELS[card.cardStatus] }}
          </text>
          <view class="asset-row__enter">
            <wd-icon name="arrow-right" size="14px" color="var(--app-text-inverse)" />
          </view>
        </view>
        <view v-if="!assetCards.length" class="asset-row asset-row--empty pressable" @click="handleCardTap">
          <wd-icon name="creditcard" size="22px" color="var(--app-text-tertiary)" />
          <view class="asset-row__main">
            <text class="asset-row__title">
              暂无水卡
            </text>
            <text class="asset-row__no">
              去开卡
            </text>
          </view>
          <wd-icon name="arrow-right" size="14px" color="var(--app-text-tertiary)" />
        </view>
      </view>
    </view>

    <view class="page-section">
      <view class="section-title-row">
        <text class="section-title">
          用水设置
        </text>
      </view>
      <wd-cell-group border>
        <wd-cell title="家庭资料" icon="usergroup" is-link @click="goTo('U13')" />
        <wd-cell title="水配送地址" icon="location" is-link @click="goTo('U14')" />
        <wd-cell title="自动补货规则" icon="refresh" is-link @click="goTo('U16')" />
      </wd-cell-group>
    </view>

    <view class="page-section">
      <view class="section-title-row">
        <text class="section-title">
          我的服务
        </text>
      </view>
      <wd-cell-group border>
        <wd-cell
          title="身份与能力"
          value="申请与管理经营身份"
          icon="user-talk"
          is-link
          @click="goTo('I01')"
        />
        <!-- 无记录兜底文案必须与 status=0 同字：查询失败也降级成无记录，不得显示成「未开通」 -->
        <wd-cell
          title="配送员"
          :value="ADMISSION_STATUS_LABELS[admission ? admission.status : 0]"
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
          ellipsis
          @click="goTo('O01')"
        />
        <!-- 收益钱包对所有登录用户开放：分账收款方含配送员，入口不能只挂机主概览 -->
        <wd-cell
          title="收益钱包"
          icon="money-circle"
          is-link
          @click="goTo('O06')"
        />
      </wd-cell-group>
    </view>

    <!-- 客服入口：只带页面路径，不带任何身份或凭据；未开通客服时组件自己降级为普通联系方式 -->
    <view class="page-section">
      <view class="section-title-row">
        <text class="section-title">
          帮助与客服
        </text>
      </view>
      <view class="contact-row">
        <WechatContactEntry scene="账号与订单咨询" page-path="/pages/user/profile/index" />
      </view>
    </view>

    <view class="page-section">
      <view class="section-title-row">
        <text class="section-title">
          邀请好友
        </text>
      </view>
      <view class="invite-block">
        <view class="invite-line">
          <view class="invite-code">
            <text class="invite-code__text">
              我的邀请码：{{ inviteCode || '—' }}
            </text>
          </view>
          <wd-button size="small" plain @click="copyInviteCode">
            复制
          </wd-button>
        </view>
        <!-- 「仅可绑定一次」不放 placeholder：一开始输入就消失，不可撤销的约束必须常显 -->
        <view class="invite-line invite-bind">
          <view class="invite-field">
            <wd-input v-model="inviteInput" placeholder="邀请码（仅可绑定一次）" no-border />
          </view>
          <wd-button size="small" :loading="inviteBusy" @click="bindInvite">
            绑定
          </wd-button>
        </view>
        <view class="invite-line invite-bind pressable" @click="goTo('I01')">
          <view>
            <view class="identity-entry__title">
              身份与能力
            </view>
            <view class="muted-text">
              申请配送员、机主、渠道和区域代理
            </view>
          </view>
          <text class="identity-entry__arrow">
            ›
          </text>
        </view>
      </view>
    </view>

    <view class="page-section">
      <view class="section-title-row">
        <text class="section-title">
          账号
        </text>
      </view>
      <wd-cell-group border>
        <wd-cell title="消息中心" icon="notification" is-link @click="goTo('C02')" />
        <wd-cell title="隐私与授权" icon="secured" is-link @click="openPrivacyContract" />
      </wd-cell-group>
    </view>

    <!-- 退出登录单独落在页尾：与查看类入口不是一类动作，避免顺手点到 -->
    <view class="page-section logout" @click="handleLogout">
      <text class="logout__text">
        退出登录
      </text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.asset-list {
  display: grid;
  gap: var(--sp-3);
}

.asset-row {
  position: relative;
  display: flex;
  overflow: hidden;
  gap: var(--sp-3);
  align-items: center;
  min-height: 72px;
  padding: var(--sp-4);
  border-radius: var(--r-md);
  color: var(--app-text-inverse);
  background: var(--art-card-navy);

  &--muted {
    opacity: 0.72;
  }

  &--empty {
    color: var(--app-text-primary);
    background: var(--app-bg-card);
  }

  &__art {
    position: absolute;
    z-index: 0;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }

  &__main {
    position: relative;
    z-index: 1;
    flex: 1;
    min-width: 0;
  }

  &__title {
    display: block;
    font-size: var(--fs-body);
    font-weight: 500;
  }

  // 卡号是查证用的，降到 note 档
  &__no {
    display: block;
    margin-top: 2px;
    overflow: hidden;
    color: currentColor;
    font-size: var(--fs-note);
    white-space: nowrap;
    text-overflow: ellipsis;
    opacity: 0.68;
  }

  &__status {
    position: relative;
    z-index: 1;
    flex: none;
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

  &__enter {
    position: relative;
    z-index: 1;
    display: flex;
    align-items: center;
  }
}

// 邀请是低频动作，脱掉白卡外壳降权；输入与按钮同排且各自留够宽度
.invite-block {
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-md);
  background: var(--app-bg-card);
}

// 平面身份区：不套卡、不描边、不投影。顶部多留一段把整行压到微信胶囊下方
// （navigationStyle=custom，320 屏上避让胶囊会把昵称压成半截）
.profile-hero {
  position: relative;
  box-sizing: border-box;
  overflow: hidden;
  height: 116px;
  margin: var(--sp-2) -16px var(--gap-group);
  padding: 44px 16px 16px;

  &__art {
    position: absolute;
    z-index: 0;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }

  // 昵称长度上限是本页自己给的 50（编辑框 maxlength），不截断必然把手机号挤下去
  .profile-name {
    overflow: hidden;
    color: var(--app-text-primary);
    font-size: var(--fs-title);
    font-weight: 700;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .muted-text {
    overflow: hidden;
    color: var(--app-text-secondary);
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .profile-edit-hint {
    color: var(--app-color-primary);
  }
}

.top-level-page {
  position: relative;
  overflow: hidden;
  padding-top: calc(env(safe-area-inset-top) + 20px);
}

.profile-watermark {
  position: absolute;
  z-index: 0;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.profile-hero,
.page-section,
.logout {
  position: relative;
  z-index: 1;
}

.profile-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.profile-avatar {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: var(--tint-primary-strong);
  overflow: hidden;
}

.profile-avatar-img {
  width: 52px;
  height: 52px;
  border-radius: 50%;
}

.profile-header-main {
  position: relative;
  z-index: 1;
  flex: 1;
  min-width: 0;
}

.profile-edit-hint {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: var(--app-text-tertiary);
}

// 编辑态是临时展开的表单面，白底即可标明它是一块；不再用品牌色描边围一圈
.profile-edit-card {
  padding: var(--sp-4);
  border-radius: var(--r-md);
  background: var(--app-bg-card);
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
  color: var(--app-text-secondary);
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
  background: var(--tint-neutral);
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
  background: var(--tint-neutral);
  font-size: 14px;
}

.profile-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 10px;
}

// 未绑号提示用 warning 浅底承担语义，不再围同色描边
.bind-phone-card {
  padding: var(--sp-4);
  border-radius: var(--r-md);
  background: var(--tint-warning);
}

.bind-phone-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: var(--app-color-warning-text);
}

.bind-phone-desc {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--app-color-warning-text);
}

.bind-phone-btn {
  margin-top: var(--sp-3);
  height: 40px;
  line-height: 40px;
  border-radius: var(--r-pill);
  font-size: 15px;
  color: var(--app-text-inverse);
  background: var(--app-color-warning);
  border: none;

  &::after {
    border: none;
  }
}

.bind-phone-hint {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-color-warning-text);
}

.group-hint {
  margin-top: var(--sp-2);
  padding: 0 var(--sp-1);
  color: var(--app-text-tertiary);
  font-size: var(--fs-caption);
}

// 退出登录：单独一行、危险语义、页尾。不与查看类入口同组，避免顺手点到。
.logout {
  padding: var(--sp-4);
  border-radius: var(--r-md);
  background: var(--app-bg-card);
  text-align: center;

  &__text {
    color: var(--app-color-danger);
    font-size: var(--fs-body);
  }
}

.invite-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  padding: var(--sp-2) 0;
}

// 邀请码长度由服务端决定，前端不设上限：不截断的话长码会把「复制」挤出屏
.invite-code {
  flex: 1;
  min-width: 0;

  &__text {
    display: block;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
}

.invite-bind {
  border-top: 1px solid var(--line-1);
}

.invite-field {
  flex: 1;
  min-width: 0;
}

.identity-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);

  &__title {
    margin-bottom: var(--sp-1);
    font-size: var(--fs-title);
    font-weight: 700;
  }

  &__arrow {
    color: var(--app-color-primary);
    font-size: var(--fs-metric);
  }
}
</style>
