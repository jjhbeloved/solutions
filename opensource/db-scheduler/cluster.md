# db-scheduler cluster

db-scheduler 设计为可在分布式环境中运行，支持多个实例协同工作，共同处理调度任务。本文档详细说明 db-scheduler 的集群支持功能。

## 1. 多实例协调机制

在集群环境中，db-scheduler 通过数据库作为协调中心，确保多个实例之间的同步。

### 1.1 基于数据库的锁机制

db-scheduler 使用数据库表中的 `picked` 和 `picked_by` 字段实现分布式锁:

1. **执行锁定**：

   ```java
   // taskRepository 实现中的锁定逻辑
   int updated = jdbcTemplate.update(
       "UPDATE scheduled_tasks SET picked = true, picked_by = ?, last_heartbeat = ? " +
       "WHERE task_name = ? AND task_instance = ? AND picked = false",
       schedulerName, 
       currentTime, 
       taskInstance.getTaskName(), 
       taskInstance.getId()
   );
   
   // 如果 updated == 1，表示成功获取锁
   ```

2. **调度器身份识别**：

   - 每个调度器实例使用唯一标识符 (`schedulerName`)
   - 启动时可以自动生成或手动配置
   ```java
   Scheduler scheduler = Scheduler.create(dataSource)
       .schedulerName("scheduler-" + InetAddress.getLocalHost().getHostName())
       .build();
   ```

### 1.2 实例发现机制

调度器实例通过数据库记录彼此感知:

1. **活跃实例查询**：

   ```sql
   SELECT DISTINCT picked_by 
   FROM scheduled_tasks 
   WHERE last_heartbeat > ?
   ```

2. **无中心设计**：

   - 所有实例地位平等，无主从区分
   - 任何实例故障不影响整体工作

### 1.3 调度状态共享

集群中的实例通过共享数据库实现调度状态同步:

1. **任务元数据共享**：

   - 任务定义、执行时间、状态等存储在数据库
   - 所有实例共享访问相同的任务信息

2. **执行记录追踪**：

   - 任务执行历史和状态记录在数据库
   - 便于监控、审计和故障分析

## 2. 心跳机制

心跳是 db-scheduler 集群健康监测的核心机制。

### 2.1 心跳更新流程

1. **心跳生命周期**：

   - 任务被 pick 时开始心跳更新
   - 执行过程中定期更新
   - 任务完成后停止心跳

2. **心跳更新实现**：

   ```java
   // 心跳更新代码示例
   private void updateHeartbeat(Execution execution) {
       boolean updated = taskRepository.updateHeartbeat(execution);
       if (!updated) {
           log.warn("Failed to update heartbeat for execution: {}", execution);
       }
   }
   
   // 在数据库层面的实现
   public boolean updateHeartbeat(Execution execution) {
       return jdbcTemplate.update(
           "UPDATE scheduled_tasks SET last_heartbeat = ? " +
           "WHERE task_name = ? AND task_instance = ? AND picked = true AND picked_by = ?",
           Timestamp.from(Instant.now()),
           execution.taskInstance.getTaskName(),
           execution.taskInstance.getId(),
           schedulerName
       ) == 1;
   }
   ```

### 2.2 心跳配置参数

以下参数可调整心跳行为:

1. **心跳间隔**：控制心跳更新频率

   ```java
   Scheduler scheduler = Scheduler.create(dataSource)
       .heartbeatInterval(Duration.ofSeconds(5))  // 默认值通常为5秒
       .build();
   ```

2. **心跳超时限制**：允许多少次心跳缺失

   ```java
   Scheduler scheduler = Scheduler.create(dataSource)
       .missedHeartbeatLimit(3)  // 默认值通常为3
       .build();
   ```

3. **最大心跳窗口计算**：

   - 实际超时时间 = `heartbeatInterval × missedHeartbeatLimit`
   - 例如：5秒 × 3 = 15秒，超过15秒未更新心跳的任务被视为"死亡执行"

## 3. 死亡执行处理

死亡执行是指由于节点故障导致的未完成任务。db-scheduler 提供自动检测和恢复机制

### 3.1 死亡执行检测

