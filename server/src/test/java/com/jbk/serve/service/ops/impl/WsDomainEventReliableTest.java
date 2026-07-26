package com.jbk.serve.service.ops.impl;

import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.ops.po.WsDomainEvent;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsDomainEventReliableTest {

    @Test
    void reliableFailureEvidenceUsesIndependentTransaction() throws Exception {
        Method method = WsDomainEventServiceImpl.class.getMethod(
                "recordReliable", OpsEnum.EventType.class, String.class, Object.class, Object.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
        assertEquals(Exception.class, transactional.rollbackFor()[0]);
    }

    /**
     * E2E-03 验收 P1-3：带幂等键的可靠落痕必须与业务写入同一事务（REQUIRED）——
     * 业务回滚时审计一并消失，不留「业务失败、审计称成功」的幽灵记录；
     * 关键状态变化的调用方有责任在业务事务内调用（契约见接口 javadoc）。
     */
    @Test
    void reliableOnceEvidenceJoinsBusinessTransaction() throws Exception {
        Method method = WsDomainEventServiceImpl.class.getMethod(
                "recordReliableOnce", OpsEnum.EventType.class, String.class, String.class,
                Object.class, Object.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRED, transactional.propagation());
        assertEquals(Exception.class, transactional.rollbackFor()[0]);
    }

    /** E2E-03 验收 P1-3：显式端口的可靠落痕同为与业务同事务（REQUIRED，语义同上一条）。 */
    @Test
    void reliableOnceAsEvidenceJoinsBusinessTransaction() throws Exception {
        Method method = WsDomainEventServiceImpl.class.getMethod(
                "recordReliableOnceAs", OpsEnum.ActorPortal.class, Long.class, OpsEnum.EventType.class,
                String.class, String.class, Object.class, Object.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRED, transactional.propagation());
        assertEquals(Exception.class, transactional.rollbackFor()[0]);
    }

    /** 落库替身：save 行为可编排（成功/失败/撞幂等键），捕获写入的事件行供身份断言。 */
    private static WsDomainEventServiceImpl stubbedImpl(List<WsDomainEvent> sink, RuntimeException failure,
                                                        boolean saveResult) {
        return new WsDomainEventServiceImpl() {
            @Override
            public boolean save(WsDomainEvent event) {
                if (failure != null) {
                    throw failure;
                }
                sink.add(event);
                return saveResult;
            }
        };
    }

    /** P1-3①：配送员动作经显式端口 API 落 COURIER（portal=4），不再被会话推断记成 USER。 */
    @Test
    void explicitPortalStampsCourierIdentity() {
        List<WsDomainEvent> sink = new ArrayList<>();
        WsDomainEventServiceImpl impl = stubbedImpl(sink, null, true);

        impl.recordAs(OpsEnum.ActorPortal.COURIER, 70L, OpsEnum.EventType.DELIVERY_NODE,
                "DT-TEST", null, "配送异常已登记");
        impl.recordReliableOnceAs(OpsEnum.ActorPortal.COURIER, 70L, OpsEnum.EventType.DELIVERY_NODE,
                "DT-TEST", "DNODE_ACCEPT:DT-TEST", "1:待接单", "2:已接单");

        assertEquals(2, sink.size());
        for (WsDomainEvent event : sink) {
            assertEquals(OpsEnum.ActorPortal.COURIER.getValue(), event.getActorPortal(), "portal 必须为配送端");
            assertEquals(OpsEnum.ActorPortal.COURIER.getDesc(), event.getActorRole());
            assertEquals(70L, event.getActorId());
        }
        assertEquals("DNODE_ACCEPT:DT-TEST", sink.get(1).getBizIdempotencyKey());
    }

    /** P1-3②：关键审计 fail-closed——写入失败/落库返回 false 必须抛出，绝不静默吞掉。 */
    @Test
    void reliableOnceAsThrowsOnWriteFailureInsteadOfSwallowing() {
        WsDomainEventServiceImpl saveFalse = stubbedImpl(new ArrayList<>(), null, false);
        assertThrows(IllegalStateException.class, () -> saveFalse.recordReliableOnceAs(
                OpsEnum.ActorPortal.COURIER, 70L, OpsEnum.EventType.DELIVERY_NODE,
                "DT-TEST", "DNODE_SIGN:DT-TEST", null, "5:已签收"));

        WsDomainEventServiceImpl saveThrows = stubbedImpl(new ArrayList<>(),
                new IllegalStateException("db down"), true);
        assertThrows(IllegalStateException.class, () -> saveThrows.recordReliableOnceAs(
                OpsEnum.ActorPortal.COURIER, 70L, OpsEnum.EventType.DELIVERY_NODE,
                "DT-TEST", "DNODE_SIGN:DT-TEST", null, "5:已签收"));

        // 分界对照：展示型 recordAs 保持 quietly——写入失败不阻断主业务
        assertDoesNotThrow(() -> saveThrows.recordAs(
                OpsEnum.ActorPortal.COURIER, 70L, OpsEnum.EventType.DELIVERY_NODE,
                "DT-TEST", null, "展示型留痕"));
    }

    /** 撞幂等键替身：save 恒撞唯一键，读回结果可编排（existingRow=null 模拟行读不回）。 */
    private static WsDomainEventServiceImpl duplicateHitImpl(WsDomainEvent existingRow) {
        return new WsDomainEventServiceImpl() {
            @Override
            public boolean save(WsDomainEvent event) {
                throw new DuplicateKeyException("uk_domain_event_biz_key");
            }

            @Override
            public WsDomainEvent getOne(
                    com.baomidou.mybatisplus.core.conditions.Wrapper<WsDomainEvent> queryWrapper,
                    boolean throwEx) {
                return existingRow;
            }
        };
    }

    private static WsDomainEvent existingRow(String eventKey, Integer portal, Long actorId, String payload) {
        WsDomainEvent row = new WsDomainEvent();
        row.setEventType(OpsEnum.EventType.DELIVERY_NODE.getValue());
        row.setEventKey(eventKey);
        row.setActorId(actorId);
        row.setActorPortal(portal);
        row.setEventPayload(payload);
        row.setBizIdempotencyKey("DNODE_SIGN:DT-TEST");
        return row;
    }

    /**
     * P1-3③：撞业务幂等键不等于幂等成功——必须读回既有行核验语义一致性：
     * 同语义（time 除外）幂等放行；行读不回或语义（key/portal/payload）不一致必须 fail-closed 抛出，
     * 盲信撞键会让伪造事件顶掉真实履约审计。真库真事务版本见 WsDomainEventTxDbTest。
     */
    @Test
    void duplicateKeyHitVerifiesExistingRowSemanticsInsteadOfBlindTrust() {
        // 行读不回：无从核验 → fail-closed
        assertThrows(JbkException.class, () -> duplicateHitImpl(null).recordReliableOnceAs(
                OpsEnum.ActorPortal.COURIER, 70L, OpsEnum.EventType.DELIVERY_NODE,
                "DT-TEST", "DNODE_SIGN:DT-TEST", "4:已送达", "5:已签收"));

        // 同语义（仅 time 不同）：幂等放行
        WsDomainEventServiceImpl sameSemantics = duplicateHitImpl(existingRow("DT-TEST",
                OpsEnum.ActorPortal.COURIER.getValue(), 70L,
                "{\"old\":\"4:已送达\",\"new\":\"5:已签收\",\"time\":\"20260101000000\"}"));
        assertDoesNotThrow(() -> sameSemantics.recordReliableOnceAs(
                OpsEnum.ActorPortal.COURIER, 70L, OpsEnum.EventType.DELIVERY_NODE,
                "DT-TEST", "DNODE_SIGN:DT-TEST", "4:已送达", "5:已签收"));

        // 语义不一致（伪造 payload/身份被占键）：fail-closed
        WsDomainEventServiceImpl poisoned = duplicateHitImpl(existingRow("BOGUS-EVENT",
                OpsEnum.ActorPortal.USER.getValue(), 9L,
                "{\"old\":\"污染\",\"new\":\"伪造\",\"time\":\"20260101000000\"}"));
        JbkException ex = assertThrows(JbkException.class, () -> poisoned.recordReliableOnceAs(
                OpsEnum.ActorPortal.COURIER, 70L, OpsEnum.EventType.DELIVERY_NODE,
                "DT-TEST", "DNODE_SIGN:DT-TEST", "4:已送达", "5:已签收"));
        assertTrue(ex.getMessage().contains("语义不一致"), "实际=" + ex.getMessage());
    }
}
