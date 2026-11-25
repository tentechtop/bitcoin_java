# 生产就绪改造与验收记录

记录日期：2026-09-08（Asia/Shanghai）。本轮已完成代码改造、可重复构建、本机自动化验收和运维交付。目标服务器上的容器、持续负载与恢复演练仍需执行，不能用本报告代替实际环境验收。

## 已交付的行为

| 范围 | 改造结果 |
|---|---|
| 交易与脚本 | 统一检查全部输入的签名、所有权、金额、重复输入、成熟度、绝对及相对锁定条件；修正签名哈希语义与脚本执行边界，限制解析和执行资源 |
| 共识与重组 | 修订 3 独立创世块和网络标识；按候选分支验证难度、时间、奖励及累计工作量；分叉切换使用精确 UTXO 撤销记录 |
| 持久化 | 区块、工作量、UTXO、索引与链尖原子提交，默认同步 WAL；提交失败停止写入并影响 readiness；修复原生资源与查询错误处理 |
| 缓存与交易池 | 地址余额读取权威索引，缓存返回副本；交易池容量、寿命、祖先数和广播队列有界；RBF 先验证容量和冲突再替换 |
| 网络 | 签名握手绑定身份、端点、网络、时间与 nonce；方向隔离的 AES-GCM 会话、重放保护、握手限速、连接与收发内存限制 |
| HTTP 与页面 | 读/管理角色 Bearer 认证、POST 管理操作、签名交易广播、限流和 chunked 请求体限制；私钥不出现在网络或 JSON；修复页面不安全插入及错误时假数据回退 |
| 运行与维护 | 磁盘保护、健康探针、Prometheus 指标和告警示例；备份校验、恢复、重建索引；非 root 容器、资源限制、部署及回滚手册 |
| 发布流程 | HTTPS 依赖解析、构件校验、OSV 审计、SBOM、重复打包校验；Windows/Linux CI 和 Linux 容器验收步骤 |

重点代码：`ChainStateStore`、`ConsensusRules`、`TransactionValidator`、`SessionCipher`、`ApiSecurityConfig`、`DatabaseTool`。完整规则与边界见 [协议文档](PROTOCOL_V2.md)，部署与恢复命令见 [运维手册](OPERATIONS.md)。

## 本机实际验收

环境：Windows、OpenJDK 21.0.1、Maven Wrapper 3.9.12、真实 RocksDB JNI。最后一次干净 `verify` 于 2026-09-08 07:57:52 完成，构建成功。

| 验证项 | 实际结果 | 证据 |
|---|---|---|
| Surefire | 81 项，失败 0、错误 0、跳过 0 | [单元测试汇总](verification/surefire-summary.json) |
| Failsafe | 14 项，失败 0、错误 0、跳过 0 | [集成测试汇总](verification/failsafe-summary.json) |
| 总测试数 | **95 项全部通过** | [干净构建日志](verification/maven-verify.log) |
| 双节点 | 两个独立 JVM、数据库、HTTP 和 UDP；竞争分支收敛；强制终止后身份/链尖恢复，并追赶到高度 7 | [双节点结果](verification/multi-node-result.txt) |
| 发布包维护入口 | checkpoint / restore / reindex 均成功；恢复与重建后高度 7、链尖和身份一致；NodeProbe 健康返回 0、非健康响应返回 1 | [命令验收结果](verification/maintenance-launcher.json) |
| 运行时依赖审计 | 90 个 Maven 坐标，OSV 已知漏洞记录 0；2026-09-08 07:59:47 扫描完整 | [依赖审计](verification/dependency-audit.json) |
| 构件完整性 | 91 个实际运行时 JAR 均与 HTTPS Maven Central 校验值一致；含 classifier，因此数量多于依赖坐标数 | [构件清单](verification/artifact-integrity.json) |
| 重复打包 | 同一环境重复 package 的可执行 JAR SHA-256 一致 | [构建复现结果](verification/reproducible-result.txt) |
| 配置与页面静态检查 | CI、Compose、Prometheus YAML 解析通过；6 个 JavaScript 单元语法检查通过 | 未执行浏览器交互验收 |

存储测试覆盖依赖交易、分叉撤销、coinbase 成熟边界、提交失败、重新打开数据库、实例隔离、备份损坏检查、派生索引修复、磁盘阈值、缓存修改隔离，以及任意脚本伪造地址索引前缀的回归。API 验证含真实 HTTP chunked 超限请求返回 413。

OSV 结果仅针对该时间点的 Maven 运行时依赖，不代表应用逻辑、JDK、操作系统或容器没有漏洞。构件清单保留 SHA-256；远端缺少 SHA-256 文件时，使用经 HTTPS 获取的 SHA-1 值核对原始文件。重复打包验证针对本机同一构建环境，未声称跨系统生成的 JAR 完全相同。

## 发布产物

- 可执行包：`target/bitcoin_java-0.0.1-SNAPSHOT.jar`。
- SHA-256：`8f959d70b122dba51008b80510c83575233a87feae905506bbe79c97be3cd0ed`。
- SBOM：[本次 CycloneDX 清单](verification/application.cdx.json)，包内位置 `META-INF/sbom/application.cdx.json`。
- CI：`.github/workflows/ci.yml`；部署：`Dockerfile`、`compose.yml`、`.env.example`。
- 测试复现：`mvnw -B -ntp -s .mvn/settings.xml -Pci clean verify`；Windows 使用 `mvnw.cmd`。维护入口、依赖审计和构件校验复现步骤见运维手册及 `scripts/`。

后续任何影响源码、资源或依赖的修改均需重新构建、验收并生成新哈希，本记录不自动覆盖后续版本。当前变更保留在本地工作区；原有未跟踪 `README.md`、`tmp/` 保留。

## 兼容性与上线边界

**这次是共识修订 3 的不兼容升级。** 旧数据和旧余额不能静默迁移：保留旧程序、配置和数据库，使用新目录启动新网络。`MainNetParams` 只对齐 Bitcoin Core 的网络参数，项目仍使用自定义 Ed25519 地址、交易格式和 UDP/QUIC 协议，不能直接连接 Bitcoin 主网、bitcoinj 钱包或标准 QUIC/TLS。

本机没有 Docker，因此没有执行镜像构建、Compose 启动或容器运行时检查；已提供相应 CI 步骤，但没有声称远程 CI 已运行。没有执行长时间压力测试、物理掉电/控制器持久性测试、异机备份恢复、GPU 实机验收、浏览器交互测试或外部密码学/共识审计。容量、恢复时间及告警送达应按运维手册在部署环境测量。

当前成果建立了可验证的运行与发布基础；承载真实高价值资产前，还需要独立安全审计和目标环境验收。