1. **检测逻辑**：

   ```java
   // 检测死亡执行的核心逻辑
   public List<Execution> getDeadExecutions() {
       return jdbcTemplate.query(
           "SELECT * FROM scheduled_tasks " +
           "WHERE picked = true " +
           "AND last_heartbeat < ?",
           executionMapper,
           Timestamp.from(Instant.now().minus(
               heartbeatInterval.multipliedBy(missedHeartbeatLimit)))
       );
   }
   ```

2. **周期性检查**：

   - 每个实例在轮询周期中检查死亡执行
   - 发现后根据任务类型执行恢复策略

### 3.2 恢复策略

db-scheduler 为不同类型任务提供不同恢复策略:

1. **一次性任务恢复**：

   ```java
   // 默认的一次性任务死亡执行恢复
   public void reschedule(Execution execution) {
       int updated = jdbcTemplate.update(
           "UPDATE scheduled_tasks SET " +
           "picked = false, " +
           "picked_by = null, " +
           "last_heartbeat = null, " +
           "execution_time = ? " +
           "WHERE task_name = ? AND task_instance = ?",
           Timestamp.from(Instant.now()),  // 立即重新调度
           execution.taskInstance.getTaskName(),
           execution.taskInstance.getId()
       );
   }
   ```

2. **周期性任务恢复**：

   - 重置执行状态
   - 更新下次执行时间
   - 确保任务连续性

3. **自定义恢复行为**：

   ```java
   // 自定义死亡执行处理
   Tasks.oneTime("critical-task", TaskData.class)
       .onDeadExecution((execution, ctx) -> {
           // 记录故障情况
           logger.warn("Dead execution detected: {}", execution.taskInstance);
           
           // 自定义恢复行为，如延迟重试
           return ctx.reschedule(execution, Instant.now().plus(Duration.ofMinutes(5)));
       })
       .execute((instance, ctx) -> { /* 任务逻辑 */ });
   ```

### 3.3 任务幂等性重要性

为确保在死亡执行恢复后任务不会产生副作用，应实现幂等设计:

```java
// 幂等任务设计示例
Tasks.oneTime("payment-process", PaymentData.class)
    .execute((instance, ctx) -> {
        String paymentId = instance.getData().getPaymentId();
        
        // 检查是否已处理过
        if (paymentRepository.isProcessed(paymentId)) {
            logger.info("Payment {} already processed, skipping", paymentId);
            return;
        }
        
        // 执行付款处理
        boolean success = paymentService.processPayment(instance.getData());
        
        // 记录结果，确保幂等性
        paymentRepository.markAsProcessed(paymentId, success);
    });
```

## 4. 任务竞争策略

在集群环境中，多个实例会竞争执行待处理的任务。

### 4.1 公平调度策略

db-scheduler 使用以下机制确保公平的任务分配:

1. **批量获取**：

   ```java
   // 批量获取待执行任务
   public List<Execution> getScheduledExecutions(int limit) {
       return jdbcTemplate.query(
           "SELECT * FROM scheduled_tasks " +
           "WHERE picked = false " +
           "AND execution_time <= ? " +
           "ORDER BY execution_time ASC LIMIT ?",
           executionMapper,
           Timestamp.from(Instant.now()),
           limit
       );
   }
   ```

2. **乐观锁机制**：

   - 多个实例可能同时查询同一任务
   - 但只有一个实例能成功更新 `picked` 状态
   - 确保任务最多被执行一次

### 4.2 负载均衡配置

可通过以下参数调整负载均衡行为:

1. **轮询间隔**：控制实例查询新任务的频率
   ```java
   Scheduler scheduler = Scheduler.create(dataSource)
       .pollingInterval(Duration.ofSeconds(10))  // 默认通常为10秒
       .build();
   ```

2. **批处理大小**：每次轮询获取的任务数量
   ```java
   Scheduler scheduler = Scheduler.create(dataSource)
       .pollingStrategyConfig(new PollingStrategyConfig().setBatchSize(20))
       .build();
   ```

3. **并发度控制**：执行线程池大小
   ```java
   Scheduler scheduler = Scheduler.create(dataSource)
       .threads(10)  // 默认通常为10
       .build();
   ```

### 4.3 任务优先级

db-scheduler 支持任务优先级机制，保证关键任务优先执行:

