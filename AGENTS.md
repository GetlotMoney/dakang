# AGENTS.md - 项目总则

## 项目背景

六维达康共享健康水站平台：社区自助售水机 IoT 运营平台，设备经 MQTT 接入，用户扫码/刷卡取水，平台完成计量计费、微信支付与多方分账、桶装水配送调度。

三端形态：

- **PC 公司后台**（`client/`）：一级工作区按运营职责展示，入口清单以 `client/src/config/businessNavigation.ts` 为准。运营总览、水站、设备、水种套餐、用户、订单履约、财务结算、配送运营、商城与系统底座各自承担单一职责；其中订单履约、财务结算、配送运营复用既有 `/order/*` 技术路由与 RBAC 权限树，仅在 `displayMenuList` 做展示投影，禁止为改导航层级迁移主库菜单或破坏旧深链。
- **微信小程序**（`miniapp/`）：统一账号能力入口——同一 `ws_user` 默认具备用水能力，并可叠加机主能力（本人收益/设备状态）和已准入配送能力（接单/送水）；固定一套 `首页 / 订单 / 我的` Tabbar，不使用全局身份切换
- **设备端**：售水机硬件经 EMQX（MQTT）接入，主题规则见 `docs/mqtt-topics.md`

一期范围（V1.0，截止 **2026-08-18**）：扫码取水全链路、设备 MQTT 接入、微信支付退款分账、水配送、后台管理。

一期待甲方/设备厂确认项逐项登记在 `docs/requirements/pending-client-confirmations.md` 与 `docs/requirements/pending-vendor-specs.md`（建模已按可空/占位预留，确认后仅补数据不改表；本文件不复制清单）。

## 技术架构

| 层     | 技术                                                         | 目录 / 地址                                   |
| ------ | ------------------------------------------------------------ | --------------------------------------------- |
| 前端   | Vue 3 + Element Plus + TypeScript + Vite（13321 仅开发预览） | `client/`                                     |
| 后端   | Java 17 + Spring Boot 3.5.11 + MyBatis-Plus + Sa-Token       | `server/`（13330，context-path `/dakangApi`） |
| 小程序 | unibest（uni-app + Vue3 + TypeScript）+ Wot UI               | `miniapp/`                                    |
| MQTT   | EMQX 5.8（Docker），MQTT 1883 / 管理台 18083                 | `deploy/docker-compose.yml`                   |
| 数据库 | MySQL 8.0 本地 Docker，宿主端口 3308，库 `dakang`            | root / 口令见仓库根 `.env`（`DAKANG_DB_PASSWORD`） |
| 缓存   | Redis 7 本地 Docker，宿主端口 6380                           | 口令见仓库根 `.env`（`DAKANG_REDIS_PASSWORD`） |

## 当前开发阶段与主工作流（强制）

状态源与阶段判据见 `docs/development-workflow.md` 开篇（模块状态与业务链状态各有唯一来源，此处不复述）。主开发工作流统一维护在：

- `docs/development-workflow.md`
- `docs/demo-module-status.md`（跨对话保存各页面验收状态与接真状态）

开发任何业务模块前先完整阅读主工作流，并在业务链矩阵中定位所属 `E2E-*` 旅程与 B 链/需求；任务分类（Demo 定型 / 契约待验收 / 真实实现 / 已有模块维护 / 分析评审）的判据与各阶段流程以主工作流为准，各链当前状态一律从业务链矩阵读取，本文件不复制状态。规则优先级以主工作流 §2 为准，资金与设备安全铁律始终最高。

用户可以一次授权一个边界明确的纯代码实现包；开发人员在包内连续完成编码、本地无副作用测试和必要文档同步。主库迁移、持久余额或水量变化、Pay-Sim 实际运行、真实支付、外部调用和设备指令仍需独立授权。

## 读者分工（强制）

本仓库的文字有三类读者，**写错地方比写少更有害**：

