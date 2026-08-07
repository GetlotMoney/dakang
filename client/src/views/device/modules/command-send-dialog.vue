<template>
  <ElDialog v-model="dialogVisible" title="下发指令" width="520px" align-center>
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="90px">
      <ElFormItem label="目标设备">
        <ElInput :model-value="`${props.deviceName || ''}（${props.deviceNo || ''}）`" disabled />
      </ElFormItem>
      <ElFormItem label="指令类型" prop="cmdType">
        <ElSelect v-model="formData.cmdType" placeholder="请选择指令类型">
          <ElOption
            v-for="opt in cmdTypeOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem
        v-if="formData.cmdType === CMD_TYPE_PARAM_SYNC"
        label="参数报文"
        prop="cmdPayload"
      >
        <ElInput
          v-model="formData.cmdPayload"
          type="textarea"
          :rows="4"
          placeholder='JSON 格式，如 {"price":{"outlet1":20}}'
        />
      </ElFormItem>
    </ElForm>
    <template #footer>
      <div class="dialog-footer">
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitLoading" @click="handleSubmit">下发</ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { fetchSendCommand } from '@/api/device'

  /** 后台中控允许的指令类型（1开始出水/2停止出水 由订单链路触发，不在下拉出现） */
  const CONSOLE_ALLOWED = [3, 4, 5, 6, 7]
  const CMD_TYPE_LOCK = 4
  const CMD_TYPE_PARAM_SYNC = 6

  interface Props {
    visible: boolean
    deviceId?: number
    deviceNo?: string
    deviceName?: string
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'submit'): void
  }

  const props = withDefaults(defineProps<Props>(), { visible: false })
  const emit = defineEmits<Emits>()

  const dialogVisible = computed({
    get: () => props.visible,
    set: (val) => emit('update:visible', val)
  })

  const formRef = ref<FormInstance>()
  const submitLoading = ref(false)
  const formData = reactive({
    cmdType: undefined as number | undefined,
    cmdPayload: ''
  })

  const rules: FormRules = {
    cmdType: [{ required: true, message: '请选择指令类型', trigger: 'change' }],
    cmdPayload: [
      {
        validator: (_rule, value: string, callback) => {
          if (formData.cmdType !== CMD_TYPE_PARAM_SYNC) return callback()
          if (!value) return callback(new Error('参数同步必须携带 JSON 报文'))
          try {
            JSON.parse(value)
            callback()
          } catch {
            callback(new Error('报文不是合法 JSON'))
          }
        },
        trigger: 'blur'
      }
    ]
  }

  const cmdTypeOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const all = toDictOptions(await fetchDictOptions(DictTypeEnum.指令类型))
    cmdTypeOptions.value = all.filter((o) => CONSOLE_ALLOWED.includes(o.value))
  })

  watch(dialogVisible, (visible) => {
    if (visible) {
      formData.cmdType = undefined
      formData.cmdPayload = ''
      nextTick(() => formRef.value?.clearValidate())
    }
  })

  async function handleSubmit() {
    await formRef.value?.validate()
    if (!props.deviceId) return
    // 锁机属高风险操作：显式确认（完整二次验证 openSafe 为商业一期口径）
    if (formData.cmdType === CMD_TYPE_LOCK) {
      await ElMessageBox.confirm(
        `锁机后设备 ${props.deviceNo} 停止取水，确认锁机？`,
        '高风险操作确认',
        { type: 'warning', confirmButtonText: '确认锁机', cancelButtonText: '取消' }
      )
    }
    submitLoading.value = true
    try {
      await fetchSendCommand({
        deviceId: props.deviceId,
        cmdType: formData.cmdType!,
        cmdPayload: formData.cmdType === CMD_TYPE_PARAM_SYNC ? formData.cmdPayload : undefined
      })
      ElMessage.success('指令已下发，执行结果见指令记录')
      dialogVisible.value = false
      emit('submit')
    } finally {
      submitLoading.value = false
    }
  }
</script>
