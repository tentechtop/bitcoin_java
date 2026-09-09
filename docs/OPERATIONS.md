# 节点部署与恢复手册（共识修订 2）

这是使用 Ed25519 和自定义 UDP 协议的独立链，**不兼容 Bitcoin 主网、bitcoinj 钱包或标准 QUIC/TLS 客户端**。`MainNetParams` 是本项目主网络的名称。新的创世块、交易验证、数据库和握手规则与旧代码不兼容。

## 构建与发布门禁

使用维护中的 JDK 21。Windows PowerShell：

```powershell
.\mvnw.cmd -B -ntp -s .mvn/settings.xml -Pci clean verify
.\mvnw.cmd -B -ntp -s .mvn/settings.xml dependency:list '-DincludeScope=runtime' '-DoutputFile=target/runtime-dependencies.txt'
python scripts/audit_dependencies.py
python scripts/verify_artifacts.py target/runtime-dependencies.txt "$env:USERPROFILE/.m2/repository"
Get-FileHash target/bitcoin_java-0.0.1-SNAPSHOT.jar -Algorithm SHA256
```

Linux 使用 `bash ./mvnw` 代替 `.\mvnw.cmd`，本地 Maven 仓库参数使用 `$HOME/.m2/repository`。如果仓库目录自定义，向校验脚本传入实际路径。测试默认开启；`dev-fast` 不能作为发布证据。

`verify` 包括单元测试、真实 RocksDB 测试、打包后的双节点 HTTP/UDP 同步与进程终止恢复测试，生成可执行 JAR、`target/classes/META-INF/sbom/application.cdx.json`、Surefire/Failsafe 报告。OSV 不可用、扫描不完整或存在未解决记录时，审计失败，不自动豁免漏洞。依赖文件校验通过 HTTPS 获取 Maven Central 校验值，另存 SHA-256 清单。

CI 配置 Windows 和 Linux 验证，Linux 作业还构建并启动容器。配置文件存在不等于远程作业已通过。发布需保存提交、JDK 版本、JAR SHA-256、SBOM、审计和测试报告；当前流程不会自动推送镜像或部署到外部服务器。

## 启动与认证

默认 `local`：HTTP `127.0.0.1:18333`、UDP `127.0.0.1:18444`、数据目录 `./data/local-v2`。首次启动生成并持久化节点身份。默认不自动挖矿，没有硬编码引导节点或收益地址。

生成两个独立随机令牌并通过环境传入，避免写进命令行、日志或 Git：

```powershell
$env:NODE_ADMIN_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:NODE_READ_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
java -Xms256m -Xmx1536m -XX:MaxDirectMemorySize=128m -XX:+ExitOnOutOfMemoryError -jar target/bitcoin_java-0.0.1-SNAPSHOT.jar
```

令牌至少 32 字符，两个角色的令牌必须不同；空值表示该角色无法登录。实际部署通过受控服务配置或秘密管理系统保存。网页令牌仅留在当前页面内存，刷新需要重新输入。

健康探针无需令牌：`GET /actuator/health/liveness`、`GET /actuator/health/readiness`。生产配置下，`GET /node/info` 需要只读或管理员令牌，返回网络、公钥、主链高度/哈希及链状态；`local` 默认允许无需认证的状态查询，仅绑定本机。需要本地也鉴权时增加 `--node.api.public-read=false`。其 `ready` 字段只表示链状态可用，负载均衡使用 readiness 探针。

```powershell
Invoke-RestMethod http://127.0.0.1:18333/node/info -Headers @{Authorization="Bearer $env:NODE_READ_TOKEN"}
```

关闭使用 Ctrl+C 或服务管理器正常终止，保留 40 秒退出时间。两个进程不能共用同一数据目录。

## 生产配置与网络

设置 `SPRING_PROFILES_ACTIVE=prod` 后使用 `./data/mainnet-v2`、UDP `0.0.0.0:8333`，HTTP 仍绑定本机。历史 `dev/test2/txy` 配置已改为独立回归目录，不再自动连接旧远端。`dev` 实验接口在同时启用 `prod` 时也关闭。

