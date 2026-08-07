<template>
  <ElDialog
    :title="dialogTitle"
    :model-value="visible"
    @update:model-value="handleCancel"
    width="860px"
    align-center
    class="menu-dialog"
    destroy-on-close
    @closed="handleClosed"
  >
    <ElForm
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="100px"
      :label-position="width > 640 ? 'right' : 'top'"
    >
      <ElRow :gutter="20">
        <!-- 上级菜单（单独一行） -->
        <ElCol :span="24">
          <ElFormItem label="上级菜单">
            <ElTreeSelect
              v-model="form.menuParentId"
              :data="menuTreeData"
              :props="{ label: 'label', value: 'value', children: 'children' }"
              placeholder="选择上级菜单，不选则为一级菜单"
              clearable
              check-strictly
              :render-after-expand="false"
              class="w-full"
            />
          </ElFormItem>
        </ElCol>

        <ElCol :span="24">
          <ElFormItem label="菜单类型" prop="menuType">
            <ElRadioGroup v-model="form.menuType" :disabled="disableMenuType">
              <ElRadio v-for="opt in props.menuTypeOptions" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </ElRadio>
            </ElRadioGroup>
          </ElFormItem>
        </ElCol>

        <ElCol :xs="24" :sm="12">
          <ElFormItem label="菜单名称" prop="menuName">
            <ElInput v-model="form.menuName" placeholder="请输入菜单名称" />
          </ElFormItem>
        </ElCol>

        <ElCol :xs="24" :sm="12">
          <ElFormItem label="菜单排序" prop="menuSort">
            <ElInputNumber
              v-model="form.menuSort"
              :min="1"
              :max="10000"
              controls-position="right"
              class="w-full"
            />
          </ElFormItem>
        </ElCol>

        <!-- 图标选择（目录和菜单类型） -->
        <ElCol v-if="form.menuType === 1 || form.menuType === 2" :xs="24" :sm="12">
          <ElFormItem label="图标">
            <ArtIconPicker v-model="form.menuIcon" />
          </ElFormItem>
        </ElCol>

        <ElCol v-if="form.menuType !== 3" :xs="24" :sm="12">
          <ElFormItem label="路由地址" prop="menuPath">
            <ElInput v-model="form.menuPath" placeholder="如：user" />
          </ElFormItem>
        </ElCol>
        <ElCol v-if="form.menuType === 2" :xs="24" :sm="12">
          <ElFormItem label="组件路径">
            <ElInput v-model="form.menuComponent" placeholder="如：/system/user/index 或留空" />
          </ElFormItem>
        </ElCol>

        <ElCol v-if="form.menuType === 2 || form.menuType === 3" :xs="24" :sm="12">
          <ElFormItem label="权限标识">
            <ElInput v-model="form.menuWebPerms" placeholder="如：add、update、delete" />
          </ElFormItem>
        </ElCol>
        <ElCol :xs="24" :sm="12" v-if="form.menuType !== 3">
          <ElFormItem label="显示状态">
            <ElSwitch
              v-model="form.menuVisibleFlag"
              :active-value="1"
              :inactive-value="2"
              active-text="显示"
              inactive-text="隐藏"
            />
          </ElFormItem>
        </ElCol>

        <ElCol :xs="24" :sm="12">
          <ElFormItem label="禁用状态">
            <ElSwitch
              v-model="form.menuDisabledFlag"
              :active-value="2"
              :inactive-value="1"
              active-text="禁用"
              inactive-text="正常"
            />
          </ElFormItem>
        </ElCol>

        <ElCol :xs="24" :sm="12" v-if="form.menuType !== 3">
          <ElFormItem label="是否外链">
            <ElSwitch
              v-model="form.menuFrameFlag"
              :active-value="2"
              :inactive-value="1"
              active-text="是"
              inactive-text="否"
            />
          </ElFormItem>
        </ElCol>

        <!-- 外链地址（仅外链模式） -->
        <ElCol v-if="form.menuFrameFlag === 2" :xs="24" :sm="12">
          <ElFormItem label="外链地址" prop="menuFrameUrl">
            <ElInput v-model="form.menuFrameUrl" placeholder="外链模式下填写完整 URL" />
          </ElFormItem>
        </ElCol>
      </ElRow>
    </ElForm>

    <template #footer>
      <span class="dialog-footer">
        <ElButton @click="handleCancel">取 消</ElButton>
        <ElButton type="primary" :loading="props.submitLoading" @click="handleSubmit"
          >确 定</ElButton
        >
      </span>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import type { FormInstance, FormRules } from 'element-plus'
  import ArtIconPicker from '@/components/core/widget/art-icon-picker/index.vue'
  import { useWindowSize } from '@vueuse/core'

  const { width } = useWindowSize()

  interface MenuFormData {
    id?: number
    menuName: string
    menuType: number
    menuIcon: string
    menuParentId: number
    menuSort: number
    menuPath: string
    menuComponent: string
    menuFrameFlag: number
    menuFrameUrl: string
    menuWebPerms: string
    menuApiPerms: string
    menuVisibleFlag: number
    menuDisabledFlag: number
  }

  interface Props {
    visible: boolean
    editData?: any
    type?: 'menu' | 'button'
    lockType?: boolean
    parentId?: number
    menuTypeOptions: { label: string; value: number }[]
    menuTreeData: any[]
    submitLoading?: boolean
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'submit', data: MenuFormData): void
  }

  const props = withDefaults(defineProps<Props>(), {
    visible: false,
    type: 'menu',
    lockType: false,
    parentId: 0,
    menuTypeOptions: () => [],
    submitLoading: false
  })

  const emit = defineEmits<Emits>()

  const formRef = ref<FormInstance>()
  const isEdit = ref(false)

  const form = reactive<MenuFormData>({
    id: undefined,
    menuName: '',
    menuType: 1,
    menuIcon: '',
    menuParentId: 0,
    menuSort: 1,
    menuPath: '',
    menuComponent: '',
    menuFrameFlag: 1,
    menuFrameUrl: '',
    menuWebPerms: '',
    menuApiPerms: '',
    menuVisibleFlag: 2,
    menuDisabledFlag: 1
  })

  const rules = computed<FormRules>(() => {
    const base: FormRules = {
      menuName: [
        { required: true, message: '请输入菜单名称', trigger: 'blur' },
        { min: 1, max: 20, message: '长度在 1 到 20 个字符', trigger: 'blur' }
      ],
      menuType: [{ required: true, message: '请选择菜单类型', trigger: 'change' }],
      menuPath:
        form.menuType !== 3 ? [{ required: true, message: '请输入路由地址', trigger: 'blur' }] : []
    }
    if (form.menuFrameFlag === 2) {
      base.menuFrameUrl = [{ required: true, message: '请输入外链地址', trigger: 'blur' }]
    }
    return base
  })

  const dialogTitle = computed(() => {
    if (isEdit.value) return '编辑菜单'
    if (props.type === 'button') return '新建按钮'
    return '新建菜单'
  })

  const disableMenuType = computed(() => {
    return props.lockType
  })

  const loadFormData = (): void => {
    if (!props.editData) return
    isEdit.value = true

    const raw = props.editData._raw || props.editData

    Object.assign(form, {
      id: raw.id,
      menuName: raw.menuName || '',
      menuType: raw.menuType ?? 1,
      menuIcon: raw.menuIcon || '',
      menuParentId: Number(raw.menuParentId) ? raw.menuParentId : 0,
      menuSort: raw.menuSort ?? 1,
      menuPath: raw.menuPath || '',
      menuComponent: raw.menuComponent || '',
      menuFrameFlag: raw.menuFrameFlag || 1,
      menuFrameUrl: raw.menuFrameUrl || '',
      menuWebPerms: raw.menuWebPerms || '',
      menuApiPerms: raw.menuWebPerms || '',
      menuVisibleFlag: raw.menuVisibleFlag || 1,
      menuDisabledFlag: raw.menuDisabledFlag || 1
    })
  }

  const handleSubmit = async (): Promise<void> => {
    if (!formRef.value) return

    try {
      await formRef.value.validate()
      form.menuApiPerms = form.menuWebPerms
      emit('submit', { ...form })
    } catch {
      ElMessage.error('表单校验失败，请检查输入')
    }
  }

  const handleCancel = (): void => {
    emit('update:visible', false)
  }

  const handleClosed = (): void => {
    form.id = undefined
    form.menuName = ''
    form.menuType = 1
    form.menuIcon = ''
    form.menuSort = 1
    form.menuPath = ''
    form.menuComponent = ''
    form.menuFrameFlag = 1
    form.menuFrameUrl = ''
    form.menuWebPerms = ''
    form.menuApiPerms = ''
    form.menuVisibleFlag = 2
    form.menuDisabledFlag = 1
    form.menuParentId = 0
    isEdit.value = false
  }

  watch(
    () => props.visible,
    (newVal) => {
      if (newVal) {
        form.menuParentId = props.parentId || 0
        nextTick(() => {
          if (props.editData) {
            loadFormData()
          }
          formRef.value?.clearValidate()
        })
      }
    }
  )
</script>

<style scoped lang="scss">
  .w-full {
    width: 100%;
  }
</style>
