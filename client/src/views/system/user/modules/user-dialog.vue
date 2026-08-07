<template>
  <ElDialog
    v-model="dialogVisible"
    :title="dialogType === 'add' ? '添加用户' : '编辑用户'"
    width="30%"
    align-center
  >
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="80px">
      <ElFormItem label="登录账号" prop="loginName">
        <ElInput
          v-model="formData.loginName"
          placeholder="请输入登录账号"
          maxlength="20"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="用户姓名" prop="employeeName">
        <ElInput
          v-model="formData.employeeName"
          placeholder="请输入用户姓名"
          maxlength="10"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="性别" prop="employeeGender">
        <ElRadioGroup v-model="formData.employeeGender">
          <ElRadio v-for="item in genderOptions" :key="item.value" :value="item.value">
            {{ item.label }}
          </ElRadio>
        </ElRadioGroup>
      </ElFormItem>
      <ElFormItem label="手机号" prop="employeePhone">
        <ElInput
          v-model="formData.employeePhone"
          placeholder="请输入手机号"
          maxlength="11"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="部门" prop="deptId">
        <ElTreeSelect
          v-model="formData.deptId"
          :data="deptTree || []"
          :props="{ label: 'deptName', value: 'id', children: 'children' }"
          placeholder="请选择部门"
          clearable
          check-strictly
          :render-after-expand="false"
          style="width: 100%"
        />
      </ElFormItem>
      <ElFormItem label="职务" prop="positionId">
        <div class="select-with-actions">
          <ElSelect
            v-model="formData.positionId"
            placeholder="请选择职务"
            clearable
            style="flex: 1"
          >
            <ElOption
              v-for="item in positionOptions"
              :key="item.id"
              :label="item.positionName"
              :value="item.id"
            >
              <div class="position-option">
                <div class="option-label">{{ item.positionName }}</div>
                <div class="option-actions">
                  <ElButton type="primary" link size="small" @click.stop="handleEditPosition(item)">
                    <ElIcon><Edit /></ElIcon>
                  </ElButton>
                  <ElButton
                    type="danger"
                    link
                    size="small"
                    @click.stop="handleDeletePosition(item.id)"
                  >
                    <ElIcon><Delete /></ElIcon>
                  </ElButton>
                </div>
              </div>
            </ElOption>
            <div class="flex align-center justify-center">
              <ElButton type="text" @click="handleAddPosition">
                <ElIcon><Plus /></ElIcon>
                去添加
              </ElButton>
            </div>
          </ElSelect>
        </div>
      </ElFormItem>
      <ElFormItem label="标签" prop="tagIdList">
        <div class="select-with-actions">
          <ElSelect
            v-model="formData.tagIdList"
            placeholder="请选择标签"
            multiple
            clearable
            style="flex: 1"
          >
            <ElOption
              v-for="item in tagOptions"
              :key="item.id"
              :label="item.tagName"
              :value="item.id"
            >
              <div class="tag-option">
                <span class="option-label">{{ item.tagName }}</span>
                <div class="option-actions">
                  <ElButton type="primary" link size="small" @click.stop="handleEditTag(item)">
                    <ElIcon><Edit /></ElIcon>
                  </ElButton>
                  <ElButton type="danger" link size="small" @click.stop="handleDeleteTag(item.id)">
                    <ElIcon><Delete /></ElIcon>
                  </ElButton>
                </div>
              </div>
            </ElOption>
            <div class="flex align-center justify-center">
              <ElButton type="text" @click="handleAddTag">
                <ElIcon><Plus /></ElIcon>
                去添加
              </ElButton>
            </div>
          </ElSelect>
          <!-- <ElButton type="primary" @click="handleAddTag">
            <ElIcon><Plus /></ElIcon>
            去添加
          </ElButton> -->
        </div>
      </ElFormItem>
      <ElFormItem label="状态" prop="disabledFlag">
        <ElRadioGroup v-model="formData.disabledFlag">
          <ElRadio :value="1">启用</ElRadio>
          <ElRadio :value="2">禁用</ElRadio>
        </ElRadioGroup>
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
  import { ElTreeSelect } from 'element-plus'
  import { fetchSaveUser, fetchUpdateUser, fetchDeletePosition } from '@/api/system-manage'
  import { fetchDeleteTag } from '@/api/tag'
  import { ElMessage, ElMessageBox, ElIcon } from 'element-plus'
  import { Plus, Edit, Delete } from '@element-plus/icons-vue'

  interface Props {
    visible: boolean
    type: string
    userData?: Partial<Api.SystemManage.UserListItem>
    deptTree?: Api.SystemManage.DeptListItem[]
    genderOptions: { label: string; value: number }[]
    positionOptions: { id: string; positionName: string }[]
    tagOptions: { id: string; tagName: string }[]
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'submit'): void
    (e: 'refreshPosition'): void
    (e: 'refreshTag'): void
    (e: 'openPositionDialog', data?: Partial<Api.SystemManage.PositionListItem>): void
    (e: 'openTagDialog', data?: Partial<Api.SystemManage.TagListItem>): void
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
    loginName: '',
    employeeName: '',
    employeeGender: 1,
    employeePhone: '',
    deptId: null as string | null,
    positionId: undefined as string | undefined,
    disabledFlag: 1,
    tagIdList: [] as string[]
  })

  const rules: FormRules = {
    loginName: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
    employeeName: [{ required: true, message: '请输入用户姓名', trigger: 'blur' }],
    employeeGender: [{ required: true, message: '请选择性别', trigger: 'change' }],
    employeePhone: [
      { required: true, message: '请输入手机号', trigger: 'blur' },
      { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号格式', trigger: 'blur' }
    ]
  }

  // 新增职务
  const handleAddPosition = () => {
    emit('openPositionDialog')
  }

  // 编辑职务
  const handleEditPosition = (item: { id: string; positionName: string }) => {
    emit('openPositionDialog', { ...item } as any)
  }

  // 删除职务
  const handleDeletePosition = (id: string) => {
    ElMessageBox.confirm('确定要删除该职务吗？', '删除职务', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
      .then(async () => {
        try {
          await fetchDeletePosition(id)
          ElMessage.success('删除成功')
          emit('refreshPosition')
        } catch {
          ElMessage.error('删除失败')
        }
      })
      .catch(() => {})
  }

  // 新增标签
  const handleAddTag = () => {
    emit('openTagDialog')
  }

  // 编辑标签
  const handleEditTag = (item: { id: string; tagName: string }) => {
    emit('openTagDialog', { id: item.id, tagName: item.tagName } as any)
  }

  // 删除标签
  const handleDeleteTag = (id: string) => {
    ElMessageBox.confirm('确定要删除该标签吗？', '删除标签', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
      .then(async () => {
        try {
          await fetchDeleteTag(id)
          ElMessage.success('删除成功')
          emit('refreshTag')
        } catch {
          ElMessage.error('删除失败')
        }
      })
      .catch(() => {})
  }

  const initFormData = () => {
    const isEdit = props.type === 'edit' && props.userData
    const row = props.userData

    Object.assign(formData, {
      id: isEdit && row ? row.id : undefined,
      loginName: isEdit && row ? row.loginName || '' : '',
      employeeName: isEdit && row ? row.employeeName || '' : '',
      employeeGender: isEdit && row ? (row.employeeGender ?? 1) : 1,
      employeePhone: isEdit && row ? row.employeePhone || '' : '',
      deptId:
        isEdit && row ? (String(row.deptId) === '0' ? null : String(row.deptId) || null) : null,
      positionId:
        isEdit && row
          ? String(row.positionId) === '0'
            ? null
            : String(row.positionId) || null
          : null,
      disabledFlag: isEdit && row ? (row.disabledFlag ?? 1) : 1,
      tagIdList: isEdit && row ? (row.tagList || []).map((t: { id: string }) => t.id) : []
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

    try {
      await formRef.value.validate()
    } catch {
      return
    }

    submitLoading.value = true
    try {
      if (dialogType.value === 'add') {
        const initPwdReceipt = await fetchSaveUser(formData as Api.SystemManage.UserFormData)
        ElMessage.success('添加成功')
        dialogVisible.value = false
        emit('submit')
        // 一次性初始密码：库内只存哈希，本弹窗关闭后无法再查看（R-201）
        await ElMessageBox.alert(
          `<p>请将初始密码转交员工，<strong>关闭后无法再次查看</strong>：</p>
           <p style="margin:12px 0;font-size:20px;text-align:center;"><code>${initPwdReceipt.initialPwd}</code></p>`,
          '初始密码（仅显示一次）',
          {
            dangerouslyUseHTMLString: true,
            confirmButtonText: '我已转交，关闭'
          }
        ).catch(() => {})
      } else {
        await fetchUpdateUser(formData as Api.SystemManage.UserFormData)
        ElMessage.success('更新成功')
        dialogVisible.value = false
        emit('submit')
      }
    } catch {
      // error handled by http
    } finally {
      submitLoading.value = false
    }
  }
</script>

<style scoped lang="scss">
  .select-with-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
  }

  .el-select-dropdown__item {
    padding: 0 12px !important;
  }

  .position-option,
  .tag-option {
    display: flex;
    align-items: center;
    justify-content: space-between;
    /* width: 100%; */
    padding-right: 4px;
  }

  .option-label {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .option-actions {
    width: fit-content;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    .el-button {
      margin: 0;
    }
  }
</style>
