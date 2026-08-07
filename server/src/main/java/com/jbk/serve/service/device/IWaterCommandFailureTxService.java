package com.jbk.serve.service.device;

import com.jbk.tool.consts.device.DeviceEnum;

/**
 * 开始出水指令失败收敛事务。
 *
 * <p>MQTT 发布位于数据库事务之外；发布失败或超时后，指令终态、订单异常态和两条状态事件
 * 必须在本事务内同生共死。拆成独立 Bean 是为了确保 Spring 事务代理生效。</p>
 *
 * @author dakang
 * @since 2026-08-03
 */
public interface IWaterCommandFailureTxService {

    /**
     * @param cmdNo                平台指令号
     * @param expectedCommandStatus 指令精确前态
     * @param targetCommandStatus  失败或超时终态
     * @param finishTime           服务端终态时间
     * @param commandReason        指令失败原因
     * @param orderReason          订单异常原因
     * @param scene                审计场景
     * @return true=本次完成状态推进；false=指令前态已被并发推进
     */
    boolean converge(String cmdNo,
                     Integer expectedCommandStatus,
                     DeviceEnum.CmdStatus targetCommandStatus,
                     String finishTime,
                     String commandReason,
                     String orderReason,
                     String scene);
}