| 类别 | 载体 | 要求 |
|---|---|---|
| **人读** | `README.md`、`miniapp/README.md`、`miniapp/e2e/README.md`、`docs/requirements/pending-*.md`、`docs/acceptance/*/README.md` | 结论先行、一屏到两屏读完、**不复制状态与计数**（只写指针）、不写实现细节 |
| **agent 读** | `AGENTS.md`、`CLAUDE.md`、`miniapp/AGENTS.md`、`docs/development-workflow.md`、`docs/demo-module-status.md`、`docs/requirements/*`、`docs/contracts/*`、`docs/runtime-entrypoints.md`、`docs/mqtt-topics.md`、各 `.agents/skills/**` | 职责唯一、判据精确、不与其他文档竞争口径；篇幅其次，但每条断言都要能被代码或门禁核对 |
| **最终用户读** | PC 页面 `client/src/views/**`、小程序页面 `miniapp/src/pages/**` 上一切渲染出来的文字 | 只写用户做决定需要的信息，一句话说完；**禁止**实现术语、系统行为讲解、免责声明、口语旁白（见铁律 4） |

四条铁律：

1. **状态单点**：业务链与需求状态只在 `docs/requirements/demo-business-chain-matrix.md` 断言，
   模块状态只在 `docs/demo-module-status.md` 断言。其余任何文档一律写指针，**不得复制状态词、计数或日期**
   （事故背景见 `tools/check-demo-baseline.py` 人读文档判定段注释）。
2. **改代码即改人读文档**：变更若使 `README.md` / `miniapp/README.md` 的任何一句话不再成立
   （工具链版本、构建命令、数据源、开关、目录），必须同轮改掉。人读文档没有 agent 会顺带更新它，
   只能靠这条规则。
3. **"为什么"写进代码**：设计取舍、踩过的坑、失效边界写在代码注释、迁移脚本头注释与测试 javadoc 里
   ——那里不会与实现漂移。文档只留结论与指针，不做第二份副本。
4. **UI 不是文档**：产品界面（PC 页面与小程序页面）是**第三类读者**，读者是最终用户，
   不是甲方也不是 agent。界面上**禁止**出现实现术语（mock / 原型 / 契约 / 口径 / 受控媒体 /
   服务端 / 端点 / 状态码流转 / 表名字段名）、系统行为讲解、免责式自我声明、口语化旁白
   （"会真的…""这里的…""是正常的"）。
   界面只写**用户不知道就会做错决定的事实**，一句话说完，只给结论不解释原因。
   由 `python3 tools/check-ui-copy.py` 常态看守（事故背景见其模块 docstring）。

## 需求基线（强制）

2026-07-05 需求池快照与当前一期需求追踪关系统一维护在：

- `docs/requirements/README.md`（入口、权威性和数据质量说明）
- `docs/requirements/requirements-pool.csv`（38 字段全量需求数据）
- `docs/requirements/demo-business-chain-matrix.md`（B01～B24 需求能力链与宏观 E2E 旅程状态的唯一来源）
- `docs/requirements/decisions.md`（用户确认和范围覆盖记录）

开发业务模块前必须检索对应 REQ ID、需求场景、验收标准、依赖和人工确认点。权威性顺序、快照定位与需求计数一律以上列四个文件为准，本文件不复述；改动矩阵后必须运行 `python3 tools/check-demo-baseline.py`。

## 运行环境与唯一入口

端口角色判定的唯一解释来源为 `docs/runtime-entrypoints.md`，此处不复述；命令与行为约束如下。

| 项目         | 命令                                                                                 | 地址                                       |
| ------------ | ------------------------------------------------------------------------------------ | ------------------------------------------ |
| PC 本地验收   | `cd deploy && ./build-all.sh`                                                        | http://localhost:8081/#/dashboard/console  |
| 前端开发预览 | `cd client && pnpm dev`                                                              | http://localhost:13321/#/dashboard/console |
| 后端 API     | `export JAVA_HOME=$(/usr/libexec/java_home -v 17); cd server && mvn spring-boot:run` | http://localhost:13330/dakangApi           |
| 基础设施容器 | `cd deploy && docker compose up -d mysql redis emqx`                                 | 见下方入口清单                             |
| 一键构建部署 | `cd deploy && ./build-all.sh`                                                        | 构建 jar+静态文件并重启容器                |

> **【无产物源码包首次启动】** 交付包不包含 `node_modules/`、`client/dist/`、`server/target/` 或 `deploy/dist/`。首次解压后先执行 `cd client && pnpm install --frozen-lockfile`，再执行 `cd ../deploy && ./build-all.sh`。构建脚本会生成产物并创建五个容器；不要在无产物目录先执行全量 `docker compose up -d`，以免 Docker 把缺失的 JAR 挂载源创建成同名目录。

