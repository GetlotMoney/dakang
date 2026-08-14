<!-- 商城商品分步编辑：基础信息 / 规格价格 / 库存履约 / 图文详情 四步，逐步校验。
     四个步骤面板用 v-show 而不是 v-if：表单项一旦卸载就退出校验，最后一步点保存时
     前面几步的必填会静默通过，等于把校验交给后端 500。
     字段集合与原单弹窗完全一致（无新增契约字段），改的只是编排与校验时机。 -->
<template>
  <ElDialog
    :model-value="visible"
    :title="editing ? '编辑商品' : '新增商品'"
    width="880px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:visible', $event)"
    @open="handleOpen"
  >
    <ElSteps :active="stepIndex" finish-status="success" align-center class="mb-4">
      <ElStep v-for="step in STEPS" :key="step.key" :title="step.title" />
    </ElSteps>

    <div class="step-body" v-loading="loadingDetail">
      <ElForm ref="formRef" :model="form" :rules="rules" label-width="96px">
        <!-- 第 1 步：基础信息 -->
        <div v-show="stepIndex === 0">
          <div class="flex gap-4">
            <ElFormItem label="商品编号" prop="productNo" class="flex-1">
              <ElInput v-model="form.productNo" :disabled="editing" placeholder="唯一编号" />
            </ElFormItem>
            <ElFormItem label="分类" prop="categoryId" class="flex-1">
              <ElSelect v-model="form.categoryId" placeholder="选择分类" class="w-full">
                <ElOption
                  v-for="c in categories"
                  :key="c.id"
                  :label="c.categoryName"
                  :value="c.id"
                />
              </ElSelect>
            </ElFormItem>
          </div>
          <ElFormItem label="商品名称" prop="productName">
            <ElInput v-model="form.productName" placeholder="用户在商城看到的名称" />
          </ElFormItem>
          <ElFormItem label="副标题">
            <ElInput v-model="form.productSubtitle" placeholder="选填，一句话卖点" />
          </ElFormItem>
        </div>

        <!-- 第 2 步：规格价格 -->
        <div v-show="stepIndex === 1">
          <ElAlert
            type="info"
            :closable="false"
            show-icon
            title="每条规格独立定价与计重，停用的规格不参与售卖"
            class="mb-3"
          />
          <ElTable :data="form.skus" border size="small">
            <ElTableColumn label="规格编号" width="150">
              <template #default="{ row, $index }">
                <ElInput
                  v-model="row.skuNo"
                  :disabled="!!row.id"
                  :placeholder="'SKU-' + ($index + 1)"
                />
              </template>
            </ElTableColumn>
            <ElTableColumn label="名称" width="140">
              <template #default="{ row }"><ElInput v-model="row.skuName" /></template>
            </ElTableColumn>
            <ElTableColumn label="规格(键:值,逗号分隔)" min-width="180">
              <template #default="{ row }">
                <ElInput v-model="row.specText" placeholder="规格:500ml,口味:原味" />
              </template>
            </ElTableColumn>
            <ElTableColumn label="售价(元)" width="120">
              <template #default="{ row }">
                <ElInputNumber
                  v-model="row.salePriceYuan"
                  :min="0.01"
                  :precision="2"
                  :step="1"
                  size="small"
                />
              </template>
            </ElTableColumn>
            <ElTableColumn label="划线价(元)" width="120">
              <template #default="{ row }">
                <ElInputNumber
                  v-model="row.marketPriceYuan"
                  :min="0"
                  :precision="2"
                  :step="1"
                  size="small"
                />
              </template>
            </ElTableColumn>
            <ElTableColumn label="重量(克)" width="110">
              <template #default="{ row }">
                <ElInputNumber v-model="row.weightGram" :min="0" :step="10" size="small" />
              </template>
            </ElTableColumn>
            <ElTableColumn label="启用" width="70" align="center">
              <template #default="{ row }">
                <ElSwitch v-model="row.enabled" />
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="60" align="center">
              <template #default="{ $index }">
                <ElButton
                  link
                  type="danger"
                  :disabled="!!form.skus[$index].id"
                  @click="removeSku($index)"
                >
                  删
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
          <ElButton class="mt-2" size="small" @click="addSku">添加规格</ElButton>
        </div>

        <!-- 第 3 步：库存履约（只读事实；库存增减一律走库存管理，本页不提供改库存入口） -->
        <div v-show="stepIndex === 2">
          <ElAlert
            type="warning"
            :closable="false"
            show-icon
            title="上架需要：分类已启用、至少一条启用规格、启用仓有可售库存"
            class="mb-3"
          />
          <template v-if="editing">
            <div class="mb-2 text-sm">
              可售合计 <span class="stock-total">{{ totalAvailable }}</span> 件 · 已预占
              {{ totalReserved }} 件
            </div>
            <ElTable :data="stocks" border size="small" max-height="240">
              <ElTableColumn prop="warehouseName" label="前置仓" min-width="140" />
              <ElTableColumn prop="skuName" label="规格" min-width="140" />
              <ElTableColumn prop="availableQty" label="可售" width="90" align="right" />
              <ElTableColumn prop="reservedQty" label="预占" width="90" align="right" />
            </ElTable>
            <ElEmpty
              v-if="!loadingDetail && stocks.length === 0"
              description="尚无库存记录，请到库存管理办理入库"
            />
          </template>
          <ElEmpty v-else description="保存商品后，到库存管理办理首次入库" />
        </div>

        <!-- 第 4 步：图文详情 -->
        <div v-show="stepIndex === 3">
          <ElFormItem label="主图地址">
            <ElInput v-model="form.coverUrl" placeholder="已有合法 URL 或相对资源路径" />
          </ElFormItem>
          <ElFormItem label="商品说明">
            <ElInput
              v-model="form.productDesc"
              type="textarea"
              :rows="8"
              placeholder="用户下单前需要知道的规格、产地、饮用建议等"
            />
          </ElFormItem>
        </div>
      </ElForm>
    </div>

    <template #footer>
      <div class="footer-bar">
        <span class="footer-hint">{{ STEPS[stepIndex].hint }}</span>
        <div class="flex gap-2">
          <ElButton @click="emit('update:visible', false)">取消</ElButton>
          <ElButton v-if="stepIndex > 0" @click="prevStep">上一步</ElButton>
          <ElButton v-if="stepIndex < STEPS.length - 1" @click="nextStep">下一步</ElButton>
          <ElButton type="primary" :loading="saving" @click="submit">保存</ElButton>
        </div>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import {
    fetchMallProductDetail,
    fetchMallProductSave,
    fetchMallProductUpdate,
    type MallCategoryItem,
    type MallSkuItem,
    type MallStockItem
  } from '@/api/mall'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

  const props = defineProps<{
    visible: boolean
    /** 商品ID：有值即编辑态。Long ID 恒 string，不做任何数值转换。 */
    productId?: string
    categories: MallCategoryItem[]
  }>()
  const emit = defineEmits<{
    'update:visible': [value: boolean]
    saved: []
  }>()

  /** 四步：每步只承载一类决定，避免一屏塞满互不相关的字段。 */
  const STEPS = [
    { key: 'basic', title: '基础信息', hint: '商品编号保存后不可更改' },
    { key: 'price', title: '规格价格', hint: '至少一条规格，价格与重量按规格分别填写' },
    { key: 'stock', title: '库存履约', hint: '库存增减在库存管理中办理' },
    { key: 'detail', title: '图文详情', hint: '说明会展示在商城详情页' }
  ] as const

  /** 逐步校验的字段清单：第 2~4 步无表单级必填项，由各自的业务校验兜底。 */
  const STEP_FIELDS: string[][] = [['productNo', 'categoryId', 'productName'], [], [], []]

  interface SkuRow {
    id?: string
    skuNo?: string
    skuName: string
    specText: string
    salePriceYuan: number
    marketPriceYuan: number
    weightGram: number
    enabled: boolean
  }

  interface ProductForm {
    id?: string
    productNo: string
    categoryId?: string
    productName: string
    productSubtitle: string
    coverUrl: string
    productDesc: string
    skus: SkuRow[]
  }

  const emptyForm = (): ProductForm => ({
    productNo: '',
    categoryId: undefined,
    productName: '',
    productSubtitle: '',
    coverUrl: '',
    productDesc: '',
    skus: []
  })

  const stepIndex = ref(0)
  const saving = ref(false)
  const loadingDetail = ref(false)
  const formRef = ref<FormInstance>()
  const form = ref<ProductForm>(emptyForm())
  const stocks = ref<MallStockItem[]>([])
  const editing = computed(() => !!props.productId)

  const rules: FormRules = {
    productNo: [{ required: true, message: '请填写商品编号', trigger: 'blur' }],
    categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
    productName: [{ required: true, message: '请填写商品名称', trigger: 'blur' }]
  }

  const totalAvailable = computed(() => stocks.value.reduce((sum, r) => sum + r.availableQty, 0))
  const totalReserved = computed(() => stocks.value.reduce((sum, r) => sum + r.reservedQty, 0))

  const specsToText = (specs?: Record<string, string>) =>
    Object.entries(specs || {})
      .map(([k, v]) => `${k}:${v}`)
      .join(',')

  const textToSpecs = (text: string): Record<string, string> => {
    const out: Record<string, string> = {}
    text
      .split(/[,，]+/)
      .map((s) => s.trim())
      .filter(Boolean)
      .forEach((pair) => {
        const [k, v] = pair.split(/[:：]/)
        if (k && v) out[k.trim()] = v.trim()
      })
    return out
  }

  const addSku = () => {
    form.value.skus.push({
      skuName: '',
      specText: '',
      salePriceYuan: 1,
      marketPriceYuan: 0,
      weightGram: 0,
      enabled: true
    })
  }
  const removeSku = (index: number) => form.value.skus.splice(index, 1)

  /**
   * 每次打开都重新取详情：列表行是上一次分页的快照，直接拿来编辑会把别人刚改的字段覆盖回去。
   */
  async function handleOpen() {
    stepIndex.value = 0
    stocks.value = []
    formRef.value?.clearValidate()
    if (!props.productId) {
      form.value = emptyForm()
      addSku()
      return
    }
    loadingDetail.value = true
    try {
      const detail = await fetchMallProductDetail(props.productId)
      form.value = {
        id: detail.id,
        productNo: detail.productNo,
        categoryId: detail.categoryId,
        productName: detail.productName,
        productSubtitle: detail.productSubtitle || '',
        coverUrl: detail.coverUrl || '',
        productDesc: detail.productDesc || '',
        skus: detail.skus.map((s) => ({
          id: s.id,
          skuNo: s.skuNo,
          skuName: s.skuName,
          specText: specsToText(s.specs),
          salePriceYuan: s.salePrice / 100,
          marketPriceYuan: s.marketPrice ? s.marketPrice / 100 : 0,
          weightGram: s.weightGram,
          enabled: s.skuStatus === 1
        }))
      }
      stocks.value = detail.stocks
    } finally {
      loadingDetail.value = false
    }
  }

  /** 规格步的业务校验：表单项是动态行，走不了 rules。 */
  function validateSkuStep(): boolean {
    if (form.value.skus.length === 0) {
      ElMessage.warning('至少配置一条规格')
      return false
    }
    if (form.value.skus.some((s) => !s.skuName.trim())) {
      ElMessage.warning('每条规格都要填名称')
      return false
    }
    return true
  }

  async function validateStep(index: number): Promise<boolean> {
    const fields = STEP_FIELDS[index]
    if (fields.length > 0) {
      try {
        await formRef.value?.validateField(fields)
      } catch {
        return false
      }
    }
    if (index === 1) return validateSkuStep()
    return true
  }

  async function nextStep() {
    if (!(await validateStep(stepIndex.value))) return
    stepIndex.value = Math.min(stepIndex.value + 1, STEPS.length - 1)
  }

  function prevStep() {
    stepIndex.value = Math.max(stepIndex.value - 1, 0)
  }

  const toSkuPayload = (r: SkuRow): MallSkuItem => ({
    id: r.id,
    skuNo: r.skuNo,
    skuName: r.skuName.trim(),
    specs: textToSpecs(r.specText),
    salePrice: Math.round(r.salePriceYuan * 100),
    marketPrice: r.marketPriceYuan > 0 ? Math.round(r.marketPriceYuan * 100) : undefined,
    weightGram: r.weightGram,
    skuStatus: r.enabled ? 1 : 2
  })

  /**
   * 保存前逐步复验并把光标停在第一处不合格的步骤：
   * 只在末步弹一句"请检查表单"，运营根本不知道错在哪一屏。
   */
  async function submit() {
    for (let i = 0; i < STEPS.length; i += 1) {
      if (await validateStep(i)) continue
      stepIndex.value = i
      return
    }
    saving.value = true
    try {
      const skus = form.value.skus.map(toSkuPayload)
      if (editing.value && form.value.id) {
        await fetchMallProductUpdate({
          id: form.value.id,
          categoryId: form.value.categoryId!,
          productName: form.value.productName.trim(),
          productSubtitle: form.value.productSubtitle,
          coverUrl: form.value.coverUrl,
          productDesc: form.value.productDesc,
          skus
        })
      } else {
        await fetchMallProductSave({
          productNo: form.value.productNo.trim(),
          categoryId: form.value.categoryId!,
          productName: form.value.productName.trim(),
          productSubtitle: form.value.productSubtitle,
          coverUrl: form.value.coverUrl,
          productDesc: form.value.productDesc,
          skus
        })
      }
      ElMessage.success('保存成功')
      emit('update:visible', false)
      emit('saved')
    } finally {
      saving.value = false
    }
  }
</script>

<style scoped>
  /* 内容区自滚 + 底栏固定：分步表单最长的一屏是规格表，整页滚动会把操作按钮推出视野 */
  .step-body {
    max-height: 52vh;
    padding-right: 8px;
    overflow-x: hidden;
    overflow-y: auto;
  }

  .footer-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .footer-hint {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .stock-total {
    font-weight: 600;
  }
</style>