1. **优先级定义**：
   ```java
   // 调度高优先级任务
   scheduler.schedule(
       TaskInstance.oneTime("critical-task", "instance-1", data),
       Instant.now(),
       10  // 优先级值，越高越优先
   );
   
   // 调度普通优先级任务
   scheduler.schedule(
       TaskInstance.oneTime("regular-task", "instance-1", data),
       Instant.now(),
       1  // 默认优先级
   );
   ```

2. **优先级查询**：
   ```sql
   -- 考虑优先级的任务查询
   SELECT * FROM scheduled_tasks 
   WHERE picked = false 
   AND execution_time <= CURRENT_TIMESTAMP
   ORDER BY priority DESC, execution_time ASC
   LIMIT ?
   ```

### 4.4 处理竞争条件

在高并发环境中，db-scheduler 通过以下机制处理竞争条件:

1. **数据库事务和锁**：

   - 利用数据库事务隔离级别
   - 使用行级锁保护关键操作

2. **乐观失败处理**：

   - 当无法获取任务锁时(即有其他实例已锁定)，简单跳过
   - 下一轮轮询会查找新的可用任务

3. **争用最小化**：

   - 轮询间隔随机抖动，避免所有实例同时查询
   - 批处理策略减少数据库连接次数

## 5. 集群扩展性考虑

db-scheduler 的集群设计允许水平扩展，提高整体吞吐量。

### 5.1 无状态设计

所有调度器实例不保存本地状态，便于扩展:

1. **实例独立性**：

   - 可随时添加或移除实例
   - 不需要重新分配或重平衡任务

2. **启动行为**：

   - 新实例启动后立即参与任务执行
   - 不需要特殊的加入过程

### 5.2 数据库瓶颈

在大规模集群中，共享数据库可能成为瓶颈:

1. **优化措施**：

   - 使用连接池管理数据库连接
   - 应用适当的索引策略，特别是 `execution_time`, `picked` 字段
   - 考虑分区或分片策略用于高吞吐量场景

2. **轮询优化**：

   ```java
   // 使用改进的轮询策略减少数据库负载
   Scheduler scheduler = Scheduler.create(dataSource)
       .pollingStrategyConfig(new PollingStrategyConfig()
           .setBatchSize(100)
           .setLockTime(Duration.ofMinutes(5)))
       .build();
   ```

### 5.3 与容器环境集成

在Kubernetes等容器环境下的考虑:

1. **实例命名**：
   - 使用Pod名称作为调度器名称
   ```java
   String podName = System.getenv("POD_NAME");
   Scheduler scheduler = Scheduler.create(dataSource)
       .schedulerName(podName != null ? podName : "scheduler-" + UUID.randomUUID())
       .build();
   ```

2. **优雅终止**：
   - 响应终止信号，完成正在执行的任务
   ```java
   // 在Spring应用中，确保优雅关闭
   @PreDestroy
   public void shutdown() {
       scheduler.stop(true); // 等待当前执行完成
   }
   ```

## 6. 监控集群健康

有效监控对于维护集群健康至关重要。

### 6.1 关键指标

应监控以下关键指标:

1. **执行统计**：
   - 完成任务数
   - 失败任务数
   - 执行时长分布

2. **集群健康**：
   - 活跃节点数
   - 每节点处理任务数
   - 心跳状态

3. **查询示例**：
   ```sql
   -- 检查活跃执行器数量
   SELECT COUNT(DISTINCT picked_by) as active_executors 
   FROM scheduled_tasks 
   WHERE last_heartbeat > (CURRENT_TIMESTAMP - INTERVAL '1 minute');
   
   -- 检查任务分布
   SELECT picked_by, COUNT(*) as task_count 
   FROM scheduled_tasks 
   WHERE picked = true 
   GROUP BY picked_by;
   ```

### 6.2 健康检查端点

实现自定义健康检查API:

