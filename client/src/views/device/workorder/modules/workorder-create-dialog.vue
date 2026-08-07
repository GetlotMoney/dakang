<!-- 后台建单弹窗（E2E-05 包D）：维修/配件/巡检，直接进待分配；巡检必须指定设备（后端同样校验） -->
<template>
  <ElDialog v-model="visible" title="后台建单" width="520px" align-center>
    <ElForm label-width="88px">
      <ElFormItem label="工单类型" required>
        <ElSelect v-model="form.workType" style="width: 100%">
          <ElOption
            v-for="item in typeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="目标设备" :required="form.workType === 3">
        <ElSelect
          v-model="form.deviceId"
          filterable
          clearable
          placeholder="选择设备（巡检必选）"
          style="width: 100%"
          :loading="deviceLoading"
        >
          <ElOption
            v-for="item in devices"
            :key="item.id"
            :label="`${item.deviceName}（${item.deviceNo}）`"
            :value="item.id"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="工单标题" required>
        <ElInput v-model="form.orderTitle" maxlength="60" show-word-limit />
      </ElFormItem>
      <ElFormItem label="问题描述">
        <ElInput
          v-model="form.orderContent"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
        />
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="visible = false">取消</ElButton>
      <ElButton type="primary" :loading="saving" @click="doCreate">创建（进待分配）</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import { fetchCreateWorkOrder, fetchDevicePage, type DeviceItem } from '@/api/device'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'

  defineOptions({ name: 'WorkOrderCreateDialog' })

  const emit = defineEmits<{ created: [] }>()
  const visible = defineModel<boolean>({ required: true })

  const saving = ref(false)
  const deviceLoading = ref(false)
  const devices = ref<DeviceItem[]>([])
  const typeOptions = ref<{ label: string; value: number }[]>([])

  const form = reactive({
    workType: 3,
    deviceId: undefined as number | undefined,
    orderTitle: '',
    orderContent: ''
  })

  onMounted(async () => {
    typeOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.工单类型))
  })

  watch(visible, async (open) => {
    if (!open || devices.value.length) return
    deviceLoading.value = true
    try {
      const page = await fetchDevicePage({ current: 1, size: 100 })
      devices.value = page.list ?? []
    } finally {
      deviceLoading.value = false
    }
  })

  async function doCreate() {
    if (!form.orderTitle.trim()) {
      ElMessage.warning('工单标题必填')
      return
    }
    if (form.workType === 3 && !form.deviceId) {
      ElMessage.warning('巡检工单必须指定设备')
      return
    }
    saving.value = true
    try {
      await fetchCreateWorkOrder({
        workType: form.workType,
        deviceId: form.deviceId,
        orderTitle: form.orderTitle.trim(),
        orderContent: form.orderContent.trim() || undefined
      })
      ElMessage.success('工单已创建（待分配）')
      visible.value = false
      form.orderTitle = ''
      form.orderContent = ''
      form.deviceId = undefined
      emit('created')
    } finally {
      saving.value = false
    }
  }
</script>
