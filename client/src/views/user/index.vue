<!--
  C 端用户列表（一期 5+1 用户管理，REQ-018 一期口径：只读）。
  列表只承担"定位到人"：关键词/手机号/编号/状态/能力/注册时间把人筛出来，
  身份细节、水卡、订单、资金、关系与审计进入独立档案工作区，不往主表横向堆列。
-->
<template>
  <div class="user-page art-full-height">
    <BusinessModuleNav module-key="user" />

    <UserDetailDrawer
      v-if="profileUserId"
      :visible="true"
      :user-id="profileUserId"
      @update:visible="handleProfileVisible"
    />

    <template v-else>
      <UserSearch v-model="searchForm" @search="handleSearch" />

      <ElCard class="art-table-card">
        <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
          <template #left>
            <BusinessTableSummary :total="pagination.total" :page-size="data.length" unit="人" />
          </template>
          <template #right>
            <ArtExcelExport
              :data="userExportRows"
              :filename="userExportFilename"
              sheet-name="用户列表"
              type="primary"
              size="small"
              plain
              auto-index
            >
              <span class="export-button-content">
                <ArtSvgIcon icon="ri:file-excel-2-line" />
                导出当前页
              </span>
            </ArtExcelExport>
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
      </ElCard>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import { ElTag } from 'element-plus'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchWsUserPage, USER_CAPABILITY, type WsUserItem } from '@/api/user'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'
  import UserSearch from './modules/user-search.vue'
  import UserDetailDrawer from './modules/user-detail-drawer.vue'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import BusinessTableSummary from '@/components/business/business-table-summary/index.vue'
  import {
    currentPageExportFilename,
    currentPageExportRows,
    maskedPhoneForExport
  } from '@/utils/current-page-export'

  defineOptions({ name: 'WsUserList' })

  const route = useRoute()
  const router = useRouter()

  const readUserId = (value: unknown): string | undefined => {
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' && /^[1-9][0-9]*$/.test(raw) ? raw : undefined
  }

  // 档案里集中着一个人的号码、资产与交易痕迹，"能打开这个页面"与"能翻开某个人"分开授权；
  // 按钮隐藏只是少一次误点，真正的闸在服务端同名权限点上。
  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }
  const canViewProfile = computed(() => hasPermission('user:user:query'))

  const profileUserId = computed(() => readUserId(route.query.userId))

  const searchForm = ref({
    userName: undefined as string | undefined,
    // 客服来电场景手上通常只有手机号，而列表列是脱敏号：没有这个条件就只能翻页比对后四位
    userPhone: undefined as string | undefined,
    id: readUserId(route.query.userId),
    disabledFlag: undefined as number | undefined,
    capability: undefined as string | undefined,
    registerRange: undefined as string[] | undefined
  })

  const disabledFlagOptions = ref<{ label: string; value: number }[]>([])
  onMounted(async () => {
    disabledFlagOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.禁用状态))
  })

  const disabledFlagLabel = (v?: number) =>
    disabledFlagOptions.value.find((o) => o.value === v)?.label || '-'

  const capabilityLabel = (code: string) =>
    code === USER_CAPABILITY.owner ? '机主' : code === USER_CAPABILITY.courier ? '配送员' : code

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
      // registerRange 只是筛选控件的内部形态，提交前已拆成起止两个参数
      excludeParams: ['registerRange'],
      columnsFactory: () => [
        { prop: 'id', label: '用户ID', width: 110 },
        { prop: 'userName', label: '姓名', minWidth: 120 },
        {
          prop: 'userPhone',
          label: '手机号',
          minWidth: 130,
          formatter: (row: WsUserItem) => row.userPhone || '-'
        },
        {
          prop: 'capabilities',
          label: '能力',
          minWidth: 150,
          formatter: (row: WsUserItem) => {
            const list = row.capabilities || []
            if (!list.length) return h('span', '取水')
            return h(
              'div',
              { style: 'display:flex;gap:4px;flex-wrap:wrap' },
              list.map((code) =>
                h(
                  ElTag,
                  {
                    key: code,
                    size: 'small',
                    type: code === USER_CAPABILITY.owner ? 'primary' : 'success'
                  },
                  () => capabilityLabel(code)
                )
              )
            )
          }
        },
        {
          prop: 'disabledFlag',
          label: '状态',
          width: 130,
          formatter: (row: WsUserItem) => {
            const tags = [
              h(ElTag, { type: row.disabledFlag === 1 ? 'success' : 'danger' }, () =>
                disabledFlagLabel(row.disabledFlag)
              )
            ]
            // 注销与禁用是两件事：禁用可恢复，注销是账号终态。只显示其中一个会把已注销的人展示成"正常"。
            if (row.userStatus === 2) {
              tags.push(h(ElTag, { type: 'info', style: 'margin-left:6px' }, () => '已注销'))
            }
            return h('div', { style: 'display:flex;align-items:center' }, tags)
          }
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
          formatter: (row: WsUserItem) => {
            if (!canViewProfile.value) return h('span', '-')
            return h('div', [
              h(ArtButtonTable, {
                type: 'view',
                onClick: () => showProfile(row)
              })
            ])
          }
        }
      ]
    }
  })

  const userExportFilename = currentPageExportFilename('用户列表')
  const userExportRows = computed(() =>
    currentPageExportRows<WsUserItem>(data.value as WsUserItem[], [
      { header: '用户ID', value: (row) => row.id },
      { header: '姓名', value: (row) => row.userName || '' },
      { header: '手机号', value: (row) => maskedPhoneForExport(row.userPhone) },
      {
        header: '能力',
        value: (row) =>
          row.capabilities?.length
            ? row.capabilities.map((code) => capabilityLabel(code)).join('、')
            : '取水'
      },
      {
        header: '状态',
        value: (row) => (row.userStatus === 2 ? '已注销' : disabledFlagLabel(row.disabledFlag))
      },
      { header: '注册时间', value: (row) => formatTime(row.createTime) }
    ])
  )

  const handleSearch = (params: Record<string, any>) => {
    replaceSearchParams(params)
    void getData()
  }

  const showProfile = (row: WsUserItem): void => {
    void router.push({ path: route.path, query: { ...route.query, userId: row.id } })
  }

  const handleProfileVisible = (visible: boolean): void => {
    if (visible) return
    const { userId: _userId, ...query } = route.query
    void router.push({ path: route.path, query })
  }
</script>

<style scoped>
  .export-button-content {
    display: inline-flex;
    gap: 6px;
    align-items: center;
  }
</style>
