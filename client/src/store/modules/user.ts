/**
 * 用户状态管理模块
 *
 * 提供用户相关的状态管理
 *
 * ## 主要功能
 *
 * - 用户登录状态管理
 * - 用户信息存储
 * - 语言设置
 * - 搜索历史记录
 * - 锁屏状态和密码管理
 * - 登出清理逻辑
 *
 * ## 使用场景
 *
 * - 用户登录和认证
 * - 权限验证
 * - 个人信息展示
 * - 多语言切换
 * - 锁屏功能
 * - 搜索历史管理
 *
 * ## 持久化
 *
 * - 使用 localStorage 存储
 * - 存储键：sys-v{version}-user
 * - 登出时自动清理
 *
 * @module store/modules/user
 * @author Art Design Pro Team
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

/**
 * 用户状态管理
 * 管理用户登录状态、个人信息、语言设置、搜索历史、锁屏状态等
 */
export const useUserStore = defineStore(
  'userStore',
  () => {
    // 语言设置
    const language = ref(LanguageEnum.ZH)
    // 登录状态
    const isLogin = ref(false)
    // 锁屏状态
    const isLock = ref(false)
    // 锁屏密码
    const lockPassword = ref('')
    // 用户信息
    const info = ref<Partial<Api.Auth.UserInfo>>({})
    // 搜索历史记录
    const searchHistory = ref<AppRouteRecord[]>([])
    // 路由菜单列表（登录后从后端返回）
    const rbacMenuList = ref<Api.Auth.RbacMenuItem[]>([])
    // 会话 token（登录响应 tokenValue，经 dakang-token 请求头回传后端 Sa-Token）
    const accessToken = ref('')
    // 登录态对应的导航契约版本；旧持久化数据没有该字段，启动时会自动失效。
    const sessionSchemaVersion = ref('')

    // 计算属性：获取用户信息
    const getUserInfo = computed(() => info.value)
    // 计算属性：获取设置状态
    const getSettingState = computed(() => useSettingStore().$state)

    /**
     * 设置用户信息
     * @param newInfo 新的用户信息
     */
    const setUserInfo = (newInfo: Api.Auth.UserInfo) => {
      info.value = newInfo
      // 登录响应中带有菜单数据，同步存入 rbacMenuList
      if (newInfo.rbacMenuList?.length) {
        rbacMenuList.value = newInfo.rbacMenuList
      }
    }

    /**
     * 设置登录状态
     * @param status 登录状态
     */
    const setLoginStatus = (status: boolean) => {
      isLogin.value = status
      if (status) {
        sessionSchemaVersion.value = DEMO_SESSION_SCHEMA_VERSION
      }
    }

    /**
     * 设置会话 token（登录成功后调用；http 拦截器读取并注入 dakang-token 头）
     * @param token 后端下发的 tokenValue
     */
    const setToken = (token: string) => {
      accessToken.value = token
    }

    /**
     * 校验从 localStorage 恢复的登录态是否仍符合当前导航契约。
     * 端口不同会产生不同 origin，不能依赖用户分别清理 13321/8081 的缓存。
     */
    const ensureSessionCompatibility = (): boolean => {
      if (!isLogin.value) return true

      // 待强改密码的会话豁免菜单检查：新建员工尚未分配角色时菜单必然为空，
      // 若按"异常缓存"清掉登录态，改密页会因失去 token 立即弹回登录页——
      // 而服务端在改密前拒绝一切业务接口，该账号将永远无法完成首改（R-201）
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

    /**
     * 设置语言
     * @param lang 语言枚举值
     */
    const setLanguage = (lang: LanguageEnum) => {
      setPageTitle(router.currentRoute.value)
      language.value = lang
    }

    /**
     * 设置搜索历史
     * @param list 搜索历史列表
     */
    const setSearchHistory = (list: AppRouteRecord[]) => {
      searchHistory.value = list
    }

    /**
     * 设置锁屏状态
     * @param status 锁屏状态
     */
    const setLockStatus = (status: boolean) => {
      isLock.value = status
    }

    /**
     * 设置锁屏密码
     * @param password 锁屏密码
     */
    const setLockPassword = (password: string) => {
      lockPassword.value = password
    }

    /**
     * 退出登录
     * 清空所有用户相关状态并跳转到登录页
     */
    const logOut = () => {
      // 清空用户信息
      info.value = {}
      // 清空会话 token
      accessToken.value = ''
      // 重置登录状态
      isLogin.value = false
      // 保留当前契约标识，避免已登出状态被重复判定为旧会话。
      sessionSchemaVersion.value = DEMO_SESSION_SCHEMA_VERSION
      // 重置锁屏状态
      isLock.value = false
      // 清空锁屏密码
      lockPassword.value = ''
      // 清空路由菜单列表
      rbacMenuList.value = []
      // 移除iframe路由缓存
      sessionStorage.removeItem('iframeRoutes')
      // 清空主页路径
      useMenuStore().setHomePath('')
      // 重置路由状态
      resetRouterState(500)
      // 跳转到登录页，携带当前路由作为 redirect 参数。
      // 认证页自身不作为回跳目标：登录路由实为 /auth/login（原先比对 '/login' 永不命中），
      // 而从改密页登出若把 /auth/change-password 记为 redirect，用新密码登录后会被直接
      // 送回改密表单，用户会以为改密没生效
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