```java
@RestController
@RequestMapping("/scheduler")
public class SchedulerHealthController {
    
    private final Scheduler scheduler;
    private final JdbcTemplate jdbcTemplate;
    
    @GetMapping("/health")
    public Map<String, Object> getHealth() {
        Map<String, Object> health = new HashMap<>();
        
        // 基本状态
        health.put("status", scheduler.isStarted() ? "running" : "stopped");
        health.put("scheduler_name", scheduler.getSchedulerName());
        
        // 执行统计
        health.put("completed_executions", scheduler.getExecutionMetrics().getCompletedExecutions());
        health.put("failed_executions", scheduler.getExecutionMetrics().getFailedExecutions());
        
        // 集群信息
        List<Map<String, Object>> activeExecutors = jdbcTemplate.queryForList(
            "SELECT picked_by, COUNT(*) as running_tasks, MAX(last_heartbeat) as last_seen " +
            "FROM scheduled_tasks WHERE picked = true " +
            "GROUP BY picked_by"
        );
        health.put("active_executors", activeExecutors);
        
        return health;
    }
}
```

## 7. 全集群故障处理

db-scheduler 原生设计主要针对单节点故障，当集群中所有节点都发生故障时，需要额外的监控和恢复策略。

### 7.1 全集群故障检测

由于 db-scheduler 本身没有节点间相互监控的机制，需要外部监控系统检测全集群故障：

1. **外部健康监控**：
   - 通过健康检查端点监控各节点状态
   - 使用第三方监控系统（如 Prometheus）监控全部节点
   - 当所有节点不可用时触发告警

2. **数据库层面监控**：
   - 监控待执行任务的堆积情况
   - 监控活跃调度器数量
   - 设置阈值告警，例如：

   ```sql
   -- 示例监控查询
   SELECT COUNT(*) AS overdue_tasks,
          COUNT(DISTINCT picked_by) AS active_schedulers
   FROM scheduled_tasks
   WHERE execution_time <= CURRENT_TIMESTAMP;
   ```

### 7.2 全集群恢复策略

当检测到全集群故障并恢复服务后，需要特殊处理积压的任务：

1. **恢复配置优化**：
   ```java
   // 恢复模式调度器配置
   Scheduler recoveryScheduler = Scheduler.create(dataSource)
       .schedulerName("recovery-scheduler")
       .pollingStrategyConfig(new PollingStrategyConfig()
           .setBatchSize(100))  // 增大批量处理数
       .threads(50)  // 增加处理线程
       .build();
   ```

2. **任务优先级排序**：
   - 优先执行关键业务任务
   - 根据延迟时间排序，最早应执行的任务优先处理

3. **监控恢复进度**：
   - 跟踪积压任务处理情况
   - 在积压任务清理完毕后恢复正常配置

### 7.3 防止全集群故障的措施

1. **部署隔离**：
   - 跨可用区部署调度器节点
   - 避免所有节点共享同一基础设施

2. **管理员节点**：
   - 部署独立的"管理员节点"，与主集群物理隔离
   - 仅用于监控和紧急恢复，不参与常规任务调度
   ```java
   // 管理员节点示例
   Scheduler adminScheduler = Scheduler.create(dataSource)
       .schedulerName("admin-node")
       .pollingInterval(Duration.ofMinutes(5))
       .register(
           Tasks.recurring("cluster-health-check", FixedDelay.ofMinutes(2))
               .execute((instance, ctx) -> {
                   // 检查集群健康状况
                   // 监控待执行任务堆积
                   // 必要时触发恢复操作
               })
       )
       .build();
   ```

3. **故障演练**：
   - 定期模拟全集群故障场景
   - 验证监控告警和恢复流程的有效性
   - 持续优化恢复策略

## 8. 任务定义变更与孤儿任务处理

### 8.1 孤儿任务问题分析

在 db-scheduler 中，当任务定义变更（如任务 A 改为任务 B）时，可能会导致"孤儿任务"问题：

**问题场景**：
1. 定义并启动了任务 A
2. 任务 A 正在执行过程中，停止了 db-scheduler
3. 修改代码，将任务 A 的定义改为任务 B（改变了任务名称或其他标识）
4. 重启 db-scheduler 后，发现原任务 A 的执行记录仍存在于数据库中
5. 由于没有对应的任务定义处理它，这些记录变成了"孤儿任务"，无法被自动处理或删除

**核心原因**：
- db-scheduler 通过任务名称（`task_name`）和实例标识（`task_instance`）来关联数据库中的执行记录与代码中的任务定义
- 当任务定义变更后，scheduler 无法找到对应处理这些旧执行记录的任务定义
- 对于已被 picked 但未完成的任务，它们会永远保持 picked 状态

