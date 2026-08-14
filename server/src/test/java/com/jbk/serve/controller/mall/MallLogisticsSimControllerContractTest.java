package com.jbk.serve.controller.mall;

import cn.hutool.crypto.digest.DigestUtil;
import com.jbk.serve.service.mall.IMallLogisticsFactService;
import com.jbk.serve.service.mall.IMallPayApplyTx;
import com.jbk.tool.data.mall.bo.MallLogisticsSimEventBo;
import com.jbk.tool.data.mall.po.WsMallLogisticsEvent;
import com.jbk.tool.domain.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logistics-Sim 入口合同（E2E-09 L1）：验签在控制器层，真库用例全从已验签后开始，
 * 「验签失败也不落库」只有这层能验。判据是事实服务一次都没被调用，而非仅返回错误。
 */
@DisplayName("Logistics-Sim 入口合同")
class MallLogisticsSimControllerContractTest {

    private static final String SECRET = "dakang-logistics-sim";

    /** 记账假实现：记下每一次调用，用于断言"根本没被调用过"。 */
    private static final class RecordingFactService implements IMallLogisticsFactService {

        private final List<String> recorded = new ArrayList<>();
        private final List<Long> processed = new ArrayList<>();

        @Override
        public WsMallLogisticsEvent recordFact(String providerCode, int factChannel,
                                               String providerEventKey, String waybillNo,
                                               String eventState, String eventTime,
                                               String eventDesc, int verifyMethod,
                                               String rawBody) {
            recorded.add(providerEventKey);
            return new WsMallLogisticsEvent().setProviderEventKey(providerEventKey);
        }

        @Override
        public IMallPayApplyTx.Outcome process(Long eventId) {
            processed.add(eventId);
            return IMallPayApplyTx.Outcome.applied();
        }
    }

    private static MallLogisticsSimEventBo bo() {
        return new MallLogisticsSimEventBo()
                .setProviderCode("SIM")
                .setProviderEventKey("EV-CONTRACT-1")
                .setWaybillNo("SIMWB0123456789ABCD")
                .setEventState("IN_TRANSIT")
                .setEventTime("20260811120000")
                .setEventDesc("已到达转运中心");
    }

    private static String sign(MallLogisticsSimEventBo bo) {
        return DigestUtil.sha256Hex(String.join(":", bo.getProviderCode(),
                bo.getProviderEventKey(), bo.getWaybillNo(), bo.getEventState(),
                bo.getEventTime(), SECRET));
    }

    private static MallLogisticsSimController controllerWith(RecordingFactService fake) {
        MallLogisticsSimController controller = new MallLogisticsSimController(fake);
        ReflectionTestUtils.setField(controller, "simSecret", SECRET);
        return controller;
    }

    @Test
    @DisplayName("验签失败时事实服务一次都不被调用，报文不落库")
    void badSignatureNeverReachesTheFactService() {
        RecordingFactService fake = new RecordingFactService();
        MallLogisticsSimController controller = controllerWith(fake);

        R<String> result = controller.simEvent(bo().setSignature("deadbeef"));

        assertTrue(fake.recorded.isEmpty(), "验签失败必须在落库之前拒绝");
        assertTrue(fake.processed.isEmpty(), "验签失败不得推进任何状态");
        assertNotEquals(200, result.getCode(), "验签失败必须返回错误");
    }

    @Test
    @DisplayName("签名正确时才落事实并推进，且顺序是先落事实再推进")
    void goodSignatureRecordsThenProcesses() {
        RecordingFactService fake = new RecordingFactService();
        MallLogisticsSimController controller = controllerWith(fake);
        MallLogisticsSimEventBo input = bo();

        R<String> result = controller.simEvent(input.setSignature(sign(input)));

        assertEquals(1, fake.recorded.size(), "验签通过后必须落一条事实");
        assertEquals("EV-CONTRACT-1", fake.recorded.get(0));
        assertEquals(1, fake.processed.size(), "落事实后必须交给推进段");
        assertNotNull(result.getData());
    }

    @Test
    @DisplayName("签名覆盖全部参与字段：改任一字段而不重算签名都必须被拒")
    void everySignedFieldIsCovered() {
        MallLogisticsSimEventBo base = bo();
        String signature = sign(base);
        List<MallLogisticsSimEventBo> tampered = List.of(
                bo().setProviderCode("OTHER").setSignature(signature),
                bo().setProviderEventKey("EV-OTHER").setSignature(signature),
                bo().setWaybillNo("SIMWB-OTHER").setSignature(signature),
                bo().setEventState("DELIVERED").setSignature(signature),
                bo().setEventTime("20260811130000").setSignature(signature));

        for (MallLogisticsSimEventBo item : tampered) {
            RecordingFactService fake = new RecordingFactService();
            controllerWith(fake).simEvent(item);
            assertTrue(fake.recorded.isEmpty(),
                    "改动 " + item.getEventState() + "/" + item.getWaybillNo()
                            + " 后旧签名仍被接受，说明该字段没有进入签名");
        }
    }

    @Test
    @DisplayName("模拟入口必须按开关条件装配：缺省与生产恒不注册")
    void simEndpointIsConditionalOnSwitch() {
        ConditionalOnProperty gate =
                MallLogisticsSimController.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(gate, "模拟物流入口必须条件装配，不得无条件注册");
        assertEquals("mall.logistics-sim.enabled", gate.name()[0]);
        assertEquals("true", gate.havingValue());
    }
}
