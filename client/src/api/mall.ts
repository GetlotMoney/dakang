import request from '@/utils/http'
import {
  adjustResultOf,
  flowRowOf,
  normalizePage,
  orderDetailOf,
  orderRowOf,
  productDetailOf,
  productRowOf,
  stockRowOf
} from './mall-normalize'

/**
 * 商城管理 API（E2E-09 S1，R1-P0-2 补运行时归一化）。
 *
 * 身份类 Long ID 全链 string（>2^53 经 Number 会静默舍入，选 A 动 B）；
 * 金额恒整数分、库存恒整数件，展示层才做元/件换算。
 * 后端 Jackson 把 Long 全局序列化为字符串（total/金额/数量/流水都是 "2400" 形态），
 * TS 类型挡不住运行时形态——数值字段一律经 mall-normalize 归一，畸形 fail-closed。
 * 契约：docs/contracts/E2E-09-mall-contract.md。
 */

type PageResult<T> = { total: number; list: T[] }

// ==================== 分类 ====================

export interface MallCategoryItem {
  id: string
  categoryCode: string
  categoryName: string
  categorySort: number
  /** 分类状态(1392)：1启用 2停用 */
  categoryStatus: number
  createTime?: string
}

export async function fetchMallCategoryPage(data: {
  current: number
  size: number
  keyword?: string
  status?: number
}): Promise<PageResult<MallCategoryItem>> {
  const raw = await request.post<PageResult<MallCategoryItem>>({ url: '/mall/category/page', data })
  // 分类行无 Long 数值字段，只需归一 total
  return normalizePage(raw, (row) => row as unknown as MallCategoryItem)
}

export function fetchMallCategoryList() {
  return request.post<MallCategoryItem[]>({ url: '/mall/category/list', data: {} })
}

export function fetchMallCategorySave(data: {
  categoryCode: string
  categoryName: string
  categorySort: number
}) {
  return request.post<string>({ url: '/mall/category/save', data })
}

export function fetchMallCategoryUpdate(data: {
  id: string
  categoryName: string
  categorySort: number
}) {
  return request.post<boolean>({ url: '/mall/category/update', data })
}

export function fetchMallCategoryChangeStatus(data: { id: string; targetStatus: number }) {
  return request.post<boolean>({ url: '/mall/category/change-status', data })
}

// ==================== 商品与 SKU ====================

export interface MallSkuItem {
  id?: string
  skuNo?: string
  skuName: string
  specs?: Record<string, string>
  /** 售价(分) */
  salePrice: number
  /** 划线价(分)，可空 */
  marketPrice?: number
  /** 重量(克) */
  weightGram: number
  /** SKU状态(1389)：1启用 2停用 */
  skuStatus: number
  version?: number
}

export interface MallProductItem {
  id: string
  productNo: string
  categoryId: string
  categoryName?: string
  productName: string
  productSubtitle?: string
  coverUrl?: string
  /** 商品状态(1388)：1草稿 2已上架 3已下架 */
  productStatus: number
  version: number
  skuCount?: number
  createTime?: string
}

export interface MallStockItem {
  id: string
  warehouseId: string
  warehouseName?: string
  skuId: string
  skuNo?: string
  skuName?: string
  productId?: string
  productName?: string
  availableQty: number
  reservedQty: number
  version: number
  updateTime?: string
}

export interface MallProductDetail extends Omit<MallProductItem, 'skuCount'> {
  productDesc?: string
  skus: MallSkuItem[]
  stocks: MallStockItem[]
}

export async function fetchMallProductPage(data: {
  current: number
  size: number
  categoryId?: string
  keyword?: string
  status?: number
}): Promise<PageResult<MallProductItem>> {
  const raw = await request.post<PageResult<MallProductItem>>({ url: '/mall/product/page', data })
  return normalizePage(raw, productRowOf)
}

export async function fetchMallProductDetail(id: string): Promise<MallProductDetail> {
  const raw = await request.post<MallProductDetail>({ url: '/mall/product/detail', data: { id } })
  return productDetailOf(raw)
}

export function fetchMallProductSave(data: {
  productNo: string
  categoryId: string
  productName: string
  productSubtitle?: string
  coverUrl?: string
  productDesc?: string
  skus: MallSkuItem[]
}) {
  return request.post<string>({ url: '/mall/product/save', data })
}

