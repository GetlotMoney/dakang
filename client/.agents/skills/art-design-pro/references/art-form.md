# ArtForm - 表单组件

基于 Element Plus Form 的增强型表单组件，提供更便捷的表单验证和布局功能。

## 基本信息

- **组件名称**: ArtForm
- **组件路径**: `@/components/core/forms/art-form/index.vue`

## 功能特性

1. **布局控制** - 支持栅格布局和响应式配置
2. **验证规则** - 支持 Element Plus 的所有验证规则
3. **组件插槽** - 支持自定义表单项渲染
4. **事件转发** - 自动转发 Element Plus Form 的所有事件

## 基本用法

```vue
<template>
  <ArtForm :model="formData" :rules="rules" label-width="100px">
    <ElFormItem label="用户名" prop="username">
      <ElInput v-model="formData.username" />
    </ElFormItem>
    <ElFormItem label="密码" prop="password">
      <ElInput v-model="formData.password" type="password" />
    </ElFormItem>
  </ArtForm>
</template>

<script setup lang="ts">
  const formData = reactive({
    username: '',
    password: ''
  })

  const rules = {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
  }
</script>
```

## API

ArtForm 继承自 Element Plus 的 el-form，**支持 el-form 的所有 Props、Events、Slots 和 Methods**。

### Props

| 属性名      | 说明               | 类型                  | 默认值    |
| ----------- | ------------------ | --------------------- | --------- |
| model       | 表单数据对象       | `Record<string, any>` | `{}`      |
| rules       | 表单验证规则       | `FormRules`           | `-`       |
| label-width | 标签宽度           | `string \| number`    | `'100px'` |
| 其他        | el-form 的所有属性 | -                     | -         |

### Methods

| 方法名        | 说明         |
| ------------- | ------------ |
| validate      | 验证整个表单 |
| validateField | 验证某个字段 |
| resetFields   | 重置表单     |
| clearValidate | 清除验证状态 |

## 使用示例

### 基础表单

```vue
<template>
  <ArtForm ref="formRef" :model="form" :rules="rules" label-width="80px">
    <ElFormItem label="姓名" prop="name">
      <ElInput v-model="form.name" placeholder="请输入姓名" />
    </ElFormItem>
    <ElFormItem label="邮箱" prop="email">
      <ElInput v-model="form.email" placeholder="请输入邮箱" />
    </ElFormItem>
    <ElFormItem label="状态" prop="status">
      <ElSelect v-model="form.status" placeholder="请选择状态">
        <ElOption label="启用" value="1" />
        <ElOption label="禁用" value="0" />
      </ElSelect>
    </ElFormItem>
  </ArtForm>
</template>

<script setup lang="ts">
  const formRef = ref()
  const form = reactive({
    name: '',
    email: '',
    status: ''
  })

  const rules = {
    name: [
      { required: true, message: '请输入姓名', trigger: 'blur' },
      { min: 2, max: 20, message: '姓名长度为 2-20 个字符', trigger: 'blur' }
    ],
    email: [
      { required: true, message: '请输入邮箱', trigger: 'blur' },
      { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' }
    ],
    status: [{ required: true, message: '请选择状态', trigger: 'change' }]
  }

  const submitForm = async () => {
    await formRef.value?.validate((valid) => {
      if (valid) {
        console.log('表单数据:', form)
      }
    })
  }
</script>
```

### 带验证的登录表单

```vue
<template>
  <ArtForm :model="loginForm" :rules="rules" size="large">
    <ElFormItem prop="username">
      <ElInput v-model="loginForm.username" prefix-icon="ri:user-line" placeholder="请输入用户名" />
    </ElFormItem>
    <ElFormItem prop="password">
      <ElInput
        v-model="loginForm.password"
        type="password"
        prefix-icon="ri:lock-line"
        placeholder="请输入密码"
        show-password
      />
    </ElFormItem>
    <ElFormItem>
      <ElButton type="primary" class="w-full" @click="handleLogin">登录</ElButton>
    </ElFormItem>
  </ArtForm>
</template>

<script setup lang="ts">
  const loginForm = reactive({
    username: '',
    password: ''
  })

  const rules = {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    password: [
      { required: true, message: '请输入密码', trigger: 'blur' },
      { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
    ]
  }

  const handleLogin = async () => {
    // 验证并登录
  }
</script>
```

## 典型应用场景

- 用户信息表单
- 搜索筛选表单
- 数据编辑表单
- 配置表单
