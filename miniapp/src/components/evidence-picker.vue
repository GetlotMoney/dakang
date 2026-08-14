<script setup lang="ts">
/**
 * 凭证选择（D03 举证 / D05 异常 / U09 申诉共用）：0~max 张本地图片。
 * 组件只做本地选图，上传动作由调用页在提交时执行。
 *
 * <p>说明文案原按业务域分流出两套（mock 说「不会上传」、real 说「真实上传至受控媒体」）。
 * mock 分支已随 2026-08-02 mock 基建退役而不可达，两个 real 域的文案又完全相同，
 * 分流因此退化成一句常量——2026-08-06 连同 runtime-notice 的 key 机制一并删除。</p>
 */
const props = withDefaults(
  defineProps<{
    modelValue: string[]
    max?: number
  }>(),
  { max: 3 },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string[]): void
}>()

function firstTempPath(paths: string | string[]): string {
  return Array.isArray(paths) ? (paths[0] ?? '') : paths
}

function addPhoto() {
  if (props.modelValue.length >= props.max) {
    return
  }
  uni.chooseImage({
    count: 1,
    success: (res) => {
      const path = firstTempPath(res.tempFilePaths)
      if (path) {
        emit('update:modelValue', [...props.modelValue, path])
      }
    },
    fail: () => {
      // 取消选择或授权失败：不追加占位数据。
    },
  })
}

function removePhoto(index: number) {
  emit('update:modelValue', props.modelValue.filter((_, current) => current !== index))
}
</script>

<template>
  <view class="evidence-picker">
    <view class="evidence-grid">
      <view v-for="(item, index) in modelValue" :key="`${index}-${item}`" class="evidence-item">
        <image :src="item" mode="aspectFill" class="evidence-image" />
        <view class="evidence-remove" @click="removePhoto(index)">
          <wd-icon name="close" size="12px" color="var(--app-text-inverse)" />
        </view>
      </view>
      <view v-if="modelValue.length < max" class="evidence-add" @click="addPhoto">
        <wd-icon name="camera" size="22px" color="var(--app-text-tertiary)" />
        <view class="muted-text">
          {{ modelValue.length }}/{{ max }}
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.evidence-picker {
  padding: 4px 0;
}

.evidence-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.evidence-item {
  position: relative;
  width: 76px;
  height: 76px;
}

.evidence-image {
  width: 76px;
  height: 76px;
  border-radius: 6px;
}

.evidence-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--mask-overlay);
}

.evidence-add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  width: 76px;
  height: 76px;
  border: 1px dashed var(--app-text-disabled);
  border-radius: 6px;
}
</style>
