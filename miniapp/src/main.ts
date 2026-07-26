import { createSSRApp } from 'vue'
import App from './App.vue'
import { registerSessionInvalidHandler } from '@/api/session-guard'
import { routeGuard } from '@/router/guard'
import store from '@/store'
import '@/style/index.scss'

export function createApp() {
  const app = createSSRApp(App)
  app.use(store)
  app.use(routeGuard)
  // 注册全局会话失效处理器：任一请求收到 1401~1405 即清会话并回登录入口 C01（复审 P1-2）。
  registerSessionInvalidHandler()

  return { app }
}
