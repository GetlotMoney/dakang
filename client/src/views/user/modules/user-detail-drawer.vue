<!--
  用户档案工作区：身份资料 / 水卡与授权 / 订单 / 资金流水 / 关系归属 / 审计记录。

  六个分区各自请求、各自分页，切到哪个才取哪个：一个长期用户的订单与流水没有上限，
  打开抽屉就把六份数据全量拉回来，在真实数据量下会直接把页面拖死。
  全区只读——余额、水量、状态、关系在这里只能被看见，改这些事实要走各自领域的受控入口。

  五个分页列表一律用 ArtTable 的内置分页，不再各自手写 ElTable+ElEmpty+ElPagination：
  手写那套把每页写死 10 条且没有条数选择器，查一个有几百条流水的老用户只能一页页点；
  空数据时表格自带的空态还会和外挂的 ElEmpty 叠成两个空态。ArtTable 的 #empty 插槽
  与内置分页正是为消掉这两件事而存在的。
-->
<template>
  <section v-if="drawerVisible" class="user-profile">
    <header class="user-profile__header">
      <div class="user-profile__identity">
        <!-- 返回钮与设备详情同形：圆形箭头钮与标题同行（全仓详情页统一形态） -->
        <ElButton circle :icon="ArrowLeft" title="返回用户列表" @click="drawerVisible = false" />
        <div class="user-profile__avatar">{{ (identity.userName || '用').slice(0, 1) }}</div>
        <div>
          <h2>{{ identity.userName || '用户档案' }}</h2>
          <p>ID {{ identity.id || userId }} · {{ identity.userPhone || '手机号未登记' }}</p>
        </div>
        <ElTag v-if="identity.id" :type="identity.disabledFlag === 1 ? 'success' : 'danger'">
          {{ dictLabel(DictTypeEnum.禁用状态, identity.disabledFlag) }}
        </ElTag>
      </div>
    </header>

    <div class="user-profile__layout">
      <nav class="user-profile__nav" aria-label="用户档案分区">
        <button
          v-for="item in sectionNav"
          :key="item.key"
          type="button"
          class="user-profile__nav-item"
          :class="{ 'is-active': activeTab === item.key }"
          @click="selectTab(item.key)"
        >
          <ArtSvgIcon :icon="item.icon" />
          <span>
            <strong>{{ item.label }}</strong>
            <small>{{ item.hint }}</small>
          </span>
          <ArtSvgIcon icon="ri:arrow-right-s-line" class="user-profile__nav-arrow" />
        </button>
      </nav>

      <main class="user-profile__content">
        <!-- 加载失败必须与"这个人没有这类记录"分开：失败时给一个明确的重试入口，
         而不是把一次失败的请求渲染成一份权威的空档案。 -->
        <ElAlert
          v-if="failedSections.has(activeTab)"
          class="mb-3"
          type="error"
          title="加载失败"
          :closable="false"
          show-icon
        >
          <ElButton link type="primary" @click="retryActiveTab">重新加载</ElButton>
        </ElAlert>

        <!-- 一、身份资料 -->
        <section v-show="activeTab === 'identity'" class="user-profile__section">
          <h3>身份资料</h3>
          <div v-loading="identityLoading">
            <ElDescriptions :column="2" border label-width="96px">
              <ElDescriptionsItem label="用户ID">{{ identity.id || '-' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="姓名">{{ identity.userName || '-' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="性别">{{
                dictLabel(DictTypeEnum.性别, identity.userGender)
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="手机号">{{
                identity.userPhone || '-'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="账号状态">
                <ElTag :type="identity.disabledFlag === 1 ? 'success' : 'danger'">
                  {{ dictLabel(DictTypeEnum.禁用状态, identity.disabledFlag) }}
                </ElTag>
                <ElTag v-if="identity.userStatus === 2" type="info" class="ml-2">已注销</ElTag>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="注册时间">{{
                formatTime(identity.createTime)
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="能力" :span="2">
                <template v-if="identity.capabilities && identity.capabilities.length">
                  <ElTag
                    v-for="code in identity.capabilities"
                    :key="code"
                    class="mr-2"
                    :type="code === USER_CAPABILITY.owner ? 'primary' : 'success'"
                  >
                    {{ capabilityLabel(code) }}
                  </ElTag>
                </template>
                <span v-else>取水</span>
              </ElDescriptionsItem>
            </ElDescriptions>
          </div>
        </section>

        <!-- 二、水卡与授权 -->
        <section v-show="activeTab === 'cards'" class="user-profile__section">
          <h3>水卡与授权</h3>
          <ArtTable
            row-key="id"
            :loading="cardLoading"
            :columns="cardColumns"
            :data="cardRows"
            :pagination="cardPage"
            :show-table-header="false"
            :empty-text="sectionEmptyText('cards', '暂无水卡')"
            empty-height="180px"
            @pagination:size-change="cardPager.onSize"
            @pagination:current-change="cardPager.onCurrent"
          >
            <template #cardStatus="{ row }">
              <ElTag :type="cardStatusTagType(row.cardStatus)">
                {{ dictLabel(DictTypeEnum.水卡状态, row.cardStatus) }}
              </ElTag>
            </template>
          </ArtTable>

          <ElDivider />
          <div class="mb-2 font-medium">配送准入</div>
          <ElDescriptions v-if="courier" :column="2" border label-width="96px">
            <ElDescriptionsItem label="配送员姓名">{{ courier.courierName }}</ElDescriptionsItem>
            <ElDescriptionsItem label="联系电话">{{
              courier.courierPhone || '-'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="准入状态">
              <ElTag :type="courierStatusTagType(courier.courierStatus)">
                {{ dictLabel(DictTypeEnum.配送员状态, courier.courierStatus) }}
              </ElTag>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="服务水站">{{
              courier.stationNames || '未配置，不可接单'
            }}</ElDescriptionsItem>
          </ElDescriptions>
          <ElEmpty
            v-else-if="!cardLoading"
            :description="sectionEmptyText('cards', '暂无配送准入记录')"
            :image-size="60"
          />
        </section>

        <!-- 三、订单 -->
        <section v-show="activeTab === 'orders'" class="user-profile__section">
          <h3>订单</h3>
          <ArtTable
            row-key="id"
            :loading="orderLoading"
            :columns="orderColumns"
            :data="orderRows"
            :pagination="orderPage"
            :show-table-header="false"
            :empty-text="sectionEmptyText('orders', '暂无订单')"
            empty-height="180px"
            @pagination:size-change="orderPager.onSize"
            @pagination:current-change="orderPager.onCurrent"
          >
            <template #orderStatus="{ row }">
              <ElTag :type="orderStatusTagType(row.orderStatus)">
                {{ dictLabel(DictTypeEnum.订单状态, row.orderStatus) }}
              </ElTag>
            </template>
          </ArtTable>
        </section>

        <!-- 四、资金流水 -->
        <section v-show="activeTab === 'flows'" class="user-profile__section">
          <h3>资金流水</h3>
          <ArtTable
            row-key="id"
            :loading="flowLoading"
            :columns="flowColumns"
            :data="flowRows"
            :pagination="flowPage"
            :show-table-header="false"
            :empty-text="sectionEmptyText('flows', '暂无流水')"
            empty-height="180px"
            @pagination:size-change="flowPager.onSize"
            @pagination:current-change="flowPager.onCurrent"
          />
        </section>

        <!-- 五、关系归属 -->
        <section v-show="activeTab === 'relation'" class="user-profile__section">
          <h3>关系归属</h3>
          <div v-loading="relationLoading">
            <ElDescriptions :column="2" border label-width="110px">
              <ElDescriptionsItem label="本人邀请码">{{
                relation.ownInviteCode || '-'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="注册推送码">{{
                relation.promoCode || '-'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="上一级邀请人" :span="2">
                <span v-if="relation.referrerMissing">邀请人账号已失效</span>
                <span v-else-if="relation.referrerUserId">
                  {{ relation.referrerUserName || '-' }}（{{ relation.referrerUserPhone || '-' }}）·
                  ID {{ relation.referrerUserId }}
                </span>
                <span v-else>未绑定</span>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="直接下级">
                {{ relation.directInviteeCount ?? 0 }} 人
              </ElDescriptionsItem>
            </ElDescriptions>

            <div class="mt-4 mb-2 font-medium">直接下级</div>
            <ArtTable
              row-key="id"
              :loading="inviteeLoading"
              :columns="inviteeColumns"
              :data="inviteeRows"
              :pagination="inviteePage"
              :show-table-header="false"
              :empty-text="sectionEmptyText('relation', '暂无直接下级')"
              empty-height="180px"
              @pagination:size-change="inviteePager.onSize"
              @pagination:current-change="inviteePager.onCurrent"
            />
          </div>
        </section>

        <!-- 六、审计记录 -->
        <section v-show="activeTab === 'audits'" class="user-profile__section">
          <h3>审计记录</h3>
          <ArtTable
            row-key="id"
            :loading="auditLoading"
            :columns="auditColumns"
            :data="auditRows"
            :pagination="auditPage"
            :show-table-header="false"
            :empty-text="sectionEmptyText('audits', '暂无审计记录')"
            empty-height="180px"
            @pagination:size-change="auditPager.onSize"
            @pagination:current-change="auditPager.onCurrent"
          />
        </section>
      </main>
    </div>
  </section>
</template>

<script setup lang="ts">
  import {
    fetchCardPage,
    fetchCourierByUser,
    fetchUserProfileAuditPage,
    fetchUserProfileFlowPage,
    fetchUserProfileIdentity,
    fetchUserProfileInviteePage,
    fetchUserProfileOrderPage,
    fetchUserProfileRelation,
    USER_CAPABILITY,
    type CardItem,
    type CourierItem,
    type UserAuditItem,
    type UserFlowItem,
    type UserOrderItem,
    type UserRelation,
    type UserRelationItem,
    type WsUserItem
  } from '@/api/user'
  import { fetchDictByTypes } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  // 金额（分→元）与水量（毫升→升）一律走全仓唯一实现：抽屉曾内联一份只保留 1 位小数的
  // mlToLiter，同一笔少出 40ml 的订单在订单中心是 18.96L、在这里被抹平成 19.0，
  // 两个页面对同一份权威水量给出两个结论。
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { ArrowLeft } from '@element-plus/icons-vue'
  import type { ColumnOption } from '@/types'

  interface Props {
    visible: boolean
    userId?: string
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

  type TabName = 'identity' | 'cards' | 'orders' | 'flows' | 'relation' | 'audits'
  const sectionNav: { key: TabName; label: string; hint: string; icon: string }[] = [
    { key: 'identity', label: '身份资料', hint: '账号与能力', icon: 'ri:user-3-line' },
    { key: 'cards', label: '水卡与授权', hint: '水卡及配送准入', icon: 'ri:bank-card-line' },
    { key: 'orders', label: '订单', hint: '全部业务订单', icon: 'ri:file-list-3-line' },
    { key: 'flows', label: '资金流水', hint: '余额与水量变动', icon: 'ri:exchange-funds-line' },
    { key: 'relation', label: '关系归属', hint: '邀请与直属关系', icon: 'ri:git-branch-line' },
    { key: 'audits', label: '审计记录', hint: '关键操作留痕', icon: 'ri:history-line' }
  ]
  const activeTab = ref<TabName>('identity')
  const loadedTabs = ref<Set<TabName>>(new Set())
  /** 取数失败的分区。失败与"没有这类记录"在界面上必须是两件事。 */
  const failedSections = ref<Set<TabName>>(new Set())

  const PAGE_SIZE = 10
  interface Pager {
    current: number
    size: number
    total: number
  }
  const newPager = (): Pager => reactive({ current: 1, size: PAGE_SIZE, total: 0 })

  // ---- 字典（一次批量取，六个分区共用）----
  const dictMap = ref<Record<string, { value: number; label: string }[]>>({})

  const loadDicts = async () => {
    if (Object.keys(dictMap.value).length) return
    const types = await fetchDictByTypes([
      DictTypeEnum.性别,
      DictTypeEnum.禁用状态,
      DictTypeEnum.水卡类型,
      DictTypeEnum.水卡状态,
      DictTypeEnum.配送员状态,
      DictTypeEnum.订单类型,
      DictTypeEnum.订单状态,
      DictTypeEnum.钱包流水类型
    ])
    const map: Record<string, { value: number; label: string }[]> = {}
    types.forEach((t) => {
      map[t.dictType] = (t.dictDataList || []).map((d) => ({
        value: d.dictValue,
        label: d.dictLabel
      }))
    })
    dictMap.value = map
  }

  const dictLabel = (type: string, value?: number) => {
    if (value === undefined || value === null) return '-'
    return dictMap.value[type]?.find((o) => o.value === value)?.label || String(value)
  }

  const capabilityLabel = (code: string) =>
    code === USER_CAPABILITY.owner ? '机主' : code === USER_CAPABILITY.courier ? '配送员' : code

  // ---- 展示口径：接口恒为分/毫升，转换只发生在展示层且只有一份实现 ----
  const signedYuan = (fen?: number) => (fen && fen > 0 ? `+${fenToYuan(fen)}` : fenToYuan(fen))
  const signedLiter = (ml?: number) => (ml && ml > 0 ? `+${mlToLiter(ml)}` : mlToLiter(ml))
  const waterText = (row: UserOrderItem) => {
    if (!row.planMl && !row.actualMl) return '-'
    return mlToLiter(row.actualMl ?? row.planMl)
  }

  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}:${t.slice(12, 14)}`
  }

  const cardStatusTagType = (v: number) =>
    v === 1 ? 'success' : v === 2 ? 'warning' : v === 3 ? 'info' : 'danger'
  const courierStatusTagType = (v: number) =>
    v === 2 ? 'success' : v === 1 ? 'primary' : v === 3 ? 'info' : 'danger'
  /**
   * 订单状态(1341)→色调，逐值与订单中心 order/index.vue 同一套：
   * 完成绿 / 进行蓝 / 异常橙 / 退款取消红。颜色是运营扫读时的第一判据，
   * 同一张单在两个页面给出相反的紧急度，比不上色更糟。
   */
  const orderStatusTagType = (v: number) =>
    v === 4
      ? 'success'
      : v === 6
        ? 'warning'
        : v === 5 || v === 7 || v === 8
          ? 'danger'
          : v === 3
            ? 'primary'
            : 'info'

  // ---- 一、身份资料 ----
  const identityLoading = ref(false)
  const identity = ref<Partial<WsUserItem>>({})

  const loadIdentity = async () => {
    if (!props.userId) return
    identityLoading.value = true
    try {
      identity.value = await fetchUserProfileIdentity(props.userId)
    } finally {
      identityLoading.value = false
    }
  }

  // ---- 二、水卡与授权 ----
  const cardLoading = ref(false)
  const cardRows = ref<CardItem[]>([])
  const cardPage = newPager()
  const courier = ref<CourierItem | null>(null)

  const cardColumns: ColumnOption[] = [
    { prop: 'cardNo', label: '卡号', minWidth: 150 },
    {
      prop: 'cardType',
      label: '类型',
      width: 90,
      formatter: (row: CardItem) => dictLabel(DictTypeEnum.水卡类型, row.cardType)
    },
    {
      prop: 'balanceAmount',
      label: '余额(元)',
      width: 100,
      formatter: (row: CardItem) => fenToYuan(row.balanceAmount)
    },
    {
      prop: 'balanceMl',
      label: '剩余水量',
      width: 110,
      formatter: (row: CardItem) => mlToLiter(row.balanceMl)
    },
    {
      prop: 'memberCount',
      label: '授权成员',
      width: 100,
      formatter: (row: CardItem) => `${row.memberCount ?? 0} 人`
    },
    { prop: 'cardStatus', label: '状态', width: 90, useSlot: true }
  ]

  const loadCards = async (current = cardPage.current) => {
    if (!props.userId) return
    cardPage.current = current
    cardLoading.value = true
    try {
      const [cards, courierIdentity] = await Promise.all([
        fetchCardPage({ current, size: cardPage.size, userId: props.userId }),
        fetchCourierByUser(props.userId)
      ])
      cardRows.value = cards.list || []
      cardPage.total = cards.total || 0
      courier.value = courierIdentity
    } finally {
      cardLoading.value = false
    }
  }

  // ---- 三、订单 ----
  const orderLoading = ref(false)
  const orderRows = ref<UserOrderItem[]>([])
  const orderPage = newPager()

  const orderColumns: ColumnOption[] = [
    { prop: 'orderNo', label: '订单号', minWidth: 180 },
    {
      prop: 'orderType',
      label: '类型',
      width: 100,
      formatter: (row: UserOrderItem) => dictLabel(DictTypeEnum.订单类型, row.orderType)
    },
    { prop: 'orderStatus', label: '状态', width: 110, useSlot: true },
    {
      prop: 'orderAmount',
      label: '金额(元)',
      width: 100,
      formatter: (row: UserOrderItem) => fenToYuan(row.orderAmount)
    },
    {
      prop: 'planMl',
      label: '水量',
      width: 120,
      formatter: (row: UserOrderItem) => waterText(row)
    },
    {
      prop: 'createTime',
      label: '下单时间',
      minWidth: 150,
      formatter: (row: UserOrderItem) => formatTime(row.createTime)
    }
  ]

  const loadOrders = async (current = orderPage.current) => {
    if (!props.userId) return
    orderPage.current = current
    orderLoading.value = true
    try {
      const res = await fetchUserProfileOrderPage({
        current,
        size: orderPage.size,
        userId: props.userId
      })
      orderRows.value = res.list || []
      orderPage.total = res.total || 0
    } finally {
      orderLoading.value = false
    }
  }

  // ---- 四、资金流水 ----
  const flowLoading = ref(false)
  const flowRows = ref<UserFlowItem[]>([])
  const flowPage = newPager()

  const flowColumns: ColumnOption[] = [
    {
      prop: 'createTime',
      label: '时间',
      minWidth: 150,
      formatter: (row: UserFlowItem) => formatTime(row.createTime)
    },
    { prop: 'cardNo', label: '卡号', minWidth: 150 },
    {
      prop: 'flowType',
      label: '类型',
      width: 110,
      formatter: (row: UserFlowItem) => dictLabel(DictTypeEnum.钱包流水类型, row.flowType)
    },
    {
      prop: 'amountChange',
      label: '余额变动(元)',
      width: 120,
      formatter: (row: UserFlowItem) => signedYuan(row.amountChange)
    },
    {
      prop: 'mlChange',
      label: '水量变动',
      width: 120,
      formatter: (row: UserFlowItem) => signedLiter(row.mlChange)
    },
    {
      prop: 'amountAfter',
      label: '变动后余额(元)',
      width: 130,
      formatter: (row: UserFlowItem) => fenToYuan(row.amountAfter)
    },
    {
      prop: 'mlAfter',
      label: '变动后水量',
      width: 130,
      formatter: (row: UserFlowItem) => mlToLiter(row.mlAfter)
    },
    {
      prop: 'flowRemark',
      label: '说明',
      minWidth: 160,
      formatter: (row: UserFlowItem) => row.flowRemark || '-'
    }
  ]

  const loadFlows = async (current = flowPage.current) => {
    if (!props.userId) return
    flowPage.current = current
    flowLoading.value = true
    try {
      const res = await fetchUserProfileFlowPage({
        current,
        size: flowPage.size,
        userId: props.userId
      })
      flowRows.value = res.list || []
      flowPage.total = res.total || 0
    } finally {
      flowLoading.value = false
    }
  }

  // ---- 五、关系归属 ----
  const relationLoading = ref(false)
  const relation = ref<UserRelation>({})
  const inviteeLoading = ref(false)
  const inviteeRows = ref<UserRelationItem[]>([])
  const inviteePage = newPager()

  const inviteeColumns: ColumnOption[] = [
    { prop: 'id', label: '用户ID', width: 120 },
    { prop: 'userName', label: '姓名', minWidth: 120 },
    {
      prop: 'userPhone',
      label: '手机号',
      minWidth: 130,
      formatter: (row: UserRelationItem) => row.userPhone || '-'
    },
    {
      prop: 'createTime',
      label: '注册时间',
      minWidth: 150,
      formatter: (row: UserRelationItem) => formatTime(row.createTime)
    }
  ]

  const loadRelation = async () => {
    if (!props.userId) return
    relationLoading.value = true
    try {
      relation.value = await fetchUserProfileRelation(props.userId)
    } finally {
      relationLoading.value = false
    }
    await loadInvitees(1)
  }

  const loadInvitees = async (current = inviteePage.current) => {
    if (!props.userId) return
    inviteePage.current = current
    inviteeLoading.value = true
    try {
      const res = await fetchUserProfileInviteePage({
        current,
        size: inviteePage.size,
        userId: props.userId
      })
      inviteeRows.value = res.list || []
      inviteePage.total = res.total || 0
    } finally {
      inviteeLoading.value = false
    }
  }

  // ---- 六、审计记录 ----
  const auditLoading = ref(false)
  const auditRows = ref<UserAuditItem[]>([])
  const auditPage = newPager()

  const auditColumns: ColumnOption[] = [
    {
      prop: 'createTime',
      label: '时间',
      minWidth: 150,
      formatter: (row: UserAuditItem) => formatTime(row.createTime)
    },
    {
      prop: 'eventTypeName',
      label: '事件',
      minWidth: 130,
      formatter: (row: UserAuditItem) => row.eventTypeName || '-'
    },
    {
      prop: 'eventKey',
      label: '业务对象',
      minWidth: 200,
      formatter: (row: UserAuditItem) => row.eventKey || '-'
    },
    {
      prop: 'actorPortalName',
      label: '来源',
      minWidth: 110,
      formatter: (row: UserAuditItem) => row.actorPortalName || '-'
    }
  ]

  const loadAudits = async (current = auditPage.current) => {
    if (!props.userId) return
    auditPage.current = current
    auditLoading.value = true
    try {
      const res = await fetchUserProfileAuditPage({
        current,
        size: auditPage.size,
        userId: props.userId
      })
      auditRows.value = res.list || []
      auditPage.total = res.total || 0
    } finally {
      auditLoading.value = false
    }
  }

  /**
   * 分区取数的统一入口。失败时把该分区移出"已加载"集合并标成失败：
   * 否则一次 403/500 会让该分区永久停在空表上——切走再切回不重发（已在集合里），
   * 分页也因 total=0 点不动，运营看到的是一份"这个人没有流水"的权威结论。
   */
  const runSection = async (tab: TabName, run: () => Promise<void>) => {
    try {
      await run()
      failedSections.value.delete(tab)
    } catch {
      // 具体错误已由请求层统一提示，这里只负责让界面停在"失败可重试"而不是"空"。
      loadedTabs.value.delete(tab)
      failedSections.value.add(tab)
    }
  }

  const loaders: Record<TabName, () => Promise<void>> = {
    identity: loadIdentity,
    cards: () => loadCards(1),
    orders: () => loadOrders(1),
    flows: () => loadFlows(1),
    relation: loadRelation,
    audits: () => loadAudits(1)
  }

  /** 分页联动：翻页与改每页条数都重新取数，失败走与首次加载相同的失败处理。 */
  const bindPager = (tab: TabName, pager: Pager, load: (current: number) => Promise<void>) => ({
    onCurrent: (current: number) => void runSection(tab, () => load(current)),
    onSize: (size: number) => {
      pager.size = size
      void runSection(tab, () => load(1))
    }
  })

  const cardPager = bindPager('cards', cardPage, loadCards)
  const orderPager = bindPager('orders', orderPage, loadOrders)
  const flowPager = bindPager('flows', flowPage, loadFlows)
  const inviteePager = bindPager('relation', inviteePage, loadInvitees)
  const auditPager = bindPager('audits', auditPage, loadAudits)

  /** 空态文案：这一分区取数失败过就不许再说"暂无"。 */
  const sectionEmptyText = (tab: TabName, text: string) =>
    failedSections.value.has(tab) ? '加载失败' : text

  /** 首次进入某个分区才发请求；已加载过的分区保留结果，不重复拉取 */
  const ensureTab = async (tab: TabName) => {
    if (loadedTabs.value.has(tab)) return
    loadedTabs.value.add(tab)
    await runSection(tab, loaders[tab])
  }

  const selectTab = (name: TabName) => {
    activeTab.value = name
    void ensureTab(name)
  }

  const retryActiveTab = () => {
    loadedTabs.value.delete(activeTab.value)
    void ensureTab(activeTab.value)
  }

  const resetAll = () => {
    loadedTabs.value = new Set()
    failedSections.value = new Set()
    activeTab.value = 'identity'
    identity.value = {}
    cardRows.value = []
    courier.value = null
    orderRows.value = []
    flowRows.value = []
    relation.value = {}
    inviteeRows.value = []
    auditRows.value = []
    ;[cardPage, orderPage, flowPage, inviteePage, auditPage].forEach((p) => {
      p.current = 1
      p.total = 0
    })
  }

  watch(
    () => [props.visible, props.userId] as const,
    async ([visible]) => {
      if (!visible || !props.userId) return
      resetAll()
      try {
        await loadDicts()
      } catch {
        // 字典取不到时状态列退化成原始编号（dictLabel 的兜底），但档案本身必须能打开：
        // 让一次字典失败连带把身份资料也挡在外面，等于用一个次要依赖决定主视图能否使用。
      }
      await ensureTab('identity')
    },
    { immediate: true }
  )
</script>

<style scoped>
  .user-profile {
    min-height: 0;
    padding: 18px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 10px;
  }

  .user-profile__header {
    padding-bottom: 16px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  .user-profile__identity {
    display: flex;
    gap: 12px;
    align-items: center;
  }

  .user-profile__identity h2,
  .user-profile__identity p,
  .user-profile__section h3 {
    margin: 0;
  }

  .user-profile__identity h2 {
    font-size: 20px;
    line-height: 28px;
    color: var(--el-text-color-primary);
  }

  .user-profile__identity p {
    margin-top: 2px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .user-profile__avatar {
    display: grid;
    width: 44px;
    height: 44px;
    font-size: 17px;
    font-weight: 650;
    color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: 50%;
    place-items: center;
  }

  .user-profile__identity .el-tag {
    margin-left: auto;
  }

  .user-profile__layout {
    display: grid;
    grid-template-columns: 184px minmax(0, 1fr);
    gap: 20px;
    padding-top: 18px;
  }

  .user-profile__nav {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .user-profile__nav-item {
    display: grid;
    grid-template-columns: 20px minmax(0, 1fr) 16px;
    gap: 9px;
    align-items: center;
    width: 100%;
    padding: 10px;
    font: inherit;
    color: var(--el-text-color-regular);
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-radius: 7px;
  }

  .user-profile__nav-item:hover {
    background: var(--el-fill-color-light);
  }

  .user-profile__nav-item.is-active {
    color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
  }

  .user-profile__nav-item span {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .user-profile__nav-item strong {
    font-size: 13px;
    line-height: 18px;
  }

  .user-profile__nav-item small {
    margin-top: 1px;
    overflow: hidden;
    font-size: 11px;
    color: var(--el-text-color-secondary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .user-profile__nav-arrow {
    color: var(--el-text-color-placeholder);
  }

  .user-profile__content {
    min-width: 0;
  }

  .user-profile__section h3 {
    padding-bottom: 12px;
    font-size: 16px;
    color: var(--el-text-color-primary);
  }

  @media (width <= 900px) {
    .user-profile__layout {
      grid-template-columns: 1fr;
    }

    .user-profile__nav {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }
</style>
