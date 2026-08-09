<!-- 水卡详情抽屉：卡信息 + 套餐快照 + 授权成员（一卡多人 REQ-009） -->
<template>
  <ElDrawer v-model="drawerVisible" title="水卡详情" size="560px">
    <div v-loading="loading">
      <ElDescriptions :column="1" border label-width="96px">
        <ElDescriptionsItem label="卡号">{{ detail.cardNo || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="卡类型">{{
          detail.cardType ? cardTypeLabel(detail.cardType) : '-'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          <ElTag v-if="detail.cardStatus" :type="cardStatusTagType(detail.cardStatus)">
            {{ cardStatusLabel(detail.cardStatus) }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="持卡人">
          {{ detail.userName ? `${detail.userName}（${detail.userPhone || '-'}）` : '-' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="余额"
          >{{ fenToYuan(detail.balanceAmount) }} 元</ElDescriptionsItem
        >
        <ElDescriptionsItem label="剩余水量"
          >{{ mlToLiter(detail.balanceMl) }} L</ElDescriptionsItem
        >
        <ElDescriptionsItem label="到期时间">
          {{ detail.expireTime ? formatTime(detail.expireTime) : '永久' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="套餐快照">
          <template v-if="detail.packageSnapshotState === 'valid' && detail.packageSnapshot">
            {{ detail.packageSnapshot.packageName }}（售价
            {{ fenToYuan(detail.packageSnapshot.payAmountFen) }} 元 /
            {{ mlToLiter(detail.packageSnapshot.waterMl) }} L）
          </template>
          <ElTag
            v-else-if="detail.packageSnapshotState === 'invalid'"
            type="danger"
            size="small"
            effect="plain"
          >
            数据异常
          </ElTag>
          <template v-else>未记录</template>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="授权范围">
          <ElTag v-if="scopeContract.scopeType === 'all'" type="success" size="small">
            全场通用
          </ElTag>
          <ElTag v-else-if="scopeContract.scopeType === 'unconfigured'" type="warning" size="small">
            未配置，不可用卡
          </ElTag>
          <template v-else>{{ scopeContract.scopeText }}</template>
          <ElButton size="small" type="primary" link class="ml-2" @click="openScopeDialog">
            修改
          </ElButton>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="备注">{{ detail.cardRemark || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="创建时间">{{
          formatTime(detail.createTime)
        }}</ElDescriptionsItem>
      </ElDescriptions>

      <div class="mt-4 mb-2 font-medium">授权成员（消费计入本卡）</div>
      <ElTable :data="detail.memberList || []" border>
        <ElTableColumn label="成员" min-width="140">
          <template #default="{ row }">
            {{ row.memberUserName || `用户#${row.memberUserId}` }}
            <span v-if="row.memberName" class="text-gray-400">（{{ row.memberName }}）</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="手机号" width="130">
          <template #default="{ row }">{{ row.memberUserPhone || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="单日限额" width="100">
          <template #default="{ row }">
            {{ row.dayLimitMl ? `${mlToLiter(row.dayLimitMl)} L` : '不限' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }">
            <ElTag :type="row.memberStatus === 1 ? 'success' : 'info'">
              {{ memberStatusLabel(row.memberStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty
        v-if="!loading && !(detail.memberList || []).length"
        description="暂无授权成员"
        :image-size="60"
      />
    </div>
    <ElDialog v-model="scopeDialogVisible" title="修改授权范围" width="520px" append-to-body>
      <ElAlert
        v-if="!scopeParseOk"
        type="error"
        :closable="false"
        show-icon
        class="mb-3"
        title="原授权范围数据异常，请重新明确选择范围后保存"
        :description="scopeParseReason"
      />
      <ElForm label-width="90px">
        <ElFormItem label="范围类型">
          <ElRadioGroup v-model="scopeForm.scopeType">
            <ElRadio value="all">全场通用</ElRadio>
            <ElRadio value="specified">指定范围</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <template v-if="scopeForm.scopeType === 'specified'">
          <ElFormItem label="水站">
            <ElSelect v-model="scopeForm.stationIds" multiple filterable clearable class="w-full">
              <ElOption
                v-for="s in stationOptions"
                :key="s.id"
                :label="s.stationName"
                :value="s.id"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="设备">
            <ElSelect
              v-model="scopeForm.deviceIds"
              multiple
              filterable
              clearable
              class="w-full"
              @change="loadOutletOptions"
            >
              <ElOption
                v-for="d in deviceOptions"
                :key="d.id"
                :label="`${d.deviceNo}（${d.deviceName || '未命名'}）`"
                :value="d.id"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="出水口">
            <ElSelect v-model="scopeForm.outletIds" multiple filterable clearable class="w-full">
              <ElOption
                v-for="o in outletOptions"
                :key="o.id"
                :label="`${o.deviceNo} ${o.outletNo} 号口`"
                :value="o.id"
              />
            </ElSelect>
            <div class="text-xs text-gray-400">先选设备后加载其出水口；三维至少填一项</div>
          </ElFormItem>
        </template>
        <ElFormItem label="修改原因" required>
          <ElInput
            v-model="scopeForm.reason"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="审计必填"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="scopeDialogVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="scopeSaving"
          :disabled="!scopeCanSubmit"
          @click="saveScope"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { fetchCardDetail, fetchUpdateCardScope, type CardItem } from '@/api/user'
  import { fetchStationPage } from '@/api/station'
  import {
    buildScopePayload,
    canSubmitScope,
    emptyScopeForm,
    normalizeDeviceOptions,
    normalizeOutletOptions,
    normalizeStationOptions,
    parseScopeJson,
    retainLegalOutlets,
    type ScopeDeviceOption,
    type ScopeFormState,
    type ScopeOutletOption,
    type ScopeStationOption
  } from './card-scope-form'
  import { fetchDevicePage, fetchOutletListByDevice } from '@/api/device'
  import { ElMessage } from 'element-plus'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'

  interface Props {
    visible: boolean
    /** 身份类 Long ID 恒 string（S4 R2）：>2^53 经 Number 会舍入到相邻值，选 A 动 B。 */
    cardId?: string
  }

  interface Emits {
    (e: 'update:visible', value: boolean): void
  }

  const props = defineProps<Props>()
  const emit = defineEmits<Emits>()

  const drawerVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const loading = ref(false)
  const detail = ref<Partial<CardItem>>({})

  const cardTypeOptions = ref<{ label: string; value: number }[]>([])
  const cardStatusOptions = ref<{ label: string; value: number }[]>([])
  const memberStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [types, statuses, memberStatuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.水卡类型),
      fetchDictOptions(DictTypeEnum.水卡状态),
      fetchDictOptions(DictTypeEnum.成员授权状态)
    ])
    cardTypeOptions.value = toDictOptions(types)
    cardStatusOptions.value = toDictOptions(statuses)
    memberStatusOptions.value = toDictOptions(memberStatuses)
  })

  const cardTypeLabel = (v: number) =>
    cardTypeOptions.value.find((o) => o.value === v)?.label || String(v)
  const cardStatusLabel = (v: number) =>
    cardStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const memberStatusLabel = (v: number) =>
    memberStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const cardStatusTagType = (v: number) =>
    v === 1 ? 'success' : v === 2 ? 'warning' : v === 3 ? 'info' : 'danger'

  const fenToYuan = (fen?: number) => ((fen ?? 0) / 100).toFixed(2)
  const mlToLiter = (ml?: number) => ((ml ?? 0) / 1000).toFixed(1)

  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}:${t.slice(12, 14)}`
  }

  /** 授权范围合同：空值必须 fail-closed；只有 scopeType=all 才表示全场。 */
  const scopeContract = computed(() => {
    if (!detail.value.scopeJson) {
      return { scopeType: 'unconfigured', scopeText: '未配置授权范围，不可用卡' }
    }
    try {
      const scope = JSON.parse(detail.value.scopeJson) as {
        scopeType?: string
        stationIds?: number[]
        deviceIds?: number[]
        outletIds?: number[]
        stationNames?: string[]
        deviceNames?: string[]
        outletLabels?: string[]
      }
      if (scope.scopeType === 'all') {
        return { scopeType: 'all', scopeText: '全场通用' }
      }
      const parts = [
        scope.stationIds?.length
          ? `水站 ${scope.stationNames?.join('、') || scope.stationIds.join('、')}`
          : '',
        scope.deviceIds?.length
          ? `设备 ${scope.deviceNames?.join('、') || scope.deviceIds.join('、')}`
          : '',
        scope.outletIds?.length
          ? `出水口 ${scope.outletLabels?.join('、') || scope.outletIds.join('、')}`
          : ''
      ].filter(Boolean)
      return {
        scopeType: scope.scopeType || 'specified',
        scopeText: parts.join('；') || '范围为空，不可用卡'
      }
    } catch {
      return { scopeType: 'invalid', scopeText: '范围配置异常，不可用卡' }
    }
  })

  // ==== 修改授权范围（S4 R3）：结构化维度提交；身份 ID 全链 string（>2^53 防舍入）；
  // 站点/设备/出水口响应经专用归一化适配器（unknown 入参→string ID 选项，零数值转换）；
  // 原范围解析失败=未选态+异常横幅+禁存，运营必须重新明确选择（fail-closed）====
  const scopeDialogVisible = ref(false)
  const scopeSaving = ref(false)
  const scopeForm = ref<ScopeFormState>(emptyScopeForm())
  const scopeParseOk = ref(true)
  const scopeParseReason = ref('')
  const scopeCanSubmit = computed(() => canSubmitScope(scopeForm.value))
  const stationOptions = ref<ScopeStationOption[]>([])
  const deviceOptions = ref<ScopeDeviceOption[]>([])
  const outletOptions = ref<ScopeOutletOption[]>([])

  const openScopeDialog = async () => {
    const parsed = parseScopeJson(detail.value.scopeJson)
    scopeParseOk.value = parsed.ok
    scopeParseReason.value = parsed.reason ?? ''
    scopeForm.value = parsed.form
    scopeDialogVisible.value = true
    if (!stationOptions.value.length) {
      const [stations, devices] = await Promise.all([
        fetchStationPage({ current: 1, size: 999 }),
        fetchDevicePage({ current: 1, size: 999 })
      ])
      stationOptions.value = normalizeStationOptions(stations.list)
      deviceOptions.value = normalizeDeviceOptions(devices.list)
    }
    if (scopeForm.value.deviceIds.length) {
      await loadOutletOptions()
    }
  }

  const loadOutletOptions = async () => {
    const lists = await Promise.all(
      scopeForm.value.deviceIds.map((id) => fetchOutletListByDevice(id))
    )
    const deviceNoById = Object.fromEntries(deviceOptions.value.map((d) => [d.id, d.deviceNo]))
    outletOptions.value = lists.flatMap((list, i) =>
      normalizeOutletOptions(list, deviceNoById[scopeForm.value.deviceIds[i]] ?? '')
    )
    scopeForm.value.outletIds = retainLegalOutlets(
      scopeForm.value.outletIds,
      outletOptions.value.map((o) => o.id)
    )
  }

  const saveScope = async () => {
    if (!props.cardId) return
    if (!canSubmitScope(scopeForm.value)) {
      // 未选态（含原范围解析失败后）：必须显式重选合法范围，按钮禁用外再设一道闸
      ElMessage.warning('请先明确选择授权范围')
      return
    }
    if (!scopeForm.value.reason.trim()) {
      ElMessage.warning('请填写修改原因')
      return
    }
    scopeSaving.value = true
    try {
      await fetchUpdateCardScope(
        buildScopePayload(props.cardId, scopeForm.value, detail.value.scopeJson || undefined)
      )
      ElMessage.success('授权范围已更新')
      scopeDialogVisible.value = false
      detail.value = await fetchCardDetail(props.cardId)
    } finally {
      scopeSaving.value = false
    }
  }

  watch(
    () => props.visible,
    async (visible) => {
      if (visible && props.cardId) {
        loading.value = true
        try {
          detail.value = await fetchCardDetail(props.cardId)
        } finally {
          loading.value = false
        }
      }
    }
  )
</script>