export function fetchMallProductUpdate(data: {
  id: string
  categoryId: string
  productName: string
  productSubtitle?: string
  coverUrl?: string
  productDesc?: string
  skus?: MallSkuItem[]
}) {
  return request.post<boolean>({ url: '/mall/product/update', data })
}

export function fetchMallProductPublish(data: { id: string; version: number }) {
  return request.post<boolean>({ url: '/mall/product/publish', data })
}

export function fetchMallProductUnpublish(data: { id: string; version: number }) {
  return request.post<boolean>({ url: '/mall/product/unpublish', data })
}

// ==================== 前置仓 ====================

export interface MallWarehouseItem {
  id: string
  warehouseNo: string
  warehouseName: string
  contactName: string
  /** 脱敏电话（原文不出接口） */
  maskedPhone: string
  provinceCode: string
  cityCode: string
  districtCode: string
  warehouseAddress: string
  longitude?: string
  latitude?: string
  /** 履约行政区码集（范围 JSON 由服务端唯一构造） */
  districtCodes: string[]
  /** 前置仓状态(1390)：1启用 2停用 */
  warehouseStatus: number
  version: number
  createTime?: string
}

export async function fetchMallWarehousePage(data: {
  current: number
  size: number
  keyword?: string
  status?: number
}): Promise<PageResult<MallWarehouseItem>> {
  const raw = await request.post<PageResult<MallWarehouseItem>>({
    url: '/mall/warehouse/page',
    data
  })
  // 仓行无 Long 数值字段（经纬度/行政区码本就是 string），只需归一 total
  return normalizePage(raw, (row) => row as unknown as MallWarehouseItem)
}

export function fetchMallWarehouseList() {
  return request.post<MallWarehouseItem[]>({ url: '/mall/warehouse/list', data: {} })
}

export function fetchMallWarehouseDetail(id: string) {
  return request.post<MallWarehouseItem>({ url: '/mall/warehouse/detail', data: { id } })
}

export function fetchMallWarehouseSave(data: {
  warehouseNo: string
  warehouseName: string
  contactName: string
  contactPhone: string
  provinceCode: string
  cityCode: string
  districtCode: string
  warehouseAddress: string
  longitude?: string
  latitude?: string
  districtCodes: string[]
}) {
  return request.post<string>({ url: '/mall/warehouse/save', data })
}

export function fetchMallWarehouseUpdate(data: {
  id: string
  warehouseName: string
  contactName: string
  contactPhone: string
  provinceCode: string
  cityCode: string
  districtCode: string
  warehouseAddress: string
  longitude?: string
  latitude?: string
  districtCodes: string[]
}) {
  return request.post<boolean>({ url: '/mall/warehouse/update', data })
}

export function fetchMallWarehouseChangeStatus(data: {
  id: string
  targetStatus: number
  version: number
}) {
  return request.post<boolean>({ url: '/mall/warehouse/change-status', data })
}

// ==================== 库存 ====================

export interface MallStockFlowItem {
  id: string
  requestId: string
  warehouseId: string
  warehouseName?: string
  skuId: string
  skuName?: string
  /** 流水类型(1391) */
  flowType: number
  availableChange: number
  reservedChange: number
  availableAfter: number
  reservedAfter: number
  flowReason: string
  operatorId: string
  createTime?: string
}

export async function fetchMallStockPage(data: {
  current: number
  size: number
  warehouseId?: string
  categoryId?: string
  productId?: string
  keyword?: string
}): Promise<PageResult<MallStockItem>> {
  const raw = await request.post<PageResult<MallStockItem>>({ url: '/mall/stock/page', data })
  return normalizePage(raw, stockRowOf)
}

/**
 * 库存动作 SKU 候选（R2-P0）：数据源=SKU⋈商品、独立于库存行——新建 SKU 尚无库存行
 * 也必须可选中做首次入库。停用 SKU 照常返回（带状态标识），后端动作语义只要求存在。
 */
export interface MallSkuCandidateItem {
  skuId: string
  skuNo: string
  skuName: string
  productId: string
  productName?: string
  /** SKU状态(1389)：1启用 2停用 */
  skuStatus: number
}

