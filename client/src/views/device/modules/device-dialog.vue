<template>
  <ElDialog
    v-model="dialogVisible"
    :title="props.type === 'add' ? '新增设备' : '编辑设备'"
    width="560px"
    align-center
  >
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="90px">
      <ElFormItem label="设备编号" prop="deviceNo">
        <ElInput
          v-model="formData.deviceNo"
          :disabled="props.type === 'edit'"
          placeholder="如 DK-DEV-0003，建档后不可修改"
          maxlength="50"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="设备名称" prop="deviceName">
        <ElInput
          v-model="formData.deviceName"
          placeholder="请输入设备名称"
          maxlength="50"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="设备型号" prop="deviceModel">
        <ElInput v-model="formData.deviceModel" placeholder="如 DK-W800" maxlength="50" />
      </ElFormItem>
      <ElFormItem label="所属水站" prop="stationId">
        <ElSelect v-model="formData.stationId" placeholder="请选择水站" filterable>
          <ElOption
            v-for="station in stationOptions"
            :key="station.id"
            :label="station.stationName"
            :value="station.id"
          />
        </ElSelect>
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
      <ElRow :gutter="12">
        <ElCol :span="12">
          <ElFormItem label="固件版本" prop="firmwareVersion">
            <ElInput v-model="formData.firmwareVersion" placeholder="如 v1.0.0" maxlength="50" />
          </ElFormItem>
        </ElCol>
        <ElCol :span="12">
          <ElFormItem label="SIM 运营商" prop="simCarrier">
            <ElInput v-model="formData.simCarrier" placeholder="如 中国移动" maxlength="20" />
          </ElFormItem>
        </ElCol>
      </ElRow>
      <ElFormItem label="SIM ICCID" prop="simIccid">
        <ElInput v-model="formData.simIccid" placeholder="选填" maxlength="50" />
      </ElFormItem>
      <ElRow :gutter="12">
        <ElCol :span="12">
          <ElFormItem label="SIM 状态" prop="simStatus">
            <ElSelect v-model="formData.simStatus" placeholder="未配置" clearable>
              <ElOption
                v-for="item in simStatusOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
        </ElCol>
        <ElCol :span="12">
          <ElFormItem label="SIM 到期" prop="simExpireTime">
            <ElDatePicker
              v-model="formData.simExpireTime"
              type="datetime"
              value-format="YYYYMMDDHHmmss"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="选填"
              clearable
            />
          </ElFormItem>
        </ElCol>
      </ElRow>
      <ElFormItem label="备注" prop="deviceRemark">
        <ElInput
          v-model="formData.deviceRemark"
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
  import { fetchAddDevice, fetchUpdateDevice, type DeviceItem } from '@/api/device'
  import { fetchStationList, type StationItem } from '@/api/station'
  import { fetchWsUserPage, type WsUserItem } from '@/api/user'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'

  interface Props {
    visible: boolean
    type: string
    deviceData?: Partial<DeviceItem>
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'submit'): void
  }

  const props = withDefaults(defineProps<Props>(), {
    visible: false,
    type: 'add'
  })
  const emit = defineEmits<Emits>()

  const dialogVisible = computed({
    get: () => props.visible,
    set: (val) => emit('update:visible', val)
  })

  const formRef = ref<FormInstance>()
  const submitLoading = ref(false)

  const formData = reactive({
    id: undefined as number | undefined,
    deviceNo: '',
    deviceName: '',
    deviceModel: '',
    stationId: undefined as number | undefined,
    ownerUserId: undefined as number | undefined,
    firmwareVersion: '',
    simIccid: '',
    simCarrier: '',
    simStatus: undefined as number | undefined,
    simExpireTime: '',
    deviceRemark: ''
  })

  const rules: FormRules = {
    deviceNo: [{ required: true, message: '请输入设备编号', trigger: 'blur' }],
    deviceName: [{ required: true, message: '请输入设备名称', trigger: 'blur' }],
    deviceModel: [{ required: true, message: '请输入设备型号', trigger: 'blur' }],
    stationId: [{ required: true, message: '请选择所属水站', trigger: 'change' }]
  }

  // 水站下拉
  const stationOptions = ref<StationItem[]>([])
  // 机主远程搜索
  const ownerOptions = ref<WsUserItem[]>([])
  const ownerLoading = ref(false)
  const simStatusOptions = ref<{ label: string; value: number }[]>([])

  async function searchOwner(keyword: string) {
    if (!keyword) {
      ownerOptions.value = []
      return
    }
    ownerLoading.value = true
    try {
      // 手机号纯数字走手机号筛选，否则按姓名
      const isPhone = /^\d+$/.test(keyword)
      const res = await fetchWsUserPage({
        current: 1,
        size: 20,
        userName: isPhone ? undefined : keyword,
        userPhone: isPhone ? keyword : undefined
      })
      ownerOptions.value = res.list
    } finally {
      ownerLoading.value = false
    }
  }

  watch(dialogVisible, async (visible) => {
    if (!visible) return
    if (!stationOptions.value.length) {
      stationOptions.value = await fetchStationList()
    }
    if (!simStatusOptions.value.length) {
      simStatusOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.SIM状态))
    }
    await nextTick()
    formRef.value?.clearValidate()
    const row = props.deviceData
    if (props.type === 'edit' && row?.id) {
      Object.assign(formData, {
        id: row.id,
        deviceNo: row.deviceNo || '',
        deviceName: row.deviceName || '',
        deviceModel: row.deviceModel || '',
        stationId: row.stationId,
        ownerUserId: row.ownerUserId,
        firmwareVersion: row.firmwareVersion || '',
        simIccid: row.simIccid || '',
        simCarrier: row.simCarrier || '',
        simStatus: row.simStatus,
        simExpireTime: row.simExpireTime || '',
        deviceRemark: row.deviceRemark || ''
      })
      // 编辑回显机主选项（远程下拉初始为空会导致只显示 ID）
      if (row.ownerUserId && row.ownerUserName) {
        ownerOptions.value = [
          { id: row.ownerUserId, userName: row.ownerUserName, userPhone: '' } as WsUserItem
        ]
      }
    } else {
      Object.assign(formData, {
        id: undefined,
        deviceNo: '',
        deviceName: '',
        deviceModel: '',
        stationId: undefined,
        ownerUserId: undefined,
        firmwareVersion: '',
        simIccid: '',
        simCarrier: '',
        simStatus: undefined,
        simExpireTime: '',
        deviceRemark: ''
      })
      ownerOptions.value = []
    }
  })

  async function handleSubmit() {
    await formRef.value?.validate()
    submitLoading.value = true
    try {
      const payload = { ...formData } as any
      if (props.type === 'add') {
        await fetchAddDevice(payload)
        ElMessage.success('新增成功，设备联网后自动激活')
      } else {
        await fetchUpdateDevice(payload)
        ElMessage.success('修改成功')
      }
      dialogVisible.value = false
      emit('submit')
    } finally {
      submitLoading.value = false
    }
  }
</script>
