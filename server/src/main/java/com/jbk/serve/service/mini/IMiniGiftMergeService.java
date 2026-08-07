package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.vo.MiniCardMergeVo;

/**
 * 赠卡合并入正式水卡（D-415，2026-08-06 甲方决议）。
 *
 * @author dakang
 * @since 2026-08-07
 */
public interface IMiniGiftMergeService {

    /**
     * 把本人名下的一张赠卡合并入本人唯一的正式水卡。
     *
     * <p>有效期内：余额与水量整体转入，权益批次携原到期时间改挂主卡（到期显示与
     * 最早到期先用的消费次序由批次层承载）；已自然过期：权益作废清零，仅完成注销清理。
     * 两种形态最终赠卡都进入已注销(4)。重放（赠卡已因合并注销）幂等返回既有结果。</p>
     *
     * @param giftCardId 待合并赠卡ID
     * @param userId     会话登录人（铁律6）
     * @return 合并结果
     */
    MiniCardMergeVo merge(Long giftCardId, Long userId);
}
