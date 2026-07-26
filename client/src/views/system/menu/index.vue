<!-- 菜单管理页面 -->
<template>
  <div class="menu-page art-full-height">
    <ElCard class="art-table-card">
      <!-- 表格头部 -->
      <ArtTableHeader
        :showZebra="false"
        :loading="loading"
        v-model:columns="columnChecks"
        @refresh="handleRefresh"
      >
        <template #left>
          <ElButton v-if="hasPermission('api:menu:add')" @click="handleAddMenu" v-ripple>
            添加菜单
          </ElButton>
          <ElButton @click="toggleExpand" v-ripple>
            {{ isExpanded ? '收起' : '展开' }}
          </ElButton>
        </template>
      </ArtTableHeader>

      <ArtTable
        ref="tableRef"
        rowKey="id"
        :loading="loading"
        :columns="columns"
        :data="tableData"
        :stripe="false"
        :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
        :default-expand-all="false"
      />
    </ElCard>

    <!-- 菜单弹窗 -->
    <MenuDialog
      v-model:visible="dialogVisible"
      :editData="editData"
      :parentId="parentId"
      :menuTypeOptions="menuTypeOptions"
      :menuTreeData="menuTreeData"
      :submitLoading="submitLoading"
      @submit="handleSubmit"
    />
  </div>
</template>

