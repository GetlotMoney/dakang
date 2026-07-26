package com.jbk.serve.service.device;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsCommandBo;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.device.vo.WsCommandVo;

/**
 * 设备指令服务（安全铁律 4：所有下行指令必须写入 ws_command，并由状态机跟踪至终态）
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface IWsCommandService extends IService<WsCommand> {

    PageDataVo<WsCommandVo> pageData(WsCommandBo commandBo);

    WsCommandVo getData(Long id);

    /**
     * 后台中控下发指令（仅允许 3查询/4锁机/5解锁/6参数同步/7重启；
     * 出水类指令 1/2 由订单链路触发，禁止人工下发防止绕开资金链）
     *
     * @return 指令ID
     */
    Long send(WsCommandBo commandBo);

    /**
     * 订单驱动下发出水指令（内部入口，仅由下单链路事务提交后调用；不进 Controller、无权限暴露）。
     * 重入原子闸：条件抢占订单 CMD_ID，赢者才 publish，物理保证一个订单只产生一条有效出水指令（铁律4）。
     * 已绑定指令则幂等返回既有指令ID。
     *
     * @param orderId 订单ID（须已支付的取水单）
     * @return 出水指令ID
     */
    Long sendDispenseForOrder(Long orderId);

    /**
     * 设备回执（ack）：2已下发 → 3已回执，并联动出水单 2已支付→3出水中。
     * ackCode 非 accepted（rejected/busy/failed）→ 指令 2→5 失败 + 出水单转异常待补偿；缺省 accepted 兼容旧设备。
     * 迟到/重复/错设备 ACK 只审计不改状态（REQ-041）。
     *
     * @param deviceId 上报设备ID（校验与指令目标一致，防错设备 ACK）
     * @param cmdNo    平台指令号
     * @param ackTs    设备回执时间 yyyyMMddHHmmss（P0-06 时间双存，非法回退服务器时间）
     * @param ackCode  回执结果码（accepted/rejected/busy/failed，可空默认 accepted）
     * @return 是否推进了状态
     */
    boolean onAck(Long deviceId, String cmdNo, String ackTs, String ackCode);

    /**
     * 设备执行结果（result）：3已回执 → 4成功/5失败，原样保存 RESULT_PAYLOAD。
     * 订单侧结算（按实际量结算/补差/订单定性）属 L1d，本方法只保证指令终态与结果报文不丢。
     * 迟到结果（已超时/已终态）只审计不改状态。
     *
     * @param deviceId      上报设备ID
     * @param cmdNo         平台指令号
     * @param success       是否成功
     * @param resultPayload 结果报文原文 JSON（实际水量等）
     * @param failReason    失败原因（可空）
     * @param finishTs      设备完成时间 yyyyMMddHHmmss（P0-06 时间双存，非法回退服务器时间）
     * @return 是否推进了状态
     */
    boolean onResult(Long deviceId, String cmdNo, boolean success, String resultPayload, String failReason, String finishTs);

    /**
     * 超时扫描（worker 调用）：已下发超 ackTimeout 无回执、已回执超 resultTimeout 无结果 → 6超时 + 告警。
     *
     * @return 本次扫描转为超时状态的指令数
     */
    int scanTimeout();
}
