<template>
  <ElDialog
    v-model="visible"
    :title="dialogType === 'add' ? '新增角色' : '编辑角色'"
    width="30%"
    align-center
    @closed="handleClose"
  >
    <ElForm ref="formRef" :model="form" :rules="rules" label-width="120px">
      <ElFormItem label="角色名称" prop="roleName">
        <ElInput
          v-model="form.roleName"
          placeholder="请输入角色名称"
          maxlength="10"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="角色编码" prop="roleCode">
        <ElInput
          v-model="form.roleCode"
          placeholder="请输入角色编码"
          maxlength="10"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="排序" prop="roleSort">
        <ElInputNumber
          v-model="form.roleSort"
          :min="1"
          :max="10000"
          :precision="0"
          placeholder="请输入排序值"
        />
      </ElFormItem>
      <ElFormItem label="备注" prop="roleRemark">
        <ElInput
          v-model="form.roleRemark"
          type="textarea"
          :rows="3"
          placeholder="请输入角色备注"
          maxlength="300"
          show-word-limit
        />
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="visible = false">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="handleSubmit">提交</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage } from 'element-plus'
  import { fetchSaveRole, fetchUpdateRole } from '@/api/system-manage'

  type RoleListItem = Api.SystemManage.RoleListItem

  interface Props {
    modelValue: boolean
    dialogType: 'add' | 'edit'
    roleData?: RoleListItem
  }

  interface Emits {
    (e: 'update:modelValue', value: boolean): void
    (e: 'success'): void
  }

  const props = withDefaults(defineProps<Props>(), {
    modelValue: false,
    dialogType: 'add',
    roleData: undefined
  })

  const emit = defineEmits<Emits>()

  const formRef = ref<FormInstance>()
  const submitting = ref(false)

  /**
   * 弹窗显示状态双向绑定
   */
  const visible = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })

  /**
   * 表单验证规则
   */
  const rules = reactive<FormRules>({
    roleName: [
      { required: true, message: '请输入角色名称', trigger: 'blur' },
      { min: 2, max: 10, message: '长度在 2 到 10 个字符', trigger: 'blur' }
    ],
    roleCode: [
      { required: true, message: '请输入角色编码', trigger: 'blur' },
      { min: 2, max: 10, message: '长度在 2 到 10 个字符', trigger: 'blur' }
    ],
    roleRemark: [{ required: true, message: '请输入角色备注', trigger: 'blur' }],
    roleSort: [
      { required: true, message: '请输入排序值', trigger: 'blur' },
      { type: 'number', min: 1, max: 10000, message: '排序值范围 1-10000', trigger: 'blur' }
    ]
  })

  /**
   * 表单数据
   */
  const form = reactive<Omit<RoleListItem, 'id' | 'createTime' | 'updateTime'> & { id?: string }>({
    roleName: '',
    roleCode: '',
    roleRemark: '',
    roleSort: 1
  })

  /**
   * 监听弹窗打开，初始化表单数据
   */
  watch(
    () => props.modelValue,
    (newVal) => {
      if (newVal) initForm()
    }
  )

  /**
   * 监听角色数据变化，更新表单
   */
  watch(
    () => props.roleData,
    (newData) => {
      if (newData && props.modelValue) initForm()
    },
    { deep: true }
  )

  /**
   * 初始化表单数据
   * 根据弹窗类型填充表单或重置表单
   */
  const initForm = () => {
    if (props.dialogType === 'edit' && props.roleData) {
      form.id = props.roleData.id
      form.roleName = props.roleData.roleName
      form.roleCode = props.roleData.roleCode
      form.roleRemark = props.roleData.roleRemark
      form.roleSort = props.roleData.roleSort
    } else {
      form.id = undefined
      form.roleName = ''
      form.roleCode = ''
      form.roleRemark = ''
      form.roleSort = 1
    }
  }

  /**
   * 关闭弹窗并重置表单
   */
  const handleClose = () => {
    visible.value = false
    formRef.value?.resetFields()
  }

  /**
   * 提交表单
   * 验证通过后调用接口保存数据
   */
  const handleSubmit = async () => {
    if (!formRef.value) return

    try {
      await formRef.value.validate()
      submitting.value = true
      const payload = { ...form }
      if (!payload.id) delete payload.id
      if (props.dialogType === 'add') {
        await fetchSaveRole(payload as Api.SystemManage.RoleListItem)
      } else {
        await fetchUpdateRole(payload as Api.SystemManage.RoleListItem)
      }
      const message = props.dialogType === 'add' ? '新增成功' : '修改成功'
      ElMessage.success(message)
      emit('success')
      handleClose()
    } catch (error) {
      console.log('表单验证失败:', error)
    } finally {
      submitting.value = false
    }
  }
</script>