容器入口清单（五容器 dakang-mysql/redis/emqx/server/web）以 `deploy/docker-compose.yml` 头注释为唯一来源，本文件不复制。

> 全部口令只存在于**不入库**的仓库根 `.env`（首次使用 `cp .env.example .env` 后填写；`.env.example` 列出全部变量与用途）。
> 任何文档、SQL 注释、配置文件里都不得再出现口令明文——公开仓库同步会把它们一并带走。

前端通过 Vite 代理转发请求到后端；前缀清单以 `client/vite.config.ts` 的 proxy 配置为唯一来源，须与 `deploy/nginx/nginx.conf` 反代正则同步（由 `client/scripts/check-proxy-prefixes.mjs` 互校），本文件不复制清单。

> **【页面访问强制】Agent 了解项目、复现问题和最终 D4 验收默认使用 `http://localhost:8081/#/...`；只有正在进行前端热更新时才使用 `13321`，完成后必须构建并回到 `8081` 验收。PC 页面必须包含 `/#/`，例如设备中控是 `http://localhost:8081/#/device/index`。统一使用 `localhost`，不要与 `127.0.0.1` 混用。`8081` 与 `13321` 的 localStorage 相互隔离，不得把一端的登录/菜单状态当成另一端状态；`13330` 是 API，禁止当作页面入口。**

## 开发规范（Skills）

| Skill          | 位置                                            | 职责                                                  |
| -------------- | ----------------------------------------------- | ----------------------------------------------------- |
| spring-boot3   | `server/.agents/skills/spring-boot3/SKILL.md`   | 后端代码规范（Po/Bo/Vo、Controller 注解、校验、权限） |
| mysql8         | `server/.agents/skills/mysql8/SKILL.md`         | 数据库建表规范（字段命名、类型、审计字段、索引）      |
| art-design-pro | `client/.agents/skills/art-design-pro/SKILL.md` | PC 页面、组件、路由与布局规范                          |
| wot-ui         | `miniapp/.agents/skills/wot-ui/SKILL.md`        | 小程序组件、主题、交互与 API 规范                      |
| element-plus-vue3 | `client/.agents/skills/element-plus-vue3/SKILL.md` | Element Plus 组件用法规范（PC 底层组件库）      |
| tailwindcss    | `client/.agents/skills/tailwindcss/SKILL.md`    | Tailwind 布局/特性/效果类名规范（PC 样式层）          |
| vue            | `client/.agents/skills/vue/SKILL.md`            | Vue 3 组合式 API 与 script setup 宏规范               |
| frontend-design | `client/.agents/skills/frontend-design/SKILL.md` | 通用前端视觉设计参考；**与 art-design-pro 冲突时以后者为准**（本项目是后台系统，一致性优先于表现力） |

> **【强制】开发前必须先完整阅读对应的 SKILL 文件内容，严格按照 SKILL 中的模板、字段名、格式生成代码和 SQL。禁止凭记忆或推测编写，必须以 SKILL 文件中的示例为准。每个 SKILL 中标注【强制】的规则不可跳过。**

微信小程序任务还必须完整阅读 `miniapp/AGENTS.md` 和 `miniapp/README.md`。开发具体 Wot UI 组件前，除完整阅读 `wot-ui/SKILL.md` 外，还必须按该 Skill 的路由说明读取对应 `references/*.md`；不得依据其他组件库经验猜测 props、events 或 slots。

**PC 侧同一条规则**：用 `Art*` 组件或 `use*` Hook 前必须读 `client/.agents/skills/art-design-pro/references/art-<组件名>.md`（或 `use-<hook名>.md`），共 65 份、覆盖当前尚未使用的组件；同样不得凭其他组件库经验猜 API。这些参考按需读取、不占常驻上下文，**不因“当前没有页面在用”而删除**。小程序任务同样先按所属业务链状态分类，不能因使用组件 Skill 跳过需求追踪或该链的验收门。

## 资金与设备安全铁律

涉及资金、设备指令和配送履约的代码，以下七条**逐条强制**，代码评审一票否决：

