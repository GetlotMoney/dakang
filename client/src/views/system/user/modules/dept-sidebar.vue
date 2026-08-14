<template>
  <div class="dept-sidebar" :class="{ 'is-collapsed': isCollapsed, 'is-compact': compact }">
    <!-- 侧边栏内容 -->
    <div class="sidebar-content">
      <!-- 顶部操作栏 -->
      <div class="sidebar-header">
        <ElButton v-if="canAdd" type="primary" size="small" @click="handleAddDept()"
          >新增部门</ElButton
        >
        <div class="header-actions">
          <ElTooltip content="展开/收起" placement="bottom">
            <ElIcon class="action-icon" @click="toggleExpand">
              <FolderOpened v-if="isAllExpanded" />
              <Folder v-else />
            </ElIcon>
          </ElTooltip>
          <ElTooltip content="刷新" placement="bottom">
            <ElIcon class="action-icon" @click="handleRefresh">
              <Refresh />
            </ElIcon>
          </ElTooltip>
        </div>
      </div>

      <!-- 搜索框 -->
      <div class="search-box">
        <ElInput
          v-model="searchKeyword"
          placeholder="请输入部门名称"
          :prefix-icon="Search"
          clearable
        />
      </div>

      <!-- 部门树 -->
      <div class="dept-tree-container" v-loading="loading">
        <ElScrollbar>
          <ElTree
            ref="treeRef"
            :data="filteredDeptTree"
            :props="treeProps"
            node-key="id"
            :default-expand-all="isAllExpanded"
            :expand-on-click-node="false"
            :filter-node-method="filterNode"
            :loading="loading"
            @node-click="handleNodeClick"
          >
            <template #default="{ node, data }">
              <div class="dept-node">
                <span class="dept-name">{{ node.label }}</span>
                <ElPopover
                  v-if="canAdd || canUpdate || canDelete"
                  placement="right-start"
                  :width="120"
                  trigger="click"
                  @show="currentDeptId = data.id"
                >
                  <template #reference>
                    <span class="operation-btn" @click.stop>
                      操作
                      <ElIcon class="arrow-icon"><CaretBottom /></ElIcon>
                    </span>
                  </template>
                  <div class="popover-actions" @click.stop>
                    <div v-if="canAdd" class="action-item" @click="handleAddSubDept(data)">
                      <ElIcon><Plus /></ElIcon>
                      <span>新增</span>
                    </div>
                    <div v-if="canUpdate" class="action-item" @click="handleEditDept(data)">
                      <ElIcon><Edit /></ElIcon>
                      <span>编辑</span>
                    </div>
                    <div
                      v-if="canDelete"
                      class="action-item danger"
                      @click="handleDeleteDept(data)"
                    >
                      <ElIcon><Delete /></ElIcon>
                      <span>删除</span>
                    </div>
                  </div>
                </ElPopover>
              </div>
            </template>
          </ElTree>
        </ElScrollbar>
      </div>
    </div>

    <!-- 右侧展开收起按钮 -->
    <button
      type="button"
      class="collapse-trigger"
      :aria-label="isCollapsed ? '展开部门筛选' : '收起部门筛选'"
      @click="toggleCollapse"
    >
      <ElIcon class="collapse-icon">
        <DArrowLeft />
      </ElIcon>
    </button>

    <!-- 部门弹窗 -->
    <DeptDialog
      v-model:visible="dialogVisible"
      :type="dialogType"
      :dept-data="currentDept"
      :dept-tree="deptTree"
      @submit="handleDialogSubmit"
    />
  </div>
</template>