/** 关键字远程搜索（SKU 编号/名称），服务端单页硬上限 50 */
export async function fetchMallSkuCandidates(data: {
  current: number
  size: number
  keyword?: string
}): Promise<PageResult<MallSkuCandidateItem>> {
  const raw = await request.post<PageResult<MallSkuCandidateItem>>({
    url: '/mall/stock/sku-candidates',
    data
  })
  // 候选行无 Long 数值字段（ID 恒 string、skuStatus 为 Integer），只需归一 total
  return normalizePage(raw, (row) => row as unknown as MallSkuCandidateItem)
}

/** 库存动作结果：首次与重放都从同一条幂等流水行构造（重放=原动作冻结值，非当前库存） */
export interface MallStockAdjustResult {
  requestId: string
  warehouseId: string
  skuId: string
  /** 流水类型(1391) */
  flowType: number
  /** 可售增减（出库为负） */
  availableChange: number
  availableAfter: number
  reservedAfter: number
}

/** 库存人工动作：requestId 为规范 UUID 幂等锚，同号重放同参返回冻结原结果、改参拒绝 */
export async function fetchMallStockAdjust(data: {
  requestId: string
  warehouseId: string
  skuId: string
  /** 1人工入库 2人工出库 3盘点调增 4盘点调减 */
  flowType: number
  quantity: number
  reason: string
}): Promise<MallStockAdjustResult> {
  const raw = await request.post<MallStockAdjustResult>({ url: '/mall/stock/adjust', data })
  return adjustResultOf(raw)
}

export async function fetchMallStockFlowPage(data: {
  current: number
  size: number
  warehouseId?: string
  skuId?: string
}): Promise<PageResult<MallStockFlowItem>> {
  const raw = await request.post<PageResult<MallStockFlowItem>>({
    url: '/mall/stock/flow/page',
    data
  })
  return normalizePage(raw, flowRowOf)
}

// ==================== 订单台账（S2，PC 只读） ====================

/** 商城订单台账行。PC 侧无写入接口（改单会绕过库存与支付事实）；电话恒脱敏，原号不出接口。 */
export interface MallOrderItem {
  id: string
  orderNo: string
  /** 下单用户ID（string） */
  userId: string
  /** 订单状态(1393)：1待支付 2已支付待履约 3履约中 4已完成 5已取消 6已全额退款 */
  orderStatus: number
  /** 支付状态(1394)：1待支付 2支付成功 3支付失败 4已关闭；无支付单时缺省 */
  payStatus?: number
  /** 商品金额(分) */
  productAmountFen: number
  /** 配送费(分) */
  deliveryFeeFen: number
  /** 订单总额(分) */
  orderAmountFen: number
  /** 商品种类数 */
  itemKindCount?: number
  /** 首个商品名称（列表摘要） */
  firstProductName?: string
  firstCoverUrl?: string
  /** 履约前置仓名称 */
  warehouseName?: string
  receiverName?: string
  /** 收货电话（脱敏） */
  maskedPhone?: string
  payExpireTime?: string
  createTime?: string
  cancelTime?: string
}

/** 订单明细行：全部字段为下单时刻冻结的快照，商品改名改价不回溯历史订单。 */
export interface MallOrderLine {
  productId: string
  skuId: string
  productName?: string
  skuName?: string
  /** 规格快照 */
  specs?: Record<string, string>
  /** 成交单价(分) */
  unitPriceFen: number
  quantity: number
  /** 行金额(分) */
  itemAmountFen: number
  /** 单件重量(克) */
  weightGram: number
}

export interface MallOrderDetail {
  summary: MallOrderItem
  /** 收货地区文本快照 */
  receiverRegion?: string
  /** 收货详细地址快照 */
  receiverAddress?: string
  receiverDistrictCode?: string
  cancelReason?: string
  /** 支付来源：1微信 2模拟支付 */
  paySource?: number
  /** 支付方交易号（成功后有值） */
  transactionId?: string
  paySuccessTime?: string
  items: MallOrderLine[]
}

export async function fetchMallOrderPage(data: {
  current: number
  size: number
  orderStatus?: number
  orderNo?: string
  userId?: string
  warehouseId?: string
}): Promise<PageResult<MallOrderItem>> {
  const raw = await request.post<PageResult<MallOrderItem>>({ url: '/mall/order/page', data })
  return normalizePage(raw, orderRowOf)
}

export async function fetchMallOrderDetail(orderNo: string): Promise<MallOrderDetail> {
  const raw = await request.post<MallOrderDetail>({ url: '/mall/order/detail', data: { orderNo } })
  return orderDetailOf(raw)
}