<script setup lang="ts">
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import MenuDialog from './modules/menu-dialog.vue'
  import {
    fetchGetMenuList,
    fetchSaveMenu,
    fetchUpdateMenu,
    fetchDeleteMenu
  } from '@/api/system-manage'
  import { fetchDictByTypes } from '@/utils/dict'
  import { ElTag, ElMessageBox, ElMessage } from 'element-plus'
  import ArtSvgIcon from '@/components/core/base/art-svg-icon/index.vue'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'Menus' })

  // 权限判断函数
  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  // 状态管理
  const loading = ref(false)
  const isExpanded = ref(false)
  const tableRef = ref()

  // 弹窗相关
  const dialogVisible = ref(false)
  const editData = ref<any>(null)
  const parentId = ref(0)
  const submitLoading = ref(false)

  // 字典数据
  const menuTypeOptions = ref<{ label: string; value: number }[]>([])

  const menuTreeData = computed(() => {
    const buildTree = (items: any[]): any[] => {
      return items.map((item) => {
        const childCount = item.children?.length || 0
        const label = childCount > 0 ? `${item.menuName} (${childCount})` : item.menuName
        return {
          label,
          value: item.id,
          children: childCount > 0 ? buildTree(item.children) : undefined
        }
      })
    }
    const topLevelCount = tableData.value?.length || 0
    return [
      {
        label: topLevelCount > 0 ? `主类目 (${topLevelCount})` : '主类目',
        value: 0,
        children: buildTree(tableData.value || [])
      }
    ]
  })

  onMounted(async () => {
    getMenuList()
    const dictList = await fetchDictByTypes(['50'])
    for (const dict of dictList) {
      if (dict.dictType === '50') {
        menuTypeOptions.value = dict.dictDataList.map((d: any) => ({
          label: d.dictLabel,
          value: Number(d.dictValue)
        }))
      }
    }
  })

  /**
   * 获取菜单列表数据
   */
  const getMenuList = async (): Promise<void> => {
    loading.value = true

    try {
      const list = await fetchGetMenuList()
      tableData.value = list
    } catch (error) {
      throw error instanceof Error ? error : new Error('获取菜单失败')
    } finally {
      loading.value = false
    }
  }

  // 表格数据
  const tableData = ref<any[]>([])

  // 表格列配置
  const { columnChecks, columns } = useTableColumns(() => [
    {
      prop: 'menuName',
      label: '菜单名称',
      minWidth: 200,
      formatter: (row: any) => {
        const children = [h('span', {}, row.menuName || '')]
        if (row.menuIcon) {
          children.unshift(
            h(ArtSvgIcon, {
              icon: row.menuIcon,
              class: 'text-base text-g-500'
            })
          )
        }
        return h('span', { class: 'inline-flex items-center gap-1' }, children)
      }
    },
    {
      prop: 'menuSort',
      label: '排序',
      width: 70
    },
    {
      prop: 'menuType',
      label: '菜单类型',
      formatter: (row: any) => {
        const option = menuTypeOptions.value.find((o) => o.value === row.menuType)
        const text = option?.label || '未知'
        const typeMap: Record<number, string> = { 1: 'info', 2: 'primary', 3: 'warning' }
        const type = typeMap[row.menuType] || 'info'
        return h(ElTag, { type: type as any }, () => text)
      }
    },
    {
      prop: 'menuPath',
      label: '路由',
      minWidth: 150,
      formatter: (row: any) => {
        return row.menuPath || ''
      }
    },
    {
      prop: 'menuComponent',
      label: '组件',
      minWidth: 160,
      formatter: (row: any) => row.menuComponent || ''
    },
    {
      prop: 'menuWebPerms',
      label: '权限标识',
      minWidth: 160,
      formatter: (row: any) => row.menuWebPerms || ''
    },
    {
      prop: 'menuVisibleFlag',
      label: '显示状态',
      width: 90,
      formatter: (row: any) => {
        const text = row.menuVisibleFlag === 1 ? '显示' : '隐藏'
        const type = row.menuVisibleFlag === 1 ? 'success' : 'info'
        return h(ElTag, { type: type as any }, () => text)
      }
    },
    {
      prop: 'menuDisabledFlag',
      label: '状态',
      width: 80,
      formatter: (row: any) => {
        const text = row.menuDisabledFlag === 1 ? '正常' : '禁用'
        const type = row.menuDisabledFlag === 1 ? 'success' : 'danger'
        return h(ElTag, { type: type as any }, () => text)
      }
    },
    {
      prop: 'operation',
      label: '操作',
      width: 180,
      align: 'right',
      fixed: 'right',
      formatter: (row: any) => {
        const buttonStyle = { style: 'text-align: right' }
        return h('div', buttonStyle, [
          hasPermission('api:menu:add') &&
            h(ArtButtonTable, {
              type: 'add',
              onClick: () => handleAddChild(row)
            }),
          hasPermission('api:menu:update') &&
            h(ArtButtonTable, {
              type: 'edit',
              onClick: () => handleEdit(row)
            }),
          hasPermission('api:menu:delete') &&
            h(ArtButtonTable, {
              type: 'delete',
              onClick: () => handleDelete(row)
            })
        ])
      }
    }
  ])

  /**
   * 刷新菜单列表
   */
  const handleRefresh = (): void => {
    getMenuList()
  }

  /**
   * 添加菜单
   */
  const handleAddMenu = (): void => {
    editData.value = null
    parentId.value = 0
    dialogVisible.value = true
  }

  /**
   * 添加子菜单
   */
  const handleAddChild = (row: any): void => {
    editData.value = null
    parentId.value = row.id
    dialogVisible.value = true
  }

  /**
   * 编辑菜单
   */
  const handleEdit = (row: any): void => {
    editData.value = row
    parentId.value = row.menuParentId || 0
    dialogVisible.value = true
  }

  /**
   * 删除菜单
   */
  const handleDelete = async (row: any): Promise<void> => {
    try {
      await ElMessageBox.confirm(`确定要删除「${row.menuName}」吗？删除后无法恢复`, '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await fetchDeleteMenu(row.id)
      ElMessage.success('删除成功')
      getMenuList()
    } catch (error) {
      if (error !== 'cancel') {
        ElMessage.error('删除失败')
      }
    }
  }

  /**
   * 提交表单数据
   */
  const handleSubmit = async (formData: any): Promise<void> => {
    submitLoading.value = true
    try {
      if (formData.id) {
        await fetchUpdateMenu(formData)
      } else {
        await fetchSaveMenu(formData)
      }
      ElMessage.success(`${formData.id ? '编辑' : '新增'}成功`)
      dialogVisible.value = false
      getMenuList()
    } finally {
      submitLoading.value = false
    }
  }

  /**
   * 切换展开/收起所有菜单
   */
  const toggleExpand = (): void => {
    isExpanded.value = !isExpanded.value
    nextTick(() => {
      if (tableRef.value?.elTableRef && tableData.value) {
        const processRows = (rows: any[]) => {
          rows.forEach((row) => {
            if (row.children?.length) {
              tableRef.value.elTableRef.toggleRowExpansion(row, isExpanded.value)
              processRows(row.children)
            }
          })
        }
        processRows(tableData.value)
      }
    })
  }
</script>
