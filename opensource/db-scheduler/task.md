# db-scheduler task

db-scheduler 的任务系统设计是其核心功能之一，提供了灵活且强大的任务定义和执行机制。本文档详细介绍任务的类型、生命周期、数据传递和错误处理等方面。

## 1. 任务类型

db-scheduler 支持三种主要任务类型，分别用于不同的调度场景：

### 1.1 一次性任务 (OneTimeTask)

一次性任务在指定时间执行一次，执行完成后会从调度表中删除。

```java
// 创建一次性任务示例
Tasks.oneTime("invoice-generation", InvoiceData.class)
    .execute((instance, ctx) -> {
        // 任务执行逻辑
        Invoice invoice = invoiceService.generateInvoice(instance.getData().getCustomerId());
        emailService.sendInvoice(invoice);
    });

// 调度一次性任务执行
scheduler.getSchedulerClient().schedule(
    TaskInstance.oneTime("invoice-generation", "customer-123", new InvoiceData("customer-123")),
    Instant.now().plus(Duration.ofHours(2))
);
```

**特点**：

- 执行完成后自动从调度表中删除
- 适用于需要在未来特定时间点执行的操作
- 可以携带执行所需的数据

### 1.2 周期性任务 (RecurringTask)

周期性任务按照预定义的调度计划重复执行，每次执行完成后自动计算下次执行时间。

```java
// 创建固定间隔的周期性任务
Tasks.recurring("daily-report", FixedDelay.ofHours(24))
    .execute((instance, ctx) -> {
        // 任务执行逻辑
        reportService.generateDailyReport();
    });

// 创建基于Cron表达式的周期性任务
Tasks.recurring("weekly-cleanup", CronSchedule.weekly(DayOfWeek.SUNDAY, LocalTime.of(2, 0)))
    .execute((instance, ctx) -> {
        // 每周日凌晨2点执行清理
        cleanupService.performWeeklyCleanup();
    });
```

**特点**：

- 配置一次，自动重复执行
- 支持多种调度计划：FixedDelay、Cron表达式等
- 执行完成后自动计算并更新下次执行时间

### 1.3 自定义任务 (CustomTask)

自定义任务允许开发者完全控制任务的行为，包括执行完成后的处理逻辑。

```java
// 创建自定义任务
Task<MyData> customTask = new CustomTask<MyData>("custom-task", MyData.class) {
    @Override
    public void execute(TaskInstance<MyData> taskInstance, ExecutionContext executionContext) {
        // 自定义执行逻辑
        processData(taskInstance.getData());
    }
    
    @Override
    public ExecutionComplete onDeadExecution(Execution execution, ExecutionOperations executionOperations) {
        // 自定义死亡执行处理逻辑
        return executionOperations.reschedule(execution, Instant.now().plus(Duration.ofMinutes(30)));
    }
};

// 注册自定义任务
scheduler.register(customTask);
```

**特点**：

- 提供最大的灵活性
- 可以自定义所有行为，包括死亡执行处理
- 适用于复杂业务场景

## 2. 任务调度计划

对于周期性任务，db-scheduler 提供了多种调度计划选项：

### 2.1 固定延迟 (FixedDelay)

在上一次执行完成后，延迟固定时间再次执行。

```java
// 创建固定延迟任务
Tasks.recurring("health-check", FixedDelay.ofMinutes(5))
    .execute((instance, ctx) -> {
        // 每5分钟执行一次健康检查
        healthService.checkSystemHealth();
    });
```

### 2.2 Cron 表达式

使用 Cron 表达式定义复杂的执行计划。

```java
// 使用Cron表达式定义执行计划
Tasks.recurring("database-backup", CronSchedule.parse("0 0 1 * * ?"))
    .execute((instance, ctx) -> {
        // 每天凌晨1点执行数据库备份
        backupService.backupDatabase();
    });
```

### 2.3 预定义时间模式

db-scheduler 提供了常用时间模式的便捷方法：

```java
// 每天特定时间执行
Tasks.recurring("daily-summary", CronSchedule.daily(LocalTime.of(23, 0)))
    .execute((instance, ctx) -> {
        // 每天晚上11点执行
        summaryService.generateDailySummary();
    });

// 每周特定时间执行
Tasks.recurring("weekly-report", CronSchedule.weekly(DayOfWeek.MONDAY, LocalTime.of(9, 0)))
    .execute((instance, ctx) -> {
        // 每周一上午9点执行
        reportService.generateWeeklyReport();
    });
```

### 2.4 自定义调度策略

实现 `Schedule` 接口可以创建完全自定义的调度策略：

