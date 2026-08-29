-- 【仅限空库】小程序身份申请与代理血缘领域源文件。
-- 既有库禁止执行本文件；请执行 deploy/mysql/migrations/2026-08-28-mini-identity-workspaces.sql。

DROP TABLE IF EXISTS `ws_demo_control`;
DROP TABLE IF EXISTS `ws_withdraw_order`;
DROP TABLE IF EXISTS `ws_public_lead`;
DROP TABLE IF EXISTS `ws_invite_code`;
DROP TABLE IF EXISTS `ws_identity_audit`;
DROP TABLE IF EXISTS `ws_identity_profile`;
DROP TABLE IF EXISTS `ws_identity_application`;

-- 建表、字典与演示邀请码定义与无损迁移保持一致；空库总初始化以
-- deploy/mysql/init/02-ws-business.sql 末尾的身份能力段为权威执行入口。
SOURCE ../../deploy/mysql/migrations/2026-08-28-mini-identity-workspaces.sql;
