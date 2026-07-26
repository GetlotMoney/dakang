export interface RechargePackageSnapshot {
  packageName: string
  payAmountFen: number
  waterMl: number
}

export type PackageSnapshotState = 'valid' | 'missing' | 'invalid'

const toNonNegativeSafeInteger = (value: unknown): number | undefined => {
  const normalized =
    typeof value === 'number'
      ? value
      : typeof value === 'string' && /^(?:0|[1-9]\d*)$/.test(value)
        ? Number(value)
        : Number.NaN
  return Number.isSafeInteger(normalized) && normalized >= 0 ? normalized : undefined
}

/** 将 PACKAGE_SNAP 原文收敛为页面可安全展示的最小投影。 */
export const normalizeRechargePackageSnapshot = (
  raw: unknown
): RechargePackageSnapshot | undefined => {
  if (typeof raw !== 'string' || !raw.trim()) return undefined
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return undefined
    const record = parsed as Record<string, unknown>
    const packageName = typeof record.packageName === 'string' ? record.packageName.trim() : ''
    const payAmountFen = toNonNegativeSafeInteger(record.payAmount)
    const waterMl = toNonNegativeSafeInteger(record.waterMl)
    if (!packageName || payAmountFen == null || waterMl == null) return undefined
    return { packageName, payAmountFen, waterMl }
  } catch {
    return undefined
  }
}

export const packageSnapshotStateOf = (
  raw: unknown,
  snapshot: RechargePackageSnapshot | undefined
): PackageSnapshotState => {
  if (typeof raw !== 'string' || !raw.trim()) return 'missing'
  return snapshot ? 'valid' : 'invalid'
}
