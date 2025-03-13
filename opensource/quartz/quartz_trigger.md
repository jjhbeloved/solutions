# Trigger

设计目的:

- 作为所有触发器的基础抽象，定义触发器的核心行为和属性
- 提供统一的接口，使调度器能够以一致的方式处理不同类型的触发器

核心功能:

- 定义触发器的基本属性（名称、组、优先级等）
- 提供获取下一次触发时间的方法
- 定义触发器的**生命周期状态**（NORMAL, PAUSED, COMPLETE, ERROR等）
- 支持与JobDetail的关联

关键方法:

``` java
Date getNextFireTime();  // 获取下一次触发时间
Date getPreviousFireTime();  // 获取上一次触发时间
Date getFinalFireTime();  // 获取最后一次触发时间
TriggerKey getKey();  // 获取触发器的唯一标识
int getPriority();  // 获取优先级
```

## 优先级机制

当多个触发器在同一时刻触发时，Quartz使用优先级来决定执行顺序：

```java
// 设置触发器优先级（默认为5）
trigger.setPriority(10);  // 数值越高，优先级越高
```

优先级机制的关键点：

- 默认优先级为5（Trigger.DEFAULT_PRIORITY）
- 优先级值越高，触发器越先被执行
- 相同优先级的触发器按照FIFO（先进先出）顺序执行
- 优先级仅影响"同一时刻"需要触发的多个触发器

应用场景：

- 关键业务任务赋予高优先级
- 资源密集型任务赋予低优先级
- 避免系统过载时的竞争情况

## 设计模式与原则分析

### 接口分离

Quartz触发器体系通过分层接口实现了关注点分离：

1. `Trigger` 接口定义**公共行为**
   1. `SimpleTrigger` 有限次数任务的触发器
      1. COMPLETE状态的触发器不会再被触发，但仍然保留在JobStore中，直到被显式删除
   2. `CronTrigger` **重复执行**模式触发器
   3. `CalendarIntervalTrigger` 基于日历的固定间隔触发器
   4. `DailyTimeIntervalTrigger` 在每天特定时间段内按固定间隔触发
2. `OperableTrigger` 接口添加**内部操作能力**
3. 具体实现类处理特定调度逻辑

### 触发器类型比较

| 触发器类型 | 主要用途 | 特点 | 适用场景 |
|------------|----------|------|----------|
| SimpleTrigger | 在特定时间点执行一次或多次 | 简单、直观、支持重复次数和间隔 | 定时报告、系统启动任务 |
| CronTrigger | 基于Cron表达式的复杂调度 | 高度灵活、支持复杂的重复模式 | 每天/每周/每月定时任务 |
| CalendarIntervalTrigger | 基于日历的固定间隔 | 考虑了月份长度和夏令时变化 | 需要精确间隔的长期任务 |
| DailyTimeIntervalTrigger | 每天固定时间段内按间隔执行 | 指定工作日和每天的活动时间窗口 | 工作时间内的周期性任务 |

> **注意**：别混淆 CalendarIntervalTrigger（一种触发器类型）和 Calendar（Quartz 中用于排除特定时间点的组件）。它们是完全不同的概念。

## Misfire（错过触发）处理

当调度器关闭或线程池资源不足时，触发器可能会错过其计划的触发时间，这称为"misfire"。Quartz提供了多种策略来处理这种情况：

### 通用Misfire策略

```java
// 设置Misfire阈值（毫秒）
org.quartz.jobStore.misfireThreshold = 60000
```

### 特定触发器的Misfire策略

**SimpleTrigger**:

```java
// 忽略错过的触发，立即触发一次，然后按正常间隔继续
simpleTrigger.setMisfireInstruction(
    SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT);

// 忽略所有错过的触发，按照原定计划继续
simpleTrigger.setMisfireInstruction(
    SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT);
```

**CronTrigger**:

```java
// 立即触发一次，然后按Cron表达式继续
cronTrigger.setMisfireInstruction(
    CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW);

// 等待下一个Cron触发时间
cronTrigger.setMisfireInstruction(
    CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
```

## 状态机

- **持久化**：所有这些状态都会被持久化到JobStore中（如数据库），确保系统重启后状态不丢失。
- 恢复机制：当调度器重启时，它会检查所有触发器的状态，并根据需要执行恢复操作：
  - BLOCKED状态的触发器可能会被重置为NORMAL（如果对应的Job不再运行）
  - ERROR状态的触发器可能会被尝试重新执行
