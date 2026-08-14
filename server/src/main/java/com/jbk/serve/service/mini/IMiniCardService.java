package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.bo.MiniCardMemberRevokeBo;
import com.jbk.tool.data.mini.bo.MiniCardMemberSaveBo;
import com.jbk.tool.data.mini.vo.MiniCardDetailVo;
import com.jbk.tool.data.mini.vo.MiniCardMemberVo;
import com.jbk.tool.data.mini.vo.MiniCardSummaryVo;
import com.jbk.tool.data.mini.vo.MiniUsableCardVo;

import java.util.List;

/**
 * 小程序水卡服务（L1f 扫码取水链：本人主卡余额真实展示）。
 * <p>数据范围铁律6：一律按会话登录人过滤，禁收前端 userId。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
public interface IMiniCardService {

    /**
     * 取本人主卡摘要（虚拟卡优先，其次最早开卡的实体卡）。
     *
     * @param userId 会话登录人（KH_USER），调用方从 StpKit 取，禁止来自前端
     * @return 主卡摘要；本人名下无卡时返回 {@code null}（对应 code 0 data null）
     */
    MiniCardSummaryVo getPrimaryCard(Long userId);

    /**
     * 取本人指定水卡详情（L2-READ）。fail-closed：不存在/已删/非本人一律抛业务异常，
     * 不返回 null 也不降级为主卡（防传他人 cardId 越权读卡）。
     *
     * @param cardId 水卡ID（来自前端）
     * @param userId 会话登录人（KH_USER），调用方从 StpKit 取，禁止来自前端
     */
    MiniCardDetailVo getCardDetail(Long cardId, Long userId);

    /**
     * 可用水卡列表（CARD-MEMBER）：本人持卡（OWNER）+ 当前有效的成员授权卡（MEMBER）。
     *
     * <p>不改 {@link #getPrimaryCard} 语义。OWNER 卡带可充值/可管成员能力位；MEMBER 卡只回
     * 取水所需最小摘要，并在配置了 DAY_LIMIT_ML 时带当日剩余限额（只读估算，事务内另行判定）。</p>
     *
     * @param userId 会话登录人（KH_USER）
     * @return 可用卡列表（可能为空）
     */
    List<MiniUsableCardVo> listUsableCards(Long userId);

    /**
     * 保存成员授权（新增/编辑，CARD-MEMBER）。
     *
     * <p>规则：只能管理本人持有的卡；手机号只匹配已存在且可用的唯一用户（不自动建户）；
     * 禁止把自己授权为成员；编辑不能换人；重新授权复用 uk_card_member_user 定位的原记录；
     * 生效时间不得晚于失效时间；DAY_LIMIT_ML 空=不限、否则正整数。响应手机号只回脱敏。</p>
     *
     * @param bo     保存入参
     * @param userId 会话登录人（KH_USER，必须为卡主）
     * @return 保存后的成员授权
     */
    MiniCardMemberVo saveMember(MiniCardMemberSaveBo bo, Long userId);

    /**
     * 撤销成员授权（CARD-MEMBER）：条件状态更新（1生效→2已解除）并校验影响行数；
     * 非本人卡、记录不存在或已解除一律 fail-closed 拒绝。
     *
     * @param bo     撤销入参
     * @param userId 会话登录人（KH_USER，必须为卡主）
     * @return 撤销后的成员授权
     */
    MiniCardMemberVo revokeMember(MiniCardMemberRevokeBo bo, Long userId);
}
