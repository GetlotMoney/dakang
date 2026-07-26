<template>
  <ElDialog
    v-model="visible"
    title="菜单权限"
    width="520px"
    align-center
    class="el-dialog-border"
    @close="handleClose"
  >
    <ElScrollbar height="70vh">
      <ElTree
        v-loading="menuLoading"
        ref="treeRef"
        :data="menuData"
        show-checkbox
        node-key="id"
        :default-expand-all="isExpandAll"
        :props="treeProps"
        :load="loadNode"
        :check-strictly="checkStrictly"
        lazy
      >
        <template #default="{ data }">
          <span>{{ data.menuName }}</span>
        </template>
      </ElTree>
    </ElScrollbar>
    <template #footer>
      <ElButton @click="toggleExpandAll">{{ isExpandAll ? '全部收起' : '全部展开' }}</ElButton>
      <ElButton @click="toggleSelectAll" style="margin-left: 8px">{{
        isSelectAll ? '取消全选' : '全部选择'
      }}</ElButton>
      <ElButton type="primary" :loading="saveLoading" @click="savePermission">保存</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { fetchGetMenuList, fetchGetRoleMenuIds, fetchUpdateRoleMenu } from '@/api/system-manage'

  type RoleListItem = Api.SystemManage.RoleListItem

  interface Props {
    modelValue: boolean
    roleData?: RoleListItem
  }

  interface Emits {
    (e: 'update:modelValue', value: boolean): void
    (e: 'success'): void
  }

  const props = withDefaults(defineProps<Props>(), {
    modelValue: false,
    roleData: undefined
  })

  const emit = defineEmits<Emits>()

  const treeRef = ref()
  const isExpandAll = ref(true)
  const isSelectAll = ref(false)
  const menuLoading = ref(false)
  const saveLoading = ref(false)
  const menuData = ref<any[]>([])
  const checkedMenuIds = ref<string[]>([])
  const checkStrictly = ref(true)

  const visible = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })

  const treeProps = {
    children: 'children',
    label: 'menuName'
  }

  watch(
    () => props.modelValue,
    async (newVal) => {
      if (newVal && props.roleData) {
        menuLoading.value = true
        try {
          await loadRoleMenuIds()
        } finally {
          menuLoading.value = false
        }
      }
    }
  )

  const loadRoleMenuIds = async () => {
    if (!props.roleData?.id) return
    const list = await fetchGetMenuList()
    menuData.value = list
    const ids = await fetchGetRoleMenuIds(String(props.roleData.id))
    checkedMenuIds.value = ids
    nextTick(() => {
      treeRef.value?.setCheckedKeys(ids)
      checkStrictly.value = false
    })
  }

  const loadNode = async (node: any, resolve: (data: any[]) => void) => {
    if (node.level === 0) {
      return resolve(menuData.value)
    }
    const children = node.data.children || []
    resolve(children)
  }

  const handleClose = () => {
    checkStrictly.value = true
    visible.value = false
    treeRef.value?.setCheckedKeys([])
    menuData.value = []
    checkedMenuIds.value = []
  }

  const savePermission = async () => {
    const tree = treeRef.value
    if (!tree) return

    const checkedKeys = tree.getCheckedKeys()
    const halfCheckedKeys = tree.getHalfCheckedKeys()
    const allSelectedKeys = [...checkedKeys, ...halfCheckedKeys]

    saveLoading.value = true
    try {
      await fetchUpdateRoleMenu({
        roleId: String(props.roleData?.id),
        menuIdList: allSelectedKeys
      })
      ElMessage.success('权限保存成功')
      emit('success')
      handleClose()
    } finally {
      saveLoading.value = false
    }
  }

  const toggleExpandAll = () => {
    const tree = treeRef.value
    if (!tree) return

    const nodes = tree.store.nodesMap
    Object.values(nodes).forEach((node: any) => {
      node.expanded = !isExpandAll.value
    })

    isExpandAll.value = !isExpandAll.value
  }

  const toggleSelectAll = () => {
    const tree = treeRef.value
    if (!tree) return

    if (!isSelectAll.value) {
      const allKeys = getAllNodeKeys(menuData.value)
      tree.setCheckedKeys(allKeys)
    } else {
      tree.setCheckedKeys([])
    }

    isSelectAll.value = !isSelectAll.value
  }

  const getAllNodeKeys = (nodes: any[]): string[] => {
    const keys: string[] = []
    const traverse = (nodeList: any[]) => {
      nodeList.forEach((node) => {
        if (node.id) keys.push(String(node.id))
        if (node.children?.length) traverse(node.children)
      })
    }
    traverse(nodes)
    return keys
  }
</script>
