<template>
  <ElCard
    class="business-module-nav"
    :class="{ 'business-module-nav--compact': props.compact }"
    shadow="never"
  >
    <div class="business-module-nav__inner">
      <div class="business-module-nav__heading">
        <div class="business-module-nav__title">{{ navigation.title }}</div>
      </div>

      <nav class="business-module-nav__links" :aria-label="`${navigation.title}页内导航`">
        <button
          v-for="item in visibleItems"
          :key="item.path"
          type="button"
          class="business-module-nav__link"
          :class="{ 'is-active': route.path === item.path }"
          :aria-current="route.path === item.path ? 'page' : undefined"
          @click="goPage(item.path)"
        >
          <ArtSvgIcon :icon="item.icon" class="business-module-nav__icon" />
          <span>{{ item.label }}</span>
        </button>
      </nav>
    </div>
  </ElCard>
</template>

<script setup lang="ts">
  import type { AppRouteRecord } from '@/types/router'
  import { BUSINESS_MODULE_NAVIGATION, type BusinessModuleKey } from '@/config/businessNavigation'
  import { useMenuStore } from '@/store/modules/menu'

  const props = withDefaults(
    defineProps<{
      moduleKey: BusinessModuleKey
      compact?: boolean
    }>(),
    {
      compact: false
    }
  )

  const route = useRoute()
  const router = useRouter()
  const { routeList } = storeToRefs(useMenuStore())

  const navigation = computed(() => BUSINESS_MODULE_NAVIGATION[props.moduleKey])

  const hasExactAuthorizedPath = (routes: AppRouteRecord[], targetPath: string): boolean =>
    routes.some(
      (item) =>
        item.path === targetPath ||
        (item.children?.length ? hasExactAuthorizedPath(item.children, targetPath) : false)
    )

  const visibleItems = computed(() => {
    // 路由初始化完成前不应渲染业务页；保留回退可避免热更新瞬间空白。
    if (!routeList.value.length) return navigation.value.items
    return navigation.value.items.filter((item) =>
      hasExactAuthorizedPath(routeList.value, item.path)
    )
  })

  const goPage = (path: string): void => {
    if (route.path === path) return
    // 不透传来源页 query：不同工作区的状态编号和筛选字段含义不同。
    void router.push(path)
  }
</script>

<style scoped>
  .business-module-nav {
    flex: 0 0 auto;
    margin-bottom: 12px;
    border-color: var(--el-border-color-lighter);
  }

  .business-module-nav :deep(.el-card__body) {
    padding: 10px 14px;
  }

  .business-module-nav__inner {
    display: flex;
    gap: 18px;
    align-items: center;
    justify-content: flex-start;
  }

  .business-module-nav__heading {
    flex: 0 0 auto;
    min-width: 92px;
  }

  .business-module-nav__title {
    font-size: 16px;
    font-weight: 650;
    line-height: 24px;
    color: var(--el-text-color-primary);
  }

  .business-module-nav__links {
    display: inline-flex;
    flex: 0 1 auto;
    flex-wrap: wrap;
    gap: 2px;
    justify-content: flex-end;
    min-width: 0;
    /* 标题居左、导航项贴右缘：中间留白由 margin-left:auto 吃掉 */
    margin-left: auto;
  }

  .business-module-nav__link {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    height: 32px;
    padding: 0 11px;
    font: inherit;
    font-size: 13px;
    color: var(--el-text-color-regular);
    cursor: pointer;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 6px;
    transition:
      color 0.18s ease,
      background-color 0.18s ease,
      box-shadow 0.18s ease;
  }

  .business-module-nav__link:hover {
    color: var(--el-color-primary);
    background: var(--el-fill-color-light);
  }

  .business-module-nav__link.is-active {
    font-weight: 600;
    color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
    border-color: var(--el-color-primary-light-7);
    box-shadow: none;
  }

  .business-module-nav__icon {
    font-size: 16px;
  }

  /* compact 只减少纵向高度，不再把链接区拉成贯穿整页的色块。 */
  .business-module-nav--compact :deep(.el-card__body) {
    padding: 10px 14px;
  }

  .business-module-nav--compact .business-module-nav__inner {
    gap: 14px;
  }

  .business-module-nav--compact .business-module-nav__heading {
    min-width: 104px;
  }

  .business-module-nav--compact .business-module-nav__links {
    flex: 0 1 auto;
    flex-wrap: nowrap;
    justify-content: flex-start;
    min-width: 0;
    overflow-x: auto;
    scrollbar-width: thin;
  }

  .business-module-nav--compact .business-module-nav__link {
    flex: 0 0 auto;
    height: 30px;
    padding: 0 10px;
    font-size: 12px;
  }

  @media (width <= 900px) {
    .business-module-nav__inner {
      flex-direction: column;
      gap: 10px;
      align-items: flex-start;
    }

    .business-module-nav__links {
      justify-content: flex-start;
      width: 100%;
      /* 纵排时不再贴右，恢复自然左对齐 */
      margin-left: 0;
    }

    .business-module-nav--compact .business-module-nav__inner {
      flex-direction: row;
      gap: 12px;
      align-items: center;
    }

    .business-module-nav--compact .business-module-nav__links {
      width: auto;
    }
  }
</style>
