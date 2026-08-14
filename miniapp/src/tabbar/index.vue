<script setup lang="ts">
import { onMounted } from 'vue'
import { tabbarItems } from './config'
import { syncTabbarByCurrentPage, tabbarStore } from './store'

onMounted(() => {
  // #ifndef MP-WEIXIN
  uni.hideTabBar()
  // #endif
})

function handleTap(name: string) {
  const target = tabbarItems.find(item => item.name === name)
  if (!target) {
    return
  }
  // 防重守卫必须对比真实页面路径：高亮状态在点击瞬间先行改写，
  // 用 store 值判断会恒等提前返回，导致 switchTab 永不执行（点击 Tab 无法跳转）。
  const rawPath = getCurrentPages().at(-1)?.route ?? ''
  const currentPath = rawPath.startsWith('/') ? rawPath : `/${rawPath}`
  if (currentPath === target.pagePath) {
    return
  }
  tabbarStore.current = target.name
  uni.switchTab({
    url: target.pagePath,
    // 切换失败时按真实页面回摆高亮，避免高亮与页面脱节。
    fail: () => syncTabbarByCurrentPage(),
  })
}
</script>

<template>
  <!-- 「水滴浮标」Tabbar：当前项图标坐进一枚上尖下圆的品牌蓝水滴，从横栏浮起，
       切页时水滴回弹落位、基座漾开一圈涟漪——水站的"水"由形状与动效表达，
       不依赖渐变（色值闸判据二）与新色值（全部取自既有 token）。
       page-shell 已为固定 Tabbar 预留底部高度，此处不再做占位。 -->
  <view class="dk-tabbar">
    <view
      v-for="item in tabbarItems"
      :key="item.name"
      class="dk-tabbar__item"
      :class="{ 'is-active': tabbarStore.current === item.name }"
      @tap="handleTap(item.name)"
    >
      <view class="dk-tabbar__bubble">
        <!-- 涟漪只在活跃项挂载：key 绑当前项，切页即重挂重播一次 -->
        <view
          v-if="tabbarStore.current === item.name"
          :key="`ripple-${item.name}`"
          class="dk-tabbar__ripple"
        />
        <view class="dk-tabbar__drop">
          <wd-icon :name="item.icon" />
        </view>
      </view>
      <text class="dk-tabbar__label">
        {{ item.text }}
      </text>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.dk-tabbar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 500;
  display: flex;
  align-items: stretch;
  height: 100rpx; // 内容区高度与 page-shell 的 50px 预留同源，改一处必须同改另一处
  padding-bottom: env(safe-area-inset-bottom, 0px);
  background: var(--app-bg-card);
  border-top: 1rpx solid var(--line-2);
  box-shadow: var(--sh-bar);
  box-sizing: content-box; // safe-area 垫在内容区之下，100rpx 恒为可视高度
}

.dk-tabbar__item {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  padding-bottom: 10rpx;
}

.dk-tabbar__bubble {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 68rpx;
  height: 68rpx;
}

/* 水滴 = 容器(管上浮，不旋转) + ::before 形状层(管旋转出上尖下圆)。
   旋转只发生在形状层，图标永远摆正——此前把旋转压在容器上、靠 custom-class
   反向转回图标，wot 的 custom-class 在本编译链路未落到节点，图标就歪了。 */
.dk-tabbar__drop {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64rpx;
  height: 64rpx;
  transition: transform 0.28s cubic-bezier(0.34, 1.4, 0.64, 1);
}

.dk-tabbar__drop::before {
  position: absolute;
  inset: 0;
  content: '';
  background: transparent;
  border-radius: 0 50% 50% 50%;
  transform: rotate(45deg);
  transition:
    background-color 0.28s ease,
    box-shadow 0.28s ease;
}

.dk-tabbar__item :deep(.wd-icon) {
  position: relative;
  font-size: 44rpx;
  color: var(--app-text-tertiary);
  transition:
    color 0.28s ease,
    font-size 0.28s ease;
}

.dk-tabbar__label {
  font-size: 26rpx;
  line-height: 34rpx;
  color: var(--app-text-tertiary);
  transition:
    color 0.28s ease,
    font-weight 0.28s ease;
}

.dk-tabbar__item.is-active .dk-tabbar__drop {
  transform: translateY(-16rpx);
}

.dk-tabbar__item.is-active .dk-tabbar__drop::before {
  background: var(--app-color-primary-soft); // 实色浅蓝水滴底，图标保持品牌蓝
  box-shadow: var(--sh-drop);
}

.dk-tabbar__item.is-active :deep(.wd-icon) {
  font-size: 40rpx;
  color: var(--app-color-primary);
}

.dk-tabbar__item.is-active .dk-tabbar__label {
  font-weight: 600;
  color: var(--app-color-primary);
}

/* 落位涟漪：一圈品牌蓝细环自水滴基座扩散淡出，只播一次 */
.dk-tabbar__ripple {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 64rpx;
  height: 64rpx;
  border: 2rpx solid var(--line-primary);
  border-radius: 50%;
  transform: translate(-50%, -50%);
  animation: dk-ripple 0.55s ease-out forwards;
  pointer-events: none;
}

@keyframes dk-ripple {
  0% {
    opacity: 1;
    transform: translate(-50%, -50%) scale(0.6);
  }

  100% {
    opacity: 0;
    transform: translate(-50%, -50%) scale(1.7);
  }
}
</style>
