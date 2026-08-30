import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const currentDir = path.dirname(fileURLToPath(import.meta.url))
const srcDir = path.resolve(currentDir, '../src')
const manifestPath = path.join(srcDir, 'manifest.json')
const pagesPath = path.join(srcDir, 'pages.json')

const pages = {
  pages: [
    {
      path: 'pages/entry/index',
      type: 'home',
      style: { navigationStyle: 'custom', navigationBarTitleText: '启动' },
    },
    {
      path: 'pages/user/home/index',
      style: { navigationStyle: 'custom', navigationBarTitleText: '首页' },
    },
    {
      path: 'pages/user/order/index',
      style: { navigationStyle: 'custom', navigationBarTitleText: '订单' },
    },
    {
      path: 'pages/user/profile/index',
      style: { navigationStyle: 'custom', navigationBarTitleText: '我的' },
    },
  ],
  subPackages: [],
}

fs.mkdirSync(srcDir, { recursive: true })
if (!fs.existsSync(manifestPath)) {
  fs.writeFileSync(manifestPath, '{}\n')
}
// pages.json 是 uni-pages 的忽略入库生成物，必须在每次构建前回到最小基线。
// 若只在文件缺失时创建，上一次“全主包”产物会被下次分包构建继续合并，页面同时进入主包和分包，
// 微信上传仍按重复后的主包计算，最终报 80051 超过 2MB。
fs.writeFileSync(pagesPath, `${JSON.stringify(pages, null, 2)}\n`)
