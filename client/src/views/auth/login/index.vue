<!-- 登录页面 -->
<template>
  <div class="flex w-full h-screen">
    <LoginLeftView />

    <div class="relative flex-1">
      <AuthTopBar />

      <div class="auth-right-wrap">
        <div class="form">
          <h3 class="title">{{ $t('login.title') }}</h3>
          <p class="sub-title">
            {{
              demoLoginAutofill
                ? '本地 Demo 账号密码已自动填充，完成滑块验证即可登录'
                : $t('login.subTitle')
            }}
          </p>
          <ElForm
            ref="formRef"
            :model="formData"
            :rules="rules"
            :key="formKey"
            @keyup.enter="handleSubmit"
            style="margin-top: 25px"
          >
            <ElFormItem prop="username">
              <ElInput
                class="custom-height"
                :placeholder="$t('login.placeholder.username')"
                v-model.trim="formData.username"
              />
            </ElFormItem>
            <ElFormItem prop="password">
              <ElInput
                class="custom-height"
                :placeholder="$t('login.placeholder.password')"
                v-model.trim="formData.password"
                type="password"
                autocomplete="off"
                show-password
              />
            </ElFormItem>

            <!-- 推拽验证 -->
            <div class="relative pb-5 mt-6">
              <div
                class="relative z-[2] overflow-hidden select-none rounded-lg border border-transparent tad-300"
                :class="{ '!border-[#FF4E4F]': !isPassing && isClickPass }"
              >
                <ArtDragVerify
                  ref="dragVerify"
                  v-model:value="isPassing"
                  :text="$t('login.sliderText')"
                  textColor="var(--art-gray-700)"
                  :successText="$t('login.sliderSuccessText')"
                  progressBarBg="var(--main-color)"
                  :background="isDark ? '#26272F' : '#F1F1F4'"
                  handlerBg="var(--default-box-color)"
                />
              </div>
              <p
                class="absolute top-0 z-[1] px-px mt-2 text-xs text-[#f56c6c] tad-300"
                :class="{ 'translate-y-10': !isPassing && isClickPass }"
              >
                {{ $t('login.placeholder.slider') }}
              </p>
            </div>

            <div v-if="!demoLoginAutofill" style="margin-top: 30px">
              <!-- eslint-disable-next-line vue/no-unregistered-directives -->
              <ElButton
                class="w-full custom-height"
                type="primary"
                @click="handleSubmit"
                :loading="loading"
                v-ripple
              >
                {{ $t('login.btnText') }}
              </ElButton>
            </div>
          </ElForm>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { ElMessage, ElNotification } from 'element-plus'
  import JSEncrypt from 'jsencrypt'
  import { HttpError } from '@/utils/http/error'
  import { type FormInstance, type FormRules } from 'element-plus'
  import { useSettingStore } from '@/store/modules/setting'
  import { useUserStore } from '@/store/modules/user'
  import { fetchLogin } from '@/api/auth'
  import { resetRouterState } from '@/router/guards/beforeEach'

  defineOptions({ name: 'Login' })

  const settingStore = useSettingStore()
  const userStore = useUserStore()
  const { isDark } = storeToRefs(settingStore)
  const { t, locale } = useI18n()
  const formKey = ref(0)

  const ENCRYPT_KEY = import.meta.env.VITE_ACCESS_LOGIN_KEY

  // 监听语言切换，重置表单
  watch(locale, () => {
    formKey.value++
  })

  const dragVerify = ref()

  const router = useRouter()
  const isPassing = ref(false)
  const isClickPass = ref(false)

  const formRef = ref<FormInstance>()

  // 仅本地 Demo 构建注入便捷账号；普通生产构建保持空表单，且两种模式都必须通过滑块和后端鉴权。
  const demoLoginAutofillRequested =
    import.meta.env.MODE === 'demo' && import.meta.env.VITE_DEMO_LOGIN_AUTOFILL === 'true'
  const demoUsername = demoLoginAutofillRequested
    ? import.meta.env.VITE_DEMO_LOGIN_USERNAME?.trim()
    : ''
  const demoPassword = demoLoginAutofillRequested ? import.meta.env.VITE_DEMO_LOGIN_PASSWORD : ''
  // 配置不完整时退回标准登录表单，避免隐藏输入框后形成不可恢复的登录死路。
  const demoLoginAutofill = Boolean(demoUsername && demoPassword)

  const formData = reactive({
    username: demoUsername || '',
    password: demoPassword || ''
  })

  const rules = computed<FormRules>(() => ({
    username: [{ required: true, message: t('login.placeholder.username'), trigger: 'blur' }],
    password: [{ required: true, message: t('login.placeholder.password'), trigger: 'blur' }]
  }))

  const loading = ref(false)

  // 登录
  const handleSubmit = async () => {
    if (!formRef.value) return

    // 表单校验独立于登录流程：ElForm.validate() 校验失败会 reject，这里显式吞掉
    // 只保留字段级错误提示，不落入下方 catch 弹出全局“登录失败”toast，也不发起请求。
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return

    // 拖拽验证
    if (!isPassing.value) {
      isClickPass.value = true
      return
    }

    loading.value = true

    try {
      // 登录请求
      const { username, password } = formData

      const encryptor = new JSEncrypt()
      encryptor.setPublicKey(ENCRYPT_KEY)
      const loginData = await fetchLogin({
        loginName: username,
        loginPwd: encryptor.encrypt(password) as string
      })

      // 重置路由状态 确保能动态注册路由
      resetRouterState()

      // 存储会话 token（http 拦截器注入 dakang-token 头）
      userStore.setToken(loginData.tokenValue || '')
      // 存储用户信息（包含 rbacMenuList）
      userStore.setUserInfo(loginData as Api.Auth.UserInfo)
      // 存储登录状态
      userStore.setLoginStatus(true)

      // 登录成功处理
      showLoginSuccessNotice()

      // 恢复用户登录前的站内目标；拒绝协议相对地址，避免开放重定向。
      const redirect = router.currentRoute.value.query.redirect
      const safeRedirect =
        typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
          ? redirect
          : '/dashboard/console'
      void router.replace(safeRedirect)
    } catch (error) {
      // 处理 HttpError（账号密码错误等服务端失败由 http 拦截器统一提示）
      if (error instanceof HttpError) {
        // console.log(error.code)
      } else {
        // 处理非 HttpError（真实网络/运行异常）
        ElMessage.error('登录失败，请稍后重试')
        console.error('[Login] Unexpected error:', error)
      }
    } finally {
      loading.value = false
      resetDragVerify()
    }
  }

  // 本地 Demo 只需完成滑块；通过后仍执行同一 RSA 加密与真实后端登录流程。
  watch(isPassing, (passed) => {
    if (demoLoginAutofill && passed && !loading.value) {
      void handleSubmit()
    }
  })

  // 重置拖拽验证
  const resetDragVerify = () => {
    dragVerify.value.reset()
  }

  // 登录成功提示
  const showLoginSuccessNotice = () => {
    setTimeout(() => {
      ElNotification({
        title: t('login.success.title'),
        type: 'success',
        duration: 2500,
        zIndex: 10000,
        message: `${t('login.success.message')}, ${userStore.getUserInfo?.employeeName}!`
      })
    }, 1000)
  }
</script>

<style scoped>
  @import './style.css';
</style>
