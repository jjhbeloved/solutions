# db-scheduler多节点任务获取流程说明

本文档解释了PlantUML图表(`cluster-task-acquisition.puml`)中展示的db-scheduler多节点任务获取流程，详细说明了该调度系统如何确保：
- 不漏掉任何任务数据
- 实现多节点间的负载均衡
- 防止任务并发执行

## 关键机制

### 1. 基于数据库的协调

db-scheduler使用共享数据库作为协调中心，所有节点状态和任务元数据都存储在数据库中。这种设计有几个关键优势：
- 无需专门的协调服务器
- 无单点故障
- 与现有的数据库系统无缝集成

### 2. 乐观锁和任务锁定

为了防止多个节点并发执行同一任务，db-scheduler使用乐观锁机制：

```sql
UPDATE scheduled_tasks 
SET picked = true, picked_by = ?, last_heartbeat = ? 
WHERE task_name = ? AND task_instance = ? AND picked = false
```

- 只有当任务处于未锁定状态(`picked=false`)时，更新才会成功
- 所有节点可能同时查询到同一任务，但只有一个节点能成功锁定
- 成功锁定的节点会记录自己的标识符(`picked_by`)，便于故障追踪

### 3. 批量轮询

为提高效率，每个节点会批量查询待执行任务：

```sql
SELECT * FROM scheduled_tasks 
WHERE picked = false AND execution_time <= ? 
ORDER BY priority DESC, execution_time ASC
LIMIT ?
```

- 优先级排序确保重要任务优先执行
- 时间排序确保最早的任务先执行
- 批量大小限制控制单次处理量

### 4. 心跳更新机制

节点执行任务时会定期更新心跳时间戳：

```sql
UPDATE scheduled_tasks 
SET last_heartbeat = ? 
WHERE task_name = ? AND task_instance = ? AND picked = true AND picked_by = ?
```

- 心跳更新与实际任务执行在不同线程并行进行
- 心跳间隔可配置，通常为几秒钟
- 心跳记录确保长时间运行的任务不会被误判为失败

### 5. 死亡执行检测与恢复

当节点崩溃时，其心跳更新会停止。其他节点通过以下查询检测这种情况：

```sql
SELECT * FROM scheduled_tasks 
WHERE picked = true 
AND last_heartbeat < ?
```

其中超时时间计算为`当前时间 - (心跳间隔 × 允许错过的心跳次数)`。

一旦检测到死亡执行，节点会将其重置为待执行状态：

```sql
UPDATE scheduled_tasks SET 
picked = false, 
picked_by = null, 
last_heartbeat = null, 
execution_time = ? 
WHERE task_name = ? AND task_instance = ?
```

### 6. 负载均衡方式

db-scheduler采用去中心化的负载均衡模式：
- 所有节点平等，无主从之分
- 每个节点独立轮询和执行任务
- 自然形成的负载平衡基于节点处理能力
- 轮询随机抖动减少争用

## 执行保证语义

db-scheduler提供以下执行保证：

1. **最多执行一次**：通过乐观锁确保一个任务在任何时刻最多只被一个节点执行。

2. **至少执行一次**：通过死亡执行检测和恢复确保任务不会因节点崩溃而被遗忘。

3. **最终执行一次**：结合上述两点，在网络和节点正常工作的前提下，每个任务理论上将恰好执行一次。

## 最佳实践

为确保系统的可靠性，推荐采用以下最佳实践：

1. **任务幂等设计**：所有任务应设计为幂等操作，即多次执行产生与单次执行相同的结果。

2. **结果存储**：关键任务应在业务表中记录执行结果，便于判断是否已处理。

3. **心跳调优**：根据任务执行特性调整心跳间隔和超时阈值。

4. **数据库优化**：确保`scheduled_tasks`表有适当的索引，特别是对`picked`和`execution_time`字段。

5. **监控告警**：实施监控系统，关注死亡执行数量和积压任务数。

## 结论

db-scheduler通过精心设计的数据库协调机制，在不引入额外复杂组件的情况下，实现了分布式环境中可靠的任务调度。其核心优势在于简单性和与关系型数据库的紧密集成，使团队能够轻松部署和维护。 