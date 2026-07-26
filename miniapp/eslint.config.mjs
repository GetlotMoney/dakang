import uniHelper from '@uni-helper/eslint-config'

export default uniHelper({
  vue: true,
  markdown: false,
  ignores: [
    'dist',
    'node_modules',
    'src/manifest.json',
    'src/pages.json',
    'src/types/uni-pages.d.ts',
    // 微信开发者工具配置由 mp-weixin 构建生成（含 miniprogramRoot/urlCheck），非手写源码，不纳入 lint。
    'project.config.json',
    'project.private.config.json',
  ],
  rules: {
    'no-console': 'off',
    'perfectionist/sort-imports': 'off',
    'vue/block-order': ['error', { order: [['script', 'template'], 'style'] }],
  },
})
