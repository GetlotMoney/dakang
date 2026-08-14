/**
 * 用户状态管理：登录态、用户信息、语言、搜索历史、锁屏；localStorage 持久化，登出时清理。
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { LanguageEnum } from '@/enums/appEnum'
import { router } from '@/router'
import { useSettingStore } from './setting'
import { AppRouteRecord } from '@/types/router'
import { setPageTitle } from '@/utils/router'
import { resetRouterState } from '@/router/guards/beforeEach'
import { useMenuStore } from './menu'
import { DEMO_SESSION_SCHEMA_VERSION } from '@/config/businessNavigation'

export const useUserStore = defineStore(
  'userStore',
  () => {
    const language = ref(LanguageEnum.ZH)
    const isLogin = ref(false)
    const isLock = ref(false)
    const lockPassword = ref('')
    const info = ref<Partial<Api.Auth.UserInfo>>({})
    const searchHistory = ref<AppRouteRecord[]>([])
    // 路由菜单列表（登录后从后端返回）
    const rbacMenuList = ref<Api.Auth.RbacMenuItem[]>([])
    // 会话 token（登录响应 tokenValue，经 dakang-token 请求头回传后端 Sa-Token）
    const accessToken = ref('')
    // 登录态对应的导航契约版本；旧持久化数据没有该字段，启动时会自动失效。
    const sessionSchemaVersion = ref('')

    const getUserInfo = computed(() => info.value)
    const getSettingState = computed(() => useSettingStore().$state)

    const setUserInfo = (newInfo: Api.Auth.UserInfo) => {
      info.value = newInfo
      // 登录响应中带有菜单数据，同步存入 rbacMenuList
      if (newInfo.rbacMenuList?.length) {
        rbacMenuList.value = newInfo.rbacMenuList
      }
    }

    const setLoginStatus = (status: boolean) => {
      isLogin.value = status
      if (status) {
        sessionSchemaVersion.value = DEMO_SESSION_SCHEMA_VERSION
      }
    }

    /** 设置会话 token（登录成功后调用；http 拦截器读取并注入 dakang-token 头）。 */
    const setToken = (token: string) => {
      accessToken.value = token
    }

    /**
     * 校验从 localStorage 恢复的登录态是否仍符合当前导航契约。
     * 端口不同会产生不同 origin，不能依赖用户分别清理 13321/8081 的缓存。
     */
    const ensureSessionCompatibility = (): boolean => {
      if (!isLogin.value) return true

      // 待强改密码会话豁免菜单检查（R-201）：未分配角色时菜单必为空，按异常缓存清登录态会让该账号永远无法完成首改
      const pwdChangePending = Boolean((info.value as Api.Auth.UserInfo)?.pwdChangeRequired)

      const isCompatible =
        sessionSchemaVersion.value === DEMO_SESSION_SCHEMA_VERSION &&
        Boolean(accessToken.value) &&
        (pwdChangePending || rbacMenuList.value.length > 0)

      if (isCompatible) return true

      console.warn('[Session] 检测到旧版或异常菜单缓存，已清理登录态并要求重新登录')
      info.value = {}
      accessToken.value = ''
      isLogin.value = false
      isLock.value = false
      lockPassword.value = ''
      rbacMenuList.value = []
      searchHistory.value = []
      sessionSchemaVersion.value = DEMO_SESSION_SCHEMA_VERSION
      sessionStorage.removeItem('iframeRoutes')
      useMenuStore().setHomePath('')
      return false
    }

    const setLanguage = (lang: LanguageEnum) => {
      setPageTitle(router.currentRoute.value)
      language.value = lang
    }

    const setSearchHistory = (list: AppRouteRecord[]) => {
      searchHistory.value = list
    }

    const setLockStatus = (status: boolean) => {
      isLock.value = status
    }

    const setLockPassword = (password: string) => {
      lockPassword.value = password
    }

    /** 退出登录：清空用户相关状态并跳转登录页。 */
    const logOut = () => {
      info.value = {}
      accessToken.value = ''
      isLogin.value = false
      // 保留当前契约标识，避免已登出状态被重复判定为旧会话。
      sessionSchemaVersion.value = DEMO_SESSION_SCHEMA_VERSION
      isLock.value = false
      lockPassword.value = ''
      rbacMenuList.value = []
      sessionStorage.removeItem('iframeRoutes')
      useMenuStore().setHomePath('')
      resetRouterState(500)
      // /auth/* 不作为 redirect 回跳目标：从改密页登出若记 redirect，新密码登录后会被送回改密表单
      const currentRoute = router.currentRoute.value
      const redirect = currentRoute.path.startsWith('/auth/') ? undefined : currentRoute.fullPath
      router.push({
        name: 'Login',
        query: redirect ? { redirect } : undefined
      })
    }

    return {
      language,
      isLogin,
      isLock,
      lockPassword,
      info,
      searchHistory,
      rbacMenuList,
      accessToken,
      sessionSchemaVersion,
      getUserInfo,
      getSettingState,
      setUserInfo,
      setLoginStatus,
      setToken,
      ensureSessionCompatibility,
      setLanguage,
      setSearchHistory,
      setLockStatus,
      setLockPassword,
      logOut
    }
  },
  {
    persist: {
      key: 'user',
      storage: localStorage
    }
  }
)
