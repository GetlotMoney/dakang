package com.jbk.serve.service.mini.auth;

import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.tool.exception.ErrorMsg;
import com.jbk.tool.exception.JbkException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 绑号闸真实 MySQL 判定：业务链测试注册的都是放行版，真实数据上的判定只有本类覆盖。
 * 分工：判据 → MiniPhoneGateTest；锚点覆盖 → PhoneGateAnchorContractTest；真库判定 → 本类。
 * 无 Docker 自动跳过。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("绑号闸真库判定")
class MiniPhoneGateChainDbTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("dakang_gate_it")
            .withUsername("root")
            .withPassword("ittest")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    private static SqlSessionFactory factory;

    /** 与主库同形的最小结构：闸只读 USER_PHONE，但唯一键必须在场——它决定空串与 NULL 的差别。 */
    private static final String DDL = """
            CREATE TABLE ws_user (
              ID BIGINT PRIMARY KEY AUTO_INCREMENT,
              USER_NAME VARCHAR(50),
              USER_PHONE VARCHAR(20) NULL DEFAULT NULL,
              DATA_STATUS TINYINT DEFAULT 0,
              UNIQUE KEY uk_user_phone (USER_PHONE)
            )
            """;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(DDL);
            // 1 已绑号；2 手机号为 NULL（游客态建号的形态）；3 不存在
            st.execute("INSERT INTO ws_user(ID,USER_NAME,USER_PHONE) VALUES (1,'已绑号','13900000001')");
            st.execute("INSERT INTO ws_user(ID,USER_NAME,USER_PHONE) VALUES (2,'游客态',NULL)");
        }
        Configuration cfg = new Configuration(
                new Environment("it", new JdbcTransactionFactory(), ds));
        cfg.addMapper(WsUserIdentityMapper.class);
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    private static MiniPhoneGate gate(SqlSession session) {
        return new MiniPhoneGate(session.getMapper(WsUserIdentityMapper.class));
    }

    @Test
    @DisplayName("已绑号放行；NULL 手机号与不存在账号都以 627 拒绝")
    void realDataVerdicts() {
        try (SqlSession session = factory.openSession(true)) {
            MiniPhoneGate g = gate(session);

            assertDoesNotThrow(() -> g.requirePhoneBound(1L, "真库-已绑号"));

            // 游客态建号写的就是 NULL。这一条是本轮改造引入的**常态**输入，
            // 而不是从前那种"数据异常"——它必须被稳定地拦住。
            JbkException nullPhone = assertThrows(JbkException.class,
                    () -> g.requirePhoneBound(2L, "真库-未绑号"));
            assertEquals(ErrorMsg.PHONE_BIND_REQUIRED.getCode(), nullPhone.getCode());

            // 账号不存在时 SQL 返回空结果集，Mapper 拿到 null——绝不能被当成"查不到就放行"
            JbkException missing = assertThrows(JbkException.class,
                    () -> g.requirePhoneBound(999L, "真库-不存在"));
            assertEquals(ErrorMsg.PHONE_BIND_REQUIRED.getCode(), missing.getCode());

            assertEquals(true, g.isPhoneBound(1L));
            assertEquals(false, g.isPhoneBound(2L));
            assertEquals(false, g.isPhoneBound(999L));
        }
    }

    @Test
    @DisplayName("多个未绑号账号可共存：uk_user_phone 忽略 NULL，游客态才成立")
    void multipleUnboundUsersCoexist() {
        try (SqlSession session = factory.openSession(true)) {
            session.getConnection();
            try (Statement st = session.getConnection().createStatement()) {
                // 游客态下未绑号账号是常态，必须能有很多个。
                // 若建号链误写空串而非 NULL，第二个用户就会撞 uk_user_phone 建不出来——
                // 那是一个只在"第二个游客注册"时才暴露的缺陷。
                st.execute("INSERT INTO ws_user(ID,USER_NAME,USER_PHONE) VALUES (10,'游客甲',NULL)");
                st.execute("INSERT INTO ws_user(ID,USER_NAME,USER_PHONE) VALUES (11,'游客乙',NULL)");
            }
            catch (Exception e) {
                throw new IllegalStateException("多个 NULL 手机号账号无法共存，游客态不成立", e);
            }
            MiniPhoneGate g = gate(session);
            assertEquals(false, g.isPhoneBound(10L));
            assertEquals(false, g.isPhoneBound(11L));
        }
    }
}