- 集群环境：在集群环境中，触发器状态**对所有节点可见**，确保同一触发器不会在多个节点同时执行。
- Misfire处理：当触发器错过触发时间（例如调度器关闭期间），根据配置的Misfire策略，触发器可能保持原状态或转换到其他状态

### 状态解释

#### NORMAL ≠ 正在执行

- NORMAL状态只表示**触发器处于活跃状态，可以被触发**
- 触发器在等待触发和正在执行期间都保持NORMAL状态

NORMAL状态并不直接意味着触发器"被一个scheduler拿到并开始执行"。它的确切含义是：

- 触发器已被成功存储到JobStore中
- **触发器处于活跃状态**，等待其触发条件满足
- 触发器可以被触发执行（当到达其下一个触发时间时）

当触发器处于NORMAL状态时，它可能处于以下几种具体情况之一：

- 等待下一次触发时间到达
- 已到达触发时间，正在等待线程池中的工作线程执行
- 正在被执行（Job正在运行）

### 完整的Trigger状态表

| 状态 | 描述 | 转换条件 |
|------|------|----------|
| NONE | 初始状态，尚未持久化 | 调度后→NORMAL |
| NORMAL | 活跃状态，等待触发 | 暂停→PAUSED, 完成→COMPLETE, 出错→ERROR, 阻塞→BLOCKED |
| PAUSED | 已暂停，不会触发 | 恢复→NORMAL, 暂停组→PAUSED, 删除→删除状态 |
| COMPLETE | 已完成所有执行 | 永久状态直到被删除 |
| ERROR | 触发时发生错误 | 修复后→NORMAL |
| BLOCKED | 触发被阻塞（通常因为@DisallowConcurrentExecution的Job实例仍在运行） | 阻塞解除→NORMAL |

## 触发器与Calendar的交互

> **重要概念澄清**：Quartz 中的 Calendar 组件与 java.util.Calendar 或 CalendarIntervalTrigger 完全不同。Calendar 是 Quartz 中专门用于定义"排除时间"的组件，用于告诉触发器某些时间点不应该触发任务。

Calendar组件用于排除特定时间段，如节假日或维护窗口：

```java
// 创建一个周末日历（排除周末）
WeeklyCalendar weekendCalendar = new WeeklyCalendar();
weekendCalendar.setDayExcluded(Calendar.SATURDAY, true);
weekendCalendar.setDayExcluded(Calendar.SUNDAY, true);

// 向调度器添加日历
scheduler.addCalendar("weekendCalendar", weekendCalendar, false, false);

// 将日历关联到触发器
trigger.setCalendarName("weekendCalendar");
```

Calendar的作用机制：

- 当触发器计算下一次触发时间时，会检查关联的Calendar
- 如果下一次触发时间被Calendar排除，则会跳过该时间点
- 支持多种Calendar类型：AnnualCalendar、MonthlyCalendar、WeeklyCalendar、DailyCalendar、CronCalendar

### Calendar与CalendarIntervalTrigger的区别

| 特性 | Calendar | CalendarIntervalTrigger |
|------|----------|-------------------------|
| 作用 | 排除特定时间点不执行任务 | 按固定日历间隔执行任务 |
| 类型 | 组件（可关联到任何触发器） | 触发器类型 |
| 主要用途 | 定义"不执行"时间（节假日等） | 定义"执行"间隔（每月/每季度等） |
| 示例 | WeeklyCalendar排除周末 | 每3个月执行一次任务 |

### CalendarIntervalTrigger示例

```java
// 创建每季度执行一次的触发器
CalendarIntervalTriggerImpl trigger = new CalendarIntervalTriggerImpl();
trigger.setName("quarterlyTrigger");
trigger.setGroup("DEFAULT");
trigger.setJobKey(jobKey("quarterlyJob", "DEFAULT"));
trigger.setStartTime(new Date()); 
trigger.setRepeatIntervalUnit(DateBuilder.IntervalUnit.MONTH);
trigger.setRepeatInterval(3); // 每3个月执行一次

// 可以将Calendar组件关联到CalendarIntervalTrigger
// 例如，排除法定节假日
trigger.setCalendarName("holidayCalendar");
```

### 实际应用中的状态转换示例

