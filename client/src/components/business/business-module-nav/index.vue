<template>
  <ElCard class="business-module-nav" shadow="never">
    <div class="business-module-nav__inner">
      <div class="business-module-nav__heading">
        <div class="business-module-nav__title">{{ navigation.title }}</div>
        <div class="business-module-nav__description">{{ navigation.description }}</div>
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

  const props = defineProps<{
    moduleKey: BusinessModuleKey
  }>()

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
    padding: 14px 16px;
  }

  .business-module-nav__inner {
    display: flex;
    gap: 20px;
    align-items: center;
    justify-content: space-between;
  }

  .business-module-nav__heading {
    min-width: 220px;
  }

  .business-module-nav__title {
    font-size: 16px;
    font-weight: 650;
    line-height: 24px;
    color: var(--el-text-color-primary);
  }

  .business-module-nav__description {
    margin-top: 2px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .business-module-nav__links {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    justify-content: flex-end;
    padding: 4px;
    background: var(--el-fill-color-light);
    border-radius: 9px;
  }

  .business-module-nav__link {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    height: 34px;
    padding: 0 13px;
    font: inherit;
    font-size: 13px;
    color: var(--el-text-color-regular);
    cursor: pointer;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 7px;
    transition:
      color 0.18s ease,
      background-color 0.18s ease,
      box-shadow 0.18s ease;
  }

  .business-module-nav__link:hover {
    color: var(--el-color-primary);
    background: var(--el-bg-color);
  }

  .business-module-nav__link.is-active {
    font-weight: 600;
    color: var(--el-color-primary);
    background: var(--el-bg-color);
    border-color: var(--el-color-primary-light-7);
    box-shadow: var(--el-box-shadow-lighter);
  }

  .business-module-nav__icon {
    font-size: 16px;
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
    }
  }
</style>
