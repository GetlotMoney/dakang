import type { EntityId, MoneyFen } from './common'
import { ContractError } from './common'
import { withRealSession } from './real-session'
import { post } from './request'

/**
 * 商城只读 API（E2E-09 S1）。
 *
 * <p>只消费已上架商品、启用 SKU 与有货/缺货布尔；ID 全链 string、金额恒整数分。
 * 响应逐行归一化：非法金额/库存形态 fail-closed 抛 ContractError——页面进入失败态，
 * 绝不回退 Mock 商品，也绝不把错值渲染上屏。本期无购物车与下单能力。</p>
 */

export interface MallCategory {
  categoryId: EntityId
  categoryName: string
}

export interface MallProductCard {
  productId: EntityId
  productName: string
  productSubtitle?: string
  coverUrl?: string
  categoryId: EntityId
  /** 最低售价(分)：启用 SKU 最小值；无启用 SKU 时后端可能为空 */
  minSalePriceFen?: MoneyFen
  inStock: boolean
}

export interface MallSkuOption {
  skuId: EntityId
  skuName: string
  specs: Record<string, string>
  salePriceFen: MoneyFen
  marketPriceFen?: MoneyFen
  weightGram: number
  inStock: boolean
}

export interface MallProductDetail {
  productId: EntityId
  productName: string
  productSubtitle?: string
  coverUrl?: string
  productDesc?: string
  inStock: boolean
  skus: MallSkuOption[]
}

export const mallEndpoints = {
  home: '/mini/mall/home',
  productDetail: '/mini/mall/product/detail',
} as const

/**
 * 身份 ID 归一：接受 string 或安全整数 number，其余按契约破坏拒绝。
 * 本文件的归一化原语由 mall-trade.ts 共用而非各写一份，避免两处分头维护被改松。
 */
export function normalizeId(raw: unknown, field: string): string {
  if (typeof raw === 'string' && raw.length > 0) {
    return raw
  }
  if (typeof raw === 'number' && Number.isSafeInteger(raw) && raw > 0) {
    return String(raw)
  }
  throw new ContractError('MALL_BAD_ID', `商城数据异常（${field}）`)
}

/**
 * 数值归一：Long 字符串与 Integer number 两种形态都必须接受（与 order.ts strictNumber 同约定）；
 * 科学计数、前导零、正负号、空串、超安全整数一律拒绝（R1-P0-2）。
 */
export function strictNonNegInt(raw: unknown): number | undefined {
  if (typeof raw === 'number') {
    return Number.isSafeInteger(raw) && raw >= 0 ? raw : undefined
  }
  if (typeof raw === 'string' && /^(?:0|[1-9]\d*)$/.test(raw)) {
    const n = Number(raw)
    return Number.isSafeInteger(n) ? n : undefined
  }
  return undefined
}

/** 金额归一：非法金额=契约破坏，宁可整页失败不渲染错价。 */
export function normalizeFen(raw: unknown, field: string): number {
  const n = strictNonNegInt(raw)
  if (n === undefined) {
    throw new ContractError('MALL_BAD_PRICE', `商城价格数据异常（${field}）`)
  }
  return n
}

function normalizeOptionalFen(raw: unknown, field: string): number | undefined {
  if (raw === null || raw === undefined) {
    return undefined
  }
  return normalizeFen(raw, field)
}

export function textOf(raw: unknown): string {
  return typeof raw === 'string' ? raw : ''
}

export function optionalText(raw: unknown): string | undefined {
  return typeof raw === 'string' && raw.length > 0 ? raw : undefined
}

/**
 * 规格快照 fail-closed（R1-P1-3）：只接受 Record<string,string>，其余都是契约破坏，
 * 整次详情读取必须失败；空对象 {} 合法（无规格 SKU）。
 */
export function specsOf(raw: unknown): Record<string, string> {
  if (raw === null || raw === undefined || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new ContractError('MALL_BAD_SPECS', '商品规格数据异常')
  }
  const specs: Record<string, string> = {}
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof value !== 'string') {
      throw new ContractError('MALL_BAD_SPECS', '商品规格数据异常')
    }
    specs[key] = value
  }
  return specs
}

function normalizeCard(row: Record<string, unknown>): MallProductCard {
  return {
    productId: normalizeId(row.productId, 'productId'),
    productName: textOf(row.productName),
    productSubtitle: optionalText(row.productSubtitle),
    coverUrl: optionalText(row.coverUrl),
    categoryId: normalizeId(row.categoryId, 'categoryId'),
    minSalePriceFen: normalizeOptionalFen(row.minSalePriceFen, 'minSalePriceFen'),
    inStock: row.inStock === true,
  }
}

function normalizeSku(row: Record<string, unknown>): MallSkuOption {
  // 重量同价格一样 fail-closed：静默降 0 会把"19.5kg 桶装水"渲染成 0g（R1-P0-2）。
  const weightGram = strictNonNegInt(row.weightGram)
  if (weightGram === undefined) {
    throw new ContractError('MALL_BAD_WEIGHT', '商品重量数据异常（weightGram）')
  }
  return {
    skuId: normalizeId(row.skuId, 'skuId'),
    skuName: textOf(row.skuName),
    specs: specsOf(row.specs),
    salePriceFen: normalizeFen(row.salePriceFen, 'salePriceFen'),
    marketPriceFen: normalizeOptionalFen(row.marketPriceFen, 'marketPriceFen'),
    weightGram,
    inStock: row.inStock === true,
  }
}

export interface MallHome {
  categories: MallCategory[]
  products: MallProductCard[]
}

async function home(categoryId?: string): Promise<MallHome> {
  return withRealSession(async () => {
    const raw = await post<Record<string, unknown>>(mallEndpoints.home, {
      categoryId: categoryId ?? null,
    })
    const categoriesRaw = Array.isArray(raw?.categories) ? raw.categories : []
    const productsRaw = Array.isArray(raw?.products) ? raw.products : []
    return {
      categories: categoriesRaw
        .filter((row): row is Record<string, unknown> => typeof row === 'object' && row !== null)
        .map(row => ({
          categoryId: normalizeId(row.categoryId, 'categoryId'),
          categoryName: textOf(row.categoryName),
        })),
      products: productsRaw
        .filter((row): row is Record<string, unknown> => typeof row === 'object' && row !== null)
        .map(row => normalizeCard(row)),
    }
  })
}

async function productDetail(productId: string): Promise<MallProductDetail> {
  return withRealSession(async () => {
    const raw = await post<Record<string, unknown>>(mallEndpoints.productDetail, { id: productId })
    if (!raw || typeof raw !== 'object') {
      throw new ContractError('MALL_BAD_DETAIL', '商品数据异常')
    }
    const skusRaw = Array.isArray(raw.skus) ? raw.skus : []
    return {
      productId: normalizeId(raw.productId, 'productId'),
      productName: textOf(raw.productName),
      productSubtitle: optionalText(raw.productSubtitle),
      coverUrl: optionalText(raw.coverUrl),
      productDesc: optionalText(raw.productDesc),
      inStock: raw.inStock === true,
      skus: skusRaw
        .filter((row): row is Record<string, unknown> => typeof row === 'object' && row !== null)
        .map(row => normalizeSku(row)),
    }
  })
}

export const mallApi = {
  home,
  productDetail,
}
