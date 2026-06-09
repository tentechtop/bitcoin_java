# Bitcoin Java

> 基于 Java 21 + Spring Boot 4 从零构建的比特币Bitcoin区块链全节点实现。采用自研 QUIC 协议（基于 UDP）作为 P2P 通信传输层，完整覆盖 P2P 网络、共识机制、交易处理、UTXO 模型、脚本引擎、挖矿、持久化等核心模块。

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.8.6+-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](LICENSE)

---

## 目录

- [项目简介](#项目简介)
- [核心特性](#核心特性)
- [技术栈](#技术栈)
- [架构概览](#架构概览)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [配置说明](#配置说明)
- [QUIC 协议](#quic-协议)
- [P2P 网络](#p2p-网络)
- [区块链核心](#区块链核心)
- [挖矿支持](#挖矿支持)
- [REST API 与监控面板](#rest-api-与监控面板)
- [测试](#测试)
- [构建与部署](#构建与部署)
- [优化记录](#优化记录)
- [后续规划](#后续规划)

---

## 项目简介

`bitcoin_java` 是一个**研究性**的区块链全节点项目，旨在从零开始构建一个类比特币的区块链系统。项目并非 fork 自任何现有比特币客户端，而是独立实现了完整的区块链协议栈。

**核心创新点**：使用自研的 **QUIC 协议（基于 UDP）** 替代传统 TCP 作为 P2P 传输层，结合 Kademlia DHT 分布式路由表，提供更高效、更低延迟的节点通信能力。

---

## 核心特性

### 区块链
- ✅ 完整 UTXO 模型，支持多输入/多输出交易
- ✅ 比特币脚本引擎（P2PKH / P2SH / P2WPKH / P2WSH）
- ✅ PoW 工作量证明（SHA-256），难度每 2016 个区块动态调整
- ✅ 链重组与孤块处理，检查点机制保障安全
- ✅ 交易内存池（TxPool），支持 RBF 手续费替换

### P2P 网络
- ✅ 自研 QUIC 传输协议（UDP 承载，分帧/重组/ACK/重传/流量控制/拥塞控制）
- ✅ Kademlia DHT 分布式路由表（节点发现、距离计算、桶管理）
- ✅ Protobuf 序列化的 P2P 消息协议（20 种消息类型）
- ✅ Ed25519 节点身份签名 + ECC (Curve25519) AES-GCM 通信加密
- ✅ 并行区块同步引擎（多节点并发拉取区块头和完整区块）
- ✅ 节点黑名单、流量控制、错误追踪、AIMD 拥塞控制

### 挖矿
- ✅ CPU 挖矿（Java 原生 SHA-256）
- ✅ GPU 挖矿（CUDA 内核 + JCuda / OpenCL + JOCL）

### 存储
- ✅ RocksDB 嵌入式 KV 持久化（15 个列族表）
- ✅ Caffeine 高性能二级缓存（UTXO 缓存、Future 缓存）

### Web 服务
- ✅ REST API（链信息、交易、节点管理、P2P 管理等 9 组接口）
- ✅ 可视化监控面板（首页仪表盘、区块链可视化、P2P 网络监控、同步状态监控、交易池监控）

---

## 技术栈

| 层级 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **语言** | Java | 21 | 最低 JDK 要求 |
| **框架** | Spring Boot | 4.0.1 | Web + 依赖注入 |
| **网络** | Netty | 4.1.66 | UDP NIO 传输（自研 QUIC 协议底层） |
| **序列化** | Protobuf | 4.31.1 | P2P 消息序列化 |
| **持久化** | RocksDB | 7.10.2 | 嵌入式 KV 存储引擎 |
| **缓存** | Caffeine | 3.1.8 | 高性能内存缓存 |
| **JSON** | Jackson | 2.15.2 | REST API 序列化 |
| **比特币库** | bitcoinj-core | 0.16.3 | Base58 / 网络参数 |
| **GPU 加速** | JCuda / JOCL | 12.6.0 / 2.0.0 | CUDA / OpenCL 挖矿 |
| **加密** | Ed25519 + ECC + AES-GCM | — | 节点身份 + 通信加密 |
| **ID 生成** | java-uuid-generator | 5.1.0 | UUID v7 生成器 |
| **构建工具** | Maven | 3.8.6+ | 项目构建与依赖管理 |

---

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        REST API Layer                           │
│  ChainApi │ TxApi │ PeerApi │ ExplorerApi │ P2PAdminApi │ ...   │
├─────────────────────────────────────────────────────────────────┤
│                        Service Layer                            │
│  BlockChainService │ MiningService │ TxPool │ NetService │ ...  │
├─────────────────────────────────────────────────────────────────┤
│                        Core Layer                               │
│  Block │ Transaction │ UTXO │ ScriptExecutor │ Address │ ...    │
├─────────────────────────────────────────────────────────────────┤
│                        P2P Layer                                │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Protocol Layer (20 message types, Protobuf serialization) │  │
│  │  Handshake │ Block Query │ Tx Broadcast │ Node Discovery  │  │
│  ├───────────────────────────────────────────────────────────┤  │
│  │  Transport Layer — Self-implemented QUIC (UDP)            │  │
│  │  Framing │ ACK │ Retransmit │ Flow Control │ Congestion   │  │
│  ├───────────────────────────────────────────────────────────┤  │
│  │  Routing Layer — Kademlia DHT                              │  │
│  │  RoutingTable │ Bucket │ PeerQualityState                 │  │
│  └───────────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                        Storage Layer                            │
│  RocksDB (15 ColumnFamilies) + Caffeine L2 Cache                │
└─────────────────────────────────────────────────────────────────┘
```

---

## 快速开始

### 环境要求

- **JDK 21** 或更高版本
- **Maven 3.8.6** 或更高版本
- （可选）NVIDIA GPU + CUDA Toolkit 12.x（GPU 挖矿需要）
- （可选）OpenCL 运行时（GPU 挖矿需要）

### 克隆项目

```bash
git clone <repository-url>
cd bitcoin_java
```

### 编译项目

```bash
# 快速构建（跳过测试）
mvn clean package -Pdev-fast

# CI 构建（包含全部测试）
mvn clean package -Pci
```

### 启动节点

```bash
java -jar target/bitcoin_java-0.0.1-SNAPSHOT.jar
```

启动后，可通过以下地址访问：

| 页面 | 地址 |
|------|------|
| 首页仪表盘 | `http://localhost:8080/home/index.html` |
| 区块链可视化 | `http://localhost:8080/block-visualization.html` |
| P2P 网络监控 | `http://localhost:8080/p2p-monitor.html` |
| 同步状态监控 | `http://localhost:8080/sync-monitor.html` |
| 交易池监控 | `http://localhost:8080/txpool-monitor.html` |

---

## 项目结构

```
src/
├── main/java/com/bit/coin/
│   ├── BitcoinJavaApplication.java     # Spring Boot 入口
│   ├── aop/                            # AOP RPC 服务自动注册
│   ├── api/                            # REST API 控制器（9 组接口）
│   ├── blockchain/                     # 区块链核心逻辑（验证/重组/UTXO 管理）
│   ├── cache/                          # Caffeine 二级缓存
│   ├── config/                         # 系统配置（网络参数/CORS）
│   ├── database/                       # RocksDB 持久化层（15 列族表）
│   ├── estimate/                       # 手续费估算
│   ├── exception/                      # 统一异常处理
│   ├── mining/                         # CPU/GPU 挖矿服务
│   ├── net/                            # P2P 区块同步引擎
│   ├── p2p/
│   │   ├── conn/                       # 自研 QUIC 协议实现（20+ 文件）
│   │   ├── kad/                        # Kademlia DHT 路由表
│   │   ├── netty/                      # Netty UDP 服务启动
│   │   ├── peer/                       # 节点实体
│   │   ├── production/                 # 生产级 P2P 服务（黑名单/流量控制/错误追踪）
│   │   ├── protocol/                   # P2P 协议层（20 种消息 + 13 个处理器）
│   │   └── rpc/                        # RPC 服务注册中心
│   ├── proto/                          # Protobuf 消息定义
│   ├── structure/
│   │   ├── block/                      # 区块/区块头/区块体
│   │   ├── script/                     # 比特币脚本引擎（P2PKH/P2SH/P2WPKH/P2WSH）
│   │   └── tx/                         # 交易/UTXO/交易状态解析
│   ├── txpool/                         # 交易内存池（含 RBF）
│   ├── utils/                          # 工具类（Ed25519/HD钱包/AES-GCM/SHA/ID生成）
│   └── web/                            # Web MVC 配置
├── main/resources/
│   ├── application.yml                 # 主配置
│   ├── application-{dev,prod,txy,test2,local}.yml  # 多环境配置
│   ├── logback-spring.xml              # 日志配置
│   ├── cuda/
│   │   ├── miningKernel.cu             # CUDA 挖矿内核源码
│   │   └── miningKernel.ptx            # CUDA PTX 中间代码
│   └── static/                         # Web 监控面板（4 个 HTML + 首页）
└── test/java/com/bit/coin/
    ├── BitCoinVM.java                  # 完整 EVM 实现（108KB，教学用途）
    ├── SimpleEVM.java                  # 简化 EVM + ERC20 示例
    ├── BlockCreationTest.java          # 区块创建测试
    ├── *ScriptExample.java             # 比特币脚本示例（4 个）
    ├── broadcast/                      # P2P 消息广播测试
    ├── p2p/conn/                       # QUIC 连接测试（7 个）
    ├── p2p/kad/                        # Kademlia 路由表测试
    ├── p2p/production/                 # P2P 生产服务测试
    ├── p2p/protocol/                   # 协议消息测试
    └── txpool/                         # RBF 交易替换测试
```

---

## 配置说明

### 环境切换

在 `application.yml` 中修改 `spring.profiles.active` 切换运行环境：

```yaml
spring:
  profiles:
    active: prod   # 可选: dev / prod / txy / test2 / local
```

### 各环境说明

| Profile | 网络 | 端口 | 说明 |
|---------|------|------|------|
| `dev` | MainNet | 18444 | 开发环境，GPU 挖矿开启 |
| `prod` | MainNet | — | 生产环境 |
| `txy` | MainNet | 18666 | 腾讯云部署环境 |
| `test2` | TestNet | — | 测试网环境 |
| `local` | — | — | 本地单节点调试 |

### 协议版本配置

```yaml
protocol:
  min_supported_version: 1          # 最低支持的协议版本
  max_supported_version: 3          # 最高支持的协议版本
  default_send_version: 3           # 默认发送版本
  deprecated_versions: [0]          # 已废弃的版本
  enable_version_fallback: true     # 版本自动降级开关
```

---

## QUIC 协议

### 概述

本项目自研的 QUIC 协议基于 UDP 传输，使用 Netty `NioDatagramChannel` 作为底层网络通道。完整实现了类 QUIC 协议的核心特性：

### 帧类型（12 种）

| 帧类型 | 说明 |
|--------|------|
| `DATA_FRAME` | 数据帧，承载上层协议数据 |
| `DATA_ACK_FRAME` | 单帧 ACK 确认 |
| `ALL_ACK_FRAME` | 全量 ACK 确认 |
| `BITMAP_ACK_FRAME` | 位图 ACK 确认 |
| `BATCH_ACK_FRAME` | 批量 ACK 确认 |
| `PING_FRAME` | 心跳探测 |
| `PONG_FRAME` | 心跳响应 |
| `CONNECT_REQUEST_FRAME` | 连接请求 |
| `CONNECT_RESPONSE_FRAME` | 连接响应 |
| `OFF_FRAME` | 断开连接 |
| `CANCEL_FRAME` | 取消传输 |
| `UPDATE_WINDOW_FRAME` | 流量控制窗口更新 |

### 核心特性

- **分帧与重组**：大数据块自动拆分/合并，单帧最大载荷 1400 字节
- **可靠传输**：ACK 确认机制（单帧/位图/批量），未确认帧自动重传
- **自适应 ACK**：每 64 帧或超过 100ms 或传输完成时触发 ACK
- **指数退避重传**：`delay = base × 2^round`，最大 5 轮/2000ms
- **流量控制**：滑动窗口 + 连接级/流级双控
- **拥塞控制**：AIMD 算法（加性增/乘性减）
- **路径 MTU 探测**：动态估算最优 MTU 值
- **连接管理**：握手/心跳/迁移/断开全生命周期管理
- **对象池优化**：`QuicFrame` 对象池复用，减少 GC 压力

详细优化记录见 [QUIC_OPTIMIZATION_SUMMARY.md](QUIC_OPTIMIZATION_SUMMARY.md)。

---

## P2P 网络

### Kademlia DHT 路由表

采用 Kademlia 分布式哈希表算法进行节点发现和路由：

- XOR 距离度量，二叉 Trie 桶结构
- 每桶最多容纳 K 个节点
- 节点质量评估（响应时间、成功率）
- 自动老化与刷新机制

### P2P 协议消息（20 种）

| 类别 | 消息类型 |
|------|----------|
| **握手** | `NETWORK_HANDSHAKE`, `REQUEST_CONNECT` |
| **区块** | `BLOCK_REQUEST`, `BLOCK_RESPONSE`, `BLOCK_HEADERS_REQUEST`, `BLOCK_HEADERS_RESPONSE` |
| **交易** | `TRANSACTION_BROADCAST`, `TRANSACTION_REQUEST`, `TRANSACTION_RESPONSE` |
| **节点** | `NODE_DISCOVERY_REQUEST`, `NODE_DISCOVERY_RESPONSE`, `NODE_STATUS` |
| **心跳** | `PING`, `PONG` |
| **同步** | `SYNC_REQUEST`, `SYNC_RESPONSE`, `SYNC_STATUS` |
| **其他** | `ERROR`, `REJECT`, `NOT_FOUND` |

### 安全机制

- **节点身份**：Ed25519 公私钥对，公钥作为节点 ID
- **通信加密**：ECC (Curve25519) 密钥交换 + AES-256-GCM 加密
- **地址验证**：P2P 地址格式校验与黑名单过滤
- **流量控制**：连接级速率限制与突发保护
- **错误追踪**：累积错误计数，超阈值自动加入黑名单

---

## 区块链核心

### 区块结构

```
Block
├── BlockHeader
│   ├── version          # 区块版本
│   ├── previousHash     # 前一个区块的 SHA-256 哈希
│   ├── merkleRoot       # 交易的 Merkle 树根
│   ├── timestamp        # 时间戳（Unix 秒）
│   ├── difficultyTarget # 难度目标（nBits 压缩格式）
│   └── nonce            # PoW 随机数
└── BlockBody
    └── transactions[]   # 交易列表
```

### 交易模型

采用比特币 UTXO（未花费交易输出）模型：

```
Transaction
├── TxInput[]
│   ├── previousTxId     # 引用的前向交易 ID
│   ├── previousTxIndex  # 引用的输出索引
│   ├── scriptSig        # 解锁脚本
│   └── sequence         # 序列号（支持 RBF）
└── TxOutput[]
    ├── value            # 金额（聪）
    ├── scriptPubKey     # 锁定脚本
    └── address          # 目标地址
```

### 脚本引擎

支持四种比特币标准脚本类型：

| 类型 | 说明 | 隔离见证 |
|------|------|----------|
| P2PKH | 支付到公钥哈希 | 否 |
| P2SH | 支付到脚本哈希 | 否 |
| P2WPKH | 支付到见证公钥哈希 | 是 |
| P2WSH | 支付到见证脚本哈希 | 是 |

脚本执行器 (`ScriptExecutor`) 支持完整的操作码集，包括算术运算、位运算、密码学操作、流程控制等。

### 共识机制

- **PoW 算法**：SHA-256，目标值 < `difficultyTarget`
- **难度调整**：每 2016 个区块（约 14 天），目标平均出块时间 10 分钟
- **链选择**：累积工作量最大的有效链
- **检查点**：内置硬编码检查点防止低难度分叉攻击

### 地址格式

采用 Ed25519 公钥 → SHA-256 → Base58 编码，类似 Solana 地址风格。

---

## 挖矿支持

### CPU 挖矿

纯 Java 实现，基于 `java.security.MessageDigest` 的 SHA-256 运算。适合开发调试和轻量级挖矿。

### GPU 挖矿

| 技术 | 绑定库 | 说明 |
|------|--------|------|
| CUDA | JCuda 12.6.0 | NVIDIA GPU 加速，自定义 CUDA kernel |
| OpenCL | JOCL 2.0.0 | 跨平台 GPU 加速 |

CUDA 内核源码位于 `src/main/resources/cuda/miningKernel.cu`，采用暴力搜索 nonce 的策略，在 GPU 端并行计算 SHA-256。

---

## REST API 与监控面板

### API 接口一览

| 控制器 | 路径前缀 | 说明 |
|--------|----------|------|
| `ChainApi` | `/chain/*` | 区块/链信息查询 |
| `TxApi` | `/tx/*` | 交易创建/查询/广播 |
| `PeerApi` | `/peer/*` | 节点信息查询 |
| `P2PAdminApi` | `/p2p/*` | P2P 网络管理 |
| `ExplorerApi` | `/explorer/*` | 区块链浏览器接口 |
| `ConnectApi` | `/connect/*` | 连接管理 |
| `TxPoolApi` | `/txpool/*` | 交易池状态查询 |
| `ChainResultApi` | `/result/*` | 链计算结果 |
| `MockApi` | `/mock/*` | 测试模拟接口 |

### 监控面板

| 面板 | 路径 | 功能 |
|------|------|------|
| 首页仪表盘 | `/home/index.html` | 节点状态总览 |
| 区块链可视化 | `/block-visualization.html` | 区块关系图 |
| P2P 网络监控 | `/p2p-monitor.html` | 连接/流量/延迟 |
| 同步状态监控 | `/sync-monitor.html` | 区块同步进度 |
| 交易池监控 | `/txpool-monitor.html` | 待确认交易 |

---

## 测试

```bash
# 运行全部测试（CI 模式）
mvn test -Pci

# 运行单个测试类
mvn test -Dtest=BlockCreationTest -Pci

# 快速构建（跳过测试）
mvn clean package -Pdev-fast
```

### 测试覆盖

- **区块链**：区块创建、脚本示例（P2PKH/P2SH/P2WPKH/P2WSH）
- **QUIC 连接**：拥塞窗口、MTU 估算、流量控制、接收失败处理、帧排序
- **P2P 网络**：Kademlia 路由表、P2P 服务、协议消息
- **交易池**：RBF 手续费替换
- **消息广播**：P2P 消息广播与接收
- **EVM**：完整 EVM 实现测试（教学用途，含 Gas 计算和 EIP 支持）

---

## 构建与部署

### 本地构建

```bash
# 清理并打包
mvn clean package -DskipTests

# 产物位置
target/bitcoin_java-0.0.1-SNAPSHOT.jar
```

### 运行

```bash
# 默认配置（prod 环境）
java -jar target/bitcoin_java-0.0.1-SNAPSHOT.jar

# 指定环境
java -jar target/bitcoin_java-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev

# 自定义端口
java -jar target/bitcoin_java-0.0.1-SNAPSHOT.jar --server.port=9090
```

### JVM 参数建议

```bash
java -Xms2g -Xmx4g \
     -XX:+UseZGC \
     -XX:MaxGCPauseMillis=50 \
     -jar target/bitcoin_java-0.0.1-SNAPSHOT.jar
```

---

## 优化记录

### QUIC 网络框架优化

详见 [QUIC_OPTIMIZATION_SUMMARY.md](QUIC_OPTIMIZATION_SUMMARY.md)，主要优化包括：

- 🔧 修复 ByteBuf 内存泄漏（`SendQuicData`, `ReceiveQuicData`）
- 🔧 修复 `HashMap` 线程安全问题（全面替换为 `ConcurrentHashMap`）
- 🔧 修复 `QuicFrame` 对象池泄漏（正确归还对象池）
- 🔧 对象池容量优化（从 1GB 降至 64KB，减少约 99% 内存占用）
- 🔧 自适应 ACK 策略（延迟降低约 50%）
- 🔧 指数退避重传（有效吞吐提升约 20%）
- 🆕 `QuicConfig` 配置常量类（集中管理所有参数）
- 🆕 `QuicMetrics` 监控指标类（帧/包/ACK/连接/性能统计）

---

## 后续规划

### 短期

- [ ] 完善测试覆盖率（目标 > 80%）
- [ ] 添加数据包大小限制（防御 OOM 攻击）
- [ ] 实现连接数限制
- [ ] 引入 Netty 内存池优化 ByteBuf 分配

### 中期

- [ ] 0-RTT 快速握手
- [ ] 连接迁移支持
- [ ] 使用 Java 21 虚拟线程处理异步任务
- [ ] 轻量级区块（Compact Block Relay）

### 长期

- [ ] 智能合约支持（Solidity 兼容）
- [ ] 分片（Sharding）扩展方案
- [ ] 轻节点（SPV）模式

---

## 许可协议

本项目采用 [Apache License 2.0](LICENSE) 开源许可协议。

---

> ⚠️ **声明**：本项目为教育性和研究性项目，不应用于生产环境。加密货币投资有风险，请谨慎决策。
