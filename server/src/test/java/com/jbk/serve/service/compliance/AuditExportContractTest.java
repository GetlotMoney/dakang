package com.jbk.serve.service.compliance;

import com.jbk.tool.consts.compliance.ComplianceEnum;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计导出的<b>能力边界</b>守卫（B23）。
 *
 * <p>这几条钉的不是普通业务规则，而是"平台不得伪造导出成功"这条边界：
 * 文件生成依赖对象存储且尚未接入，任何把任务推进到「已生成」或写入文件摘要的代码，
 * 都会让合规页展示一份并不存在的导出结果——比没有导出功能更糟。</p>
 *
 * <p>用源级断言而非行为断言，是因为要禁止的是"未来有人写出这样的代码"，
 * 而不是"当前某次调用的返回值"；行为测试盖不住尚未存在的写入路径。</p>
 */
class AuditExportContractTest {

    /** 仓库根向上查找：Surefire 与 IDE runner 的 cwd 不同，写死相对路径会红在找不到文件上。 */
    private static final Path REPO = locateRepoRoot();

    private static Path locateRepoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            if (Files.isDirectory(cur.resolve("client")) && Files.isDirectory(cur.resolve("server"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("无法从 " + Path.of("").toAbsolutePath() + " 定位仓库根");
    }

    private static final Path SERVER_MAIN = REPO.resolve("server/src/main/java");
    private static final Path SERVICE_IMPL = SERVER_MAIN.resolve(
            "com/jbk/serve/service/compliance/impl/WsAuditExportTaskServiceImpl.java");
    private static final Path CONTROLLER_DIR = SERVER_MAIN.resolve("com/jbk/serve/controller");
    private static final Path CLIENT_API = REPO.resolve("client/src/api/compliance.ts");
    private static final Path INIT_SQL = REPO.resolve("deploy/mysql/init/02-ws-business.sql");

    private String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "找不到 " + p.toAbsolutePath());
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 遍历后端全部主代码，返回 (相对路径 → 源码文本)。 */
    private Map<String, String> allServerSources() throws IOException {
        try (Stream<Path> paths = Files.walk(SERVER_MAIN)) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toMap(
                            p -> SERVER_MAIN.relativize(p).toString(),
                            p -> {
                                try {
                                    return Files.readString(p, StandardCharsets.UTF_8);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }));
        }
    }

    /**
     * 服务端不得存在把状态置为 DONE/EXPIRED 或把文件摘要/过期时间<b>写入库</b>的路径。
     *
     * <p>只禁写入形式，不禁读取：{@code toVo} 里的 {@code setFileDigest(task.getFileDigest())}
     * 是把库里的值投影给页面（页面据此判断"无文件、不渲染下载入口"），必须保留。
     * 首版断言写成裸 {@code "setFileDigest("} 把这条读路径一并判红了。</p>
     */
    @Test
    void serviceNeverProducesGeneratedState() throws IOException {
        // 扫描面是**整个后端主代码**，不是 compliance 包：IService/BaseMapper 让
        // updateById/saveOrUpdate 成为该 Bean 的公开方法，任何新类注入本 Service 后
        // 调 updateById(task.setTaskStatus(4)) 都能绕过——而对象存储接入时最自然的写法
        // 恰恰就是新开一个类。只盯着 impl 一个文件的守卫挡不住它。
        Map<String, String> sources = allServerSources();
        String[] forbidden = {
                // 终态枚举：一旦出现即意味着有代码在推进到"已生成/已过期"
                "AuditExportStatus.DONE", "AuditExportStatus.EXPIRED",
                // lambdaUpdate 写入形式
                "set(WsAuditExportTask::getFileDigest", "set(WsAuditExportTask::getExpireTime",
                // PO setter 写入形式
                ".setFileDigest(", ".setExpireTime(", ".setTaskStatus(",
        };
        for (var entry : sources.entrySet()) {
            String file = entry.getKey();
            String src = entry.getValue();
            // 只看"碰得到审计导出实体或其状态枚举"的文件。setTaskStatus/setExpireTime 这类
            // setter 名在别的领域也有（如配送任务），不限定范围会把无关模块一并判红
            boolean touchesAuditExport = src.contains("WsAuditExportTask")
                    || src.contains("AuditExportStatus");
            if (!touchesAuditExport) {
                continue;
            }
            // 枚举定义自身与 VO/PO 的字段声明不算写入路径
            boolean isDeclaration = file.endsWith("ComplianceEnum.java")
                    || file.endsWith("WsAuditExportTask.java")
                    || file.endsWith("WsAuditExportTaskVo.java");
            // 服务实现是唯一允许写状态的地方（置 PENDING），文件字段在那里也只做只读投影
            boolean isOwningService = file.endsWith("WsAuditExportTaskServiceImpl.java");
            for (String token : forbidden) {
                if (!src.contains(token)) {
                    continue;
                }
                assertTrue(isDeclaration || isOwningService,
                        file + " 出现了 " + token
                                + "——审计导出任务的状态与文件字段只能由 WsAuditExportTaskServiceImpl 写，"
                                + "对象存储未接入前更不得产生已生成终态或文件摘要");
            }
        }

        // 服务实现内部再精确核一遍：只允许 PENDING，且文件字段只读不写
        String impl = read(SERVICE_IMPL);
        assertFalse(impl.contains("AuditExportStatus.DONE") || impl.contains("AuditExportStatus.EXPIRED"),
                "服务实现不得引用 DONE/EXPIRED 终态");
        assertFalse(impl.contains("task.setFileDigest(") || impl.contains("task.setExpireTime("),
                "服务实现不得向 PO 写入文件摘要或过期时间");
        // 反面确认：读取投影必须仍在，否则页面拿不到"文件为空"这个事实
        assertTrue(impl.contains("setFileDigest(task.getFileDigest())"),
                "VO 必须仍下发 fileDigest（值为空正是「文件未生成」的证据）");
    }

