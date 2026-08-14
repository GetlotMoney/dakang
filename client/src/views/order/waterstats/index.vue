<!-- 按水种业务用量统计（S5）：只聚合现有订单与配送任务事实；生产量无权威数据源，显式标注未提供 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="deliveryOps" />

    <ElAlert
      type="info"
      :closable="false"
      show-icon
      title="统计来自订单与配送任务；工厂生产量暂无数据来源"
      class="mb-3"
    />

    <ElCard class="mb-3">
      <ElForm inline>
        <ElFormItem label="时间范围">
          <ElDatePicker
            v-model="dateRange"
            type="daterange"
            value-format="YYYYMMDD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
        </ElFormItem>
        <ElFormItem label="水站">
          <ElSelect v-model="stationId" clearable placeholder="全部水站" style="width: 180px">
            <ElOption
              v-for="s in stationOptions"
              :key="s.id"
              :label="s.stationName"
              :value="s.id"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="水种">
          <ElInput v-model="waterTypeName" clearable placeholder="水种名称" style="width: 140px" />
        </ElFormItem>
        <ElFormItem>
          <ElButton type="primary" :loading="loading" @click="load">查询</ElButton>
        </ElFormItem>
      </ElForm>
    </ElCard>

    <ElCard class="art-table-card" v-loading="loading">
      <ElTable :data="rows" border>
        <ElTableColumn prop="waterTypeName" label="水种" width="110" fixed />
        <ElTableColumn label="取水计划量(L)" width="120" align="right">
          <template #default="{ row }">{{ mlToLiter(row.planMl) }}</template>
        </ElTableColumn>
        <ElTableColumn label="取水实际量(L)" width="120" align="right">
          <template #default="{ row }">{{ mlToLiter(row.actualMl) }}</template>
        </ElTableColumn>
        <ElTableColumn label="不足退差量(L)" width="120" align="right">
          <template #default="{ row }">{{ mlToLiter(row.shortfallMl) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="abnormalCount" label="异常订单数" width="100" align="right" />
        <ElTableColumn prop="deliveryBuckets" label="配送桶数" width="90" align="right" />
        <ElTableColumn label="配送折算水量(L)" width="130" align="right">
          <template #default="{ row }">{{ mlToLiter(row.deliveryMl) }}</template>
        </ElTableColumn>
        <ElTableColumn label="补送桶数(不计收入)" width="140" align="right">
          <template #default="{ row }">
            {{ row.resendBuckets }}（{{ mlToLiter(row.resendMl) }}L）
          </template>
        </ElTableColumn>
        <ElTableColumn label="工厂生产量" width="100" align="center">
          <template #default>
            <ElTag type="info" size="small" effect="plain">未提供</ElTag>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { fetchWaterStatsSummary, type WaterStatsRow } from '@/api/finance'
  import { fetchStationPage } from '@/api/station'
  import { ElAlert, ElMessage } from 'element-plus'

  defineOptions({ name: 'OrderWaterStats' })

  const today = new Date()
  const lastWeek = new Date(today.getTime() - 6 * 86400_000)
  const fmt = (d: Date) =>
    `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`

  const dateRange = ref<[string, string]>([fmt(lastWeek), fmt(today)])
  const stationId = ref<string>()
  const waterTypeName = ref('')
  const loading = ref(false)
  const rows = ref<WaterStatsRow[]>([])
  const stationOptions = ref<{ id: string; stationName: string }[]>([])

  const mlToLiter = (ml: number) => (ml / 1000).toFixed(1)

  const load = async () => {
    if (!dateRange.value?.[0] || !dateRange.value?.[1]) {
      ElMessage.warning('请选择时间范围')
      return
    }
    loading.value = true
    try {
      rows.value = await fetchWaterStatsSummary({
        startTime: `${dateRange.value[0]}000000`,
        endTime: `${dateRange.value[1]}235959`,
        stationId: stationId.value || undefined,
        waterTypeName: waterTypeName.value.trim() || undefined
      })
    } finally {
      loading.value = false
    }
  }

  onMounted(async () => {
    const stations = await fetchStationPage({ current: 1, size: 999 })
    stationOptions.value = stations.list.map((s) => ({
      id: String(s.id),
      stationName: s.stationName
    }))
    await load()
  })
</script>
