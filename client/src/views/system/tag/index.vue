<template>
  <div class="tag-page art-full-height">
    <!-- 搜索栏 -->
    <TagSearch v-model="searchForm" @search="handleSearch" @reset="handleReset"></TagSearch>

    <ElCard class="art-table-card">
      <!-- 表格头部 -->
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="getTagList">
        <template #left>
          <ElButton v-if="hasPermission('api:tag:add')" @click="showDialog('add')" v-ripple
            >新增标签</ElButton
          >
        </template>
      </ArtTableHeader>

      <!-- 表格 -->
      <ArtTable
        ref="tableRef"
        row-key="id"
        :loading="loading"
        :columns="columns"
        :data="filteredData"
      >
      </ArtTable>

      <!-- 标签弹窗 -->
      <TagDialog
        v-model:visible="dialogVisible"
        :type="dialogType"
        :tag-data="currentTagData"
        @submit="getTagList"
      />

      <!-- 用户抽屉 -->
      <TagEmployeeDrawer
        v-model:visible="drawerVisible"
        :tag-data="currentTagData"
        @submit="getTagList"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import { fetchTagList, fetchDeleteTag } from '@/api/tag'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import TagSearch from './modules/tag-search.vue'
  import TagDialog from './modules/tag-dialog.vue'
  import TagEmployeeDrawer from './modules/tag-employee-drawer.vue'
  import { DialogType } from '@/types'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'Tag' })

  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  type TagListItem = Api.SystemManage.TagListItem

  const loading = ref(false)
  const tableRef = ref()

  const dialogType = ref<DialogType>('add')
  const dialogVisible = ref(false)
  const currentTagData = ref<Partial<TagListItem>>({})

  const drawerVisible = ref(false)

  const searchForm = ref({
    tagName: ''
  })

  const tableData = ref<TagListItem[]>([])

  // 过滤后的数据（前端筛选）
  const filteredData = computed(() => {
    if (!searchForm.value.tagName) return tableData.value
    return tableData.value.filter((item) =>
      item.tagName.toLowerCase().includes(searchForm.value.tagName.toLowerCase())
    )
  })

  const getTagList = async (): Promise<void> => {
    loading.value = true
    try {
      const res = await fetchTagList({ tagName: searchForm.value.tagName || undefined })
      tableData.value = res || []
    } catch (error) {
      throw error instanceof Error ? error : new Error('获取标签列表失败')
    } finally {
      loading.value = false
    }
  }

  const handleSearch = (params: { tagName?: string }) => {
    searchForm.value.tagName = params.tagName || ''
    getTagList()
  }

  const handleReset = () => {
    searchForm.value.tagName = ''
    getTagList()
  }

  const { columnChecks, columns } = useTableColumns(() => [
    {
      prop: 'tagName',
      label: '标签名称',
      minWidth: 180
    },
    {
      prop: 'operation',
      label: '操作',
      width: 220,
      fixed: 'right',
      formatter: (row: TagListItem) =>
        h('div', [
          h(ArtButtonTable, {
            type: 'view',
            text: '用户',
            onClick: () => showDrawer(row)
          }),
          hasPermission('api:tag:update') &&
            h(ArtButtonTable, {
              type: 'edit',
              onClick: () => showDialog('edit', row)
            }),
          hasPermission('api:tag:delete') &&
            h(ArtButtonTable, {
              type: 'delete',
              onClick: () => deleteTag(row)
            })
        ])
    }
  ])

  const showDialog = (type: DialogType, row?: TagListItem): void => {
    dialogType.value = type
    currentTagData.value = row || {}
    nextTick(() => {
      dialogVisible.value = true
    })
  }

  const showDrawer = (row: TagListItem): void => {
    currentTagData.value = row
    nextTick(() => {
      drawerVisible.value = true
    })
  }

  const deleteTag = (row: TagListItem): void => {
    ElMessageBox.confirm('确定要删除该标签吗？', '删除标签', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      try {
        await fetchDeleteTag(row.id)
        ElMessage.success('删除成功')
        getTagList()
      } catch {
        ElMessage.error('删除失败')
      }
    })
  }

  onMounted(() => {
    getTagList()
  })
</script>
