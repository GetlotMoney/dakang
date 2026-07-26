<!-- 安全与合规（REQ-066）：展示当前事实、目标配置和外部依赖，不伪造备份或审计成功。 -->
<template>
  <div class="compliance-page">
    <ElAlert
      class="mb-3"
      type="warning"
      :closable="false"
      show-icon
      title="Demo 只定型合规责任与状态契约"
      description="本地 HTTP、演示数据和配置说明不等于生产合规。KMS、TLS 证书、异地灾备、恢复演练和正式审计导出必须在真实环境验收。"
    />

    <ElRow :gutter="16">
      <ElCol v-for="item in summary" :key="item.title" :xs="24" :sm="12" :xl="6">
        <ElCard shadow="never" class="summary-card">
          <div class="summary-card__top">
            <span>{{ item.title }}</span>
            <ElTag :type="item.type" effect="plain">{{ item.status }}</ElTag>
          </div>
          <div class="summary-card__value">{{ item.value }}</div>
          <div class="summary-card__desc">{{ item.description }}</div>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElCard shadow="never" class="section-card">
      <template #header>
        <div class="section-header">
          <div>
            <div class="section-title">数据分级与保护责任</div>
            <div class="section-subtitle">页面字段与后端事实源的正式边界</div>
          </div>
          <ElTag type="primary" effect="plain">PC 配置与审计责任</ElTag>
        </div>
      </template>
      <ElTable :data="dataClasses" border>
        <ElTableColumn prop="category" label="数据类别" min-width="150" />
        <ElTableColumn prop="examples" label="代表字段" min-width="220" />
        <ElTableColumn prop="storage" label="存储与展示要求" min-width="260" />
        <ElTableColumn prop="retention" label="留存原则" min-width="190" />
        <ElTableColumn label="当前状态" width="120">
          <template #default="{ row }">
            <ElTag :type="row.ready ? 'success' : 'warning'" size="small">
              {{ row.ready ? '基础可用' : '待接生产能力' }}
            </ElTag>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElRow :gutter="16">
      <ElCol :xs="24" :lg="12">
        <ElCard shadow="never" class="section-card">
          <template #header>
            <div class="section-header">
              <div>
                <div class="section-title">备份与恢复契约</div>
                <div class="section-subtitle">没有真实作业记录时，不显示“备份成功”</div>
              </div>
              <ElTag type="warning" effect="plain">基础设施待接入</ElTag>
            </div>
          </template>
          <ElTimeline>
            <ElTimelineItem
              v-for="item in backupContract"
              :key="item.stage"
              :type="item.ready ? 'success' : 'warning'"
              :hollow="!item.ready"
            >
              <div class="timeline-title">{{ item.stage }}</div>
              <div class="timeline-desc">{{ item.description }}</div>
              <div class="timeline-evidence">验收证据：{{ item.evidence }}</div>
            </ElTimelineItem>
          </ElTimeline>
        </ElCard>
      </ElCol>

      <ElCol :xs="24" :lg="12">
        <ElCard shadow="never" class="section-card">
          <template #header>
            <div class="section-header">
              <div>
                <div class="section-title">审计导出合同</div>
                <div class="section-subtitle"
                  >申请、状态追踪和失败重试可验收；真实文件仍待生产能力</div
                >
              </div>
              <ElButton
                v-if="hasPermission('system:audit:export')"
                type="primary"
                @click="openApplyDialog"
              >
                申请审计导出
              </ElButton>
            </div>
          </template>
          <ElAlert
            class="mb-3"
            type="info"
            :closable="false"
            title="手机号固定脱敏、身份信息不导出；Demo 不提供假下载按钮。"
          />
          <ElTable :data="exportTasks" border v-loading="exportLoading" max-height="360">
            <ElTableColumn prop="taskNo" label="任务号" min-width="190" />
            <ElTableColumn prop="exportScope" label="范围" min-width="150" show-overflow-tooltip />
            <ElTableColumn
              prop="filterSummary"
              label="筛选快照"
              min-width="220"
              show-overflow-tooltip
            />
            <ElTableColumn label="状态" width="90">
              <template #default="{ row }">
                <ElTag :type="exportStatusTag(row.taskStatus)" size="small">
                  {{ row.taskStatus }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <ElButton
                  v-if="row.taskStatus === '失败'"
                  type="primary"
                  link
                  @click="retryExport(row.id)"
                >
                  重试申请
                </ElButton>
                <span v-else class="section-subtitle">等待任务推进</span>
              </template>
            </ElTableColumn>
          </ElTable>
          <div class="mt-3 flex justify-between items-center">
            <span class="section-subtitle">任务终态必须包含文件摘要、过期时间与操作日志。</span>
            <ElButton @click="router.push('/system/log/operation')">查看操作日志</ElButton>
          </div>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElDialog v-model="applyVisible" title="申请审计导出" width="620px" align-center>
      <ElAlert
        class="mb-4"
        type="warning"
        :closable="false"
        title="提交只创建导出任务；真实文件生成、摘要与到期下载必须由后端和对象存储完成。"
      />
      <ElForm label-width="96px">
        <ElFormItem label="导出范围" required>
          <ElCheckboxGroup v-model="applyForm.exportScope">
            <ElCheckbox value="操作日志">操作日志</ElCheckbox>
            <ElCheckbox value="领域事件">领域事件</ElCheckbox>
            <ElCheckbox value="订单追溯">订单追溯</ElCheckbox>
            <ElCheckbox value="设备事件">设备事件</ElCheckbox>
          </ElCheckboxGroup>
        </ElFormItem>
        <ElFormItem label="操作人">
          <ElInput v-model="applyForm.operatorKeyword" placeholder="姓名或员工账号，可不填" />
        </ElFormItem>
        <ElFormItem label="业务对象">
          <ElInput v-model="applyForm.businessKeyword" placeholder="订单号、设备号或用户，可不填" />
        </ElFormItem>
        <ElFormItem label="时间范围" required>
          <ElInput v-model="applyForm.startTime" placeholder="开始时间" />
          <span class="range-separator">至</span>
          <ElInput v-model="applyForm.endTime" placeholder="结束时间" />
        </ElFormItem>
        <ElFormItem label="申请原因" required>
          <ElInput
            v-model="applyForm.applyReason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="applyVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="applySubmitting" @click="submitApply">
          创建导出任务
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, type TagProps } from 'element-plus'
  import {
    fetchAuditExportTaskList,
    fetchCreateAuditExportTask,
    fetchRetryAuditExportTask,
    type AuditExportApplyForm,
    type AuditExportTaskItem,
    type AuditExportTaskStatus
  } from '@/api/compliance'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'Compliance' })

  const router = useRouter()
  const userStore = useUserStore()
  const hasPermission = (permission: string) =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === permission)

  type TagType = TagProps['type']

  const exportLoading = ref(false)
  const applyVisible = ref(false)
  const applySubmitting = ref(false)
  const exportTasks = ref<AuditExportTaskItem[]>([])
  const applyForm = reactive<AuditExportApplyForm>({
    exportScope: ['操作日志'],
    operatorKeyword: '',
    businessKeyword: '',
    startTime: '2026-07-14 00:00',
    endTime: '2026-07-14 23:59',
    applyReason: ''
  })

  const summary: Array<{
    title: string
    status: string
    value: string
    description: string
    type: TagType
  }> = [
    {
      title: '权限隔离',
      status: '基础可用',
      value: 'RBAC',
      description: '员工、角色、菜单和按钮权限已具备',
      type: 'success'
    },
    {
      title: '操作留痕',
      status: '基础可用',
      value: '操作日志',
      description: '后台操作日志可查询，领域状态另写事件',
      type: 'success'
    },
    {
      title: '敏感数据',
      status: '待接 KMS',
      value: '密文契约',
      description: '身份字段禁止明文，生产密钥不得入库',
      type: 'warning'
    },
    {
      title: '灾备恢复',
      status: '待真实环境',
      value: 'RPO / RTO',
      description: '需要作业记录和恢复演练证明',
      type: 'warning'
    }
  ]

  const dataClasses = [
    {
      category: '身份与联系方式',
      examples: '手机号、身份信息、微信 openid',
      storage: '身份信息密文存储；页面按角色脱敏；禁止写入日志',
      retention: '按业务必要期限与删除权处理',
      ready: false
    },
    {
      category: '设备与遥测',
      examples: '心跳、TDS、滤芯、故障码',
      storage: '原始消息按 msgId 去重；聚合状态保留来源时间',
      retention: '满足故障追溯与对账窗口',
      ready: true
    },
    {
      category: '业务与审计',
      examples: '订单、指令、ACK、领域事件',
      storage: '状态机单向流转；旧值/新值和操作身份留痕',
      retention: '按合同与监管要求留存',
      ready: true
    },
    {
      category: '资金与对账',
      examples: '支付、退款、分账、钱包流水',
      storage: '不可由前端改写；回调幂等；导出需独立权限',
      retention: '财务口径待甲方确认',
      ready: false
    }
  ]

  const backupContract = [
    {
      stage: '每日全量备份',
      description: '生产 MySQL 定时全量备份并加密保存',
      evidence: '作业 ID、文件摘要、开始/结束时间',
      ready: false
    },
    {
      stage: '实时增量备份',
      description: '启用 binlog 并持续复制到独立存储',
      evidence: '复制延迟、断点位置、告警记录',
      ready: false
    },
    {
      stage: '异地副本',
      description: '备份副本与业务主机、主可用区隔离',
      evidence: '存储位置、保留策略、访问审计',
      ready: false
    },
    {
      stage: '恢复演练',
      description: '按季度恢复到隔离环境并核对订单/流水一致性',
      evidence: '演练单、RPO/RTO、核对结果与问题单',
      ready: false
    }
  ]

  const exportStatusTag = (status: AuditExportTaskStatus): TagType => {
    if (status === '已生成') return 'success'
    if (status === '失败') return 'danger'
    if (status === '生成中') return 'primary'
    return 'warning'
  }

  async function loadExportTasks() {
    exportLoading.value = true
    try {
      exportTasks.value = await fetchAuditExportTaskList()
    } finally {
      exportLoading.value = false
    }
  }

  function openApplyDialog() {
    Object.assign(applyForm, {
      exportScope: ['操作日志'],
      operatorKeyword: '',
      businessKeyword: '',
      startTime: '2026-07-14 00:00',
      endTime: '2026-07-14 23:59',
      applyReason: ''
    })
    applyVisible.value = true
  }

  async function submitApply() {
    if (!applyForm.exportScope.length || !applyForm.startTime || !applyForm.endTime) {
      ElMessage.warning('请选择导出范围和时间范围')
      return
    }
    if (!applyForm.applyReason.trim()) {
      ElMessage.warning('申请原因不能为空')
      return
    }
    applySubmitting.value = true
    try {
      await fetchCreateAuditExportTask({
        ...applyForm,
        exportScope: [...applyForm.exportScope],
        applyReason: applyForm.applyReason.trim()
      })
      applyVisible.value = false
      await loadExportTasks()
      ElMessage.success('审计导出任务已创建，等待后端生成能力接入')
    } finally {
      applySubmitting.value = false
    }
  }

  async function retryExport(id: number) {
    const success = await fetchRetryAuditExportTask(id)
    if (!success) {
      ElMessage.warning('仅失败任务可以重试')
      return
    }
    await loadExportTasks()
    ElMessage.success('任务已回到待生成状态')
  }

  onMounted(loadExportTasks)
</script>

<style scoped lang="scss">
  .compliance-page {
    padding-bottom: 16px;
  }

  .summary-card,
  .section-card {
    margin-bottom: 16px;
  }

  .summary-card {
    min-height: 164px;
  }

  .summary-card__top,
  .section-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
  }

  .summary-card__value {
    margin-top: 18px;
    font-size: 26px;
    font-weight: 650;
  }

  .summary-card__desc,
  .section-subtitle,
  .timeline-desc,
  .timeline-evidence {
    color: var(--art-text-gray-600);
  }

  .range-separator {
    margin: 0 8px;
    color: var(--art-text-gray-600);
  }

  .summary-card__desc,
  .section-subtitle {
    margin-top: 6px;
    font-size: 13px;
  }

  .section-title,
  .timeline-title {
    font-weight: 600;
  }

  .timeline-desc {
    margin-top: 4px;
  }

  .timeline-evidence {
    margin-top: 4px;
    font-size: 12px;
  }
</style>