### 8.2 解决方案

#### 8.2.1 正确的任务定义变更流程

变更任务定义时应遵循以下流程：

1. **确保优雅停止**：
   ```java
   // 优雅停止调度器，等待所有执行中的任务完成
   scheduler.stop(true);
   ```

2. **清理旧任务**：在变更前清理数据库中的旧任务
   ```java
   // 使用JDBC手动清理
   jdbcTemplate.update("DELETE FROM scheduled_tasks WHERE task_name = ?", "task-A");
   ```

3. **使用任务迁移策略**：如果需要保留任务执行状态，实现迁移而非直接删除

#### 8.2.2 处理已存在的孤儿任务

如果已经出现孤儿任务，可通过以下方式处理：

1. **创建临时清理任务**：
   ```java
   // 注册临时清理任务
   scheduler.register(
       Tasks.oneTime("orphan-cleaner", Void.class)
           .execute((instance, ctx) -> {
               // 查找并清理孤儿任务
               jdbcTemplate.update(
                   "DELETE FROM scheduled_tasks WHERE task_name = ? AND picked = true " + 
                   "AND last_heartbeat < ?", 
                   "task-A", 
                   Timestamp.from(Instant.now().minus(Duration.ofHours(1)))
               );
           })
   );
   
   // 调度清理任务立即执行
   scheduler.getSchedulerClient().schedule(
       TaskInstance.oneTime("orphan-cleaner", UUID.randomUUID().toString(), null),
       Instant.now()
   );
   ```

2. **实现兼容处理器**：为旧任务创建兼容处理器
   ```java
   // 创建处理旧任务A的桥接任务
   scheduler.register(
       Tasks.oneTime("task-A", OldTaskData.class)
           .execute((instance, ctx) -> {
               // 转换为新任务B的格式并执行
               NewTaskData newData = convertOldToNew(instance.getData());
               // 执行新逻辑
               performTaskBLogic(newData);
           })
   );
   ```

3. **使用数据库管理工具**：在紧急情况下，可直接使用数据库管理工具清理
   ```sql
   -- 识别孤儿任务
   SELECT * FROM scheduled_tasks 
   WHERE task_name NOT IN (
       -- 列出当前所有已注册的任务名称
       'task-B', 'task-C', 'task-D'
   );
   
   -- 清理确认的孤儿任务
   DELETE FROM scheduled_tasks 
   WHERE task_name = 'task-A';
   ```

### 8.3 预防措施

1. **版本化任务名称**：在任务名称中包含版本信息
   ```java
   // 使用版本号作为任务名称的一部分
   Tasks.recurring("report-generator-v2", FixedDelay.ofHours(1))
       .execute((instance, ctx) -> { /* 任务逻辑 */ });
   ```

2. **实施任务生命周期管理**：
   - 制定任务废弃策略
   - 在变更前确保所有执行中的任务已完成
   - 维护任务变更日志

3. **定期审计**：
   ```java
   // 创建定期审计任务检测潜在的孤儿任务
   Tasks.recurring("task-audit", FixedDelay.ofDays(1))
       .execute((instance, ctx) -> {
           // 获取当前所有注册的任务名称
           Set<String> registeredTasks = getCurrentRegisteredTasks();
           
           // 查找数据库中不在注册任务列表中的任务
           List<Map<String, Object>> orphanTasks = jdbcTemplate.queryForList(
               "SELECT task_name, COUNT(*) as count FROM scheduled_tasks " +
               "WHERE task_name NOT IN (" + 
               String.join(",", Collections.nCopies(registeredTasks.size(), "?")) + 
               ") GROUP BY task_name",
               registeredTasks.toArray()
           );
           
           // 记录潜在的孤儿任务
           if (!orphanTasks.isEmpty()) {
               logger.warn("Potential orphan tasks detected: {}", orphanTasks);
               // 可以发送告警或采取其他措施
           }
       });
   ```

4. **使用任务标签**：
   ```java
   // 使用元数据或标签标识任务类型，便于跟踪和迁移
   TaskData taskData = TaskData.create(actualData)
       .withMetadata("version", "2.0")
       .withMetadata("category", "reporting");
   ```

