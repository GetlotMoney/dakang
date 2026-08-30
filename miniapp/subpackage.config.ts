/**
 * 微信主包只保留登录、三项 Tabbar 与用户高频链路。
 *
 * 这些目录均是非 Tabbar 工作台；迁入分包不改变页面完整路径，但可避免商城、经营、配送和
 * 身份页面持续挤占微信 2MB 主包上限。新增低频工作台时先判断应归入哪个分包，而不是回填主包。
 */
export const MINIAPP_SUBPACKAGE_ROOTS = [
  'src/pages/mall',
  'src/pages/owner',
  'src/pages/courier',
  'src/pages/identity',
  'src/pages/channel',
  'src/pages/region',
  'src/pages/demo',
] as const
