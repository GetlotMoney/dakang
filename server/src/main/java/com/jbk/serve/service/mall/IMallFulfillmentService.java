package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.bo.MallFulfillActionBo;
import com.jbk.tool.data.mall.bo.MallFulfillAssignBo;
import com.jbk.tool.data.mall.bo.MallFulfillSignBo;
import com.jbk.tool.data.mall.vo.MallCourierCandidateVo;
import com.jbk.tool.data.mall.vo.MallFulfillVo;

import java.util.List;

/**
 * 商城履约服务（E2E-09 S3）：把已支付订单推进到用户签收完成。不碰库存——S2 支付时已完成
 * 实销（流水类型 7），回库只能由 S4 合法售后事实驱动。范围校验一律在服务层重做：
 * 页面过滤是给人看的不是授权，每个动作按会话身份重新判定归属。
 *
 * @author dakang
 * @since 2026-08-09
 */
public interface IMallFulfillmentService {

    /**
     * 幂等生成履约任务：同一订单重复触发返回原任务，绝不生成第二个。
     *
     * <p>只有未删除、状态精确为 2「已支付待履约」的订单才能生成任务。刻意不挂在支付
     * 事务B 里：那段是资金链，让履约建表参与进去会把两条链的失败面绑在一起。</p>
     */
    MallFulfillVo ensureTask(String orderNo);

    /** 前置仓拣货：任务 1→2，同事务把订单 2→3（履约开始）。 */
    MallFulfillVo pick(Long operatorId, MallFulfillActionBo bo);

    /** 前置仓打包完成：任务 2→3（待分配）。 */
    MallFulfillVo pack(Long operatorId, MallFulfillActionBo bo);

    /** 用户签收：任务 6→7 且订单 3→4，同一事务内落时间、证据、轨迹、消息与审计（审计时间不同源）。 */
    MallFulfillVo signByUser(Long userId, MallFulfillSignBo bo);

    /** 用户视角详情（本人订单）。 */
    MallFulfillVo detailForUser(Long userId, String orderNo);

    /** PC 视角详情（含完整时间线）；操作员须归属本单前置仓。 */
    MallFulfillVo detailForManage(Long operatorId, String orderNo);
}
