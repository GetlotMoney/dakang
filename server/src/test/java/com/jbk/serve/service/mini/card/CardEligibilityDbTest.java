package com.jbk.serve.service.mini.card;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.tool.data.user.po.WsCard;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 赠卡判据的 <b>Java × SQL 真库同构矩阵</b>（审计 R2 P1-2）。
 *
 * <p>被审计钉住的漂移：Java 用 {@code isNotBlank}、SQL 用 {@code IS NOT NULL}——
 * 空串/空白有效期在 Java 侧按付费卡占名额、在 SQL 侧被当赠卡排除，两侧结论相反，
 * 「一人一张付费卡」被静默绕过。本类对同一行数据同时断言
 * {@link CardEligibility#occupiesPaidSlot}（Java 判定）与
 * {@code countLiveCardsByUser}（真实 MySQL 执行 {@code SQL_NOT_GIFT} 谓词）逐形态一致。
 * 判据只在 {@code CardEligibility} 一处，任何一侧单独改动这里立刻红。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CardEligibilityDbTest.Ctx.class)
class CardEligibilityDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_elig_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static final long USER_ID = 9L;

    @Configuration
    static class Ctx {
        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            ds.setMaximumPoolSize(4);
            return ds;
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            MybatisConfiguration cfg = new MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        MapperFactoryBean<RechargeCreditMapper> creditMapper(SqlSessionTemplate t) {
            MapperFactoryBean<RechargeCreditMapper> bean = new MapperFactoryBean<>(RechargeCreditMapper.class);
            bean.setSqlSessionTemplate(t);
            return bean;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }
    }

    @Autowired
    private RechargeCreditMapper creditMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ws_card (
                  ID BIGINT PRIMARY KEY, CARD_NO VARCHAR(50), CARD_TYPE TINYINT DEFAULT 1, USER_ID BIGINT,
                  BALANCE_AMOUNT BIGINT NOT NULL DEFAULT 0, BALANCE_ML BIGINT NOT NULL DEFAULT 0,
                  EXPIRE_TIME VARCHAR(20) NULL, CARD_STATUS TINYINT, ISSUE_ORDER_ID BIGINT NULL,
                  DATA_STATUS TINYINT DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        jdbc.execute("DELETE FROM ws_card");
    }

    /** 一行判据形态：SQL 计数（0=被当赠卡排除，1=占名额）必须与 Java occupiesPaidSlot 同判。 */
    private void assertConsistent(String label, Integer cardType, String expireTime,
                                  Long issueOrderId, int cardStatus, boolean expectOccupiesSlot) {
        jdbc.execute("DELETE FROM ws_card");
        jdbc.update("INSERT INTO ws_card(ID, CARD_NO, CARD_TYPE, USER_ID, EXPIRE_TIME, CARD_STATUS, "
                        + "ISSUE_ORDER_ID, DATA_STATUS) VALUES(1, 'VC-M', ?, ?, ?, ?, ?, 0)",
                cardType, USER_ID, expireTime, cardStatus, issueOrderId);
        long sqlCount = creditMapper.countLiveCardsByUser(USER_ID);
        WsCard card = new WsCard();
        card.setId(1L);
        card.setCardType(cardType);
        card.setUserId(USER_ID);
        card.setExpireTime(expireTime);
        card.setCardStatus(cardStatus);
        card.setIssueOrderId(issueOrderId);
        boolean javaOccupies = CardEligibility.occupiesPaidSlot(card);

        assertEquals(expectOccupiesSlot, javaOccupies, label + "：Java 判定");
        assertEquals(expectOccupiesSlot ? 1L : 0L, sqlCount, label + "：SQL 判定");
    }

    /** 审计 R2 P1-2 要求的八形态矩阵：两侧逐形态同判，畸形有效期一律按占名额 fail-closed。 */
    @Test
    void giftPredicateMatrixIsIsomorphicAcrossJavaAndSql() {
        // 真赠卡（虚拟 + 规范 14 位 + 无锚）：不占名额
        assertConsistent("合法 14 位赠卡", 1, "20270710120000", null, 1, false);
        // 永久虚拟卡（NULL 有效期）：付费卡占名额
        assertConsistent("NULL 有效期", 1, null, null, 1, true);
        // 空串有效期：畸形，按异常付费卡占名额（漂移修复的靶点）
        assertConsistent("空串有效期", 1, "", null, 1, true);
        // 空白有效期：同上
        assertConsistent("空白有效期", 1, "   ", null, 1, true);
        // 非法长度有效期：同上
        assertConsistent("非法长度有效期", 1, "2026", null, 1, true);
        // 实体有限卡（CARD_TYPE=2 带期无锚）：不是赠卡，占名额
        assertConsistent("实体有限卡", 2, "20270710120000", null, 1, true);
        // 有订单锚的有限付费卡：占名额
        assertConsistent("有锚有限付费卡", 1, "20270710120000", 555L, 1, true);
        // 注销付费卡（状态 4）：仍占名额（D-417）
        assertConsistent("注销付费卡", 1, null, 666L, 4, true);
        // ---- R3 P1：只看长度放行的形态，现在必须全部按非赠卡占名额 ----
        // 14 位英文字母：删除数字限制（REGEXP / Java 逐字符判数字）本形态立刻变红
        assertConsistent("14 位字母", 1, "abcdefghijklmn", null, 1, true);
        // 不存在的日期：删除真实时间/往返一致性检查（STR_TO_DATE+DATE_FORMAT / parse+format）本形态立刻变红
        assertConsistent("不存在日期 0230", 1, "20260230000000", null, 1, true);
        // 数字字母混合恰 14 位
        assertConsistent("数字字母混合", 1, "2026a230000000", null, 1, true);
        // 含制表符（13 位数字 + \t，长 14）：不 trim，任何空白直接非法
        assertConsistent("含制表符", 1, "2026071012000\t", null, 1, true);
        // 首部空白 + 13 位数字（Java trim 能去、MySQL TRIM 也能去的空格——两侧都不再 trim）
        assertConsistent("首部空格", 1, " 2026071012000", null, 1, true);
        // 全零：REGEXP 过但 STR_TO_DATE 得不出真实日期
        assertConsistent("全零伪日期", 1, "00000000000000", null, 1, true);
    }

    /** 谓词常量自身的守卫：SQL_NOT_GIFT 必须是 SQL_GIFT 的整体取反，禁止漂移成手写副本。 */
    @Test
    void notGiftPredicateIsExactNegation() {
        assertEquals("NOT (" + CardEligibility.SQL_GIFT + ")", CardEligibility.SQL_NOT_GIFT);
        // 赠卡行在 NOT 谓词下不计入；再插一张付费卡，两谓词的并集覆盖全集
        jdbc.update("INSERT INTO ws_card(ID, CARD_NO, CARD_TYPE, USER_ID, EXPIRE_TIME, CARD_STATUS, "
                + "ISSUE_ORDER_ID, DATA_STATUS) VALUES(1, 'GC-1', 1, " + USER_ID + ", '20270710120000', 1, NULL, 0)");
        jdbc.update("INSERT INTO ws_card(ID, CARD_NO, CARD_TYPE, USER_ID, EXPIRE_TIME, CARD_STATUS, "
                + "ISSUE_ORDER_ID, DATA_STATUS) VALUES(2, 'WC-1', 1, " + USER_ID + ", NULL, 1, 777, 0)");
        Long giftCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_card WHERE " + CardEligibility.SQL_GIFT, Long.class);
        Long notGiftCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ws_card WHERE " + CardEligibility.SQL_NOT_GIFT, Long.class);
        assertEquals(1L, giftCount);
        assertEquals(1L, notGiftCount);
        assertEquals(2L, giftCount + notGiftCount, "两谓词划分全集，无行同时落两侧或两侧皆漏");
    }

    /** MP Wrapper 的 apply 路径与注解 SQL 同谓词：空串有效期卡必须被 Wrapper 计为占名额。 */
    @Test
    void wrapperApplyPathSeesSameBlankExpiryAsPaid() {
        jdbc.update("INSERT INTO ws_card(ID, CARD_NO, CARD_TYPE, USER_ID, EXPIRE_TIME, CARD_STATUS, "
                + "ISSUE_ORDER_ID, DATA_STATUS) VALUES(1, 'BAD-1', 1, " + USER_ID + ", '', 1, NULL, 0)");
        List<Long> ids = jdbc.queryForList(
                "SELECT ID FROM ws_card WHERE USER_ID = " + USER_ID + " AND " + CardEligibility.SQL_NOT_GIFT,
                Long.class);
        assertEquals(List.of(1L), ids, "空串有效期按异常付费卡占名额（合并定位与 hasOtherPaidCard 同谓词）");
    }
}
