# ArtCountTo - 数字滚动动画

基于 `@vueuse/core` 的 `useTransition` 实现数字滚动动画，支持多种缓动函数、格式化，千分位分隔。

## 基本信息

- **组件名称**: ArtCountTo
- **组件路径**: `@/components/core/text-effect/art-count-to/index.vue`
- **使用示例**: `src/views/widgets/count-to/index.vue`

## 功能特性

1. **多种缓动动画** - 支持 linear、easeOutCubic、easeOutExpo 等多种缓动函数
2. **前缀后缀** - 支持自定义前缀（如 ¥）和后缀（如元）
3. **小数点控制** - 支持指定小数位数和千分位分隔符
4. **完整事件** - 提供 started、finished、paused、reset 等事件回调
5. **方法控制** - 提供 start、pause、reset 等方法手动控制动画

## API

### Props

| 属性        | 类型                             | 默认值          | 说明                     |
| ----------- | -------------------------------- | --------------- | ------------------------ |
| `target`    | `number`                         | `0`             | 目标值                   |
| `duration`  | `number`                         | `2000`          | 动画持续时间（毫秒）     |
| `autoStart` | `boolean`                        | `true`          | 是否自动开始             |
| `decimals`  | `number`                         | `0`             | 小数位数                 |
| `decimal`   | `string`                         | `'.'`           | 小数点符号               |
| `separator` | `string`                         | `''`            | 千分位分隔符（如 `','`） |
| `prefix`    | `string`                         | `''`            | 前缀文本                 |
| `suffix`    | `string`                         | `''`            | 后缀文本                 |
| `easing`    | `keyof typeof TransitionPresets` | `'easeOutExpo'` | 缓动函数名               |
| `disabled`  | `boolean`                        | `false`         | 是否禁用动画             |

### TransitionPresets 可选值

`'linear'`, `'easeInSine'`, `'easeOutSine'`, `'easeInOutSine'`, `'easeInQuad'`, `'easeOutQuad'`, `'easeInOutQuad'`, `'easeInCubic'`, `'easeOutCubic'`, `'easeInOutCubic'`, `'easeInQuart'`, `'easeOutQuart'`, `'easeInOutQuart'`, `'easeInQuint'`, `'easeOutQuint'`, `'easeInOutQuint'`, `'easeInExpo'`, `'easeOutExpo'`, `'easeInOutExpo'`, `'easeInCirc'`, `'easeOutCirc'`, `'easeInOutCirc'`, `'easeInBack'`, `'easeOutBack'`, `'easeInOutBack'`, `'easeInElastic'`, `'easeOutElastic'`, `'easeInOutElastic'`, `'easeInBounce'`, `'easeOutBounce'`, `'easeInOutBounce'`

### Events

| 事件       | 参数            | 说明     |
| ---------- | --------------- | -------- |
| `started`  | `value: number` | 动画开始 |
| `finished` | `value: number` | 动画结束 |
| `paused`   | `value: number` | 动画暂停 |
| `reset`    | —               | 重置     |

### Methods (defineExpose)

```ts
const countRef = ref()
countRef.value.start()           // 开始动画
countRef.value.pause()           // 暂停
countRef.value.reset(newTarget?) // 重置
countRef.value.stop()            // 停止
countRef.value.setTarget(n)      // 设置新目标值
countRef.value.isRunning         // 当前是否运行中
countRef.value.isPaused          // 当前是否暂停
countRef.value.currentValue      // 当前数值
countRef.value.targetValue       // 目标数值
countRef.value.progress          // 进度 0-1
```

## 基本用法

```vue
<!-- 基础 -->
<ArtCountTo :target="1000" />

<!-- 带格式化：千分位 + 前缀 + 后缀 -->
<ArtCountTo :target="1234567" :decimals="2" separator="," prefix="$" suffix="+" />

<!-- 引用方式 -->
<ArtCountTo ref="countRef" :target="999" :duration="3000" easing="easeOutExpo" />

<!-- 卡片展示 -->
<div class="flex gap-4">
  <ArtCountTo :target="256" suffix="+" class="text-4xl font-bold" />
  <ArtCountTo :target="98.6" :decimals="1" suffix="%" class="text-4xl font-bold" />
</div>
```

## 完整示例

```vue
<template>
  <!-- 带前缀后缀 -->
  <ArtCountTo :target="20000" prefix="¥" suffix="元" :decimals="2" />

  <!-- 小数点和分隔符 -->
  <ArtCountTo :target="2023.45" :duration="3000" :decimals="2" separator="," />

  <!-- 控制按钮 -->
  <ArtCountTo
    ref="countToRef"
    :target="controlTarget"
    :duration="2000"
    @started="handleAnimationStarted"
    @finished="handleAnimationFinished"
  />

  <ElButton @click="startCount">开始</ElButton>
  <ElButton @click="pauseCount">暂停</ElButton>
  <ElButton @click="resetCount">重置</ElButton>
</template>

<script setup lang="ts">
  const countToRef = ref()
  const controlTarget = ref(0)

  const startCount = () => {
    countToRef.value?.start(5000)
  }

  const pauseCount = () => {
    countToRef.value?.pause()
  }

  const resetCount = () => {
    countToRef.value?.reset()
  }

  const handleAnimationStarted = (value: number) => {
    console.log('动画开始，目标值:', value)
  }

  const handleAnimationFinished = (value: number) => {
    console.log('动画完成，最终值:', value)
  }
</script>
```

## 典型应用场景

- 仪表盘数据统计
- 金额数字动画展示
- 进度指标数字变化
- 排行榜数字滚动
