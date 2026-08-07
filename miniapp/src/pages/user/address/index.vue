<script setup lang="ts">
import type { DeliveryAddress } from '@/api/card'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { ContractError } from '@/api/common'
import AppNavbar from '@/components/app-navbar.vue'
import { setDraftAddress } from '@/store/delivery-draft'
import { goTo } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '水配送地址',
  },
})

const toast = useToast()
const message = useMessage()

/** returnTo=delivery 时本页为 U08 的地址选择模式：选择后写草稿回 U08。 */
// 2026-08-02 链路落地：地址簿已接服务端（按账号隔离），配送下单可直接选用。
const selectMode = ref(false)
const loading = ref(true)
const errorMessage = ref('')
const addresses = ref<DeliveryAddress[]>([])

onLoad((options?: Record<string, string>) => {
  selectMode.value = (options ?? {}).returnTo === 'delivery'
})

// onShow 刷新：U15 新增/编辑保存返回后立即可见最新地址。
onShow(refresh)

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    addresses.value = await cardApi.listDeliveryAddresses()
  }
  catch (error) {
    addresses.value = []
    errorMessage.value = error instanceof ContractError ? error.message : '地址加载失败，请重试'
  }
  finally {
    loading.value = false
  }
}

function handleItemTap(item: DeliveryAddress) {
  if (selectMode.value) {
    // 选择结果只写页面间草稿并返回，由 U08 onShow 消费（store/delivery-draft 契约）。
    setDraftAddress(item.addressId)
    uni.navigateBack({})
    return
  }
  goTo('U15', { addressId: item.addressId })
}

function handleAdd() {
  if (selectMode.value) {
    goTo('U15', { returnTo: 'delivery' })
    return
  }
  goTo('U15')
}

function handleDelete(item: DeliveryAddress) {
  message
    .confirm({
      title: '删除地址',
      msg: `确认删除「${item.contactName}」的水配送地址？删除后不可恢复。`,
    })
    .then(async () => {
      try {
        await cardApi.deleteDeliveryAddress(item.addressId)
        toast.success('地址已删除')
        await refresh()
      }
      catch (error) {
        toast.show(error instanceof ContractError ? error.message : '删除失败，请重试')
      }
    })
    .catch(() => null)
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar title="水配送地址" back-to="U03" />
    <wd-toast />
    <wd-message-box />

    <view v-if="loading" class="page-section muted-text">
      地址加载中…
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <wd-status-tip image="network" :tip="errorMessage">
        <template #bottom>
          <view class="status-actions">
            <wd-button size="small" plain @click="refresh">
              重新加载
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <view v-else-if="!addresses.length" class="page-section">
      <wd-status-tip image="content" tip="暂无水配送地址">
        <template #bottom>
          <view class="status-actions">
            <wd-button size="small" icon="add-circle" @click="handleAdd">
              新增地址
            </wd-button>
          </view>
        </template>
      </wd-status-tip>
    </view>

    <view v-else class="page-section">
      <wd-card v-for="item in addresses" :key="item.addressId">
        <template #title>
          <view class="card-title-row" @click="handleItemTap(item)">
            <view class="address-contact">
              {{ item.contactName }}
              <text class="muted-text">
                {{ item.maskedPhone }}
              </text>
            </view>
            <view class="address-tags">
              <wd-tag v-if="item.isDefault" type="primary" plain>
                默认
              </wd-tag>
              <wd-tag :type="item.locationAuthorized ? 'success' : 'default'" plain>
                {{ item.locationAuthorized ? '定位已授权' : '定位未授权' }}
              </wd-tag>
            </view>
          </view>
        </template>
        <view class="address-body" @click="handleItemTap(item)">
          <view>{{ item.region }}</view>
          <view class="muted-text">
            {{ item.detail }}
          </view>
          <view v-if="selectMode" class="select-cue">
            点击选择该地址
            <wd-icon name="arrow-right" size="14px" />
          </view>
        </view>
        <template v-if="!selectMode" #footer>
          <view class="address-actions">
            <wd-button size="small" plain @click="goTo('U15', { addressId: item.addressId })">
              编辑
            </wd-button>
            <wd-button size="small" plain type="error" @click="handleDelete(item)">
              删除
            </wd-button>
          </view>
        </template>
      </wd-card>
    </view>

    <view v-if="!loading && !errorMessage && addresses.length" class="page-section">
      <wd-button block plain icon="add-circle" @click="handleAdd">
        新增地址
      </wd-button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.status-actions {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  width: 100%;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.address-contact {
  font-size: 15px;
  font-weight: 600;
}

.address-tags {
  display: flex;
  align-items: center;
  gap: 6px;
}

.address-body {
  font-size: 14px;
  line-height: 1.7;
}

.select-cue {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-top: 6px;
  color: var(--wot-color-theme, var(--app-color-primary));
  font-size: 13px;
}

.address-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
