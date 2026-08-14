<script setup lang="ts">
/**
 * 业务时间线：只画调用方给的节点。
 *
 * 不推导状态、不补造缺失节点、不把两份不同来源的证据拼成一条链——把"订单轨迹"和
 * "设备执行记录"接在一起看起来完整，实际上是在编造一条从未发生过的连续过程。
 */
export interface TimelineNode {
  /** 节点名。 */
  title: string
  /** 节点时间（已格式化，组件不做时间解析）。 */
  time?: string
  /** 说明文字。 */
  description?: string
  /** 操作方，如「前置仓」「配送员」。 */
  actor?: string
  /** 节点状态；不传则按已完成绘制。 */
  status?: 'finished' | 'process' | 'error'
}

defineProps<{
  nodes: TimelineNode[]
}>()
</script>

<template>
  <wd-steps :active="nodes.length" vertical dot>
    <wd-step
      v-for="(node, index) in nodes"
      :key="`${node.title}-${index}`"
      :status="node.status || 'finished'"
    >
      <template #title>
        <view class="timeline-title">
          <text class="timeline-title__name">
            {{ node.title }}
          </text>
          <text v-if="node.time" class="timeline-title__time num">
            {{ node.time }}
          </text>
        </view>
      </template>
      <template v-if="node.description || node.actor" #description>
        <text class="timeline-desc">
          {{ node.description }}<text v-if="node.actor">
            （{{ node.actor }}）
          </text>
        </text>
      </template>
    </wd-step>
  </wd-steps>
</template>

<style lang="scss" scoped>
.timeline-title {
  display: flex;
  gap: var(--sp-3);
  align-items: baseline;
  justify-content: space-between;

  &__name {
    font-size: var(--fs-body);
  }

  &__time {
    flex: none;
    color: var(--app-text-tertiary);
    font-size: var(--fs-note);
  }
}

.timeline-desc {
  color: var(--app-text-secondary);
  font-size: var(--fs-caption);
}
</style>
