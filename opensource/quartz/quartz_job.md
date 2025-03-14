# Quartz Job

## 1. Job 的核心概念

Quartz 中的 Job 是调度系统的核心执行单元，代表了"要做什么"的业务逻辑。Job 设计遵循以下几个关键原则：

### 1.1 接口定义

```java
public interface Job {
    void execute(JobExecutionContext context) throws JobExecutionException;
}
```

这个简洁的接口是 Quartz Job 设计的基础，所有任务都必须实现这个接口。

### 1.2 Job 与 JobDetail 分离

- **Job**: 定义执行逻辑（代码）
- **JobDetail**: 存储 Job 的实例属性和状态信息

这种分离使得同一个 Job 类可以被多个 JobDetail 引用，每个 JobDetail 可以有不同的配置参数。

### 1.3 Job 实例的生命周期

Quartz 采用"一次执行一个实例"的模式：

- 每次触发时创建新的 Job 实例
- 执行完成后实例被丢弃
- 不保留状态（除非通过 JobDataMap 显式保存）

## 2. JobDetail 详解

JobDetail 是 Job 的配置容器，包含以下关键组件：

### 2.1 核心属性

- **JobKey**: 唯一标识符（name + group）
- **JobClass**: 要执行的 Job 类
- **JobDataMap**: 参数和状态存储
- **Description**: 任务描述
- **Durability**: 是否在没有关联触发器时保留
- **RequestsRecovery**: 是否在系统崩溃后恢复执行

### 2.2 JobDataMap

JobDataMap 是一个特殊的 Map 结构，用于：

- 在调度时向 Job 传递参数
- 在执行之间保存状态（当配合 @PersistJobDataAfterExecution 使用时）
- 支持基本数据类型和序列化对象

## 3. Job 执行控制注解

Quartz 提供了两个关键注解来控制 Job 的执行行为：

### 3.1 @DisallowConcurrentExecution

- 防止同一个 JobDetail 的多个实例并发执行
- 基于 JobKey 实现锁定机制
- 适用于不支持并发的任务

### 3.2 @PersistJobDataAfterExecution

- 在 Job 执行完成后自动保存 JobDataMap 的更改
- 通常与 @DisallowConcurrentExecution 一起使用
- 用于实现有状态的 Job

## 4. Job 执行上下文

JobExecutionContext 提供了 Job 执行时的完整上下文信息：

### 4.1 主要组件

- **Scheduler**: 当前调度器实例
- **Trigger**: 触发当前执行的触发器
- **JobDetail**: 当前执行的任务详情
- **JobDataMap**: 合并了触发器和任务的数据映射
- **JobRunTime**: 执行相关的时间信息

### 4.2 数据访问

Job 可以通过上下文访问三种 JobDataMap：

```java
// 仅来自 JobDetail 的数据
context.getJobDetail().getJobDataMap();

// 仅来自 Trigger 的数据
context.getTrigger().getJobDataMap();

// 合并的数据（Trigger 数据优先）
context.getMergedJobDataMap();
```

## 5. Job 异常处理

Job 执行过程中的异常处理是 Quartz 设计的重要部分：

### 5.1 JobExecutionException

- 唯一可以从 execute() 方法抛出的检查异常
- 包含重试和重调度指令
- 可以控制触发器的后续行为

### 5.2 异常处理选项

```java
JobExecutionException e = new JobExecutionException("执行失败");

// 选项1: 立即重新执行
e.setRefireImmediately(true);

// 选项2: 不再执行此触发器
e.setUnscheduleAllTriggers(true);

// 选项3: 不再执行所有相关触发器
e.setUnscheduleAllTriggers(true);
```

## 6. Job 工厂

JobFactory 负责创建 Job 实例，允许自定义 Job 的实例化过程：

### 6.1 默认实现

```java
public interface JobFactory {
    Job newJob(TriggerFiredBundle bundle, Scheduler scheduler) throws SchedulerException;
}
```

默认的 SimpleJobFactory 通过反射创建 Job 实例。

### 6.2 自定义 Job 工厂

自定义 JobFactory 可以实现：
- 依赖注入集成（如 Spring）
- 实例池化
- 自定义初始化逻辑

## 7. Job 监听器

JobListener 允许在 Job 生命周期的关键点执行自定义逻辑：

### 7.1 监听器接口

```java
public interface JobListener {
    String getName();
    void jobToBeExecuted(JobExecutionContext context);
    void jobExecutionVetoed(JobExecutionContext context);
    void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException);
}
```

### 7.2 监听器注册

监听器可以全局注册或针对特定 Job 注册：

```java
scheduler.getListenerManager().addJobListener(myListener, 
    KeyMatcher.keyEquals(JobKey.jobKey("myJob", "myGroup")));
```

## 8. Job 存储与恢复

### 8.1 持久化策略

Job 可以通过不同的 JobStore 实现持久化：
- RAMJobStore: 内存存储，不支持恢复
- JDBCJobStore: 数据库存储，支持恢复
- TerracottaJobStore: 分布式存储

### 8.2 恢复机制

对于标记了 `requestsRecovery(true)` 的 Job：
- 在系统崩溃后重启时会被重新执行
- 通过 JobExecutionContext.isRecovering() 可以判断是否处于恢复执行

## 9. Job 设计最佳实践

### 9.1 无状态设计

- 设计无状态的 Job，避免依赖实例变量
- 使用 JobDataMap 传递所需参数
- 将执行结果持久化到外部存储

### 9.2 幂等性

Quartz 本身不提供内置的幂等性保证机制，但在分布式环境或需要故障恢复的场景中，幂等性设计至关重要

- 设计支持重复执行的 Job
- 实现幂等操作，避免重复执行导致的问题
- 使用事务或分布式锁确保一致性

### 9.3 异常处理

- 合理使用 JobExecutionException 控制重试行为
- 实现适当的日志记录和监控
- 考虑使用监听器进行统一的异常处理

### 9.4 性能考虑

- 避免长时间运行的 Job
- 合理设置线程池大小
- 考虑使用异步执行模式处理耗时操作

## 10. Job 在集群环境中的行为

在 Quartz 集群中，Job 的执行遵循以下规则：

- 同一个 JobDetail 在集群中只会被一个节点执行
- 使用 @DisallowConcurrentExecution 的 Job 在整个集群范围内互斥
- 节点故障时，其正在执行的 Job 可能会被其他节点恢复执行