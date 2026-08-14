package com.jbk.serve.service.mini.wxpay;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import com.jbk.serve.service.mini.recharge.RechargePayEligibility;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 充值订单的微信 JSAPI 下单编排（WX-ECO S3）。金额/币种/截止时间全部取服务端支付单，
 * 前端只能决定"付哪张单"、改不了"付多少"；Pay-Sim 环境直接拒绝——模拟环境拿到
 * 真 prepay_id 意味着测试流程能产生真实扣款。商城链下单入口仍是 Pay-Sim（E2E-09 口径），
 * 但回调路由两条链已齐备。出站经 {@link IWechatPayClient}，未注册真实适配器时由
 * fail-closed 兜底拒绝。
 *
 * @author dakang
 * @since 2026-08-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MiniWechatPayService {

    private static final int ORDER_TYPE_RECHARGE = 2;
    private static final DateTimeFormatter TIME14 = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final RechargeIdentityMapper identityMapper;
    private final WsUserIdentityMapper userIdentityMapper;
    private final IRechargePaySourceAdapter paySourceAdapter;
    private final IWechatPayClient payClient;
    private final WechatPaySigner signer;

    /**
     * 为当前用户的充值订单发起 JSAPI 下单，返回 requestPayment 五参数。
     *
     * @param userId  会话用户（控制器从会话取，绝不来自请求体）
     * @param orderNo 充值订单号
     */
    public WechatPaySigner.MiniPayParams prepay(Long userId, String orderNo) {
        if (userId == null || StrUtil.isBlank(orderNo)) {
            throw new JbkException("参数不完整");
        }
        if (paySourceAdapter.currentSource() != IRechargePaySourceAdapter.WECHAT) {
            // Pay-Sim 环境闸：模拟环境绝不发起真实下单
            throw new JbkException("当前环境为模拟支付，不能发起微信支付");
        }
        List<WsOrder> orders = identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo.trim());
        if (orders == null || orders.size() != 1) {
            throw new JbkException("订单不存在或无权访问");
        }
        WsOrder order = orders.get(0);
        if (!ObjectUtil.equals(order.getUserId(), userId)
                || !ObjectUtil.equals(order.getDataStatus(), 0)
                || !ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_RECHARGE)) {
            // 与 Pay-Sim 同一句拒绝话术：不向探测者区分"不存在"与"不是你的"
            throw new JbkException("订单不存在或无权访问");
        }
        List<WsPayment> payments = identityMapper.selectPaymentsByOrderIdIncludingDeleted(order.getId());
        if (payments == null || payments.size() != 1
                || !ObjectUtil.equals(payments.get(0).getDataStatus(), 0)) {
            throw new JbkException("订单支付单异常，请联系客服");
        }
        WsPayment payment = payments.get(0);
        if (!ObjectUtil.equals(payment.getPaySource(), IRechargePaySourceAdapter.WECHAT)) {
            // 环境窜线属数据/部署问题：与 Pay-Sim 对称场景同口径走 internal
            throw JbkException.internal("支付单来源与当前适配器不一致，拒绝微信下单");
        }
        // 可支付性必须与 Pay-Sim 同一份判定：自写一套漂移成"模拟拒绝、真实放行"会多扣钱
        String now = DateUtils.time();
        String reject = RechargePayEligibility.rejectReason(order, payment, now);
        if (reject != null) {
            throw new JbkException(reject);
        }
        WsUser user = userIdentityMapper.selectByIdIncludingDeleted(userId);
        if (user == null || StrUtil.isBlank(user.getWechatXcxOpenid())) {
            throw new JbkException("账号未绑定微信身份，无法发起微信支付");
        }

        try {
            // 金额/截止时间均取自服务端支付单；订单描述不带个人信息
            String prepayId = payClient.jsapiPrepay(order.getOrderNo(), payment.getPayAmount(),
                    "水卡充值", user.getWechatXcxOpenid(), toRfc3339(payment.getPayExpireTime()));
            if (StrUtil.isBlank(prepayId)) {
                // 适配器契约要求返回非空 prepay_id；空值宁可失败也不签一个空 package
                throw new JbkException("微信下单未返回预支付单");
            }
            log.info("微信 JSAPI 下单成功 orderNo={} userId={}", order.getOrderNo(), userId);
            return signer.miniPayParams(prepayId);
        }
        catch (IllegalStateException config) {
            // 凭据/AppID 未配置等配置类异常：细节只进日志，用户拿到能行动的话
            log.error("微信支付配置不完整，下单被拒 orderNo={}", order.getOrderNo(), config);
            throw new JbkException("微信支付暂未开通，请稍后再试");
        }
    }

    /** 内部 yyyyMMddHHmmss（Asia/Shanghai）→ 微信 time_expire 要求的 RFC3339。 */
    private static String toRfc3339(String time14) {
        if (StrUtil.isBlank(time14)) {
            return null;
        }
        return LocalDateTime.parse(time14, TIME14).atZone(BUSINESS_ZONE).format(RFC3339);
    }
}
