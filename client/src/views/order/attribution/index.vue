<!-- 分润归属管理（D-404/D-406/D-407/D-428）：机主加盟推荐关系与区域归属链。
     两张关系都「一人一行、建立即冻结」——没有修改与删除入口，录错走后续申诉流程。
     录入决定商务推广 5% 与运营中心 5% 的钱付给谁，动作受 finance:attribution:edit 权限门。 -->
<template>
  <div class="art-full-height">
    <BusinessModuleNav module-key="finance" />

    <ElAlert
      type="warning"
      :closable="false"
      show-icon
      title="归属关系建立后即冻结，直接决定分润归属；请核实后录入"
      class="mb-3"
    />

    <ElTabs v-model="activeTab">
      <ElTabPane label="推荐关系" name="referrer">
        <ArtSearchBar
          v-model="referrerSearch"
          :items="referrerSearchItems"
          auto-search
          :show-reset="false"
          @search="handleReferrerSearch"
          @reset="handleReferrerSearch"
        />
        <ElCard class="art-table-card">
          <ArtTableHeader
            v-model:columns="referrerColumnChecks"
            :loading="referrerLoading"
            @refresh="refreshReferrer"
          >
            <template #left>
              <ElButton
                v-if="hasPermission('finance:attribution:edit')"
                type="primary"
                @click="referrerDialog = true"
                v-ripple
                >录入推荐关系</ElButton
              >
            </template>
          </ArtTableHeader>
          <ArtTable
            :loading="referrerLoading"
            :data="referrerData"
            :columns="referrerColumns"
            :pagination="referrerPagination"
            @pagination:size-change="referrerSizeChange"
            @pagination:current-change="referrerCurrentChange"
          />
        </ElCard>
      </ElTabPane>

      <ElTabPane label="区域归属链" name="chain">
        <ArtSearchBar
          v-model="chainSearch"
          :items="chainSearchItems"
          auto-search
          :show-reset="false"
          @search="handleChainSearch"
          @reset="handleChainSearch"
        />
        <ElCard class="art-table-card">
          <ArtTableHeader
            v-model:columns="chainColumnChecks"
            :loading="chainLoading"
            @refresh="refreshChain"
          >
            <template #left>
              <ElButton
                v-if="hasPermission('finance:attribution:edit')"
                type="primary"
                @click="chainDialog = true"
                v-ripple
                >录入归属链</ElButton
              >
            </template>
          </ArtTableHeader>
          <ArtTable
            :loading="chainLoading"
            :data="chainData"
            :columns="chainColumns"
            :pagination="chainPagination"
            @pagination:size-change="chainSizeChange"
            @pagination:current-change="chainCurrentChange"
          />
        </ElCard>
      </ElTabPane>
    </ElTabs>

    <ElDialog v-model="referrerDialog" title="录入机主推荐关系" width="460px">
      <ElAlert
        type="info"
        :closable="false"
        show-icon
        title="任何身份都可担任推荐人，仅记一级；建立后不可换绑"
        class="mb-3"
      />
      <ElForm label-width="110px">
        <ElFormItem label="机主用户ID" required>
          <ElInput v-model="referrerForm.ownerUserId" placeholder="数字ID" />
        </ElFormItem>
        <ElFormItem label="推荐人用户ID" required>
          <ElInput v-model="referrerForm.referrerUserId" placeholder="数字ID，不能是机主本人" />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput v-model="referrerForm.remark" maxlength="100" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="referrerDialog = false">取消</ElButton>
        <ElButton type="primary" :loading="referrerSaving" @click="handleReferrerCreate"
          >确认录入</ElButton
        >
      </template>
    </ElDialog>

    <ElDialog v-model="chainDialog" title="录入机主区域归属链" width="500px">
      <ElAlert
        type="info"
        :closable="false"
        show-icon
        title="三级至少填一级、同一人不能兼任两级；某级留空时其份额按级差归最近的上级"
        class="mb-3"
      />
      <ElForm label-width="150px">
        <ElFormItem label="机主用户ID" required>
          <ElInput v-model="chainForm.ownerUserId" placeholder="数字ID" />
        </ElFormItem>
        <ElFormItem label="归属来源" required>
          <ElSelect v-model="chainForm.attributionSource" style="width: 100%">
            <ElOption label="推荐关系（血缘）" value="PRIVATE_REFERRAL" />
            <ElOption label="公域人工分配" value="PUBLIC_MANUAL" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="省级运营中心ID">
          <ElInput v-model="chainForm.provinceAgentUserId" placeholder="留空=该级无人" />
        </ElFormItem>
        <ElFormItem label="市级运营中心ID">
          <ElInput v-model="chainForm.cityAgentUserId" placeholder="留空=该级无人" />
        </ElFormItem>
        <ElFormItem label="区县级运营中心ID">
          <ElInput v-model="chainForm.countyAgentUserId" placeholder="留空=该级无人" />
        </ElFormItem>
        <ElFormItem label="备注">
          <ElInput v-model="chainForm.remark" maxlength="100" placeholder="分配依据、合同号等" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="chainDialog = false">取消</ElButton>
        <ElButton type="primary" :loading="chainSaving" @click="handleChainCreate"
          >确认录入</ElButton
        >
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchOwnerAttributionCreate,
    fetchOwnerAttributionPage,
    fetchOwnerReferrerCreate,
    fetchOwnerReferrerPage,
    type OwnerAttributionRow,
    type OwnerReferrerRow
  } from '@/api/finance'
  import { hasPermission } from '@/utils/permission'
  import {
    ElAlert,
    ElButton,
    ElCard,
    ElDialog,
    ElForm,
    ElFormItem,
    ElInput,
    ElMessage,
    ElOption,
    ElSelect,
    ElTabPane,
    ElTabs
  } from 'element-plus'
  import dayjs from 'dayjs'

  defineOptions({ name: 'OrderAttribution' })

  const activeTab = ref<'referrer' | 'chain'>('referrer')

  const formatBindTime = (value: string) =>
    value && value.length === 14
      ? dayjs(value, 'YYYYMMDDHHmmss').format('YYYY-MM-DD HH:mm:ss')
      : value || '—'

  const SOURCE_LABELS: Record<string, string> = {
    PRIVATE_REFERRAL: '推荐关系（血缘）',
    PUBLIC_MANUAL: '公域人工分配',
    ADMIN_ENTRY: '后台录入',
    INVITE_LINK: '邀请链路'
  }
  const sourceLabel = (code: string) => SOURCE_LABELS[code] ?? code

  /** ID 输入必须是纯数字串；Long 恒 string，不经 Number() 防精度截断 */
  const validId = (value?: string) => !!value && /^\d+$/.test(value.trim())

  // ---------------- 推荐关系 ----------------
  const referrerSearch = ref<{ ownerUserId?: string; referrerUserId?: string }>({})
  const referrerDialog = ref(false)
  const referrerSaving = ref(false)
  const referrerForm = ref<{ ownerUserId?: string; referrerUserId?: string; remark?: string }>({})

  const {
    columns: referrerColumns,
    columnChecks: referrerColumnChecks,
    data: referrerData,
    loading: referrerLoading,
    pagination: referrerPagination,
    getData: getReferrerData,
    replaceSearchParams: replaceReferrerParams,
    handleSizeChange: referrerSizeChange,
    handleCurrentChange: referrerCurrentChange,
    refreshData: refreshReferrer
  } = useTable({
    core: {
      apiFn: fetchOwnerReferrerPage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'ownerUserId', label: '机主用户ID', width: 160 },
        { prop: 'referrerUserId', label: '推荐人用户ID', width: 160 },
        {
          prop: 'bindSource',
          label: '建立来源',
          width: 120,
          formatter: (row: OwnerReferrerRow) => sourceLabel(row.bindSource)
        },
        {
          prop: 'bindTime',
          label: '建立时间',
          minWidth: 170,
          formatter: (row: OwnerReferrerRow) => formatBindTime(row.bindTime)
        },
        { prop: 'referrerRemark', label: '备注', minWidth: 150, showOverflowTooltip: true }
      ]
    }
  })

  const referrerSearchItems = computed(() => [
    {
      label: '机主用户ID',
      key: 'ownerUserId',
      type: 'input',
      placeholder: '精确ID',
      clearable: true
    },
    {
      label: '推荐人用户ID',
      key: 'referrerUserId',
      type: 'input',
      placeholder: '精确ID',
      clearable: true
    }
  ])

  const handleReferrerSearch = () => {
    const params: Record<string, string> = {}
    if (validId(referrerSearch.value.ownerUserId)) {
      params.ownerUserId = referrerSearch.value.ownerUserId!.trim()
    }
    if (validId(referrerSearch.value.referrerUserId)) {
      params.referrerUserId = referrerSearch.value.referrerUserId!.trim()
    }
    replaceReferrerParams(params)
    getReferrerData()
  }

  const handleReferrerCreate = async () => {
    const f = referrerForm.value
    if (!validId(f.ownerUserId) || !validId(f.referrerUserId)) {
      ElMessage.warning('机主与推荐人的用户ID都必须是数字')
      return
    }
    referrerSaving.value = true
    try {
      await fetchOwnerReferrerCreate({
        ownerUserId: f.ownerUserId!.trim(),
        referrerUserId: f.referrerUserId!.trim(),
        remark: f.remark
      })
      ElMessage.success('推荐关系已录入')
      referrerDialog.value = false
      referrerForm.value = {}
      getReferrerData()
    } finally {
      referrerSaving.value = false
    }
  }

  // ---------------- 区域归属链 ----------------
  const chainSearch = ref<{ ownerUserId?: string }>({})
  const chainDialog = ref(false)
  const chainSaving = ref(false)
  const chainForm = ref<{
    ownerUserId?: string
    attributionSource?: string
    provinceAgentUserId?: string
    cityAgentUserId?: string
    countyAgentUserId?: string
    remark?: string
  }>({})

  const {
    columns: chainColumns,
    columnChecks: chainColumnChecks,
    data: chainData,
    loading: chainLoading,
    pagination: chainPagination,
    getData: getChainData,
    replaceSearchParams: replaceChainParams,
    handleSizeChange: chainSizeChange,
    handleCurrentChange: chainCurrentChange,
    refreshData: refreshChain
  } = useTable({
    core: {
      apiFn: fetchOwnerAttributionPage,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'ownerUserId', label: '机主用户ID', width: 150 },
        {
          prop: 'attributionSource',
          label: '归属来源',
          width: 150,
          formatter: (row: OwnerAttributionRow) => sourceLabel(row.attributionSource)
        },
        {
          prop: 'provinceAgentUserId',
          label: '省级运营中心',
          width: 140,
          formatter: (row: OwnerAttributionRow) => row.provinceAgentUserId || '—'
        },
        {
          prop: 'cityAgentUserId',
          label: '市级运营中心',
          width: 140,
          formatter: (row: OwnerAttributionRow) => row.cityAgentUserId || '—'
        },
        {
          prop: 'countyAgentUserId',
          label: '区县级运营中心',
          width: 140,
          formatter: (row: OwnerAttributionRow) => row.countyAgentUserId || '—'
        },
        {
          prop: 'bindTime',
          label: '建立时间',
          minWidth: 170,
          formatter: (row: OwnerAttributionRow) => formatBindTime(row.bindTime)
        },
        { prop: 'attributionRemark', label: '备注', minWidth: 140, showOverflowTooltip: true }
      ]
    }
  })

  const chainSearchItems = computed(() => [
    {
      label: '机主用户ID',
      key: 'ownerUserId',
      type: 'input',
      placeholder: '精确ID',
      clearable: true
    }
  ])

  const handleChainSearch = () => {
    const params: Record<string, string> = {}
    if (validId(chainSearch.value.ownerUserId)) {
      params.ownerUserId = chainSearch.value.ownerUserId!.trim()
    }
    replaceChainParams(params)
    getChainData()
  }

  const handleChainCreate = async () => {
    const f = chainForm.value
    if (!validId(f.ownerUserId)) {
      ElMessage.warning('机主用户ID必须是数字')
      return
    }
    if (!f.attributionSource) {
      ElMessage.warning('请选择归属来源')
      return
    }
    const levels = [f.provinceAgentUserId, f.cityAgentUserId, f.countyAgentUserId]
      .map((v) => v?.trim())
      .filter((v): v is string => !!v)
    if (!levels.length) {
      ElMessage.warning('三级运营中心至少填一级')
      return
    }
    if (levels.some((v) => !/^\d+$/.test(v))) {
      ElMessage.warning('运营中心用户ID必须是数字')
      return
    }
    if (new Set(levels).size !== levels.length) {
      ElMessage.warning('同一人不能在归属链上兼任两级')
      return
    }
    chainSaving.value = true
    try {
      await fetchOwnerAttributionCreate({
        ownerUserId: f.ownerUserId!.trim(),
        attributionSource: f.attributionSource,
        provinceAgentUserId: f.provinceAgentUserId?.trim() || undefined,
        cityAgentUserId: f.cityAgentUserId?.trim() || undefined,
        countyAgentUserId: f.countyAgentUserId?.trim() || undefined,
        remark: f.remark
      })
      ElMessage.success('归属链已录入')
      chainDialog.value = false
      chainForm.value = {}
      getChainData()
    } finally {
      chainSaving.value = false
    }
  }
</script>
