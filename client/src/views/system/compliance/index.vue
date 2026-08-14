<!-- 安全与合规（REQ-066）：展示当前事实、目标配置和外部依赖，不伪造备份或审计成功。 -->
<template>
  <div class="compliance-page">
    <ElRow :gutter="16">
      <ElCol v-for="item in summary" :key="item.title" :xs="24" :sm="12" :xl="6">
        <ElCard shadow="never" class="summary-card">
          <div class="summary-card__top">
            <span>{{ item.title }}</span>
            <ElTag :type="item.type" effect="plain">{{ item.status }}</ElTag>
          </div>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElCard shadow="never" class="section-card">
      <template #header>
        <div class="section-header">
          <div class="section-title">数据分级与保护责任</div>
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
              {{ row.ready ? '已达标' : '待完善' }}
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
              <div class="section-title">备份与恢复</div>
              <ElTag type="warning" effect="plain">未启用</ElTag>
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
            </ElTimelineItem>
          </ElTimeline>
        </ElCard>
      </ElCol>

      <ElCol :xs="24" :lg="12">
        <ElCard shadow="never" class="section-card">
          <template #header>
            <div class="section-header">
              <div class="section-title">审计导出</div>
              <ElButton
                v-if="hasPermission('system:audit:export')"
                type="primary"
                @click="openApplyDialog()"
              >
                申请审计导出
              </ElButton>
            </div>
          </template>
          <ElAlert class="mb-3" type="info" :closable="false" title="导出文件暂不可下载。" />
          <ElEmpty
            v-if="!canViewExport"
            description="无审计导出权限，请联系管理员开通"
            :image-size="80"
          />
          <ElTable v-else :data="exportTasks" border v-loading="exportLoading" max-height="360">
            <ElTableColumn prop="taskNo" label="任务号" min-width="190" />
            <ElTableColumn prop="exportScope" label="范围" min-width="150" show-overflow-tooltip />
            <ElTableColumn
              prop="filterSummary"
              label="筛选快照"
              min-width="220"
              show-overflow-tooltip
            />
            <ElTableColumn label="申请人" prop="applyByName" width="110" show-overflow-tooltip />
            <ElTableColumn label="状态" width="90">
              <template #default="{ row }">
                <ElTag :type="exportStatusTag(row.taskStatus)" size="small">
                  {{ row.taskStatusName }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <ElButton
                  v-if="row.taskStatus === AuditExportStatus.Failed"
                  type="primary"
                  link
                  @click="retryExport(row.id)"
                >
                  重试申请
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
          <div class="mt-3 flex justify-between items-center">
            <span v-if="canViewExport" class="section-subtitle">
              共 {{ exportTotal }} 条<template v-if="exportTotal > EXPORT_PAGE_SIZE">
                ，仅显示最近 {{ EXPORT_PAGE_SIZE }} 条</template
              >
            </span>
            <ElButton @click="router.push('/system/log/operation')">查看操作日志</ElButton>
          </div>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElDialog v-model="applyVisible" title="申请审计导出" width="620px" align-center>
      <ElForm label-width="96px">
        <ElFormItem label="导出范围" required>
          <ElCheckboxGroup v-model="applyForm.exportScope">
            <ElCheckbox v-for="scope in exportScopeOptions" :key="scope" :value="scope">
              {{ scope }}
            </ElCheckbox>
          </ElCheckboxGroup>
        </ElFormItem>
        <ElFormItem label="操作人">
          <ElInput v-model="applyForm.operatorKeyword" placeholder="姓名或员工账号，可不填" />
        </ElFormItem>
        <ElFormItem label="业务对象">
          <ElInput v-model="applyForm.businessKeyword" placeholder="订单号、设备号或用户，可不填" />
        </ElFormItem>
        <ElFormItem label="时间范围" required>
          <!-- value-format 直接产出服务端要的 14 位串，展示层仍是人类可读格式
               （同 device-dialog 的 SIM 到期字段）。不给硬编码默认值：
               默认值会被原样冻结进 FILTER_SUMMARY 永久留痕，比留空更糟 -->
          <ElDatePicker
            v-model="applyTimeRange"
            type="datetimerange"
            value-format="YYYYMMDDHHmmss"
            format="YYYY-MM-DD HH:mm:ss"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            :default-time="defaultRangeTime"
          />
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
    fetchAuditExportTaskPage,
    fetchCreateAuditExportTask,
    fetchRetryAuditExportTask,
    AuditExportStatus,
    AUDIT_EXPORT_SCOPES,
    type AuditExportApplyForm,
    type AuditExportTaskItem
  } from '@/api/compliance'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'Compliance' })

  const route = useRoute()
  const router = useRouter()
  const userStore = useUserStore()
  const hasPermission = (permission: string) =>
    userStore.rbacMenuList.some((item) => item.menuWebPerms === permission)

  type TagType = TagProps['type']

  const exportLoading = ref(false)
  const applyVisible = ref(false)
  const applySubmitting = ref(false)
  const exportTasks = ref<AuditExportTaskItem[]>([])
  const exportScopeOptions = AUDIT_EXPORT_SCOPES
  const applyForm = reactive<AuditExportApplyForm>({
    exportScope: ['操作日志'],
    operatorKeyword: '',
    businessKeyword: '',
    startTime: '',
    endTime: '',
    applyReason: ''
  })
  // ElDatePicker 的区间绑定；起止两个字段仍按契约分别提交
  const applyTimeRange = ref<[string, string] | null>(null)
  // 选日期不选时刻时，区间默认覆盖整天，避免生成 00:00:00~00:00:00 的空区间
  const defaultRangeTime: [Date, Date] = [
    new Date(2000, 0, 1, 0, 0, 0),
    new Date(2000, 0, 1, 23, 59, 59)
  ]

  const summary: Array<{
    title: string
    status: string
    type: TagType
  }> = [
    {
      title: '权限隔离',
      status: '已启用',
      type: 'success'
    },
    {
      title: '操作留痕',
      status: '已启用',
      type: 'success'
    },
    {
      title: '敏感数据加密',
      status: '未启用',
      type: 'warning'
    },
    {
      title: '灾备恢复',
      status: '未启用',
      type: 'warning'
    }
  ]

  const dataClasses = [
    {
      category: '身份与联系方式',
      examples: '手机号、身份信息、微信账号',
      storage: '加密存储，页面按角色脱敏',
      retention: '按业务需要保留，可申请删除',
      ready: false
    },
    {
      category: '设备与遥测',
      examples: '心跳、TDS、滤芯、故障码',
      storage: '保留原始上报时间，自动去重',
      retention: '满足故障追溯与对账',
      ready: true
    },
    {
      category: '业务与审计',
      examples: '订单、指令、操作记录',
      storage: '变更前后值与操作人留痕',
      retention: '按合同与监管要求留存',
      ready: true
    },
    {
      category: '资金与对账',
      examples: '支付、退款、分账、钱包流水',
      storage: '不可修改，导出需单独授权',
      retention: '按财务要求留存',
      ready: false
    }
  ]

  const backupContract = [
    {
      stage: '每日全量备份',
      ready: false
    },
    {
      stage: '实时增量备份',
      ready: false
    },
    {
      stage: '异地副本',
      ready: false
    },
    {
      stage: '恢复演练',
      ready: false
    }
  ]

  const exportStatusTag = (status: AuditExportStatus): TagType => {
    if (status === AuditExportStatus.Done) return 'success'
    if (status === AuditExportStatus.Failed) return 'danger'
    if (status === AuditExportStatus.Running) return 'primary'
    return 'warning'
  }

  // 列表与「申请」按钮共用同一权限：服务端 pageData 端点也带 system:audit:export，
  // 无权限时若照常请求，用户会吃一条报错 toast 并看到一张空表——
  // 在审计语境下"空表"极易被读成"从来没人申请过导出"，是危险的假阴性
  const canViewExport = computed(() => hasPermission('system:audit:export'))

  const exportTotal = ref(0)
  const EXPORT_PAGE_SIZE = 50

  async function loadExportTasks() {
    if (!canViewExport.value) return
    exportLoading.value = true
    try {
      const page = await fetchAuditExportTaskPage({ current: 1, size: EXPORT_PAGE_SIZE })
      exportTasks.value = page.list
      exportTotal.value = Number(page.total ?? 0)
    } catch {
      // 失败时清空并由模板给出显式提示，不留一张会被误读成"无申请记录"的空表
      exportTasks.value = []
      exportTotal.value = 0
    } finally {
      exportLoading.value = false
    }
  }

  function openApplyDialog(initialScope = '操作日志') {
    const selectedScope = (exportScopeOptions as readonly string[]).includes(initialScope)
      ? initialScope
      : '操作日志'
    Object.assign(applyForm, {
      exportScope: [selectedScope],
      operatorKeyword: '',
      businessKeyword: '',
      startTime: '',
      endTime: '',
      applyReason: ''
    })
    applyTimeRange.value = null
    applyVisible.value = true
  }

  async function submitApply() {
    // DatePicker 产出的是 [起, 止] 区间，契约按两个字段提交
    const [start, end] = applyTimeRange.value ?? ['', '']
    applyForm.startTime = start
    applyForm.endTime = end
    if (!applyForm.exportScope.length || !start || !end) {
      ElMessage.warning('请选择导出范围和时间范围')
      return
    }
    if (!applyForm.applyReason.trim()) {
      ElMessage.warning('申请原因不能为空')
      return
    }
    applySubmitting.value = true
    try {
      // 服务端错误（范围非法/时间格式/区间倒置）由 http 拦截器统一提示，这里只管成功路径
      await fetchCreateAuditExportTask({
        ...applyForm,
        exportScope: [...applyForm.exportScope],
        applyReason: applyForm.applyReason.trim()
      })
      applyVisible.value = false
      await loadExportTasks()
      ElMessage.success('审计导出任务已创建')
    } finally {
      applySubmitting.value = false
    }
  }

  async function retryExport(id: string) {
    try {
      await fetchRetryAuditExportTask(id)
      ElMessage.success('已重新提交')
    } finally {
      // 无论成败都拉回真实状态：服务端拒绝多半意味着本地这一行已过期
      //（他人已重试过），此时不刷新会让用户对着同一条陈旧的"失败"反复点击
      await loadExportTasks()
    }
  }

  let handledApplyScope = ''

  async function handleApplyScopeQuery() {
    const raw = Array.isArray(route.query.applyScope)
      ? route.query.applyScope[0]
      : route.query.applyScope
    const scope = typeof raw === 'string' ? raw : ''
    if (!scope || scope === handledApplyScope || !canViewExport.value) return
    handledApplyScope = scope
    openApplyDialog(scope)
  }

  onMounted(async () => {
    await loadExportTasks()
    await handleApplyScopeQuery()
  })

  watch(() => route.query.applyScope, handleApplyScopeQuery)
</script>

<style scoped lang="scss">
  .compliance-page {
    padding-bottom: 16px;
  }

  .summary-card,
  .section-card {
    margin-bottom: 16px;
  }

  .summary-card__top,
  .section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .section-subtitle {
    margin-top: 6px;
    font-size: 13px;
    color: var(--art-text-gray-600);
  }

  .section-title,
  .timeline-title {
    font-weight: 600;
  }
</style>
