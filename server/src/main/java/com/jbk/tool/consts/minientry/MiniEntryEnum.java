package com.jbk.tool.consts.minientry;

/**
 * 小程序入口配置枚举（S6；值对齐 ws_mini_entry.sql 字典 1384/1385/1386）。
 *
 * @author dakang
 * @since 2026-08-07
 */
public interface MiniEntryEnum {

    /** 入口类型 (dictType=1384) */
    enum EntryType {
        FEATURE(1, "功能入口"),
        CONTENT_LINK(2, "内容链接"),
        NOTICE(3, "维护公告");

        private final int value;
        private final String desc;

        EntryType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }
    }

    /** 跳转类型 (dictType=1385) */
    enum JumpType {
        INTERNAL_ROUTE(1, "内部路由"),
        EXTERNAL_URL(2, "外部链接");

        private final int value;
        private final String desc;

        JumpType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }
    }

    /** 配置状态 (dictType=1386)：小程序只读已发布 */
    enum ConfigStatus {
        DRAFT(1, "草稿"),
        PUBLISHED(2, "已发布"),
        RETRACTED(3, "已撤回");

        private final int value;
        private final String desc;

        ConfigStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }
    }
}
