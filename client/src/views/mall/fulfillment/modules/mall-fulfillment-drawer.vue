<!-- 商城履约抽屉（E2E-09 S3-B）：详情 + 七节点时间线 + 前置仓动作与分配。
     动作按钮按当前履约状态显隐，但显隐只是给人看的——真正的判据在服务端，
     越权、跨仓、状态不符一律由后端拒绝，这里不做任何"前端放行"。 -->
<template>
  <ElDrawer
    :model-value="visible"
    :title="`履约详情 · ${orderNo}`"
    size="620px"
    @update:model-value="emit('update:visible', $event)"
    @open="loadDetail"
  >
    <div v-loading="loading">
      <ElAlert v-if="errorText" type="error" :closable="false" :title="errorText" show-icon />

      <template v-if="detail">
        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="履约状态">
            <ElTag :type="statusTagType">{{ detail.fulfillStatusName }}</ElTag>
            <ElTag v-if="detail.exchangeReshipment" type="warning" class="ml-2"> 换货补发 </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="前置仓">{{ detail.warehouseName || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="承运渠道">
            <ElTag :type="modeTagType">{{ detail.fulfillModeName || '未选择' }}</ElTag>
          </ElDescriptionsItem>
          <!-- 配送员只在自营渠道有意义：第三方单上显示"未分配"会让人以为还差一步 -->
          <ElDescriptionsItem v-if="!isThirdParty" label="配送员">
            {{ detail.courierName ? `${detail.courierName}（${detail.courierPhone}）` : '未分配' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="收货人">
            {{ detail.receiverName }} {{ detail.receiverPhone }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="收货地址">
            {{ detail.receiverRegion }} {{ detail.receiverAddress }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="detail.signTime" label="签收">
            {{ formatTime(detail.signTime) }}
            {{ detail.signMethod === 2 ? '（他人代收）' : '（本人签收）' }}
            {{ detail.signRemark || '' }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="fulfill-actions">
          <ElButton
            v-if="detail.fulfillStatus === MALL_FULFILL_STATUS.PENDING_PICK && canHandle"
            type="primary"
            :loading="acting"
            @click="handlePick"
          >
            确认拣货
          </ElButton>
          <ElButton
            v-if="detail.fulfillStatus === MALL_FULFILL_STATUS.PENDING_PACK && canHandle"
            type="primary"
            :loading="acting"
            @click="handlePack"
          >
            打包完成
          </ElButton>
          <!-- 待安排发运时两个入口并列；渠道一经冻结，另一条链的入口不再出现。
               显隐只是给人看的，真正的互斥判据是服务端那次 FULFILL_MODE 的 CAS -->
          <ElButton
            v-if="canArrange && !isThirdParty && canDispatch"
            type="primary"
            :loading="acting"
            @click="openAssign"
          >
            分配自营配送员
          </ElButton>
          <ElButton
            v-if="canArrange && !isSelfDelivery && canDispatch"
            :loading="acting"
            @click="openShipment"
          >
            创建第三方运单
          </ElButton>
          <span v-if="actionBlocked" class="fulfill-hint">当前账号没有该操作权限。</span>
          <span
            v-if="detail.fulfillStatus >= MALL_FULFILL_STATUS.PENDING_FETCH"
            class="fulfill-hint"
          >
            {{
              isThirdParty
                ? '后续节点由承运方回传，平台不代填；确认收货恒由用户在小程序完成。'
                : '后续节点由配送员与用户在小程序完成，后台不代签。'
            }}
          </span>
        </div>

        <template v-if="shipments.length > 0">
          <ElDivider>出库包裹</ElDivider>
          <div v-for="ship in shipments" :key="ship.shipmentId" class="fulfill-shipment">
            <div class="fulfill-shipment-head">
              <ElTag size="small">{{ ship.directionName }}</ElTag>
              <ElTag size="small" type="info">{{ ship.fulfillModeName }}</ElTag>
              <ElTag size="small" :type="shipmentTagType(ship.shipmentStatus)">
                {{ ship.shipmentStatusName }}
              </ElTag>
            </div>
            <ElDescriptions :column="1" border size="small" class="fulfill-shipment-body">
              <ElDescriptionsItem label="承运商">{{ ship.providerCode || '-' }}</ElDescriptionsItem>
              <!-- 运单号要等 Worker 拿到承运方回执才出现，空值如实显示为"待承运方受理"，
                   不要显示"-"——那会被读成"这单没有运单号" -->
              <ElDescriptionsItem label="运单号">
                {{ ship.waybillNo || '待承运方受理' }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="服务类型">{{
                ship.serviceCode || '-'
              }}</ElDescriptionsItem>
            </ElDescriptions>
            <ElTimeline v-if="ship.logisticsTraces.length > 0" class="fulfill-shipment-trace">
              <ElTimelineItem
                v-for="(node, index) in ship.logisticsTraces"
                :key="`${ship.shipmentId}-${index}`"
                :timestamp="formatTime(node.eventTime)"
                placement="top"
              >
                <div class="fulfill-node">{{ node.eventStateName }}</div>
                <div class="fulfill-node-text">{{ node.eventDesc || '-' }}</div>
              </ElTimelineItem>
            </ElTimeline>
          </div>
        </template>

        <ElDivider>履约时间线</ElDivider>
        <ElTimeline>
          <ElTimelineItem
            v-for="node in detail.timeline"
            :key="node.traceNode"
            :timestamp="formatTime(node.traceTime)"
            placement="top"
          >
            <div class="fulfill-node">{{ node.traceNodeName }}</div>
            <div class="fulfill-node-text">{{ node.traceText }}（{{ node.actorTypeName }}）</div>
          </ElTimelineItem>
        </ElTimeline>
      </template>
    </div>

    <!-- 候选列表只是给人看的，不构成授权：后端在分配时会重新校验准入、停用、
         前置仓范围与自配送，前端少列或多列都不改变最终判定。 -->
    <ElDialog v-model="assignVisible" title="分配配送员" width="460px">
      <ElAlert
        type="info"
        :closable="false"
        show-icon
        title="只列出可为本前置仓配送、已通过准入的配送员；下单人本人不可选。"
      />
      <ElSelect v-model="assignCourierId" class="fulfill-select" placeholder="请选择配送员">
        <ElOption
          v-for="item in candidates"
          :key="item.courierId"
          :label="`${item.courierName}（${item.courierPhone}）`"
          :value="item.courierId"
        />
      </ElSelect>
      <template #footer>
        <ElButton @click="assignVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="acting"
          :disabled="!assignCourierId"
          @click="handleAssign"
        >
          确认分配
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="shipmentVisible" title="创建第三方运单" width="460px">
      <ElAlert
        type="warning"
        :closable="false"
        show-icon
        title="确认后承运渠道即冻结为第三方物流，本单不能再分配自营配送员。"
      />
      <ElSelect v-model="providerCode" class="fulfill-select" placeholder="请选择承运商">
        <ElOption
          v-for="item in providers"
          :key="item.providerCode"
          :label="`${item.providerName}（${item.providerCode}）`"
          :value="item.providerCode"
        />
      </ElSelect>
      <template #footer>
        <ElButton @click="shipmentVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="acting"
          :disabled="!providerCode"
          @click="handleShipment"
        >
          确认创建
        </ElButton>
      </template>
    </ElDialog>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { ref, computed } from 'vue'
  import {
    ElAlert,
    ElButton,
    ElDescriptions,
    ElDescriptionsItem,
    ElDialog,
    ElDivider,
    ElDrawer,
    ElMessage,
    ElOption,
    ElSelect,
    ElTag,
    ElTimeline,
    ElTimelineItem
  } from 'element-plus'
  import dayjs from 'dayjs'
  import {
    MALL_FULFILL_MODE,
    MALL_FULFILL_STATUS,
    MALL_SHIPMENT_STATUS,
    fetchMallCourierCandidates,
    fetchMallFulfillAssign,
    fetchMallFulfillDetail,
    fetchMallFulfillPack,
    fetchMallFulfillPick,
    fetchMallShipmentCreate,
    fetchMallShipmentProviders,
    fetchMallShipments,
    type MallCourierCandidate,
    type MallFulfillDetail,
    type MallLogisticsProvider,
    type MallShipment
  } from '@/api/mall-fulfillment'
  import { hasPermission } from '@/utils/permission'

  const props = defineProps<{ visible: boolean; orderNo: string }>()
  const emit = defineEmits<{
    'update:visible': [value: boolean]
    changed: []
  }>()

  const loading = ref(false)
  const acting = ref(false)
  const detail = ref<MallFulfillDetail | null>(null)
  const errorText = ref('')
  const assignVisible = ref(false)
  const assignCourierId = ref('')
  const candidates = ref<MallCourierCandidate[]>([])
  const shipmentVisible = ref(false)
  const providerCode = ref('')
  const providers = ref<MallLogisticsProvider[]>([])
  const shipments = ref<MallShipment[]>([])

  const statusTagType = computed(() => {
    const status = detail.value?.fulfillStatus ?? 0
    if (status >= MALL_FULFILL_STATUS.SIGNED) return 'success'
    if (status >= MALL_FULFILL_STATUS.PENDING_FETCH) return 'warning'
    return 'info'
  })

  const isSelfDelivery = computed(
    () => detail.value?.fulfillMode === MALL_FULFILL_MODE.SELF_DELIVERY
  )
  const isThirdParty = computed(() => detail.value?.fulfillMode === MALL_FULFILL_MODE.THIRD_PARTY)
  const canArrange = computed(
    () => detail.value?.fulfillStatus === MALL_FULFILL_STATUS.PENDING_ASSIGN
  )
  /**
   * 包裹语义色：异常待人工必须是 danger。
   *
   * 这里曾按字面量 9 判异常，而后端的异常态是 8——异常包裹被渲染成绿色 success，
   * 而按颜色扫读台账的人不会点进去看。判据一律引常量，不写字面量。
   */
  function shipmentTagType(status: number) {
    if (status === MALL_SHIPMENT_STATUS.NEED_MANUAL) return 'danger'
    if (status === MALL_SHIPMENT_STATUS.CANCELLED) return 'info'
    if (status === MALL_SHIPMENT_STATUS.DELIVERED || status === MALL_SHIPMENT_STATUS.SIGNED) {
      return 'success'
    }
    return 'warning'
  }

  const modeTagType = computed(() => {
    if (isThirdParty.value) return 'warning'
    if (isSelfDelivery.value) return 'success'
    return 'info'
  })

  /**
   * 履约动作分两档授权：拣货打包是仓内作业，分配配送员与创建运单决定这批货交给谁
   * 并同事务冻结承运渠道。按钮显隐只是不摆出点不动的入口，真正的判定在服务端。
   */
  const canHandle = computed(() => hasPermission('mall:fulfillment:handle'))
  const canDispatch = computed(() => hasPermission('mall:fulfillment:dispatch'))

  /** 有动作可做却没权限：动作区不能留成空白，那会被读成「这单已经处理完了」。 */
  const actionBlocked = computed(() => {
    const status = detail.value?.fulfillStatus
    if (
      status === MALL_FULFILL_STATUS.PENDING_PICK ||
      status === MALL_FULFILL_STATUS.PENDING_PACK
    ) {
      return !canHandle.value
    }
    return canArrange.value && !canDispatch.value
  })

  function formatTime(value?: string) {
    if (!value) return '-'
    const parsed = dayjs(value, 'YYYYMMDDHHmmss')
    return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value
  }

  async function loadDetail() {
    if (!props.orderNo) return
    loading.value = true
    errorText.value = ''
    detail.value = null
    shipments.value = []
    try {
      detail.value = await fetchMallFulfillDetail(props.orderNo)
      shipments.value = await fetchMallShipments(props.orderNo)
    } catch (error) {
      // 履约任务尚未生成、跨仓、共键不一致都会落到这里；页面只如实显示，不猜原因
      errorText.value = error instanceof Error ? error.message : '履约详情读取失败'
    } finally {
      loading.value = false
    }
  }

  async function runAction(action: () => Promise<MallFulfillDetail>, done: string) {
    acting.value = true
    try {
      detail.value = await action()
      // 动作可能刚建出包裹或改了运单，包裹区块必须跟着重读，否则页面停在动作前的样子
      shipments.value = await fetchMallShipments(props.orderNo)
      ElMessage.success(done)
      emit('changed')
    } finally {
      acting.value = false
    }
  }

  const handlePick = () => runAction(() => fetchMallFulfillPick(props.orderNo), '已确认拣货')
  const handlePack = () => runAction(() => fetchMallFulfillPack(props.orderNo), '已打包完成')

  async function openAssign() {
    assignCourierId.value = ''
    candidates.value = await fetchMallCourierCandidates(props.orderNo)
    if (candidates.value.length === 0) {
      ElMessage.warning('本前置仓暂无可分配的配送员，请先在配送员范围中绑定')
      return
    }
    assignVisible.value = true
  }

  async function handleAssign() {
    await runAction(
      () => fetchMallFulfillAssign({ orderNo: props.orderNo, courierId: assignCourierId.value }),
      '已分配配送员'
    )
    assignVisible.value = false
  }

  async function openShipment() {
    providerCode.value = ''
    providers.value = await fetchMallShipmentProviders()
    if (providers.value.length === 0) {
      // 没有已装配的适配器就没有能真正发出去的运单：宁可不给点，也不建一张永远发不出去的单
      ElMessage.warning('暂无可用的承运商，请先联系管理员开通后再创建运单')
      return
    }
    shipmentVisible.value = true
  }

  async function handleShipment() {
    await runAction(
      () => fetchMallShipmentCreate({ orderNo: props.orderNo, providerCode: providerCode.value }),
      '已提交第三方运单请求'
    )
    shipmentVisible.value = false
  }
</script>

<style lang="scss" scoped>
  .fulfill-actions {
    display: flex;
    gap: 12px;
    align-items: center;
    margin-top: 16px;
  }

  .fulfill-hint {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .fulfill-select {
    width: 100%;
    margin-top: 16px;
  }

  .fulfill-shipment {
    padding: 12px;
    margin-bottom: 12px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
  }

  .fulfill-shipment-head {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .fulfill-shipment-body {
    margin-top: 12px;
  }

  .fulfill-shipment-trace {
    margin-top: 12px;
  }

  .fulfill-node {
    font-weight: 500;
  }

  .fulfill-node-text {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
</style>
