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
