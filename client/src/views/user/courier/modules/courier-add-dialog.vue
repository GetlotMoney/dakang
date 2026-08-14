<!-- 人工创建配送员（REQ-079"后台至少支持人工创建/审核配送员"，创建即待审核） -->
<template>
  <ElDialog v-model="dialogVisible" title="新增配送员" width="560px" align-center>
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="90px">
      <ElFormItem label="关联用户" prop="userId">
        <ElSelect
          v-model="formData.userId"
          placeholder="按姓名/手机号搜索用户"
          filterable
          remote
          :remote-method="searchUser"
          :loading="userLoading"
        >
          <ElOption
            v-for="user in userOptions"
            :key="user.id"
            :label="`${user.userName}（${user.userPhone}）`"
            :value="user.id"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="姓名" prop="courierName">
        <ElInput
          v-model="formData.courierName"
          placeholder="请输入配送员姓名"
          maxlength="50"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="联系电话" prop="courierPhone">
        <ElInput
          v-model="formData.courierPhone"
          placeholder="请输入完整 11 位手机号"
          maxlength="11"
        />
      </ElFormItem>
      <ElFormItem label="身份证号" prop="idCardNo">
        <ElInput v-model="formData.idCardNo" placeholder="选填，用于实名资质留档" maxlength="30" />
      </ElFormItem>
      <ElFormItem label="服务水站" prop="stationIdList">
        <ElSelect
          v-model="formData.stationIdList"
          placeholder="不选则不可接单"
          multiple
          clearable
          collapse-tags
        >
          <ElOption
            v-for="station in stationOptions"
            :key="station.id"
            :label="station.stationName"
            :value="station.id"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="服务区域" prop="serviceRegion">
        <ElInput
          v-model="formData.serviceRegion"
          placeholder="选填，如 武汉东湖高新区"
          maxlength="100"
        />
      </ElFormItem>
      <ElAlert type="info" :closable="false" show-icon title="创建后需审核通过才能接单" />
    </ElForm>
    <template #footer>
      <div class="dialog-footer">
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitLoading" @click="handleSubmit">提交</ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage } from 'element-plus'
  import { fetchAddCourier, fetchWsUserPage, type WsUserItem } from '@/api/user'
  import { fetchStationList, type StationItem } from '@/api/station'

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'submit'): void
  }

  const props = defineProps<{ visible: boolean }>()
  const emit = defineEmits<Emits>()

  const dialogVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const formRef = ref<FormInstance>()
  const submitLoading = ref(false)

  const formData = reactive({
    // 身份类 Long ID 恒为 string：数值化会在超过 2^53 时静默改人
    userId: undefined as string | undefined,
    courierName: '',
    courierPhone: '',
    idCardNo: '',
    stationIdList: [] as number[],
    serviceRegion: ''
  })

  /** 中国大陆手机号。派单/异常联系全靠这一列，位数不对等于没有联系方式。 */
  const MOBILE_PHONE = /^1[3-9]\d{9}$/

  /**
   * 联系电话格式校验。
   *
   * 「关联用户」下拉的标签用的是列表下发的脱敏号（139****1111），运营照着上面读、
   * 敲进这个框是最自然的动作；而脱敏串恰好也是 11 位，肉眼与真号码无从区分。
   * 后端 WsCourierBo 只有非空与长度约束，不写这条校验，脱敏号会一路存进配送员档案，
   * 直到配送出问题联系不上人才被发现。故格式在提交前就拒，并明说要填什么。
   */
  const validateCourierPhone = (
    _rule: unknown,
    value: unknown,
    callback: (error?: Error) => void
  ) => {
    const phone = String(value ?? '').trim()
    if (!phone) {
      return callback(new Error('请输入联系电话'))
    }
    if (phone.includes('*')) {
      return callback(new Error('请输入完整手机号，不能填带 * 的号码'))
    }
    if (!MOBILE_PHONE.test(phone)) {
      return callback(new Error('请输入完整 11 位手机号'))
    }
    callback()
  }

  const rules: FormRules = {
    userId: [{ required: true, message: '请选择关联用户', trigger: 'change' }],
    courierName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
    courierPhone: [
      { required: true, message: '请输入联系电话', trigger: 'blur' },
      { validator: validateCourierPhone, trigger: 'blur' }
    ]
  }

  // C 端用户远程搜索
  const userLoading = ref(false)
  const userOptions = ref<WsUserItem[]>([])

  const searchUser = async (query: string) => {
    userLoading.value = true
    try {
      const res = await fetchWsUserPage({
        current: 1,
        size: 20,
        userName: query || undefined,
        userPhone: /^\d{5,}$/.test(query) ? query : undefined
      })
      userOptions.value = res.list || []
    } finally {
      userLoading.value = false
    }
  }

  // 水站下拉（任务范围）
  const stationOptions = ref<StationItem[]>([])
  onMounted(async () => {
    stationOptions.value = (await fetchStationList()) || []
  })

  // 选用户后自动带出姓名（可改）。
  // 刻意不再自动带出电话：列表下发的号码是脱敏值，带过来会把 138****5678 当成真号码提交，
  // 存进配送员表后再也拨不通，且看上去完全正常。联系电话必须人工录入完整号码。
  watch(
    () => formData.userId,
    (userId) => {
      const user = userOptions.value.find((u) => u.id === userId)
      if (user && !formData.courierName) {
        formData.courierName = user.userName
      }
    }
  )

  watch(
    () => props.visible,
    (visible) => {
      if (visible) {
        Object.assign(formData, {
          userId: undefined,
          courierName: '',
          courierPhone: '',
          idCardNo: '',
          stationIdList: [],
          serviceRegion: ''
        })
        userOptions.value = []
        nextTick(() => formRef.value?.clearValidate())
      }
    }
  )

  const handleSubmit = async () => {
    if (!formRef.value) return
    await formRef.value.validate(async (valid) => {
      if (valid) {
        submitLoading.value = true
        try {
          await fetchAddCourier({
            userId: formData.userId as string,
            courierName: formData.courierName,
            courierPhone: formData.courierPhone.trim(),
            idCardNo: formData.idCardNo || undefined,
            stationIds: formData.stationIdList.length
              ? formData.stationIdList.join(',')
              : undefined,
            serviceRegion: formData.serviceRegion || undefined
          })
          ElMessage.success('创建成功，已进入待审核')
          dialogVisible.value = false
          emit('submit')
        } finally {
          submitLoading.value = false
        }
      }
    })
  }
</script>
