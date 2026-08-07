---
name: spring-boot3
description: 六维达康 Spring Boot 3 后端开发规范。创建或维护真实后端、接口、权限、字典和领域状态机时使用。
---

# 六维达康 Spring Boot 3 规范

技术基线：Java 17、Spring Boot 3.5.11、MyBatis-Plus、Sa-Token、Knife4j，根包 `com.jbk`。

## 强制前置门

1. 先读根目录 `AGENTS.md`、`docs/development-workflow.md` 和对应 REQ。
2. 当前是 PC Demo 定型阶段。未通过页面/契约验收门，不得因为本 Skill 有真实后端模板就提前接真。
3. 设备、资金、身份与数据范围必须同时满足 `AGENTS.md` 的安全铁律。

## 目录

```text
server/src/main/java/com/jbk/
├── tool/consts/<module>/<Module>Enum.java
├── tool/data/<module>/{po,bo,vo}/
└── serve/
    ├── controller/<module>/
    ├── mapper/<module>/
    └── service/<module>/{I<Service>.java,impl/<Service>Impl.java}
server/src/main/resources/mapper/<module>/
```

## 命名与契约

| 对象 | 规则 | 示例 |
|---|---|---|
| 表 | `ws_` + 小写下划线 | `ws_station` |
| 实体 | 与表语义一致 | `WsStation` |
| Java 字段 | 小驼峰 | `stationName` |
| 数据库列 | 大写下划线 | `STATION_NAME` |
| 接口 | 全部 POST | `/station/station/page` |
| 权限 | 模块:实体:操作 | `station:station:update` |
| 成功码 | `code = 0` | `R.ok(data)` |

禁止使用 `NewXxx`、示例教育域名或与真实业务无关的占位模块名。

## Po / Bo / Vo

### Po

- 使用 `@TableName`、`@TableId`、`@TableField`。
- 继承项目现有审计基类时必须与表字段一致。
- `@Schema` 写业务含义、字典 type 和计量单位。
- 金额为分、体积为毫升，Java 使用 `Long`。

```java
@Data
@TableName("ws_station")
public class WsStation {
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @TableField("STATION_NAME")
    @Schema(description = "水站名称")
    private String stationName;

    @TableField("STATION_STATUS")
    @Schema(description = "水站状态(10)：1正常 2禁用")
    private Integer stationStatus;
}
```

### Bo

- 禁止出现 MyBatis 的表/列注解。
- 新增、修改、查询条件使用项目已有校验分组；关键字段必须校验。
- 字符串同时使用 `@NotBlank` 与 `@Size`，ID 使用 `@NotNull`。

### Vo

- 禁止出现 `@TableName`、`@TableId`、`@TableField`。
- 只返回页面契约需要的数据。
- 身份密文、支付密钥、密码、完整证件号不得返回。
- 跨表派生字段写清来源，例如“关联 ws_user 派生”。

## Mapper 与 Service

- Mapper 继承项目统一基类；复杂查询放 XML，不在 Controller 拼接。
- Service 负责查重、状态机、事务、权限数据范围和审计事件。
- Controller 只做参数校验、登录/权限声明和调用 Service。
- 机主、渠道等接口必须在 Service 按登录身份强制过滤数据范围。
- 设备与资金操作必须使用事务并验证影响行数。

## Controller 模板

```java
@RestController
@RequestMapping("/station/station")
@Tag(name = "水站管理")
@RequiredArgsConstructor
public class WsStationController {

    private final IWsStationService stationService;

    @PostMapping("/page")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<PageDataVo<WsStationVo>> page(@RequestBody WsStationBo bo) {
        return R.ok(stationService.pageList(bo));
    }

    @PostMapping("/update")
    @SaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "station:station:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> update(@Validated(UpdateGroup.class) @RequestBody WsStationBo bo) {
        return R.ok(stationService.update(bo));
    }
}
```

## 字典

1. 先读 `server/src/main/java/com/jbk/tool/consts/ApiEnum.java` 的 `DictType`，不得撞号。
2. 模块枚举集中在 `tool/consts/<module>/`。
3. 同步注册 `ApiEnum.DictType`。
4. SQL 必须同时写 `api_dict_type` 和 `api_dict_data`，且**必须用 `INSERT ... SELECT ... WHERE NOT EXISTS`，不能用 `INSERT IGNORE`**——这两张表除主键外无唯一键，IGNORE 无键可撞、重复执行会翻倍并让字典接口 500。范式与原因见 `mysql8` skill「字典与菜单」。
5. SQL 必须实际执行；只写文件不算完成。

## 菜单与权限

项目唯一口径：

| MENU_TYPE | 含义 | 路径 |
|---:|---|---|
| 1 | 目录 | `/station`，组件 `/index/index` |
| 2 | 菜单 | 相对路径 `index`，组件 `/station/index` |
| 3 | 功能点 | 无页面路径，配置 `MENU_API_PERMS` |

后端菜单、前端路由、Controller 权限必须逐字一致。当前 PC Demo 的最终菜单基线统一维护在 `deploy/mysql/init/03-demo-baseline.sql`；新增菜单时同步该文件，不得再创建第二套历史迁移菜单。

## 安全铁律

- 余额/水量：原子 UPDATE + 流水，禁止读出后内存扣减。
- 支付/退款/分账回调：业务单号唯一索引幂等。
- 设备上行：`msgId` 唯一去重。
- 设备下行：必须落 `ws_command`，状态走下发→ACK→result/失败/超时。
- 禁止万能密码、Demo 直通登录、前端圈定数据范围。
- 敏感信息密文存储，日志和 Vo 不得泄露。

## 完成检查

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd server
mvn compile -q
```

还必须逐个验证真实接口返回 `code: 0`，并检查操作日志、领域事件、状态机和幂等证据。