| 环境变量 | 用途与默认值 |
|---|---|
| `NODE_DATA_DIR` | 节点独占、可写的数据目录 |
| `NODE_HTTP_ADDRESS` / `NODE_HTTP_PORT` | `127.0.0.1` / `18333` |
| `NODE_P2P_ADDRESS` / `NODE_P2P_PORT` | prod：`0.0.0.0` / `8333`；local：`127.0.0.1` / `18444` |
| `NODE_NETWORKING_ENABLED` | 默认 true；离线节点可设 false |
| `NODE_BOOTSTRAP` | prod 中以逗号分隔的修订 2 多地址 |
| `NODE_MINER_ADDRESSES` | prod 收益公钥列表：Base58 编码 32 字节 Ed25519 公钥，不接受私钥 |
| `NODE_READINESS_MIN_PEERS` | prod 默认 1，local 默认 0；有意隔离的节点设 0 |
| `NODE_READINESS_MAX_SYNC_LAG` | 默认 2，超过已知最佳节点高度差时 readiness 失败 |
| `NODE_MIN_FREE_DISK_BYTES` | 默认 256 MiB，Compose 默认 1 GiB；低于阈值拒绝写入 |
| `LOGGING_FILE_PATH` | 默认 `./logs`，滚动日志目录 |

多地址格式：`/ip4/主机IP/quic/UDP端口/p2p/Base58节点公钥`。从另一台同修订、同网络节点的 `/node/info` 获取公钥。握手校验预期公钥、端点、网络、签名、时间与 nonce；服务器时钟需正常同步。防火墙只开放指定 UDP 端口，管理 HTTP 经 VPN/内网或 HTTPS 反向代理访问。认证只接受 `Authorization: Bearer ...` 请求头，不接受 URL 参数与 Cookie。

初始部署资源基线为 2 CPU、3 GiB 内存、持久化 SSD 和至少数 GiB 空闲空间，再根据实测负载确定容量。RocksDB 原生内存、直接内存和线程栈也占用内存，不应把整个容器限额分给堆。CPU 为默认挖矿路径；GPU 需目标显卡/驱动实机验收。

## HTTP 接口行为

只读令牌可读取状态、指标及提交已经签名的交易。管理操作需要管理员令牌及 POST，例如 `/chain/startMining`、`/chain/stopMining`、`/chain/mingOneBlock`、`/chain/mingOneBlockByCount?count=1`、`/p2p/connect?addr=...`、`/chain/sync/startHeadersFirst`。挖矿前必须配置收益公钥。

推荐广播接口：

```http
POST /tx/broadcast
Authorization: Bearer <read-or-admin-token>
Content-Type: application/json

{"rawTransaction":"完整已签名交易的十六进制编码"}
```

服务端检查交易 ID、全部输入签名、所有权、金额、双花、成熟度和锁定条件。`accepted` 表示进入本节点交易池，不等于确认。交易池是内存状态，重启后客户端可重新广播。

常用错误：400 输入无效；401 未认证；403 权限不足；409 分页期间主链变化；413 请求体过大（包括 chunked）；422 交易未被接纳；429 限流；503 繁忙或状态不可用。响应不含内部栈和令牌。

`GET /chain/utxos?address=...&limit=100` 提供分页，后续请求使用返回游标以及链尖作为 `expectedTip`。主链变化时丢弃累计分页结果并重新查询。旧的全地址余额接口最多处理 10,000 个输出，超过上限明确失败，大地址使用分页。余额来自持久化索引与交易池状态，数据库异常不会降级成零余额。

公钥地址索引只覆盖 20 字节公钥哈希锁。版本 101 的其他脚本锁仍保存在 UTXO 集中，通过具体交易输出查询，不混入公钥地址余额。

## 容器部署

先在 Linux 构建并通过 `verify`，然后：

```sh
cp .env.example .env
# 用受控编辑器或秘密管理系统填入不同令牌、引导节点和可选收益公钥。
docker compose config --quiet
docker compose build
docker compose up -d
docker compose ps
docker compose logs --tail=100 node
```

`.env` 已被忽略。不要把 `docker compose config` 的完整展开输出复制到工单，其中可能有令牌。容器使用 UID/GID 10001、只读根目录、移除 capabilities、限制 CPU/内存/PID，HTTP 仅映射宿主回环地址。数据和日志使用持久化卷；`/tmp` 供 JNI 解压，需要允许可执行映射。基础镜像固定 digest，升级后重跑容器验证及镜像漏洞审计。

healthcheck 使用 JAR 中的 `NodeProbe`，无需 curl。readiness 检查链状态、数据库、空闲磁盘、UDP 及可配置的节点数/同步差距；liveness 保持进程探针语义，避免短暂离线导致反复重启。不要用 `docker compose down -v` 清理生产环境，该参数删除持久化卷。

