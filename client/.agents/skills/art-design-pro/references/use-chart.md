# useChart - ECharts 图表管理 Hook

提供完整的 ECharts 图表生命周期管理和配置能力，简化图表开发流程。

## 基本信息

- **Hook 名称**: useChart, useChartComponent
- **文件路径**: `@/hooks/core/useChart.ts`

## 功能特性

1. **图表生命周期管理** - 自动处理初始化、更新、销毁
2. **主题自动适配** - 响应系统主题变化，自动更新图表样式
3. **响应式调整** - 监听窗口大小、菜单展开等变化
4. **空状态处理** - 优雅的空数据展示
5. **样式配置统一** - 提供坐标轴、图例、提示框等统一配置
6. **性能优化** - 防抖处理、样式缓存、requestAnimationFrame 优化

## 基本用法

```typescript
import { useChart } from '@/hooks/core/useChart'

const { chartRef, initChart, updateChart, getAxisLineStyle, getSplitLineStyle, getTooltipStyle } =
  useChart()

onMounted(() => {
  initChart({
    xAxis: { type: 'category', data: ['Mon', 'Tue', 'Wed'] },
    yAxis: { type: 'value' },
    series: [{ data: [120, 200, 150], type: 'bar' }]
  })
})
```

## API

### useChart 返回值

| 属性/方法          | 说明                   |
| ------------------ | ---------------------- |
| **核心**           |                        |
| chartRef           | 图表容器 DOM 引用      |
| initChart          | 初始化图表             |
| updateChart        | 更新图表               |
| handleResize       | 手动触发 resize        |
| destroyChart       | 销毁图表               |
| getChartInstance   | 获取图表实例           |
| **样式配置**       |                        |
| getAxisLineStyle   | 获取坐标轴线样式       |
| getSplitLineStyle  | 获取分割线样式         |
| getAxisLabelStyle  | 获取坐标轴标签样式     |
| getAxisTickStyle   | 获取刻度样式           |
| getAnimationConfig | 获取动画配置           |
| getTooltipStyle    | 获取提示框样式         |
| getLegendStyle     | 获取图例样式           |
| getGridWithLegend  | 获取带图例的 grid 配置 |
| useChartOps        | 获取图表默认配置       |

### useChartOptions 配置

```typescript
interface UseChartOptions {
  initOptions?: EChartsOption // 初始配置
  initDelay?: number // 初始化延迟(ms)
  threshold?: number // 可见性阈值
  autoTheme?: boolean // 是否自动响应主题
}
```

### useChartComponent 高级用法

```typescript
import { useChartComponent } from '@/hooks/core/useChart'

const chart = useChartComponent({
  props, // 组件 props
  generateOptions: () => ({
    xAxis: { type: 'category', data: ['Mon', 'Tue', 'Wed'] },
    series: [{ data: [120, 200, 150], type: 'bar' }]
  }),
  checkEmpty: () => data.value.length === 0,
  watchSources: [() => props.data],
  chartOptions: { initDelay: 300 }
})

// 返回值同 useChart，额外包含：
// - isEmpty: 计算属性，判断是否为空数据
// - updateChart: 更新图表
```

## 使用示例

### 基础折线图

```typescript
const { chartRef, initChart, isDark } = useChart()

onMounted(() => {
  initChart({
    xAxis: {
      type: 'category',
      data: ['周一', '周二', '周三', '周四', '周五'],
      ...getAxisLabelStyle()
    },
    yAxis: {
      type: 'value',
      ...getAxisLineStyle()
    },
    series: [
      {
        data: [120, 200, 150, 80, 70],
        type: 'line',
        smooth: true
      }
    ],
    tooltip: getTooltipStyle('axis'),
    grid: getGridWithLegend(false)
  })
})
```

### 带主题适配

```typescript
const { chartRef, initChart, updateChart } = useChart({ autoTheme: true })

const chartData = ref([120, 200, 150, 80, 70])

onMounted(() => {
  initChart(generateOptions())
})

// 主题变化时自动更新
watch(isDark, () => {
  updateChart(generateOptions())
})

const generateOptions = () => ({
  // 图表配置会自动适配主题
})
```

### 组合式样式配置

```typescript
const { getTooltipStyle, getLegendStyle, getGridWithLegend, useChartOps } = useChart()

const chartOps = useChartOps()
const tooltip = getTooltipStyle('axis')
const legend = getLegendStyle('bottom')
const grid = getGridWithLegend(true, 'bottom')

initChart({
  tooltip,
  legend,
  grid,
  series: [
    {
      data: [120, 200, 150],
      type: 'bar',
      itemStyle: { color: chartOps.colors[0] }
    }
  ]
})
```

## 典型应用场景

- 仪表盘图表展示
- 数据可视化
- 统计报表
- 实时数据监控
