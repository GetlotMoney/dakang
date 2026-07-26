<template>
  <ElDialog
    v-model="dialogVisible"
    title="分配角色"
    width="520px"
    align-center
    class="el-dialog-border"
    @closed="handleClosed"
  >
    <div v-loading="loading">
      <div class="role-section">
        <div class="section-title">选择角色</div>
        <ElCheckboxGroup v-model="selectedRoleIds" class="role-checkbox-group">
          <ElCheckbox
            v-for="item in roleOptions"
            :key="item.id"
            :value="item.id"
            :label="item.id"
            style="display: flex; margin-bottom: 12px"
          >
            {{ item.roleName }}
          </ElCheckbox>
        </ElCheckboxGroup>
        <ElEmpty v-if="!loading && roleOptions.length === 0" description="暂无可分配的角色" />
      </div>

      <!-- 菜单权限编辑区域 -->
      <div v-if="selectedRoleIds.length > 0" class="menu-section">
        <div class="section-title">菜单权限</div>
        <ElScrollbar height="50vh">
          <ElTree
            v-loading="menuLoading"
            ref="treeRef"
            :data="menuData"
            show-checkbox
            node-key="id"
            :default-expand-all="isExpandAll"
            :props="treeProps"
            :check-strictly="checkStrictly"
            :load="loadNode"
            lazy
          >
            <template #default="{ data }">
              <span>{{ data.menuName }}</span>
            </template>
          </ElTree>
          <ElEmpty v-if="!menuLoading && menuData.length === 0" description="暂无菜单数据" />
        </ElScrollbar>
      </div>
    </div>
    <template #footer>
      <ElButton v-if="selectedRoleIds.length > 0" @click="toggleExpandAll">
        {{ isExpandAll ? '全部收起' : '全部展开' }}
      </ElButton>
      <ElButton v-if="selectedRoleIds.length > 0" @click="toggleSelectAll" style="margin-left: 8px">
        {{ isSelectAll ? '取消全选' : '全部选择' }}
      </ElButton>
      <ElButton @click="dialogVisible = false">取消</ElButton>
      <ElButton type="primary" :loading="submitLoading" @click="handleSubmit">保存</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import {
    fetchGetRoleList,
    fetchAssignRole,
    fetchGetEmployeeRoleList,
    fetchGetMenuList,
    fetchGetRoleMenuIds,
    fetchUpdateRoleMenu
  } from '@/api/system-manage'
  import { ElMessage } from 'element-plus'

  interface Props {
    visible: boolean
    userData?: Partial<Api.SystemManage.UserListItem>
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
    (e: 'success'): void
  }

  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()

  const dialogVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const loading = ref(false)
  const submitLoading = ref(false)
  const menuLoading = ref(false)
  const treeRef = ref()
  const isExpandAll = ref(true)
  const isSelectAll = ref(false)
  const checkStrictly = ref(true)
  const roleOptions = ref<{ id: string; roleName: string }[]>([])
  const selectedRoleIds = ref<string[]>([])
  const menuData = ref<any[]>([])
  const roleMenuIdsMap = ref<Record<string, string[]>>({})
  // 是否已完成初始加载，用于区分初始赋值和用户手动操作
  const isInitialLoaded = ref(false)

  const treeProps = {
    children: 'children',
    label: 'menuName'
  }

  // 加载菜单列表
  const loadMenuList = async () => {
    const list = await fetchGetMenuList()
    const setDisabled = (items: any[]) => {
      items.forEach((item) => {
        item.disabled = true
        if (item.children?.length) {
          setDisabled(item.children)
        }
      })
    }
    setDisabled(list)
    menuData.value = list
  }

  // 懒加载子节点
  const loadNode = async (node: any, resolve: (data: any[]) => void) => {
    if (node.level === 0) {
      return resolve(menuData.value)
    }
    const children = node.data.children || []
    resolve(children)
  }

  // 加载选中角色的菜单权限
  const loadSelectedRolesMenus = async () => {
    checkStrictly.value = true
    treeRef.value?.setCheckedKeys([])
    if (selectedRoleIds.value.length === 0) {
      roleMenuIdsMap.value = {}
      return
    }

    menuLoading.value = true
    try {
      // 先加载菜单列表
      await loadMenuList()

      // 并行加载所有选中角色的菜单权限
      const promises = selectedRoleIds.value.map(async (roleId) => {
        const menuIds = await fetchGetRoleMenuIds(roleId)
        roleMenuIdsMap.value[roleId] = menuIds
      })
      await Promise.all(promises)

      // 设置树形控件的勾选状态
      nextTick(() => {
        // 只计算当前选中的角色的菜单权限
        const allMenuIds = selectedRoleIds.value.flatMap(
          (roleId) => roleMenuIdsMap.value[roleId] || []
        )
        const uniqueMenuIds = [...new Set(allMenuIds)]
        treeRef.value?.setCheckedKeys(uniqueMenuIds)
        checkStrictly.value = false
      })
    } finally {
      menuLoading.value = false
    }
  }

  const loadRoleData = async () => {
    if (!props.userData?.id) return
    loading.value = true
    isInitialLoaded.value = false
    try {
      const [roleRes, employeeRoleRes] = await Promise.all([
        fetchGetRoleList({}),
        fetchGetEmployeeRoleList(props.userData.id)
      ])

      roleOptions.value = (roleRes || []).map((item) => ({
        id: item.id,
        roleName: item.roleName
      }))

      selectedRoleIds.value = (employeeRoleRes || []).map((item) => item.id)

      // 加载选中角色的菜单权限
      await loadSelectedRolesMenus()

      // 标记初始加载完成
      isInitialLoaded.value = true
    } finally {
      loading.value = false
    }
  }

  // 获取当前树形控件选中的菜单 ID
  const getTreeCheckedMenuIds = () => {
    const tree = treeRef.value
    if (!tree) return []

    const checkedKeys = tree.getCheckedKeys()
    const halfCheckedKeys = tree.getHalfCheckedKeys()
    return [...checkedKeys, ...halfCheckedKeys]
  }

  const handleSubmit = async () => {
    if (!props.userData?.id) return
    submitLoading.value = true
    try {
      // 暂时不用编辑菜单权限
      // const menuIds = getTreeCheckedMenuIds()

      // // 循环调用接口，为每个选中的角色保存菜单权限
      // for (const roleId of selectedRoleIds.value) {
      //   await fetchUpdateRoleMenu({
      //     roleId,
      //     menuIdList: menuIds
      //   })
      // }

      // 更新用户的角色分配
      await fetchAssignRole({
        employeeId: props.userData.id,
        roleIdList: selectedRoleIds.value
      })

      ElMessage.success('保存成功')
      dialogVisible.value = false
      emit('success')
    } finally {
      submitLoading.value = false
    }
  }

  const handleClosed = () => {
    selectedRoleIds.value = []
    roleMenuIdsMap.value = {}
    menuData.value = []
    isExpandAll.value = true
    isSelectAll.value = false
    checkStrictly.value = true
    treeRef.value?.setCheckedKeys([])
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

  // 监听选中角色变化，重新加载菜单权限（仅用户手动操作时触发）
  watch(selectedRoleIds, async () => {
    if (dialogVisible.value && selectedRoleIds.value.length > 0 && isInitialLoaded.value) {
      checkStrictly.value = true
      await loadSelectedRolesMenus()
    }
  })

  watch(
    () => props.visible,
    (visible) => {
      if (visible) {
        loadRoleData()
      }
    }
  )
</script>

<style scoped>
  .role-checkbox-group {
    display: flex;
    flex-direction: column;
  }

  .role-section,
  .menu-section {
    margin-bottom: 16px;
  }

  .section-title {
    font-size: 14px;
    font-weight: 500;
    color: #303133;
    margin-bottom: 12px;
    padding-left: 4px;
  }

  .menu-section {
    border-top: 1px solid #ebeef5;
    padding-top: 16px;
  }
</style>
