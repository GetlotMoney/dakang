# ArtFireworksEffect - 烟花效果

Canvas 实现的烟花粒子动画系统，支持键盘快捷键 `Ctrl+Shift+P` 触发，或通过 `mittBus` 事件触发。

## 基本信息

- **组件名称**: ArtFireworksEffect
- **组件路径**: `@/components/core/layouts/art-fireworks-effect/index.vue`

## 功能特性

1. **烟花动画** - Canvas 绘制的烟花效果
2. **自定义图片** - 支持自定义烟花图片
3. **全局挂载** - 全屏覆盖展示

## 触发方式

```ts
import { mittBus } from '@/utils/sys'

// 键盘快捷键触发
// Ctrl+Shift+P (Windows) / Cmd+Shift+P (Mac)

// 编程触发
mittBus.emit('triggerFireworks')
```

## 特性

- 对象池模式（预创建600个粒子，避免 GC）
- 支持矩形/圆形/三角形/椭圆/图片粒子
- 13种颜色配置
- 物理模拟：重力、速度衰减、透明度淡出
- `FireworkSystem` 类管理动画生命周期

## 配置说明

节日烟花配置位于 `src/config/modules/festival.ts`：

```typescript
export const festivalConfigList: FestivalConfig[] = [
  {
    date: '2024-12-31',
    name: '跨年夜',
    image: '', // 可选的自定义烟花图片
    count: 3 // 烟花播放次数
    // ...
  }
]
```

## 典型应用场景

- 节日庆祝
- 新年烟花
- 版本发布庆祝
