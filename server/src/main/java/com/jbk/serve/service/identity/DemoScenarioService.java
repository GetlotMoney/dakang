package com.jbk.serve.service.identity;

import com.jbk.serve.mapper.identity.WsDemoControlMapper;
import com.jbk.tool.data.identity.po.WsDemoControl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 老板测试档的下一次结果编排器。
 *
 * <p>设置只替换支付机构或物理设备的外部结果，订单、支付事实、指令、流水和分润仍走正式服务。
 * 非默认结果采用一次性消费语义并在同一事务内复位，避免一次“超时”污染后续所有演示。</p>
 */
@Service
@RequiredArgsConstructor
public class DemoScenarioService {

    public static final String PAY_SUCCESS = "SUCCESS";
    public static final String DEVICE_NORMAL = "NORMAL";

    private final WsDemoControlMapper controlMapper;

    @Value("${demo-simulation.enabled:false}")
    private boolean demoEnabled;

    @Transactional(rollbackFor = Exception.class)
    public String consumeNextPayResult(Long userId) {
        if (!demoEnabled || userId == null) {
            return PAY_SUCCESS;
        }
        WsDemoControl control = controlMapper.selectByUserIdForUpdate(userId);
        if (control == null || control.getNextPayResult() == null) {
            return PAY_SUCCESS;
        }
        String result = control.getNextPayResult();
        if (!PAY_SUCCESS.equals(result)) {
            if (controlMapper.resetPayResult(control.getId(), result) != 1) {
                throw new IllegalStateException("演示支付结果已被并发消费");
            }
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public String consumeNextDeviceResult(Long userId) {
        if (!demoEnabled || userId == null) {
            return DEVICE_NORMAL;
        }
        WsDemoControl control = controlMapper.selectByUserIdForUpdate(userId);
        if (control == null || control.getNextDeviceResult() == null) {
            return DEVICE_NORMAL;
        }
        String result = control.getNextDeviceResult();
        if (!DEVICE_NORMAL.equals(result)) {
            if (controlMapper.resetDeviceResult(control.getId(), result) != 1) {
                throw new IllegalStateException("演示设备结果已被并发消费");
            }
        }
        return result;
    }

    public boolean deliveryAutoEnabled(Long userId) {
        if (!demoEnabled || userId == null) {
            return false;
        }
        WsDemoControl control = controlMapper.selectByUserId(userId);
        return control == null || control.getDeliveryAuto() == null || control.getDeliveryAuto() == 1;
    }
}
