<!--
  申诉处理（REQ-017，E2E-03 包C 已接真）：PC 核验证据后登记裁决，事务由后端包A 收口。
  E2E-04 包E 起，本页同时是「裁决 → 售后执行」的落点：裁决只登记一笔待执行的售后动作，
  真正的资金返还／外部退款／补送生成在同一个抽屉里完成，运营不必再跳到别处找这笔动作。
-->
<template>
  <div class="appeal-page art-full-height">
    <BusinessModuleNav module-key="order" />

    <ElCard class="art-table-card" shadow="never">
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <ElRadioGroup v-model="statusFilter" @change="applyFilters">
          <ElRadioButton :value="0">全部</ElRadioButton>
          <ElRadioButton v-for="opt in appealStatusOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </ElRadioButton>
        </ElRadioGroup>
        <ElInput
          v-model="orderKeyword"
          placeholder="关联订单号"
          clearable
          style="width: 210px"
          @input="applyFiltersDebounced"
        />
      </div>

      <!-- 一行 = 一个案件（taskId）；同订单的多次申诉由后端聚合，行内展示代表申诉 -->
      <ElTable :data="list" row-key="taskId" border v-loading="loading">
        <ElTableColumn label="关联订单 / 任务号" min-width="200" fixed="left">
          <template #default="{ row }">
            <div>{{ row.orderNo || '-' }}</div>
            <div class="text-xs text-secondary">{{ row.taskNo || '-' }}</div>
          </template>
        </ElTableColumn>
        <!-- 反复申诉是运营信号：必须在列表一眼可见，不能只藏在抽屉里 -->
        <ElTableColumn label="申诉次数" width="100" align="center">
          <template #default="{ row }">
            <ElTag v-if="appealTimes(row) > 1" type="warning" effect="dark">
              {{ appealTimes(row) }} 次
            </ElTag>
            <span v-else>{{ appealTimes(row) }} 次</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="申诉用户" min-width="150">
          <template #default="{ row }">
            {{ row.userName || '-' }}
            <template v-if="row.userMaskedPhone">（{{ row.userMaskedPhone }}）</template>
          </template>
        </ElTableColumn>
        <ElTableColumn label="申诉原因" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <ElTag size="small" effect="plain">{{
              row.appealReasonLabel || row.appealReason || '-'
            }}</ElTag>
            <span v-if="row.appealDesc" class="ml-1">{{ row.appealDesc }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="实收" width="80">
          <template #default="{ row }">
            {{ row.receivedCount != null ? `${row.receivedCount} 桶` : '-' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag :type="statusTagType(row.appealStatus)">{{
              statusLabel(row.appealStatus)
            }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="申诉时间" width="170">
          <template #default="{ row }">
            <div>{{ formatTime(row.lastAppealTime || row.createTime) }}</div>
            <div v-if="appealTimes(row) > 1 && row.firstAppealTime" class="text-xs text-secondary">
              首次 {{ formatTime(row.firstAppealTime) }}
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="处理人 / 结果" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.handleResult">
              {{ row.handleByName || '-' }}：{{ row.handleResult }}
            </template>
            <template v-else>-</template>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <ElButton type="primary" size="small" link @click="showEvidence(row)">
              {{
                row.activeAppealId && hasPermission('order:appeal:handle')
                  ? '查看并裁决'
                  : '查看历史'
              }}
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="mt-3 flex justify-end">
        <ElPagination
          v-model:current-page="pageParams.current"
          v-model:page-size="pageParams.size"
          :total="total"
          layout="total, prev, pager, next"
          @change="loadData"
        />
      </div>
    </ElCard>

    <ElDrawer v-model="evidenceVisible" title="申诉证据与配送履约" size="720px" destroy-on-close>
      <div v-loading="evidenceLoading">
        <template v-if="evidence">
          <!--
            申诉往来：同一订单被驳回后再次申诉时，运营必须先看到上一轮的理由与裁决说明，
            否则是在缺上下文的情况下裁决。只有一轮时不出这个区块，避免空壳。
          -->
          <template v-if="appealHistory.length > 1">
            <div class="section-title">申诉往来（共 {{ appealHistory.length }} 次）</div>
            <ElTimeline class="pl-1">
              <ElTimelineItem
                v-for="(item, index) in appealHistory"
                :key="item.appealId"
                :type="item.appealId === evidence.appeal.appealId ? 'primary' : 'info'"
                :hollow="item.appealId !== evidence.appeal.appealId"
                :timestamp="formatTime(item.createTime)"
                placement="top"
              >
                <div class="flex flex-wrap items-center gap-2">
                  <span class="font-medium">第 {{ index + 1 }} 次申诉</span>
                  <ElTag size="small" effect="plain">
                    {{ item.appealReasonLabel || item.appealReason || '-' }}
                  </ElTag>
                  <ElTag
                    v-if="item.appealId === evidence.appeal.appealId"
                    size="small"
                    type="primary"
                  >
                    本次
                  </ElTag>
                  <span class="text-xs text-secondary">
                    实收 {{ item.receivedCount != null ? `${item.receivedCount} 桶` : '未填写' }}
                  </span>
                </div>
                <div class="mt-1 text-sm">{{ item.appealDesc || '用户未填写说明' }}</div>
                <div v-if="item.handleResult" class="appeal-verdict">
                  <div class="flex flex-wrap items-center gap-2">
                    <ElTag :type="statusTagType(item.appealStatus)" size="small">
                      {{ statusLabel(item.appealStatus) }}
                    </ElTag>
                    <span class="text-xs text-secondary">
                      {{ item.handleByName || '-' }} · {{ formatTime(item.handleTime) }}
                    </span>
                  </div>
                  <div class="mt-1 text-sm">{{ item.handleResult }}</div>
                </div>
                <div v-else class="mt-1 text-xs text-secondary">尚未裁决</div>
              </ElTimelineItem>
            </ElTimeline>
          </template>

          <ElDescriptions :column="2" border label-width="96px">
            <ElDescriptionsItem label="关联订单">{{
              evidence.appeal.orderNo || '-'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="配送任务">{{
              evidence.appeal.taskNo || '-'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="申诉用户">
              {{ evidence.appeal.userName || '-' }}（{{ evidence.appeal.userMaskedPhone || '-' }}）
            </ElDescriptionsItem>
            <ElDescriptionsItem label="申诉状态">
              <ElTag :type="statusTagType(evidence.appeal.appealStatus)" size="small">
                {{ statusLabel(evidence.appeal.appealStatus) }}
              </ElTag>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="申诉原因">
              {{ evidence.appeal.appealReasonLabel || evidence.appeal.appealReason || '-' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="用户实收">
              {{
                evidence.appeal.receivedCount != null ? `${evidence.appeal.receivedCount} 桶` : '-'
              }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="申诉时间">{{
              formatTime(evidence.appeal.createTime)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="处理人">{{
              evidence.appeal.handleByName || '待处理'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="申诉说明" :span="2">{{
              evidence.appeal.appealDesc || '-'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem v-if="evidence.appeal.handleResult" label="处理结果" :span="2">
              {{ evidence.appeal.handleResult }}
            </ElDescriptionsItem>
          </ElDescriptions>

          <!-- fail-closed：申诉-任务-订单共键错位时不展示任务与举证，如实呈现数据异常 -->
          <ElAlert
            v-if="evidence.linkStatus === 'mismatch'"
            class="mt-3"
            type="error"
            :closable="false"
            show-icon
            title="申诉关联数据异常"
            :description="`${evidence.linkReason || '申诉与任务、订单的数据不一致'}。请人工核查后再裁决。`"
          />

          <template v-else>
            <div class="section-title">用户申诉举证</div>
            <div v-if="evidence.appealPhotos && evidence.appealPhotos.length" class="media-grid">
              <MediaRefCard v-for="ref in evidence.appealPhotos" :key="ref.mediaKey" :media="ref" />
            </div>
            <ElEmpty v-else description="用户未提交申诉举证" :image-size="56" />

            <div class="section-title">配送员举证</div>
            <template v-if="evidence.courierEvidences && evidence.courierEvidences.length">
              <div
                v-for="(item, index) in evidence.courierEvidences"
                :key="index"
                class="courier-evidence"
              >
                <div class="flex items-center justify-between">
                  <span class="font-medium">举证 {{ index + 1 }}</span>
                  <span class="text-xs text-secondary">{{ formatTime(item.time) }}</span>
                </div>
                <div class="mt-1 text-sm">{{ item.description || '-' }}</div>
                <div v-if="item.evidenceRefs.length" class="media-grid mt-2">
                  <MediaRefCard v-for="ref in item.evidenceRefs" :key="ref.mediaKey" :media="ref" />
                </div>
              </div>
            </template>
            <ElEmpty v-else description="配送员暂未追加举证" :image-size="56" />

            <template v-if="evidence.task">
              <div class="section-title section-title--between">
                <span>关联配送任务</span>
                <ElButton
                  v-if="evidence.task.orderId"
                  type="primary"
                  size="small"
                  link
                  @click="openOrderTrace"
                >
                  进入订单全链路追溯
                </ElButton>
              </div>
              <ElAlert
                v-if="evidence.task.linkStatus === 'mismatch'"
                type="error"
                :closable="false"
                show-icon
                title="履约任务数据异常"
                :description="evidence.task.linkReason || '任务与订单的数据不一致'"
              />
              <template v-else>
                <ElDescriptions :column="2" border label-width="96px">
                  <ElDescriptionsItem label="任务号">{{ evidence.task.taskNo }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="任务状态">{{
                    taskStatusLabel(evidence.task.taskStatus)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="配送员">
                    {{ evidence.task.courierName || '未分配' }}
                    <template v-if="evidence.task.courierMaskedPhone"
                      >（{{ evidence.task.courierMaskedPhone }}）</template
                    >
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="水品 / 数量">
                    {{ evidence.task.waterTypeName || '-' }} ·
                    {{ evidence.task.containerSpec || '-' }} ×
                    {{ evidence.task.deliveryCount ?? '-' }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="实际签收">
                    <template v-if="evidence.task.actualDeliveryCount != null">
                      {{ evidence.task.actualDeliveryCount }} 桶
                      <ElTag
                        v-if="
                          evidence.task.deliveryCount != null &&
                          evidence.task.actualDeliveryCount < evidence.task.deliveryCount
                        "
                        type="danger"
                        size="small"
                      >
                        少
                        {{ evidence.task.deliveryCount - evidence.task.actualDeliveryCount }} 桶
                      </ElTag>
                    </template>
                    <template v-else>未形成签收数量</template>
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="价格快照">
                    水费 {{ fenToYuan(evidence.task.waterAmountFen) }} 元 + 配送费
                    {{ fenToYuan(evidence.task.deliveryFeeFen) }} 元
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="签收时间">{{
                    formatTime(evidence.task.signTime)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="申诉截止">{{
                    formatTime(evidence.task.appealDeadline)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="收货地址" :span="2">{{
                    evidence.task.receiveAddress || '-'
                  }}</ElDescriptionsItem>
                </ElDescriptions>

                <div class="section-title">配送签收三照</div>
                <div
                  v-if="evidence.task.signPhotos && evidence.task.signPhotos.length"
                  class="media-grid"
                >
                  <div
                    v-for="photo in evidence.task.signPhotos"
                    :key="photo.mediaKey"
                    class="media-card"
                  >
                    <div class="media-card__title">
                      {{ photo.typeLabel || `类型${photo.type}` }}
                      <ElTag :type="photo.mediaStatus === 'ok' ? 'success' : 'danger'" size="small">
                        {{ mediaStatusLabel(photo.mediaStatus) }}
                      </ElTag>
                    </div>
                    <div class="media-card__meta">拍摄 {{ formatTime(photo.time) }}</div>
                    <div class="media-card__meta">
                      GPS
                      {{
                        photo.latitude != null && photo.longitude != null
                          ? `${photo.latitude}, ${photo.longitude}`
                          : '未记录'
                      }}
                    </div>
                    <div v-if="photo.mediaStatus === 'ok'" class="media-card__meta">
                      {{ photo.mimeType }} · {{ formatSize(photo.sizeBytes) }}
                    </div>
                    <div v-else class="media-card__meta media-card__meta--danger">
                      {{ photo.mediaReason || '媒体核验未通过' }}
                    </div>
                    <div class="media-card__key" :title="photo.mediaKey">{{ photo.mediaKey }}</div>
                  </div>
                </div>
                <ElEmpty v-else description="当前任务没有签收三照" :image-size="56" />
              </template>
            </template>
          </template>

          <!--
            本次申诉产生的售后动作（E2E-04 包E）。按 sourceType=配送申诉 且 sourceId=本申诉ID
            精确匹配：同一订单可能有多轮申诉、也可能同时挂着配送取消的返还，
            用订单号一把抓会把别的动作摆到这次裁决下面，运营会照着它去执行。
          -->
          <template v-if="canQueryAfterSale && appealActions.length">
            <div class="section-title section-title--between">
              <span>本次裁决产生的售后动作</span>
              <ElButton type="primary" size="small" link @click="goAfterSaleLedger">
                在售后台账中查看
              </ElButton>
            </div>
            <div v-loading="appealActionsLoading">
              <div v-for="action in appealActions" :key="action.id" class="after-sale-card">
                <div class="flex flex-wrap items-center gap-2">
                  <ElTag size="small" :type="afterSaleStatusTagType(action.actionStatus)">
                    {{ afterSaleStatusLabel(action.actionStatus) }}
                  </ElTag>
                  <span class="font-medium">{{ afterSaleTypeLabel(action.actionType) }}</span>
                  <ElTag size="small" effect="plain">
                    {{ afterSaleStrategyLabel(action.strategyCode) }}
                  </ElTag>
                  <span v-if="action.approvedCount != null" class="text-xs text-secondary">
                    批准 {{ action.approvedCount }} 桶
                  </span>
                  <span class="ml-auto text-xs text-secondary">{{ action.afterSaleNo }}</span>
                </div>
                <!-- 四元额度只读展示，全部由服务端在登记时算定并冻结 -->
                <div class="mt-2 text-sm">
                  返还合计 <span class="font-medium">{{ fenText(action.refundAmount) }}</span>
                  <span class="ml-2 text-xs text-secondary">
                    水品 {{ fenText(action.refundProductFen) }} · 配送费
                    {{ fenText(action.refundServiceFen) }}
                    <template v-if="action.refundProductMl">
                      · 水品水量 {{ mlToLiter(action.refundProductMl) }}
                    </template>
                  </span>
                </div>
                <div v-if="action.lastError" class="mt-1 text-xs text-danger">
                  最近失败原因：{{ action.lastError }}
                </div>
                <div class="mt-2 flex items-center gap-2">
                  <template v-if="executeEntryOf(action)">
                    <ElTooltip
                      v-if="!canUseExecuteEntry(executeEntryOf(action)!)"
                      content="当前账号没有该操作权限"
                      placement="top"
                    >
                      <span>
                        <ElButton size="small" type="danger" plain disabled>
                          {{ executeEntryOf(action)?.label }}
                        </ElButton>
                      </span>
                    </ElTooltip>
                    <ElTooltip
                      v-else-if="!executeEntryOf(action)?.enabled"
                      :content="executeEntryOf(action)?.disabledReason"
                      placement="top"
                    >
                      <span>
                        <ElButton size="small" type="danger" plain disabled>
                          {{ executeEntryOf(action)?.label }}
                        </ElButton>
                      </span>
                    </ElTooltip>
                    <ElButton
                      v-else
                      size="small"
                      type="danger"
                      @click="openExecute(action, executeEntryOf(action)!)"
                    >
                      {{ executeEntryOf(action)?.label }}
                    </ElButton>
                  </template>
                  <span v-else class="text-xs text-secondary">该动作不在此处执行</span>
                </div>
              </div>
            </div>
          </template>

          <div
            v-if="
              evidence.appeal.appealStatus === 1 &&
              evidence.linkStatus === 'ok' &&
              hasPermission('order:appeal:handle')
            "
            class="decision-bar"
          >
            <div class="font-medium">确认已完成证据核验</div>
            <ElButton type="primary" @click="openDecision(evidence.appeal)">登记裁决</ElButton>
          </div>
        </template>
      </div>
    </ElDrawer>

    <ElDialog v-model="decisionVisible" title="申诉裁决登记" width="540px" align-center>
      <ElAlert
        class="mb-4"
        type="warning"
        :closable="false"
        show-icon
        title="裁决不直接退款，返还需另行执行"
      />
      <ElForm label-width="96px">
        <ElFormItem label="关联订单">
          <ElInput :model-value="decisionTarget?.orderNo || '-'" disabled />
        </ElFormItem>
        <ElFormItem label="处理策略" required>
          <!--
            只提交策略码：申诉终态由服务端从策略码唯一派生（REJECT→不成立驳回、
            RESEND→补送待执行、其余→成立待补偿）。前端不再选终态，避免出现
            "驳回却带补偿策略"这种自相矛盾的组合。
          -->
          <!--
            D-414（2026-08-06 甲方确认）：履约后的售后不退配送费，只退水品——
            「只退配送费」「水品+配送费」两个策略从新裁决入口移除，服务端同样拒绝。
            类型与标签常量保留：历史裁决记录仍要渲染旧策略名。
          -->
          <ElRadioGroup v-model="decisionForm.strategyCode">
            <ElRadio value="REJECT">不成立驳回</ElRadio>
            <ElRadio value="RESEND">补送待执行</ElRadio>
            <ElRadio value="PRODUCT_ONLY">只补水品</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem v-if="needsApprovedCount" label="受影响数量" required>
          <ElInputNumber v-model="decisionForm.approvedCount" :min="1" :step="1" step-strictly />
          <span class="ml-2 text-xs text-secondary">单位：桶，超出可补偿数量会被拒绝</span>
        </ElFormItem>
        <ElFormItem label="裁决依据" required>
          <ElInput
            v-model="decisionForm.handleResult"
            type="textarea"
            :rows="4"
            maxlength="300"
            show-word-limit
            placeholder="请填写照片、时间、GPS、数量等核验依据"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="decisionVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="decisionSubmitting" @click="submitDecision">
          确认登记
        </ElButton>
      </template>
    </ElDialog>

    <AfterSaleExecuteDialog
      v-model:visible="executeVisible"
      :action="executeTarget"
      :mode="executeMode"
      :action-type-label="afterSaleTypeLabel"
      :action-status-label="afterSaleStatusLabel"
      @done="reloadAppealActions"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed, defineComponent, h, type PropType } from 'vue'
  import { ElMessage, ElTag } from 'element-plus'
  import {
    fetchAppealEvidence,
    fetchAppealPage,
    fetchDecideAppeal,
    type AdminMediaRef,
    type AppealAdminEvidence,
    type AppealAdminItem,
    type AppealStrategyCode
  } from '@/api/order'
  import {
    afterSaleExecuteEntry,
    afterSaleStrategyLabel,
    AfterSaleActionStatus,
    AfterSalePerms,
    AfterSaleSourceType,
    canUseAfterSaleExecuteEntry,
    fetchAfterSaleActionPage,
    type AfterSaleActionItem,
    type AfterSaleExecuteEntry,
    type AfterSaleExecuteMode
  } from '@/api/after-sale'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import BusinessModuleNav from '@/components/business/business-module-nav/index.vue'
  import AfterSaleExecuteDialog from '../modules/after-sale-execute-dialog.vue'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'OrderAppeal' })

  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()
  const hasPermission = (permission: string): boolean =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === permission)

  const readQueryValue = (value: unknown): string => {
    const raw = Array.isArray(value) ? value[0] : value
    return typeof raw === 'string' ? raw : ''
  }
  const readQueryNumber = (value: unknown): number => {
    const parsed = Number(readQueryValue(value))
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
  }

  const formatTime = (time?: string) => {
    if (!time || time.length !== 14) return time || '-'
    return `${time.slice(0, 4)}-${time.slice(4, 6)}-${time.slice(6, 8)} ${time.slice(8, 10)}:${time.slice(10, 12)}`
  }
  const formatSize = (bytes?: number) => {
    if (bytes == null || !Number.isFinite(bytes)) return '-'
    if (bytes < 1024) return `${bytes}B`
    return `${(bytes / 1024).toFixed(0)}KB`
  }
  const mediaStatusLabel = (status: AdminMediaRef['mediaStatus']) =>
    status === 'ok' ? '媒体已核验' : status === 'missing' ? '登记缺失' : '核验不符'

  /** 受控媒体元数据卡片（页内局部组件：一期无媒体下载出口，只展示核验元数据）。 */
  const MediaRefCard = defineComponent({
    name: 'MediaRefCard',
    props: { media: { type: Object as PropType<AdminMediaRef>, required: true } },
    setup(props) {
      return () =>
        h('div', { class: 'media-card' }, [
          h('div', { class: 'media-card__title' }, [
            h('span', '举证媒体'),
            h(
              ElTag,
              { type: props.media.mediaStatus === 'ok' ? 'success' : 'danger', size: 'small' },
              { default: () => mediaStatusLabel(props.media.mediaStatus) }
            )
          ]),
          h(
            'div',
            { class: 'media-card__meta' },
            props.media.mediaStatus === 'ok'
              ? `${props.media.mimeType || '-'} · ${formatSize(props.media.sizeBytes)} · 登记 ${formatTime(props.media.uploadTime)}`
              : props.media.mediaReason || '媒体核验未通过'
          ),
          h('div', { class: 'media-card__key', title: props.media.mediaKey }, props.media.mediaKey)
        ])
    }
  })

  const loading = ref(false)
  const list = ref<AppealAdminItem[]>([])
  const total = ref(0)
  const pageParams = reactive({ current: 1, size: 20 })
  let applyingFilters = false
  const statusFilter = ref(readQueryNumber(route.query.appealStatus))
  const orderKeyword = ref(readQueryValue(route.query.orderNo))
  const evidenceVisible = ref(false)
  const evidenceLoading = ref(false)
  const evidence = ref<AppealAdminEvidence | null>(null)
  const decisionVisible = ref(false)
  const decisionSubmitting = ref(false)
  const decisionTarget = ref<AppealAdminItem | null>(null)
  const decisionForm = reactive<{
    strategyCode: AppealStrategyCode
    approvedCount: number
    handleResult: string
  }>({
    strategyCode: 'REJECT',
    approvedCount: 1,
    handleResult: ''
  })

  /**
   * 除 REJECT 外所有策略（含 RESEND）都必须传批准数量：服务端非空校验会以 400 拒绝，
   * 此处漏掉某个策略该策略的裁决就 100% 提交失败且页面无补值入口。
   */
  const needsApprovedCount = computed(() => decisionForm.strategyCode !== 'REJECT')

  const appealStatusOptions = ref<{ label: string; value: number }[]>([
    { label: '待处理', value: 1 },
    { label: '成立待补偿', value: 2 },
    { label: '不成立驳回', value: 3 },
    { label: '已撤销', value: 4 },
    { label: '补送待执行', value: 5 }
  ])
  // ===== 售后动作（E2E-04 包E） =====
  const canQueryAfterSale = computed(() => hasPermission(AfterSalePerms.query))
  const appealActions = ref<AfterSaleActionItem[]>([])
  const appealActionsLoading = ref(false)
  const executeVisible = ref(false)
  const executeMode = ref<AfterSaleExecuteMode>('execute')
  const executeTarget = ref<AfterSaleActionItem | null>(null)
  const afterSaleTypeOptions = ref<{ label: string; value: number }[]>([])
  const afterSaleStatusOptions = ref<{ label: string; value: number }[]>([])

  const taskStatusOptions = ref<{ label: string; value: number }[]>([
    { label: '待接单', value: 1 },
    { label: '已接单', value: 2 },
    { label: '配送中', value: 3 },
    { label: '已送达待确认', value: 4 },
    { label: '已签收', value: 5 },
    { label: '已取消', value: 6 },
    { label: '申诉中', value: 7 }
  ])

  onMounted(async () => {
    try {
      const [appealStatuses, taskStatuses, afterSaleTypes, afterSaleStatuses] = await Promise.all([
        fetchDictOptions(DictTypeEnum.申诉状态),
        fetchDictOptions(DictTypeEnum.配送任务状态),
        fetchDictOptions(DictTypeEnum.售后动作类型),
        fetchDictOptions(DictTypeEnum.售后执行状态)
      ])
      const remoteAppealStatuses = toDictOptions(appealStatuses)
      const remoteTaskStatuses = toDictOptions(taskStatuses)
      if (remoteAppealStatuses.length) appealStatusOptions.value = remoteAppealStatuses
      if (remoteTaskStatuses.length) taskStatusOptions.value = remoteTaskStatuses
      afterSaleTypeOptions.value = toDictOptions(afterSaleTypes)
      afterSaleStatusOptions.value = toDictOptions(afterSaleStatuses)
    } catch {
      // 字典接口暂不可用时保留本地兜底选项，列表仍可查询。
    }
    await loadData()
  })

  watch(
    () => route.fullPath,
    () => {
      if (route.path !== '/order/appeal') return
      statusFilter.value = readQueryNumber(route.query.appealStatus)
      orderKeyword.value = readQueryValue(route.query.orderNo)
      pageParams.current = 1
      if (applyingFilters) return
      loadData()
    }
  )

  const statusLabel = (value?: number) =>
    appealStatusOptions.value.find((item) => item.value === value)?.label ||
    (value == null ? '-' : String(value))
  const statusTagType = (value?: number) =>
    value === 1
      ? 'warning'
      : value === 2 || value === 5
        ? 'success'
        : value === 3
          ? 'danger'
          : 'info'
  const taskStatusLabel = (value?: number) =>
    taskStatusOptions.value.find((item) => item.value === value)?.label ||
    (value == null ? '-' : String(value))

  /** 聚合行的累计申诉次数；后端未下发时按 1 次呈现，不臆造更大的数字。 */
  const appealTimes = (row: AppealAdminItem) => row.appealCount ?? 1
  /** 共键核验未通过时后端不下发往来（fail-closed），此处同样按空处理。 */
  const appealHistory = computed(() => evidence.value?.appealHistory ?? [])

  async function loadData() {
    loading.value = true
    try {
      const result = await fetchAppealPage({
        current: pageParams.current,
        size: pageParams.size,
        appealStatus: statusFilter.value || undefined,
        orderNo: orderKeyword.value || undefined
      })
      list.value = result.list
      total.value = result.total
    } finally {
      loading.value = false
    }
  }

  async function applyFilters() {
    pageParams.current = 1
    const query = {
      ...(statusFilter.value ? { appealStatus: String(statusFilter.value) } : {}),
      ...(orderKeyword.value ? { orderNo: orderKeyword.value } : {})
    }
    const target = router.resolve({ path: route.path, query }).fullPath
    if (target === route.fullPath) return loadData()
    applyingFilters = true
    try {
      await router.replace({ path: route.path, query })
      await nextTick()
      await loadData()
    } finally {
      applyingFilters = false
    }
  }

  const applyFiltersDebounced = useDebounceFn(() => applyFilters(), 350)

  async function showEvidence(row: AppealAdminItem) {
    evidence.value = null
    appealActions.value = []
    evidenceVisible.value = true
    evidenceLoading.value = true
    try {
      evidence.value = await fetchAppealEvidence(row.appealId)
      await reloadAppealActions()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载申诉证据失败')
    } finally {
      evidenceLoading.value = false
    }
  }

  const afterSaleDictLabel = (options: { label: string; value: number }[], value?: number) =>
    options.find((item) => item.value === value)?.label || (value == null ? '-' : String(value))
  const afterSaleTypeLabel = (value?: number) =>
    afterSaleDictLabel(afterSaleTypeOptions.value, value)
  const afterSaleStatusLabel = (value?: number) =>
    afterSaleDictLabel(afterSaleStatusOptions.value, value)
  const afterSaleStatusTagType = (value?: number) =>
    value === AfterSaleActionStatus.SUCCESS
      ? 'success'
      : value === AfterSaleActionStatus.RECONCILIATION_REQUIRED ||
          value === AfterSaleActionStatus.TERMINATED
        ? 'danger'
        : value === AfterSaleActionStatus.RETRY_WAIT
          ? 'warning'
          : value === AfterSaleActionStatus.PROCESSING
            ? 'primary'
            : 'info'
  const fenText = (fen?: number) => (fen == null ? '-' : `￥${fenToYuan(fen)}`)
  const executeEntryOf = (action: AfterSaleActionItem) => afterSaleExecuteEntry(action)
  const canUseExecuteEntry = (entry: AfterSaleExecuteEntry) =>
    canUseAfterSaleExecuteEntry(entry, hasPermission)

  /**
   * 拉取本次申诉产生的售后动作：台账 keyword 是模糊匹配，先按订单号检索、
   * 再按「来源=配送申诉 且 来源ID=本申诉ID」精确过滤（同一任务可合法有多条已裁决申诉）。
   */
  async function reloadAppealActions() {
    const appeal = evidence.value?.appeal
    if (!canQueryAfterSale.value || !appeal?.appealId || !appeal.orderNo) {
      appealActions.value = []
      return
    }
    appealActionsLoading.value = true
    try {
      const result = await fetchAfterSaleActionPage({
        current: 1,
        size: 50,
        keyword: appeal.orderNo,
        sourceType: AfterSaleSourceType.DELIVERY_APPEAL
      })
      appealActions.value = result.list.filter((item) => item.sourceId === appeal.appealId)
    } catch {
      // 售后台账不可用不应挡住证据核验：保持空态，运营仍可在售后台账页处理。
      appealActions.value = []
    } finally {
      appealActionsLoading.value = false
    }
  }

  function openExecute(action: AfterSaleActionItem, entry: AfterSaleExecuteEntry) {
    if (!canUseExecuteEntry(entry)) {
      ElMessage.warning('当前账号没有该操作权限')
      return
    }
    executeTarget.value = action
    executeMode.value = entry.mode
    executeVisible.value = true
  }

  function goAfterSaleLedger() {
    const orderNo = evidence.value?.appeal.orderNo
    if (!orderNo) return
    evidenceVisible.value = false
    router.push({ path: '/order/index', query: { view: 'aftersale', afterSaleKeyword: orderNo } })
  }

  function openOrderTrace() {
    const orderId = evidence.value?.task?.orderId
    if (!orderId) {
      ElMessage.warning('当前记录未关联有效订单，无法打开追溯')
      return
    }
    // 真实数据源：追溯走管理端 /order/order/trace，不再进入 Mock 分流
    router.push({
      path: '/order/index',
      query: {
        ...(evidence.value?.appeal.orderNo ? { orderNo: evidence.value.appeal.orderNo } : {}),
        traceOrderId: orderId
      }
    })
  }

  function openDecision(row: AppealAdminItem) {
    if (!hasPermission('order:appeal:handle')) {
      ElMessage.warning('当前账号没有申诉裁决权限')
      return
    }
    decisionTarget.value = row
    decisionForm.strategyCode = 'REJECT'
    decisionForm.approvedCount = 1
    decisionForm.handleResult = ''
    decisionVisible.value = true
  }

  async function submitDecision() {
    if (!decisionTarget.value) return
    if (!decisionForm.handleResult.trim()) {
      ElMessage.warning('请填写裁决依据')
      return
    }
    decisionSubmitting.value = true
    try {
      await fetchDecideAppeal({
        id: decisionTarget.value.appealId,
        strategyCode: decisionForm.strategyCode,
        // 非资金策略不带数量：后端 requireApprovedCount 只在资金策略下校验上界
        approvedCount: needsApprovedCount.value ? decisionForm.approvedCount : undefined,
        handleResult: decisionForm.handleResult.trim()
      })
      ElMessage.success('裁决已登记，返还需在下方售后动作中单独执行')
      decisionVisible.value = false
      // 裁决只登记一笔待执行的售后动作。抽屉保持打开并回表刷新，
      // 让运营立刻看到这笔动作并在同一屏执行；关掉抽屉等于把「还没执行」这件事藏起来。
      const appealId = decisionTarget.value.appealId
      try {
        evidence.value = await fetchAppealEvidence(appealId)
        await reloadAppealActions()
      } catch {
        // 回读失败不影响裁决结果本身，抽屉退回列表由运营重新打开
        evidenceVisible.value = false
      }
      await loadData()
    } finally {
      decisionSubmitting.value = false
    }
  }
</script>

<style scoped>
  .section-title {
    display: flex;
    align-items: center;
    margin: 20px 0 10px;
    font-size: 14px;
    font-weight: 600;
  }

  .section-title::before {
    width: 3px;
    height: 14px;
    margin-right: 8px;
    content: '';
    background: var(--el-color-primary);
    border-radius: 2px;
  }

  .section-title--between {
    justify-content: space-between;
  }

  .section-title--between > span {
    margin-right: auto;
  }

  .media-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
    gap: 12px;
  }

  :deep(.media-card) {
    padding: 10px 12px;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  :deep(.media-card__title) {
    display: flex;
    gap: 6px;
    align-items: center;
    justify-content: space-between;
    font-size: 13px;
    font-weight: 600;
  }

  :deep(.media-card__meta) {
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  :deep(.media-card__meta--danger) {
    color: var(--el-color-danger);
  }

  :deep(.media-card__key) {
    margin-top: 6px;
    overflow: hidden;
    font-size: 11px;
    color: var(--el-text-color-placeholder);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .appeal-verdict {
    padding: 8px 10px;
    margin-top: 8px;
    background: var(--el-fill-color-lighter);
    border-radius: 6px;
  }

  .courier-evidence {
    padding: 10px 12px;
    margin-bottom: 10px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .decision-bar {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
    padding: 14px 16px;
    margin-top: 20px;
    background: var(--el-color-warning-light-9);
    border: 1px solid var(--el-color-warning-light-5);
    border-radius: 8px;
  }

  .text-secondary {
    color: var(--el-text-color-secondary);
  }

  .text-danger {
    color: var(--el-color-danger);
  }

  .after-sale-card {
    padding: 12px 14px;
    margin-bottom: 10px;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  @media (width <= 768px) {
    .decision-bar {
      flex-direction: column;
      align-items: flex-start;
    }
  }
</style>