## 备份、恢复、重建与回滚

数据目录含节点身份私钥，备份应具有同等访问控制并在外部存储加密。SHA-256 清单用于检查损坏，不提供备份加密或发布者身份签名。

先停止节点。维护工具独占数据库；目标必须不存在且在源目录之外，不覆盖或删除源目录。PowerShell：

```powershell
$nodeJar = (Resolve-Path target/bitcoin_java-0.0.1-SNAPSHOT.jar).Path
java '-Dloader.main=com.bit.coin.ops.DatabaseTool' -cp $nodeJar org.springframework.boot.loader.launch.PropertiesLauncher checkpoint ./data/mainnet-v2 ./backups/mainnet-v2-20260908 MainNetParams
java '-Dloader.main=com.bit.coin.ops.DatabaseTool' -cp $nodeJar org.springframework.boot.loader.launch.PropertiesLauncher restore ./backups/mainnet-v2-20260908 ./data/mainnet-restored-v2 MainNetParams
java '-Dloader.main=com.bit.coin.ops.DatabaseTool' -cp $nodeJar org.springframework.boot.loader.launch.PropertiesLauncher reindex ./data/mainnet-v2 ./data/mainnet-rebuilt-v2 MainNetParams
```

Linux 使用同样的 Java 参数和实际路径。回归网的最后一个参数用 `RegressionNetParams`，测试网用 `TestNet3Params`。Compose 维护时先停止 node，再用同镜像的 maintenance 入口挂载源卷与新的备份/恢复卷，确保 UID/GID 10001 可写。

自动化演练可在 `verify` 后对测试留下的已停止回归节点执行 `python scripts/smoke_maintenance.py target/multi-node-实际编号/a --expected-height 7`。脚本在 `target` 新目录中执行上述三个发布包入口，再分别启动恢复和重建的数据库，比较身份和链尖，并验证探针的成功/失败退出码。勿把该测试脚本指向仍在运行的节点或生产网络数据库。

- `checkpoint` 创建 RocksDB 一致性快照及 `backup-sha256.txt`。每次使用新目录，异地复制后再次校验。
- `restore` 在复制前后验证清单，再检查网络和链尖元数据。更新 `NODE_DATA_DIR` 指向新目录，启动后核对公钥、链尖、readiness 和交易查询。
- `reindex` 从保存的链尖沿区块头追溯创世块，重新验证和重放，重建 UTXO、地址、工作量、高度及撤销索引。不能修复损坏/丢失的原始区块或丢失的链尖哈希，此时恢复备份或重新同步。重建保留选定主链和节点身份，不保留侧链与交易池。路径追溯使用临时磁盘文件，上限 1,000 万块。
- 自动重组限制保留区块估算内存 64 MiB、状态覆盖 64 MiB、路径 512 块；原子写批次限制估算 256 MiB / 100 万操作。触发后停止接受写入，检查日志并保留原数据，在新目录同步正确分支。这些是本节点运行限制，不构成网络最终确认。
- 磁盘不足或提交/内存协调失败时，先恢复空间或排查存储，再重启并核对状态。进程终止测试不能替代宿主掉电和磁盘控制器持久性验证。
- 中断可能留下未完成的新目标目录。保留源目录，选择另一个新目标重试，确认失败目录路径后再清理。

升级前保存旧 JAR/镜像 digest、配置和可恢复备份。修订 1 数据必须原样保留；修订 2 拒绝直接打开或重建为新规则。另建数据目录启动修订 2 网络，不能静默迁入旧余额。回滚旧程序时使用对应旧备份与配置，禁止旧 RocksDB 版本直接写升级后的目录。

## 监控与环境验收

Prometheus 用只读令牌抓取 `/actuator/prometheus`，参考 `ops/prometheus.yml.example` 和 `ops/prometheus-alerts.yml`。将告警接到值班渠道，演练提交失败、磁盘阈值和断网，并确认通知送达。

指标覆盖链高、状态、同步差距、连接数、交易池内存、网络在途/队列内存、空闲磁盘、写入能力、提交失败和重组。拒绝区块计数覆盖上下文验证后的拒绝，不能当作所有攻击包计数。

生产环境验收包括对应环境的构建报告、镜像安全检查、持续负载、断线重连、备份异机恢复、磁盘耗尽、正常关闭和故障告警，并测量自己的容量及恢复时间目标。仓库自动化覆盖可重复的开发环境行为，不代表外部安全审计或公有链经济安全保证。
