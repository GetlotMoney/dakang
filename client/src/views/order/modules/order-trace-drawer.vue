<!-- 订单全链路追溯抽屉（REQ-050，MVP 验收核心）
     七区块：①基本信息 ②支付事实 ③指令与回执 ④共享审计 ⑤支付/扣减流水 ⑥配送轨迹与三照 ⑦申诉记录 -->
<template>
  <ElDrawer
    v-model="drawerVisible"
    :title="source === 'mock' ? '订单全链路追溯（Mock 演示）' : '订单全链路追溯'"
    size="760px"
    destroy-on-close
  >
    <div ref="traceContentRef" v-loading="loading">
      <template v-if="trace">
        <!-- Mock 数据源整体标识（2026-07-20 最终收口轮）：与真实追溯明确视觉区分，不只在审计区块标注 -->
        <ElAlert
          v-if="source === 'mock'"
          class="mb-3"
          type="warning"
          :closable="false"
          show-icon
          title="Mock 演示追溯"
          description="本抽屉全部区块均来自前端演示数据源（配送/申诉 Mock 域），不来自数据库真实记录；真实订单追溯请从订单列表打开。"
        />
        <!-- 区块一：基本信息 -->
        <ElDescriptions :column="2" border label-width="88px">
          <ElDescriptionsItem label="订单号" :span="2">
            {{ trace.order.orderNo }}
            <ElTag class="ml-2" size="small" :type="statusTagType(trace.order.orderStatus)">
              {{ orderStatusLabel(trace.order.orderStatus) }}
            </ElTag>
          </ElDescriptionsItem>
          <!-- UI-TRACE：使用人与持卡人两行分开呈现；角色由服务端 accessRole 下发，MEMBER 明显标注 -->
          <ElDescriptionsItem label="使用人">
            {{ trace.order.userName
            }}<template v-if="displayActorPhone">（{{ displayActorPhone }}）</template>
            <ElTag
              v-if="trace.order.accessRole === 'MEMBER'"
              type="warning"
              size="small"
              class="ml-1"
            >
              成员用卡
            </ElTag>
            <ElTag
              v-else-if="trace.order.accessRole === 'OWNER'"
              type="info"
              size="small"
              effect="plain"
              class="ml-1"
            >
              本人用卡
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="hasCardOwnerIdentity" label="持卡人">
            {{
              trace.order.cardOwnerName ||
              (trace.order.cardOwnerUserId ? `用户#${trace.order.cardOwnerUserId}` : '-')
            }}<template v-if="trace.order.cardOwnerMaskedPhone"
              >（{{ trace.order.cardOwnerMaskedPhone }}）</template
            >
          </ElDescriptionsItem>
          <ElDescriptionsItem label="金额"
            >￥{{ fenToYuan(trace.order.orderAmount) }}</ElDescriptionsItem
          >
          <ElDescriptionsItem v-if="trace.order.stationName" label="站点/设备">
            {{ trace.order.stationName }}
            <template v-if="trace.order.deviceNo">
              · {{ trace.order.deviceNo }} · {{ trace.order.outletNo }}号口
              <ElButton type="primary" size="small" link @click="goDevice">查看设备</ElButton>
            </template>
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="!isRealRecharge && trace.order.cardNo" label="扣费水卡">
            {{ trace.order.cardNo }}
          </ElDescriptionsItem>
          <ElDescriptionsItem
            v-else-if="rechargeEvidenceOk && rechargeDetail?.cardNo"
            :label="rechargeDetail.purchaseMode === 'FIRST_CARD' ? '新发虚拟卡' : '充值水卡'"
          >
            {{ rechargeDetail.cardNo }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="trace.order.planMl" label="计划水量">
            {{ mlToLiter(trace.order.planMl) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="trace.order.planMl" label="实际水量">
            <span :class="isShortfall ? 'shortfall' : ''">
              {{ trace.order.actualMl != null ? mlToLiter(trace.order.actualMl) : '出水中…' }}
            </span>
          </ElDescriptionsItem>
          <!-- P1-B 证据口径：「本单结算后余额/水量」＝本订单最后一条有效流水写入时冻结的 AFTER 快照；
               「当前水卡余额/水量」＝ws_card 当前只读值（会随后续充值/取水变化）。
               两者语义不同，禁止把 ws_card 当前值标成"本订单终值"。充值单由下方已核验充值区块呈现。 -->
          <ElDescriptionsItem
            v-if="!isRealRecharge && settledAfter?.amountAfter != null"
            label="本单结算后余额"
          >
            ￥{{ fenToYuan(settledAfter.amountAfter) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem
            v-if="!isRealRecharge && settledAfter?.mlAfter != null"
            label="本单结算后水量"
          >
            {{ mlToLiter(settledAfter.mlAfter) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem
            v-if="!isRealRecharge && trace.order.cardBalanceFen != null"
            label="当前水卡余额"
          >
            ￥{{ fenToYuan(trace.order.cardBalanceFen) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem
            v-if="!isRealRecharge && trace.order.cardBalanceMl != null"
            label="当前水卡水量"
          >
            {{ mlToLiter(trace.order.cardBalanceMl) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem
            v-if="
              !isRealRecharge &&
              trace.order.packageSnapshotState === 'valid' &&
              trace.order.packageSnapshot
            "
            label="套餐快照"
            :span="2"
          >
            {{ trace.order.packageSnapshot.packageName }}
            <span class="text-secondary">
              （快照价 ￥{{ fenToYuan(trace.order.packageSnapshot.payAmountFen)
              }}<template v-if="trace.order.packageSnapshot.waterMl">
                / {{ mlToLiter(trace.order.packageSnapshot.waterMl) }}</template
              >， 调价不溯及历史订单）
            </span>
          </ElDescriptionsItem>
          <ElDescriptionsItem
            v-else-if="!isRealRecharge && trace.order.orderType === 2"
            label="套餐快照"
            :span="2"
          >
            <ElTag type="danger" size="small" effect="plain">
              {{
                trace.order.packageSnapshotState === 'invalid'
                  ? '快照数据异常，禁止按页面推算权益'
                  : '未记录套餐快照'
              }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="trace.order.cancelReason" label="异常/取消说明" :span="2">
            {{ trace.order.cancelReason }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="trace.order.scenarioLabel" label="ACK 验收" :span="2">
            <ElTag type="warning" size="small" effect="plain">
              {{ trace.order.scenarioLabel }}
            </ElTag>
          </ElDescriptionsItem>
        </ElDescriptions>

        <template v-if="isRealRecharge">
          <ElAlert
            v-if="!rechargeEvidenceOk"
            class="mt-3"
            type="error"
            :closable="false"
            show-icon
            title="充值关联证据异常"
            :description="`${rechargeMismatchReason}。已隐藏支付成功、发卡和权益到账等正向证据，请核对订单、支付、事件、流水与水卡共键。`"
          />
          <template v-else-if="rechargeDetail">
            <div class="section-title">
              {{ rechargeDetail.cardId ? '充值与发卡事实' : '充值订单事实' }}
            </div>
            <ElDescriptions :column="2" border label-width="104px">
              <ElDescriptionsItem label="业务类型">
                {{ rechargeDetail.purchaseMode === 'FIRST_CARD' ? '首次购卡' : '已有卡充值' }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="套餐">
                {{ rechargeDetail.packageName }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="套餐金额">
                ￥{{ fenToYuan(rechargeDetail.payAmountFen) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="套餐权益">
                金额 {{ fenChangeText(rechargeDetail.bonusAmountFen) }}，水量
                {{ mlChangeText(rechargeDetail.waterMl) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem
                v-if="rechargeDetail.purchaseMode === 'FIRST_CARD' && rechargeDetail.issueOrderId"
                label="发行锚点"
              >
                订单 ID {{ rechargeDetail.issueOrderId }}
              </ElDescriptionsItem>
              <ElDescriptionsItem
                v-if="
                  rechargeDetail.purchaseMode === 'FIRST_CARD' && rechargeDetail.scopeDescription
                "
                label="初始可用范围"
              >
                {{ rechargeDetail.scopeDescription }}
              </ElDescriptionsItem>
              <ElDescriptionsItem v-if="rechargeDetail.cardId" label="卡有效期">
                {{
                  rechargeDetail.cardExpireTime ? formatTime(rechargeDetail.cardExpireTime) : '永久'
                }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="权益处理状态">
                {{ rechargeProcessingLabel(rechargeDetail.processingStatus) }}
              </ElDescriptionsItem>
            </ElDescriptions>
          </template>
        </template>

        <!-- 区块二：支付事实。支付成功只证明收款，不代表充值权益已经到账。 -->
        <template v-if="trace.order.orderType === 2 && (!isRealRecharge || rechargeEvidenceOk)">
          <div class="section-title">支付事实</div>
          <ElAlert
            v-if="paymentEvidenceState === 'invalid'"
            type="error"
            :closable="false"
            show-icon
            title="支付记录异常"
            description="关联支付记录处于非正常数据状态，已按异常证据呈现，不能据此认定收款或权益到账。"
          />
          <ElAlert
            v-else-if="paymentEvidenceState === 'missing'"
            type="info"
            :closable="false"
            show-icon
            title="无支付记录"
            description="当前订单未查询到关联支付记录，不能仅凭订单支付方式推断支付来源或支付结果。"
          />
          <template v-else>
            <ElDescriptions :column="2" border label-width="96px">
              <ElDescriptionsItem label="支付状态">
                <ElTag size="small" :type="displayPayStatus === 2 ? 'success' : 'info'">
                  {{ paymentStatusLabel(displayPayStatus) }}
                </ElTag>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="支付来源">
                {{ paymentSourceLabel(displayPaySource) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="交易号" :span="2">
                {{ maskedTransactionId(trace.order.transactionId) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="成功时间">
                {{ formatTime(trace.order.paySuccessTime) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="支付截止时间">
                {{ formatTime(trace.order.payExpireTime) }}
              </ElDescriptionsItem>
            </ElDescriptions>
            <ElAlert
              class="mt-2"
              type="warning"
              :closable="false"
              show-icon
              title="支付事实不等于权益到账"
              description="支付成功仅证明收款事实；充值权益到账仍须同时核对订单完成状态和资金／水量流水。"
            />
          </template>
        </template>

        <!-- 区块三：指令与回执（订单→指令→ACK→结果，取水单专属）；
             共键校验不一致时只呈现数据异常，不把他单/无主指令拼成本单履约证据（2026-07-20 收口轮） -->
        <template v-if="trace.command">
          <template v-if="trace.command.linkStatus === 'mismatch'">
            <div class="section-title">指令与回执</div>
            <ElAlert
              type="error"
              :closable="false"
              title="订单-指令关联数据异常"
              :description="`${trace.command.linkReason || '指令与订单共键不一致'}（指令号 ${trace.command.cmdNo}）。已按数据异常呈现，不作为本单履约证据。`"
            />
          </template>
          <template v-else>
            <div class="section-title"
              >指令与回执
              <ElTag
                size="small"
                :type="cmdStatusTagType(trace.command.cmdStatus ?? 0)"
                class="ml-2"
              >
                {{ cmdStatusLabel(trace.command.cmdStatus ?? 0) }}
              </ElTag>
            </div>
            <div class="mb-2 text-xs text-secondary">
              指令号 {{ trace.command.cmdNo }} · 设备 {{ trace.command.deviceNo }} · 载荷
              {{ trace.command.payload }}
            </div>
            <ElTimeline class="pl-1">
              <ElTimelineItem
                v-for="node in trace.command.timeline"
                :key="node.node"
                :type="node.tone"
                :timestamp="formatTime(node.time)"
                placement="top"
              >
                <div class="font-medium">{{ node.nodeLabel }}</div>
                <div v-if="node.detail" class="text-xs text-secondary mt-1">{{ node.detail }}</div>
              </ElTimelineItem>
            </ElTimeline>
          </template>
        </template>

        <!-- 区块四：关联审计事件（订单/指令/设备任一业务键可反查） -->
        <div class="section-title">
          状态审计
          <ElTag class="ml-2" type="info" size="small" effect="plain">
            {{ source === 'mock' ? '共享 Mock' : trace.auditEvents.length ? '真实接口' : '待接入' }}
          </ElTag>
        </div>
        <ElTable v-if="trace.auditEvents.length" :data="trace.auditEvents" border size="small">
          <ElTableColumn prop="eventTypeLabel" label="事件" min-width="120" />
          <ElTableColumn prop="eventKey" label="业务键" min-width="180" show-overflow-tooltip />
          <ElTableColumn prop="actorLabel" label="操作/触发方" min-width="145" />
          <ElTableColumn label="时间" width="165">
            <template #default="{ row }">{{ formatTime(row.time) }}</template>
          </ElTableColumn>
          <ElTableColumn
            prop="detail"
            label="状态变化与结果"
            min-width="260"
            show-overflow-tooltip
          />
        </ElTable>
        <ElEmpty
          v-else
          :description="source === 'mock' ? '暂无关联审计事件' : '真实后端暂未返回关联审计事件'"
          :image-size="50"
        />

        <!-- 区块五：充值单展示后端已核验的双维权益；其他订单和 Mock 保持原流水列表。 -->
        <div class="section-title">资金 / 水量流水</div>
        <template v-if="isRealRecharge">
          <ElDescriptions
            v-if="rechargeEvidenceOk && rechargeDetail && hasRechargeFlowEvidence"
            :column="3"
            border
            label-width="96px"
          >
            <ElDescriptionsItem label="金额变动">
              <span :class="flowToneClass(rechargeDetail.flowAmountChange)">
                {{ fenChangeText(rechargeDetail.flowAmountChange) }}
              </span>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="入账后金额">
              ￥{{ fenToYuan(rechargeDetail.flowAmountAfter ?? 0) }}
            </ElDescriptionsItem>
            <!-- P1-B：cardBalance* 是 ws_card 当前只读值，不得标成"终值"——后续消费会使其偏离本单入账后快照 -->
            <ElDescriptionsItem label="当前水卡余额">
              ￥{{ fenToYuan(rechargeDetail.cardBalanceFen ?? 0) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="水量变动">
              <span :class="flowToneClass(rechargeDetail.flowMlChange)">
                {{ mlChangeText(rechargeDetail.flowMlChange) }}
              </span>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="入账后水量">
              {{ mlToLiter(rechargeDetail.flowMlAfter ?? 0) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="当前水卡水量">
              {{ mlToLiter(rechargeDetail.cardBalanceMl ?? 0) }}
            </ElDescriptionsItem>
          </ElDescriptions>
          <ElEmpty
            v-else
            :description="
              rechargeEvidenceOk
                ? '当前订单尚未产生完整入账流水'
                : '充值关联校验未通过，不展示权益到账证据'
            "
            :image-size="50"
          />
        </template>
        <template v-else>
          <ElTable v-if="trace.flows.length" :data="trace.flows" border size="small">
            <ElTableColumn prop="flowType" label="流水类型" min-width="110" />
            <ElTableColumn label="变动" width="130" align="right">
              <template #default="{ row }">
                <span :class="row.amount < 0 ? 'flow-minus' : 'flow-plus'">
                  {{ row.amount > 0 ? '+' : '' }}{{ flowDisplay(row) }}
                </span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="时间" width="145">
              <template #default="{ row }">{{ formatTime(row.time) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="remark" label="备注" min-width="180" show-overflow-tooltip />
          </ElTable>
          <!-- 空态按订单状态中性呈现：只有真正待支付的订单才写"待支付"。 -->
          <ElEmpty
            v-else
            :description="
              trace.order.orderStatus === 1 ? '暂无流水（订单待支付）' : '该订单暂无流水记录'
            "
            :image-size="50"
          />
        </template>

        <!-- 区块六：配送轨迹与三照签收（配送单专属；Mock 演示形状） -->
        <template v-if="trace.delivery">
          <div class="section-title">
            配送履约
            <span class="ml-2 text-xs text-secondary"
              >任务 {{ trace.delivery.taskNo }} · 配送员
              {{ trace.delivery.courierName || '待接单' }}</span
            >
            <ElButton class="ml-auto" type="primary" size="small" link @click="goDelivery">
              查看配送任务
            </ElButton>
          </div>
          <ElSteps
            :active="deliveryActiveStep"
            finish-status="success"
            process-status="process"
            align-center
            class="mb-3"
          >
            <ElStep
              v-for="node in trace.delivery.nodes"
              :key="node.node"
              :title="node.node"
              :description="node.time ? formatTime(node.time) : ''"
            />
          </ElSteps>
          <template v-if="trace.delivery.signPhotos.length">
            <div class="mb-2 text-xs text-secondary"
              >三照签收（门牌/水品/摆放缺一不可，照片带时间戳与 GPS，REQ-016）</div
            >
            <div class="flex gap-3">
              <div v-for="photo in trace.delivery.signPhotos" :key="photo.type" class="sign-photo">
                <ElImage
                  :src="photo.url"
                  :preview-src-list="trace.delivery.signPhotos.map((p) => p.url)"
                  fit="cover"
                  style="width: 120px; height: 90px; border-radius: 6px"
                />
                <div class="text-xs text-center mt-1">{{ photo.label }}</div>
              </div>
            </div>
          </template>
          <div class="mt-3 mb-2 text-xs text-secondary">配送通知证据（REQ-059）</div>
          <ElTable
            v-if="trace.delivery.notifications.length"
            :data="trace.delivery.notifications"
            border
            size="small"
          >
            <ElTableColumn prop="channel" label="渠道" width="110" />
            <ElTableColumn prop="content" label="内容" min-width="220" show-overflow-tooltip />
            <ElTableColumn label="状态" width="90">
              <template #default="{ row }">
                {{ row.sendStatus === 2 ? '成功' : row.sendStatus === 4 ? '已降级' : '待处理' }}
              </template>
            </ElTableColumn>
          </ElTable>
        </template>

        <!-- 区块六（真实）：配送单真实聚合区块（E2E-03 包C）。
             履约链 linkStatus 与资金链 payment.flowStatus 独立核验；
             mismatch 只呈现数据异常，不把断链数据拼成履约/资金证据。 -->
        <template v-else-if="trace.deliveryTrace">
          <div class="section-title">
            配送履约
            <span v-if="trace.deliveryTrace.taskNo" class="ml-2 text-xs text-secondary">
              任务 {{ trace.deliveryTrace.taskNo }}
              <template v-if="trace.deliveryTrace.linkStatus === 'ok'">
                · 配送员 {{ trace.deliveryTrace.courierName || '待接单'
                }}<template v-if="trace.deliveryTrace.courierMaskedPhone"
                  >（{{ trace.deliveryTrace.courierMaskedPhone }}）</template
                >
              </template>
            </span>
            <ElButton class="ml-auto" type="primary" size="small" link @click="goDelivery">
              查看配送任务
            </ElButton>
          </div>
          <ElAlert
            v-if="trace.deliveryTrace.linkStatus === 'mismatch'"
            type="error"
            :closable="false"
            show-icon
            title="配送履约链数据异常"
            :description="`${trace.deliveryTrace.linkReason || '任务与订单共键核验未通过'}。已隐藏配送员、费用、时间线与三照等正向证据，请人工核查数据。`"
          />
          <template v-else>
            <ElDescriptions :column="2" border label-width="92px" class="mb-3">
              <ElDescriptionsItem label="收货用户">
                {{ trace.deliveryTrace.userName || '-' }}
                <template v-if="trace.deliveryTrace.userMaskedPhone"
                  >（{{ trace.deliveryTrace.userMaskedPhone }}）</template
                >
              </ElDescriptionsItem>
              <ElDescriptionsItem label="收货电话">{{
                trace.deliveryTrace.receiveMaskedPhone || '-'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="配送内容">
                {{ trace.deliveryTrace.waterTypeName || '-' }} ·
                {{ trace.deliveryTrace.containerSpec || '-' }} ×
                {{ trace.deliveryTrace.deliveryCount ?? '-' }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="实际签收">
                {{
                  trace.deliveryTrace.actualDeliveryCount != null
                    ? `${trace.deliveryTrace.actualDeliveryCount} 桶`
                    : '尚未签收'
                }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="费用快照" :span="2">
                水费 {{ fenToYuan(trace.deliveryTrace.waterAmountFen) }} 元 + 配送费
                {{ fenToYuan(trace.deliveryTrace.deliveryFeeFen) }} 元 =
                {{ fenToYuan(trace.deliveryTrace.totalAmountFen) }} 元
              </ElDescriptionsItem>
              <ElDescriptionsItem label="收水地址" :span="2">{{
                trace.deliveryTrace.receiveAddress || '-'
              }}</ElDescriptionsItem>
              <ElDescriptionsItem v-if="trace.deliveryTrace.appealDeadline" label="申诉截止">
                {{ formatTime(trace.deliveryTrace.appealDeadline) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem v-if="trace.deliveryTrace.locationStatus" label="签收定位">
                {{ trace.deliveryTrace.locationStatus === 1 ? '定位已记录' : '定位未记录' }}
              </ElDescriptionsItem>
            </ElDescriptions>
            <ElSteps
              :active="realDeliveryActiveStep"
              finish-status="success"
              process-status="process"
              align-center
              class="mb-3"
            >
              <ElStep
                v-for="node in trace.deliveryTrace.timeline || []"
                :key="node.node"
                :title="node.nodeLabel"
                :description="node.time ? formatTime(node.time) : ''"
              />
            </ElSteps>
            <template v-if="trace.deliveryTrace.signPhotos?.length">
              <div class="mb-2 text-xs text-secondary"
                >三照签收元数据（门牌/水品/摆放，REQ-016；一期无媒体下载出口，仅呈现核验元数据）</div
              >
              <div class="trace-media-grid">
                <div
                  v-for="photo in trace.deliveryTrace.signPhotos"
                  :key="photo.mediaKey"
                  class="trace-media-card"
                >
                  <div class="trace-media-card__title">
                    {{ photo.typeLabel || `类型${photo.type}` }}
                    <ElTag :type="photo.mediaStatus === 'ok' ? 'success' : 'danger'" size="small">
                      {{
                        photo.mediaStatus === 'ok'
                          ? '媒体已核验'
                          : photo.mediaStatus === 'missing'
                            ? '登记缺失'
                            : '核验不符'
                      }}
                    </ElTag>
                  </div>
                  <div class="trace-media-card__meta">拍摄 {{ formatTime(photo.time) }}</div>
                  <div class="trace-media-card__meta">
                    GPS
                    {{
                      photo.latitude != null && photo.longitude != null
                        ? `${photo.latitude}, ${photo.longitude}`
                        : '未记录'
                    }}
                  </div>
                  <div
                    v-if="photo.mediaStatus !== 'ok'"
                    class="trace-media-card__meta trace-media-card__meta--danger"
                  >
                    {{ photo.mediaReason || '媒体核验未通过' }}
                  </div>
                </div>
              </div>
            </template>

            <div class="mt-3 mb-2 text-xs text-secondary">
              卡扣款流水核验（幂等键 DELIVERY:订单号）
            </div>
            <ElAlert
              v-if="trace.deliveryTrace.payment?.flowStatus === 'mismatch'"
              type="error"
              :closable="false"
              show-icon
              title="配送资金链数据异常"
              :description="`${trace.deliveryTrace.payment?.flowReason || '扣款流水核验未通过'}。已隐藏流水证据，不能据此认定扣款成立。`"
            />
            <ElDescriptions
              v-else-if="trace.deliveryTrace.payment"
              :column="2"
              border
              label-width="104px"
            >
              <ElDescriptionsItem label="扣款金额">
                <span class="flow-minus"
                  >-￥{{
                    fenToYuan(Math.abs(trace.deliveryTrace.payment.amountChangeFen ?? 0))
                  }}</span
                >
              </ElDescriptionsItem>
              <ElDescriptionsItem label="扣款后余额">
                ￥{{ fenToYuan(trace.deliveryTrace.payment.amountAfterFen) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="流水时间">{{
                formatTime(trace.deliveryTrace.payment.time)
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="业务幂等键">{{
                trace.deliveryTrace.payment.bizKey || '-'
              }}</ElDescriptionsItem>
            </ElDescriptions>

            <div class="mt-3 mb-2 text-xs text-secondary">配送站内消息证据（REQ-059）</div>
            <ElTable
              v-if="trace.deliveryTrace.notifications?.length"
              :data="trace.deliveryTrace.notifications"
              border
              size="small"
            >
              <ElTableColumn prop="title" label="标题" min-width="130" show-overflow-tooltip />
              <ElTableColumn prop="content" label="内容" min-width="220" show-overflow-tooltip />
              <ElTableColumn label="状态" width="110">
                <template #default="{ row }">
                  {{ row.sendStatus === 4 ? '已送达(站内)' : `状态${row.sendStatus ?? '-'}` }}
                </template>
              </ElTableColumn>
              <ElTableColumn label="时间" width="150">
                <template #default="{ row }">{{ formatTime(row.sendTime) }}</template>
              </ElTableColumn>
            </ElTable>
            <ElEmpty v-else description="暂无配送站内消息" :image-size="50" />
          </template>
        </template>

        <!-- 区块七：申诉记录 -->
        <div class="section-title">
          申诉记录
          <ElButton
            v-if="trace.appeals.length"
            class="ml-auto"
            type="primary"
            size="small"
            link
            @click="goAppeal"
          >
            进入申诉处理
          </ElButton>
        </div>
        <template v-if="trace.appeals.length">
          <div v-for="appeal in trace.appeals" :key="appeal.appealId" class="appeal-card">
            <!-- 真实聚合行逐条共键核验：mismatch 行只有ID与原因，不展示状态/理由/裁决 -->
            <template v-if="appeal.linkStatus === 'mismatch'">
              <div class="flex items-center justify-between">
                <ElTag type="danger" size="small">申诉数据异常</ElTag>
                <span class="text-xs text-secondary">申诉 #{{ appeal.appealId }}</span>
              </div>
              <div class="mt-2 text-sm">
                {{ appeal.linkReason || '申诉共键核验未通过，已隐藏内容' }}
              </div>
            </template>
            <template v-else>
              <div class="flex items-center justify-between">
                <ElTag :type="appealStatusTagType(appeal.appealStatus ?? 0)" size="small">
                  {{ appealStatusLabel(appeal.appealStatus ?? 0) }}
                </ElTag>
                <span class="text-xs text-secondary">{{ formatTime(appeal.createTime) }}</span>
              </div>
              <div class="mt-2 text-sm">
                <ElTag v-if="appeal.appealReasonLabel" size="small" effect="plain" class="mr-1">
                  {{ appeal.appealReasonLabel }}
                </ElTag>
                {{ appeal.appealDesc || appeal.appealReason }}
                <span v-if="appeal.receivedCount != null" class="text-xs text-secondary">
                  （实收 {{ appeal.receivedCount }} 桶）
                </span>
              </div>
              <div v-if="appeal.handleResult" class="mt-1 text-xs text-secondary">
                处理结果：{{ appeal.handleResult
                }}<template v-if="appeal.handleTime"
                  >（{{ formatTime(appeal.handleTime) }}）</template
                >
              </div>
            </template>
          </div>
        </template>
        <ElEmpty v-else description="无申诉" :image-size="50" />
      </template>
      <ElEmpty v-else-if="!loading" description="未找到追溯数据" :image-size="56" />
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    fetchOrderTrace,
    fetchOrderTraceMock,
    paymentEvidenceStateOf,
    type OrderTraceVo,
    type FlowTrace
  } from '@/api/order'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'

  interface Props {
    visible: boolean
    orderId?: string
    /**
     * 追溯数据源（2026-07-20 收口轮复审）：real=管理端真实接口（默认）；
     * mock=配送/申诉等 Mock 模块的演示数据源。两者严格分流，互不回退。
     */
    source?: 'real' | 'mock'
  }

  const props = defineProps<Props>()
  const emit = defineEmits<{ (e: 'update:visible', value: boolean): void }>()
  const router = useRouter()

  const drawerVisible = computed({
    get: () => props.visible,
    set: (value) => emit('update:visible', value)
  })

  const loading = ref(false)
  const trace = ref<OrderTraceVo | null>(null)
  const traceContentRef = ref<HTMLElement | null>(null)
  let loadSequence = 0

  const orderStatusOptions = ref<{ label: string; value: number }[]>([])
  const cmdStatusOptions = ref<{ label: string; value: number }[]>([])
  const appealStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [orderStatuses, cmdStatuses, appealStatuses] = await Promise.all([
      fetchDictOptions(DictTypeEnum.订单状态),
      fetchDictOptions(DictTypeEnum.指令状态),
      fetchDictOptions(DictTypeEnum.申诉状态)
    ])
    orderStatusOptions.value = toDictOptions(orderStatuses)
    cmdStatusOptions.value = toDictOptions(cmdStatuses)
    appealStatusOptions.value = toDictOptions(appealStatuses)
  })

  const orderStatusLabel = (v: number) =>
    orderStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const cmdStatusLabel = (v: number) =>
    cmdStatusOptions.value.find((o) => o.value === v)?.label || String(v)
  const appealStatusLabel = (v: number) =>
    appealStatusOptions.value.find((o) => o.value === v)?.label || String(v)

  const statusTagType = (v: number) =>
    v === 4
      ? 'success'
      : v === 6
        ? 'warning'
        : v === 5 || v === 7 || v === 8
          ? 'danger'
          : v === 3
            ? 'primary'
            : 'info'
  const cmdStatusTagType = (v: number) =>
    v === 4 ? 'success' : v === 5 || v === 6 ? 'danger' : v === 7 ? 'warning' : 'primary'
  const appealStatusTagType = (v: number) =>
    v === 1 ? 'warning' : v === 2 ? 'success' : v === 3 ? 'danger' : 'info'

  const isRealRecharge = computed(
    () => props.source !== 'mock' && trace.value?.order.orderType === 2
  )
  const rechargeDetail = computed(() => trace.value?.recharge?.detail)
  const rechargeEvidenceOk = computed(
    () => trace.value?.recharge?.linkStatus === 'ok' && !!rechargeDetail.value
  )
  const hasRechargeFlowEvidence = computed(() => {
    const detail = rechargeDetail.value
    return (
      detail?.flowAmountChange != null &&
      detail.flowMlChange != null &&
      detail.flowAmountAfter != null &&
      detail.flowMlAfter != null &&
      detail.cardBalanceFen != null &&
      detail.cardBalanceMl != null
    )
  })
  const rechargeMismatchReason = computed(
    () => trace.value?.recharge?.linkReason || '服务端未返回充值共键核验结果'
  )

  const paymentEvidenceState = computed(() => {
    const order = trace.value?.order
    if (!order || order.orderType !== 2) return 'missing'
    if (isRealRecharge.value) return rechargeEvidenceOk.value ? 'valid' : 'invalid'
    return paymentEvidenceStateOf(order)
  })

  const displayPayStatus = computed(() =>
    isRealRecharge.value ? rechargeDetail.value?.payStatus : trace.value?.order.payStatus
  )
  const displayPaySource = computed(() =>
    isRealRecharge.value ? rechargeDetail.value?.paySource : trace.value?.order.paySource
  )

  const paymentStatusLabel = (status?: number) =>
    status === 1
      ? '待支付'
      : status === 2
        ? '支付成功'
        : status === 3
          ? '支付失败'
          : status === 4
            ? '已关闭'
            : '状态缺失'

  const paymentSourceLabel = (source?: number) =>
    source === 1 ? '微信支付' : source === 2 ? 'Pay-Sim' : '来源缺失'

  const maskedTransactionId = (transactionId?: string) => {
    if (!transactionId) return '-'
    if (transactionId.includes('*')) return transactionId
    if (transactionId.length <= 8)
      return `${transactionId.slice(0, 2)}***${transactionId.slice(-2)}`
    return `${transactionId.slice(0, 6)}…${transactionId.slice(-4)}`
  }

  const fenChangeText = (fen?: number) => {
    const value = fen ?? 0
    const sign = value > 0 ? '+' : value < 0 ? '-' : ''
    return `${sign}￥${fenToYuan(Math.abs(value))}`
  }
  const mlChangeText = (ml?: number) => {
    const value = ml ?? 0
    const sign = value > 0 ? '+' : value < 0 ? '-' : ''
    return `${sign}${mlToLiter(Math.abs(value))}`
  }
  const flowToneClass = (value?: number) =>
    (value ?? 0) > 0 ? 'flow-plus' : (value ?? 0) < 0 ? 'flow-minus' : 'text-secondary'
  const rechargeProcessingLabel = (status: string) =>
    ({
      WAITING_PAYMENT: '等待支付',
      PENDING: '待处理',
      PROCESSING: '处理中',
      PROCESSED: '已处理',
      RETRY_WAIT: '等待重试',
      RECONCILIATION_REQUIRED: '待人工对账'
    })[status] || `未知处理态（${status}）`
  const formatTime = (t?: string) => {
    if (!t || t.length !== 14) return t || '-'
    return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}:${t.slice(12, 14)}`
  }

  /** 流水显示：分→元 / 毫升→升 */
  const flowDisplay = (row: FlowTrace) =>
    row.unit === '分'
      ? `￥${(Math.abs(row.amount) / 100).toFixed(2)}`
      : mlToLiter(Math.abs(row.amount))

  /**
   * 实际使用人展示号码（UI-TRACE）：真实数据只用服务端脱敏结果，
   * 空串表示号码异常整体屏蔽，不回落 userPhone 明文；Mock 演示域无脱敏字段，沿用演示数据。
   */
  const displayActorPhone = computed(() =>
    props.source === 'mock' ? trace.value?.order.userPhone : trace.value?.order.actorMaskedPhone
  )

  /** 持卡人行展示条件：持卡人任一身份信息存在即展示；卡删除/查不到时整行隐藏（后端字段置空）。 */
  const hasCardOwnerIdentity = computed(() => {
    const order = trace.value?.order
    return !!(order?.cardOwnerUserId || order?.cardOwnerName || order?.cardOwnerMaskedPhone)
  })

  /**
   * P1-B「本单结算后」证据源：取本订单最后一条携带 AFTER 快照的有效流水（后端按流水 ID 升序下发，
   * 末条即本单终笔）。AFTER 是流水写入时冻结的卡面快照，永不随后续充值/取水漂移；
   * Mock 域与历史数据无 AFTER 字段时保持 undefined，对应条目整行隐藏，不伪造数值。
   */
  const settledAfter = computed(() => {
    const flows = trace.value?.flows || []
    for (let i = flows.length - 1; i >= 0; i--) {
      if (flows[i].amountAfter != null || flows[i].mlAfter != null) return flows[i]
    }
    return undefined
  })

  /** 出水不足高亮（异常订单口径 REQ-035） */
  const isShortfall = computed(
    () =>
      trace.value?.order.actualMl != null &&
      trace.value.order.planMl != null &&
      trace.value.order.actualMl < trace.value.order.planMl
  )

  const deliveryActiveStep = computed(() => {
    const nodes = trace.value?.delivery?.nodes || []
    return nodes.filter((n) => n.done).length
  })

  /** 真实配送区块节点完成数：done 由服务端按落库时间判定，页面不推导状态。 */
  const realDeliveryActiveStep = computed(() => {
    const nodes = trace.value?.deliveryTrace?.timeline || []
    return nodes.filter((n) => n.done).length
  })

  function goDevice() {
    if (!trace.value?.order.deviceNo) return
    drawerVisible.value = false
    router.push({ path: '/device/index', query: { deviceNo: trace.value.order.deviceNo } })
  }

  function goDelivery() {
    if (!trace.value) return
    drawerVisible.value = false
    router.push({ path: '/order/delivery', query: { keyword: trace.value.order.orderNo } })
  }

  function goAppeal() {
    if (!trace.value) return
    drawerVisible.value = false
    router.push({ path: '/order/appeal', query: { orderNo: trace.value.order.orderNo } })
  }

  const resetDrawerScroll = async () => {
    await nextTick()
    const drawerBody = traceContentRef.value?.parentElement
    if (drawerBody) drawerBody.scrollTop = 0
  }

  watch(
    () => [props.visible, props.orderId, props.source] as const,
    async ([visible, orderId, source]) => {
      if (!visible || !orderId) return

      const currentSequence = ++loadSequence
      trace.value = null
      loading.value = true
      await resetDrawerScroll()
      try {
        const result =
          source === 'mock' ? await fetchOrderTraceMock(orderId) : await fetchOrderTrace(orderId)
        if (currentSequence === loadSequence) {
          trace.value = result
          if (!result) {
            ElMessage.warning(`订单 #${orderId} 不在 Mock 演示数据源中，无法展示追溯`)
          }
        }
      } catch (error) {
        if (currentSequence === loadSequence) {
          ElMessage.error(error instanceof Error ? error.message : '加载订单追溯失败')
        }
      } finally {
        if (currentSequence === loadSequence) loading.value = false
      }
    }
  )
</script>

<style scoped>
  .section-title {
    display: flex;
    align-items: center;
    margin: 18px 0 10px;
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

  .text-secondary {
    color: var(--el-text-color-secondary);
  }

  .shortfall {
    font-weight: 700;
    color: var(--el-color-warning);
  }

  .flow-minus {
    color: var(--el-color-danger);
  }

  .flow-plus {
    color: var(--el-color-success);
  }

  .appeal-card {
    padding: 10px 12px;
    margin-bottom: 8px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .trace-media-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
    gap: 12px;
  }

  .trace-media-card {
    padding: 10px 12px;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .trace-media-card__title {
    display: flex;
    gap: 6px;
    align-items: center;
    justify-content: space-between;
    font-size: 13px;
    font-weight: 600;
  }

  .trace-media-card__meta {
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .trace-media-card__meta--danger {
    color: var(--el-color-danger);
  }
</style>
