<!-- 分账比例配置（E2E-08 包E）：版本化——新增生效版本而非改历史；变更受 finance:config:edit 权限门。
     本轮只补读侧：把「当前生效」与「未来待生效」分开呈现，写入路径与既有版本化机制一个字未改。 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="finance" />

    <ElAlert
      type="warning"
      :closable="false"
      show-icon
      title="修改比例只影响生效后的新单"
      class="mb-3"
    />

    <!-- 六方整版计划（D-428）：六个比例一次给齐、整版校验、整版生效。
         公司份额恒为余数自动承接（含市场激励/公司运营/设备三个内部口径），不单独配置。 -->
    <ElCard class="art-table-card mb-3">
      <template #header>
        <ElSpace wrap>
          <span>分润计划（六方整版）</span>
          <span class="hint-text">运营中心三级为累计上限，实得按级差自动计算；公司份额为余数</span>
          <ElButton
            v-if="hasPermission('finance:config:edit')"
            type="primary"
            size="small"
            @click="openPlanDialog"
            v-ripple
            >发布新计划</ElButton
          >
        </ElSpace>
      </template>
      <ElTable
        v-if="planRows.length"
        :data="planRows"
        border
        size="small"
        row-key="id"
        v-loading="planLoading"
      >
        <ElTableColumn label="计划版本" prop="planVersion" width="170" />
        <ElTableColumn label="生效时间" min-width="170">
          <template #default="{ row }">{{ formatEffectTime(row.effectTime) }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="130">
          <!-- 与订单取版本同规则指认现行计划：多版并存时只有一版此刻管钱，
               一律标"生效"会让运营按旧版比例解释口径（V1 区块同型缺陷本轮已修，这里同口径） -->
          <template #default="{ row }">
            <ElTag v-if="row.planStatus === 3" type="info">停用</ElTag>
            <ElTag v-else-if="row.effectTime > businessNowStamp()" type="warning">未来待生效</ElTag>
            <ElTag v-else-if="row.id === currentPlanId" type="success">当前生效</ElTag>
            <ElTag v-else type="info" effect="plain">已被新版取代</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="备注" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.planRemark || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="比例明细" width="110">
          <template #default="{ row }">
            <ElButton link type="primary" @click="showPlanItems(row)">查看</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-else description="尚未发布分润计划" :image-size="60" />
    </ElCard>

    <!--
      当前生效版本＝同商品线同收款方中，生效时间已到且最晚的那一版（与订单取版本的规则一致）。
      判定必须拿到该范围的全部版本才成立：拿不全时如实说明并不出结论，绝不按半份数据推断现行比例。
    -->
    <ElCard class="art-table-card mb-3">
      <template #header>
        <ElSpace wrap>
          <span>当前生效版本</span>
          <span class="hint-text">下方列表按生效时间倒序，含历史与未来版本</span>
        </ElSpace>
      </template>
      <ElAlert
        v-if="currentVersionsTruncated"
        type="info"
        :closable="false"
        show-icon
        title="版本较多，请先按商品线筛选后查看当前生效比例"
      />
      <ElTable
        v-else-if="currentVersions.length"
        :data="currentVersions"
        border
        size="small"
        row-key="id"
      >
        <ElTableColumn label="商品线" width="110">
          <template #default="{ row }">{{ dictLabel(lineOptions, row.productLine) }}</template>
        </ElTableColumn>
        <ElTableColumn label="收款方" width="120">
          <template #default="{ row }">{{ dictLabel(receiverOptions, row.receiverType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="比例" width="110">
          <template #default="{ row }">{{ bpToPercentText(row.splitRate) }}</template>
        </ElTableColumn>
        <ElTableColumn label="生效时间" min-width="180">
          <template #default="{ row }">{{ formatEffectTime(row.effectTime) }}</template>
        </ElTableColumn>
        <ElTableColumn label="版本" width="110">
          <template #default="{ row }">{{ row.id }}</template>
        </ElTableColumn>
        <ElTableColumn label="备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.configRemark || '—' }}</template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-else description="尚无已生效的比例版本" :image-size="60" />
    </ElCard>

    <!-- 全仓统一搜索形态：auto-search 选择即防抖查询，无独立查询按钮；动作按钮归表头标准位 -->
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      auto-search
      :show-reset="false"
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="handleRefresh">
        <template #left>
          <ElButton
            v-if="hasPermission('finance:config:edit')"
            type="primary"
            @click="createVisible = true"
            v-ripple
            >新增生效版本</ElButton
          >
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>
    </ElCard>

    <ElDialog v-model="planVisible" title="发布六方分润计划" width="520px">
      <ElAlert
        type="info"
        :closable="false"
        show-icon
        title="六个比例一次给齐，整版生效；公司份额=余数，自动承接未分配部分"
        class="mb-3"
      />
      <ElForm label-width="180px">
        <ElFormItem label="水站（机主）%" required>
          <ElInputNumber
            v-model="planForm.owner"
            :min="0"
            :max="100"
            :precision="2"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="商务推广（推荐机主）%" required>
          <ElInputNumber
            v-model="planForm.referrer"
            :min="0"
            :max="100"
            :precision="2"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="运营中心·省 累计上限 %" required>
          <ElInputNumber
            v-model="planForm.province"
            :min="0"
            :max="100"
            :precision="2"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="运营中心·市 累计上限 %" required>
          <ElInputNumber
            v-model="planForm.city"
            :min="0"
            :max="100"
            :precision="2"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="运营中心·区县 累计上限 %" required>
          <ElInputNumber
            v-model="planForm.county"
            :min="0"
            :max="100"
            :precision="2"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="配送费·配送员 %" required>
          <ElInputNumber
            v-model="planForm.courier"
            :min="0"
            :max="100"
            :precision="2"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput v-model="planForm.remark" maxlength="100" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="planVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="planPublishing" @click="handlePlanPublish"
          >发布（自当下生效）</ElButton
        >
      </template>
    </ElDialog>

    <ElDialog v-model="planItemsVisible" :title="planItemsTitle" width="560px">
      <ElTable :data="planItemRows" border size="small" row-key="id">
        <ElTableColumn label="基数线" width="100">
          <template #default="{ row }">{{ lineLabel(row.productLine) }}</template>
        </ElTableColumn>
        <ElTableColumn label="角色" min-width="170">
          <template #default="{ row }">{{ roleLabel(row.roleCode) }}</template>
        </ElTableColumn>
        <ElTableColumn label="比例" width="110">
          <template #default="{ row }">{{ bpToPercentText(row.rateBp) }}</template>
        </ElTableColumn>
        <ElTableColumn label="计算方式" min-width="130">
          <template #default="{ row }">
            {{ row.rateMode === 'REGIONAL_CUMULATIVE' ? '累计上限（按级差实得）' : '固定比例' }}
          </template>
        </ElTableColumn>
      </ElTable>
    </ElDialog>

    <ElDialog v-model="createVisible" title="新增比例生效版本" width="460px">
      <ElForm label-width="90px">
        <ElFormItem label="商品线" required>
          <ElSelect v-model="createForm.productLine" style="width: 100%">
            <ElOption
              v-for="opt in lineOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="收款方" required>
          <!-- 白名单：只展示单行版本真正会发钱的组合（售水=机主；配送=机主/配送员）。
               推荐人与运营中心比例走上方六方整版计划，配了单行也不会发——不给出这个选项。 -->
          <ElSelect v-model="createForm.receiverType" style="width: 100%">
            <ElOption
              v-for="opt in createReceiverOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="比例(%)" required>
          <ElInputNumber
            v-model="createForm.percent"
            :min="0"
            :max="100"
            :precision="2"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput v-model="createForm.remark" maxlength="100" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="creating" @click="handleCreate">生效（自当下）</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    businessNowStamp,
    fetchSplitConfigCreate,
    fetchSplitConfigPage,
    fetchSplitPlanCreate,
    fetchSplitPlanItems,
    fetchSplitPlanPage,
    type SplitConfigItem,
    type SplitPlanItemRow,
    type SplitPlanRow
  } from '@/api/finance'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { bpToPercentText, percentToBp } from '@/utils/format'
  import { useUserStore } from '@/store/modules/user'
  import {
    ElAlert,
    ElButton,
    ElCard,
    ElDialog,
    ElEmpty,
    ElForm,
    ElFormItem,
    ElInput,
    ElInputNumber,
    ElMessage,
    ElOption,
    ElSelect,
    ElSpace,
    ElTable,
    ElTableColumn,
    ElTag
  } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'OrderSplitConfig' })

  const hasPermission = (perm: string) => {
    return useUserStore().rbacMenuList.some((item) => item.menuWebPerms === perm)
  }

  const searchForm = ref<{ productLine?: number }>({})
  const lineOptions = ref<{ label: string; value: number }[]>([])
  const receiverOptions = ref<{ label: string; value: number }[]>([])
  const createVisible = ref(false)
  const creating = ref(false)
  const createForm = ref<{
    productLine?: number
    receiverType?: number
    percent?: number
    remark?: string
  }>({})

  /** 单行版本可选收款方白名单：售水=机主；配送=机主/配送员（与后端白名单同源）。 */
  const createReceiverOptions = computed(() => {
    const allowed = createForm.value.productLine === 2 ? [1, 2] : [1]
    return receiverOptions.value.filter((opt) => allowed.includes(opt.value))
  })
  watch(
    () => createForm.value.productLine,
    () => {
      if (
        createForm.value.receiverType != null &&
        !createReceiverOptions.value.some((o) => o.value === createForm.value.receiverType)
      ) {
        createForm.value.receiverType = undefined
      }
    }
  )

  // ---------------- 六方整版计划（D-428） ----------------
  const planRows = ref<SplitPlanRow[]>([])
  const planLoading = ref(false)
  const planVisible = ref(false)
  const planPublishing = ref(false)
  const planItemsVisible = ref(false)
  const planItemsTitle = ref('')
  const planItemRows = ref<SplitPlanItemRow[]>([])
  /** 首版默认值＝甲方 8.14 分配表 + 8.6 会议三级口径（省5/市3/区县2）；发布前可改 */
  const planForm = ref<{
    owner?: number
    referrer?: number
    province?: number
    city?: number
    county?: number
    courier?: number
    remark?: string
  }>({})

  /**
   * 现行计划＝生效时间已到里最晚的一版（同秒按 ID 大者），与服务端 activePlanAt 同规则。
   * 仅在首页 20 版内可靠——版本极多时最新生效版必在最前（列表按生效时间倒序），仍成立。
   */
  const currentPlanId = computed(() => {
    const now = businessNowStamp()
    let current: SplitPlanRow | undefined
    planRows.value.forEach((row) => {
      if (row.planStatus === 3 || !row.effectTime || row.effectTime > now) return
      // ID 是 Long 字符串，数值序=先比长度再比字典序（不得 Number() 防精度截断）
      const idAfter = (a: string, b: string) =>
        a.length !== b.length ? a.length > b.length : a > b
      if (
        !current ||
        row.effectTime > current.effectTime ||
        (row.effectTime === current.effectTime && idAfter(row.id, current.id))
      ) {
        current = row
      }
    })
    return current?.id
  })

  const ROLE_LABELS: Record<string, string> = {
    WATER_OWNER: '水站（机主）',
    WATER_DIRECT_REFERRER: '商务推广（推荐机主）',
    REGION_PROVINCE: '运营中心·省',
    REGION_CITY: '运营中心·市',
    REGION_COUNTY: '运营中心·区县',
    DELIVERY_COURIER: '配送员'
  }
  const roleLabel = (code: string) => ROLE_LABELS[code] ?? code
  const lineLabel = (line: string) => (line === 'DELIVERY_FEE' ? '配送费' : '售水')

  async function loadPlans() {
    planLoading.value = true
    try {
      const result = await fetchSplitPlanPage({ current: 1, size: 20 })
      planRows.value = result.list
    } finally {
      planLoading.value = false
    }
  }

  function openPlanDialog() {
    planForm.value = { owner: 50, referrer: 5, province: 5, city: 3, county: 2, courier: 90 }
    planVisible.value = true
  }

  async function showPlanItems(row: SplitPlanRow) {
    planItemsTitle.value = `计划 ${row.planVersion} 比例明细`
    planItemRows.value = await fetchSplitPlanItems(row.id)
    planItemsVisible.value = true
  }

  async function handlePlanPublish() {
    const f = planForm.value
    if (
      f.owner == null ||
      f.referrer == null ||
      f.province == null ||
      f.city == null ||
      f.county == null ||
      f.courier == null
    ) {
      ElMessage.warning('六个比例必须全部填写（可为 0）')
      return
    }
    planPublishing.value = true
    try {
      await fetchSplitPlanCreate({
        waterOwnerBp: percentToBp(f.owner),
        waterReferrerBp: percentToBp(f.referrer),
        regionProvinceCumBp: percentToBp(f.province),
        regionCityCumBp: percentToBp(f.city),
        regionCountyCumBp: percentToBp(f.county),
        deliveryCourierBp: percentToBp(f.courier),
        remark: f.remark
      })
      ElMessage.success('计划已发布生效')
      planVisible.value = false
      await loadPlans()
    } finally {
      planPublishing.value = false
    }
  }

  /** 当前生效版本（同线同收款方各一行）；truncated=范围内版本没取全，此时不出结论。 */
  const currentVersions = ref<SplitConfigItem[]>([])
  const currentVersionsTruncated = ref(false)
  /** 一次取回的版本上限，与分页上限同值。 */
  const CURRENT_VERSION_SCAN_SIZE = 100

  /**
   * 现行版本的版本号集合。列表里同线同收款方往往有多版历史，只判「生效时间是否已到」
   * 会把一串早被取代的旧版一律标成已生效，运营无从知道哪一版此刻算数。
   * 仅在同范围版本取全时成立——它就是上方卡片的那批。
   */
  const currentVersionIds = computed(() => new Set(currentVersions.value.map((item) => item.id)))
  /** 现行版本集合是否已就绪；尚未取到或范围内版本没取全时一律不指认现行版本。 */
  const currentVersionsResolved = ref(false)

  onMounted(async () => {
    const [lines, receivers] = await Promise.all([
      fetchDictOptions(DictTypeEnum.分账商品线),
      fetchDictOptions(DictTypeEnum.分账收款方类型)
    ])
    lineOptions.value = toDictOptions(lines)
    receiverOptions.value = toDictOptions(receivers)
    await Promise.all([loadCurrentVersions(), loadPlans()])
  })

  const dictLabel = (options: { label: string; value: number }[], value: number) =>
    options.find((o) => o.value === value)?.label ?? String(value)

  /** 生效时间为 14 位本地时刻；这里只做展示换算，比较仍按原始字符串逐位比大小。 */
  const formatEffectTime = (effectTime: string) =>
    effectTime && effectTime.length === 14
      ? dayjs(effectTime, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss')
      : effectTime || '—'

  /**
   * 取当前筛选范围内的全部版本，按「同线同收款方取生效时间已到且最晚的一版」归并。
   * 与订单取版本的规则同源：都是 effectTime ≤ 当下里最大的那一条。
   * 「当下」必须取业务时区（businessNowStamp）：EFFECT_TIME 是服务端按业务时区写的 14 位串，
   * 拿浏览器本地时钟去比，运营机器时区一变就会把已生效的新比例排除掉、把上一版旧比例指认成现行比例。
   */
  async function loadCurrentVersions() {
    currentVersionsResolved.value = false
    const result = await fetchSplitConfigPage({
      current: 1,
      size: CURRENT_VERSION_SCAN_SIZE,
      productLine: searchForm.value.productLine
    })
    const list = Array.isArray(result?.list) ? result.list : []
    currentVersionsTruncated.value = (result?.total ?? list.length) > list.length
    if (currentVersionsTruncated.value) {
      currentVersions.value = []
      return
    }
    const now = businessNowStamp()
    const latest = new Map<string, SplitConfigItem>()
    list.forEach((item) => {
      if (!item.effectTime || item.effectTime > now) return
      const key = `${item.productLine}:${item.receiverType}`
      const kept = latest.get(key)
      if (!kept || item.effectTime > kept.effectTime) latest.set(key, item)
    })
    currentVersions.value = [...latest.values()].sort(
      (a, b) => a.productLine - b.productLine || a.receiverType - b.receiverType
    )
    currentVersionsResolved.value = true
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
      apiFn: fetchSplitConfigPage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        {
          prop: 'productLine',
          label: '商品线',
          width: 100,
          formatter: (row: SplitConfigItem) => dictLabel(lineOptions.value, row.productLine)
        },
        {
          prop: 'receiverType',
          label: '收款方',
          width: 110,
          formatter: (row: SplitConfigItem) => dictLabel(receiverOptions.value, row.receiverType)
        },
        {
          prop: 'splitRate',
          label: '比例',
          width: 100,
          formatter: (row: SplitConfigItem) => bpToPercentText(row.splitRate)
        },
        {
          prop: 'effectTime',
          label: '生效时间',
          minWidth: 170,
          formatter: (row: SplitConfigItem) => formatEffectTime(row.effectTime)
        },
        {
          prop: 'effectState',
          label: '生效状态',
          width: 120,
          /*
           * 三态而不是两态：同线同收款方通常有多版历史，一律标「已生效」等于告诉运营每一版都算数。
           * 只有同范围版本取全时才敢指认现行版本；没取全（或还没取到）就只说生效时间已到，不出结论。
           */
          formatter: (row: SplitConfigItem) => {
            if (!row.effectTime || row.effectTime > businessNowStamp()) {
              return h(ElTag, { type: 'warning' }, () => '未来待生效')
            }
            if (!currentVersionsResolved.value) {
              return h(ElTag, { type: 'info', effect: 'plain' }, () => '生效时间已到')
            }
            return currentVersionIds.value.has(row.id)
              ? h(ElTag, { type: 'success' }, () => '当前生效')
              : h(ElTag, { type: 'info', effect: 'plain' }, () => '已被新版取代')
          }
        },
        { prop: 'id', label: '版本', width: 110 },
        { prop: 'configRemark', label: '备注', minWidth: 160, showOverflowTooltip: true }
      ]
    }
  })

  const searchItems = computed(() => [
    {
      label: '商品线',
      key: 'productLine',
      type: 'select',
      placeholder: '商品线',
      clearable: true,
      options: lineOptions.value
    }
  ])

  /** 筛选变化时当前生效版本要跟着换范围，否则上下两块说的不是同一批商品线。 */
  const handleSearch = () => {
    replaceSearchParams({ ...searchForm.value })
    getData()
    loadCurrentVersions()
  }

  const handleReset = () => {
    searchForm.value = {}
    replaceSearchParams({})
    getData()
    loadCurrentVersions()
  }

  const handleRefresh = () => {
    refreshData()
    loadCurrentVersions()
  }

  const handleCreate = async () => {
    const form = createForm.value
    if (form.productLine == null || form.receiverType == null || form.percent == null) {
      ElMessage.warning('商品线/收款方/比例均必填')
      return
    }
    creating.value = true
    try {
      // 百分比 → 万分比（服务端口径）；生效时间由服务端取当下，不允许写过去时点
      await fetchSplitConfigCreate({
        productLine: form.productLine,
        receiverType: form.receiverType,
        splitRate: percentToBp(form.percent),
        remark: form.remark
      })
      ElMessage.success('新版本已生效')
      createVisible.value = false
      createForm.value = {}
      getData()
      loadCurrentVersions()
    } finally {
      creating.value = false
    }
  }
</script>

<style scoped>
  .hint-text {
    font-size: 12px;
    color: var(--art-text-gray-500);
  }
</style>
