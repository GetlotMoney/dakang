package com.jbk.tool.exception;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断类异常不得把内部状态弹给用户。
 *
 * <p>{@code GlobalExceptionHandler} 把 {@code JbkException.getMsg()} 原样写进响应体，
 * 前端直接 toast。对「余额不足」这类业务拒绝，原文就是用户要看的；但对不变式守卫
 * ——「支付单与订单共键错位」「前态漂移，账本断裂」——原文是给排障看的诊断，
 * 弹给用户既看不懂也无法处置，只会在演示或真实交易时造成恐慌。</p>
 *
 * <p>处置不是把诊断改模糊（那会毁掉排障精度，而接真后这些守卫恰恰最要紧），
 * 而是分流：{@link JbkException#internal(String)} 保留精确原文进日志，
 * 对外统一换成 {@link JbkException#INTERNAL_FALLBACK_MSG}。</p>
 */
class InternalDiagnosticMessageTest {

    private static final Path REPO = locateRepoRoot();

    private static Path locateRepoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve("client")) && Files.isDirectory(cur.resolve("server"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("无法定位仓库根");
    }

    @Test
    void internalKeepsDiagnosticButMarksItNonUserFacing() {
        JbkException e = JbkException.internal("支付单与订单共键错位，拒绝");
        // 诊断原文必须完整保留：日志与测试断言都靠它
        assertEquals("支付单与订单共键错位，拒绝", e.getMsg());
        assertFalse(e.isUserFacing(), "internal() 构造的异常不得标为可展示");
    }

    @Test
    void ordinaryExceptionStaysUserFacing() {
        // 既有构造函数行为不得改变：绝大多数业务拒绝的原文就是要给用户看的
        assertTrue(new JbkException("水卡余额不足").isUserFacing());
        assertTrue(new JbkException("套餐已下架", 500).isUserFacing());
    }

    @Test
    void fallbackMessageLeaksNothingAndTellsUserWhatToDo() {
        String m = JbkException.INTERNAL_FALLBACK_MSG;
        for (String leak : new String[] { "共键", "前态", "幂等", "事实", "落库", "适配器", "快照", "契约" }) {
            assertFalse(m.contains(leak), "兜底文案泄漏了内部术语「" + leak + "」");
        }
        // 不能只说「失败了」——要给出路
        assertTrue(m.contains("重试") || m.contains("客服"), "兜底文案没告诉用户下一步能做什么");
    }

    /**
     * 支付链上的诊断守卫必须走 internal 通道。
     *
     * <p>这两个类是用户下单/支付时真正会经过的路径，一旦守卫触发就会直接弹到小程序上。
     * 用源级断言而不是行为断言，是因为要禁止的是「将来有人在这里新写一条裸 JbkException」。</p>
     */
    @Test
    void paymentGuardsUseInternalChannel() throws IOException {
        Path base = REPO.resolve("server/src/main/java/com/jbk/serve/service/mini/impl");
        Pattern raw = Pattern.compile("new JbkException\\(\\s*\"([^\"]*)\"");
        // 只盯诊断词：业务规则拒绝（余额不足、订单不存在）仍应原样展示
        List<String> diagnostic = List.of("共键", "前态", "适配器", "落库", "不自洽", "事实键");
        List<String> offenders = new ArrayList<>();
        for (String name : new String[] { "MiniPaySimServiceImpl.java", "MiniRechargeServiceImpl.java" }) {
            Path f = base.resolve(name);
            assertTrue(Files.exists(f), "找不到 " + f);
            String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            Matcher m = raw.matcher(src);
            while (m.find()) {
                String msg = m.group(1);
                if (diagnostic.stream().anyMatch(msg::contains)) {
                    offenders.add(name + " → " + msg);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些诊断消息仍走裸 JbkException，会原样弹给用户；请改用 JbkException.internal()："
                        + offenders);
    }

    /** handler 必须真的做了分流，而不是把 getMsg() 直接回给前端。 */
    @Test
    void handlerSubstitutesNonUserFacingMessage() throws IOException {
        Path handler = REPO.resolve(
                "server/src/main/java/com/jbk/tool/exception/GlobalExceptionHandler.java");
        String src = new String(Files.readAllBytes(handler), StandardCharsets.UTF_8);
        assertTrue(src.contains("isUserFacing()"), "handler 未按 userFacing 分流");
        assertTrue(src.contains("INTERNAL_FALLBACK_MSG"), "handler 未使用兜底文案");
        // 日志必须仍然拿到精确原文，否则排障信息就丢了
        assertTrue(src.contains("log.error") && src.contains("e.getMsg()"),
                "精确诊断必须仍然完整进日志");
    }

    @Test
    @SuppressWarnings("unused")
    void repoLayoutSanity() throws IOException {
        try (Stream<Path> s = Files.list(REPO)) {
            assertTrue(s.findAny().isPresent());
        }
    }
}
