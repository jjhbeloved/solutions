# quartz job storage

## 概述

JobStorage 是 Quartz 调度框架中负责存储和管理作业与触发器数据的核心组件。它决定了作业数据的持久化方式、集群支持能力以及整体性能特点。Quartz 提供了多种 JobStore 实现，以满足不同应用场景的需求

## JobStorage 类型

Quartz 主要提供三种 JobStore 实现：

1. **RAMJobStore**：内存存储，性能最高但不持久化
2. **JDBCJobStore**：数据库存储，支持持久化和集群
   - **JobStoreTX**：自管理事务
   - **JobStoreCMT**：容器管理事务
3. **TerracottaJobStore**：基于 Terracotta 分布式缓存的存储方案

## JobStoreSupport 架构设计与流程分析

### 整体架构设计

1. **模板模式**
   - JobStoreSupport 是一个抽象类，它定义了调度存储的基本框架，但将一些特定实现留给子类

2. **委托模式**
   - JobStoreSupport 使用 DriverDelegate 接口来处理与特定数据库的交互
   - 这种设计允许支持不同的数据库，而无需修改核心代码

3. **回调模式**
   - 使用 TransactionCallback 接口来封装在事务中执行的操作
   - 事务管理与业务逻辑分离

4. **策略模式**
   - 通过 Semaphore 接口实现不同的锁策略
   - 可以配置使用数据库锁或其他锁实现

### 核心组件

1. 事务管理
2. 锁管理 LockHandler
3. 集群管理 ClusterManager
4. 错过触发管理 MisfireHandler
5. 重试机制

### 关键流程

#### 初始化流程

``` java
public void initialize(ClassLoadHelper loadHelper, SchedulerSignaler signaler) throws SchedulerConfigException {
    // 1. 初始化类加载器和信号器
    this.classLoadHelper = loadHelper;
    this.schedSignaler = signaler;
    
    // 2. 初始化数据库代理
    initializeDelegate();
    
    // 3. 初始化锁处理器
    initializeLockHandler();
    
    // 4. 如果是集群模式，初始化集群管理线程
    if (isClustered) {
        clusterManagementThread = new ClusterManager();
        clusterManagementThread.initialize();
    }
    
    // 5. 初始化错过触发处理线程
    misfireHandler = new MisfireHandler();
    misfireHandler.initialize();
    
    // 6. 恢复作业
    recoverJobs();
}
```

#### 作业存储流程

``` java
// 1. 获取锁
// 2. 在事务中执行
// 3. 释放锁

public void storeJob(final JobDetail newJob, final boolean replaceExisting) throws JobPersistenceException {
    executeInLock(LOCK_TRIGGER_ACCESS, new VoidTransactionCallback() {
        public void executeVoid(Connection conn) throws JobPersistenceException {
            storeJob(conn, newJob, replaceExisting);
        }
    });
}
```

#### 触发器获取流程

``` java
// 负责获取下一批要触发的触发器
public List<OperableTrigger> acquireNextTriggers(final long noLaterThan, final int maxCount, final long timeWindow) throws JobPersistenceException {
    // 在锁保护下执行
    return executeInLock(LOCK_TRIGGER_ACCESS, new TransactionCallback<List<OperableTrigger>>() {
        public List<OperableTrigger> execute(Connection conn) throws JobPersistenceException {
            return acquireNextTrigger(conn, noLaterThan, maxCount, timeWindow);
        }
    });
}
```

#### 集群检查流程

``` java
protected boolean doCheckin() throws JobPersistenceException {
    // 1. 获取连接
    Connection conn = getNonManagedTXConnection();
    try {
        // 2. 检查并更新调度器状态
        List<SchedulerStateRecord> failedInstances = clusterCheckIn(conn);
        
        // 3. 如果有失败的实例，进行恢复
        if (!failedInstances.isEmpty()) {
            clusterRecover(conn, failedInstances);
            return true;
        }
        return false;
    } finally {
        // 4. 清理连接
        cleanupConnection(conn);
    }
}
```

### JobStorageTX

``` text
JobStore (接口)
    ↑
JobStoreSupport (抽象类)
    ↑
JobStoreTX (具体实现)
```

`JobStoreTX`继承自`JobStoreSupport`，后者实现了大部分JDBC持久化的核心逻辑。

#### 核心特点

1. 自管理事务：
   - 自行处理事务的开始、提交和回滚
   - 不依赖外部事务管理器
   - 适用于独立应用程序环境
1. 简化的实现：
   - 代码量较少，主要是对父类方法的特定化
   - 大部分复杂逻辑由JobStoreSupport提供

### JobStorageCMT

专为应用服务器环境设计，其中事务**由容器管理**。这意味着：

1. 该类**不负责事务的提交和回滚**
2. 适用于 J2EE/Jakarta EE 环境，如 JBoss、WebSphere 等应用服务器
3. 如果需要手动管理事务，应该使用 JobStoreTX 而非此类

#### 设计特点

- 事务管理分离：将事务管理职责委托给容器，符合J2EE的设计理念
- 锁机制：默认使用数据库锁，确保在分布式环境中的并发安全
- 数据源要求：需要配置非XA数据源，用于非托管事务操作
- 连接属性控制：提供了对连接自动提交和事务隔离级别的精细控制

## RAMJobStore

`RAMJobStore` 是一个基于内存的作业存储实现, **不支持集群**（isClustered() 返回 false）

### 特点

- **高性能**：所有操作都在内存中完成，无 I/O 开销
- **简单**：无需配置数据库
- **非持久化**：应用重启后数据丢失
- **适用场景**：开发测试、临时任务、不需要持久化的场景

## TerracottaJobStore

`TerracottaJobStore` 是一种基于 Terracotta 分布式缓存技术的 JobStore 实现。

### 特点

- **分布式存储**：利用 Terracotta 提供分布式内存存储
- **高可用性**：支持集群，具有故障转移能力
- **高性能**：比 JDBC 存储性能更好，接近内存存储
- **持久化**：支持数据持久化，应用重启后数据不丢失
- **适用场景**：需要高性能且支持集群的大规模部署

## 各实现对比

### JobStoreTX 与 JobStoreCMT 对比

| 特性 | JobStoreTX | JobStoreCMT |
|------|------------|-------------|
| 环境 | 独立应用 | 应用服务器 |
| 事务管理 | 自行管理 | 容器管理 |
| 连接获取 | 直接从数据源 | 可能从JNDI |
| 事务控制 | 显式commit/rollback | 依赖容器 |
| 复杂度 | 较低 | 较高 |

### 所有 JobStore 性能与功能对比

| 特性 | RAMJobStore | JobStoreTX/CMT | TerracottaJobStore |
|------|-------------|----------------|-------------------|
| 性能 | 最高 | 较低 | 高 |
| 持久化 | 否 | 是 | 是 |
| 集群支持 | 否 | 是 | 是 |
| 配置复杂度 | 低 | 高 | 中 |
| 资源消耗 | 内存 | 数据库 | 分布式缓存 |
| 适用场景 | 单机、测试 | 企业级应用 | 高性能集群 |

## 选择建议

在 SpringBoot 微服务环境中：

- **JobStoreTX** 通常是更好的选择，因为它：
  - 自行管理事务，不依赖容器
  - 配置更简单直接
  - 更符合微服务的独立性原则
- 只有在特定场景（如部署在完整 Java EE 应用服务器、需要 JTA 事务）下才考虑 **JobStoreCMT**
- 对于高性能需求且有集群要求的场景，可以考虑 **TerracottaJobStore**
- 对于简单应用或开发测试，**RAMJobStore** 是最简单的选择