```java
public class BusinessDaySchedule implements Schedule {
    @Override
    public Instant getNextExecutionTime(Instant lastSuccess) {
        // 计算下一个工作日的上午9点
        LocalDateTime next = LocalDateTime.ofInstant(lastSuccess, ZoneId.systemDefault())
            .plusDays(1)
            .withHour(9)
            .withMinute(0)
            .withSecond(0);
            
        // 如果是周末，调整到下周一
        DayOfWeek dayOfWeek = next.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY) {
            next = next.plusDays(2);
        } else if (dayOfWeek == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        
        return next.atZone(ZoneId.systemDefault()).toInstant();
    }
    
    @Override
    public boolean isDeterministic() {
        return true;
    }
}

// 使用自定义调度策略
Tasks.recurring("business-day-task", new BusinessDaySchedule())
    .execute((instance, ctx) -> {
        // 每个工作日上午9点执行
        businessService.processBusinessDayTasks();
    });
```

## 3. 任务数据传递

db-scheduler 支持在任务调度和执行时传递数据，使任务执行更加灵活。

### 3.1 任务数据类型

任务数据可以是任何可序列化的对象：

```java
// 定义任务数据类
public class OrderProcessData {
    private final String orderId;
    private final BigDecimal amount;
    private final String customerId;
    
    // 构造函数、getter等
}

// 创建使用此数据的任务
Tasks.oneTime("process-order", OrderProcessData.class)
    .execute((instance, ctx) -> {
        OrderProcessData data = instance.getData();
        orderService.processOrder(data.getOrderId(), data.getAmount(), data.getCustomerId());
    });
```

- 直接使用的业务对象（如OrderProcessData、PaymentData等）
- 只包含执行任务所需的业务数据
- 直接传递给TaskInstance：TaskInstance.oneTime("task-name", "id", myData)

### 3.2 序列化与反序列化

任务数据在存储到数据库时会被序列化，执行时再反序列化：

```java
// 配置自定义序列化器
Scheduler scheduler = Scheduler.create(dataSource)
    .serializer(new JacksonSerializer()) // 默认使用Jackson
    .build();
```

### 3.3 任务元数据

可以使用TaskData.create()方法添加额外的元数据：

```java
// 创建带元数据的任务数据
TaskData taskData = TaskData.create(new OrderProcessData("order-123", new BigDecimal("99.95"), "customer-456"))
    .withMetadata("priority", "high")
    .withMetadata("source", "web");

// 调度带元数据的任务
scheduler.getSchedulerClient().schedule(
    TaskInstance.oneTime("process-order", "order-123", taskData),
    Instant.now()
);
```

除了包含原始业务数据外，还可以添加元数据（metadata）

``` java
// 创建带元数据的任务数据
TaskData taskData = TaskData.create(new OrderProcessData("order-123", new BigDecimal("99.95"), "customer-456"))
    .withMetadata("priority", "high")
    .withMetadata("source", "web");
```

## 4. 任务执行上下文

在任务执行期间，db-scheduler 提供 `ExecutionContext` 对象，允许任务与调度器交互：

### 4.1 更新心跳

对于长时间运行的任务，可以手动更新心跳避免被误判为死亡执行：

```java
Tasks.oneTime("long-running-process", ProcessData.class)
    .execute((instance, ctx) -> {
        // 处理大批量数据
        for (int i = 0; i < 10000; i++) {
            processItem(i);
            
            // 每处理100项更新一次心跳
            if (i % 100 == 0) {
                ctx.updateHeartbeat();
            }
            
            // 检查是否被请求取消
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
    });
```

### 4.2 获取调度器客户端

任务可以通过上下文访问调度器客户端，在执行过程中调度新任务：

```java
Tasks.oneTime("generate-reports", ReportRequest.class)
    .execute((instance, ctx) -> {
        ReportRequest request = instance.getData();
        
        // 根据请求数据创建多个子任务
        for (String reportType : request.getReportTypes()) {
            // 调度子任务
            ctx.getSchedulerClient().schedule(
                TaskInstance.oneTime(
                    "generate-single-report",
                    request.getRequestId() + "-" + reportType,
                    new SingleReportData(request.getUserId(), reportType)
                ),
                Instant.now()
            );
        }
    });
```

## 5. 错误处理与恢复

db-scheduler 提供了全面的错误处理机制，确保任务执行的可靠性。

### 5.1 执行失败处理

任务执行失败时，可以自定义处理逻辑：

