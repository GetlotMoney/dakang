package com.jbk.tool.data.user.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.Default;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户档案分区查询请求（一人一档：身份/水卡/订单/资金/关系/审计六区共用）。
 *
 * <p>每个分区各自分页，禁止一次拉全量：一个长期用户的订单与流水没有上限，
 * 「先全查再前端截断」在真实数据量下会直接把抽屉打死。</p>
 *
 * <p>上界必须在本 Bo 兑现，不能只写在注释里：父类 {@link PageBo} 的 size 只有 @NotNull，
 * 全局 PaginationInnerInterceptor 也没有 setMaxLimit——直传时 {@code size=1000000} 会真下发
 * {@code LIMIT 1000000}，{@code size=-1} 更会让 MP 干脆不生成 LIMIT 子句、直接返回全量。
 * 档案面聚合的是一个人的资金流水与关系，放开等同于开了一个无脱敏无审计的批量导出口，
 * 因此按仓内既有敏感读面口径（FinanceQueryBo、领域事件白名单分页）越界即拒绝，
 * 而不是静默截断——越界只可能来自调用方写错或有意扫库，两种都该报错。</p>
 *
 * @author dakang
 * @since 2026-08-13
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsUserProfileBo", description = "用户档案分区查询请求")
public class WsUserProfileBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 单页上限。档案六区都是「看一个人」的面，10~20 条足够；超出即视为扫库意图。 */
    public static final long MAX_PAGE_SIZE = 100L;

    /** 页码上限。与 FinanceQueryBo 同口径，挡住深翻页把库拖垮。 */
    public static final long MAX_PAGE_NO = 100_000L;

    /** 分页分区用 PageGroup 校验 current/size，userId 必须在两个组里都生效，否则分页端点会漏掉它。 */
    @Schema(description = "用户ID")
    @NotNull(message = "用户信息不为空", groups = { Default.class, PageGroup.class })
    private Long userId;

    /** 页码：缺省 1；越界直接拒绝，不静默回落。 */
    public Long pageOrDefault() {
        long value = getCurrent() == null ? 1L : getCurrent();
        if (value <= 0 || value > MAX_PAGE_NO) {
            throw new JbkException("页码不合法");
        }
        return value;
    }

    /**
     * 页大小：缺省 20；{@code <=0}（含 MP 会当成「不分页」的负数）与超上限一律拒绝。
     * 服务层四个分页分区必须经由本方法取值，绕过它就等于放开全量导出。
     */
    public Long sizeOrDefault() {
        long value = getSize() == null ? 20L : getSize();
        if (value <= 0 || value > MAX_PAGE_SIZE) {
            throw new JbkException("页大小不合法");
        }
        return value;
    }
}