1. 定时报表任务：
   - 创建CronTrigger → NONE
   - 调度到调度器 → NORMAL
   - 每天执行 → 保持NORMAL
   - 管理员暂停报表 → PAUSED
   - 管理员恢复报表 → NORMAL
   - 报表服务出错 → ERROR
   - 修复错误 → NORMAL
2. 有限次数任务：
   - 创建SimpleTrigger(重复10次) → NONE
   - 调度到调度器 → NORMAL
   - 执行10次后 → COMPLETE
   - 最终被清理 → 删除
3. 不允许并发的长时间任务：
   - 创建触发器(每小时执行一次) → NONE
   - 调度到调度器 → NORMAL
   - 第一次执行(耗时90分钟) → 保持NORMAL
   - 一小时后再次触发 → BLOCKED (因为上一个实例仍在运行)
   - 第一个实例完成 → NORMAL
   - 继续下一次执行

## TriggerListener的设计与运行机制详解

TriggerListener接口是Quartz中的一个核心监听器接口，它允许应用程序在触发器生命周期的关键点接收通知并执行自定义逻辑

### TriggerListener的运行机制

注册阶段:

- 通过ListenerManager注册TriggerListener实例
- 可以指定 `Matcher` 来**控制哪些触发器会通知该监听器**
- 一个触发器可以通知多个监听器，一个监听器也可以监听多个触发器

### 匹配机制

1. `KeyMatcher`: 基于触发器的键（名称和组）进行匹配
2. `GroupMatcher`: 基于触发器的组进行匹配
3. `AndMatcher.and(), OrMatcher.or()`
4. `EverythingMatcher`: 匹配所有触发器

### TriggerListener实现示例

```java
public class AuditTriggerListener implements TriggerListener {
    @Override
    public String getName() {
        return "AuditTriggerListener";
    }
    
    @Override
    public void triggerFired(Trigger trigger, JobExecutionContext context) {
        System.out.println("触发器 " + trigger.getKey() + " 已触发");
        // 可以记录审计日志、发送通知等
    }
    
    @Override
    public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
        // 返回true会阻止作业执行
        if (isSystemInMaintenance()) {
            return true; // 系统维护期间不执行任务
        }
        return false;
    }
    
    @Override
    public void triggerMisfired(Trigger trigger) {
        System.out.println("触发器 " + trigger.getKey() + " 错过触发");
        // 可以发送告警通知
    }
    
    @Override
    public void triggerComplete(Trigger trigger, JobExecutionContext context,
                               Trigger.CompletedExecutionInstruction triggerInstCode) {
        System.out.println("触发器 " + trigger.getKey() + " 完成执行，结果码: " + triggerInstCode);
        // 可以执行后续处理
    }
}

// 注册监听器
scheduler.getListenerManager().addTriggerListener(
    new AuditTriggerListener(),
    GroupMatcher.triggerGroupEquals("CRITICAL_TRIGGERS")
);
```

## 集群环境中的触发器行为

Quartz支持集群环境，其中多个调度器实例共享同一组触发器和作业：

```properties
# 启用集群配置
org.quartz.jobStore.isClustered = true
org.quartz.jobStore.clusterCheckinInterval = 20000
```

集群中的触发器特性：

- 触发器状态在集群中同步共享
- 一个触发器仅由集群中的一个节点执行
- 当一个节点失败时，其触发器可以被其他节点接管
- JobStore（通常是JDBC-JobStore）负责协调触发器的分布式执行

集群环境中的锁机制：

- 使用数据库锁防止同一触发器被多个节点同时执行
- 每个节点定期向数据库发送"心跳"更新
- 处理节点故障的"失效转移"机制

实际应用考虑：

- 确保所有节点的时钟同步
- 适当设置clusterCheckinInterval
- 考虑数据库性能和连接池配置
- 监控触发器执行的分布情况

## JobExecutionContext与触发器的交互

当触发器触发时，Quartz创建JobExecutionContext对象，它包含了作业执行环境的所有信息：

```java
public interface JobExecutionContext {
    JobDetail getJobDetail();
    Trigger getTrigger();
    Calendar getCalendar();
    boolean isRecovering();
    JobDataMap getMergedJobDataMap();
    JobDataMap getTriggerJobDataMap();
    JobDataMap getJobDetailJobDataMap();
    Scheduler getScheduler();
    Date getFireTime();
    Date getScheduledFireTime();
    Date getPreviousFireTime();
    Date getNextFireTime();
    // ...其他方法
}
```

