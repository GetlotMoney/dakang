<!-- 告警中心（E2E-05 包D REQ-036/037）：全量告警分页 + 忽略/转工单处置；恢复只由设备事实驱动，PC 不提供人工标记恢复 -->
<template>
  <div class="alarm-page art-full-height">
    <BusinessModuleNav module-key="device" />

    <ArtSearchBar
      ref="searchBarRef"
      v-model="searchForm"
      :items="searchItems"
      auto-search
      @reset="handleReset"
      @search="handleSearch"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData" />

      <ArtTable
        row-key="id"
        :loading="loading"
        :columns="columns"
        :data="data"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      />

      <AlarmDetailDrawer
        v-model="drawerVisible"
        :alarm-id="currentId"
        :can-handle="canHandle"
        @handled="refreshData"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import { ElTag } from 'element-plus'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchAlarmPage, type AlarmItem } from '@/api/device'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import AlarmDetailDrawer from './modules/alarm-detail-drawer.vue'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'DeviceAlarm' })

  const userStore = useUserStore()
  const canHandle = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === 'device:alarm:handle')
  )

  const searchBarRef = ref()
  const drawerVisible = ref(false)
  const currentId = ref<number>()

  const searchForm = ref({
    alarmType: undefined as number | undefined,
    alarmStatus: undefined as number | undefined,
    alarmLevel: undefined as number | undefined
  })

  const alarmTypeOptions = ref<{ label: string; value: number }[]>([])
  const alarmStatusOptions = ref<{ label: string; value: number }[]>([])
  const levelOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses, levels] = await Promise.all([
      fetchDictOptions(DictTypeEnum.告警类型),
      fetchDictOptions(DictTypeEnum.告警状态),
      fetchDictOptions(DictTypeEnum.故障等级)
    ])
    alarmTypeOptions.value = toDictOptions(types)
    alarmStatusOptions.value = toDictOptions(statuses)
    levelOptions.value = toDictOptions(levels)
    getData()
  })

  const alarmTypeLabel = (v: number) =>
    alarmTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const alarmStatusLabel = (v: number) =>
    alarmStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const levelLabel = (v: number) =>
    levelOptions.value.find((o) => o.value === v)?.label || String(v)
  const levelTagType = (v: number) => (v === 3 ? 'danger' : v === 2 ? 'warning' : 'info')
  // 1待处理 2已转工单 3已忽略 4自动恢复
  const statusTagType = (v: number) =>
    v === 1 ? 'danger' : v === 2 ? 'primary' : v === 4 ? 'success' : 'info'

  const searchItems = computed(() => [
    {
      label: '告警类型',
      key: 'alarmType',
      type: 'select',
      placeholder: '请选择类型',
      clearable: true,
      options: alarmTypeOptions.value
    },
    {
      label: '告警状态',
      key: 'alarmStatus',
      type: 'select',
      placeholder: '请选择状态',
      clearable: true,
      options: alarmStatusOptions.value
    },
    {
      label: '告警等级',
      key: 'alarmLevel',
      type: 'select',
      placeholder: '请选择等级',
      clearable: true,
      options: levelOptions.value
    }
  ])

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
      apiFn: fetchAlarmPage,
      apiParams: {
        current: 1,
        size: 20,
        ...searchForm.value
      },
      immediate: false,
      columnsFactory: () => [
        { prop: 'id', label: '告警ID', width: 90 },
        {
          prop: 'deviceNo',
          label: '设备编号',
          minWidth: 140,
          formatter: (row: AlarmItem) => row.deviceNo || String(row.deviceId)
        },
        {
          prop: 'alarmType',
          label: '类型',
          minWidth: 100,
          formatter: (row: AlarmItem) => alarmTypeLabel(row.alarmType)
        },
        {
          prop: 'alarmLevel',
          label: '等级',
          width: 90,
          formatter: (row: AlarmItem) =>
            h(ElTag, { type: levelTagType(row.alarmLevel) as any, size: 'small' }, () =>
              levelLabel(row.alarmLevel)
            )
        },
        {
          prop: 'alarmContent',
          label: '内容',
          minWidth: 240,
          showOverflowTooltip: true
        },
        {
          prop: 'alarmStatus',
          label: '状态',
          width: 100,
          formatter: (row: AlarmItem) =>
            h(ElTag, { type: statusTagType(row.alarmStatus) as any, size: 'small' }, () =>
              alarmStatusLabel(row.alarmStatus)
            )
        },
        {
          prop: 'createTime',
          label: '产生时间',
          minWidth: 150,
          formatter: (row: AlarmItem) => formatTime(row.createTime)
        },
        {
          prop: 'recoverTime',
          label: '恢复时间',
          minWidth: 150,
          formatter: (row: AlarmItem) => formatTime(row.recoverTime)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 90,
          fixed: 'right',
          formatter: (row: AlarmItem) =>
            h(ArtButtonTable, {
              type: 'view',
              onClick: () => {
                currentId.value = row.id
                drawerVisible.value = true
              }
            })
        }
      ]
    }
  })

  function formatTime(time?: string) {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
  }

  function handleSearch() {
    replaceSearchParams({ current: 1, size: pagination.size, ...searchForm.value })
  }

  function handleReset() {
    searchForm.value = { alarmType: undefined, alarmStatus: undefined, alarmLevel: undefined }
    resetSearchParams()
  }
</script>

<style scoped lang="scss">
  .alarm-page {
    display: flex;
    flex-direction: column;
  }
</style>