1. **原子扣减**：一切余额/库存/水量扣减必须原子 `UPDATE ... SET 余额=余额-X WHERE id=? AND 余额>=X`（校验影响行数）+ 落流水表，**禁止"读出→内存计算→写回"**。
2. **支付回调幂等**：微信支付/退款/分账回调通过 `OUT_TRADE_NO` 唯一索引保证幂等性；重复回调直接返回成功，不得重复入账。
3. **设备消息去重**：设备上行消息以 `MSG_ID` 唯一索引去重（`ws_device_msg.MSG_ID`），支撑断网补传场景不重复计量。
4. **指令状态机**：所有下行指令必须写入 `ws_command` 表并执行状态机（下发→ack→result/超时），直至形成可追踪终态。
5. **禁止 Demo 鉴权降级**：任何测试账号直通、万能密码或跳过鉴权逻辑均不得进入代码。**唯一已裁决的例外**是小程序 `/mini/test-login/by-phone`，由独立开关 `mini.test-login.enabled` 门控（dev/prod 均缺省 false，缺省不注册路由，与 Pay-Sim 开关互相独立；口径见 `decisions.md` D-425，判据由 `SimulationEntryContractTest`「模拟入口各用各的开关」看守）。新增此类入口一律视为违反本条。
6. **数据范围强制过滤**：机主/配送员/渠道接口必须在 Service 层按登录人强制过滤数据范围（机主只看自己的站/设备/收益；配送员只看服务范围内可接任务及本人任务），禁止依赖前端传参圈定范围。
7. **禁止同账号自配送**：配送任务查询必须排除下单用户等于当前登录用户的任务，接单动作必须再次校验；禁止同一主体同时作为下单人和履约配送员，也禁止仅依赖前端过滤。

## MQTT 主题规则

设备通信协议（主题、消息格式、QoS、幂等、断网补传约定）统一维护在 **`docs/mqtt-topics.md`**，开发设备接入相关代码前必读。要点：上行 `up/{deviceNo}/{type}`，type 全集 6 个 —— `heartbeat` / `status` / `telemetry` / `ack` / `result` / `replay`（以 `DeviceTopics` 常量为准，由 `DeviceTopicsDocParityTest` 与文档常态对齐）；下行只有 `down/{deviceNo}/cmd`；QoS 1；服务端按 `msgId` 幂等去重。

## 审核通过后的真实模块开发流程

本节只适用于工作流验收门已经打开且已获得对应实施授权的真实实现阶段。用户授权完整四步时，按 SQL→后端→前端接真→联调 **完整执行**；用户只授权边界明确的纯代码实现包时，只连续完成该授权包，不得执行未授权的 SQL、主库或外部副作用。授权范围内不在正常步骤之间中断等待重复确认。

### 第 1 步：数据库设计

1. 根据业务需求设计表结构（遵循 `mysql8` skill）
2. **检查枚举冲突**：读取 `server/src/main/java/com/jbk/tool/consts/ApiEnum.java` 中 `DictType` 已占用编号，选择未冲突的编号
3. 编写建表 SQL + **字典数据 INSERT** + 权限菜单 INSERT + **测试数据 INSERT**
4. 将 SQL 文件保存到 `server/sql/<模块名>.sql`，并**同步一份到 `deploy/mysql/init/`**
5. **应用 SQL —— 按目标库是否为空库分两条互斥路径，绝不混用**：

> **【铁律】`server/sql/*.sql` 是领域源文件，只能作用于空库，禁止对任何既有库执行**——文件以 `DROP TABLE IF EXISTS` 开头，对既有库执行会静默删光该域全部数据，无提示、不可恢复。
>
> | 目标库 | 用哪份 SQL | 怎么执行 |
> |---|---|---|
> | **空库 / 全新环境** | `deploy/mysql/init/*.sql`（内容与 `server/sql/` 同源） | `docker compose up` 首启自动执行，人不手动跑 |
> | **既有库**（主库 3308、验收库 3309、任何已部署环境） | `deploy/mysql/migrations/<日期>-<模块>.sql` | 先 `set -a; . .env; set +a`，再 `mysql -h127.0.0.1 -P3308 -uroot -p"$DAKANG_DB_PASSWORD" dakang < deploy/mysql/migrations/<文件>`，**须经用户逐次授权** |
>
> 口令必须走 `.env` 变量，任何命令行、文档或脚本里都不得出现明文。迁移只允许非破坏语句；判据与禁止语句白名单以 `server/.agents/skills/mysql8/SKILL.md` §初始化与迁移 为唯一来源，由 `SchemaParityTest.migrationsCarryNoDestructiveDdl` 常态看守。
> 新建表时 `server/sql/` + `init/` 与 `migrations/` 两份都要写；只写前者会导致新表在主库根本不存在，接口全 500。