通过以上方法，可以有效处理任务定义变更导致的孤儿任务问题，确保 db-scheduler 表中数据的一致性和干净度。

## 9. 心跳更新机制详解

db-scheduler 的核心可靠性特性之一是其心跳更新机制，它在任务执行过程中扮演关键角色。

### 9.1 心跳更新原理

在 db-scheduler 中，心跳更新机制的主要实现原理如下：

1. **执行任务时启动心跳**：

   - 当 Scheduler 开始执行一个任务时，会同时启动心跳更新机制
   - 心跳更新通过独立线程池或定时任务实现，与主任务执行并行
   - 默认情况下，心跳更新间隔由 `heartbeatInterval` 配置决定

2. **数据库记录**：

   - 心跳信息存储在 `scheduled_tasks` 表的 `last_heartbeat` 字段中
   - 每次更新将当前时间戳写入该字段
   - 其他调度器节点通过检查此字段确定任务是否仍在活跃执行

3. **生命周期**：

   - 心跳更新在任务开始执行时启动
   - 在任务正常完成或失败时自动停止
   - 如果执行节点崩溃，心跳将停止更新，使任务成为"死亡执行"

### 9.2 心跳更新实现代码

心跳更新机制的核心实现位于 Scheduler 的执行器部分，主要通过以下组件实现：

```java
// 在Scheduler类中的执行逻辑
private void executeTask(Execution execution) {
    // ... 任务准备执行代码
    
    // 启动心跳更新
    final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    heartbeatExecutor.scheduleAtFixedRate(
        () -> updateHeartbeat(execution),
        0,
        heartbeatInterval.toMillis(),
        TimeUnit.MILLISECONDS
    );
    
    try {
        // 执行实际任务
        task.execute(execution.taskInstance, executionContext);
        
        // 完成处理
        complete(execution, ExecutionComplete.success(execution));
    } catch (Exception e) {
        // 异常处理
        complete(execution, ExecutionComplete.failure(execution, e.getMessage()));
    } finally {
        // 停止心跳更新
        heartbeatExecutor.shutdownNow();
    }
}

private void updateHeartbeat(Execution execution) {
    try {
        boolean updated = taskRepository.updateHeartbeat(execution);
        if (!updated) {
            log.warn("Failed to update heartbeat for execution: {}", execution);
        }
    } catch (Exception e) {
        log.error("Error updating heartbeat for execution: {}", execution, e);
    }
}
```

### 9.3 自定义心跳行为

对于长时间运行的任务，db-scheduler 提供了自定义心跳行为的能力：

1. **通过 ExecutionContext 手动更新**：
   ```java
   Tasks.oneTime("long-running-task", TaskData.class)
       .execute((instance, ctx) -> {
           // 在长时间运行的循环中手动更新心跳
           for (int i = 0; i < 1000; i++) {
               // 处理一批数据
               processBatch(i);
               
               // 手动更新心跳
               ctx.updateHeartbeat();
               
               // 可选：检查任务是否被请求取消
               if (Thread.currentThread().isInterrupted()) {
                   break;
               }
           }
       });
   ```

2. **配置心跳参数**：
   ```java
   Scheduler scheduler = Scheduler.create(dataSource)
       // 设置心跳间隔为30秒
       .heartbeatInterval(Duration.ofSeconds(30))
       // 设置最大允许错过3次心跳
       .missedHeartbeatLimit(3)
       .build();
   ```

### 9.4 心跳机制的作用

心跳更新机制是db-scheduler故障恢复能力的基础，提供了以下核心功能：

1. **确保任务执行状态可见**：

   - 使集群中的其他节点能够监控任务执行状态
   - 提供"活跃度"信息，表明任务仍在处理中

2. **支持死亡执行检测**：

   - 当执行节点崩溃时，心跳将停止更新
   - 其他节点可以通过检测心跳超时识别死亡执行
   - 使系统能够自动从节点故障中恢复

3. **防止重复执行**：

   - 通过心跳和锁定状态(`picked=true`)结合，确保任务只被一个执行器处理
   - 即使在任务处理时间长的情况下，也能维持"最多执行一次"的语义

4. **最大错过心跳计算**：

   - 系统使用 `heartbeatInterval * missedHeartbeatLimit` 计算允许的最大心跳缺失时间
   - 超过此时间的执行被视为死亡执行，将触发恢复处理

