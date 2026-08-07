# 隔离环境冒烟脚本

这里的脚本用于在**隔离验收环境**（`deploy/acceptance/`，MySQL 3309 / 后端 13340）对单条整改链做端到端核验。
把它们放进仓库而不是留在临时目录，是为了让交付说明里的「N/N 通过」可被独立复核——
只写数字不留脚本，等于没有证据。

## 前置

```bash
cd deploy/acceptance
./acc-env.sh rebuild        # 重建验收库（init + 全部 migrations + acc-seed）
cd ../../server && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && mvn -DskipTests package -q
cd ../deploy/acceptance && ./acc-env.sh backend-start
```

## 运行

```bash
./deploy/acceptance/smoke/r201-employee-pwd-smoke.sh      # R-201 员工口令 BCrypt 化
./deploy/acceptance/smoke/b23-audit-export-smoke.sh       # B23 审计导出接真
```

脚本自带 PASS/FAIL 计数并以非零退出码表示失败，可直接串进流水线。

## 安全边界

- 只打 `localhost:13340`（验收后端）与 `dakang-acc-mysql`（3309），**物理上到不了主库 3308**。
- 凭据一律从仓库根 `.env` 读取，脚本内不留任何口令明文。
- `RsaEnc.java` 只用仓库 `.env` 的公钥生成传输层密文，等价于前端 `JSEncrypt.encrypt`；
  它不解密、不落盘任何凭据。

## 覆盖范围

| 脚本 | 用例数 | 覆盖 |
|---|---:|---|
| `r201-employee-pwd-smoke.sh` | 23 | BCrypt 登录、一次性初始密码回执、强改门拦截与专用错误码、弱密码拒绝、改密闭环、旧 token 作废、身份锚定、口令与初始密码不落操作日志、改密全端登出、新旧密码相同拒绝 |
| `b23-audit-export-smoke.sh` | 29 | 列表接真、申请落库、脱敏规则与筛选快照服务端固化、范围白名单、时间校验、重试仅限失败态与前态 CAS、无伪造终态、操作日志留痕；整改回归：`@Size` 生效、手机号双路径脱敏、字典不翻倍、时间戳不被误判为手机号 |