<script setup lang="ts">
  import {
    Refresh,
    Search,
    Plus,
    Edit,
    Delete,
    DArrowLeft,
    DArrowRight,
    CaretBottom,
    FolderOpened,
    Folder
  } from '@element-plus/icons-vue'
  import { ElMessageBox, ElMessage } from 'element-plus'
  import { fetchGetDeptTree, fetchDeleteDept } from '@/api/dept'
  import DeptDialog from './dept-dialog.vue'

  interface Emits {
    (e: 'select', deptId: string | undefined): void
    (e: 'refresh'): void
    (e: 'loaded', items: { id: string; deptName: string }[]): void
  }

  interface Props {
    compact?: boolean
    canAdd?: boolean
    canUpdate?: boolean
    canDelete?: boolean
  }

  const props = withDefaults(defineProps<Props>(), {
    compact: false,
    canAdd: false,
    canUpdate: false,
    canDelete: false
  })
  const emit = defineEmits<Emits>()

  type DeptListItem = Api.SystemManage.DeptListItem

  const treeRef = ref()
  const searchKeyword = ref('')
  const isAllExpanded = ref(true)
  const isCollapsed = ref(false)
  const loading = ref(false)
  const deptTree = ref<DeptListItem[]>([])
  const currentDeptId = ref<string>()
  const selectedDeptId = ref<string>()

  const dialogVisible = ref(false)
  const dialogType = ref<'add' | 'edit'>('add')
  const currentDept = ref<Partial<DeptListItem>>({})

  const treeProps = {
    label: 'deptName',
    children: 'children'
  }

  const flattenDeptTree = (nodes: DeptListItem[]): { id: string; deptName: string }[] =>
    nodes.flatMap((node) => [
      { id: node.id, deptName: node.deptName },
      ...flattenDeptTree(node.children || [])
    ])

  const filteredDeptTree = computed(() => {
    if (!searchKeyword.value) {
      return deptTree.value
    }
    return deptTree.value
  })

  const loadDeptTree = async () => {
    try {
      loading.value = true
      const data = await fetchGetDeptTree()
      deptTree.value = data || []
      emit('loaded', flattenDeptTree(deptTree.value))
    } catch {
      // error handled by http
    } finally {
      loading.value = false
    }
  }

  watch(searchKeyword, (val) => {
    treeRef.value?.filter(val)
  })

  const filterNode = (value: string, data: any) => {
    if (!value) return true
    return data.deptName.includes(value)
  }

  const toggleExpand = () => {
    isAllExpanded.value = !isAllExpanded.value
    const nodes = treeRef.value?.store.nodesMap || {}
    Object.values(nodes).forEach((node: any) => {
      node.expanded = isAllExpanded.value
    })
  }

  const toggleCollapse = () => {
    isCollapsed.value = !isCollapsed.value
  }

  watch(
    () => props.compact,
    (compact) => {
      if (compact) isCollapsed.value = true
    },
    { immediate: true }
  )

  const handleRefresh = () => {
    loadDeptTree()
  }

  const handleAddDept = () => {
    dialogType.value = 'add'
    currentDept.value = {}
    dialogVisible.value = true
  }

  const handleAddSubDept = (data: DeptListItem) => {
    dialogType.value = 'add'
    currentDept.value = { deptParentId: data.id }
    dialogVisible.value = true
  }

  const handleEditDept = (data: DeptListItem) => {
    dialogType.value = 'edit'
    currentDept.value = data
    dialogVisible.value = true
  }

  const handleDeleteDept = (data: DeptListItem) => {
    ElMessageBox.confirm(`确定要删除部门"${data.deptName}"吗？`, '删除部门', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      try {
        await fetchDeleteDept(data.id)
        ElMessage.success('删除成功')
        loadDeptTree()
        emit('refresh')
      } catch {
        ElMessage.error('删除失败')
      }
    })
  }

  const handleDialogSubmit = () => {
    loadDeptTree()
    emit('refresh')
  }

  // 获取扁平化的部门列表（用于用户表单下拉）
  const getFlatDeptList = () => {
    return flattenDeptTree(deptTree.value)
  }

  // 点击树节点选中/取消选中
  const handleNodeClick = (data: DeptListItem) => {
    if (selectedDeptId.value === data.id) {
      selectedDeptId.value = undefined
      emit('select', undefined)
    } else {
      selectedDeptId.value = data.id
      emit('select', data.id)
    }
  }

  const clearSelection = () => {
    selectedDeptId.value = undefined
    treeRef.value?.setCurrentKey(null)
  }

  onMounted(() => {
    loadDeptTree()
  })

  defineExpose({
    loadDeptTree,
    deptTree,
    getFlatDeptList,
    clearSelection
  })
