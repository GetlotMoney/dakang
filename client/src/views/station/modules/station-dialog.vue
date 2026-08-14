<template>
  <ElDialog
    v-model="dialogVisible"
    :title="props.type === 'add' ? '新增水站' : '编辑水站'"
    width="560px"
    align-center
  >
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="90px">
      <ElFormItem label="水站名称" prop="stationName">
        <ElInput
          v-model="formData.stationName"
          placeholder="请输入水站名称"
          maxlength="50"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="水站编码" prop="stationCode">
        <ElInput
          v-model="formData.stationCode"
          placeholder="如 WS-WH-001，业务唯一"
          maxlength="50"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="所属区域" prop="stationRegion">
        <ElInput v-model="formData.stationRegion" placeholder="如 武汉东湖高新区" maxlength="50" />
      </ElFormItem>
      <ElFormItem label="详细地址" prop="stationAddress">
        <ElInput v-model="formData.stationAddress" placeholder="请输入详细地址" maxlength="200" />
      </ElFormItem>
      <ElRow :gutter="12">
        <ElCol :span="12">
          <ElFormItem label="经度" prop="stationLng">
            <ElInput v-model="formData.stationLng" placeholder="如 114.4276" maxlength="20" />
          </ElFormItem>
        </ElCol>
        <ElCol :span="12">
          <ElFormItem label="纬度" prop="stationLat">
            <ElInput v-model="formData.stationLat" placeholder="如 30.4586" maxlength="20" />
          </ElFormItem>
        </ElCol>
      </ElRow>
      <ElFormItem label="状态" prop="stationStatus">
        <ElRadioGroup v-model="formData.stationStatus">
          <ElRadio v-for="opt in statusOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </ElRadio>
        </ElRadioGroup>
      </ElFormItem>
      <ElFormItem label="绑定机主" prop="ownerUserId">
        <ElSelect
          v-model="formData.ownerUserId"
          placeholder="按姓名/手机号搜索用户（可不绑定）"
          clearable
          filterable
          remote
          :remote-method="searchOwner"
          :loading="ownerLoading"
        >
          <ElOption
            v-for="user in ownerOptions"
            :key="user.id"
            :label="`${user.userName}（${user.userPhone}）`"
            :value="user.id"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="备注" prop="stationRemark">
        <ElInput
          v-model="formData.stationRemark"
          type="textarea"
          :rows="2"
          placeholder="选填"
          maxlength="500"
        />
      </ElFormItem>
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
  import { fetchAddStation, fetchUpdateStation, type StationItem } from '@/api/station'
  import { fetchWsUserPage } from '@/api/user'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'

  interface Props {
    visible: boolean
    type: string
    stationData?: Partial<StationItem>
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'submit'): void
  }

  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()

  const submitLoading = ref(false)

  const dialogVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const formRef = ref<FormInstance>()

  const formData = reactive({
    id: undefined as number | undefined,
    stationName: '',
    stationCode: '',
    stationRegion: '',
    stationAddress: '',
    stationLng: '',
    stationLat: '',
    stationStatus: 1,
    ownerUserId: undefined as number | undefined,
    stationRemark: ''
  })

  const rules: FormRules = {
    stationName: [{ required: true, message: '请输入水站名称', trigger: 'blur' }],
    stationCode: [{ required: true, message: '请输入水站编码', trigger: 'blur' }],
    stationRegion: [{ required: true, message: '请输入所属区域', trigger: 'blur' }],
    stationAddress: [{ required: true, message: '请输入详细地址', trigger: 'blur' }],
    stationStatus: [{ required: true, message: '请选择状态', trigger: 'change' }]
  }

  // 状态选项（字典 10）
  const statusOptions = ref<{ label: string; value: number }[]>([])
  onMounted(async () => {
    statusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.禁用状态))
  })

  // 机主远程搜索。
  // 选项 id 兼容两种来源：远程搜索来自用户列表（身份类 Long 恒为 string），
  // 编辑回显来自水站行上的 ownerUserId（本域历史契约仍是 number）。
  // 这里刻意不做数值转换——Number() 会在超过 2^53 时静默改人，宁可让两种形态并存。
  interface OwnerOption {
    id: string | number
    userName: string
    userPhone?: string
  }
  const ownerLoading = ref(false)
  const ownerOptions = ref<OwnerOption[]>([])

  const searchOwner = async (query: string) => {
    ownerLoading.value = true
    try {
      const res = await fetchWsUserPage({
        current: 1,
        size: 20,
        userName: query || undefined,
        userPhone: /^\d{5,}$/.test(query) ? query : undefined
      })
      ownerOptions.value = res.list || []
    } finally {
      ownerLoading.value = false
    }
  }

  const initFormData = () => {
    const isEdit = props.type === 'edit' && props.stationData
    const row = props.stationData

    Object.assign(formData, {
      id: isEdit && row ? row.id : undefined,
      stationName: (isEdit && row?.stationName) || '',
      stationCode: (isEdit && row?.stationCode) || '',
      stationRegion: (isEdit && row?.stationRegion) || '',
      stationAddress: (isEdit && row?.stationAddress) || '',
      stationLng: (isEdit && row?.stationLng) || '',
      stationLat: (isEdit && row?.stationLat) || '',
      stationStatus: (isEdit && row?.stationStatus) || 1,
      ownerUserId: isEdit && row ? (row.ownerUserId ?? undefined) : undefined,
      stationRemark: (isEdit && row?.stationRemark) || ''
    })

    // 编辑态回显机主选项（避免下拉只显示 ID）
    if (isEdit && row?.ownerUserId && row.ownerUserName) {
      ownerOptions.value = [
        {
          id: row.ownerUserId,
          userName: row.ownerUserName,
          userPhone: row.ownerUserPhone || ''
        }
      ]
    } else {
      ownerOptions.value = []
    }
  }

  watch(
    () => props.visible,
    (visible) => {
      if (visible) {
        initFormData()
        nextTick(() => {
          formRef.value?.clearValidate()
        })
      }
    }
  )

  const handleSubmit = async () => {
    if (!formRef.value) return

    await formRef.value.validate(async (valid) => {
      if (valid) {
        submitLoading.value = true
        try {
          const payload = {
            id: formData.id,
            stationName: formData.stationName,
            stationCode: formData.stationCode,
            stationRegion: formData.stationRegion,
            stationAddress: formData.stationAddress,
            stationLng: formData.stationLng || undefined,
            stationLat: formData.stationLat || undefined,
            stationStatus: formData.stationStatus,
            ownerUserId: formData.ownerUserId,
            stationRemark: formData.stationRemark || undefined
          }
          if (props.type === 'add') {
            await fetchAddStation(payload)
            ElMessage.success('新增成功')
          } else {
            await fetchUpdateStation(payload)
            ElMessage.success('更新成功')
          }
          dialogVisible.value = false
          emit('submit')
        } finally {
          submitLoading.value = false
        }
      }
    })
  }
</script>
