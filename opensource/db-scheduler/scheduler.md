# db-scheduler 中的 Scheduler

## 1. 基本概念

在db-scheduler中，Scheduler是整个框架的核心组件，负责协调和执行所有任务。与传统的调度框架不同，db-scheduler的Scheduler特别设计用于在集群环境中提供可靠的任务调度，并使用数据库作为持久化和协调机制。

Scheduler主要职责：

- 管理任务的生命周期
- 检测并获取需要执行的任务
- 分配任务到执行线程
- 维护心跳以支持集群协调
- 处理失败任务和死亡执行

## 2. 设计原理

### 2.1 单表模型

db-scheduler采用**单表设计模式**存储所有调度数据：

- 所有任务和执行信息存储在一个表中
- 简化了数据库结构，降低维护成本
- 通过乐观锁机制确保任务只被执行一次

### 2.2 轮询机制

Scheduler通过定期轮询数据库表来检测需要执行的任务：

- 支持两种轮询策略：`fetch-and-lock-on-execute`和`lock-and-fetch`
- 可配置轮询间隔以平衡实时性和数据库负载
- 使用数据库锁机制确保在集群环境中任务不会被重复执行

### 2.3 心跳机制

Scheduler使用心跳机制跟踪执行中的任务：

- 定期更新执行记录的心跳时间
- 自动检测并处理由于节点崩溃导致的"死亡"执行
- 提供可配置的死亡执行处理策略

### 2.4 线程池管理

任务执行通过线程池进行管理：

- 可配置执行线程数量
- 支持任务优先级排序
- 防止单个任务阻塞整个调度器

## 3. 使用方法

### 3.1 基本配置和启动

```java
// 创建数据源
DataSource dataSource = ...

// 创建调度器实例
Scheduler scheduler = Scheduler.create(dataSource)
    .pollingInterval(Duration.ofSeconds(10))
    .threads(5)
    .build();

// 注册任务
scheduler.register(
    Tasks.oneTime("my-task", MyTaskData.class)
        .execute((taskInstance, executionContext) -> {
            // 任务执行逻辑
            System.out.println("Executing task: " + taskInstance.getTaskName());
        })
);

// 启动调度器
scheduler.start();
```

### 3.2 高级配置

```java
Scheduler scheduler = Scheduler.create(dataSource)
    // 自定义调度器名称（用于集群环境识别）
    .schedulerName("my-scheduler")
    // 设置轮询间隔
    .pollingInterval(Duration.ofSeconds(5))
    // 配置执行线程数
    .threads(10)
    // 配置死亡执行检测
    .heartbeatInterval(Duration.ofMinutes(1))
    // 启用立即执行功能
    .enableImmediateExecution()
    // 设置序列化器
    .serializer(new JacksonSerializer())
    // 配置任务执行监听器
    .listener(new MyTaskExecutionListener())
    .build();
```

### 3.3 调度任务

```java
// 使用SchedulerClient进行任务调度操作
SchedulerClient client = scheduler.getSchedulerClient();

// 调度一次性任务
client.schedule(
    TaskInstance.oneTime("my-one-time-task", "instance-id", myTaskData),
    Instant.now().plus(Duration.ofHours(2))
);

// 取消任务
client.cancel("my-task", "instance-id");

// 重新调度任务
client.reschedule("my-task", "instance-id", Instant.now().plus(Duration.ofMinutes(30)));
```

### 3.4 集群模式下的负载均衡与重复执行防护

在集群环境中，db-scheduler设计了特定机制来解决两个核心问题：如何均衡分配任务，以及如何防止任务重复执行。

#### 3.4.1 负载均衡机制

db-scheduler采用以下策略实现集群节点间的任务均衡分配：

1. **竞争式调度**：

   - 所有节点平等竞争可执行的任务
   - 没有中央协调器，避免单点故障
   - 通过数据库锁机制实现公平竞争

2. **批量获取策略**：

   - 每次轮询获取一批任务，而不是单个任务
   - 可通过`batchSize`参数配置每批获取的任务数
   - 减少数据库交互，提高效率

3. **随机抖动（Jitter）**：

   - 在轮询间隔中添加随机抖动
   - 防止所有节点同时查询数据库
   - 缓解数据库压力峰值

```java
// 配置批量大小和随机抖动
Scheduler scheduler = Scheduler.create(dataSource)
    .pollingInterval(Duration.ofSeconds(10))
    .pollingStrategyConfig(
        new PollingStrategyConfig()
            .setBatchSize(20)  // 每次获取20个任务
            .setJitterFactor(0.2)  // 添加20%的随机抖动
    )
    .build();
```

4. **优先级支持（v15+）**：

   - 通过`priority`字段支持任务优先级
   - 高优先级任务会被优先执行
   - 确保关键任务在集群中得到及时处理

```java
// 调度带优先级的任务
client.schedule(
    TaskInstance.oneTime("critical-task", "instance-id", data),
    Instant.now().plus(Duration.ofMinutes(5)),
    10  // 设置较高优先级
);
```

