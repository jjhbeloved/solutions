# quartz 设计

## 1. Jobs And Triggers

Quartz 将 Job（业务逻辑） 和 Trigger（调度逻辑） 解耦：

- 提高了灵活性与复用性。
- 支持动态调整调度策略，方便维护和扩展。
- 满足各种复杂的任务调度需求。

### 1.1. 区别

#### **Job（任务）**

- **定义**：Job 是一个执行单元，表示具体要执行的业务逻辑。
- **实现**：需要实现 `Job` 接口并重写其 `execute(JobExecutionContext context)` 方法。
- **职责**：负责描述“**做什么**”。
  - 示例：发送邮件、生成报表等。

#### **Trigger（触发器）**

- **定义**：Trigger 决定任务执行的时间点和条件。
- **实现**：使用 `Trigger` 接口的实现类（如 `SimpleTrigger` 或 `CronTrigger`）。
- **职责**：负责描述“**什么时候执行**”。
  - 示例：每天凌晨2点执行、每隔10分钟执行一次等

## 1.2. 好处

### **解耦**

- **独立性**：Job 和 Trigger 分开，Job 负责逻辑，Trigger 负责调度，彼此不依赖。
- **可复用性**：一个 Job 可以被多个 Trigger 调度。例如，报表生成任务可在不同时间点触发。

### **灵活性**

- Trigger 支持多种类型（如简单触发器和 Cron 表达式），满足各种复杂调度需求。
- Job 可以动态绑定不同的 Trigger，无需修改业务逻辑。

### **易维护性**

- 调整调度时间只需修改 Trigger 配置，减少对 Job 代码的影响，降低维护成本。

### **动态调度**

- 可在运行时动态调整 Trigger 的配置，而不影响 Job 的执行逻辑

## 2. Jobs and Job Details And **Job Instance**

Schedule 每次执行 task 的时候会根据 job 的定义创建新 Job Instance

### 1.1 优点

1. 线程安全性
   1. 每次调度创建一个全新的 Job 实例，避免了多线程访问共享状态时可能出现的并发问题
   2. 不同的job之间是不能共享数据的，因为每次都是创建一个新的 job 实例
2. 垃圾回收友好
   1. 每次执行后立即释放 Job 实例的引用，减少内存占用，使实例能快速被垃圾回收。
3. 代码简单性
   1. 任务类无需考虑状态管理，只需关注单次执行逻辑，降低复杂度。
4. 更灵活的任务配置
   1. 使用 JobDataMap 传递数据，而不是依赖实例变量，使任务配置更灵活，也更容易与外部系统集成

## 3. 功能

### Scheduler

一个JVM进程中维护一个中央调度器(Scheduler)，它负责管理所有的作业(Job)和触发器(Trigger)

1. mode
   1. single
   2. standby
2. shutdown(boolean waitFinished)
   1. true: Wait for Executing Jobs to Finish
   2. false: Do Not Wait for Executing Jobs to Finish

### Job

是执行逻辑，“做什么”的具体实现。

1. Job Detail
2. Job Instance
3. Job State
4. Job Attributes

### Trigger

决定 “什么时候做”，并与 Job 绑定。

1. priority
2. calendars
3. start at
4. schedule
5. cron

### Job Stores

1. RAMJobStore
2. JDBCJobStore
3. TerracottaJobStore(不知道是什么)

### Listener

1. Job Listener
2. Trigger Listener
3. Scheduler Listener
