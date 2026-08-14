package com.jbk.serve.service.mini.wxship;

import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.mall.WsWechatShippingOutboxMapper;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.tool.data.mall.po.WsWechatShippingOutbox;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发货同步 Worker（WX-ECO S4）：提交后外呼，认领/租约/退避/人工态与通知 Worker 同语义。
 * 适配器未启用时整个 Worker 休眠——待同步行保持待处理，联调轮开启后自然补投；
 * 绝不把「没接入」写成任何终态（skip/manual 都会让真接入后的补投做不成）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatShippingSyncWorker {

    private static final int SCAN_LIMIT = 50;
    private static final int LEASE_SECONDS = 120;
    private static final int MAX_RETRY = 5;

    private final WsWechatShippingOutboxMapper outboxMapper;
    private final WsUserIdentityMapper userIdentityMapper;
    private final IWechatShippingClient shippingClient;

    /** 与出站适配器同一个开关：false 时兜底实现在位，外呼必失败，扫了也是白扫。 */
    @Value("${wechat.shipping.http-client.enabled:false}")
    private boolean clientEnabled;

    @Scheduled(fixedDelayString = "${wechat.shipping.scan-interval-ms:30000}")
    public void scan() {
        if (!clientEnabled) {
            return;
        }
        runOnce(DateUtils.time());
    }

    /** 拆出可测入口：测试直接驱动，不等调度。返回本轮处理条数。 */
    public int runOnce(String now) {
        List<Long> ids = outboxMapper.scanClaimableIds(now, SCAN_LIMIT);
        int handled = 0;
        for (Long id : ids) {
            if (processOne(id)) {
                handled++;
            }
        }
        return handled;
    }

    boolean processOne(Long id) {
        String now = DateUtils.time();
        if (outboxMapper.claimSync(id, now, DateUtils.plusSeconds(now, LEASE_SECONDS)) != 1) {
            return false;
        }
        WsWechatShippingOutbox row = outboxMapper.selectById(id);
        if (row == null) {
            return false;
        }
        try {
            WsUser user = userIdentityMapper.selectByIdIncludingDeleted(row.getReceiverUserId());
            if (user == null || StrUtil.isBlank(user.getWechatXcxOpenid())) {
                // 无 openid 无法定位付款人：确定性问题，重试不自愈
                outboxMapper.markNeedManual(id, "付款人无微信身份，无法同步发货", DateUtils.time());
                return true;
            }
            shippingClient.uploadShippingInfo(row.getTransactionId(), row.getLogisticsType(),
                    row.getProviderCode(), row.getWaybillNo(), row.getItemDesc(),
                    user.getWechatXcxOpenid());
            outboxMapper.markProcessed(id, null, DateUtils.time());
            return true;
        }
        catch (Exception e) {
            String done = DateUtils.time();
            int next = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
            if (next > MAX_RETRY) {
                outboxMapper.markNeedManual(id,
                        StrUtil.maxLength("重试上限：" + e.getMessage(), 480), done);
            }
            else {
                // 指数退避与通知 Worker 同曲线
                long backoffSeconds = 60L * (1L << (next - 1));
                outboxMapper.markRetry(id, DateUtils.plusSeconds(done, backoffSeconds),
                        StrUtil.maxLength(String.valueOf(e.getMessage()), 480), done);
            }
            return true;
        }
    }
}
