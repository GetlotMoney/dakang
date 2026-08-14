/**
 * 商城页面组件规范与真分页闸（E2E-09 R1-P1-1/P2-2，S2 纳入订单台账）。
 *
 * 背景：四页曾固定 current:1,size:200 且不读 total（后端硬裁 100），第 101 条起
 * 永久不可见；流水抽屉同病；且用裸 ElForm/ElTable 自绘查询区，偏离仓库规范
 * （ArtSearchBar auto-search + ArtTable/useTable）。类型检查与构建都不报错，
 * 只能靠这道静态闸把整改钉成常驻约束。
 *
 * S2 追加：订单台账 PC 侧恒只读，操作列不得长出推进状态的按钮——后台改单会绕过
 * 库存动作与支付事实，让订单、库存、资金三者各说各话。
 *
 * S3-B 追加：履约页可以推进前置仓与分配节点，但**签收永远不许长在这里**——
 * 签收是用户的动作，后台代签会让「用户已确认收货」这句话失去意义。
 *
 * S4 追加：售后页可以审核、收货、质检，但**不许出现任意金额输入或库存终值输入**——
 * 应退金额由服务端按原订单不可变明细算出，库存由质检结论决定。任何一个可编辑的数字
 * 都会让「退多少」取决于谁在操作，而不是当初卖了多少。
 *
 * 用法：node scripts/check-mall-pages.mjs（非零退出即有页面回退）
 */
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const clientDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const pages = ['category', 'product', 'warehouse', 'stock', 'order', 'fulfillment', 'aftersale']

/** 门禁只看代码不看注释：自述性注释（如本说明）不得击中检查。 */
function stripComments(source) {
  return source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
}

const required = [
  ['useTable(', '必须用 useTable 管理列表状态'],
  ['ArtSearchBar', '必须用 ArtSearchBar 查询区'],
  ['auto-search', 'ArtSearchBar 必须开 auto-search（无冗余查询按钮）'],
  [':pagination="pagination"', 'ArtTable 必须绑定分页对象（消费 total）'],
  ['@pagination:size-change', '必须处理每页条数变化'],
  ['@pagination:current-change', '必须处理页码变化（可翻页）']
]

const forbidden = [
  [/\bsize:\s*(?:100|200)\b/, '禁止固定大页拉取（size:100/200）——真分页消费 total'],
  [/>\s*查询\s*<\/ElButton>/, '禁止冗余查询按钮（auto-search 已覆盖）']
]

const stockExtra = [
  ['flowTotal', '流水抽屉必须消费 total'],
  ['flowCurrent', '流水抽屉必须有页码状态'],
  ['ElPagination', '流水抽屉必须有分页组件'],
  // R2-P0：SKU 候选必须来自独立接口（SKU⋈商品）——新 SKU 无库存行也要能首次入库
  ['fetchMallSkuCandidates', 'SKU 候选必须调用独立候选接口（不得从库存行派生）'],
  ['remote-method', 'SKU 候选必须远程搜索（服务端单页硬上限 50，禁全量拉取）']
]

const stockForbidden = [
  [
    /skuOptions\s*=\s*computed/,
    'SKU 候选禁止从当前列表页 data 派生（新 SKU 无库存行即不可选，首次入库断链——R2-P0）'
  ]
]

/** 订单台账只读闸：详情入口必须在，任何推进状态的动作一律不许长回来。 */
const orderExtra = [
  ['fetchMallOrderPage', '订单台账必须走订单分页接口'],
  ['fetchMallOrderDetail', '订单详情必须走详情接口（不得从列表行拼详情）'],
  ["'详情'", '订单台账操作列必须提供详情入口']
]

const orderForbidden = [
  [
    /=>\s*'(?:取消订单|关闭订单|发货|完成订单|确认收货|退款|去支付|推进|改单)'/,
    'PC 订单台账恒只读：操作列不得出现推进订单状态的按钮'
  ],
  [
    /fetchMallOrder(?:Cancel|Ship|Finish|Refund|Pay|Advance|Update|Save|Action)\b/,
    'PC 侧不得调用订单写入接口（改单绕过库存动作与支付事实）'
  ]
]

/** 履约页闸：动作收敛在抽屉里，且后台不代签、不代替配送员推进。 */
const fulfillmentExtra = [
  ['MallFulfillmentDrawer', '履约动作必须收敛在履约抽屉组件里'],
  ['fetchMallFulfillDetail', '履约详情必须走详情接口'],
  ['fetchMallFulfillAssign', '分配必须走分配接口'],
  ['fetchMallCourierCandidates', '配送员候选必须走候选接口（不得从任意配送员列表派生）']
]

const fulfillmentForbidden = [
  [
    /=>\s*'(?:签收|确认收货|代签)'/,
    'PC 履约页不得提供签收入口——签收是用户动作，后台代签会让「用户已确认收货」失去意义'
  ],
  [
    /fetchMallFulfill(?:Sign|Fetch|Arrive)\b/,
    'PC 不得调用签收/取货/送达接口：那三步分别属于用户与配送员'
  ]
]

