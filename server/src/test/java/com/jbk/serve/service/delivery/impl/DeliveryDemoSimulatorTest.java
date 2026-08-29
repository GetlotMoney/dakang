package com.jbk.serve.service.delivery.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryDemoSimulatorTest {

    @Test
    void stageDelayUsesBusinessTimeAndIncludesExactBoundary() {
        assertFalse(DeliveryDemoSimulator.stageReady("20260826230000", "20260826230003", 4));
        assertTrue(DeliveryDemoSimulator.stageReady("20260826230000", "20260826230004", 4));
        assertFalse(DeliveryDemoSimulator.stageReady("bad", "20260826230004", 4));
    }

    @Test
    void demoPhotosRemainPngAndAreUniquePerTaskAndType() {
        byte[] first = DeliveryDemoSimulator.demoPng("DT-1", 1);
        byte[] second = DeliveryDemoSimulator.demoPng("DT-1", 2);
        byte[] otherTask = DeliveryDemoSimulator.demoPng("DT-2", 1);
        assertArrayEquals(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47},
                new byte[] {first[0], first[1], first[2], first[3]});
        assertNotEquals(java.util.Arrays.hashCode(first), java.util.Arrays.hashCode(second));
        assertNotEquals(java.util.Arrays.hashCode(first), java.util.Arrays.hashCode(otherTask));
    }
}