    /**
     * 枚举文案与字典 1382 的 DICT_LABEL 必须一致。
     *
     * <p>服务端 {@code taskStatusName} 实际取自枚举常量而非运行时查字典，于是文案有两份
     * 独立副本。不做断言的话，运维改了字典标签就会出现"页面一套说法、字典另一套说法"——
     * 正是 R-204 要消灭的那种病，只不过换了个地方长出来。</p>
     */
    @Test
    void statusEnumLabelsMatchDictionarySeed() throws IOException {
        String initSql = read(INIT_SQL);
        for (ComplianceEnum.AuditExportStatus status : ComplianceEnum.AuditExportStatus.values()) {
            String row = "'1382', " + status.getValue() + ", " + status.getValue()
                    + ", '" + status.getDesc() + "'";
            String firstRow = "SELECT '1382' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '"
                    + status.getDesc() + "' AS DICT_LABEL";
            assertTrue(initSql.contains(row) || initSql.contains(firstRow),
                    "字典 1382 缺少与枚举 " + status.name() + "(" + status.getValue() + ","
                            + status.getDesc() + ") 对应的行——枚举与字典文案已漂移");
        }
    }

    /** 初始状态只能是 PENDING，且不接受调用方指定。 */
    @Test
    void applyAlwaysStartsPending() throws IOException {
        String src = read(SERVICE_IMPL);
        assertTrue(src.contains("AuditExportStatus.PENDING.getValue()"),
                "申请必须显式置为待生成");
        // 首版这里还有一支 src.contains("bo.getTaskStatus()) ")，尾随空格导致恒 false，
        // 属死断言（实际文本在该括号后直接换行），已删除
        assertFalse(src.contains("setTaskStatus(bo.get"),
                "任务状态不得取自请求体——否则申请人可自行把任务标成已完成");
    }

    /** 重试必须是前态 CAS（eq 失败态），不能读出后无条件写回。 */
    @Test
    void retryUsesFailedStateCas() throws IOException {
        String src = read(SERVICE_IMPL);
        int cas = src.indexOf("eq(WsAuditExportTask::getTaskStatus, ComplianceEnum.AuditExportStatus.FAILED");
        assertTrue(cas > 0, "重试必须以「当前是失败态」为 CAS 条件，否则并发重试与状态漂移都挡不住");
        assertTrue(src.contains("仅失败的任务可以重试"), "CAS 影响 0 行时必须明确拒绝");
    }

    /** 脱敏规则由服务端固化，不接受调用方传入。 */
    @Test
    void maskingRuleIsServerFixed() throws IOException {
        String src = read(SERVICE_IMPL);
        assertTrue(src.contains("private static final String MASKING_RULE"),
                "脱敏规则必须是服务端常量");
        assertFalse(src.contains("bo.getMaskingRule"),
                "脱敏规则不得取自请求体——可指定即等于导出范围不受控");
    }