/** 售后页闸：受控动作可以有，金额与库存的可编辑入口一个都不许有。 */
const afterSaleExtra = [
  ['MallAfterSaleDrawer', '售后动作必须收敛在售后抽屉组件里'],
  ['fetchMallAfterSalePage', '台账必须走售后分页接口'],
  ['fetchMallAfterSaleInspect', '质检必须走质检接口（结论决定退款与回库）']
]

const afterSaleForbidden = [
  [
    /v-model="[^"]*(?:refundAmount|amountFen|stockQty|availableQty)[^"]*"/,
    'PC 售后页不得出现金额或库存的可编辑输入——退款额由服务端按原明细算出'
  ],
  [
    /fetchMallAfterSale(?:SetAmount|SetStock|ForceComplete|DirectRefund)\b/,
    'PC 不得调用任何绕过退款事实或换货状态机的「直接成功」接口'
  ]
]

let failed = false

for (const page of pages) {
  const file = path.join(clientDir, 'src/views/mall', page, 'index.vue')
  if (!fs.existsSync(file)) {
    console.error(`缺少页面文件：${file}`)
    failed = true
    continue
  }
  const source = stripComments(fs.readFileSync(file, 'utf8'))

  for (const [needle, reason] of required) {
    if (!source.includes(needle)) {
      console.error(`mall/${page}: 缺少 ${needle} —— ${reason}`)
      failed = true
    }
  }
  for (const [pattern, reason] of forbidden) {
    if (pattern.test(source)) {
      console.error(`mall/${page}: 命中禁用模式 ${pattern} —— ${reason}`)
      failed = true
    }
  }
  if (page === 'stock') {
    for (const [needle, reason] of stockExtra) {
      if (!source.includes(needle)) {
        console.error(`mall/stock: 缺少 ${needle} —— ${reason}`)
        failed = true
      }
    }
    for (const [pattern, reason] of stockForbidden) {
      if (pattern.test(source)) {
        console.error(`mall/stock: 命中禁用模式 ${pattern} —— ${reason}`)
        failed = true
      }
    }
  }
  if (page === 'aftersale') {
    const drawerDir = path.join(clientDir, 'src/views/mall/aftersale/modules')
    const drawerSource = fs.existsSync(drawerDir)
      ? fs
          .readdirSync(drawerDir)
          .filter((name) => name.endsWith('.vue'))
          .map((name) => stripComments(fs.readFileSync(path.join(drawerDir, name), 'utf8')))
          .join('\n')
      : ''
    const afterSaleSource = `${source}\n${drawerSource}`

    for (const [needle, reason] of afterSaleExtra) {
      if (!afterSaleSource.includes(needle)) {
        console.error(`mall/aftersale: 缺少 ${needle} —— ${reason}`)
        failed = true
      }
    }
    for (const [pattern, reason] of afterSaleForbidden) {
      if (pattern.test(afterSaleSource)) {
        console.error(`mall/aftersale: 命中禁用模式 ${pattern} —— ${reason}`)
        failed = true
      }
    }
  }
  if (page === 'fulfillment') {
    const drawerDir = path.join(clientDir, 'src/views/mall/fulfillment/modules')
    const drawerSource = fs.existsSync(drawerDir)
      ? fs
          .readdirSync(drawerDir)
          .filter((name) => name.endsWith('.vue'))
          .map((name) => stripComments(fs.readFileSync(path.join(drawerDir, name), 'utf8')))
          .join('\n')
      : ''
    const fulfillSource = `${source}\n${drawerSource}`

    for (const [needle, reason] of fulfillmentExtra) {
      if (!fulfillSource.includes(needle)) {
        console.error(`mall/fulfillment: 缺少 ${needle} —— ${reason}`)
        failed = true
      }
    }
    for (const [pattern, reason] of fulfillmentForbidden) {
      if (pattern.test(fulfillSource)) {
        console.error(`mall/fulfillment: 命中禁用模式 ${pattern} —— ${reason}`)
        failed = true
      }
    }
  }
  if (page === 'order') {
    // 详情抽屉与页面同属一页，一并纳入检查（抽屉里同样不得出现写入调用）
    const drawerDir = path.join(clientDir, 'src/views/mall/order/modules')
    const drawerSource = fs.existsSync(drawerDir)
      ? fs
          .readdirSync(drawerDir)
          .filter((name) => name.endsWith('.vue'))
          .map((name) => stripComments(fs.readFileSync(path.join(drawerDir, name), 'utf8')))
          .join('\n')
      : ''
    const orderSource = `${source}\n${drawerSource}`

    for (const [needle, reason] of orderExtra) {
      if (!orderSource.includes(needle)) {
        console.error(`mall/order: 缺少 ${needle} —— ${reason}`)
        failed = true
      }
    }
    for (const [pattern, reason] of orderForbidden) {
      if (pattern.test(orderSource)) {
        console.error(`mall/order: 命中禁用模式 ${pattern} —— ${reason}`)
        failed = true
      }
    }
  }
}

if (failed) {
  process.exit(1)
}

console.log(`商城页面组件规范与真分页通过（${pages.join(', ')}）`)
