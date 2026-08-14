package com.jbk.serve.service.mini.recharge;

/**
 * 铸造 D-213 之前的历史订单快照（仅测试用）：生产 build 已拒绝付费+有效期套餐，
 * 兼容路径的旧数据只能先走正规 build 再改写 expireDays——
 * 绝不在生产代码开"跳过校验"口子，那会被创建路径误用。
 */
public final class LegacySnapshots {

    private LegacySnapshots() {
    }

    /**
     * @param snapshotJson 用<b>永久</b>套餐 build 出的快照（{@code expireDays} 必须是 null）
     * @param expireDays   要伪造的历史有效期；null 表示不改写、原样返回
     */
    public static String forgeExpireDays(String snapshotJson, Integer expireDays) {
        if (expireDays == null) {
            return snapshotJson;
        }
        String marker = "\"expireDays\":null";
        if (!snapshotJson.contains(marker)) {
            throw new IllegalStateException(
                    "快照里找不到 expireDays:null——铸造历史快照必须从永久套餐的 build 产物出发");
        }
        return snapshotJson.replace(marker, "\"expireDays\":" + expireDays);
    }
}
