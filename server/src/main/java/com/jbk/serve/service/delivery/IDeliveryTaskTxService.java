package com.jbk.serve.service.delivery;

import com.jbk.tool.data.delivery.bo.DeliveryExceptionReportBo;
import com.jbk.tool.data.delivery.bo.DeliverySignBo;
import com.jbk.tool.data.delivery.po.WsDeliveryException;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;

import java.util.List;

/**
 * 配送任务状态机服务（E2E-03 A3 / 规则7~13）。
 *
 * <p>每次转换都是条件 UPDATE：WHERE 当前态 + VERSION + 配送员归属，叠加订单共键/可履约
 * 栅栏（fail-closed）；actorUserId 一律来自会话，Service 层强制过滤（铁律6/7）。
 * now 由调用方传入（yyyyMMddHHmmss），与订单/消息/审计同源。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface IDeliveryTaskTxService {

    /** 可接任务池：启用配送员 + 服务范围 ∩ 待接单 + 非本人下单 + 预约到点 + 订单可履约。 */
    List<WsDeliveryTask> listAvailableTasks(Long actorUserId, String now);

    /** 接单（规则7/8/9：CAS 并发唯一 + 启用态 + 范围 + 禁自配送 + 预约到点）。 */
    WsDeliveryTask acceptTask(String taskNo, Integer expectedVersion, Long actorUserId, String now);

    /** 推进：targetStatus=3 离站（当前须2）/ 4 送达（当前须3）。 */
    WsDeliveryTask advanceTask(String taskNo, int targetStatus, Integer expectedVersion, Long actorUserId, String now);

    /** 三照签收（规则12/13/14）：服务端时间、结构化数量、三照齐全、签收即订单完成+申诉截止。 */
    WsDeliveryTask signTask(DeliverySignBo bo, Long actorUserId, String now);

    /** 异常上报（限归属配送员，任务 2/3/4；不推进状态不加版本，记录+审计同源时间）。 */
    WsDeliveryException reportException(DeliveryExceptionReportBo bo, Long actorUserId, String now);
}
