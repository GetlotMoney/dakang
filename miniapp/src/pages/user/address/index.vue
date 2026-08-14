<script setup lang="ts">
import type { DeliveryAddress } from '@/api/card'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useMessage, useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { ContractError } from '@/api/common'
import AppBottomActionBar from '@/components/app-bottom-action-bar.vue'
import AppNavbar from '@/components/app-navbar.vue'
import AppPageState from '@/components/app-page-state.vue'
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
  <!-- 吸底栏是条件渲染的：栏出现时由它的等高 placeholder 负责底部留白，
       此时才去掉 page-shell 为旧流式底栏预留的 84px，否则两段空白叠加。 -->
  <view class="page-shell" :class="{ 'page-shell--with-bar': !loading && !errorMessage && !selectMode }">
    <AppNavbar title="水配送地址" back-to="U03" />
    <wd-toast />
    <wd-message-box />

    <view v-if="loading" class="page-section">
      <AppPageState state="loading" :row-col="[1, 1, { width: '60%' }]" />
    </view>

    <view v-else-if="errorMessage" class="page-section">
      <AppPageState state="error" :message="errorMessage">
        <template #actions>
          <wd-button size="small" plain @click="refresh">
            重新加载
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <view v-else-if="!addresses.length" class="page-section">
      <AppPageState state="empty" message="暂无水配送地址">
        <template #actions>
          <wd-button size="small" icon="add-circle" @click="handleAdd">
            新增地址
          </wd-button>
        </template>
      </AppPageState>
    </view>

    <!-- 一张连续的白底表，不是一摞阴影卡。收货人与手机号第一层，区域与详址第二层；
         编辑与删除降到文字级，主入口「新增地址」上吸底栏常驻。 -->
    <view v-else class="address-list">
      <view
        v-for="item in addresses"
        :key="item.addressId"
        class="address-row"
        :class="{ pressable: selectMode }"
        @click="handleItemTap(item)"
      >
        <view class="address-row__head">
          <text class="address-row__contact">
            {{ item.contactName }}
          </text>
          <text class="address-row__phone">
            {{ item.maskedPhone }}
          </text>
          <wd-tag v-if="item.isDefault" type="primary" plain>
            默认
          </wd-tag>
          <!-- 缺区县编码才出标签：定位/编码齐全是常态，常态不上标签，
               否则每一行都挂两枚状态 tag，真正缺东西的那一行反而看不出来 -->
          <wd-tag v-if="!item.districtCode" type="warning" plain>
            缺区县编码
          </wd-tag>
        </view>
        <text class="address-row__region">
          {{ item.region }}
        </text>
        <text class="address-row__detail">
          {{ item.detail }}
        </text>
        <view v-if="selectMode" class="address-row__select">
          点击选择该地址
          <wd-icon name="arrow-right" size="14px" />
        </view>
        <view v-else class="address-row__actions">
          <text class="address-row__link" @click.stop="goTo('U15', { addressId: item.addressId })">
            编辑
          </text>
          <text class="address-row__link address-row__link--danger" @click.stop="handleDelete(item)">
            删除
          </text>
        </view>
      </view>
    </view>

    <AppBottomActionBar v-if="!loading && !errorMessage && !selectMode">
      <template #primary>
        <wd-button block size="large" type="primary" icon="add-circle" @click="handleAdd">
          新增地址
        </wd-button>
      </template>
    </AppBottomActionBar>
  </view>
</template>

<style scoped lang="scss">
.address-list {
  margin-top: var(--gap-block);
  overflow: hidden;
  border-radius: var(--r-md);
  background: var(--app-bg-card);
}

.address-row {
  padding: var(--sp-4);
  border-bottom: 1px solid var(--line-1);

  &:last-child {
    border-bottom: none;
  }

  &__head {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-2);
    align-items: center;
  }

  &__contact {
    max-width: 40%;
    overflow: hidden;
    font-size: var(--fs-body);
    font-weight: 600;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__phone {
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }

  &__region {
    display: block;
    margin-top: var(--sp-2);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);
  }

  // 详址长度不可控，最多两行；完整地址在编辑页可见
  &__detail {
    display: -webkit-box;
    overflow: hidden;
    margin-top: 2px;
    font-size: var(--fs-caption);
    line-height: 1.45;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  &__select {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 2px;
    margin-top: var(--sp-3);
    color: var(--app-color-primary);
    font-size: var(--fs-caption);
    font-weight: 600;
  }

  // 编辑与删除弱化成文字级：它们是低频维护动作，不该和「新增地址」抢按钮形态
  &__actions {
    display: flex;
    gap: var(--sp-5);
    justify-content: flex-end;
    margin-top: var(--sp-3);
    padding-top: var(--sp-3);
    border-top: 1px solid var(--line-1);
  }

  &__link {
    padding: 2px var(--sp-2);
    color: var(--app-text-secondary);
    font-size: var(--fs-caption);

    &--danger {
      color: var(--app-color-danger);
    }
  }
}
</style>
