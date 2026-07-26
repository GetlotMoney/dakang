<!-- 图标选择器组件 -->
<!-- 支持从预设图标库中选择图标，支持搜索和分类过滤 -->
<template>
  <div class="art-icon-picker">
    <div class="icon-preview" @click="dialogVisible = true">
      <ArtSvgIcon v-if="modelValue" :icon="modelValue" class="preview-icon" />
      <span v-else class="placeholder-text">{{ placeholder }}</span>
      <ElIcon class="edit-icon"><Edit /></ElIcon>
    </div>

    <ElDialog
      v-model="dialogVisible"
      title="选择图标"
      width="780px"
      class="icon-picker-dialog"
      destroy-on-close
    >
      <div class="picker-container">
        <!-- 搜索框 -->
        <div class="search-bar">
          <ElInput
            v-model="searchText"
            placeholder="搜索图标..."
            clearable
            prefix-icon="Search"
            @input="handleSearch"
          />
        </div>

        <!-- 分类标签 -->
        <div class="category-tabs">
          <div
            v-for="cat in categories"
            :key="cat.key"
            class="category-tab"
            :class="{ active: activeCategory === cat.key }"
            @click="activeCategory = cat.key"
          >
            {{ cat.label }}
          </div>
        </div>

        <!-- 图标网格 -->
        <div class="icon-grid" v-loading="loading">
          <template v-if="filteredIcons.length > 0">
            <div
              v-for="icon in filteredIcons"
              :key="icon"
              class="icon-item"
              :class="{ selected: modelValue === icon }"
              @click="handleSelect(icon)"
            >
              <ArtSvgIcon :icon="icon" class="icon-display" />
              <span class="icon-name">{{ icon.split(':')[1] }}</span>
            </div>
          </template>
          <div v-else class="empty-state">
            <ElIcon :size="40" class="text-g-400"><DocumentDelete /></ElIcon>
            <p>未找到匹配图标</p>
          </div>
        </div>

        <!-- 已选图标预览 -->
        <div class="selected-preview" v-if="modelValue">
          <span class="preview-label">已选图标：</span>
          <ArtSvgIcon :icon="modelValue" class="preview-icon" />
          <span class="preview-name">{{ modelValue }}</span>
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <ElButton @click="handleClear">清空</ElButton>
          <ElButton @click="dialogVisible = false">取 消</ElButton>
          <ElButton type="primary" @click="handleConfirm">确 定</ElButton>
        </div>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { Icon } from '@iconify/vue'
  import { Edit, Search, DocumentDelete } from '@element-plus/icons-vue'
  import ArtSvgIcon from '@/components/core/base/art-svg-icon/index.vue'

  defineOptions({ name: 'ArtIconPicker' })

  interface Props {
    /** 当前选中的图标 */
    modelValue?: string
    /** 占位提示文字 */
    placeholder?: string
  }

  const props = withDefaults(defineProps<Props>(), {
    placeholder: '点击选择图标'
  })

  interface Emits {
    (e: 'update:modelValue', value: string): void
  }

  const emit = defineEmits<Emits>()

  const dialogVisible = ref(false)
  const searchText = ref('')
  const activeCategory = ref('all')
  const loading = ref(false)
  const tempSelected = ref('')

  // 预设图标库（常用图标，可根据项目需求扩展）
  const iconSets = {
    // Remix Icon（主要图标集）
    remix: [
      'ri:home-line',
      'ri:home-fill',
      'ri:dashboard-line',
      'ri:dashboard-fill',
      'ri:user-line',
      'ri:user-fill',
      'ri:user-2-line',
      'ri:user-2-fill',
      'ri:team-line',
      'ri:team-fill',
      'ri:group-line',
      'ri:group-fill',
      'ri:admin-line',
      'ri:admin-fill',
      'ri:shield-user-line',
      'ri:shield-user-fill',
      'ri:menu-line',
      'ri:menu-fill',
      'ri:menu-2-line',
      'ri:menu-2-fill',
      'ri:settings-line',
      'ri:settings-fill',
      'ri:settings-2-line',
      'ri:settings-2-fill',
      'ri:tool-line',
      'ri:tool-fill',
      'ri:configuration-line',
      'ri:configuration-fill',
      'ri:book-line',
      'ri:book-fill',
      'ri:book-open-line',
      'ri:book-open-fill',
      'ri:file-list-line',
      'ri:file-list-fill',
      'ri:file-line',
      'ri:file-fill',
      'ri:folder-line',
      'ri:folder-fill',
      'ri:folder-2-line',
      'ri:folder-2-fill',
      'ri:folder-3-line',
      'ri:folder-3-fill',
      'ri:archive-line',
      'ri:archive-fill',
      'ri:database-line',
      'ri:database-fill',
      'ri:server-line',
      'ri:server-fill',
      'ri:cloud-line',
      'ri:cloud-fill',
      'ri:cloud-off-line',
      'ri:cloud-off-fill',
      'ri:global-line',
      'ri:globe-line',
      'ri:earth-line',
      'ri:globe',
      'ri:customer-service-line',
      'ri:customer-service-fill',
      'ri:headphone-line',
      'ri:headphone-fill',
      'ri:message-line',
      'ri:message-fill',
      'ri:chat-1-line',
      'ri:chat-1-fill',
      'ri:chat-2-line',
      'ri:chat-2-fill',
      'ri:chat-3-line',
      'ri:chat-3-fill',
      'ri:mail-line',
      'ri:mail-fill',
      'ri:mail-open-line',
      'ri:mail-open-fill',
      'ri:send-line',
      'ri:send-fill',
      'ri:time-line',
      'ri:time-fill',
      'ri:calendar-line',
      'ri:calendar-fill',
      'ri:calendar-2-line',
      'ri:calendar-2-fill',
      'ri:schedule-line',
      'ri:schedule-fill',
      'ri:alarm-line',
      'ri:alarm-fill',
      'ri:bell-line',
      'ri:bell-fill',
      'ri:notification-line',
      'ri:notification-fill',
      'ri:announcement-line',
      'ri:announcement-fill',
      'ri:flag-line',
      'ri:flag-fill',
      'ri:bookmark-line',
      'ri:bookmark-fill',
      'ri:star-line',
      'ri:star-fill',
      'ri:heart-line',
      'ri:heart-fill',
      'ri:thumb-up-line',
      'ri:thumb-up-fill',
      'ri:like-line',
      'ri:like-fill',
      'ri:dislike-line',
      'ri:dislike-fill',
      'ri:eye-line',
      'ri:eye-fill',
      'ri:eye-off-line',
      'ri:eye-off-fill',
      'ri:search-line',
      'ri:search-fill',
      'ri:zoom-in-line',
      'ri:zoom-in-fill',
      'ri:zoom-out-line',
      'ri:zoom-out-fill',
      'ri:filter-line',
      'ri:filter-fill',
      'ri:sort-line',
      'ri:sort-asc',
      'ri:sort-desc',
      'ri:arrow-up-line',
      'ri:arrow-down-line',
      'ri:arrow-left-line',
      'ri:arrow-right-line',
      'ri:check-line',
      'ri:check-fill',
      'ri:close-line',
      'ri:close-fill',
      'ri:add-line',
      'ri:add-fill',
      'ri:subtract-line',
      'ri:subtract-fill',
      'ri:delete-bin-line',
      'ri:delete-bin-fill',
      'ri:trash-line',
      'ri:trash-fill',
      'ri:edit-line',
      'ri:edit-fill',
      'ri:edit-2-line',
      'ri:edit-2-fill',
      'ri:pencil-line',
      'ri:pencil-fill',
      'ri:write-line',
      'ri:write-fill',
      'ri:copy-line',
      'ri:copy-fill',
      'ri:clipboard-line',
      'ri:clipboard-fill',
      'ri:scissors-cut-line',
      'ri:scissors-fill',
      'ri:attachment-line',
      'ri:attachment-fill',
      'ri:download-line',
      'ri:download-fill',
      'ri:upload-line',
      'ri:upload-fill',
      'ri:export-line',
      'ri:export-fill',
      'ri:import-line',
      'ri:import-fill',
      'ri:save-line',
      'ri:save-fill',
      'ri:save-2-line',
      'ri:save-2-fill',
      'ri:lock-line',
      'ri:lock-fill',
      'ri:unlock-line',
      'ri:unlock-fill',
      'ri:key-line',
      'ri:key-fill',
      'ri:password-line',
      'ri:password-fill',
      'ri:shield-line',
      'ri:shield-fill',
      'ri:shield-check-line',
      'ri:shield-check-fill',
      'ri:eye-close-line',
      'ri:scan-line',
      'ri:scan-fill',
      'ri:qr-code-line',
      'ri:barcode-line',
      'ri:barcode-fill',
      'ri:database-2-line',
      'ri:database-2-fill',
      'ri:hdd-line',
      'ri:hdd-fill',
      'ri:computer-line',
      'ri:computer-fill',
      'ri:device-line',
      'ri:device-fill',
      'ri:tablet-line',
      'ri:tablet-fill',
      'ri:smartphone-line',
      'ri:smartphone-fill',
      'ri:phone-line',
      'ri:phone-fill',
      'ri:tv-line',
      'ri:tv-fill',
      'ri:printer-line',
      'ri:printer-fill',
      'ri:camera-line',
      'ri:camera-fill',
      'ri:camera-off-line',
      'ri:camera-off-fill',
      'ri:image-line',
      'ri:image-fill',
      'ri:image-2-line',
      'ri:image-2-fill',
      'ri:gallery-line',
      'ri:gallery-fill',
      'ri:video-line',
      'ri:video-fill',
      'ri:music-line',
      'ri:music-fill',
      'ri:headphone-line',
      'ri:headphone-fill',
      'ri:voiceprint-line',
      'ri:voice-line',
      'ri:mic-line',
      'ri:mic-fill',
      'ri:mic-off-line',
      'ri:mic-off-fill',
      'ri:speaker-line',
      'ri:speaker-fill',
      'ri:volume-line',
      'ri:volume-fill',
      'ri:volume-mute-line',
      'ri:volume-mute-fill',
      'ri:sound-module-line',
      'ri:sound-module-fill',
      'ri:links-line',
      'ri:links-fill',
      'ri:link',
      'ri:link-m',
      'ri:link-unlink',
      'ri:link-unlink-m',
      'ri:share-line',
      'ri:share-fill',
      'ri:share-forward-line',
      'ri:share-forward-fill',
      'ri:share-circle-line',
      'ri:share-circle-fill',
      'ri:share-box-line',
      'ri:share-box-fill',
      'ri:external-link-line',
      'ri:external-link-fill',
      'ri:window-line',
      'ri:window-fill',
      'ri:apps-line',
      'ri:apps-fill',
      'ri:grid-line',
      'ri:grid-fill',
      'ri:columns-line',
      'ri:columns-fill',
      'ri:layout-line',
      'ri:layout-fill',
      'ri:layout-2-line',
      'ri:layout-2-fill',
      'ri:layout-3-line',
      'ri:layout-3-fill',
      'ri:layout-4-line',
      'ri:layout-4-fill',
      'ri:layout-5-line',
      'ri:layout-5-fill',
      'ri:layout-6-line',
      'ri:layout-6-fill',
      'ri:news-line',
      'ri:news-fill',
      'ri:article-line',
      'ri:article-fill',
      'ri:newspaper-line',
      'ri:newspaper-fill',
      'ri:booklet-line',
      'ri:booklet-fill',
      'ri:briefcase-line',
      'ri:briefcase-fill',
      'ri:handbag-line',
      'ri:handbag-fill',
      'ri:shopping-bag-line',
      'ri:shopping-bag-fill',
      'ri:shopping-cart-line',
      'ri:shopping-cart-fill',
      'ri:store-line',
      'ri:store-fill',
      'ri:store-2-line',
      'ri:store-2-fill',
      'ri:bank-line',
      'ri:bank-fill',
      'ri:coin-line',
      'ri:coin-fill',
      'ri:coins-line',
      'ri:coins-fill',
      'ri:wallet-line',
      'ri:wallet-fill',
      'ri:credit-card-line',
      'ri:credit-card-fill',
      'ri:shopping-basket-line',
      'ri:shopping-basket-fill',
      'ri:coupon-line',
      'ri:coupon-fill',
      'ri:price-tag-line',
      'ri:price-tag-fill',
      'ri:bill-line',
      'ri:bill-fill',
      'ri:percentage-line',
      'ri:percentage-fill',
      'ri:discount-line',
      'ri:discount-fill',
      'ri:sale-line',
      'ri:sale-fill',
      'ri:gift-line',
      'ri:gift-fill',
      'ri:trophy-line',
      'ri:trophy-fill',
      'ri:medal-line',
      'ri:medal-fill',
      'ri:crown-line',
      'ri:crown-fill',
      'ri:diamond-line',
      'ri:diamond-fill',
      'ri:sparkling-line',
      'ri:sparkling-fill',
      'ri:award-line',
      'ri:award-fill',
      'ri:ribbon-line',
      'ri:ribbon-fill',
      'ri:medal-2-line',
      'ri:medal-2-fill',
      'ri:location-line',
      'ri:location-fill',
      'ri:map-pin-line',
      'ri:map-pin-fill',
      'ri:map-line',
      'ri:map-fill',
      'ri:navigation-line',
      'ri:navigation-fill',
      'ri:compass-line',
      'ri:compass-fill',
      'ri:pin-distance-line',
      'ri:pin-distance-fill',
      'ri:car-line',
      'ri:car-fill',
      'ri:bus-line',
      'ri:bus-fill',
      'ri:train-line',
      'ri:train-fill',
      'ri:flight-line',
      'ri:flight-fill',
      'ri:ship-line',
      'ri:ship-fill',
      'ri:bike-line',
      'ri:bike-fill',
      'ri:walk-line',
      'ri:walk-fill',
      'ri:run-line',
      'ri:run-fill',
      'ri:restaurant-line',
      'ri:restaurant-fill',
      'ri:hotel-line',
      'ri:hotel-fill',
      'ri:hospital-line',
      'ri:hospital-fill',
      'ri:bank-line',
      'ri:bank-fill',
      'ri:school-line',
      'ri:school-fill',
      'ri:government-line',
      'ri:government-fill',
      'ri:home-2-line',
      'ri:home-2-fill',
      'ri:home-3-line',
      'ri:home-3-fill',
      'ri:home-4-line',
      'ri:home-4-fill',
      'ri:home-5-line',
      'ri:home-5-fill',
      'ri:home-6-line',
      'ri:home-6-fill',
      'ri:home-7-line',
      'ri:home-7-fill',
      'ri:home-8-line',
      'ri:home-8-fill',
      'ri:home-gear-line',
      'ri:home-gear-fill',
      'ri:home-smile-line',
      'ri:home-smile-fill',
      'ri:home-heart-line',
      'ri:home-heart-fill',
      'ri:building-line',
      'ri:building-fill',
      'ri:building-2-line',
      'ri:building-2-fill',
      'ri:building-3-line',
      'ri:building-3-fill',
      'ri:building-4-line',
      'ri:building-4-fill',
      'ri:tree-line',
      'ri:tree-fill',
      'ri:plant-line',
      'ri:plant-fill',
      'ri:leaf-line',
      'ri:leaf-fill',
      'ri:sun-line',
      'ri:sun-fill',
      'ri:moon-line',
      'ri:moon-fill',
      'ri:flashlight-line',
      'ri:flashlight-fill',
      'ri:contrast-line',
      'ri:contrast-fill',
      'ri:drop-line',
      'ri:drop-fill',
      'ri:water-drop-line',
      'ri:water-drop-fill',
      'ri:fire-line',
      'ri:fire-fill',
      'ri:temp-hot-line',
      'ri:temp-hot-fill',
      'ri:temp-cold-line',
      'ri:temp-cold-fill',
      'ri:cloudy-line',
      'ri:cloudy-fill',
      'ri:snowy-line',
      'ri:snowy-fill',
      'ri:rainy-line',
      'ri:rainy-fill',
      'ri:thunderstorms-line',
      'ri:thunderstorms-fill',
      'ri:hail-line',
      'ri:hail-fill',
      'ri:foggy-line',
      'ri:foggy-fill',
      'ri:wind-line',
      'ri:wind-fill',
      'ri:compass-2-line',
      'ri:compass-2-fill',
      'ri:compass-3-line',
      'ri:compass-3-fill',
      'ri:compass-4-line',
      'ri:compass-4-fill',
      'ri:information-line',
      'ri:information-fill',
      'ri:question-line',
      'ri:question-fill',
      'ri:error-warning-line',
      'ri:error-warning-fill',
      'ri:alert-line',
      'ri:alert-fill',
      'ri:warning-line',
      'ri:warning-fill',
      'ri:checkbox-blank-circle-line',
      'ri:checkbox-blank-circle-fill',
      'ri:checkbox-circle-line',
      'ri:checkbox-circle-fill',
      'ri:checkbox-line',
      'ri:checkbox-fill',
      'ri:radio-button-line',
      'ri:radio-button-fill',
      'ri:toggle-line',
      'ri:toggle-fill',
      'ri:information-2-line',
      'ri:information-2-fill',
      'ri:skip-back-line',
      'ri:skip-back-fill',
      'ri:skip-forward-line',
      'ri:skip-forward-fill',
      'ri:play-line',
      'ri:play-fill',
      'ri:pause-line',
      'ri:pause-fill',
      'ri:stop-line',
      'ri:stop-fill',
      'ri:rewind-line',
      'ri:rewind-fill',
      'ri:fast-forward-line',
      'ri:fast-forward-fill',
      'ri:shuffle-line',
      'ri:shuffle-fill',
      'ri:repeat-line',
      'ri:repeat-fill',
      'ri:repeat-2-line',
      'ri:repeat-2-fill',
      'ri:order-play-line',
      'ri:order-play-fill',
      'ri:list-check',
      'ri:list-ordered',
      'ri:listunordered',
      'ri:list-check-2',
      'ri:task-line',
      'ri:task-fill',
      'ri:todo-line',
      'ri:todo-fill',
      'ri:bookmark-2-line',
      'ri:bookmark-2-fill',
      'ri:bookmark-3-line',
      'ri:bookmark-3-fill',
      'ri:price-chart-line',
      'ri:price-chart-fill',
      'ri:pie-chart-line',
      'ri:pie-chart-fill',
      'ri:pie-chart-2-line',
      'ri:pie-chart-2-fill',
      'ri:bar-chart-line',
      'ri:bar-chart-fill',
      'ri:bar-chart-2-line',
      'ri:bar-chart-2-fill',
      'ri:line-chart-line',
      'ri:line-chart-fill',
      'ri:line-chart-up-line',
      'ri:line-chart-up-fill',
      'ri:line-chart-down-line',
      'ri:line-chart-down-fill',
      'ri:growth-line',
      'ri:growth-fill',
      'ri:money-dollar-circle-line',
      'ri:money-dollar-circle-fill',
      'ri:money-euro-circle-line',
      'ri:money-euro-circle-fill',
      'ri:money-pound-circle-line',
      'ri:money-pound-circle-fill',
      'ri:money-yen-circle-line',
      'ri:money-yen-circle-fill',
      'ri:refund-line',
      'ri:refund-fill',
      'ri:refund-2-line',
      'ri:refund-2-fill',
      'ri:secure-payment-line',
      'ri:secure-payment-fill',
      'ri:hand-coin-line',
      'ri:hand-coin-fill',
      'ri:hand-heart-line',
      'ri:hand-heart-fill',
      'ri:handshake-line',
      'ri:handshake-fill',
      'ri:contacts-line',
      'ri:contacts-fill',
      'ri:user-search-line',
      'ri:user-search-fill',
      'ri:user-location-line',
      'ri:user-location-fill',
      'ri:user-star-line',
      'ri:user-star-fill',
      'ri:user-settings-line',
      'ri:user-settings-fill',
      'ri:profile-line',
      'ri:profile-fill',
      'ri:id-card-line',
      'ri:id-card-fill',
      'ri:passport-line',
      'ri:passport-fill',
      'ri:stamp-line',
      'ri:stamp-fill',
      'ri:verified-badge-line',
      'ri:verified-badge-fill',
      'ri:shirt-line',
      'ri:shirt-fill',
      'ri:t-shirt-line',
      'ri:t-shirt-fill',
      'ri:dress-line',
      'ri:dress-fill',
      'ri:glasses-line',
      'ri:glasses-fill',
      'ri:sunglasses-line',
      'ri:sunglasses-fill',
      'ri:high-heels-line',
      'ri:high-heels-fill',
      'ri:pijin-line',
      'ri:pijin-fill',
      'ri: umbrella-line',
      'ri: umbrella-fill',
      'ri:patch-line',
      'ri:patch-fill',
      'ri:bear-smile-line',
      'ri:bear-smile-fill',
      'ri:emoji-sticker-line',
      'ri:emoji-sticker-fill',
      'ri:expression-line',
      'ri:expression-fill',
      'ri:emotion-line',
      'ri:emotion-fill',
      'ri:emotion-2-line',
      'ri:emotion-2-fill',
      'ri:flash-line',
      'ri:flash-fill',
      'ri:lightbulb-line',
      'ri:lightbulb-fill',
      'ri:spiral-ball-line',
      'ri:spiral-ball-fill',
      'ri:timer-line',
      'ri:timer-fill',
      'ri:countdown-line',
      'ri:countdown-fill',
      'ri:hourglass-line',
      'ri:hourglass-fill',
      'ri:history-line',
      'ri:history-fill',
      'ri:time-line',
      'ri:time-fill',
      'ri:clock-line',
      'ri:clock-fill',
      'ri:clockwise-line',
      'ri:clockwise-fill',
      'ri:anticlockwise-line',
      'ri:anticlockwise-fill',
      'ri:translate',
      'ri:translate-2',
      'ri:translate-2-fill',
      'ri:a-b',
      'ri:english-input',
      'ri: input-method',
      'ri:typing',
      'ri:format',
      'ri:text',
      'ri:text-spacing',
      'ri:text-color',
      'ri:format-clear',
      'ri:format-align-left',
      'ri:format-align-center',
      'ri:format-align-right',
      'ri:format-align-justify',
      'ri:format-bold',
      'ri:format-italic',
      'ri:format-underline',
      'ri:format-strikethrough',
      'ri:overflow',
      'ri:code-view',
      'ri:code-s-line',
      'ri:code-s-fill',
      'ri:terminal-window-line',
      'ri:terminal-window-fill',
      'ri:bug-line',
      'ri:bug-fill',
      'ri:code-box-line',
      'ri:code-box-fill',
      'ri:code',
      'ri:code-fill',
      'ri:braces-line',
      'ri:braces-fill',
      'ri:git-branch-line',
      'ri:git-branch-fill',
      'ri:git-commit-line',
      'ri:git-commit-fill',
      'ri:git-merge-line',
      'ri:git-merge-fill',
      'ri:git-pull-request-line',
      'ri:git-pull-request-fill',
      'ri:git-repository-line',
      'ri:git-repository-fill',
      'ri:github-line',
      'ri:github-fill',
      'ri:gitlab-line',
      'ri:gitlab-fill',
      'ri:gitee-line',
      'ri:gitee-fill',
      'ri:drive-line',
      'ri:drive-fill'
    ]
  }

  // Element Plus 图标
  const elementPlusIcons = [
    'ep:add-location',
    'ep:aim',
    'ep:alarm-clock',
    'ep:apple',
    'ep:arrow-down',
    'ep:arrow-down-bold',
    'ep:arrow-left',
    'ep:arrow-left-bold',
    'ep:arrow-right',
    'ep:arrow-right-bold',
    'ep:arrow-up',
    'ep:arrow-up-bold',
    'ep:avatar',
    'ep:back',
    'ep:baseball',
    'ep:basketball',
    'ep:bell',
    'ep:bell-filled',
    'ep:birthday-cake',
    'ep:blank',
    'ep:blur-on',
    'ep:bottom',
    'ep:bottom-left',
    'ep:bottom-right',
    'ep:bowl',
    'ep:box',
    'ep:bread',
    'ep:briefcase',
    'ep:brush',
    'ep:brush-filled',
    'ep:burger',
    'ep:calendar',
    'ep:camera',
    'ep:camera-filled',
    'ep:caret-bottom',
    'ep:caret-left',
    'ep:caret-right',
    'ep:caret-top',
    'ep:cart',
    'ep:cart-empty',
    'ep:cat',
    'ep:cellphone',
    'ep:chat-bubble',
    'ep:chat-bubble-filled',
    'ep:chat-dot-square',
    'ep:chat-dot-square-filled',
    'ep:chat-line-round',
    'ep:chat-line-square',
    'ep:chat-round',
    'ep:chat-square',
    'ep:check',
    'ep:checkbox',
    'ep:checkbox-button',
    'ep:checkbox-button-checked',
    'ep:checkbox-checked',
    'ep:check',
    'ep:cherry',
    'ep:chicken',
    'ep:circle-check',
    'ep:circle-check-filled',
    'ep:circle-close',
    'ep:circle-close-filled',
    'ep:circle-plus',
    'ep:circle-plus-filled',
    'ep:citrus',
    'ep:close',
    'ep:close-bold',
    'ep:cloudy',
    'ep:coffee',
    'ep:coffee-cup',
    'ep:coin',
    'ep:cold',
    'ep:collection',
    'ep:collection-tag',
    'ep:comment',
    'ep:compass',
    'ep:connection',
    'ep:coordinate',
    'ep:crop',
    'ep:cross',
    'ep:cut',
    'ep:d-arrow-left',
    'ep:d-arrow-right',
    'ep:d-caret',
    'ep:data-analysis',
    'ep:data-board',
    'ep:data-line',
    'ep:data-settings',
    'ep:delete',
    'ep:delete-filled',
    'ep:delete-location',
    'ep:dessert',
    'ep:direction-left',
    'ep:direction-right',
    'ep:document',
    'ep:document-add',
    'ep:document-checked',
    'ep:document-copy',
    'ep:document-delete',
    'ep:document-remove',
    'ep:document',
    'ep:download',
    'ep:d-arrow-left',
    'ep:doughnut',
    'ep:down',
    'ep:download',
    'ep:drag',
    'ep:edit',
    'ep:edit-pen',
    'ep:eleme',
    'ep:eleme-filled',
    'ep:element-plus',
    'ep:elevator',
    'ep:expand',
    'ep:failed',
    'ep:far-away',
    'ep:fast-left',
    'ep:fast-right',
    'ep:film',
    'ep:filter',
    'ep:finished',
    'ep:first-aid-kit',
    'ep:flag',
    'ep:folder',
    'ep:folder-add',
    'ep:folder-checked',
    'ep:folder-delete',
    'ep:folder-opened',
    'ep:folder-remove',
    'ep:food',
    'ep:football',
    'ep:fork-spoon',
    'ep:free-breakfast',
    'ep:french-fries',
    'ep:fries',
    'ep:full-screen',
    'ep:gantt',
    'ep:ghost',
    'ep:gift',
    'ep:girl',
    'ep:globe',
    'ep:goods',
    'ep:goods-packed',
    'ep:grape',
    'ep:grid',
    'ep:group',
    'ep:guide',
    'ep:headset',
    'ep:help',
    'ep:help-filled',
    'ep:hide',
    'ep:histogram',
    'ep:home',
    'ep:hot',
    'ep:hotel',
    'ep:house',
    'ep:ice-cream',
    'ep:ice-cream-round',
    'ep:ice-cream-square',
    'ep:ice-drink',
    'ep:ice-tea',
    'ep:info-filled',
    'ep:iron',
    'ep:jar',
    'ep:key',
    'ep:kitchenware',
    'ep:lamp',
    'ep:lemon',
    'ep:letter',
    'ep:lightning',
    'ep:link',
    'ep:list',
    'ep:loading',
    'ep:location',
    'ep:location-filled',
    'ep:lock',
    'ep:lock-filled',
    'ep:lollipop',
    'ep:long-arrow-down',
    'ep:long-arrow-left',
    'ep:long-arrow-right',
    'ep:long-arrow-up',
    'ep:magic-stick',
    'ep:magnet',
    'ep:male',
    'ep:management',
    'ep:map-location',
    'ep:map',
    'ep:message',
    'ep:message-box',
    'ep:milk-tea',
    'ep:minus',
    'ep:money',
    'ep:monitor',
    'ep:moon',
    'ep:more',
    'ep:more-filled',
    'ep:mostly-cloudy',
    'ep:mouse',
    'ep:mouse-filled',
    'ep:mp橄',
    'ep:music',
    'ep:mute',
    'ep:mute-notification',
    'ep:navigation',
    'ep:news',
    'ep:notification',
    'ep:notebook',
    'ep:odometer',
    'ep:office-building',
    'ep:open',
    'ep:operation',
    'ep:opportunity',
    'ep:orange',
    'ep:paperclip',
    'ep:partly-cloudy',
    'ep:pear',
    'ep:pen',
    'ep:pencil',
    'ep:phone',
    'ep:phone-filled',
    'ep:picture',
    'ep:picture-filled',
    'ep:picture-outline',
    'ep:picture-outline-filled',
    'ep:pie-chart',
    'ep:pineapple',
    'ep:place',
    'ep:platform',
    'ep:plus',
    'ep:pointer',
    'ep:position',
    'ep:postcard',
    'ep:pouch',
    'ep:power',
    'ep:present',
    'ep:price-tag',
    'ep:printer',
    'ep:purchase-tag',
    'ep:qualifring',
    'ep:question-filled',
    'ep:rank',
    'ep:reading',
    'ep:reading-lamp',
    'ep:refresh',
    'ep:refresh-left',
    'ep:refresh-right',
    'ep:repeat',
    'ep:repeat-right',
    'ep:report',
    'ep:rice',
    'ep:right',
    'ep:ring',
    'ep:rocket',
    'ep:rotate-left',
    'ep:rotate-right',
    'ep:rss',
    'ep:sauce',
    'ep:school',
    'ep:scooter',
    'ep:search',
    'ep:select',
    'ep:sell',
    'ep:semi-select',
    'ep:set-up',
    'ep:service',
    'ep:setteiement',
    'ep:setting',
    'ep:share',
    'ep:sharp-bread',
    'ep:shirt',
    'ep:shop',
    'ep:shopping-bag',
    'ep:shopping-cart',
    'ep:shopping-cart-full',
    'ep:shrink',
    'ep:soccer',
    'ep:sort',
    'ep:sort-down',
    'ep:sort-up',
    'ep:stamp',
    'ep:star',
    'ep:star-filled',
    'ep:stopwatch',
    'ep:stopwatch-filled',
    'ep:sunny',
    'ep:suitcase',
    'ep:suitcase-rolling',
    'ep:sunrise',
    'ep:sunset',
    'ep:switch',
    'ep:switch-button',
    'ep:syrup',
    'ep:table',
    'ep:table-lamp',
    'ep:tag',
    'ep:tag-filled',
    'ep:tailor',
    'ep:taking-picture',
    'ep:target',
    'ep:tea',
    'ep:temperature',
    'ep:temperature-base',
    'ep:tent',
    'ep:timer',
    'ep:to-top',
    'ep:top',
    'ep:top-left',
    'ep:top-right',
    'ep:tickets',
    'ep:tickets-filled',
    'ep:tikets',
    'ep:toilet-paper',
    'ep:tomato',
    'ep:top-right',
    'ep:top-left',
    'ep:top',
    'ep:to-bottom',
    'ep:trophy',
    'ep:trophy-base',
    'ep:turn-off',
    'ep:umbrella',
    'ep:uncover',
    'ep:undo',
    'ep:unfold',
    'ep:unlock',
    'ep:upload',
    'ep:upload-filled',
    'ep:user',
    'ep:user-filled',
    'ep:van',
    'ep:video-camera',
    'ep:video-camera-filled',
    'ep:video-pause',
    'ep:video-play',
    'ep:view',
    'ep:wallet',
    'ep:wallet-filled',
    'ep:warning',
    'ep:warning-filled',
    'ep:watermelon',
    'ep:weather',
    'ep:wind',
    'ep:zoom-in',
    'ep:zoom-out'
  ]

  // 分类配置
  const categories = [
    { key: 'all', label: '全部' },
    { key: 'common', label: '常用' },
    { key: 'system', label: '系统' },
    { key: 'file', label: '文件' },
    { key: 'data', label: '数据' },
    { key: 'ui', label: '界面' },
    { key: 'business', label: '业务' },
    { key: 'media', label: '媒体' },
    { key: 'social', label: '社交' },
    { key: 'office', label: '办公' }
  ]

  // 常用图标列表（精选）
  const commonIcons = [
    'ri:home-line',
    'ri:dashboard-line',
    'ri:user-line',
    'ri:settings-line',
    'ri:menu-line',
    'ri:search-line',
    'ri:add-line',
    'ri:edit-line',
    'ri:delete-bin-line',
    'ri:check-line',
    'ri:close-line',
    'ri:bell-line',
    'ri:folder-line',
    'ri:file-line',
    'ri:download-line',
    'ri:upload-line',
    'ri:lock-line',
    'ri:unlock-line',
    'ri:eye-line',
    'ri:eye-off-line',
    'ri:link',
    'ri:share-line',
    'ri:calendar-line',
    'ri:time-line',
    'ri:image-line',
    'ri:camera-line',
    'ri:mail-line',
    'ri:message-line',
    'ri:phone-line',
    'ri:chat-1-line',
    'ri:send-line',
    'ri:filter-line',
    'ri:sort-line',
    'ri:bar-chart-line',
    'ri:pie-chart-line',
    'ri:line-chart-line',
    'ri:star-line',
    'ri:heart-line',
    'ri:thumb-up-line',
    'ri:flag-line',
    'ri:bookmark-line',
    'ri:tag-line',
    'ri:location-line',
    'ri:map-pin-line',
    'ri:shopping-cart-line',
    'ri:wallet-line',
    'ri:coin-line',
    'ri:trophy-line',
    'ri:medal-line',
    'ri:crown-line',
    'ri:building-line',
    'ri:store-line',
    'ri:global-line',
    'ri:compass-line',
    'ri:guide-line',
    'ri:flag-line'
  ]

  // 系统图标
  const systemIcons = [
    'ri:admin-line',
    'ri:shield-line',
    'ri:shield-check-line',
    'ri:lock-line',
    'ri:unlock-line',
    'ri:key-line',
    'ri:password-line',
    'ri:shield-user-line',
    'ri:shield-check-fill',
    'ri:lock-fill',
    'ri:key-fill',
    'ri:verified-badge-line',
    'ri:safety-certificate-line',
    'ri:fingerprint-line',
    'ri:scan-line',
    'ri:qr-code-line',
    'ri:barcode-line',
    'ri:ban-line',
    'ri:forbid-line',
    'ri:prohibited-line'
  ]

  // 文件图标
  const fileIcons = [
    'ri:file-line',
    'ri:file-fill',
    'ri:file-list-line',
    'ri:file-list-2-line',
    'ri:file-3-line',
    'ri:file-4-line',
    'ri:file-text-line',
    'ri:file-pdf-line',
    'ri:file-word-line',
    'ri:file-excel-line',
    'ri:file-ppt-line',
    'ri:file-zip-line',
    'ri:file-image-line',
    'ri:file-music-line',
    'ri:file-video-line',
    'ri:file-gif-line',
    'ri:folder-line',
    'ri:folder-fill',
    'ri:folder-2-line',
    'ri:folder-3-line',
    'ri:folder-4-line',
    'ri:folder-5-line',
    'ri:folder-open-line',
    'ri:folder-add-line',
    'ri:folder-reduce-line',
    'ri:archive-line',
    'ri:hard-drive-2-line',
    'ri:hdd-line',
    'ri:sd-card-line',
    'ri:usb-line',
    'ri:drive-line',
    'ri:cloud-line',
    'ri:cloud-off-line'
  ]

  // 数据图标
  const dataIcons = [
    'ri:database-line',
    'ri:database-fill',
    'ri:database-2-line',
    'ri:server-line',
    'ri:server-fill',
    'ri:hdd-line',
    'ri:hdd-fill',
    'ri:cloud-line',
    'ri:cloud-fill',
    'ri:bar-chart-line',
    'ri:bar-chart-fill',
    'ri:bar-chart-2-line',
    'ri:bar-chart-2-fill',
    'ri:pie-chart-line',
    'ri:pie-chart-fill',
    'ri:pie-chart-2-line',
    'ri:pie-chart-2-fill',
    'ri:line-chart-line',
    'ri:line-chart-fill',
    'ri:line-chart-up-line',
    'ri:line-chart-down-line',
    'ri:growth-line',
    'ri:donut-chart-line',
    'ri:axis-chart-line',
    'ri:bubble-chart-line',
    'ri:git-branch-line',
    'ri:git-commit-line',
    'ri:git-merge-line',
    'ri:git-pull-request-line',
    'ri:git-repository-line',
    'ri:github-line',
    'ri:gitlab-line',
    'ri:code-line'
  ]

  // UI 图标
  const uiIcons = [
    'ri:layout-line',
    'ri:layout-fill',
    'ri:layout-2-line',
    'ri:layout-3-line',
    'ri:layout-4-line',
    'ri:layout-5-line',
    'ri:layout-6-line',
    'ri:grid-line',
    'ri:grid-fill',
    'ri:apps-line',
    'ri:apps-fill',
    'ri:menu-line',
    'ri:menu-fill',
    'ri:menu-2-line',
    'ri:menu-2-fill',
    'ri:menu-3-line',
    'ri:menu-3-fill',
    'ri:sidebar-line',
    'ri:sidebar-fill',
    'ri:navigation-line',
    'ri:guide-line',
    'ri:arrows-left-line',
    'ri:arrows-right-line',
    'ri:arrow-left-s-line',
    'ri:arrow-right-s-line',
    'ri:arrow-up-s-line',
    'ri:arrow-down-s-line',
    'ri:expand-line',
    'ri:shrink-line',
    'ri:full-screen-line',
    'ri:fullscreen-exit-line',
    'ri:add-circle-line',
    'ri:subtract-circle-line',
    'ri:close-circle-line',
    'ri:check-circle-line',
    'ri:information-line',
    'ri:question-line'
  ]

  // 业务图标
  const businessIcons = [
    'ri:building-line',
    'ri:building-fill',
    'ri:building-2-line',
    'ri:building-2-fill',
    'ri:store-line',
    'ri:store-fill',
    'ri:store-2-line',
    'ri:store-2-fill',
    'ri:shop-line',
    'ri:shop-fill',
    'ri:shopping-bag-line',
    'ri:shopping-bag-fill',
    'ri:shopping-cart-line',
    'ri:shopping-cart-fill',
    'ri:shopping-basket-line',
    'ri:shopping-basket-fill',
    'ri:trophy-line',
    'ri:trophy-fill',
    'ri:medal-line',
    'ri:medal-fill',
    'ri:crown-line',
    'ri:crown-fill',
    'ri:diamond-line',
    'ri:diamond-fill',
    'ri:coin-line',
    'ri:coin-fill',
    'ri:coins-line',
    'ri:coins-fill',
    'ri:wallet-line',
    'ri:wallet-fill',
    'ri:credit-card-line',
    'ri:credit-card-fill',
    'ri:money-dollar-circle-line',
    'ri:sale-line',
    'ri:coupon-line',
    'ri:price-tag-line',
    'ri:customer-service-line',
    'ri:headphone-line',
    'ri:service-line',
    'ri:store-2-fill'
  ]

  // 媒体图标
  const mediaIcons = [
    'ri:image-line',
    'ri:image-fill',
    'ri:image-2-line',
    'ri:image-2-fill',
    'ri:image-add-line',
    'ri:image-edit-line',
    'ri:gallery-line',
    'ri:gallery-fill',
    'ri:video-line',
    'ri:video-fill',
    'ri:video-add-line',
    'ri:video-edit-line',
    'ri:camera-line',
    'ri:camera-fill',
    'ri:camera-off-line',
    'ri:camera-off-fill',
    'ri:mic-line',
    'ri:mic-fill',
    'ri:mic-off-line',
    'ri:mic-off-fill',
    'ri:headphone-line',
    'ri:headphone-fill',
    'ri:music-line',
    'ri:music-fill',
    'ri:voiceprint-line',
    'ri:speaker-line',
    'ri:speaker-fill',
    'ri:volume-line',
    'ri:volume-fill',
    'ri:volume-mute-line',
    'ri:volume-mute-fill',
    'ri:cd-line',
    'ri:dvd-line',
    'ri:film-line',
    'ri:film-fill',
    'ri:clapperboard-line'
  ]

  // 社交图标
  const socialIcons = [
    'ri:user-line',
    'ri:user-fill',
    'ri:user-2-line',
    'ri:user-2-fill',
    'ri:user-3-line',
    'ri:user-3-fill',
    'ri:team-line',
    'ri:team-fill',
    'ri:group-line',
    'ri:group-fill',
    'ri:user-search-line',
    'ri:user-add-line',
    'ri:user-follow-line',
    'ri:user-forbid-line',
    'ri:user-location-line',
    'ri:user-settings-line',
    'ri:chat-1-line',
    'ri:chat-1-fill',
    'ri:chat-2-line',
    'ri:chat-2-fill',
    'ri:chat-3-line',
    'ri:chat-3-fill',
    'ri:chat-4-line',
    'ri:chat-4-fill',
    'ri:message-line',
    'ri:message-fill',
    'ri:message-2-line',
    'ri:message-2-fill',
    'ri:mail-line',
    'ri:mail-fill',
    'ri:mail-open-line',
    'ri:mail-open-fill',
    'ri:send-line',
    'ri:send-fill',
    'ri:heart-line',
    'ri:heart-fill',
    'ri:thumb-up-line',
    'ri:thumb-up-fill',
    'ri:share-line',
    'ri:share-fill',
    'ri:forward-line',
    'ri:reply-line',
    'ri:link',
    'ri:links-line'
  ]

  // 办公图标
  const officeIcons = [
    'ri:book-line',
    'ri:book-fill',
    'ri:book-open-line',
    'ri:book-open-fill',
    'ri:book-2-line',
    'ri:book-3-line',
    'ri:notebook-line',
    'ri:notebook-fill',
    'ri:newspaper-line',
    'ri:newspaper-fill',
    'ri:article-line',
    'ri:article-fill',
    'ri:file-text-line',
    'ri:file-text-fill',
    'ri:file-line',
    'ri:file-fill',
    'ri:file-list-line',
    'ri:file-list-2-line',
    'ri:stamp-line',
    'ri:stamp-fill',
    'ri:pencil-line',
    'ri:pencil-fill',
    'ri:edit-line',
    'ri:edit-fill',
    'ri:quill-pen-line',
    'ri:ink-bottle-line',
    'ri:ballpen-line',
    'ri:brush-line',
    'ri:scissors-cut-line',
    'ri:scissors-line',
    'ri:paste-line',
    'ri:clipboard-line',
    'ri:clipboard-fill',
    'ri:sticky-note-line',
    'ri:sticky-note-fill',
    'ri:calendar-line',
    'ri:calendar-fill',
    'ri:calendar-todo-line',
    'ri:task-line',
    'ri:briefcase-line'
  ]

  // 根据分类获取图标列表
  const getCategoryIcons = (category: string): string[] => {
    const allIcons = [...iconSets.remix]
    switch (category) {
      case 'common':
        return commonIcons
      case 'system':
        return systemIcons
      case 'file':
        return fileIcons
      case 'data':
        return dataIcons
      case 'ui':
        return uiIcons
      case 'business':
        return businessIcons
      case 'media':
        return mediaIcons
      case 'social':
        return socialIcons
      case 'office':
        return officeIcons
      default:
        return allIcons
    }
  }

  // 过滤后的图标列表
  const filteredIcons = computed(() => {
    let icons = getCategoryIcons(activeCategory.value)

    if (searchText.value.trim()) {
      const search = searchText.value.toLowerCase()
      icons = icons.filter((icon) => {
        const name = icon.split(':')[1]?.toLowerCase() || ''
        return name.includes(search)
      })
    }

    return icons
  })

  // 防抖处理搜索
  let searchTimer: ReturnType<typeof setTimeout> | null = null
  const handleSearch = () => {
    if (searchTimer) clearTimeout(searchTimer)
    searchTimer = setTimeout(() => {
      // 搜索逻辑已在 computed 中处理
    }, 200)
  }

  // 选择图标
  const handleSelect = (icon: string) => {
    tempSelected.value = icon
    emit('update:modelValue', icon)
  }

  // 清空选择
  const handleClear = () => {
    tempSelected.value = ''
    emit('update:modelValue', '')
    dialogVisible.value = false
  }

  // 确认选择
  const handleConfirm = () => {
    dialogVisible.value = false
  }

  // 监听弹窗打开
  watch(dialogVisible, (val) => {
    if (val) {
      tempSelected.value = props.modelValue || ''
      searchText.value = ''
      activeCategory.value = 'all'
    }
  })
