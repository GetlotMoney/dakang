<!-- 设备中控——设备列表（一期 5+1 之一，REQ-020/025/028/029 / MVP 必需） -->
<template>
  <div class="device-page art-full-height">
    <BusinessModuleNav module-key="device" />

    <!-- 搜索栏 -->
    <DeviceSearch v-model="searchForm" @search="handleSearch" @reset="handleReset"></DeviceSearch>

    <ElCard class="art-table-card">
      <!-- 表格头部 -->
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElButton v-if="hasPermission('device:device:add')" @click="showDialog('add')" v-ripple
            >新增设备</ElButton
          >
        </template>
      </ArtTableHeader>

      <!-- 表格 -->
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

      <!-- 新增/编辑弹窗 -->
      <DeviceDialog
        v-model:visible="dialogVisible"
        :type="dialogType"
        :device-data="currentRow"
        @submit="refreshData"
      />

      <!-- 下发指令弹窗 -->
      <CommandSendDialog
        v-model:visible="sendVisible"
        :device-id="currentRow.id"
        :device-no="currentRow.deviceNo"
        :device-name="currentRow.deviceName"
        @submit="refreshData"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchDevicePage, fetchDeleteDevice, type DeviceItem } from '@/api/device'
  import { ElMessage, ElMessageBox, ElTag } from 'element-plus'
  import DeviceSearch from './modules/device-search.vue'
  import DeviceDialog from './modules/device-dialog.vue'
  import CommandSendDialog from './modules/command-send-dialog.vue'
  import { DialogType } from '@/types'
  import { useUserStore } from '@/store/modules/user'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'

  defineOptions({ name: 'Device' })

  const router = useRouter()
  const route = useRoute()

  // 权限判断函数
  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  // 弹窗
  const dialogType = ref<DialogType>('add')
  const dialogVisible = ref(false)
  const sendVisible = ref(false)
  const currentRow = ref<Partial<DeviceItem>>({})

  // 搜索表单（可选属性与 DeviceSearch 的 SearchForm 保持同构）
  interface DeviceSearchForm {
    deviceNo?: string
    deviceName?: string
    stationId?: number
    onlineStatus?: number
    runStatus?: number
  }
  const readQueryValue = (value: unknown): string | undefined => {
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' && raw.trim() ? raw : undefined
  }

  const readQueryNumber = (value: unknown): number | undefined => {
    const raw = readQueryValue(value)
    if (!raw) return undefined
    const parsed = Number(raw)
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
  }

  const readDeviceQuery = (): DeviceSearchForm => ({
    deviceNo: readQueryValue(route.query.deviceNo),
    deviceName: readQueryValue(route.query.deviceName),
    stationId: readQueryNumber(route.query.stationId),
    onlineStatus: readQueryNumber(route.query.onlineStatus),
    runStatus: readQueryNumber(route.query.runStatus)
  })

  const searchForm = ref<DeviceSearchForm>(readDeviceQuery())

  // 在线状态：1在线 2离线 3未激活
  const onlineTagType = (v: number) => (v === 1 ? 'success' : v === 2 ? 'danger' : 'info')
  const onlineLabel = (v: number) => (v === 1 ? '在线' : v === 2 ? '离线' : '未激活')
  // 运行状态：1空闲 2出水中 3故障 4维护中 5锁机
  const runTagType = (v: number) =>
    v === 1 ? 'success' : v === 2 ? 'primary' : v === 3 ? 'danger' : v === 4 ? 'warning' : 'info'
  const runLabel = (v: number) =>
    ({ 1: '空闲', 2: '出水中', 3: '故障', 4: '维护中', 5: '锁机' })[v] || String(v)

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
      apiFn: fetchDevicePage,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      columnsFactory: () => [
        { prop: 'deviceNo', label: '设备编号', minWidth: 130 },
        { prop: 'deviceName', label: '设备名称', minWidth: 120 },
        { prop: 'deviceModel', label: '型号', minWidth: 100 },
        {
          prop: 'stationName',
          label: '所属水站',
          minWidth: 130,
          formatter: (row: DeviceItem) => row.stationName || '-'
        },
        {
          prop: 'ownerUserName',
          label: '机主',
          minWidth: 100,
          formatter: (row: DeviceItem) => row.ownerUserName || '未绑定'
        },
        {
          prop: 'onlineStatus',
          label: '在线状态',
          minWidth: 90,
          formatter: (row: DeviceItem) =>
            h(ElTag, { type: onlineTagType(row.onlineStatus) as any }, () =>
              onlineLabel(row.onlineStatus)
            )
        },
        {
          prop: 'runStatus',
          label: '运行状态',
          minWidth: 90,
          formatter: (row: DeviceItem) =>
            h(ElTag, { type: runTagType(row.runStatus) as any }, () => runLabel(row.runStatus))
        },
        {
          prop: 'lastHeartbeat',
          label: '最后心跳',
          minWidth: 150,
          formatter: (row: DeviceItem) => formatTime(row.lastHeartbeat)
        },
        {
          prop: 'outletCount',
          label: '出水口',
          minWidth: 80,
          formatter: (row: DeviceItem) => `${row.outletCount ?? 0} 个`
        },
        {
          prop: 'operation',
          label: '操作',
          width: 260,
          fixed: 'right',
          formatter: (row: DeviceItem) =>
            h('div', [
              h(ArtButtonTable, {
                type: 'view',
                onClick: () => router.push({ path: '/device/detail', query: { id: row.id } })
              }),
              hasPermission('device:command:send')
                ? h(ArtButtonTable, {
                    icon: 'ri:send-plane-line',
                    iconClass: 'bg-warning/12 text-warning',
                    onClick: () => showSendDialog(row)
                  })
                : null,
              hasPermission('device:device:update')
                ? h(ArtButtonTable, { type: 'edit', onClick: () => showDialog('edit', row) })
                : null,
              hasPermission('device:device:delete')
                ? h(ArtButtonTable, { type: 'delete', onClick: () => handleDelete(row) })
                : null
            ])
        }
      ]
    }
  })

  // keepAlive 页面再次被业务深链打开时，以当前 URL 恢复筛选，而不是沿用旧状态。
  watch(
    () => route.fullPath,
    () => {
      if (route.path !== '/device/index') return
      const query = readDeviceQuery()
      searchForm.value = query
      replaceSearchParams(query)
      void getData()
    }
  )

  /** varchar(14) 时间转展示格式 */
  function formatTime(time?: string) {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}:${time.slice(12, 14)}`
  }

  function showDialog(type: DialogType, row?: DeviceItem) {
    dialogType.value = type
    currentRow.value = row ? { ...row } : {}
    dialogVisible.value = true
  }

  function showSendDialog(row: DeviceItem) {
    currentRow.value = { ...row }
    sendVisible.value = true
  }

  function handleDelete(row: DeviceItem) {
    ElMessageBox.confirm(
      `确认删除设备「${row.deviceName}（${row.deviceNo}）」？出水口将一并删除。`,
      '删除确认',
      { type: 'warning' }
    ).then(async () => {
      await fetchDeleteDevice(row.id)
      ElMessage.success('删除成功')
      getData()
    })
  }

  function handleSearch(params: DeviceSearchForm) {
    replaceSearchParams({ ...params })
    void getData()
  }

  async function handleReset() {
    searchForm.value = {}
    replaceSearchParams({})
    if (Object.keys(route.query).length) {
      await router.replace({ path: route.path, query: {} })
    } else {
      await getData()
    }
  }
</script>

<style scoped lang="scss">
  .device-page {
    display: flex;
    flex-direction: column;
  }
</style>
