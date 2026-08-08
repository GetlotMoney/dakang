<script setup lang="ts">
/**
 * 三照签收单个照片位（D04）：仅调用 uni.chooseImage 记录本地临时路径；
 * Mock 构建最多到 mock-recorded，不宣称云端上传成功；
 * delivery 接真构建由页面传入 recordedTag（提交时统一上传为受控媒体）。
 */
withDefaults(
  defineProps<{
    label: string
    modelValue: string
    /** 提交前置校验时高亮缺失位（D04：三照缺一不可）。 */
    missing?: boolean
    /** 已选照片的状态标签文案；缺省为「未上传」（mock 回落态，页面通常各自传入）。 */
    recordedTag?: string
  }>(),
  { recordedTag: '未上传' },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

function firstTempPath(paths: string | string[]): string {
  return Array.isArray(paths) ? (paths[0] ?? '') : paths
}

function choosePhoto() {
  uni.chooseImage({
    count: 1,
    success: (res) => {
      const path = firstTempPath(res.tempFilePaths)
      if (path) {
        emit('update:modelValue', path)
      }
    },
    fail: () => {
      // 取消选择或授权失败：停留当前状态，不伪造已选结果。
    },
  })
}

function removePhoto() {
  emit('update:modelValue', '')
}
</script>

<template>
  <view class="photo-slot" :class="{ 'photo-slot-missing': missing && !modelValue }">
    <view class="photo-slot-header">
      <view class="photo-slot-label">
        {{ label }}
      </view>
      <wd-tag v-if="modelValue" type="warning" plain>
        {{ recordedTag }}
      </wd-tag>
      <wd-tag v-else-if="missing" type="danger" plain>
        缺少照片
      </wd-tag>
    </view>
    <view v-if="modelValue" class="photo-slot-body">
      <image :src="modelValue" mode="aspectFill" class="photo-slot-image" />
      <view class="photo-slot-actions">
        <wd-button size="small" plain icon="camera" @click="choosePhoto">
          重选
        </wd-button>
        <wd-button size="small" plain type="error" icon="close" @click="removePhoto">
          删除
        </wd-button>
      </view>
    </view>
    <view v-else class="photo-slot-picker" @click="choosePhoto">
      <wd-icon name="camera" size="26px" color="#8a8f99" />
      <view class="muted-text">
        选择本地照片
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.photo-slot {
  padding: 12px;
  border: 1px solid #e6e8eb;
  border-radius: 8px;
  background: #fff;

  & + & {
    margin-top: 12px;
  }
}

.photo-slot-missing {
  border-color: var(--app-color-danger);
}

.photo-slot-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.photo-slot-label {
  font-size: 15px;
  font-weight: 600;
}

.photo-slot-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.photo-slot-image {
  width: 96px;
  height: 96px;
  border-radius: 6px;
}

.photo-slot-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.photo-slot-picker {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 96px;
  margin-top: 10px;
  border: 1px dashed #c9ced6;
  border-radius: 6px;
}
</style>
