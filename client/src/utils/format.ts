/**
 * 将毫升转换为升的展示文本。
 *
 * 业务计算仍使用整数毫升；此函数只统一 PC 页面展示口径：
 * 最多保留两位小数并去除尾零（10000 → 10L，4980 → 4.98L）。
 */
export function mlToLiter(ml?: number | string | null): string {
  if (ml === undefined || ml === null || ml === '') return '-'

  const value = Number(ml)
  if (!Number.isFinite(value)) return '-'

  return `${Number((value / 1000).toFixed(2))}L`
}

/**
 * 将"分"转换为"元"的展示文本（固定两位小数，如 10000 → 100.00）。
 *
 * 业务计算与接口传输仍使用整数分；此函数只统一 PC 页面展示口径，
 * 新页面一律复用本函数，不再各自内联 fenToYuan。
 */
export function fenToYuan(fen?: number | string | null): string {
  if (fen === undefined || fen === null || fen === '') return '-'

  const value = Number(fen)
  if (!Number.isFinite(value)) return '-'

  return (value / 100).toFixed(2)
}

/**
 * 万分比 → 百分数展示文本（7000 → "70%"，最多两位小数去尾零）。
 *
 * 分账比例的传输与存储恒为整数万分比（E2E-08 契约）；此函数只统一展示口径，
 * 与 percentToBp 互为一对，页面不再各自内联 /100、*100。
 */
export function bpToPercentText(bp?: number | string | null): string {
  if (bp === undefined || bp === null || bp === '') return '-'

  // D-419 分线快照：机主 "W7000+D6000"（水费线+配送费线）、配送员 "D3000"
  if (typeof bp === 'string') {
    const lined = bp.match(/^(?:W(\d+)\+)?D(\d+)$/)
    if (lined) {
      const water =
        lined[1] !== undefined ? `水${Number((Number(lined[1]) / 100).toFixed(2))}%` : ''
      const fee = `送${Number((Number(lined[2]) / 100).toFixed(2))}%`
      return water ? `${water}+${fee}` : fee
    }
  }

  const value = Number(bp)
  if (!Number.isFinite(value)) return '-'

  return `${Number((value / 100).toFixed(2))}%`
}

/** 百分数输入 → 整数万分比（70 → 7000；四舍五入吸收浮点尾差）。 */
export function percentToBp(percent: number): number {
  return Math.round(percent * 100)
}