</script>

<style scoped lang="scss">
  .dept-sidebar {
    position: relative;
    flex: 0 0 260px;
    width: 260px;
    display: flex;
    flex-direction: column;
    min-width: 0;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    transition: width 0.3s ease;

    &.is-collapsed {
      flex-basis: 20px;
      width: 20px;
      background: transparent;
      border-color: transparent;

      .sidebar-content {
        display: none;
      }

      .collapse-trigger {
        right: 0;
        border-radius: 0 4px 4px 0;
        background: var(--el-fill-color-light);

        .collapse-icon {
          transform: rotate(180deg);
        }
      }
    }

    &.is-compact {
      flex: 0 0 20px;
      width: 20px;
      background: transparent;
      border-color: transparent;

      &:not(.is-collapsed) {
        .sidebar-content {
          position: absolute;
          z-index: 12;
          top: 0;
          left: 0;
          width: 280px;
          height: 100%;
          overflow: hidden;
          background: var(--el-bg-color);
          border: 1px solid var(--el-border-color);
          border-radius: 8px;
          box-shadow: var(--el-box-shadow-dark);
        }

        .collapse-trigger {
          right: auto;
          left: 266px;
        }
      }
    }
  }

  .collapse-trigger {
    position: absolute;
    right: -14px;
    top: 50%;
    transform: translateY(-50%);
    width: 14px;
    height: 48px;
    background: var(--el-fill-color-light);
    border: 1px solid var(--el-border-color-lighter);
    border-left: none;
    border-radius: 0 4px 4px 0;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    padding: 0;
    color: inherit;
    z-index: 10;
    transition: all 0.3s ease;

    &:hover {
      background: var(--el-color-primary-light-8);

      .collapse-icon {
        color: var(--el-color-primary);
      }
    }
  }

  .collapse-icon {
    font-size: 12px;
    color: var(--el-text-color-regular);
    transition: transform 0.3s ease;
  }

  .sidebar-content {
    display: flex;
    flex-direction: column;
    height: 100%;
  }

  .sidebar-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px;
    border-bottom: 1px solid var(--el-border-color-lighter);
    gap: 8px;
  }

  .header-actions {
    display: flex;
    gap: 8px;
  }

  .action-icon {
    font-size: 20px;
    color: var(--el-text-color-regular);
    cursor: pointer;
    padding: 4px;
    border-radius: 4px;
    transition: all 0.2s;

    &:hover {
      color: var(--el-color-primary);
      background: var(--el-fill-color-light);
    }
  }

  .search-box {
    padding: 12px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  .dept-tree-container {
    flex: 1;
    overflow: hidden;
    padding: 8px 0;
  }

  .dept-node {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex: 1;
    width: 100%;
    padding-right: 4px;
  }

  .dept-name {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .operation-btn {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    font-size: 13px;
    color: var(--el-color-primary);
    cursor: pointer;
    padding: 2px 6px;
    border-radius: 4px;
    transition: all 0.2s;
    flex-shrink: 0;

    &:hover {
      background: var(--el-color-primary-light-9);
    }
  }

  .arrow-icon {
    font-size: 12px;
  }

  .popover-actions {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .action-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    cursor: pointer;
    border-radius: 4px;
    font-size: 13px;
    transition: all 0.2s;

    &:hover {
      background: var(--el-fill-color-light);
    }

    &.danger {
      color: var(--el-color-danger);

      &:hover {
        background: var(--el-color-danger-light-9);
      }
    }
  }

  :deep(.el-tree) {
    background: transparent;

    .el-tree-node__content {
      height: 32px;
    }

    .el-tree-node.is-current > .el-tree-node__content {
      background-color: var(--el-color-primary-light-9);
      color: var(--el-color-primary);
    }
  }
</style>
