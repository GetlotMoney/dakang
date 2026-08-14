import { describe, expect, it } from 'vitest'
import {
  currentPageExportFilename,
  currentPageExportRows,
  maskedPhoneForExport
} from './current-page-export'

describe('当前页导出白名单', () => {
  it('只输出显式列，不把接口隐藏字段带进文件', () => {
    const rows = currentPageExportRows(
      [{ id: '1', name: '张三', rawPhone: '13800000001', internalToken: 'secret' }],
      [
        { header: '编号', value: (row) => row.id },
        { header: '姓名', value: (row) => row.name },
        { header: '手机号', value: (row) => maskedPhoneForExport(row.rawPhone) }
      ]
    )

    expect(rows).toEqual([{ 编号: '1', 姓名: '张三', 手机号: '138****0001' }])
    expect(JSON.stringify(rows)).not.toContain('internalToken')
    expect(JSON.stringify(rows)).not.toContain('secret')
  })

  it('手机号形态异常时留空，不回落原文', () => {
    expect(maskedPhoneForExport('13800000001')).toBe('138****0001')
    expect(maskedPhoneForExport('138****0001')).toBe('138****0001')
    expect(maskedPhoneForExport('not-a-phone')).toBe('')
  })

  it('文件名明确标识当前页并使用稳定时间格式', () => {
    expect(currentPageExportFilename('订单查询', new Date(2026, 7, 13, 9, 5))).toBe(
      '六维达康-订单查询-当前页-20260813-0905'
    )
  })
})