#### 3.4.2 防止任务重复执行

db-scheduler使用几种机制确保任务只被执行一次：

1. **悲观锁策略**（适用于`fetch-and-lock-on-execute`）：

   - 先查询可执行任务
   - 执行前通过UPDATE语句尝试锁定任务（设置picked=true）
   - 使用乐观并发控制（版本检查）确保只有一个节点成功更新

```sql
-- 伪代码：尝试锁定任务的SQL
UPDATE scheduled_tasks
SET picked = true, picked_by = ?, last_heartbeat = ?
WHERE task_name = ? AND task_instance = ? AND picked = false
```

2. **SELECT FOR UPDATE SKIP LOCKED**（适用于`lock-and-fetch`）：

   - 使用数据库级别的行锁直接锁定并获取任务
   - 其他节点自动跳过已锁定的行
   - 减少一次数据库交互，提高效率和公平性

```java
// 为PostgreSQL启用lock-and-fetch策略
Scheduler scheduler = Scheduler.create(dataSource)
    .pollingStrategy(PollingStrategy.lockAndFetch(
        new PostgreSqlLockAndFetchStrategy.Builder()
            .withCustomLockingQuery("custom SQL if needed")
            .build()
    ))
    .build();
```

3. **心跳更新机制**：

   - 执行中的任务定期更新心跳时间
   - 如果节点崩溃，其他节点会检测到心跳超时
   - 通过`heartbeatInterval`和`missedHeartbeatsLimit`参数控制

```java
// 配置心跳检测机制
Scheduler scheduler = Scheduler.create(dataSource)
    .heartbeatInterval(Duration.ofSeconds(30))  // 每30秒更新一次心跳
    .missedHeartbeatLimit(3)  // 允许最多错过3次心跳后判定为死亡执行
    .build();
```

#### 3.4.3 集群配置最佳实践

1. **节点标识**：
   - 为每个调度器实例提供唯一名称
   - 便于在日志和监控中识别执行节点

```java
// 设置唯一的调度器名称
Scheduler scheduler = Scheduler.create(dataSource)
    .schedulerName("scheduler-node-" + UUID.randomUUID().toString())
    .build();
```

2. **差异化配置**：

   - 根据节点性能设置不同的线程数
   - 对处理特定任务的节点进行专门配置

3. **数据库连接池调优**：

   - 确保足够的连接支持多节点并发操作
   - 设置合理的连接超时和重试策略

4. **监控与警报**：

   - 监控各节点的任务执行情况
   - 对频繁出现的死亡执行设置警报

## 4. 与Quartz的Scheduler对比

### 4.1 架构复杂度

**db-scheduler**:

- 简单、轻量级的设计
- 单表数据模型
- 专注于可靠性和简单性

**Quartz**:

- 更复杂的架构
- 11张表的数据模型
- 更丰富的特性集

### 4.2 任务管理

**db-scheduler**:

- 任务类型较少（RecurringTask、OneTimeTask、CustomTask）
- 简化的API，配置相对直观
- 任务与执行分离的模型

**Quartz**:

- 更丰富的任务类型和触发器类型
- 更复杂的API和配置
- Job和Trigger分离的模型

### 4.3 集群支持

**db-scheduler**:

- 基于数据库锁和心跳机制实现集群
- 自动检测和处理死亡执行
- 集群配置简单，无需额外设置

**Quartz**:

- 提供更复杂的集群特性
- 需要额外配置启用集群模式
- 集群节点间有更多交互

### 4.4 性能特点

**db-scheduler**:

- 单表模型在高吞吐量场景下性能良好
- 支持批处理机制
- 提供针对PostgreSQL等数据库的特定优化

**Quartz**:

- 强大但可能更重量级
- 在复杂调度场景下功能更全面
- 可能需要更多的资源

### 4.5 适用场景

**db-scheduler**:

- 适合需要简单可靠调度的应用
- 适合已有数据库且不想引入额外组件的环境
- 适合微服务架构

**Quartz**:

- 适合复杂的企业级调度需求
- 适合需要丰富调度功能的场景
- 适合传统的单体应用

## 5. 实际使用建议

### 5.1 选择db-scheduler的场景

- 项目需要一个简单、可靠的调度解决方案
- 已经使用关系型数据库且不想引入额外组件
- 对部署和维护简单性有较高要求
- 需要在集群环境中协调任务执行

### 5.2 最佳实践

- 合理设置轮询间隔，平衡实时性和数据库负载
- 为长时间运行的任务设置适当的心跳间隔
- 实现适当的错误处理和重试逻辑
- 定期清理已完成的执行记录
- 在高负载环境下考虑使用`lock-and-fetch`策略（对于PostgreSQL）

### 5.3 监控和管理

- 利用提供的指标进行监控
- 定期检查执行历史和失败记录
- 为关键任务实现自定义的监听器
- 利用SchedulerClient进行运行时的任务管理
