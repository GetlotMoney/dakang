const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

/**
 * 入口页推荐码承接位的源码级门禁。
 *
 * <h3>它锁什么</h3>
 * 游客扫了推荐人的码进来浏览、几天后才登录：页面参数活不过一次冷启，而绑定接口需要会话。
 * 中间没有承接位的话，那个 inviteCode 就永久丢失，D-406「经营归属在建立时冻结」的血缘链
 * 从源头断掉——机主推广来的用户，分润分不到推广人头上，而且**没有任何报错**。
 *
 * <h3>为什么按源码扫描</h3>
 * 入口页是 Vue SFC，三条登录动线（测试登录 / 正式登录 BOUND / 绑号后建会话）各自
 * 调一次 establishRealSession。要断言的是「每一处后面都跟着消费推荐码」——
 * 这是源码结构事实。跑运行时只能覆盖被用例走到的那条动线，而漏掉的恰恰是没被走到的那条。
 *
 * 判据是**计数相等**而不是「至少调用一次」：后者在新增第四条登录动线却忘了补调时仍然绿。
 */

const ENTRY = path.resolve(__dirname, '../src/pages/entry/index.vue')

function readEntry() {
  return fs.readFileSync(ENTRY, 'utf8')
}

test('每个建会话点都消费待归属推荐码', () => {
  const src = readEntry()

  const sessionPoints = (src.match(/accountStore\.establishRealSession\(/g) || []).length
  const consumeCalls = (src.match(/await consumePendingInvite\(\)/g) || []).length

  assert.ok(sessionPoints > 0, '未找到任何建会话点，判据已失效（入口页被重构？）')
  assert.equal(consumeCalls, sessionPoints, `建会话点 ${sessionPoints} 处，消费推荐码 ${consumeCalls} 处——`
  + '漏掉的那条动线上，用户扫过的推荐码会永久丢失且不报错')
})

test('推荐码在冷启动落点被持久化，不依赖页面参数存活', () => {
  const src = readEntry()
  assert.match(src, /onLoad\(async \(query\)/, 'onLoad 未接收 query：推荐码无从取得')
  assert.match(src, /uni\.setStorageSync\(PENDING_INVITE_KEY/, '推荐码未落 storage：页面参数活不过一次冷启，游客逛几天再登录就丢了')
})

test('消费前先清 storage：失败不重试', () => {
  const src = readEntry()
  const removeAt = src.indexOf('uni.removeStorageSync(PENDING_INVITE_KEY)')
  const bindAt = src.indexOf('await inviteApi.bind(')
  assert.ok(removeAt > 0 && bindAt > 0, '未找到清除或绑定调用')
  // 绑定是一次性 CAS，「已绑过」本就会被拒绝。留着码重试只会让每次登录都重试一个
  // 注定失败的绑定，而推荐关系一旦绑定不可改，重试没有任何价值。
  assert.ok(removeAt < bindAt, '必须先清 storage 再调绑定，否则失败后会无限重试')
})
