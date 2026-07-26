<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { useAccountStore } from '@/store/account'

const accountStore = useAccountStore()

onLaunch(() => {
  void accountStore.restoreSession()
})
</script>
