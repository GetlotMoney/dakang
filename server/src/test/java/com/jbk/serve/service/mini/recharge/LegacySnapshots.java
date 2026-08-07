package com.jbk.serve.service.mini.recharge;

/**
 * 铸造 <b>D-213 之前</b>的历史订单快照（仅测试用）。
 *
 * <p>D-213 落地后，{@link RechargeSnapshot#build}/{@code buildForPurchase} 里的
 * {@code RechargeLimits.validatePackage} 会拒绝付费+有效期的套餐——生产代码<b>再也造不出</b>
 * 有限期付费快照。但主库里已经躺着一批这样的旧单，审计要求它们「仅兼容查询，
 * 不得作为新订单规则继续执行」：入账、发卡、关单、详情都必须继续读得动。</p>
 *
 * <p>要测这条兼容路径，就得先造出旧数据。造法是先用永久套餐走正规 build
 * （所有其他守卫照常生效），再把 JSON 里的 {@code "expireDays":null} 改写成数值——
 * 与真实旧单逐字段同构。<b>不要</b>为此在生产代码上开任何"跳过校验"的口子：
 * 那个口子一定会被创建路径误用。</p>
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
