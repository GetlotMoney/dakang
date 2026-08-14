# 隔离环境冒烟脚本

这里的脚本用于在**隔离验收环境**（`deploy/acceptance/`，MySQL 3309 / 后端 13340）对单条整改链做端到端核验。

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

脚本自带 PASS/FAIL 计数并以非零退出码表示失败，可直接串进流水线；
逐条断言清单以脚本内 `chk` 标签与运行输出的 `PASS:` 行为准。

## 安全边界

- 只打 `localhost:13340`（验收后端）与 `dakang-acc-mysql`（3309），**物理上到不了主库 3308**。
- 凭据一律从仓库根 `.env` 读取，脚本内不留任何口令明文。
- `RsaEnc.java`：仅用仓库 `.env` 的公钥生成传输层密文，不解密、不落盘凭据（详见类头注释）。
