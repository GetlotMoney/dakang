<!-- 订单全链路追溯抽屉（REQ-050）。八区块：①基本信息 ②支付事实 ③指令与回执 ④共享审计
     ⑤原扣款与返还流水 ⑥售后处理 ⑦配送轨迹与三照 ⑧申诉记录。
     售后区块取台账行按 orderId 精确匹配（模糊命中会让互为子串的订单号串行）；
     台账没下发的字段一律不显示，本抽屉是对账证据，不在前端凑数。 -->
<template>
  <ElDrawer v-model="drawerVisible" title="订单全链路追溯" size="760px" destroy-on-close>
    <div ref="traceContentRef" v-loading="loading">
      <template v-if="trace">
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
              >）
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
                  ? '套餐快照数据异常'
                  : '未记录套餐快照'
              }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="trace.order.cancelReason" label="异常/取消说明" :span="2">
            {{ trace.order.cancelReason }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <template v-if="isRealRecharge">
          <ElAlert
            v-if="!rechargeEvidenceOk"
            class="mt-3"
            type="error"
            :closable="false"
            show-icon
            title="充值关联数据异常"
            :description="`${rechargeMismatchReason}。请人工核查。`"
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
          />
          <ElAlert
            v-else-if="paymentEvidenceState === 'missing'"
            type="info"
            :closable="false"
            show-icon
            title="无支付记录"
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
              title="支付成功不等于权益已到账"
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
              :description="`${trace.command.linkReason || '指令与订单的数据不一致'}（指令号 ${trace.command.cmdNo}）`"
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
        <div class="section-title">状态审计</div>
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
        <ElEmpty v-else description="暂无关联审计事件" :image-size="50" />

        <!-- 区块五：充值单展示后端已核验的双维权益；其他订单保持原流水列表。
             本区块同时是「原扣款」与「售后返还流水」的证据面：两者都以 ORDER_ID 挂在本单上，
             由服务端按流水ID升序整体下发，页面不做拆分也不做正负归类。 -->
        <div class="section-title">原扣款 / 返还流水</div>
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
            :description="rechargeEvidenceOk ? '当前订单尚未产生完整入账流水' : '充值关联数据异常'"
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

        <!--
          区块六：售后处理（E2E-04 包E）。三条来源共用一份返还内核，因此售后动作、退款事实与
          补送任务是同一张台账的三种 actionType，而不是三份互不相干的记录。
        -->
        <template>
          <div class="section-title">
            售后处理
            <ElButton
              v-if="afterSaleActions.length"
              class="ml-auto"
              type="primary"
              size="small"
              link
              @click="goAfterSale"
            >
              进入售后台账
            </ElButton>
          </div>
          <ElAlert
            v-if="!canQueryAfterSale"
            type="info"
            :closable="false"
            show-icon
            title="当前账号没有售后台账查询权限"
          />
          <template v-else>
            <div v-loading="afterSaleLoading">
              <ElTable v-if="afterSaleActions.length" :data="afterSaleActions" border size="small">
                <ElTableColumn label="售后号 / 来源" min-width="185">
                  <template #default="{ row }">
                    <div>{{ row.afterSaleNo || '-' }}</div>
                    <div class="text-xs text-secondary">
                      {{ afterSaleSourceLabel(row.sourceType) }}
                    </div>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="动作 / 策略" min-width="150">
                  <template #default="{ row }">
                    <div>{{ afterSaleActionTypeLabel(row.actionType) }}</div>
                    <div class="text-xs text-secondary">
                      {{ afterSaleStrategyLabel(row.strategyCode)
                      }}<template v-if="row.approvedCount != null">
                        · 批准 {{ row.approvedCount }} 桶</template
                      >
                    </div>
                  </template>
                </ElTableColumn>
                <!-- 四元额度分列：合计相同而分项不同的两笔，运营口径完全不同 -->
                <ElTableColumn label="返还额度" min-width="180">
                  <template #default="{ row }">
                    <div>合计 {{ afterSaleFenText(row.refundAmount) }}</div>
                    <div class="text-xs text-secondary">
                      水品 {{ afterSaleFenText(row.refundProductFen) }} · 配送费
                      {{ afterSaleFenText(row.refundServiceFen) }}
                    </div>
                    <div v-if="row.refundProductMl" class="text-xs text-secondary">
                      水品水量 {{ mlToLiter(row.refundProductMl) }}
                    </div>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="状态" min-width="160">
                  <template #default="{ row }">
                    <ElTag size="small" :type="afterSaleStatusTagType(row.actionStatus)">
                      {{ afterSaleStatusLabel(row.actionStatus) }}
                    </ElTag>
                    <div v-if="row.lastError" class="text-xs flow-minus mt-1">
                      {{ row.lastError }}
                    </div>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="批准 / 终态" width="150">
                  <template #default="{ row }">
                    <div class="text-xs">{{ row.approveByName || '-' }}</div>
                    <div class="text-xs text-secondary">批准 {{ formatTime(row.approveTime) }}</div>
                    <div class="text-xs text-secondary">终态 {{ formatTime(row.finishTime) }}</div>
                  </template>
                </ElTableColumn>
              </ElTable>
              <ElEmpty v-else description="该订单没有售后动作" :image-size="50" />
            </div>
            <!--
              两条口径必须写在证据旁边，否则运营会把「已受理」读成「已退款」、
              把「已生成补送」读成「已补送到户」。
            -->
            <div v-if="hasGatewayRefundAction" class="mt-2 text-xs text-secondary">
              状态转为「已完成」前，不能认定已退款。
            </div>
            <div v-if="hasResendAction" class="mt-2 text-xs text-secondary">
              补送签收后才转「已完成」，履约进度见配送任务页。
            </div>
          </template>
        </template>

        <!-- 区块七（真实）：配送单真实聚合区块（E2E-03 包C）。
             履约链 linkStatus 与资金链 payment.flowStatus 独立核验；
             mismatch 只呈现数据异常，不把断链数据拼成履约/资金证据。 -->
        <template v-if="trace.deliveryTrace">
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
            :description="`${trace.deliveryTrace.linkReason || '任务与订单的数据不一致'}。请人工核查。`"
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
              <!-- D-214：payWay=3 水费以水量抵扣（金额恒 0），如实呈现抵扣行而不是 0 元水费 -->
              <ElDescriptionsItem label="费用快照" :span="2">
                <template v-if="trace.deliveryTrace.payWay === 3">
                  水量抵扣 {{ mlToLiter(trace.deliveryTrace.deductWaterMl) }} + 配送费
                  {{ fenToYuan(trace.deliveryTrace.deliveryFeeFen) }} 元 = 应扣
                  {{ fenToYuan(trace.deliveryTrace.totalAmountFen) }} 元
                </template>
                <template v-else>
                  水费 {{ fenToYuan(trace.deliveryTrace.waterAmountFen) }} 元 + 配送费
                  {{ fenToYuan(trace.deliveryTrace.deliveryFeeFen) }} 元 =
                  {{ fenToYuan(trace.deliveryTrace.totalAmountFen) }} 元
                </template>
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
              <div class="mb-2 text-xs text-secondary">签收三照（门牌 / 水品 / 摆放）</div>
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

            <div class="mt-3 mb-2 text-xs text-secondary">卡扣款流水</div>
            <ElAlert
              v-if="trace.deliveryTrace.payment?.flowStatus === 'mismatch'"
              type="error"
              :closable="false"
              show-icon
              title="配送资金链数据异常"
              :description="trace.deliveryTrace.payment?.flowReason || '扣款流水核对不一致'"
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
              <!-- D-214 payWay=3：同一行流水的水量扣减证据（ML_CHANGE/ML_AFTER 双列） -->
              <template v-if="(trace.deliveryTrace.payment.mlChange ?? 0) !== 0">
                <ElDescriptionsItem label="水量扣减">
                  <span class="flow-minus"
                    >-{{ mlToLiter(Math.abs(trace.deliveryTrace.payment.mlChange ?? 0)) }}</span
                  >
                </ElDescriptionsItem>
                <ElDescriptionsItem label="扣减后水量">
                  {{ mlToLiter(trace.deliveryTrace.payment.mlAfter) }}
                </ElDescriptionsItem>
              </template>
              <ElDescriptionsItem label="流水时间">{{
                formatTime(trace.deliveryTrace.payment.time)
              }}</ElDescriptionsItem>
              <ElDescriptionsItem label="业务幂等键">{{
                trace.deliveryTrace.payment.bizKey || '-'
              }}</ElDescriptionsItem>
            </ElDescriptions>

            <div class="mt-3 mb-2 text-xs text-secondary">配送站内消息</div>
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
                  {{ row.sendStatus === 4 ? '已送达' : '-' }}
                </template>
              </ElTableColumn>
              <ElTableColumn label="时间" width="150">
                <template #default="{ row }">{{ formatTime(row.sendTime) }}</template>
              </ElTableColumn>
            </ElTable>
            <ElEmpty v-else description="暂无配送站内消息" :image-size="50" />
          </template>
        </template>

        <!-- 区块八：申诉记录 -->
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
                {{ appeal.linkReason || '申诉数据核对不一致' }}
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
    paymentEvidenceStateOf,
    type OrderTraceVo,
    type FlowTrace
  } from '@/api/order'
  import {
    afterSaleStrategyLabel,
    AfterSaleActionStatus,
    AfterSaleActionType,
    AfterSalePerms,
    fetchAfterSaleActionPage,
    type AfterSaleActionItem
  } from '@/api/after-sale'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { fenToYuan, mlToLiter } from '@/utils/format'
  import { DictTypeEnum } from '@/constants/dict'
  import { useUserStore } from '@/store/modules/user'

  interface Props {
    visible: boolean
    orderId?: string
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
  const afterSaleSourceOptions = ref<{ label: string; value: number }[]>([])
  const afterSaleTypeOptions = ref<{ label: string; value: number }[]>([])
  const afterSaleStatusOptions = ref<{ label: string; value: number }[]>([])

  onMounted(async () => {
    const [
      orderStatuses,
      cmdStatuses,
      appealStatuses,
      afterSaleSources,
      afterSaleTypes,
      afterSaleStatuses
    ] = await Promise.all([
      fetchDictOptions(DictTypeEnum.订单状态),
      fetchDictOptions(DictTypeEnum.指令状态),
      fetchDictOptions(DictTypeEnum.申诉状态),
      fetchDictOptions(DictTypeEnum.售后来源),
      fetchDictOptions(DictTypeEnum.售后动作类型),
      fetchDictOptions(DictTypeEnum.售后执行状态)
    ])
    orderStatusOptions.value = toDictOptions(orderStatuses)
    cmdStatusOptions.value = toDictOptions(cmdStatuses)
    appealStatusOptions.value = toDictOptions(appealStatuses)
    afterSaleSourceOptions.value = toDictOptions(afterSaleSources)
    afterSaleTypeOptions.value = toDictOptions(afterSaleTypes)
    afterSaleStatusOptions.value = toDictOptions(afterSaleStatuses)
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

  // ============ 售后区块（E2E-04 包E） ============

  const userStore = useUserStore()
  const canQueryAfterSale = computed(() =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === AfterSalePerms.query)
  )
  const afterSaleActions = ref<AfterSaleActionItem[]>([])
  const afterSaleLoading = ref(false)

  const afterSaleDictLabel = (options: { label: string; value: number }[], value?: number) =>
    options.find((item) => item.value === value)?.label || (value == null ? '-' : String(value))
  const afterSaleSourceLabel = (value?: number) =>
    afterSaleDictLabel(afterSaleSourceOptions.value, value)
  const afterSaleActionTypeLabel = (value?: number) =>
    afterSaleDictLabel(afterSaleTypeOptions.value, value)
  const afterSaleStatusLabel = (value?: number) =>
    afterSaleDictLabel(afterSaleStatusOptions.value, value)
  const afterSaleFenText = (fen?: number) => (fen == null ? '-' : `￥${fenToYuan(fen)}`)
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

  const hasGatewayRefundAction = computed(() =>
    afterSaleActions.value.some((item) => item.actionType === AfterSaleActionType.GATEWAY_REFUND)
  )
  const hasResendAction = computed(() =>
    afterSaleActions.value.some((item) => item.actionType === AfterSaleActionType.RESEND)
  )

  /**
   * 按订单号关键字检索后，再按 orderId 精确过滤：台账 keyword 是 LIKE 模糊匹配，
   * 订单号互为子串时会混入他单的售后动作。
   */
  async function loadAfterSaleActions(
    orderId: string,
    orderNo: string | undefined,
    sequence: number
  ) {
    afterSaleActions.value = []
    if (!canQueryAfterSale.value || !orderNo) return
    afterSaleLoading.value = true
    try {
      const result = await fetchAfterSaleActionPage({ current: 1, size: 50, keyword: orderNo })
      // 竞态守卫（与主流程同一把 loadSequence）：本请求是第二跳，快速切换订单时
      // 乱序回包不比对序号会把 B 单的售后动作展示到 A 单上。
      if (sequence !== loadSequence) return
      afterSaleActions.value = result.list.filter((item) => item.orderId === orderId)
    } catch {
      // 售后台账不可用不应连累主追溯：主区块已渲染，此处保持空态即可。
      if (sequence === loadSequence) afterSaleActions.value = []
    } finally {
      if (sequence === loadSequence) afterSaleLoading.value = false
    }
  }

  function goAfterSale() {
    const orderNo = trace.value?.order.orderNo
    if (!orderNo) return
    drawerVisible.value = false
    router.push({ path: '/order/index', query: { view: 'aftersale', afterSaleKeyword: orderNo } })
  }

  const isRealRecharge = computed(() => trace.value?.order.orderType === 2)
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
    () => trace.value?.recharge?.linkReason || '充值关联数据核对不一致'
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
    source === 1 ? '微信支付' : source === 2 ? '模拟支付' : '来源缺失'

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
   * 实际使用人展示号码（UI-TRACE）：只用服务端脱敏结果，
   * 空串表示号码异常整体屏蔽，不回落 userPhone 明文。
   */
  const displayActorPhone = computed(() => trace.value?.order.actorMaskedPhone)

  /** 持卡人行展示条件：持卡人任一身份信息存在即展示；卡删除/查不到时整行隐藏（后端字段置空）。 */
  const hasCardOwnerIdentity = computed(() => {
    const order = trace.value?.order
    return !!(order?.cardOwnerUserId || order?.cardOwnerName || order?.cardOwnerMaskedPhone)
  })

  /**
   * P1-B「本单结算后」证据源：取本订单最后一条携带 AFTER 快照的有效流水（后端按流水 ID 升序下发，
   * 末条即本单终笔）。AFTER 是流水写入时冻结的卡面快照，永不随后续充值/取水漂移；
   * 历史数据无 AFTER 字段时保持 undefined，对应条目整行隐藏，不伪造数值。
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
    () => [props.visible, props.orderId] as const,
    async ([visible, orderId]) => {
      if (!visible || !orderId) return

      const currentSequence = ++loadSequence
      trace.value = null
      afterSaleActions.value = []
      loading.value = true
      await resetDrawerScroll()
      try {
        const result = await fetchOrderTrace(orderId)
        if (currentSequence === loadSequence) {
          trace.value = result
          if (result) {
            await loadAfterSaleActions(orderId, result.order.orderNo, currentSequence)
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
