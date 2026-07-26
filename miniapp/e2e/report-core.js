const fs = require('node:fs')
const path = require('node:path')

/**
 * 验收产物公共层：run-d4 / run-e1b / run-card-lifecycle 三个跑批共用的
 * Markdown 报告与批次落盘原语。此前 cell 转义在 run-d4 与 run-e1b 各有一份，
 * 本模块收敛为唯一实现（防第二套 E2E 框架/复制粘贴漂移）。
 */

/** Markdown 表格单元转义：换行压平为空格、竖线转义；null/undefined 一律空串。 */
function cell(value) {
  return String(value ?? '').replace(/\r?\n/g, ' ').replace(/\|/g, '\\|')
}

/** 批次 ID：ISO 时间戳去掉冒号/点（与 docs/acceptance 既有 runs/<批次>/ 目录命名一致）。 */
function newBatchId(date = new Date()) {
  return date.toISOString().replace(/[:.]/g, '-')
}

/**
 * 批次落盘：在批次目录写 result.json（结构化结果）与 report.md（人读报告）。
 * 只负责这两份共有产物；latest.md 指针等跑批专属文件由调用方自理。
 */
function writeBatchFiles(runDir, result, reportMarkdown) {
  fs.mkdirSync(runDir, { recursive: true })
  fs.writeFileSync(path.join(runDir, 'result.json'), `${JSON.stringify(result, null, 2)}\n`)
  fs.writeFileSync(path.join(runDir, 'report.md'), reportMarkdown)
}

module.exports = { cell, newBatchId, writeBatchFiles }
