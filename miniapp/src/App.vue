<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { useAccountStore } from '@/store/account'

const accountStore = useAccountStore()

onLaunch(async () => {
  try {
    await accountStore.restoreSession()
  }
  catch {
    // 冷启动没有会话是正常的未登录状态，由 C01 入口继续完成正式登录。
  }
})
</script>
