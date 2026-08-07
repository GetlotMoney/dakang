<!-- 首次登录强制改密页（R-201）：登录响应 pwdChangeRequired=true 时进入。
     服务端拦截器在强改期间拒绝除 登录/退出/改密 外的全部接口，本页是唯一出口；
     改密成功后服务端强制下线，回登录页用新密码登录。 -->
<template>
  <div class="change-password-page">
    <ElCard class="change-password-card" shadow="always">
      <template #header>
        <div class="card-header">
          <h3>修改初始密码</h3>
          <p>未修改前无法使用系统</p>
        </div>
      </template>
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="90px" @submit.prevent>
        <ElFormItem label="初始密码" prop="oldPwd">
          <ElInput
            v-model.trim="formData.oldPwd"
            type="password"
            show-password
            placeholder="请输入初始密码"
            autocomplete="current-password"
          />
        </ElFormItem>
        <ElFormItem label="新密码" prop="newPwd">
          <ElInput
            v-model.trim="formData.newPwd"
            type="password"
            show-password
            placeholder="8~32 位，须同时包含字母和数字"
            autocomplete="new-password"
          />
        </ElFormItem>
        <ElFormItem label="确认新密码" prop="confirmPwd">
          <ElInput
            v-model.trim="formData.confirmPwd"
            type="password"
            show-password
            placeholder="再次输入新密码"
            autocomplete="new-password"
          />
        </ElFormItem>
        <ElFormItem>
          <ElButton type="primary" :loading="loading" @click="handleSubmit">确认修改</ElButton>
          <ElButton @click="handleGiveUp">退出登录</ElButton>
        </ElFormItem>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { reactive, ref, onMounted } from 'vue'
  import { useRouter } from 'vue-router'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
  import JSEncrypt from 'jsencrypt'
  import { HttpError } from '@/utils/http/error'
  import { useUserStore } from '@/store/modules/user'
  import { fetchLoginOut } from '@/api/auth'
  import { fetchUpdateEmployeePassword } from '@/api/system-manage'

  defineOptions({ name: 'ChangePassword' })

  const router = useRouter()
  const userStore = useUserStore()
  const ENCRYPT_KEY = import.meta.env.VITE_ACCESS_LOGIN_KEY

  // 无会话直接回登录页：本页依赖 dakang-token 调改密接口
  onMounted(() => {
    if (!userStore.accessToken) {
      void router.replace('/auth/login')
    }
  })

  const formRef = ref<FormInstance>()
  const loading = ref(false)
  const formData = reactive({
    oldPwd: '',
    newPwd: '',
    confirmPwd: ''
  })

  // 与服务端 PwdUtils.checkStrength 同一底线：8~32 位且同时包含字母和数字
  const STRENGTH_PATTERN = /^(?=.*[A-Za-z])(?=.*\d)[\s\S]{8,32}$/

  const rules: FormRules = {
    oldPwd: [{ required: true, message: '请输入初始密码', trigger: 'blur' }],
    newPwd: [
      { required: true, message: '请输入新密码', trigger: 'blur' },
      {
        validator: (_rule, value: string, callback) => {
          if (!STRENGTH_PATTERN.test(value)) {
            callback(new Error('新密码须为 8~32 位且同时包含字母和数字'))
          } else if (value === formData.oldPwd) {
            callback(new Error('新密码不能与初始密码相同'))
          } else {
            callback()
          }
        },
        trigger: 'blur'
      }
    ],
    confirmPwd: [
      { required: true, message: '请再次输入新密码', trigger: 'blur' },
      {
        validator: (_rule, value: string, callback) => {
          if (value !== formData.newPwd) {
            callback(new Error('两次输入的新密码不一致'))
          } else {
            callback()
          }
        },
        trigger: 'blur'
      }
    ]
  }

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return

    loading.value = true
    try {
      const encryptor = new JSEncrypt()
      encryptor.setPublicKey(ENCRYPT_KEY)
      await fetchUpdateEmployeePassword({
        loginPwd: encryptor.encrypt(formData.oldPwd) as string,
        newLoginPwd: encryptor.encrypt(formData.newPwd) as string
      })
      ElMessage.success('密码修改成功，请使用新密码重新登录')
      // 服务端已强制下线，本地同步清理并回登录页
      userStore.logOut()
    } catch (error) {
      // HttpError（原密码错误/强度不足等）由 http 拦截器统一提示
      if (!(error instanceof HttpError)) {
        ElMessage.error('修改失败，请稍后重试')
        console.error('[ChangePassword] Unexpected error:', error)
      }
    } finally {
      loading.value = false
    }
  }

  // 放弃改密同样要销毁服务端会话：强改门保留了 loginOut 端点正是为此，
  // 只清本地会让带强改标记的会话存活到超时
  const handleGiveUp = async () => {
    await fetchLoginOut().catch(() => {})
    userStore.logOut()
  }
</script>

<style scoped lang="scss">
  .change-password-page {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    min-height: 100vh;
    background: var(--art-main-bg-color, #f5f7fa);
  }

  .change-password-card {
    width: 460px;
    max-width: calc(100vw - 32px);

    .card-header {
      h3 {
        margin: 0 0 6px;
        font-size: 18px;
      }

      p {
        margin: 0;
        font-size: 13px;
        color: var(--el-text-color-secondary);
      }
    }
  }
</style>
