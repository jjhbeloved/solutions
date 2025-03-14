# db-scheduler 错误处理与恢复机制

## 1. 基本错误处理机制

db-scheduler 提供了多层次的错误处理机制，用于处理任务执行过程中可能出现的各种故障情况。

### 1.1 执行失败处理

当任务执行过程中抛出异常时，db-scheduler 会：

1. **记录失败**：捕获异常并记录到日志中
2. **增加失败计数**：更新数据库中的 `consecutive_failures` 计数
3. **决定后续操作**：根据配置和失败次数决定是否重试、终止或标记失败

```java
// 定义失败处理策略的示例
Tasks.recurring("my-task", FixedDelay.ofMinutes(10))
    .onFailure((executionComplete, executionOperations) -> {
        // 自定义失败处理逻辑
        if (executionComplete.getConsecutiveFailures() > 3) {
            return ExecutionComplete.failure(executionComplete.getExecution(), 
                "Too many failures, giving up");
        } else {
            // 重试，时间推迟
            return executionOperations.reschedule(executionComplete, 
                Instant.now().plus(Duration.ofMinutes(5)));
        }
    })
    .execute((instance, ctx) -> {
        // 任务执行逻辑
    });
```

### 1.2 失败重试策略

db-scheduler 支持多种重试策略：

1. **固定延迟重试**：失败后按固定时间间隔重试
2. **指数退避重试**：每次失败后等待时间呈指数增长
3. **最大重试次数**：达到最大重试次数后停止尝试
4. **自定义重试逻辑**：通过 `onFailure` 回调实现自定义重试逻辑

```java
// 实现指数退避重试的示例
Tasks.recurring("retry-task", FixedDelay.ofMinutes(10))
    .onFailure((executionComplete, executionOperations) -> {
        int failures = executionComplete.getConsecutiveFailures();
        if (failures > 10) {
            return ExecutionComplete.failure(executionComplete.getExecution(), 
                "Max retries reached");
        }
        
        // 指数退避: 5分钟 * (2^失败次数)
        long delayMinutes = 5 * (long)Math.pow(2, failures - 1);
        return executionOperations.reschedule(executionComplete, 
            Instant.now().plus(Duration.ofMinutes(delayMinutes)));
    })
    .execute((instance, ctx) -> { /* 任务逻辑 */ });
```

## 2. 节点崩溃与死亡执行处理

db-scheduler 通过心跳机制检测和处理由于节点崩溃导致的"死亡执行"。

### 2.1 心跳机制

- 正在执行任务的节点会定期更新数据库中的 `last_heartbeat` 时间戳
- 其他节点会检测超过特定时间没有更新心跳的执行，并将其标记为"死亡执行"
- 对于死亡执行，调度器会根据任务类型决定如何处理

```java
// 配置心跳检测机制
Scheduler scheduler = Scheduler.create(dataSource)
    .heartbeatInterval(Duration.ofSeconds(20))  // 心跳更新间隔
    .missedHeartbeatLimit(3)  // 允许错过的心跳次数
    .build();
```

### 2.2 死亡执行的处理策略

db-scheduler 对不同类型任务的死亡执行采用不同策略：

1. **周期性任务（RecurringTask）**：
   - 默认行为是重新调度到当前时间执行
   - 可以自定义处理逻辑

2. **一次性任务（OneTimeTask）**：
   - 默认行为是重新调度到当前时间执行
   - 可以自定义处理逻辑，如放弃执行或延迟重试

3. **自定义任务（CustomTask）**：
   - 完全自定义如何处理死亡执行

```java
// 自定义死亡执行处理策略
Tasks.oneTime("critical-task", TaskData.class)
    .onDeadExecution((execution, executionOperations) -> {
        // 记录告警信息
        logger.warn("Dead execution detected for task: {}", execution.taskInstance.getTaskName());
        // 在5分钟后重试，而不是立即重试
        return executionOperations.reschedule(execution, Instant.now().plus(Duration.ofMinutes(5)));
    })
    .execute((instance, ctx) -> { /* 任务逻辑 */ });
```

## 3. 保证执行语义

db-scheduler 在集群环境中提供特定的执行保证语义。

### 3.1 "至少执行一次"与"最多执行一次"

- db-scheduler 默认提供"最多执行一次"的保证
- 通过死亡执行检测和处理机制，实现"至少尝试一次"的执行语义
- 结合这两点，在网络和节点正常工作的情况下，可以实现"恰好执行一次"

### 3.2 防止任务从不执行的机制

db-scheduler 通过多种机制确保任务不会被遗漏：

1. **轮询机制**：定期检查所有应该执行的任务
2. **死亡执行检测**：自动处理因节点崩溃而未完成的任务
3. **执行优先级**：可以为重要任务设置更高优先级
4. **自定义重试策略**：允许根据业务需求定制重试逻辑

```java
// 使用优先级确保关键任务被优先执行
client.schedule(
    TaskInstance.oneTime("critical-task", "instance-1", data),
    Instant.now(),
    10  // 高优先级
);
```

## 4. 中断执行恢复

当任务执行过程中被中断（如节点崩溃）时，db-scheduler 的恢复机制如下：

### 4.1 中断检测

- 其他节点通过心跳超时检测到中断的执行
- 超过 `heartbeatInterval * missedHeartbeatLimit` 时间未更新心跳的执行会被视为中断

### 4.2 中断恢复策略

1. **任务状态隔离**：执行中的任务被标记为 `picked=true`，防止其他节点重复执行
2. **全有或全无语义**：任务要么完全执行成功，要么被视为失败并按失败处理
3. **自动重新调度**：中断的任务通常会被重新调度执行
4. **幂等性设计**：建议任务实现设计为幂等，以防多次执行造成问题