</script>

<style scoped lang="scss">
  .art-icon-picker {
    width: 100%;
  }

  .icon-preview {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    border: 1px solid var(--el-border-color);
    border-radius: var(--el-border-radius-base);
    cursor: pointer;
    transition: all 0.3s;
    background: var(--el-fill-color-light);
    height: 36px;
    box-sizing: border-box;

    &:hover {
      border-color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
    }

    .preview-icon {
      font-size: 18px;
      color: var(--el-text-color-regular);
    }

    .placeholder-text {
      color: var(--el-text-color-placeholder);
      font-size: 14px;
    }

    .edit-icon {
      margin-left: auto;
      color: var(--el-text-color-placeholder);
      font-size: 14px;
    }
  }

  :deep(.icon-picker-dialog) {
    .picker-container {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-height: 60vh;
      overflow: hidden;

      .search-bar {
        flex-shrink: 0;
      }

      .category-tabs {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        flex-shrink: 0;
        padding-bottom: 12px;
        border-bottom: 1px solid var(--el-border-color-lighter);

        .category-tab {
          padding: 6px 14px;
          font-size: 13px;
          border-radius: var(--el-border-radius-base);
          cursor: pointer;
          transition: all 0.2s;
          color: var(--el-text-color-regular);
          background: var(--el-fill-color-light);

          &:hover {
            background: var(--el-color-primary-light-9);
            color: var(--el-color-primary);
          }

          &.active {
            background: var(--el-color-primary);
            color: #fff;
          }
        }
      }

      .icon-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(80px, 1fr));
        gap: 8px;
        overflow-y: auto;
        padding: 4px;
        flex: 1;
        max-height: calc(60vh - 180px);
        min-height: 200px;

        &::-webkit-scrollbar {
          width: 6px;
        }

        &::-webkit-scrollbar-thumb {
          background: var(--el-border-color);
          border-radius: 3px;

          &:hover {
            background: var(--el-border-color-dark);
          }
        }

        .icon-item {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          gap: 4px;
          padding: 10px 4px;
          border-radius: var(--el-border-radius-base);
          cursor: pointer;
          transition: all 0.2s;
          border: 1px solid transparent;

          &:hover {
            background: var(--el-color-primary-light-9);
            border-color: var(--el-color-primary-light-5);
          }

          &.selected {
            background: var(--el-color-primary-light-9);
            border-color: var(--el-color-primary);
          }

          .icon-display {
            font-size: 22px;
            color: var(--el-text-color-regular);
          }

          .icon-name {
            font-size: 11px;
            color: var(--el-text-color-secondary);
            max-width: 70px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
          }

          &.selected .icon-display {
            color: var(--el-color-primary);
          }
        }

        .empty-state {
          grid-column: 1 / -1;
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          gap: 12px;
          padding: 40px;
          color: var(--el-text-color-placeholder);
          font-size: 14px;
        }
      }

      .selected-preview {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 12px 16px;
        background: var(--el-fill-color-light);
        border-radius: var(--el-border-radius-base);
        border: 1px solid var(--el-border-color-lighter);
        flex-shrink: 0;

        .preview-label {
          font-size: 13px;
          color: var(--el-text-color-secondary);
        }

        .preview-icon {
          font-size: 20px;
          color: var(--el-color-primary);
        }

        .preview-name {
          font-size: 13px;
          color: var(--el-text-color-regular);
          font-family: monospace;
        }
      }
    }

    .dialog-footer {
      display: flex;
      justify-content: flex-end;
      gap: 12px;
    }
  }
</style>
