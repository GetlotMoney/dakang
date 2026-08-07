package com.jbk.tool.consts.compliance;

import com.jbk.tool.exception.JbkException;

/**
 * 安全与合规域枚举（B23 / REQ-024、REQ-066）
 *
 * @author dakang
 * @since 2026-08-06
 */
public interface ComplianceEnum {

    /**
     * 审计导出任务状态 (dictType=1382)。
     *
     * <p><b>DONE(4) 与 EXPIRED(5) 当前不可达</b>：文件生成依赖对象存储，尚未接入。
     * 平台只能创建 PENDING、失败时置 FAILED、重试回 PENDING；把任务推进到 DONE
     * 等于在合规页上伪造"导出成功"，与 PC 职责边界第 3 条直接冲突。
     * 两个值先登记，等对象存储接入后由真实文件产出驱动。</p>
     */
    enum AuditExportStatus {
        PENDING(1, "待生成"),
        RUNNING(2, "生成中"),
        FAILED(3, "失败"),
        DONE(4, "已生成"),
        EXPIRED(5, "已过期");

        private final int value;
        private final String desc;

        AuditExportStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static AuditExportStatus getType(int type) {
            for (AuditExportStatus item : AuditExportStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }
}
