<template>
  <ElDialog
    v-model="dialogVisible"
    :title="props.type === 'add' ? '添加标签' : '编辑标签'"
    width="30%"
    align-center
  >
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="80px">
      <ElFormItem label="标签名称" prop="tagName">
        <ElInput
          v-model="formData.tagName"
          placeholder="请输入标签名称"
          maxlength="20"
          show-word-limit
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
  import { fetchSaveTag, fetchUpdateTag } from '@/api/tag'
  import { ElMessage } from 'element-plus'

  interface Props {
    visible: boolean
    type: string
    tagData?: Partial<Api.SystemManage.TagListItem>
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
    id: undefined as string | undefined,
    tagName: ''
  })

  const rules: FormRules = {
    tagName: [{ required: true, message: '请输入标签名称', trigger: 'blur' }]
  }

  const initFormData = () => {
    const isEdit = props.type === 'edit' && props.tagData
    const row = props.tagData

    Object.assign(formData, {
      id: isEdit && row ? row.id : undefined,
      tagName: isEdit && row ? row.tagName || '' : ''
    })
  }

  watch(
    () => props.visible,
    async (visible) => {
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
          if (props.type === 'add') {
            await fetchSaveTag({ tagName: formData.tagName } as Api.SystemManage.TagFormData)
            ElMessage.success('添加成功')
          } else {
            await fetchUpdateTag({
              id: formData.id,
              tagName: formData.tagName
            } as Api.SystemManage.TagFormData)
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
