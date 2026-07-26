package com.jbk.serve.service.delivery;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 配送员服务范围解析（ws_courier.STATION_IDS 逗号分隔）。
 *
 * <p>空串/非法片段一律得到空集；空集=未配置=默认拒绝接单（fail-closed，
 * 与 Mock 契约 COURIER_SCOPE_DENIED 同口径）。解析绝不抛异常放行脏数据。</p>
 */
public final class CourierScope {

    private CourierScope() {
    }

    public static Set<Long> parseStationIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (String piece : csv.split(",")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(trimmed);
                if (id > 0) {
                    result.add(id);
                }
            } catch (NumberFormatException e) {
                // 单个片段非法：整体按未配置处理（宁可少接一单，不可范围失控）
                return Collections.emptySet();
            }
        }
        return result;
    }
}
