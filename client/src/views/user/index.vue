<!-- 用户列表（一期 5+1 用户管理，REQ-018 一期口径：只读，详情抽屉聚合水卡/配送员准入） -->
<template>
  <div class="user-page art-full-height">
    <BusinessModuleNav module-key="user" />

    <UserSearch v-model="searchForm" @search="handleSearch" @reset="handleReset"></UserSearch>

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElText type="info" size="small"
            >一期口径：C 端用户由小程序注册产生，后台只读查询（REQ-018）</ElText
          >
        </template>
      </ArtTableHeader>

      <ArtTable
        row-key="id"
        :loading="loading"
        :columns="columns"
        :data="data"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>

      <!-- 详情抽屉（聚合：基础信息 + 水卡 + 配送员准入） -->
      <UserDetailDrawer v-model:visible="drawerVisible" :user-id="currentRow.id" />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import { ElTag } from 'element-plus'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchWsUserPage, type WsUserItem } from '@/api/user'
  import UserSearch from './modules/user-search.vue'
  import UserDetailDrawer from './modules/user-detail-drawer.vue'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'

  defineOptions({ name: 'WsUserList' })

  const drawerVisible = ref(false)
  const currentRow = ref<Partial<WsUserItem>>({})

  const searchForm = ref({
    userName: undefined as string | undefined,
    userPhone: undefined as string | undefined
  })

  /** varchar(14) 时间串 → 展示格式 */
  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}`
  }

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    resetSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchWsUserPage,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      columnsFactory: () => [
        { prop: 'id', label: '用户ID', width: 90 },
        { prop: 'userName', label: '姓名', minWidth: 120 },
        {
          prop: 'userGender',
          label: '性别',
          width: 80,
          formatter: (row: WsUserItem) =>
            h(ElTag, { type: row.userGender === 1 ? 'primary' : 'warning' }, () =>
              row.userGender === 1 ? '男' : row.userGender === 2 ? '女' : '-'
            )
        },
        { prop: 'userPhone', label: '手机号', minWidth: 130 },
        {
          prop: 'promoCode',
          label: '注册推送码',
          minWidth: 110,
          formatter: (row: WsUserItem) => row.promoCode || '-'
        },
        {
          prop: 'createTime',
          label: '注册时间',
          minWidth: 150,
          formatter: (row: WsUserItem) => formatTime(row.createTime)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 100,
          fixed: 'right',
          formatter: (row: WsUserItem) =>
            h('div', [
              h(ArtButtonTable, {
                type: 'view',
                onClick: () => showDrawer(row)
              })
            ])
        }
      ]
    }
  })

  const handleSearch = (params: Record<string, any>) => {
    replaceSearchParams(params)
    void getData()
  }

  const handleReset = () => {
    resetSearchParams()
  }

  const showDrawer = (row: WsUserItem): void => {
    currentRow.value = row
    nextTick(() => {
      drawerVisible.value = true
    })
  }
</script>
