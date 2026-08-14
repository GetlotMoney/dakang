<!--
  用户列表筛选：关键词模糊 与 用户ID精确 分成两个物理输入框。
  合成一个"智能框"看着省地方，但运营手里拿到一个确切编号时，模糊匹配会把同名或近号的另一个人
  一并捞进来；一个人一个编号，就该有一个只认编号的入口。

  手机号是客服最高频的定位键（来电时手上往往只有号码），必须保留独立输入框：
  列表列下发的是脱敏号，删掉这个框就只剩翻页比对后四位一条路。
  该条件在库里是等值匹配，所以必须收完整 11 位——收半截号码只会静默返回空表。
-->
<template>
  <ArtSearchBar
    ref="searchBarRef"
    v-model="formData"
    :items="formItems"
    auto-search
    default-expanded
    @reset="handleReset"
    @search="handleSearch"
  >
  </ArtSearchBar>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import { fetchDictOptions, toDictOptions } from '@/utils/dict'
  import { DictTypeEnum } from '@/constants/dict'
  import { USER_CAPABILITY } from '@/api/user'

  /** 表单内部形态：注册时间在控件里是一个区间数组，提交前才拆成起止两个参数 */
  interface SearchForm {
    userName?: string
    userPhone?: string
    id?: string
    disabledFlag?: number
    capability?: string
    registerRange?: string[]
  }

  /** 向父页面提交的查询条件（已拆区间） */
  interface UserSearchEmitParams {
    userName?: string
    userPhone?: string
    id?: string
    disabledFlag?: number
    capability?: string
    createTimeBegin?: string
    createTimeEnd?: string
  }

  interface Emits {
    (e: 'update:modelValue', value: SearchForm): void
    (e: 'search', params: UserSearchEmitParams): void
    (e: 'reset'): void
  }

  const props = defineProps<{ modelValue: SearchForm }>()
  const emit = defineEmits<Emits>()

  const searchBarRef = ref()
  const formData = computed({
    get: () => props.modelValue,
    set: (val) => emit('update:modelValue', val)
  })

  const disabledFlagOptions = ref<{ label: string; value: number }[]>([])
  onMounted(async () => {
    disabledFlagOptions.value = toDictOptions(await fetchDictOptions(DictTypeEnum.禁用状态))
  })

  const formItems = computed(() => [
    {
      label: '姓名',
      key: 'userName',
      type: 'input',
      placeholder: '支持模糊查找',
      clearable: true
    },
    {
      label: '手机号',
      key: 'userPhone',
      type: 'input',
      placeholder: '完整号码精确查找',
      clearable: true
    },
    {
      label: '用户ID',
      key: 'id',
      type: 'input',
      placeholder: '编号精确查找',
      clearable: true
    },
    // 下面三项一旦写了 props，根层的 placeholder / clearable 就不会再透传给控件，
    // 必须整体写进 props 里，否则会得到一个既没有占位提示也清不掉的下拉框。
    {
      label: '账号状态',
      key: 'disabledFlag',
      type: 'select',
      props: {
        placeholder: '全部状态',
        clearable: true,
        options: disabledFlagOptions.value
      }
    },
    {
      label: '能力',
      key: 'capability',
      type: 'select',
      props: {
        placeholder: '全部能力',
        clearable: true,
        options: [
          { label: '机主', value: USER_CAPABILITY.owner },
          { label: '配送员', value: USER_CAPABILITY.courier }
        ]
      }
    },
    {
      label: '注册时间',
      key: 'registerRange',
      type: 'datetime',
      span: 12,
      props: {
        style: { width: '100%' },
        type: 'datetimerange',
        clearable: true,
        rangeSeparator: '至',
        startPlaceholder: '开始时间',
        endPlaceholder: '结束时间',
        // 注册时间在库里是 14 位定长串，比较按字符逐位进行。若这里输出 'YYYY-MM-DD'，
        // 分隔符的字符序小于数字，上界会把当年记录整批排除且不报任何错——选了区间却返回空表，
        // 比没有这个筛选更难发现。故值格式与存储格式逐字一致。
        valueFormat: 'YYYYMMDDHHmmss',
        defaultTime: [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]
      }
    }
  ])

  function handleReset() {
    emit('reset')
  }

  async function handleSearch(params: SearchForm) {
    await searchBarRef.value.validate()
    const { registerRange, ...rest } = params
    // 用户ID 是自由文本输入：非数字在这里就拦住，不把反序列化错误留给后端
    // （与消息记录页同一处理口径）。ID 本身逐字直传，不做任何数值转换。
    const rawId = String(rest.id ?? '').trim()
    if (rawId && !/^\d+$/.test(rawId)) {
      ElMessage.warning('用户ID必须是数字编号')
      return
    }
    // 手机号在库里是等值匹配：半截号码或带 * 的脱敏号都只会返回空表，
    // 而空表看上去和"没有这个人"一模一样，所以在这里就拦住并说清楚要填什么。
    const rawPhone = String(rest.userPhone ?? '').trim()
    if (rawPhone && !/^\d{11}$/.test(rawPhone)) {
      ElMessage.warning('请输入完整 11 位手机号')
      return
    }
    const [createTimeBegin, createTimeEnd] = Array.isArray(registerRange)
      ? registerRange
      : [undefined, undefined]
    emit('search', {
      ...rest,
      id: rawId || undefined,
      userPhone: rawPhone || undefined,
      createTimeBegin,
      createTimeEnd
    })
  }
</script>
