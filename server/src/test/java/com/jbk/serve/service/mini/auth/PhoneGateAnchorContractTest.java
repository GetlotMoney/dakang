package com.jbk.serve.service.mini.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绑号闸锚点覆盖合同：MiniPhoneGateTest 只证「判得对」，本类证「该调的地方都调了」——
 * 漏挂不会红，只会静默放行，故用源码扫描。四类锚点：①卡资金流出 ②入账与发卡
 * ③分润出金 ④履约归属（④有 CourierAccess 与 MallFulfillCore 两个解析器，缺一漏整链）。
 */
@DisplayName("绑号闸锚点覆盖合同")
class PhoneGateAnchorContractTest {

    /**
     * 锚点：文件 → {必须挂闸的方法签名片段, scene 关键字}。判据必须精确到方法而非文件：
     * 整文件 contains 对「闸挂到隔壁只读方法」恒绿，而出金方法可以一行闸都没有。
     */
    private static final Map<String, String[]> ANCHORS = new LinkedHashMap<>();

    static {
        // ① 卡资金流出：全仓水卡扣减只有 deductBalance / deductMl 两条 SQL，调用方只有这两处
        ANCHORS.put("src/main/java/com/jbk/serve/service/trade/impl/TradeOrderTxServiceImpl.java",
                new String[] { "public WsOrder createWaterOrder(", "扫码取水扣款" });
        ANCHORS.put("src/main/java/com/jbk/serve/service/delivery/impl/DeliveryOrderTxServiceImpl.java",
                new String[] { "public WsOrder createPaidDeliveryOrder(", "配送下单扣款" });
        // ② 资金入账与发卡：真金白银首次进入平台并转成可兑付预付卡
        ANCHORS.put("src/main/java/com/jbk/serve/service/mini/impl/MiniRechargeServiceImpl.java",
                new String[] { "public MiniRechargeOrderVo create(MiniRechargeCreateBo", "购卡充值" });
        // ② 商城是与充值彼此独立的第二条资金链（走微信支付、不经水卡）
        ANCHORS.put("src/main/java/com/jbk/serve/service/mall/impl/MallOrderServiceImpl.java",
                new String[] { "public MallOrderVo create(Long userId, MallCheckoutBo", "商城下单" });
        // ③ 分润出金：唯一的资金出账方向。必须是 applyWithdraw 而不是只读的 walletFor——
        //    挂错方法时用户看不了余额却提得了现，而整文件判据看不出区别
        ANCHORS.put("src/main/java/com/jbk/serve/service/settlement/impl/IncomeServiceImpl.java",
                new String[] { "public void applyWithdraw(", "分润提现" });
        // ② 商城派单：闸挂在**分配**而不只在配送员取货，否则订单会卡死在一个联系不上的人手里
        ANCHORS.put("src/main/java/com/jbk/serve/service/mall/impl/MallSelfDeliveryServiceImpl.java",
                new String[] { "public MallFulfillVo assign(", "商城分配自营配送员" });
        // ② 机主报修：申报电话被刻意丢弃，账号手机号是工单唯一联系方式
        ANCHORS.put("src/main/java/com/jbk/serve/service/mini/impl/MiniOwnerServiceImpl.java",
                new String[] { "public MiniOwnerServiceVo applyService(", "机主报修申报" });
        // ④ 履约归属——两个准入解析器缺一不可
        ANCHORS.put("src/main/java/com/jbk/serve/service/delivery/impl/CourierAccess.java",
                new String[] { "public EnabledCourier requireEnabledCourier(", "配送员履约" });
        ANCHORS.put("src/main/java/com/jbk/serve/service/mall/impl/MallFulfillCore.java",
                new String[] { "WsCourier requireEnabledCourierByUser(", "商城配送履约" });
    }

    /** 从方法签名截到下一个同缩进方法声明：粗略但够用，整文件扫描对「挂到隔壁方法」全绿。 */
    private static String bodyOf(String src, String declaration) {
        int start = src.indexOf(declaration);
        if (start < 0) {
            return null;
        }
        // 大括号配平取方法体，而不是「找下一个 public/private」——后者对
        // 包级私有方法、内部类、注解都判不准，而判不准的那一次就是漏检的那一次。
        int open = src.indexOf('{', start);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            }
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("每个资金/履约归属锚点都在**正确的方法体内**调用了绑号闸")
    void everyAnchorCallsTheGate() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String[]> e : ANCHORS.entrySet()) {
            Path path = Paths.get(e.getKey());
            if (!Files.exists(path)) {
                offenders.add(e.getKey() + "：文件不存在（锚点被移动或改名，判据已失效）");
                continue;
            }
            String src = Files.readString(path, StandardCharsets.UTF_8);
            String signature = e.getValue()[0];
            String scene = e.getValue()[1];

            String body = bodyOf(src, signature);
            if (body == null) {
                offenders.add(path.getFileName() + "：找不到方法「" + signature
                        + "」，锚点判据已失效（方法被改名或重构？）");
                continue;
            }
            if (!body.contains("phoneGate.requirePhoneBound(")) {
                offenders.add(path.getFileName() + "#" + signature
                        + "：方法体内未调用 requirePhoneBound，该链在游客态下会对未绑号账号放行");
                continue;
            }
            if (!body.contains(scene)) {
                offenders.add(path.getFileName() + "#" + signature + "：缺少场景标识「" + scene
                        + "」，无法确认闸挂在预期的动作上");
            }
        }
        assertTrue(offenders.isEmpty(), "绑号闸锚点缺失：" + offenders);
        assertFalse(ANCHORS.isEmpty(), "锚点表为空，判据失效");
    }

    @Test
    @DisplayName("只读的分润钱包不得挂闸：拦住它等于让游客态用户连自己的余额都看不到")
    void readOnlyWalletMustNotBeGated() throws IOException {
        String src = Files.readString(
                Paths.get("src/main/java/com/jbk/serve/service/settlement/impl/IncomeServiceImpl.java"),
                StandardCharsets.UTF_8);
        String body = bodyOf(src, "public MiniWalletVo walletFor(");
        assertTrue(body != null, "找不到 walletFor，判据失效");
        // 这条与上一条互为方向：上一条保证出金挂了闸，这条保证只读没被误挂。
        // 两条都在才能钉住「挂在哪一个方法」——只有前者时，把闸挪到隔壁只读方法仍然全绿。
        assertFalse(body.contains("phoneGate.requirePhoneBound("),
                "只读钱包被挂了绑号闸：用户看不到自己的分润余额，而这与他绑没绑号无关");
    }

    @Test
    @DisplayName("水卡扣减的调用方没有第三处——有就是新的漏闸口")
    void cardDeductCallersAreExactlyTwo() throws IOException {
        // 闸挂在调用点而非 Mapper 上，因此新增一个扣卡调用方就等于新增一个绕闸入口。
        // 这条把「调用方恰好两处」钉死：多出第三处时必须显式来这里加锚点，而不是静默漏掉。
        List<String> callers = new ArrayList<>();
        Path root = Paths.get("src/main/java");
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith("Mapper.java")) {
                    continue;
                }
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (src.contains(".deductBalance(") || src.contains(".deductMl(")) {
                    callers.add(name);
                }
            }
        }
        assertTrue(callers.contains("TradeOrderTxServiceImpl.java"), "扫码取水扣款方缺失，判据失效");
        assertTrue(callers.contains("DeliveryOrderTxServiceImpl.java"), "配送扣款方缺失，判据失效");
        assertTrue(callers.size() == 2,
                "水卡扣减出现了新的调用方，必须同轮在本测试的 ANCHORS 里登记并挂闸：" + callers);
    }
}
