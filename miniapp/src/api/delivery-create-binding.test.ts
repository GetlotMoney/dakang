import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * D-214 创单页支付选择的源码绑定断言（仓库无组件挂载基建，
 * 手法对齐 components/runtime-notice-binding.test.ts）：钉住「页面消费契约纯函数、
 * 不内联第二份可用性规则或拒因文案」。断言匹配标识符与绑定形态而非整行文本。
 */
function pageSource(relative: string): string {
  return readFileSync(fileURLToPath(new URL(relative, import.meta.url)), 'utf8')
}

describe('配送创单页支付方式绑定 (D-214)', () => {
  const source = pageSource('../pages/user/delivery/create.vue')

  it('支付方式二选一：radio 组绑定 payWay，选项 2/3 的禁用态来自契约可用性函数', () => {
    expect(source).toMatch(/<wd-radio-group v-model="payWay" cell>/)
    expect(source).toMatch(/<wd-radio :value="2" :disabled="balanceOption\.disabled">/)
    expect(source).toMatch(/<wd-radio :value="3" :disabled="mlOption\.disabled">/)
    expect(source).toMatch(/deliveryPayWayOptions\(/)
    expect(source).toMatch(/autoRefill: deliveryMode\.value === 'auto-refill'/)
  })

  it('禁用原因取 option.reason，页面不内联水量/余额不足的第二份文案', () => {
    expect(source).toMatch(/balanceOption\.value\.reason/)
    expect(source).toMatch(/mlOption\.value\.reason/)
    expect(source).not.toContain('水卡水量不足以抵扣本单水量')
    expect(source).not.toContain('水卡余额不足以支付配送费')
    expect(source).not.toContain('自动补货暂仅支持水卡余额支付')
  })

  it('区分无卡与有卡但余额或水量不足，不把所有支付方式禁用误报成无卡', () => {
    expect(source).toMatch(/const noCard = computed\(\(\) => primaryCard\.value === null\)/)
    expect(source).toMatch(/const noPayWayAvailable = computed\(\(\) => balanceOption\.value\.disabled && mlOption\.value\.disabled\)/)
    expect(source).toMatch(/v-if="!noCard" class="pay-option-note/)
    expect(source).toMatch(/\{\{ payBlockedText \}\}/)
    expect(source).toMatch(/\{\{ noCard \? '去购卡' : '去充值' \}\}/)
  })

  it('费用预览走 deliveryPriceLines 契约展示函数，payWay=3 时快照水费为 0 而不是价目水费', () => {
    expect(source).toMatch(/deliveryPriceLines\(\{/)
    expect(source).toMatch(/v-for="line in priceLines"/)
    expect(source).toMatch(/waterAmountFen: payWay\.value === 3 \? 0 : waterAmountFen\.value/)
    expect(source).toMatch(/deliveryWaterMl\(containerSpec\.value, deliveryCount\.value\)/)
  })

  it('提交把所选 payWay 传入契约创单入参，禁用选项在提交前被拦截', () => {
    expect(source).toMatch(/payWay: payWay\.value,/)
    expect(source).toMatch(/selectedOption\.disabled/)
  })

  it('默认余额支付：payWay 初值为 2', () => {
    expect(source).toMatch(/const payWay = ref<DeliveryPayWay>\(2\)/)
  })
})