### 9.5 心跳机制的优化建议

使用心跳机制时的最佳实践：

1. **合理设置心跳间隔**：

   - 太短的间隔会增加数据库负载
   - 太长的间隔会延迟死亡执行的检测
   - 推荐基于任务平均执行时间和数据库性能来设置

2. **长时间任务的处理**：

   - 对于执行时间超过数分钟的任务，考虑在任务内部手动更新心跳
   - 或将大型任务拆分为多个较小的子任务，避免单个任务执行时间过长

3. **监控心跳更新失败**：

   - 心跳更新失败可能预示着数据库连接问题
   - 持续的心跳更新失败应触发告警，可能需要运维干预

### 9.6 心跳线程正常但任务线程异常的"假活"问题

在 db-scheduler 的默认设计中，存在一个潜在风险：心跳更新线程和实际执行任务的线程是分离的，这可能导致"假活"问题。

#### 9.6.1 问题描述

"假活"(False Liveness)问题指的是：

- 心跳更新线程正常运行，定期更新任务的 `last_heartbeat` 时间戳
- 但实际执行任务的线程已经异常终止、死锁或阻塞
- 系统认为任务仍在正常执行（因为心跳正常），但实际上任务已经停止进展
- 其他节点无法接管这个"死亡"但看起来"活着"的任务

#### 9.6.2 导致"假活"的常见场景

1. **任务线程死锁**：

   - 任务执行代码中出现死锁，但心跳线程仍在运行
   - 任务永远无法完成，但也不会被标记为死亡执行

2. **任务线程被异常终止**：

   - JVM 或系统级别的 OOM (OutOfMemoryError) 可能终止任务线程
   - Thread.stop() 等不安全操作导致线程异常终止
   - 某些安全管理器或容器可能终止特定线程

3. **长时间 I/O 或网络阻塞**：

   - 任务线程在等待无响应的外部系统
   - 网络连接挂起但未超时

#### 9.6.3 解决方案

1. **任务执行进度监控**：
   ```java
   Tasks.oneTime("monitored-task", TaskData.class)
       .execute((instance, ctx) -> {
           // 创建进度追踪器
           AtomicLong lastProgressTime = new AtomicLong(System.currentTimeMillis());
           AtomicBoolean taskCompleted = new AtomicBoolean(false);
           
           // 启动进度监控线程
           ScheduledExecutorService progressMonitor = Executors.newSingleThreadScheduledExecutor();
           progressMonitor.scheduleAtFixedRate(() -> {
               // 检查自上次进度更新已经过去了多长时间
               long timeSinceLastProgress = System.currentTimeMillis() - lastProgressTime.get();
               if (!taskCompleted.get() && timeSinceLastProgress > TimeUnit.MINUTES.toMillis(5)) {
                   // 任务5分钟没有进展，可能出现了问题
                   log.error("Task {} appears stuck (no progress for {} ms)", 
                       instance.getTaskName(), timeSinceLastProgress);
                   // 可以选择中断任务线程或触发告警
               }
           }, 1, 1, TimeUnit.MINUTES);
           
           try {
               // 实际任务执行，定期更新进度
               for (int i = 0; i < 100; i++) {
                   processStep(i);
                   // 更新进度时间戳
                   lastProgressTime.set(System.currentTimeMillis());
               }
           } finally {
               // 标记任务完成
               taskCompleted.set(true);
               progressMonitor.shutdownNow();
           }
       });
   ```