```java
// 实现幂等性任务示例
Tasks.oneTime("idempotent-task", TaskData.class)
    .execute((instance, ctx) -> {
        // 查询之前的执行结果，避免重复处理
        String taskId = instance.getId();
        if (resultRepository.hasResult(taskId)) {
            logger.info("Task {} already executed successfully, skipping", taskId);
            return;
        }
        
        // 执行任务逻辑
        Result result = performTask(instance.getData());
        
        // 存储结果，标记已完成
        resultRepository.storeResult(taskId, result);
    });
```

## 5. 潜在问题与解决方案

### 5.1 垃圾数据问题

长期运行的db-scheduler可能面临数据表中积累大量执行记录的问题：

1. **问题**：

   - 表数据量大幅增长影响查询性能
   - 已完成/失败的执行记录占用存储空间
   - 索引效率降低，影响整体性能

2. **解决方案**：

   a. **自动清理机制**：

   ```java
   // 配置自动清理，保留7天的执行记录
   Scheduler scheduler = Scheduler.create(dataSource)
       .enableRecordTaskHistory()
       .deleteCompletedAfter(Duration.ofDays(7))
       .build();
   ```

   b. **手动清理任务**：

   ```java
   // 创建定期清理数据的任务
   Tasks.recurring("db-cleanup", FixedDelay.ofDays(1))
       .execute((instance, ctx) -> {
           // 清理30天前的执行记录
           jdbcTemplate.update(
               "DELETE FROM scheduled_tasks WHERE completed = true AND time_finished < ?", 
               Timestamp.from(Instant.now().minus(Duration.ofDays(30)))
           );
       });
   ```

   c. **表分区**：对于支持分区的数据库，可按时间对表进行分区，并定期删除旧分区

   d. **索引优化**：确保适当的索引以支持高效查询，特别是针对 `execution_time` 和 `picked` 列

### 5.2 长时间运行任务

对于执行时间长的任务，可能面临心跳超时被错误判定为死亡执行的问题：

1. **问题**：

   - 任务运行时间超过心跳检测窗口
   - 被错误地标记为死亡并重新调度
   - 可能导致同一任务并发执行

2. **解决方案**：

   a. **延长心跳窗口**：

   ```java
   Scheduler scheduler = Scheduler.create(dataSource)
       .heartbeatInterval(Duration.ofMinutes(5))  // 更长的心跳间隔
       .missedHeartbeatLimit(5)  // 允许更多次数的心跳缺失
       .build();
   ```

   b. **任务拆分**：将长时间任务拆分为多个短时间任务
   
   c. **周期性心跳更新**：在长时间任务内部定期手动更新心跳
   ```java
   Tasks.oneTime("long-running-task", TaskData.class)
       .execute((instance, ctx) -> {
           // 获取执行上下文
           ExecutionContext executionContext = ctx;
           
           // 启动后台线程定期更新心跳
           ScheduledExecutorService heartbeatService = Executors.newSingleThreadScheduledExecutor();
           heartbeatService.scheduleAtFixedRate(
               () -> executionContext.updateHeartbeat(),
               0, 10, TimeUnit.SECONDS  // 每10秒更新一次心跳
           );
           
           try {
               // 执行长时间运行的任务
               performLongRunningTask();
           } finally {
               // 关闭心跳服务
               heartbeatService.shutdown();
           }
       });
   ```

### 5.3 执行阻塞问题

1. **问题**：

   - 特定任务卡住或执行时间过长
   - 占用执行线程，阻塞其他任务
   - 整体任务执行吞吐量下降

2. **解决方案**：

   a. **设置执行超时**：
   
   ```java
   // 为任务设置执行超时
   Tasks.oneTime("time-sensitive-task", TaskData.class)
       .execute((instance, ctx) -> {
           // 使用Java的Future和超时机制
           ExecutorService executor = Executors.newSingleThreadExecutor();
           Future<?> future = executor.submit(() -> {
               // 任务逻辑
               performTask();
           });
           
           try {
               // 设置5分钟超时
               future.get(5, TimeUnit.MINUTES);
           } catch (TimeoutException e) {
               future.cancel(true);
               throw new RuntimeException("Task execution timed out", e);
           } finally {
               executor.shutdown();
           }
       });
   ```

   b. **增加执行线程**：

   ```java
   Scheduler scheduler = Scheduler.create(dataSource)
       .threads(20)  // 增加执行线程数
       .build();
   ```

   c. **任务分类与优先级**：将任务按执行特性分类，为快速任务和关键任务设置高优先级

## 6. 最佳实践建议

### 6.1 任务设计

1. **实现幂等性**：设计任务为幂等操作，确保多次执行不会产生副作用
2. **合理拆分**：避免过长的任务，拆分为多个短任务
3. **错误处理**：在任务内部进行全面的错误捕获和处理
4. **状态记录**：考虑将任务执行状态记录到业务表中，便于追踪和恢复

### 6.2 系统配置

1. **心跳配置**：根据任务特性调整心跳间隔和超时阈值
2. **监控告警**：设置监控和告警，及时发现执行异常
3. **定期清理**：实施数据清理策略，避免表无限增长
4. **负载测试**：在生产环境部署前进行充分的负载测试

### 6.3 运维策略

1. **gradual deployments**：新版本部署时采用灰度策略，避免全局影响
2. **备份恢复**：定期备份任务数据，制定恢复策略
3. **执行审计**：记录关键任务的执行历史和结果
4. **容量规划**：根据任务增长预测，进行系统容量规划
