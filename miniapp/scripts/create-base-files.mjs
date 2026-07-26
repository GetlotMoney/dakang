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
if (!fs.existsSync(pagesPath)) {
  fs.writeFileSync(pagesPath, `${JSON.stringify(pages, null, 2)}\n`)
}
