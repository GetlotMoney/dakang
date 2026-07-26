<template>
  <ElDialog
    v-model="dialogVisible"
    :title="props.type === 'add' ? '添加部门' : '编辑部门'"
    width="30%"
    align-center
  >
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="100px">
      <ElFormItem label="部门名称" prop="deptName">
        <ElInput
          v-model="formData.deptName"
          placeholder="请输入部门名称"
          maxlength="20"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="上级部门" prop="deptParentId">
        <ElTreeSelect
          v-model="formData.deptParentId"
          :data="props.deptTree || []"
          :props="{ label: 'deptName', value: 'id', children: 'children' }"
          placeholder="请选择上级部门"
          clearable
          check-strictly
          :render-after-expand="false"
          class="w-full"
        />
      </ElFormItem>
      <ElFormItem label="部门负责人" prop="deptManagerId">
        <ElSelect
          v-model="formData.deptManagerId"
          placeholder="请选择部门负责人"
          clearable
          filterable
          class="w-full"
        >
          <ElOption
            v-for="user in userOptions"
            :key="user.id"
            :label="user.employeeName"
            :value="user.id"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="排序" prop="deptSort">
        <ElInputNumber
          v-model="formData.deptSort"
          :min="1"
          :max="10000"
          placeholder="请输入排序值"
        />
      </ElFormItem>
      <ElFormItem label="部门描述" prop="deptDesc">
        <ElInput
          v-model="formData.deptDesc"
          type="textarea"
          :rows="3"
          placeholder="请输入部门描述"
          maxlength="300"
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
  import { ElTreeSelect, ElOption } from 'element-plus'
  import { fetchSaveDept, fetchUpdateDept } from '@/api/dept'
  import { ElMessage } from 'element-plus'

  interface Props {
    visible: boolean
    type: string
    deptData?: Partial<Api.SystemManage.DeptListItem>
    deptTree?: Api.SystemManage.DeptListItem[]
    userOptions?: Api.SystemManage.UserListItem[]
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

  const userOptions = computed(() => props.userOptions || [])

  const formData = reactive({
    id: undefined as string | undefined,
    deptName: '',
    deptManagerId: undefined as string | undefined,
    deptParentId: null as string | null,
    deptSort: 0,
    deptDesc: ''
  })

  const rules: FormRules = {
    deptName: [{ required: true, message: '请输入部门名称', trigger: 'blur' }]
  }

  const initFormData = () => {
    const isEdit = props.type === 'edit' && props.deptData
    const row = props.deptData

    Object.assign(formData, {
      id: isEdit && row ? row.id : undefined,
      deptName: isEdit && row ? row.deptName || '' : '',
      deptManagerId:
        isEdit && row ? (Number(row.deptManagerId) ? row.deptManagerId : undefined) : undefined,
      deptParentId:
        isEdit && row ? (Number(row.deptParentId) ? row.deptParentId : undefined) : undefined,
      deptSort: isEdit && row ? row.deptSort || 0 : 0,
      deptDesc: isEdit && row ? row.deptDesc || '' : ''
    })
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
          const submitData = {
            ...formData,
            deptParentId: formData.deptParentId || '0',
            deptManagerId: formData.deptManagerId || '0'
          }
          if (props.type === 'add') {
            await fetchSaveDept(submitData as Api.SystemManage.DeptFormData)
            ElMessage.success('添加成功')
          } else {
            await fetchUpdateDept(submitData as Api.SystemManage.DeptFormData)
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

<style scoped lang="scss">
  .w-full {
    width: 100%;
  }
</style>
