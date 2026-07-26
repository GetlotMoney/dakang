const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const { cell, newBatchId, writeBatchFiles } = require('./report-core')

test('cell：换行压平、竖线转义、null/undefined 空串', () => {
  assert.equal(cell('a\r\nb\nc'), 'a b c')
  assert.equal(cell('x|y'), 'x\\|y')
  assert.equal(cell(null), '')
  assert.equal(cell(undefined), '')
  assert.equal(cell(0), '0')
})

test('newBatchId：ISO 时间戳且不含冒号/点（可作目录名）', () => {
  const id = newBatchId(new Date('2026-07-23T01:02:03.456Z'))
  assert.equal(id, '2026-07-23T01-02-03-456Z')
  assert.ok(!/[:.]/.test(id))
})

test('writeBatchFiles：批次目录写 result.json 与 report.md', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'report-core-'))
  const runDir = path.join(dir, 'runs', 'batch-1')
  writeBatchFiles(runDir, { ok: true }, '# 报告\n')
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(runDir, 'result.json'), 'utf8')), { ok: true })
  assert.equal(fs.readFileSync(path.join(runDir, 'report.md'), 'utf8'), '# 报告\n')
  fs.rmSync(dir, { recursive: true, force: true })
})
