# ArtFestivalTextScroll - 节日滚动文字

`ArtTextScroll` 的封装，专门用于节日/庆典滚动通知。

## 基本信息

- **组件名称**: ArtFestivalTextScroll
- **组件路径**: `@/components/core/text-effect/art-festival-text-scroll/index.vue`

## 功能特性

1. **节日祝福** - 专门用于节日祝福文本展示
2. **自动检测** - 自动检测当前是否为节日
3. **文字滚动** - 支持左右滚动效果
4. **可关闭** - 支持用户关闭显示

## 基本用法

```vue
<ArtFestivalTextScroll />
```

自动根据日期显示节日祝福文字，支持手动关闭。

## 配置说明

节日文本内容在 `src/config/modules/festival.ts` 中配置：

```typescript
// festival.ts
export const festivalConfigList: FestivalConfig[] = [
  {
    date: '2024-12-31',
    name: '跨年夜',
    scrollText: '🎉 新年快乐！祝您新的一年万事如意！'
    // ...
  }
]
```

## 典型应用场景

- 新年祝福
- 节日庆祝提示
- 版本发布公告