重要概念：

1. **上下文的生命周期**：
   - 每次触发器触发时创建新的Context实例
   - 仅在作业执行期间有效
   - 作业执行完成后由Quartz清理

2. **运行时数据的来源**：
   - `getTrigger()` 获取触发本次执行的触发器
   - `getFireTime()` 实际触发时间（可能与计划时间有差异）
   - `getScheduledFireTime()` 计划触发时间
   - `getNextFireTime()` 下一次触发时间

3. **访问合并的JobDataMap**：

   ```java
   // 在Job实现中访问上下文
   public void execute(JobExecutionContext context) throws JobExecutionException {
       // 获取合并后的JobDataMap（JobDetail和Trigger的数据合并，Trigger值优先）
       JobDataMap mergedData = context.getMergedJobDataMap();
       
       // 单独获取Trigger的JobDataMap
       JobDataMap triggerData = context.getTriggerJobDataMap();
       
       // 获取触发器实例
       Trigger trigger = context.getTrigger();
       
       // 获取执行计划信息
       Date fireTime = context.getFireTime();
       Date nextFireTime = context.getNextFireTime();
   }
   ```

### JobDataMap在触发器中的应用

触发器可以携带自己的JobDataMap，这些数据会在每次触发时传递给Job实例：

```java
// 创建带有数据的触发器
TriggerBuilder<Trigger> builder = TriggerBuilder.newTrigger()
    .withIdentity("myTrigger", "group1")
    .startNow()
    .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(10).repeatForever())
    .usingJobData("triggerId", "T001")
    .usingJobData("triggerName", "DataTrigger")
    .usingJobData("lastUpdated", System.currentTimeMillis());

// 在Job中获取数据
public void execute(JobExecutionContext context) throws JobExecutionException {
    String triggerId = context.getTrigger().getJobDataMap().getString("triggerId");
    String triggerName = context.getTrigger().getJobDataMap().getString("triggerName");
    long lastUpdated = context.getTrigger().getJobDataMap().getLong("lastUpdated");
    
    // 使用这些数据...
}
```

数据合并规则：
- 如果JobDetail和Trigger都定义了同名的数据项，Trigger的值会覆盖JobDetail的值
- 可以通过`getMergedJobDataMap()`获取合并后的数据
- 数据类型支持所有可序列化的Java对象，常用的有String、Integer、Long、Float、Boolean等

## TriggerBuilder与DSL风格的API

Quartz 2.0+引入了DSL风格的构建器API，提供了更好的可读性和类型安全：

### 不同类型触发器的创建示例

**SimpleTrigger**:

```java
// 创建一个每30秒触发一次，重复10次的触发器
Trigger simpleTrigger = TriggerBuilder.newTrigger()
    .withIdentity("mySimpleTrigger", "group1")
    .startAt(startTime)
    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
        .withIntervalInSeconds(30)
        .withRepeatCount(10))
    .build();
```

**CronTrigger**:

```java
// 创建一个每周一至周五的上午8:30触发的触发器
Trigger cronTrigger = TriggerBuilder.newTrigger()
    .withIdentity("myCronTrigger", "group1")
    .withSchedule(CronScheduleBuilder.cronSchedule("0 30 8 ? * MON-FRI"))
    .build();
```

**CalendarIntervalTrigger**:

```java
// 创建一个每2个月触发一次的触发器
Trigger calendarIntervalTrigger = TriggerBuilder.newTrigger()
    .withIdentity("myCalendarIntervalTrigger", "group1")
    .withSchedule(CalendarIntervalScheduleBuilder.calendarIntervalSchedule()
        .withInterval(2, DateBuilder.IntervalUnit.MONTH))
    .build();
```

**DailyTimeIntervalTrigger**:

```java
// 创建一个工作日9:00-17:00之间每小时触发一次的触发器
Trigger dailyTrigger = TriggerBuilder.newTrigger()
    .withIdentity("myDailyTrigger", "group1")
    .withSchedule(DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
        .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(9, 0))
        .endingDailyAt(TimeOfDay.hourAndMinuteOfDay(17, 0))
        .onDaysOfTheWeek(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, 
                          Calendar.THURSDAY, Calendar.FRIDAY)
        .withInterval(1, IntervalUnit.HOUR))
    .build();
```