```java
Tasks.oneTime("payment-processing", PaymentData.class)
    .onFailure((executionComplete, executionOperations) -> {
        // 获取失败信息
        String errorMessage = executionComplete.getFailureInfo().getCause();
        int failures = executionComplete.getExecution().getConsecutiveFailures();
        
        // 记录失败信息
        logger.error("Payment processing failed: {}, attempt: {}", errorMessage, failures);
        
        if (failures < 3) {
            // 失败少于3次，5分钟后重试
            return executionOperations.reschedule(executionComplete, 
                Instant.now().plus(Duration.ofMinutes(5)));
        } else if (failures < 5) {
            // 失败3-4次，30分钟后重试
            return executionOperations.reschedule(executionComplete, 
                Instant.now().plus(Duration.ofMinutes(30)));
        } else {
            // 失败5次或更多，放弃并通知
            notificationService.sendAlert("Payment failed after multiple attempts: " + 
                executionComplete.getExecution().getTaskInstance().getId());
            return ExecutionComplete.failure(executionComplete.getExecution(), 
                "Giving up after " + failures + " attempts");
        }
    })
    .execute((instance, ctx) -> {
        // 支付处理逻辑
        paymentService.processPayment(instance.getData());
    });
```

### 5.2 死亡执行处理

当执行任务的节点崩溃时，其他节点可以接管"死亡执行"：

```java
Tasks.oneTime("critical-transaction", TransactionData.class)
    .onDeadExecution((execution, operations) -> {
        // 记录死亡执行信息
        logger.warn("Dead execution detected for transaction: {}", 
            execution.getTaskInstance().getId());
        
        // 检查事务状态
        TransactionData data = (TransactionData) execution.getTaskInstance().getData();
        TransactionStatus status = transactionService.checkStatus(data.getTransactionId());
        
        if (status == TransactionStatus.COMPLETED) {
            // 事务已完成，可以安全删除
            return operations.delete(execution);
        } else if (status == TransactionStatus.FAILED) {
            // 事务失败，需要人工介入
            notificationService.sendAlert("Transaction requires manual review: " + 
                data.getTransactionId());
            return operations.delete(execution);
        } else {
            // 事务状态未知，重新调度
            return operations.reschedule(execution, Instant.now());
        }
    })
    .execute((instance, ctx) -> {
        // 事务处理逻辑
        transactionService.processTransaction(instance.getData());
    });
```

### 5.3 任务幂等性

为处理潜在的重复执行，任务应设计为幂等操作：

```java
Tasks.oneTime("send-notification", NotificationData.class)
    .execute((instance, ctx) -> {
        String notificationId = instance.getId();
        
        // 检查是否已发送
        if (notificationRepository.isAlreadySent(notificationId)) {
            logger.info("Notification {} already sent, skipping", notificationId);
            return;
        }
        
        // 发送通知
        NotificationResult result = notificationService.send(instance.getData());
        
        // 记录结果
        notificationRepository.markAsSent(notificationId, result);
    });
```

## 6. 最佳实践

### 6.1 任务设计原则

1. **原子性**：任务应设计为原子操作，避免部分完成状态
2. **幂等性**：任务应能安全地多次执行，产生相同结果
3. **独立性**：减少任务间的依赖，提高系统鲁棒性
4. **快速失败**：快速检测并报告错误，避免长时间运行后失败

### 6.2 性能优化

1. **批处理**：将相关的小任务合并为一个批处理任务
2. **数据量控制**：限制任务数据大小，避免序列化/反序列化开销
3. **合理调度**：错开高负载任务的执行时间
4. **任务拆分**：将长时间任务拆分为多个短时间任务

### 6.3 运维建议

1. **监控**：设置监控告警，关注失败率和执行延迟
2. **日志**：添加充分的日志记录，便于排查问题
3. **清理策略**：实施定期清理策略，避免数据表无限增长
4. **版本化**：使用版本号命名任务，便于任务定义变更

## 7. 任务与执行的关系

db-scheduler将**任务定义**和**任务执行**分开处理：

### 7.1 关系模型

```
Task (任务定义)
  |
  |-- 定义业务逻辑
  |-- 定义错误处理策略
  |-- 定义调度计划 (对于周期性任务)
  |
  v
TaskInstance (任务实例)
  |
  |-- 引用特定Task
  |-- 包含唯一ID
  |-- 包含任务数据
  |
  v
Execution (执行)
  |
  |-- 包含执行状态 (picked, lastHeartbeat等)
  |-- 包含执行时间
  |-- 包含失败计数
```

### 7.2 生命周期

1. **创建**：任务在调度器中注册
2. **调度**：创建任务实例并设置执行时间
3. **拾取**：调度器实例锁定并准备执行任务
4. **执行**：运行任务逻辑并维护心跳
5. **完成**：处理执行结果(成功/失败/重试)
6. **删除/重新调度**：完成后的清理或重新安排

这种设计确保了在分布式环境中任务能可靠地执行，即使面临节点故障。
