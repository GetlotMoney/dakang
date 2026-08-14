package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.bo.MallShipmentCreateBo;
import com.jbk.tool.data.mall.vo.MallFulfillVo;
import com.jbk.tool.data.mall.vo.MallLogisticsProviderVo;

import java.util.List;

/**
 * 第三方物流渠道服务（E2E-09 L1）。
 *
 * <p>本服务只处理 {@code FULFILL_MODE = 2} 的任务。创建运单的**业务事务只写 outbox**，
 * 真正的外呼由 {@code MallLogisticsOutboxWorker} 领取后执行——在事务里外呼的代价是：
 * 网络慢一秒锁就多持有一秒；对方超时本地事务跟着回滚，而对方可能已经把运单建好了。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
public interface IMallLogisticsService {

    /**
     * PC 创建第三方运单请求。
     *
     * <p>同事务完成：CAS 0→2 冻结渠道 → 建包裹 → 登记 outbox 动作 → 履约 3→4。
     * 全部落库后才返回；运单号要等 Worker 拿到承运方回执才会出现。</p>
     */
    MallFulfillVo createShipment(Long operatorId, MallShipmentCreateBo bo);

    /**
     * 已注册的承运商。
     *
     * <p>列表取自实际装配的适配器而不是字典表：字典能被随便加一行，适配器不能。
     * 让运营从字典里选，就会选出一个没有适配器的编码，然后建出一张永远发不出去的运单。</p>
     */
    List<MallLogisticsProviderVo> providers();
}
