export type CurrentPageExportValue = string | number | boolean | null | undefined | Date

export interface CurrentPageExportColumn<T> {
  header: string
  value: (row: T) => CurrentPageExportValue
}

/**
 * 只按显式白名单生成当前页导出数据。
 *
 * 不能直接把接口行对象交给 Excel：接口为抽屉、权限判断准备的隐藏字段也会跟着出现在文件里，
 * 页面虽然没有展示，下载文件却可能泄露。每个页面必须逐列声明允许离开浏览器的字段。
 */
export function currentPageExportRows<T>(
  rows: readonly T[],
  columns: readonly CurrentPageExportColumn<T>[]
): Record<string, CurrentPageExportValue>[] {
  return rows.map((row) =>
    Object.fromEntries(columns.map((column) => [column.header, column.value(row)]))
  )
}

/** 员工手机号导出恒脱敏；形态异常时留空，绝不回落原文。 */
export function maskedPhoneForExport(phone?: string | null): string {
  const normalized = phone?.trim() ?? ''
  if (/^1[0-9]{2}\*{4}[0-9]{4}$/.test(normalized)) return normalized
  if (!/^1[0-9]{10}$/.test(normalized)) return ''
  return `${normalized.slice(0, 3)}****${normalized.slice(7)}`
}

export function currentPageExportFilename(title: string, now = new Date()): string {
  const part = (value: number) => String(value).padStart(2, '0')
  const stamp = `${now.getFullYear()}${part(now.getMonth() + 1)}${part(now.getDate())}-${part(
    now.getHours()
  )}${part(now.getMinutes())}`
  return `六维达康-${title}-当前页-${stamp}`
}
