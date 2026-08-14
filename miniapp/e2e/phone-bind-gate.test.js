const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

/**
 * 绑号闸在端上的消费合同。
 *
 * <h3>它锁什么</h3>
 * 服务端对未绑号账号的资金/履约动作返回独立错误码 627（PHONE_BIND_REQUIRED）。
 * 端上若不认这个码，用户只会看到一句「请先绑定手机号」的 toast——话看得懂，
 * 但不知道去哪绑，而他刚才想做的事就卡在那里了。游客态下这是**主动线**而非边缘情况。
 *
 * <h3>为什么判「发起资金动作的页面都要有绑号出口」</h3>
 * 光断言「映射表里有 627」不够：映射存在但没有任何页面消费它，用户体验与没映射一样。
 * 所以这里同时钉住两端——码要登记，且每个会撞闸的页面都要挂上绑号半屏。
 */

const SRC = path.resolve(__dirname, '../src')

function read(rel) {
  return fs.readFileSync(path.join(SRC, rel), 'utf8')
}

/** 会触发服务端绑号闸的页面（与后端七处锚点里的用户侧入口对应）。 */
const GATED_PAGES = [
  'pages/user/recharge/index.vue', // 购卡充值：发行可兑付预付卡
  'pages/mall/checkout.vue', // 商城下单：占库存 + 配送员上门
]

test('627 已登记为独立字符码', () => {
  const src = read('api/request.ts')
  assert.match(src, /627:\s*'PHONE_BIND_REQUIRED'/, '未登记 627：撞闸时端上只会拿到通用错误，无法弹绑号引导')
})

test('每个会撞闸的页面都挂了绑号出口', () => {
  const missing = []
  for (const rel of GATED_PAGES) {
    const src = read(rel)
    const handles = src.includes('PHONE_BIND_REQUIRED')
    const mounts = src.includes('PhoneBindSheet')
    if (!handles || !mounts) {
      missing.push(`${rel}（判码=${handles} 挂载=${mounts}）`)
    }
  }
  assert.deepEqual(missing, [], `以下页面撞闸后没有绑号出口：${missing.join('，')}`)
})

test('绑号半屏本身提供「暂不绑定」出路', () => {
  // 微信《运营规范》15.1.3.2：用户拒绝某项功能所需的授权后，
  // 不得以任何方式停止提供小程序全部服务。半屏必须能关掉、用户能继续逛。
  const src = read('components/phone-bind-sheet.vue')
  assert.ok(src.includes('暂不绑定') || src.includes('知道了'), '绑号半屏没有退出路径：用户拒绝授权后会被卡死在这个弹层里')
  assert.match(src, /isPhoneComponentAvailable/, '未判组件可用性：平台禁用时点击不弹窗也无回调，渲染按钮只会得到一个死按钮')
})