> **【强制】字典/菜单/测试数据 SQL 必须幂等且实际执行到库**（只写不执行，字典接口无数据、下拉框永远为空）。字典表 `api_dict_type`/`api_dict_data` 无业务唯一键，**禁止 `INSERT IGNORE`**，必须用 `INSERT ... SELECT ... WHERE NOT EXISTS`；携带显式主键 ID 的菜单/角色绑定/业务测试数据用 `INSERT IGNORE`。判据、SQL 模板与事故背景以 `server/.agents/skills/mysql8/SKILL.md` §字典与菜单 为唯一来源，由 `tools/check-migration-safety.py` 与 `SchemaParityTest.dictionaryInsertsNeverUseInsertIgnore` 常态看守。
> **【强制】必须生成测试数据**：每张新表 5～10 条、覆盖枚举值与边界，要求见 `mysql8` Skill §测试数据。

### 第 2 步：后端开发

遵循 `spring-boot3` skill：目录树、文件清单与各层模板以 `server/.agents/skills/spring-boot3/SKILL.md` 为唯一来源，本文件不复制。涉及资金/设备的代码必须同时满足上文"资金与设备安全铁律"。

### 第 3 步：前端开发

遵循 `art-design-pro` skill：新页面文件清单与流程以 `client/.agents/skills/art-design-pro/SKILL.md` §项目结构 / §新页面流程 为唯一来源，本文件不复制；新增路径前缀时按上文「运行环境与唯一入口」的代理条款同步双文件。

### 第 4 步：前后端联调与自测

1. 确保前端 API 调用路径与后端 Controller `@RequestMapping` 一致
2. 确保 Vite 代理已配置新模块的路径前缀
3. 枚举字段通过 `/api/dict/listByType` 接口获取，页面加载时调用一次
4. 编译/类型/格式检查按 `spring-boot3` 与 `art-design-pro` 两份 Skill 的「完成检查」命令块执行（后端那份带 `JAVA_HOME` 钉 17，不得省略）
5. 接口自测：用 curl 逐个验证 page/add/update/delete/字典接口，均返回 `"code": 0`
6. 页面自测：列表渲染、搜索/重置、新增/编辑/删除、分页、表单校验逐项通过

## 关键约定

- 所有接口统一 POST，请求体 JSON
- 后端成功码 `code: 0`，前端 `ApiStatus.success = 0`
- 枚举字段必须注册字典（`api_dict_type` + `api_dict_data`），前端通过接口获取
- 新模块必须同步生成权限 SQL（目录 + 菜单 + 功能点）
- 前端页面直接写中文，不定义额外 i18n（仅 menus 菜单需要）
- 完成后执行 `pnpm run lint:prettier` 格式化前端代码
- 注释应说明设计意图、业务边界、数据来源、并发约束或失败行为；避免使用口语化表达、情绪化措辞以及与代码等价的过程复述。

### 【强制】前后端路由与权限对应规则

后端权限菜单（`api_rbac_menu`）中的路径配置必须与前端路由模块严格对应，否则菜单无法正确渲染或权限校验失效。对应关系如下：

| 后端字段（api_rbac_menu）          | 前端对应                                        | 说明                                      |
| ---------------------------------- | ----------------------------------------------- | ----------------------------------------- |
| `MENU_PATH`（目录，type=1）        | 前端路由模块的 `path`（如 `/station`）          | 一级路径，必须一致                        |
| `MENU_PATH`（菜单，type=2）        | 前端子路由的 `path`（如 `index`）               | 相对路径，拼接后为完整 URL                |
| `MENU_COMPONENT`（菜单，type=2）   | 前端子路由的 `component`（如 `/station/index`） | 对应 `src/views/` 下的页面路径            |
| `MENU_API_PERMS`（功能点，type=3） | 后端 `@SaCheckPermission` 的 `permission` 值    | 格式 `模块:实体:操作`，前后端必须一字不差 |
| `MENU_NAME`                        | 前端 i18n `menus.xxx.title` 对应的中文          | 侧边栏显示名称                            |
| `MENU_ICON`（目录）                | 前端路由 `meta.icon`                            | Iconify 图标名，如 `ri:drop-line`         |
