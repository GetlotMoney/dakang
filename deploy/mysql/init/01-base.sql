-- ============================================================
-- 六维达康 · 底座初始化（01-base.sql）
-- 六维达康 PC Demo 基础表与系统权限底座（api_* 16 表 + ws_user）
-- 数据：仅系统管理/运营总览/日志菜单、超管角色与 admin 演示账号、
--      通用字典(是否/性别/菜单类型/登录类型/禁用状态)、基础部门岗位标签；业务数据零残留
-- ============================================================
CREATE DATABASE IF NOT EXISTS dakang DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE dakang;
SET FOREIGN_KEY_CHECKS=0;

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `api_dept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_dept` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `DEPT_NAME` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '部门名称max20',
  `DEPT_MANAGER_ID` bigint DEFAULT NULL COMMENT '负责人ID',
  `DEPT_PARENT_ID` bigint NOT NULL COMMENT '部门父级id',
  `DEPT_SORT` smallint NOT NULL COMMENT '排序max10000',
  `DEPT_DESC` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '部门描述max300',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='部门';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_dict_data`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_dict_data` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键，自增ID',
  `DICT_CLASS` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '样式',
  `DICT_DEFAULT_FLAG` tinyint NOT NULL COMMENT '是否默认(1)',
  `DICT_TYPE` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '字典类型(max5)',
  `DICT_SORT` smallint NOT NULL COMMENT '字典排序',
  `DICT_VALUE` tinyint NOT NULL COMMENT '字典键值',
  `DICT_LABEL` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '字典标签(max10)',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=104 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='字典数据表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_dict_type`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_dict_type` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键，自增ID',
  `DICT_NAME` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '字典名称(max10)',
  `DICT_TYPE` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '字典类型(max5)',
  `DICT_REMARK` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '字典备注(max150)',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=38 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='字典类型表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_employee`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_employee` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `LOGIN_NAME` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录账号',
  `LOGIN_PWD` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录密码',
  `EMPLOYEE_NAME` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '员工名称max30',
  `EMPLOYEE_GENDER` tinyint NOT NULL COMMENT '员工性别10000',
  `EMPLOYEE_PHONE` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '手机号码',
  `DEPT_ID` bigint NOT NULL COMMENT '部门ID',
  `POSITION_ID` bigint DEFAULT NULL COMMENT '职务ID',
  `DISABLED_FLAG` tinyint NOT NULL COMMENT '是否被禁用1',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='员工';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_employee_tag`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_employee_tag` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `TAG_ID` bigint NOT NULL COMMENT '标签ID',
  `EMPLOYEE_ID` bigint NOT NULL COMMENT '人员ID',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='标签';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_log_login`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_log_login` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键，自增ID',
  `LOG_USER_ID` bigint NOT NULL COMMENT '用户ID',
  `LOG_USER_TYPE` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户类型',
  `LOG_USER_NAME` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户姓名',
  `LOG_EXECUTE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作时间',
  `LOG_TYPE` tinyint NOT NULL COMMENT '登录类型120',
  `LOG_IP` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'IP',
  `LOG_USER_AGENT` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '客户端使用的浏览器或其他应用程序的信息',
  `UA_IS_MOBILE` tinyint NOT NULL COMMENT '是否为移动平台1',
  `UA_PLATFORM` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '平台',
  `UA_BROWSER` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '浏览器',
  `UA_BROWSER_VERSION` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '浏览器版本',
  `UA_ENGINE` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '引擎',
  `UA_ENGINE_VERSION` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '引擎版本',
  `UA_OS` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作系统',
  `UA_OS_VERSION` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作系统版本',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=176 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_log_operation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_log_operation` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `LOG_USER_ID` bigint NOT NULL COMMENT '用户ID',
  `LOG_USER_TYPE` tinyint NOT NULL COMMENT '用户类型110',
  `LOG_USER_NAME` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户姓名',
  `LOG_MODULE` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作模块',
  `LOG_CONTENT` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作内容',
  `LOG_METHOD` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '请求方法',
  `LOG_EXECUTE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作时间',
  `LOG_SUCCESS_FLAG` tinyint NOT NULL COMMENT '是否成功1',
  `LOG_IP` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'IP',
  `LOG_USER_AGENT` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '客户端使用的浏览器或其他应用程序的信息',
  `LOG_CONSUMER_TIME` int NOT NULL COMMENT '耗时ms',
  `LOG_URL` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '请求URL',
  `LOG_REQUEST_PARAM` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '请求参数',
  `LOG_RESPONSE_PARAM` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '返回参数',
  `LOG_MONITOR_INFO` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '监控信息',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=452 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_oss_file`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_oss_file` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `FILE_PATH` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '文件地址(mx150)',
  `FILE_SIZE` bigint NOT NULL COMMENT '文件大小',
  `FILE_NAME` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '文件名称(max50)',
  `USR_ID` bigint NOT NULL COMMENT '所属用户ID',
  `USR_TYPE` tinyint NOT NULL COMMENT '用户类型110',
  PRIMARY KEY (`ID`) USING BTREE,
  KEY `PATH` (`FILE_PATH`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='文件';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_position`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_position` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `POSITION_NAME` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '职务名称max20',
  `POSITION_LEVEL` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '职级',
  `POSITION_SORT` smallint NOT NULL COMMENT '排序max10000',
  `POSITION_REMARK` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注max300',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_rbac_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_rbac_menu` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `MENU_NAME` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜单/功能点名称max20',
  `MENU_TYPE` tinyint NOT NULL COMMENT '菜单类型(50)：1菜单 2目录 3功能点',
  `MENU_ICON` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '菜单图标max150',
  `MENU_PARENT_ID` bigint NOT NULL COMMENT '父菜单ID',
  `MENU_SORT` smallint NOT NULL COMMENT '顺序max10000',
  `MENU_PATH` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '路由地址max150',
  `MENU_COMPONENT` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组件路径max150',
  `MENU_FRAME_FLAG` tinyint NOT NULL COMMENT '是否为外链(1)：1否 2是',
  `MENU_FRAME_URL` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '外链地址max300',
  `MENU_API_PERMS` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '后端权限字符串max150',
  `MENU_WEB_PERMS` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '前端权限字符串max150',
  `MENU_VISIBLE_FLAG` tinyint NOT NULL COMMENT '显示状态(1)：1否 2是',
  `MENU_DISABLED_FLAG` tinyint NOT NULL COMMENT '禁用状态(1)：1否 2是',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=681 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_rbac_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_rbac_role` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `ROLE_NAME` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色名称max10',
  `ROLE_CODE` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色标识max10',
  `ROLE_REMARK` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '角色备注max300',
  `ROLE_SORT` smallint NOT NULL COMMENT '角色排序max10000',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_rbac_role_employee`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_rbac_role_employee` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL DEFAULT '1' COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '20251127000000' COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL DEFAULT '1' COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '20251127000000' COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL DEFAULT '0' COMMENT '状态',
  `ROLE_ID` bigint NOT NULL COMMENT '角色ID',
  `EMPLOYEE_ID` bigint NOT NULL COMMENT '员工ID',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_rbac_role_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_rbac_role_menu` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `ROLE_ID` bigint NOT NULL COMMENT '角色ID',
  `MENU_ID` bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1041 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_rbac_role_scope`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_rbac_role_scope` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `ROLE_ID` bigint NOT NULL COMMENT '角色ID',
  `SCOPE_TYPE` tinyint NOT NULL COMMENT '范围类型55',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `api_tag`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_tag` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `TAG_NAME` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '人员标签max20',
  PRIMARY KEY (`ID`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `ws_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ws_user` (
  `ID` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `CREATE_BY` bigint NOT NULL COMMENT '创建者',
  `CREATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建时间',
  `UPDATE_BY` bigint NOT NULL COMMENT '更新者',
  `UPDATE_TIME` varchar(14) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新时间',
  `DATA_STATUS` tinyint NOT NULL COMMENT '状态',
  `USER_NAME` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户名称',
  `USER_GENDER` tinyint NULL COMMENT '用户性别10000（L2-AUTH：微信登录建户可空）',
  `USER_PHONE` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户联系方式',
  `USER_IDENTITY_CIPHER` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '身份信息密文（禁止存明文）',
  `USER_AVATAR` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '用户头像',
  `DISABLED_FLAG` tinyint NOT NULL COMMENT '是否被禁用1',
  `USER_STATUS` tinyint NOT NULL COMMENT '用户状态10100',
  `POINTS` int NOT NULL COMMENT '积分数',
  `WECHAT_XCX_OPENID` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用户小程序openid（L2-AUTH：大小写敏感精确比较）',
  `CHANNEL_USER_ID` bigint DEFAULT NULL COMMENT '归属渠道用户ID（一期预留）',
  `REFERRER_USER_ID` bigint DEFAULT NULL COMMENT '推荐人用户ID（一期预留）',
  `PROMO_CODE` varchar(20) DEFAULT NULL COMMENT '注册推广码(max20，一期预留)',
  PRIMARY KEY (`ID`) USING BTREE,
  -- L2-AUTH 身份唯一约束（fresh init 已是权威结构，新部署无需再跑迁移；迁移仅用于既有库升级）。
  UNIQUE KEY `uk_user_phone` (`USER_PHONE`) USING BTREE,
  UNIQUE KEY `uk_user_wechat_xcx_openid` (`WECHAT_XCX_OPENID`) USING BTREE,
  KEY `identity` (`USER_IDENTITY_CIPHER`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


-- ---------------- 种子数据 ----------------

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

LOCK TABLES `api_dept` WRITE;
/*!40000 ALTER TABLE `api_dept` DISABLE KEYS */;
INSERT IGNORE INTO `api_dept` (`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `DEPT_NAME`, `DEPT_MANAGER_ID`, `DEPT_PARENT_ID`, `DEPT_SORT`, `DEPT_DESC`) VALUES (1,1,'20260520120000',1,'20260526173652',0,'总部',1,0,1,'公司总部'),(2,1,'20260520120000',1,'20260520120000',0,'运维部',NULL,1,1,'负责设备运维'),(3,1,'20260520120000',1,'20260520120000',0,'客服部',NULL,1,2,'负责运营推广'),(4,1,'20260520120000',1,'20260520120000',0,'技术部',NULL,1,3,'负责技术开发');
/*!40000 ALTER TABLE `api_dept` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `api_dict_data` WRITE;
/*!40000 ALTER TABLE `api_dict_data` DISABLE KEYS */;
INSERT IGNORE INTO `api_dict_data` (`ID`, `DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (7,NULL,1,'20',1,1,'男'),(8,NULL,1,'20',2,2,'女'),(23,NULL,1,'1',1,1,'否'),(24,NULL,1,'1',2,2,'是'),(28,NULL,1,'22',1,1,'目录'),(29,NULL,1,'22',2,2,'菜单'),(30,NULL,1,'22',3,3,'按钮'),(50,NULL,1,'50',1,1,'目录'),(51,NULL,0,'50',2,2,'菜单'),(52,NULL,0,'50',3,3,'功能点'),(53,NULL,1,'120',1,1,'登录成功'),(54,NULL,0,'120',2,2,'退出登录'),(55,NULL,0,'120',3,3,'密码错误'),(56,NULL,1,'10',1,1,'正常'),(57,NULL,0,'10',2,2,'禁用');
/*!40000 ALTER TABLE `api_dict_data` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `api_dict_type` WRITE;
/*!40000 ALTER TABLE `api_dict_type` DISABLE KEYS */;
INSERT IGNORE INTO `api_dict_type` (`ID`, `DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES (9,'是否','1','用于布尔值的表述'),(16,'性别','20','性别枚举'),(19,'菜单类型','50','系统菜单类型'),(20,'登录类型','120','登录日志类型'),(21,'禁用状态','10','账号禁用状态');
/*!40000 ALTER TABLE `api_dict_type` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `api_employee` WRITE;
/*!40000 ALTER TABLE `api_employee` DISABLE KEYS */;
-- admin 的 LOGIN_PWD 是 **Demo 固定口令密文**，仅与源码内置的 Demo RSA 密钥对
-- （server/.../RSAUtils.java 的 DEMO_PRIVATE_KEY/DEMO_PUBLIC_KEY，示例值见 .env.example 的
-- DAKANG_RSA_PRIVATE_KEY/DAKANG_RSA_PUBLIC_KEY 注释）配套；密钥对随源码公开，该密文等同明文。
-- 保留它是为了让全新环境 `docker compose up` 后能直接登录 PC 走通 Demo。
-- **生产部署必须**：先换 RSA 密钥对（含前端 client/.env 的 VITE_ACCESS_LOGIN_KEY 同步换成新公钥），
-- 再用新密钥对重置管理员口令覆盖本行密文——只做其中一步会导致管理员账号登不上。
INSERT IGNORE INTO `api_employee` (`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `LOGIN_NAME`, `LOGIN_PWD`, `EMPLOYEE_NAME`, `EMPLOYEE_GENDER`, `EMPLOYEE_PHONE`, `DEPT_ID`, `POSITION_ID`, `DISABLED_FLAG`) VALUES (1,1,'20260520120000',1,'20260520120000',0,'admin','B51yw4neAThtpTnUhsmvp+Ikho2Cu6ToTXw3c3iDtbTR7HSOfPIk6vY0cHjGRQzy6UdINaYqQbKpKeBOYSYAw/d1oQM2OmevVJ2UgCUTi3eVopeNXL5YHk+Uyx6JDV61M0M3kymmNn5ZLPaaqZeYMH0YHRKQMdDHXDpNl05IkhQ=','超级管理员',1,'13800000001',1,1,1);
/*!40000 ALTER TABLE `api_employee` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `api_position` WRITE;
/*!40000 ALTER TABLE `api_position` DISABLE KEYS */;
INSERT IGNORE INTO `api_position` (`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `POSITION_NAME`, `POSITION_LEVEL`, `POSITION_SORT`, `POSITION_REMARK`) VALUES (1,1,'20260520120000',1,'20260526172504',0,'总经理','P1',1,'公司最高管理者'),(2,1,'20260520120000',1,'20260520120000',0,'部门主管','P2',2,'部门负责人'),(3,1,'20260520120000',1,'20260520120000',0,'普通员工','P3',3,'普通岗位');
/*!40000 ALTER TABLE `api_position` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `api_rbac_menu` WRITE;
/*!40000 ALTER TABLE `api_rbac_menu` DISABLE KEYS */;
INSERT IGNORE INTO `api_rbac_menu` (`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `MENU_NAME`, `MENU_TYPE`, `MENU_ICON`, `MENU_PARENT_ID`, `MENU_SORT`, `MENU_PATH`, `MENU_COMPONENT`, `MENU_FRAME_FLAG`, `MENU_FRAME_URL`, `MENU_API_PERMS`, `MENU_WEB_PERMS`, `MENU_VISIBLE_FLAG`, `MENU_DISABLED_FLAG`) VALUES
(607,1,'20260714120000',1,'20260714120000',0,'系统管理',1,'ri:settings-line',0,2,'/system','/index/index',1,NULL,NULL,NULL,1,1),
(608,1,'20260714120000',1,'20260714120000',0,'部门管理',2,NULL,607,2,'dept','/system/dept/index',1,NULL,NULL,NULL,1,1),
(609,1,'20260714120000',1,'20260714120000',0,'仪表盘',1,'ri:pie-chart-line',0,99,'/dashboard','/index/index',1,NULL,NULL,NULL,1,1),
(610,1,'20260714120000',1,'20260714120000',0,'运营总览',2,NULL,609,1,'console','/dashboard/console/index',1,NULL,NULL,NULL,1,1),
(611,1,'20260714120000',1,'20260714120000',0,'员工管理',2,NULL,607,5,'user','/system/user/index',1,NULL,NULL,NULL,1,1),
(612,1,'20260714120000',1,'20260714120000',0,'角色管理',2,NULL,607,4,'role','/system/role/index',1,NULL,NULL,NULL,1,1),
(613,1,'20260714120000',1,'20260714120000',0,'菜单管理',2,NULL,607,3,'menu','/system/menu/index',1,NULL,NULL,NULL,1,1),
(614,1,'20260714120000',1,'20260714120000',0,'职务管理',2,NULL,607,2,'position','/system/position/index',1,NULL,NULL,NULL,1,1),
(615,1,'20260714120000',1,'20260714120000',0,'日志管理',1,'ri:newspaper-line',0,1,'/system/log','/index/index',1,NULL,NULL,NULL,1,1),
(616,1,'20260714120000',1,'20260714120000',0,'登录日志',2,NULL,615,2,'login','/system/log/login/index',1,NULL,'api:logLogin:query','api:logLogin:query',1,1),
(617,1,'20260714120000',1,'20260714120000',0,'操作日志',2,NULL,615,1,'operation','/system/log/operation/index',1,NULL,'api:logOperation:query','api:logOperation:query',1,1),
(640,1,'20260714120000',1,'20260714120000',0,'标签管理',2,NULL,607,1,'tag','/system/tag/index',1,NULL,NULL,NULL,1,1),
(619,1,'20260714120000',1,'20260714120000',0,'新增部门',3,NULL,608,1,NULL,NULL,1,NULL,'api:dept:add','api:dept:add',2,1),
(633,1,'20260714120000',1,'20260714120000',0,'删除部门',3,NULL,608,2,NULL,NULL,1,NULL,'api:dept:delete','api:dept:delete',2,1),
(634,1,'20260714120000',1,'20260714120000',0,'修改部门',3,NULL,608,3,NULL,NULL,1,NULL,'api:dept:update','api:dept:update',2,1),
(635,1,'20260714120000',1,'20260714120000',0,'查询部门',3,NULL,608,4,NULL,NULL,1,NULL,'api:dept:query','api:dept:query',2,1),
(620,1,'20260714120000',1,'20260714120000',0,'新增菜单',3,NULL,613,1,NULL,NULL,1,NULL,'api:menu:add','api:menu:add',2,1),
(621,1,'20260714120000',1,'20260714120000',0,'删除菜单',3,NULL,613,2,NULL,NULL,1,NULL,'api:menu:delete','api:menu:delete',2,1),
(622,1,'20260714120000',1,'20260714120000',0,'修改菜单',3,NULL,613,3,NULL,NULL,1,NULL,'api:menu:update','api:menu:update',2,1),
(623,1,'20260714120000',1,'20260714120000',0,'查询菜单',3,NULL,613,4,NULL,NULL,1,NULL,'api:menu:query','api:menu:query',2,1),
(624,1,'20260714120000',1,'20260714120000',0,'新增员工',3,NULL,611,1,NULL,NULL,1,NULL,'api:employee:add','api:employee:add',2,1),
(625,1,'20260714120000',1,'20260714120000',0,'删除员工',3,NULL,611,2,NULL,NULL,1,NULL,'api:employee:delete','api:employee:delete',2,1),
(626,1,'20260714120000',1,'20260714120000',0,'修改员工',3,NULL,611,3,NULL,NULL,1,NULL,'api:employee:update','api:employee:update',2,1),
(627,1,'20260714120000',1,'20260714120000',0,'查询员工',3,NULL,611,4,NULL,NULL,1,NULL,'api:employee:query','api:employee:query',2,1),
(628,1,'20260714120000',1,'20260714120000',0,'重置密码',3,NULL,611,5,NULL,NULL,1,NULL,'api:employee:resetPwd','api:employee:resetPwd',2,1),
(629,1,'20260714120000',1,'20260714120000',0,'新增角色',3,NULL,612,1,NULL,NULL,1,NULL,'api:role:add','api:role:add',2,1),
(630,1,'20260714120000',1,'20260714120000',0,'删除角色',3,NULL,612,2,NULL,NULL,1,NULL,'api:role:delete','api:role:delete',2,1),
(631,1,'20260714120000',1,'20260714120000',0,'修改角色',3,NULL,612,3,NULL,NULL,1,NULL,'api:role:update','api:role:update',2,1),
(632,1,'20260714120000',1,'20260714120000',0,'查询角色',3,NULL,612,4,NULL,NULL,1,NULL,'api:role:query','api:role:query',2,1),
(636,1,'20260714120000',1,'20260714120000',0,'新增职务',3,NULL,614,1,NULL,NULL,1,NULL,'api:position:add','api:position:add',2,1),
(637,1,'20260714120000',1,'20260714120000',0,'删除职务',3,NULL,614,2,NULL,NULL,1,NULL,'api:position:delete','api:position:delete',2,1),
(638,1,'20260714120000',1,'20260714120000',0,'修改职务',3,NULL,614,3,NULL,NULL,1,NULL,'api:position:update','api:position:update',2,1),
(639,1,'20260714120000',1,'20260714120000',0,'查询职务',3,NULL,614,4,NULL,NULL,1,NULL,'api:position:query','api:position:query',2,1),
(641,1,'20260714120000',1,'20260714120000',0,'新增标签',3,NULL,640,1,NULL,NULL,1,NULL,'api:tag:add','api:tag:add',2,1),
(642,1,'20260714120000',1,'20260714120000',0,'删除标签',3,NULL,640,2,NULL,NULL,1,NULL,'api:tag:delete','api:tag:delete',2,1),
(643,1,'20260714120000',1,'20260714120000',0,'修改标签',3,NULL,640,3,NULL,NULL,1,NULL,'api:tag:update','api:tag:update',2,1),
(644,1,'20260714120000',1,'20260714120000',0,'查询标签',3,NULL,640,4,NULL,NULL,1,NULL,'api:tag:query','api:tag:query',2,1),
(645,1,'20260714120000',1,'20260714120000',0,'分配角色',3,NULL,611,6,NULL,NULL,1,NULL,'api:employee:assignRole','api:employee:assignRole',2,1),
(646,1,'20260714120000',1,'20260714120000',0,'分配权限',3,NULL,612,5,NULL,NULL,1,NULL,'api:role:permission','api:role:permission',2,1);
/*!40000 ALTER TABLE `api_rbac_menu` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `api_rbac_role` WRITE;
/*!40000 ALTER TABLE `api_rbac_role` DISABLE KEYS */;
INSERT IGNORE INTO `api_rbac_role` (`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `ROLE_NAME`, `ROLE_CODE`, `ROLE_REMARK`, `ROLE_SORT`) VALUES (1,1,'20260520120000',1,'20260525111427',0,'超级管理员','R_SUPER','拥有系统全部权限',3);
/*!40000 ALTER TABLE `api_rbac_role` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `api_rbac_role_employee` WRITE;
/*!40000 ALTER TABLE `api_rbac_role_employee` DISABLE KEYS */;
INSERT IGNORE INTO `api_rbac_role_employee` (`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `ROLE_ID`, `EMPLOYEE_ID`) VALUES (1,1,'20260520120000',1,'20260520120000',0,1,1);
/*!40000 ALTER TABLE `api_rbac_role_employee` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `api_rbac_role_menu` WRITE;
/*!40000 ALTER TABLE `api_rbac_role_menu` DISABLE KEYS */;
/*!40000 ALTER TABLE `api_rbac_role_menu` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `api_tag` WRITE;
/*!40000 ALTER TABLE `api_tag` DISABLE KEYS */;
INSERT IGNORE INTO `api_tag` (`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `TAG_NAME`) VALUES (1,1,'20260520120000',1,'20260520120000',0,'全职'),(2,1,'20260520120000',1,'20260520120000',0,'兼职'),(3,1,'20260520120000',1,'20260520120000',0,'实习');
/*!40000 ALTER TABLE `api_tag` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

SET FOREIGN_KEY_CHECKS=1;