## 触发器的生命周期管理

### 创建与注册

```java
// 创建触发器
Trigger trigger = TriggerBuilder.newTrigger()
    .withIdentity("myTrigger", "group1")
    .startNow()
    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
        .withIntervalInSeconds(10)
        .repeatForever())
    .build();

// 将触发器与作业关联并注册到调度器
scheduler.scheduleJob(jobDetail, trigger);

// 或者单独添加触发器（如果JobDetail已经存在）
scheduler.scheduleJob(trigger);
```

### 修改触发器

```java
// 获取触发器
TriggerKey triggerKey = TriggerKey.triggerKey("myTrigger", "group1");
Trigger oldTrigger = scheduler.getTrigger(triggerKey);

// 创建修改后的触发器（注意：保持相同的TriggerKey）
Trigger newTrigger = oldTrigger.getTriggerBuilder()
    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
        .withIntervalInSeconds(15)  // 修改间隔时间
        .repeatForever())
    .build();

// 重新调度触发器
scheduler.rescheduleJob(triggerKey, newTrigger);
```

### 暂停与恢复

```java
// 暂停单个触发器
scheduler.pauseTrigger(triggerKey);

// 暂停触发器组
scheduler.pauseTriggers(GroupMatcher.triggerGroupEquals("group1"));

// 恢复单个触发器
scheduler.resumeTrigger(triggerKey);

// 恢复触发器组
scheduler.resumeTriggers(GroupMatcher.triggerGroupEquals("group1"));
```

### 删除触发器

```java
// 删除单个触发器
scheduler.unscheduleJob(triggerKey);

// 删除多个触发器
List<TriggerKey> triggerKeys = new ArrayList<>();
triggerKeys.add(TriggerKey.triggerKey("trigger1", "group1"));
triggerKeys.add(TriggerKey.triggerKey("trigger2", "group1"));
scheduler.unscheduleJobs(triggerKeys);
```

### 触发器持久化

触发器在JobStore中的持久化方式取决于JobStore的实现：

**RAMJobStore**（内存存储）:

- 触发器存储在内存中
- 不支持持久化，调度器重启后数据丢失
- 适合临时或测试场景

**JDBCJobStore**（数据库存储）:

```properties
# 配置JDBC JobStore
org.quartz.jobStore.class = org.quartz.impl.jdbcjobstore.JobStoreTX
org.quartz.jobStore.driverDelegateClass = org.quartz.impl.jdbcjobstore.StdJDBCDelegate
org.quartz.jobStore.dataSource = myDS
org.quartz.jobStore.tablePrefix = QRTZ_
```

数据库表结构:

- `QRTZ_TRIGGERS`: 存储基本触发器信息
- `QRTZ_SIMPLE_TRIGGERS`: 存储SimpleTrigger特定信息
- `QRTZ_CRON_TRIGGERS`: 存储CronTrigger特定信息
- `QRTZ_FIRED_TRIGGERS`: 存储当前正在执行的触发器信息

触发器序列化:

- JobDataMap中的自定义对象必须实现`java.io.Serializable`接口
- 如果使用`org.quartz.jobStore.useProperties=true`，则所有JobDataMap值必须是字符串

## 触发器的最佳实践

1. **明确命名**：使用有意义的命名约定，例如：

   ```java
   trigger.withIdentity("dailyReport-trigger", "reporting-group");
   ```

2. **设置描述**：为触发器添加详细描述，便于管理和调试：

   ```java
   trigger.withDescription("每天早上8:30生成日报表的触发器");
   ```

3. **错误处理**：实现JobListener处理作业执行错误：

   ```java
   scheduler.getListenerManager().addJobListener(new ErrorHandlingJobListener());
   ```

4. **优先使用DSL API**：使用TriggerBuilder而不是直接实例化触发器类

5. **Misfire处理**：为每种触发器类型选择适当的Misfire策略

6. **避免过度使用**：不要创建过多触发器（数千个）指向同一时间点，会导致性能问题

7. **使用组织功能**：利用组和Tags来组织触发器：

   ```java
   trigger.withIdentity("trigger1", "reporting");
   // 后续可以按组操作
   scheduler.pauseTriggerGroup("reporting");
   ```

8. **监控和管理**：实现自定义TriggerListener进行监控和审计
