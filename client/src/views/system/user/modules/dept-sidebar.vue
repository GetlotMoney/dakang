<template>
  <div class="dept-sidebar" :class="{ 'is-collapsed': isCollapsed }">
    <!-- 侧边栏内容 -->
    <div class="sidebar-content">
      <!-- 顶部操作栏 -->
      <div class="sidebar-header">
        <ElButton type="primary" size="small" @click="handleAddDept()">新增部门</ElButton>
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
                    <div class="action-item" @click="handleAddSubDept(data)">
                      <ElIcon><Plus /></ElIcon>
                      <span>新增</span>
                    </div>
                    <div class="action-item" @click="handleEditDept(data)">
                      <ElIcon><Edit /></ElIcon>
                      <span>编辑</span>
                    </div>
                    <div class="action-item danger" @click="handleDeleteDept(data)">
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
    <div class="collapse-trigger" @click="toggleCollapse">
      <ElIcon class="collapse-icon">
        <DArrowLeft />
      </ElIcon>
    </div>

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
  }

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

  // 弹窗相关
  const dialogVisible = ref(false)
  const dialogType = ref<'add' | 'edit'>('add')
  const currentDept = ref<Partial<DeptListItem>>({})

  const treeProps = {
    label: 'deptName',
    children: 'children'
  }

  // 过滤后的部门树
  const filteredDeptTree = computed(() => {
    if (!searchKeyword.value) {
      return deptTree.value
    }
    return deptTree.value
  })

  // 加载部门树
  const loadDeptTree = async () => {
    try {
      loading.value = true
      const data = await fetchGetDeptTree()
      deptTree.value = data || []
    } catch {
      // error handled by http
    } finally {
      loading.value = false
    }
  }

  // 搜索过滤
  watch(searchKeyword, (val) => {
    treeRef.value?.filter(val)
  })

  // 树节点过滤
  const filterNode = (value: string, data: any) => {
    if (!value) return true
    return data.deptName.includes(value)
  }

  // 展开/收起切换
  const toggleExpand = () => {
    isAllExpanded.value = !isAllExpanded.value
    const nodes = treeRef.value?.store.nodesMap || {}
    Object.values(nodes).forEach((node: any) => {
      node.expanded = isAllExpanded.value
    })
  }

  // 侧边栏展开/收起
  const toggleCollapse = () => {
    isCollapsed.value = !isCollapsed.value
  }

  // 刷新
  const handleRefresh = () => {
    loadDeptTree()
  }

  // 新增顶级部门
  const handleAddDept = () => {
    dialogType.value = 'add'
    currentDept.value = {}
    dialogVisible.value = true
  }

  // 新增子部门
  const handleAddSubDept = (data: DeptListItem) => {
    dialogType.value = 'add'
    currentDept.value = { deptParentId: data.id }
    dialogVisible.value = true
  }

  // 编辑部门
  const handleEditDept = (data: DeptListItem) => {
    dialogType.value = 'edit'
    currentDept.value = data
    dialogVisible.value = true
  }

  // 删除部门
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

  // 弹窗提交
  const handleDialogSubmit = () => {
    loadDeptTree()
    emit('refresh')
  }

  // 获取扁平化的部门列表（用于用户表单下拉）
  const getFlatDeptList = () => {
    const flatten = (nodes: DeptListItem[]): { id: string; deptName: string }[] => {
      return nodes.flatMap((node) => [
        { id: node.id, deptName: node.deptName },
        ...flatten(node.children || [])
      ])
    }
    return flatten(deptTree.value)
  }

  // 点击树节点选中/取消选中
  const handleNodeClick = (data: DeptListItem) => {
    if (selectedDeptId.value === data.id) {
      // 取消选中
      selectedDeptId.value = undefined
      emit('select', undefined)
    } else {
      selectedDeptId.value = data.id
      emit('select', data.id)
    }
  }

  // 清除选中
  const clearSelection = () => {
    selectedDeptId.value = undefined
    treeRef.value?.setCurrentKey(null)
  }

  // 初始化时加载部门树
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
    width: 260px;
    display: flex;
    flex-direction: column;
    background: #fff;
    border-right: 1px solid var(--el-border-color-lighter);
    transition: width 0.3s ease;

    &.is-collapsed {
      width: 20px;

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
