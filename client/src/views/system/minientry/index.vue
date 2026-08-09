<!-- 小程序入口配置（S6）：草稿→发布→撤回；小程序只读已发布；Tabbar 与能力权限不受配置覆盖 -->
<template>
  <div class="art-full-height">
    <ElAlert
      type="info"
      :closable="false"
      show-icon
      title="功能入口与维护公告发布后小程序即刻可见，撤回即刻隐藏；已发布配置须先撤回才能修改"
      class="mb-3"
    />

    <ElCard class="art-table-card" v-loading="loading">
      <div class="mb-3">
        <ElButton type="primary" @click="openEdit(null)">新建入口</ElButton>
        <ElButton @click="load">刷新</ElButton>
      </div>
      <ElTable :data="rows" border>
        <ElTableColumn prop="entryKey" label="入口键" width="100" />
        <ElTableColumn label="类型" width="90">
          <template #default="{ row }">{{ typeLabel(row.entryType) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="entryName" label="展示名称" width="130" />
        <ElTableColumn prop="sortNo" label="排序" width="70" align="right" />
        <ElTableColumn label="启用" width="70" align="center">
          <template #default="{ row }">
            <ElTag :type="row.enabledFlag === 2 ? 'success' : 'info'" size="small">
              {{ row.enabledFlag === 2 ? '是' : '否' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="目标" min-width="180">
          <template #default="{ row }">
            {{ row.routeId || row.externalUrl || row.contentText || '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }">
            <ElTag
              :type="
                row.configStatus === 2 ? 'success' : row.configStatus === 3 ? 'warning' : 'info'
              "
              size="small"
            >
              {{ statusLabel(row.configStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <ElButton
              v-if="row.configStatus !== 2"
              size="small"
              link
              type="primary"
              @click="openEdit(row)"
            >
              编辑
            </ElButton>
            <ElButton
              v-if="row.configStatus !== 2"
              size="small"
              link
              type="success"
              @click="doPublish(row)"
            >
              发布
            </ElButton>
            <ElButton
              v-if="row.configStatus === 2"
              size="small"
              link
              type="warning"
              @click="doRetract(row)"
            >
              撤回
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="editVisible" :title="form.id ? '编辑入口' : '新建入口'" width="520px">
      <ElForm label-width="90px">
        <ElFormItem label="入口类型">
          <ElSelect v-model="form.entryType" :disabled="!!form.id">
            <ElOption label="功能入口" :value="1" />
            <ElOption label="维护公告" :value="3" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem v-if="form.entryType === 1" label="路由编号">
          <ElInput
            v-model="form.routeId"
            placeholder="请输入页面编号"
            :disabled="!!form.id"
            @input="form.entryKey = form.routeId"
          />
        </ElFormItem>
        <ElFormItem v-else label="入口键">
          <ElSelect v-model="form.entryKey" :disabled="!!form.id">
            <ElOption v-for="k in ['notice']" :key="k" :label="k" :value="k" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="展示名称">
          <ElInput v-model="form.entryName" maxlength="50" />
        </ElFormItem>
        <ElFormItem label="排序">
          <ElInputNumber v-model="form.sortNo" :min="0" :max="9999" />
        </ElFormItem>
        <ElFormItem label="启用">
          <ElSwitch v-model="form.enabled" />
        </ElFormItem>
        <ElFormItem v-if="form.entryType === 2" label="外部链接">
          <ElInput v-model="form.externalUrl" placeholder="https://（域名须已批准）" />
        </ElFormItem>
        <ElFormItem v-if="form.entryType === 3" label="公告正文">
          <ElInput v-model="form.contentText" type="textarea" :rows="3" maxlength="500" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="save">保存草稿</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import {
    fetchMiniEntryList,
    fetchMiniEntrySave,
    fetchMiniEntryPublish,
    fetchMiniEntryRetract,
    type MiniEntryItem
  } from '@/api/system-manage'
  import { ElAlert, ElMessage, ElMessageBox } from 'element-plus'

  defineOptions({ name: 'SystemMiniEntry' })

  const loading = ref(false)
  const saving = ref(false)
  const rows = ref<MiniEntryItem[]>([])
  const editVisible = ref(false)
  const form = ref({
    id: undefined as string | undefined,
    version: undefined as number | undefined,
    entryType: 1,
    entryKey: '',
    routeId: '',
    entryName: '',
    sortNo: 0,
    enabled: true,
    externalUrl: '',
    contentText: ''
  })

  const typeLabel = (v: number) => ({ 1: '功能入口', 2: '内容链接', 3: '维护公告' })[v] ?? String(v)
  const statusLabel = (v: number) => ({ 1: '草稿', 2: '已发布', 3: '已撤回' })[v] ?? String(v)

  const load = async () => {
    loading.value = true
    try {
      rows.value = await fetchMiniEntryList()
    } finally {
      loading.value = false
    }
  }

  const openEdit = (row: MiniEntryItem | null) => {
    form.value = row
      ? {
          id: row.id,
          version: row.version,
          entryType: row.entryType,
          entryKey: row.entryKey,
          routeId: row.routeId ?? '',
          entryName: row.entryName,
          sortNo: row.sortNo,
          enabled: row.enabledFlag === 2,
          externalUrl: row.externalUrl ?? '',
          contentText: row.contentText ?? ''
        }
      : {
          id: undefined,
          version: undefined,
          entryType: 1,
          entryKey: '',
          routeId: '',
          entryName: '',
          sortNo: 0,
          enabled: true,
          externalUrl: '',
          contentText: ''
        }
    editVisible.value = true
  }

  const save = async () => {
    saving.value = true
    try {
      await fetchMiniEntrySave({
        id: form.value.id,
        version: form.value.version,
        entryKey: form.value.entryType === 1 ? form.value.routeId : form.value.entryKey,
        entryType: form.value.entryType,
        entryName: form.value.entryName,
        sortNo: form.value.sortNo,
        enabledFlag: form.value.enabled ? 2 : 1,
        jumpType: form.value.entryType === 1 ? 1 : form.value.entryType === 2 ? 2 : undefined,
        routeId: form.value.entryType === 1 ? form.value.routeId : undefined,
        externalUrl: form.value.entryType === 2 ? form.value.externalUrl : undefined,
        contentText: form.value.entryType === 3 ? form.value.contentText : undefined
      })
      ElMessage.success('草稿已保存')
      editVisible.value = false
      await load()
    } finally {
      saving.value = false
    }
  }

  const doPublish = async (row: MiniEntryItem) => {
    await ElMessageBox.confirm(`发布「${row.entryName}」后小程序即刻可见，确认？`, '发布')
    await fetchMiniEntryPublish(row.id, row.version)
    ElMessage.success('已发布')
    await load()
  }

  const doRetract = async (row: MiniEntryItem) => {
    await ElMessageBox.confirm(`撤回「${row.entryName}」后小程序即刻隐藏，确认？`, '撤回')
    await fetchMiniEntryRetract(row.id, row.version)
    ElMessage.success('已撤回')
    await load()
  }

  onMounted(load)
</script>
