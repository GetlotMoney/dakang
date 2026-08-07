package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.tool.data.user.po.WsUser;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2-AUTH 身份层<b>真实 MySQL</b>集成测试（复审 P1-1/P0-2）：Testcontainers 起 mysql:8.0，
 * 用真实 MyBatis-Plus + 迁移后表结构，复现 @TableLogic 隐藏删除账号、CAS 抢绑影响行数、唯一约束并发。
 * 无 Docker 环境自动跳过。迁移脚本本身的正确性由 deploy/mysql/migrations/verify-l2-auth.sh 覆盖。
 */
@Testcontainers(disabledWithoutDocker = true)
class MiniAuthIdentityDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static SqlSessionFactory factory;

    /** 迁移后表结构（uk_user_phone + uk_user_wechat_xcx_openid + openid utf8mb4_bin + gender 可空）。 */
    private static final String DDL = """
            CREATE TABLE ws_user (
              ID BIGINT PRIMARY KEY AUTO_INCREMENT,
              USER_NAME VARCHAR(50),
              USER_GENDER TINYINT NULL,
              USER_PHONE VARCHAR(20),
              USER_IDENTITY_CIPHER VARCHAR(500),
              USER_AVATAR VARCHAR(500),
              DISABLED_FLAG TINYINT,
              USER_STATUS TINYINT,
              POINTS INT,
              WECHAT_XCX_OPENID VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
              CHANNEL_USER_ID BIGINT,
              REFERRER_USER_ID BIGINT,
              PROMO_CODE VARCHAR(20),
              OWN_INVITE_CODE VARCHAR(12) NULL,
              DATA_STATUS TINYINT DEFAULT 0,
              CREATE_BY BIGINT, CREATE_TIME VARCHAR(20), UPDATE_BY BIGINT, UPDATE_TIME VARCHAR(20),
              UNIQUE KEY uk_user_phone (USER_PHONE),
              UNIQUE KEY uk_user_wechat_xcx_openid (WECHAT_XCX_OPENID)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    @BeforeAll
    static void bootstrap() throws Exception {
        DataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Connection c = ds.getConnection(); var st = c.createStatement()) {
            st.execute(DDL);
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setLogicDeleteValue("1");
        dbConfig.setLogicNotDeleteValue("0");
        globalConfig.setDbConfig(dbConfig);
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.addMapper(WsUserMapper.class);
        configuration.addMapper(WsUserIdentityMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    static void teardown() {
        factory = null;
    }

    @BeforeEach
    void clean() {
        try (SqlSession s = factory.openSession(true); var st = s.getConnection().createStatement()) {
            st.execute("TRUNCATE TABLE ws_user");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WsUser row(String name, String phone, String openid, int dataStatus, int disabled, int status) {
        WsUser u = new WsUser();
        u.setUserName(name);
        u.setUserGender(null);
        u.setUserPhone(phone);
        u.setWechatXcxOpenid(openid);
        u.setDisabledFlag(disabled);
        u.setUserStatus(status);
        u.setPoints(0);
        u.setDataStatus(dataStatus);
        u.setCreateBy(1L);
        u.setCreateTime("20260721000000");
        u.setUpdateBy(1L);
        u.setUpdateTime("20260721000000");
        return u;
    }

    // P1-1：逻辑删除账号——普通 BaseMapper（@TableLogic）看不见，身份 Mapper 跨状态可见
    @Test
    void tableLogicHidesDeletedButIdentityMapperSeesIt() {
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            WsUserMapper base = s.getMapper(WsUserMapper.class);
            identity.insertIdentityUser(row("del", "13500000000", "oDEL", 1, 1, 1)); // DATA_STATUS=1

            // BaseMapper 自动追加 DATA_STATUS=0 → 查不到（这正是旧代码把已删除 openid 误判为 UNBOUND 的根因）。
            List<WsUser> viaBase = base.selectList(
                    Wrappers.<WsUser>lambdaQuery().eq(WsUser::getWechatXcxOpenid, "oDEL"));
            assertTrue(viaBase.isEmpty(), "@TableLogic 应隐藏逻辑删除账号");

            // 身份 Mapper 跨状态可见 → 服务层据此 fail-closed 拒绝，不进 UNBOUND。
            List<WsUser> viaIdentityOpenid = identity.selectByOpenidIncludingDeleted("oDEL");
            assertEquals(1, viaIdentityOpenid.size());
            assertEquals(1, viaIdentityOpenid.get(0).getDataStatus());

            List<WsUser> viaIdentityPhone = identity.selectByPhoneIncludingDeleted("13500000000");
            assertEquals(1, viaIdentityPhone.size());
        }
    }

    // P1-1：openid BINARY 大小写敏感精确匹配
    @Test
    void openidMatchIsCaseSensitive() {
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            identity.insertIdentityUser(row("u", "13511110000", "oCaseAAA", 0, 1, 1));
            assertEquals(1, identity.selectByOpenidIncludingDeleted("oCaseAAA").size());
            assertTrue(identity.selectByOpenidIncludingDeleted("ocaseaaa").isEmpty(), "openid 应大小写敏感");
        }
    }

    // P0-2：CAS 抢绑——两个不同 openid 并发绑同一手机号，只有一个影响 1 行，最终归属唯一
    @Test
    void concurrentCasBindOnlyOneWins() throws Exception {
        long phoneUserId;
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            WsUser u = row("phoneOnly", "13900002222", null, 0, 1, 1); // openid 未绑
            identity.insertIdentityUser(u);
            phoneUserId = u.getId();
        }
        assertNotNull(phoneUserId);

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        List<String> winners = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        String[] openids = {"oWIN-A", "oWIN-B"};
        for (String openid : openids) {
            pool.submit(() -> {
                try (SqlSession s = factory.openSession(true)) {
                    WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
                    ready.countDown();
                    go.await();
                    int affected = identity.bindOpenidToUsablePhoneUser(
                            phoneUserId, "13900002222", openid, 1L, "20260721000000");
                    if (affected == 1) {
                        successCount.incrementAndGet();
                        winners.add(openid);
                    }
                } catch (Exception ignored) {
                    // 冲突分支不计成功
                }
                return null;
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, successCount.get(), "并发 CAS 只能一个成功");
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            List<WsUser> rows = identity.selectByPhoneIncludingDeleted("13900002222");
            assertEquals(1, rows.size());
            assertEquals(winners.get(0), rows.get(0).getWechatXcxOpenid(), "最终归属为胜出的 openid");
        }
    }

    // P0-2：手机号唯一约束——两个线程并发建同手机号用户，只有一个成功
    @Test
    void concurrentInsertSamePhoneOnlyOneSucceeds() throws Exception {
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        String[] openids = {"oNEW-A", "oNEW-B"};
        for (String openid : openids) {
            pool.submit(() -> {
                try (SqlSession s = factory.openSession(true)) {
                    WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
                    ready.countDown();
                    go.await();
                    identity.insertIdentityUser(row("new", "13900003333", openid, 0, 1, 1));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    // 唯一键冲突 → 失败，不计成功
                }
                return null;
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, ok.get(), "手机号唯一约束下并发建户只能一个成功");
    }

    // P0-2：CAS 不会绑到禁用/删除的手机号用户（影响 0 行）
    @Test
    void casDoesNotBindDisabledPhoneUser() {
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            WsUser disabled = row("disabled", "13900004444", null, 0, 2, 1); // DISABLED_FLAG=2
            identity.insertIdentityUser(disabled);
            int affected = identity.bindOpenidToUsablePhoneUser(
                    disabled.getId(), "13900004444", "oX", 1L, "20260721000000");
            assertEquals(0, affected, "禁用手机号用户不得被 CAS 绑定");
        }
    }

    // 仅微信身份建号：多个手机号为 NULL 的账号可共存（MySQL 唯一索引忽略 NULL）
    @Test
    void multipleNullPhoneUsersCoexistUnderUniqueKey() {
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            identity.insertIdentityUser(row("wx1", null, "oNULL-1", 0, 1, 1));
            identity.insertIdentityUser(row("wx2", null, "oNULL-2", 0, 1, 1));
            identity.insertIdentityUser(row("wx3", null, "oNULL-3", 0, 1, 1));
            assertEquals(1, identity.selectByOpenidIncludingDeleted("oNULL-2").size());
            assertNull(identity.selectByOpenidIncludingDeleted("oNULL-2").get(0).getUserPhone());
        }
    }

    // 反证：写空串而非 NULL 时第二个未绑用户就撞 uk_user_phone —— 这正是应用层禁止空串的原因
    @Test
    void blankPhoneCollidesOnUniqueKey() {
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            identity.insertIdentityUser(row("blank1", "", "oBLANK-1", 0, 1, 1));
            assertThrows(Exception.class,
                    () -> identity.insertIdentityUser(row("blank2", "", "oBLANK-2", 0, 1, 1)),
                    "空串手机号之间会互撞唯一键，必须写 NULL");
        }
    }

    // 补绑 CAS：手机号为 NULL 才可写入，且只写一次
    @Test
    void bindPhoneCasOnlyAppliesToPhonelessUser() {
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            WsUser phoneless = row("wx", null, "oBIND-1", 0, 1, 1);
            identity.insertIdentityUser(phoneless);

            assertEquals(1, identity.bindPhoneToPhonelessUser(
                    phoneless.getId(), "13900005555", 1L, "20260801000000"), "未绑号应补绑成功");
            // 已绑号后再补绑 → 影响 0 行（换绑必须另走身份复核，不从此路进）
            assertEquals(0, identity.bindPhoneToPhonelessUser(
                    phoneless.getId(), "13900006666", 1L, "20260801000000"), "已绑号不得被本路径改号");

            List<WsUser> rows = identity.selectByOpenidIncludingDeleted("oBIND-1");
            assertEquals("13900005555", rows.get(0).getUserPhone());
        }
    }

    // 改名守卫：占位名才改，已被用户改过的昵称绝不覆盖
    @Test
    void renameOnlyAppliesToPlaceholderName() {
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            WsUser placeholder = row("微信用户", null, "oNAME-1", 0, 1, 1);
            identity.insertIdentityUser(placeholder);
            assertEquals(1, identity.renamePlaceholderUserName(
                    placeholder.getId(), "微信用户", "微信用户0001", 1L, "20260801000000"));

            // 已改过名（不再是占位值）→ 影响 0 行，用户自定义昵称不被覆盖
            assertEquals(0, identity.renamePlaceholderUserName(
                    placeholder.getId(), "微信用户", "微信用户9999", 1L, "20260801000000"));

            assertEquals("微信用户0001",
                    identity.selectByOpenidIncludingDeleted("oNAME-1").get(0).getUserName());
        }
    }

    // 补绑 CAS 不会作用于禁用/删除账号
    @Test
    void bindPhoneCasSkipsUnusableUser() {
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            WsUser deleted = row("gone", null, "oBIND-2", 1, 1, 1); // DATA_STATUS=1
            identity.insertIdentityUser(deleted);
            assertEquals(0, identity.bindPhoneToPhonelessUser(
                    deleted.getId(), "13900007777", 1L, "20260801000000"), "逻辑删除账号不得补绑");
        }
    }

    // 并发同 openid 建号：唯一键只放行一个，另一路重读（锁定读）拿得到胜出行
    @Test
    void concurrentPhonelessRegisterOnlyOneRowSurvives() throws Exception {
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try (SqlSession s = factory.openSession(true)) {
                    WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
                    ready.countDown();
                    go.await();
                    identity.insertIdentityUser(row("wx", null, "oRACE-1", 0, 1, 1));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    // 唯一键冲突 → 不计成功
                }
                return null;
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, ok.get(), "同 openid 并发建号只能一个成功");
        try (SqlSession s = factory.openSession(true)) {
            WsUserIdentityMapper identity = s.getMapper(WsUserIdentityMapper.class);
            List<WsUser> winner = identity.selectByOpenidForUpdate("oRACE-1");
            assertEquals(1, winner.size(), "锁定读应能拿到胜出行");
            assertNotNull(winner.get(0).getId());
        }
    }
}
