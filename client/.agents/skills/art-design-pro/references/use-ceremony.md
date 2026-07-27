# useCeremony - 节日庆祝管理 Hook

提供节日烟花效果和祝福文本展示功能，为系统增添节日氛围。

## 基本信息

- **Hook 名称**: useCeremony
- **文件路径**: `@/hooks/core/useCeremony.ts`

## 功能特性

1. **节日检测** - 自动匹配当前日期与节日配置列表
2. **烟花动画** - 播放节日烟花特效，支持自定义图片和触发次数
3. **祝福文本** - 烟花结束后显示节日祝福文本
4. **状态管理** - 记录烟花播放状态，避免重复播放
5. **清理机制** - 提供清理方法，支持手动停止和重置

## 基本用法

```typescript
import { useCeremony } from '@/hooks/core/useCeremony'

const { openFestival, cleanup, currentFestivalData } = useCeremony()
```

## API

### 返回值

| 属性/方法              | 说明             |
| ---------------------- | ---------------- |
| openFestival           | 开启节日庆祝     |
| cleanup                | 清理烟花效果     |
| holidayFireworksLoaded | 烟花是否已播放过 |
| currentFestivalData    | 当前节日数据     |
| isShowFireworks        | 是否显示烟花     |

### 节日配置类型

```typescript
interface FestivalConfig {
  date: string // 节日日期 'YYYY-MM-DD'
  endDate?: string // 结束日期（跨日期节日）
  name: string // 节日名称
  image?: string // 烟花图片
  count?: number // 烟花播放次数
  scrollText?: string // 祝福文本
}
```

## 节日配置

节日配置位于 `src/config/modules/festival.ts`，可配置：

```typescript
// 单日节日
{
  date: '2024-12-25',
  name: '圣诞节',
  image: christmasImage,
  count: 3,
  scrollText: 'Merry Christmas!'
}

// 跨日期节日
{
  date: '2025-11-07',
  endDate: '2025-11-10',
  name: 'v3.0 发布',
  image: '',
  count: 5,
  scrollText: '系统 v3.0 正式发布！'
}
```

## 使用示例

### 触发节日庆祝

```typescript
const { openFestival, cleanup, currentFestivalData, isShowFireworks } = useCeremony()

// 开启节日庆祝
onMounted(() => {
  if (currentFestivalData.value && isShowFireworks.value) {
    openFestival()
  }
})

// 清理
onUnmounted(() => {
  cleanup()
})
```

### 手动触发烟花

```typescript
import { mittBus } from '@/utils/sys'
import { useCeremony } from '@/hooks/core/useCeremony'

const { currentFestivalData } = useCeremony()

// 手动触发单个烟花
const handleSingleFireworks = () => {
  mittBus.emit('triggerFireworks')
}

// 手动触发带图片的烟花
const handleImageFireworks = (imageUrl: string) => {
  mittBus.emit('triggerFireworks', imageUrl)
}
```

## 典型应用场景

- 新年庆祝
- 重大版本发布庆祝
- 节日氛围营造
- 特殊活动提示
