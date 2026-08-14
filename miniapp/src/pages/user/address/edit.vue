<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { computed, reactive, ref } from 'vue'
import { useToast } from 'wot-design-uni'
import { cardApi } from '@/api/card'
import { ContractError } from '@/api/common'
import AppNavbar from '@/components/app-navbar.vue'
import { backOr } from '@/utils/navigation'

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '编辑水配送地址',
  },
})

const toast = useToast()

const addressId = ref('')
/** 契约只回传脱敏号码（DeliveryAddress.maskedPhone），编辑时用于提示，完整号码需重新填写。 */
const editingMaskedPhone = ref('')
const saving = ref(false)

const model = reactive({
  contactName: '',
  phone: '',
  region: '',
  districtCode: '',
  detail: '',
  isDefault: false,
  locationAuthorized: false,
})

const formRef = ref<{ validate: () => Promise<{ valid: boolean }> } | null>(null)

const pageTitle = computed(() => (addressId.value ? '编辑水配送地址' : '新增水配送地址'))
const phonePlaceholder = computed(() =>
  editingMaskedPhone.value
    ? `原号码 ${editingMaskedPhone.value}，保存需重新填写完整号码`
    : '请输入 11 位手机号',
)

const phoneRules = [{ required: true, pattern: /^1\d{10}$/, message: '请填写 11 位有效手机号' }]

/**
 * 区县码刻意不设 required：水配送不需要它，只有商城下单要用。
 * 设成必填会把「只想改个电话」的老地址挡在保存之外，代价远大于收益。
 * 填了就必须是 6 位，半截值比空值更糟——服务端会拿它去选仓且必然选不到。
 */
const districtRules = [{ required: false, pattern: /^$|^\d{6}$/, message: '区县编码为 6 位数字' }]

onLoad(async (options?: Record<string, string>) => {
  const query = options ?? {}
  addressId.value = query.addressId ?? ''
  if (!addressId.value) {
    return
  }
  try {
    const address = await cardApi.getDeliveryAddress(addressId.value)
    model.contactName = address.contactName
    model.region = address.region
    model.districtCode = address.districtCode ?? ''
    model.detail = address.detail
    model.isDefault = address.isDefault
    model.locationAuthorized = address.locationAuthorized
    editingMaskedPhone.value = address.maskedPhone
  }
  catch (error) {
    toast.show(error instanceof ContractError ? error.message : '地址加载失败')
  }
})

async function handleSave() {
  if (saving.value || !formRef.value) {
    return
  }
  const result = await formRef.value.validate().catch(() => ({ valid: false }))
  if (!result.valid) {
    return
  }
  saving.value = true
  try {
    await cardApi.saveDeliveryAddress({
      addressId: addressId.value || undefined,
      contactName: model.contactName,
      phone: model.phone,
      region: model.region,
      districtCode: model.districtCode,
      detail: model.detail,
      isDefault: model.isDefault,
      locationAuthorized: model.locationAuthorized,
    })
    // 成功后不复位 saving：短暂展示结果后返回 U14，避免重复提交。
    toast.success('地址已保存')
    setTimeout(() => backOr('U14'), 600)
  }
  catch (error) {
    // 失败保留表单草稿，仅提示契约错误信息。
    saving.value = false
    toast.show(error instanceof ContractError ? error.message : '保存失败，请重试')
  }
}
</script>

<template>
  <view class="page-shell">
    <AppNavbar :title="pageTitle" back-to="U14" />
    <wd-toast />

    <view class="page-section">
      <wd-form ref="formRef" :model="model">
        <wd-cell-group border>
          <wd-input
            v-model="model.contactName"
            label="联系人"
            prop="contactName"
            required
            clearable
            placeholder="请输入联系人姓名"
            :rules="[{ required: true, message: '请填写联系人' }]"
          />
          <wd-input
            v-model="model.phone"
            label="手机号"
            prop="phone"
            required
            clearable
            type="number"
            :maxlength="11"
            :placeholder="phonePlaceholder"
            :rules="phoneRules"
          />
          <wd-input
            v-model="model.region"
            label="地区"
            prop="region"
            required
            clearable
            placeholder="请输入省市区，如：武汉东湖高新区"
            :rules="[{ required: true, message: '请填写地区' }]"
          />
          <wd-input
            v-model="model.districtCode"
            label="区县编码"
            prop="districtCode"
            clearable
            type="text"
            :maxlength="6"
            placeholder="选填，6 位数字，商城下单需要"
            :rules="districtRules"
          />
          <wd-textarea
            v-model="model.detail"
            label="详细地址"
            prop="detail"
            required
            auto-height
            :maxlength="100"
            show-word-limit
            placeholder="小区/楼栋/门牌号"
            :rules="[{ required: true, message: '请填写详细地址' }]"
          />
          <wd-cell title="设为默认地址" center>
            <wd-switch v-model="model.isDefault" />
          </wd-cell>
          <wd-cell title="定位授权" center>
            <wd-switch v-model="model.locationAuthorized" />
          </wd-cell>
        </wd-cell-group>
      </wd-form>
    </view>

    <view class="page-section">
      <wd-button block size="large" :loading="saving" @click="handleSave">
        保存地址
      </wd-button>
    </view>
  </view>
</template>