    /** 导出范围走白名单，前后端同源。 */
    @Test
    void exportScopeWhitelistMatchesClient() throws IOException {
        String server = read(SERVICE_IMPL);
        String client = read(CLIENT_API);
        for (String scope : new String[] {
                "操作日志", "登录日志", "订单追溯", "设备事件", "指令回执", "领域事件" }) {
            assertTrue(server.contains("\"" + scope + "\""), "服务端白名单缺少 " + scope);
            assertTrue(client.contains("'" + scope + "'"), "前端可选项缺少 " + scope);
        }
        assertTrue(server.contains("不支持的导出范围"), "白名单外的范围必须被拒绝");
    }

    /**
     * 不得提供下载端点：一个必然返回空的下载入口会被读成"导出已可用"。
     * 扫整个 controller 目录，新开一个 AuditExportDownloadController 同样会被抓住。
     */
    @Test
    void noDownloadEndpointExposed() throws IOException {
        try (Stream<Path> paths = Files.walk(CONTROLLER_DIR)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (!src.contains("auditExport") && !src.contains("AuditExport")) {
                    continue;
                }
                assertFalse(src.contains("/download") || src.contains("download("),
                        p.getFileName() + " 暴露了审计导出下载端点——对象存储接入前不允许");
            }
        }
    }

    /**
     * 封住源级断言够不着的三条写入通道：Mapper 注解 SQL、XML 映射、PO 的
     * {@code FieldStrategy.NEVER}——覆盖通道而非措辞。落盘行为由
     * AuditExportDbTest.ormCannotWriteFileDigestOrExpireTime 在真库证明，两者互补。
     */
    @Test
    void noWriteChannelBypassesTheFileFieldBan() throws Exception {
        // 1. Mapper 接口不得出现任何写注解——本接口只允许只读的 @Select
        for (java.lang.reflect.Method m
                : com.jbk.serve.mapper.compliance.WsAuditExportTaskMapper.class.getDeclaredMethods()) {
            for (java.lang.annotation.Annotation a : m.getAnnotations()) {
                String an = a.annotationType().getSimpleName();
                assertFalse(an.equals("Update") || an.equals("Insert") || an.equals("Delete")
                                || an.endsWith("Provider"),
                        "WsAuditExportTaskMapper." + m.getName() + " 挂了写注解 @" + an
                                + "。本 Mapper 只允许只读查询；状态迁移统一走 Service 的前态 CAS，"
                                + "而 FILE_DIGEST/EXPIRE_TIME 在对象存储接入前不允许有任何写入通道");
            }
        }

        // 2. 不得有 XML 映射碰这张表
        Path mapperXmlDir = REPO.resolve("server/src/main/resources/mapper");
        if (Files.isDirectory(mapperXmlDir)) {
            try (Stream<Path> paths = Files.walk(mapperXmlDir)) {
                for (Path p : paths.filter(f -> f.toString().endsWith(".xml")).toList()) {
                    assertFalse(Files.readString(p, StandardCharsets.UTF_8)
                                    .contains("ws_audit_export_task"),
                            p.getFileName() + " 用 XML 直接操作 ws_audit_export_task，"
                                    + "绕过了 PO 上的 ORM 禁写开关");
                }
            }
        }

        // 3. PO 的禁写开关仍在位——它是 ORM 层唯一的兜底，注解被摘掉时上面两条都拦不住
        for (String field : new String[] { "fileDigest", "expireTime" }) {
            var tf = com.jbk.tool.data.compliance.po.WsAuditExportTask.class
                    .getDeclaredField(field)
                    .getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class);
            assertEquals(com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, tf.insertStrategy(),
                    field + " 的 insertStrategy 不再是 NEVER——ORM 层已能写出该列");
            assertEquals(com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, tf.updateStrategy(),
                    field + " 的 updateStrategy 不再是 NEVER——ORM 层已能写出该列");
        }
    }

    /** 枚举值与字典 1382 的定义一致。 */
    @Test
    void statusEnumMatchesDictionary() {
        assertEquals(1, ComplianceEnum.AuditExportStatus.PENDING.getValue());
        assertEquals(2, ComplianceEnum.AuditExportStatus.RUNNING.getValue());
        assertEquals(3, ComplianceEnum.AuditExportStatus.FAILED.getValue());
        assertEquals(4, ComplianceEnum.AuditExportStatus.DONE.getValue());
        assertEquals(5, ComplianceEnum.AuditExportStatus.EXPIRED.getValue());
        assertEquals("待生成", ComplianceEnum.AuditExportStatus.getType(1).getDesc());
        assertThrows(RuntimeException.class, () -> ComplianceEnum.AuditExportStatus.getType(9));
    }
}