2. **自定义心跳实现与进度绑定**：
   ```java
   Tasks.oneTime("progress-bound-heartbeat", TaskData.class)
       .execute((instance, ctx) -> {
           // 自定义心跳更新器
           ProgressAwareHeartbeatUpdater heartbeatUpdater = new ProgressAwareHeartbeatUpdater(ctx);
           
           try {
               // 实际任务执行
               for (int i = 0; i < 100; i++) {
                   processItem(i);
                   // 每完成一步就更新进度并发送心跳
                   heartbeatUpdater.recordProgress(i);
               }
           } finally {
               heartbeatUpdater.shutdown();
           }
       });
   
   // 自定义进度感知心跳更新器
   class ProgressAwareHeartbeatUpdater {
       private final ExecutionContext ctx;
       private final AtomicInteger lastProgress = new AtomicInteger(0);
       private final ScheduledExecutorService heartbeatService;
       
       public ProgressAwareHeartbeatUpdater(ExecutionContext ctx) {
           this.ctx = ctx;
           this.heartbeatService = Executors.newSingleThreadScheduledExecutor();
           
           // 启动心跳更新，但只在有进度时更新
           heartbeatService.scheduleAtFixedRate(() -> {
               // 记录当前进度快照
               int currentProgress = lastProgress.get();
               
               // 创建带有进度信息的心跳数据
               // 这可能需要自定义扩展db-scheduler
               ctx.updateHeartbeat();
           }, 0, 10, TimeUnit.SECONDS);
       }
       
       public void recordProgress(int progress) {
           lastProgress.set(progress);
       }
       
       public void shutdown() {
           heartbeatService.shutdownNow();
       }
   }
   ```

3. **设置任务超时时间**：
   ```java
   Tasks.oneTime("timeout-protected-task", TaskData.class)
       .execute((instance, ctx) -> {
           // 使用 CompletableFuture 设置整体超时
           CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
               // 实际任务逻辑
               performLongRunningTask();
           });
           
           try {
               // 设置整体任务超时
               future.get(30, TimeUnit.MINUTES);
           } catch (TimeoutException e) {
               future.cancel(true);
               throw new RuntimeException("Task timed out after 30 minutes", e);
           }
       });
   ```

4. **增强型心跳机制**（需要扩展db-scheduler核心）：
   ```java
   // 在Scheduler类的executeTask方法中
   private void executeTask(Execution execution) {
       // 创建任务线程存活监视器
       final AtomicReference<Thread> taskThread = new AtomicReference<>(Thread.currentThread());
       final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());
       
       // 启动心跳更新器，同时检查任务线程状态
       final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
       heartbeatExecutor.scheduleAtFixedRate(() -> {
           Thread thread = taskThread.get();
           
           // 检查任务线程是否仍然存活
           if (thread == null || !thread.isAlive()) {
               log.warn("Task thread is no longer alive for execution: {}", execution);
               // 停止心跳更新，触发故障恢复
               heartbeatExecutor.shutdown();
               handleTaskThreadFailure(execution);
               return;
           }
           
           // 检查任务线程状态
           if (thread.getState() == Thread.State.BLOCKED || 
               thread.getState() == Thread.State.WAITING) {
               // 线程被阻塞，记录阻塞时长
               long blockedTime = System.currentTimeMillis() - lastActivityTime.get();
               if (blockedTime > MAX_ALLOWED_BLOCK_TIME) {
                   log.warn("Task thread blocked for too long: {} ms, execution: {}", 
                       blockedTime, execution);
                   // 可以选择中断线程或标记为故障
               }
           } else {
               // 线程处于活跃状态，更新活动时间
               lastActivityTime.set(System.currentTimeMillis());
           }
           
           // 正常更新心跳
           updateHeartbeat(execution);
       }, 0, heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
       
       try {
           // 执行实际任务
           task.execute(execution.taskInstance, executionContext);
           complete(execution, ExecutionComplete.success(execution));
       } catch (Exception e) {
           complete(execution, ExecutionComplete.failure(execution, e.getMessage()));
       } finally {
           heartbeatExecutor.shutdownNow();
       }
   }
   ```

#### 9.6.4 系统级监控方案

除了代码级解决方案，还可以实施系统级监控：

1. **应用健康检查**：

   - 为应用提供健康检查接口，报告任务执行状态
   - 如果任务长时间没有进展，健康检查会报告不健康状态

2. **JVM内存和线程状态监控**：

   - 使用工具如Prometheus、JMX等监控JVM内存使用和线程状态
   - 设置告警，当发现长时间BLOCKED或WAITING状态的任务线程时触发

3. **外部看门狗服务**：

   - 实现独立的看门狗服务，定期检查任务执行进度
   - 看门狗可以执行紧急操作，如重启执行节点或标记任务为失败

通过这些策略，可以有效地解决"假活"问题，确保db-scheduler能够正确识别并恢复那些看似活着但实际已经异常的任务执行。
