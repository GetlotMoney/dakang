<template>
  <ElDialog
    v-model="dialogVisible"
    :title="dialogType === 'add' ? '添加职务' : '编辑职务'"
    width="30%"
    align-center
  >
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="80px">
      <ElFormItem label="职务名称" prop="positionName">
        <ElInput
          v-model="formData.positionName"
          placeholder="请输入职务名称"
          maxlength="20"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="职务等级" prop="positionLevel">
        <ElInput
          v-model="formData.positionLevel"
          placeholder="请输入职务等级"
          maxlength="20"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="排序" prop="positionSort">
        <ElInputNumber
          v-model="formData.positionSort"
          :min="1"
          :max="10000"
          placeholder="请输入排序值"
        />
      </ElFormItem>
      <ElFormItem label="备注" prop="positionRemark">
        <ElInput
          v-model="formData.positionRemark"
          type="textarea"
          :rows="3"
          maxlength="300"
          show-word-limit
          placeholder="请输入备注"
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
  import { fetchSavePosition, fetchUpdatePosition } from '@/api/system-manage'
  import { ElMessage } from 'element-plus'

  interface Props {
    visible: boolean
    type: string
    positionData?: Partial<Api.SystemManage.PositionListItem>
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

  const dialogType = computed(() => props.type)

  const formRef = ref<FormInstance>()

  const formData = reactive({
    id: undefined as string | undefined,
    positionName: '',
    positionLevel: '',
    positionSort: 0,
    positionRemark: ''
  })

  const rules: FormRules = {
    positionName: [{ required: true, message: '请输入职务名称', trigger: 'blur' }],
    positionLevel: [{ required: true, message: '请输入职务等级', trigger: 'blur' }]
  }

  const initFormData = () => {
    const isEdit = props.type === 'edit' && props.positionData
    const row = props.positionData

    Object.assign(formData, {
      id: isEdit && row ? row.id : undefined,
      positionName: isEdit && row ? row.positionName || '' : '',
      positionLevel: isEdit && row ? (row.positionLevel as string) || '' : '',
      positionSort: isEdit && row ? (row.positionSort ?? 0) : 0,
      positionRemark: isEdit && row ? row.positionRemark || '' : ''
    })
  }

  watch(
    () => [props.visible, props.type, props.positionData],
    ([visible]) => {
      if (visible) {
        initFormData()
        nextTick(() => {
          formRef.value?.clearValidate()
        })
      }
    },
    { immediate: true }
  )

  const handleSubmit = async () => {
    if (!formRef.value) return

    await formRef.value.validate(async (valid) => {
      if (valid) {
        submitLoading.value = true
        try {
          if (dialogType.value === 'add') {
            await fetchSavePosition({
              positionName: formData.positionName,
              positionLevel: formData.positionLevel,
              positionSort: formData.positionSort,
              positionRemark: formData.positionRemark
            })
            ElMessage.success('添加成功')
          } else {
            await fetchUpdatePosition({
              id: formData.id,
              positionName: formData.positionName,
              positionLevel: formData.positionLevel,
              positionSort: formData.positionSort,
              positionRemark: formData.positionRemark
            })
            ElMessage.success('更新成功')
          }
          dialogVisible.value = false
          emit('submit')
        } catch {
          // error handled by http
        } finally {
          submitLoading.value = false
        }
      }
    })
  }
</script>
