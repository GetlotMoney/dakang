<!-- 角色授权弹窗：菜单权限（能看到哪些页面）与操作权限（能执行哪些动作）分两个页签各授一次。

     此前是一棵混合树，且载入后把 check-strictly 置回 false：勾一个一级菜单会连带勾中
     其下全部功能点，再把半选父节点一并提交——「页面可见」在数据层直接等于「可执行全部写动作」，
     角色管理员没有任何办法只给查询不给执行。两个页签各自严格独立勾选，回显即入库原样。 -->
<template>
  <ElDialog
    v-model="visible"
    :title="dialogTitle"
    width="560px"
    align-center
    class="el-dialog-border"
    @close="handleClose"
  >
    <ElTabs v-model="activeTab">
      <ElTabPane label="菜单权限" name="menu" />
      <ElTabPane label="操作权限" name="point" />
    </ElTabs>

    <p class="permission-tip">勾选菜单不会同时授予操作权限</p>

    <ElScrollbar height="56vh">
      <ElTree
        v-show="activeTab === 'menu'"
        ref="menuTreeRef"
        v-loading="menuLoading"
        :data="menuTreeData"
        show-checkbox
        check-strictly
        node-key="id"
        :default-expand-all="true"
        :props="treeProps"
      >
        <template #default="{ data }">
          <span>{{ data.menuName }}</span>
        </template>
      </ElTree>

      <ElTree
        v-show="activeTab === 'point'"
        ref="pointTreeRef"
        v-loading="menuLoading"
        :data="pointTreeData"
        show-checkbox
        check-strictly
        node-key="id"
        :default-expand-all="true"
        :props="treeProps"
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

  /** 授权节点：id 恒为 string（菜单主键是 Long，前端一律不转数字）。 */
  interface PermissionNode {
    id: string
    menuName: string
    menuType: number
    /** 仅用于操作权限页签的分组节点：可展开、不可勾选 */
    disabled?: boolean
    children?: PermissionNode[]
  }

  /** 菜单类型：1 目录、2 菜单、3 功能点。功能点即「操作权限」，判据只认这一个字段。 */
  const MENU_TYPE = { CATALOG: 1, MENU: 2, POINT: 3 } as const

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

  const menuTreeRef = ref()
  const pointTreeRef = ref()
  const activeTab = ref<'menu' | 'point'>('menu')
  const isExpandAll = ref(true)
  const isSelectAll = ref(false)
  const menuLoading = ref(false)
  const saveLoading = ref(false)
  const menuTreeData = ref<PermissionNode[]>([])
  const pointTreeData = ref<PermissionNode[]>([])

  /** 节点类型与父子关系：保存时要据此拆分两个页签并补齐目录归属。 */
  const typeById = new Map<string, number>()
  const parentById = new Map<string, string>()

  const visible = computed({
    get: () => props.modelValue,
    set: (value) => emit('update:modelValue', value)
  })

  const dialogTitle = computed(() =>
    props.roleData?.roleName ? `分配权限 · ${props.roleData.roleName}` : '分配权限'
  )

  const treeProps = {
    children: 'children',
    label: 'menuName',
    disabled: 'disabled'
  }

  const activeTree = computed(() =>
    activeTab.value === 'menu' ? menuTreeRef.value : pointTreeRef.value
  )

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

  /** 索引整棵原始树：类型用于分栏，父指针用于保存时补齐目录。 */
  // 全选按钮只对当前页签生效，切页签必须回到「全部选择」，否则按钮文字会描述另一棵树的状态
  watch(activeTab, () => {
    isSelectAll.value = false
  })

  const indexNodes = (nodes: any[], parentId?: string) => {
    nodes.forEach((node) => {
      const id = String(node.id)
      typeById.set(id, Number(node.menuType))
      if (parentId) parentById.set(id, parentId)
      if (node.children?.length) indexNodes(node.children, id)
    })
  }

  /**
   * 菜单权限树：剔除全部功能点。
   * 功能点只要在这棵树里出现过一次，勾选联动就能把写动作一并带走。
   */
  const buildMenuTree = (nodes: any[]): PermissionNode[] =>
    nodes
      .filter((node) => Number(node.menuType) !== MENU_TYPE.POINT)
      .map((node) => ({
        id: String(node.id),
        menuName: node.menuName,
        menuType: Number(node.menuType),
        children: buildMenuTree(node.children || [])
      }))

  /**
   * 操作权限树：只有功能点可勾选，其上的目录与菜单降级为不可勾选的分组节点，
   * 用来说明这个动作属于哪个页面；没有功能点的分支整条不展示。
   */
  const buildPointTree = (nodes: any[]): PermissionNode[] => {
    const result: PermissionNode[] = []
    nodes.forEach((node) => {
      const id = String(node.id)
      const menuType = Number(node.menuType)
      if (menuType === MENU_TYPE.POINT) {
        result.push({ id, menuName: node.menuName, menuType })
        return
      }
      const children = buildPointTree(node.children || [])
      if (children.length > 0) {
        result.push({ id, menuName: node.menuName, menuType, disabled: true, children })
      }
    })
    return result
  }

  const loadRoleMenuIds = async () => {
    if (!props.roleData?.id) return
    const list = await fetchGetMenuList()
    typeById.clear()
    parentById.clear()
    indexNodes(list || [])
    menuTreeData.value = buildMenuTree(list || [])
    pointTreeData.value = buildPointTree(list || [])

    const ids = (await fetchGetRoleMenuIds(String(props.roleData.id))) || []
    const grantedIds = ids.map(String)
    // 严格模式下回显即入库原样：勾中的就是已授权的那些行，不再由联动推算
    const menuIds = grantedIds.filter((id) => typeById.get(id) !== MENU_TYPE.POINT)
    const pointIds = grantedIds.filter((id) => typeById.get(id) === MENU_TYPE.POINT)
    nextTick(() => {
      menuTreeRef.value?.setCheckedKeys(menuIds)
      pointTreeRef.value?.setCheckedKeys(pointIds)
    })
  }

  const handleClose = () => {
    visible.value = false
    menuTreeRef.value?.setCheckedKeys([])
    pointTreeRef.value?.setCheckedKeys([])
    menuTreeData.value = []
    pointTreeData.value = []
    typeById.clear()
    parentById.clear()
    activeTab.value = 'menu'
    isSelectAll.value = false
  }

  /** 收集某棵树里全部可勾选（未禁用）的节点 ID。 */
  const collectSelectableKeys = (nodes: PermissionNode[]): string[] => {
    const keys: string[] = []
    const traverse = (list: PermissionNode[]) => {
      list.forEach((node) => {
        if (!node.disabled) keys.push(node.id)
        if (node.children?.length) traverse(node.children)
      })
    }
    traverse(nodes)
    return keys
  }

  const savePermission = async () => {
    if (!menuTreeRef.value || !pointTreeRef.value) return

    const menuKeys: string[] = (menuTreeRef.value.getCheckedKeys() || []).map(String)
    const pointKeys: string[] = (pointTreeRef.value.getCheckedKeys() || []).map(String)
    const selected = new Set<string>([...menuKeys, ...pointKeys])

    // 勾了子菜单必须连带授权它所在的目录，否则菜单在侧栏无处挂载。
    // 目录行自身不带任何权限码，补上它不会多给出一个写动作；功能点刻意不向上补，
    // 「能执行」与「能看到」要各授各的。
    menuKeys.forEach((id) => {
      let parentId = parentById.get(id)
      while (parentId) {
        selected.add(parentId)
        parentId = parentById.get(parentId)
      }
    })

    saveLoading.value = true
    try {
      await fetchUpdateRoleMenu({
        roleId: String(props.roleData?.id),
        menuIdList: [...selected]
      })
      ElMessage.success('权限保存成功')
      emit('success')
      handleClose()
    } finally {
      saveLoading.value = false
    }
  }

  const toggleExpandAll = () => {
    const tree = activeTree.value
    if (!tree) return

    const nodes = tree.store.nodesMap
    Object.values(nodes).forEach((node: any) => {
      node.expanded = !isExpandAll.value
    })

    isExpandAll.value = !isExpandAll.value
  }

  /** 全选只作用于当前页签，且不会勾中分组节点。 */
  const toggleSelectAll = () => {
    const tree = activeTree.value
    if (!tree) return

    if (!isSelectAll.value) {
      const source = activeTab.value === 'menu' ? menuTreeData.value : pointTreeData.value
      tree.setCheckedKeys(collectSelectableKeys(source))
    } else {
      tree.setCheckedKeys([])
    }

    isSelectAll.value = !isSelectAll.value
  }
</script>

<style lang="scss" scoped>
  .permission-tip {
    margin: 0 0 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
</style>
