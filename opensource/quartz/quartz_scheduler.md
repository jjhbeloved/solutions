# quartz scheduler

## `SchedulerFactory` and `Scheduler`

- SchedulerFactory负责创建和初始化Scheduler实例
- Scheduler是实际执行任务调度的核心组件
  - 一个JVM进程中维护一个中央调度器(Scheduler)，它负责管理所有的作业(Job)和触发器(Trigger)
- 典型使用流程是先通过SchedulerFactory获取Scheduler实例，然后使用该实例进行任务调度

### Q&A

- scheduler name 和 scheduler instance id 的区别
  - scheduler name：调度器的逻辑名称，用于在应用中标识特定的调度器。多个应用可能使用相同名称的调度器访问同一组任务
  - scheduler instance id：特定调度器实例的唯一标识符。在集群环境中尤为重要，用于区分同一组任务的不同执行实例
  - scheduler name更像是"调度器类型"的标识，而instance id则是特定实例的唯一标识
- SchedulerContext 的作用
  - SchedulerContext是一个类似于Map的存储结构，主要用途：
    - 在应用程序和调度器之间共享数据
    - 存储全局配置信息
    - 为所有Job提供统一访问的数据容器
    - 可在运行时修改，动态影响调度器行为
- start 和 startDelayed 的目的
  - start()：立即启动调度器，开始执行已调度的任务
  - startDelayed(int seconds)：延迟指定秒数后启动调度器
- standby 的作用
  - standby将调度器置于"待机"模式： **暂停**所有触发器的**执行**，但**保持调度器活跃**
- scheduler 的 metadata 是什么
  - 调度器名称和实例ID
  - 是否处于待机模式或已关闭
  - 是否支持持久化存储
  - 是否运行在集群模式
  - 线程池大小和类型
  - 作业存储类型
  - Quartz版本信息
- JobExecutionContext 是什么
  - 是**任务执行时的上下文对象**
    - 每次任务执行时创建一个新的JobExecutionContext实例
    - 包含当前执行的任务相关信息（JobDetail、Trigger、调度时间等）
    - 提供访问SchedulerContext的途径
    - 允许任务在执行过程中获取和存储数据
    - 一个调度器可以同时执行多个任务，因此会有多个JobExecutionContext实例
- ListenerManager是什么
  - ListenerManager是监听器管理组件：
    - 管理JobListener、TriggerListener和SchedulerListener
    - 允许对调度器中的各种事件进行监听
    - 支持全局监听器和针对特定Job/Trigger的监听器
    - 可实现任务执行前后的自定义逻辑
- scheduleJob 和 addJob 的区别
  - schedule job
    - 一步完成任务的添加和调度
  - add job
    - 仅添加Job定义，不会调度执行

### DirectSchedulerFactory  和 StdSchedulerFactory 区别

>（StdSchedulerFactory和DirectSchedulerFactory）本质上都是配置QuartzSchedulerResources来初始化调度器的

- 设计模式
  - DirectSchedulerFactory 单例模式，通过getInstance()获取唯一实例
  - StdSchedulerFactory 可创建多个实例，每个可加载不同配置
- 抽象级别
  - DirectSchedulerFactory：低级API，直接操作组件
  - StdSchedulerFactory：高级API，面向配置
- 使用便捷性
  - DirectSchedulerFactory：更复杂但控制更精细
  - StdSchedulerFactory：更简单，有合理默认值

#### QuartzSchedulerResources

> QuartzSchedulerResources本质上体现了"组件组装"设计模式，将所有依赖收集在一个地方，然后一次性传递给目标对象，使**系统更加模块化和可测试**

- 资源集合器：收集创建QuartzScheduler所需的所有组件和配置
- 配置持有者：存储调度器的各种配置参数
- 组件管理器：管理调度器依赖的核心组件引用
- 传递媒介：作为工厂与调度器实例之间的桥梁

``` text 
┌─────────────────────────────────────────┐
│     QuartzSchedulerResources            │
├─────────────────────────────────────────┤
│ ✓ 基本信息:                              │
│   - name (调度器名称)                    │
│   - instanceId (实例ID)                 │
│   - threadName (调度线程名)              │
│                                        │
│ ✓ 核心组件:                              │
│   - ThreadPool (线程池)                  │
│   - JobStore (作业存储)                  │
│   - JobRunShellFactory                 │
│   - SchedulerPlugins (插件列表)         │
│   - ThreadExecutor (线程执行器)         │
│                                        │
│ ✓ 功能配置:                             │
│   - RMI相关配置                         │
│   - JMX暴露控制                         │
│   - 批处理窗口配置                       │
│   - 线程守护配置                         │
└─────────────────────────────────────────┘
```

### QuartzScheduler(核心类)

QuartzScheduler是整个Quartz调度框架的**核心引擎**，提供了调度系统的实际实现。它是一个复杂的组件，负责调度器的所有底层功能

``` text
┌─────────────────┐        ┌─────────────────┐
│  StdScheduler   │───────>│ QuartzScheduler │
└─────────────────┘  代理  └─────────────────┘
                              │
                              │ 使用
                              ▼
┌─────────────────┐        ┌─────────────────┐
│  SchedulerFactory│───────>│SchedulerResources│
└─────────────────┘  配置  └─────────────────┘
```

- StdScheduler是一个门面类，将API请求代理到QuartzScheduler
- QuartzScheduler持有QuartzSchedulerResources，获取所需的资源组件
- QuartzSchedulerResources由SchedulerFactory配置并提供给QuartzScheduler

功能分工:

- 提供外部API接口
- 管理所有调度资源
- 处理添加/移除任务和触发器
- 管理整体调度状态
- 协调各组件交互

#### 核心职责

1. 执行调度逻辑
   1. 实际操作调度线程(QuartzSchedulerThread)
   2. 计算触发器的下一次触发时间
   3. 管理任务的执行周期
2. 资源协调者
   1. 使用QuartzSchedulerResources整合各种依赖组件
   2. 与JobStore交互进行数据存储和检索
   3. 管理ThreadPool执行任务
3. 事件通知中心
   1. 包含复杂的事件通知系统
   2. 管理各种监听器(JobListener、TriggerListener、SchedulerListener)
   3. 在任务生命周期的各个阶段发送通知
4. 状态管理器
   1. 维护调度器的各种状态(运行中、暂停、关闭等)
   2. 处理任务执行异常
   3. 支持集群和分布式操作

#### 重要组件

- `QuartzSchedulerThread`：专门负责**触发器扫描**和**任务触发**的后台线程
- ExecutingJobsManager：跟踪当前执行中的任务
- ErrorLogger：处理调度器错误的内部监听器
- SchedulerSignaler：用于线程间通信
- 内部监听器集合：管理各类事件监听

### SchedulerContext 和 SchedulerMetaData 区别

**调度器的状态**是**系统内部信息**，通过`SchedulerMetaData`提供**只读访问**，而SchedulerContext则用于存储和共享应用程序数据

#### 核心区别

- 用途不同：Context用于数据共享，MetaData用于状态报告
- 可变性：**Context可修改**，**MetaData只读**
- 内容来源：**Context由用户填充**，**MetaData由系统生成**
- 数据类型：Context内容不固定，MetaData结构固定

### QuartzSchedulerThread

QuartzSchedulerThread的核心业务职责是**持续监控触发器并在适当时机触发任务执行**

- 扫描并获取即将触发的触发器
- 等待直到触发时间到达
- 触发任务执行
- 处理执行结果和异常情况

``` text
┌─────────────────────────────────────────┐
│              主循环开始                  │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│          检查是否处于暂停状态            │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│          获取线程池可用线程数            │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│       从JobStore获取即将触发的触发器     │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│       等待直到最近的触发时间到达         │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│       将触发器标记为已触发状态           │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│       为每个触发器创建执行外壳           │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│       将执行外壳提交到线程池             │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│       等待下一轮触发器扫描               │
└───────────────────┬─────────────────────┘
                    ▼
                 循环继续
```

业务优化设计:

- 批量处理：一次获取和处理多个触发器，减少数据库交互
- 预获取：提前获取未来一段时间内的触发器，提高响应性
- 时间窗口：将接近同一时间的触发器一起处理，优化资源使用
- 随机化等待：使用随机化的等待时间，避免多个节点同时竞争资源
- 成本效益分析：在决定是否放弃当前触发器时考虑成本和收益
- 自适应重试：根据失败情况动态调整重试策略

## JobStore & SchedulerSignaler

``` text
┌─────────────────┐  信号   ┌─────────────────┐
│    JobStore     │────────>│SchedulerSignaler│
└─────────────────┘         └────────┬────────┘
                                     │
                                     │ 实现
                                     ▼
                            ┌─────────────────┐
                            │ QuartzScheduler │
                            └─────────────────┘
                                     │
                                     │ 通知
                                     ▼
                            ┌─────────────────┐
                            │QuartzSchedulerThread
                            └─────────────────┘
```

### SchedulerSignaler

`SchedulerSignaler`是Quartz调度框架中的一个关键接口，它定义了一组方法，用于`JobStore`与`QuartzScheduler`之间的**通信机制**。这个接口充当了调度系统内部组件之间的信号传递通道。

1. 解耦组件：将JobStore与QuartzScheduler解耦，使它们可以独立演化
2. 反向通信：提供从JobStore到调度器的反向通信通道
3. 事件驱动：支持事件驱动的架构，使调度器能够响应JobStore中的变化


## ThreadPool 和 ThreadExecutor

``` text
┌─────────────────┐     使用     ┌─────────────────┐
│   Scheduler     │─────────────>│    ThreadPool   │
└─────────────────┘              └────────┬────────┘
                                          │
                                          │ 可能使用
                                          ▼
                                 ┌─────────────────┐
                                 │  ThreadExecutor │
                                 └─────────────────┘
```

> ThreadExecutor作为一个辅助组件，可能被ThreadPool用来执行它创建的线程。ThreadExecutor提供了一层抽象，允许自定义线程的执行策略（例如，使用不同的线程组、优先级或安全上下文）

- 抽象级别
  - ThreadPool：更高级抽象，管理线程资源池
  - ThreadExecutor：更底层抽象，定义执行单个线程的策略
- 使用场景
  - ThreadPool在调度器启动时创建，整个生命周期存在
  - ThreadExecutor可以根据需要实例化，提供灵活的执行策略

## JobRunShellFactory

> 负责创建JobRunShell实例

``` text
QuartzSchedulerThread
        │
        │ 当触发器触发时
        ▼
StdJobRunShellFactory
        │
        │ createJobRunShell(triggerBundle)
        ▼
    JobRunShell
        │
        │ run()
        ▼
    实际Job执行
```

### JobRunShell

> 为Job的执行提供了一个安全的运行环境。这个类负责**Job执行的整个生命周期管理**，包括执行前准备、实际执行、异常处理和执行后的清理工作
>
> JobRunShell是一个执行环境提供者，**不是shell命令执行器**

#### 架构

``` text
QuartzSchedulerThread
        │
        │ 创建请求
        ▼
JobRunShellFactory
        │
        │ 创建
        ▼
    JobRunShell
        │
        │ 提交
        ▼
    ThreadPool
        │
        │ 执行
        ▼
      Job实例
```

#### 流程

``` text
┌─────────────────────────────────────────┐
│              初始化阶段                  │
│  - 创建Job实例                          │
│  - 构建JobExecutionContext              │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│              执行前通知                  │
│  - 通知触发器监听器                      │
│  - 通知Job监听器                        │
│  - 处理可能的否决(Veto)                  │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│              执行Job                    │
│  - 调用Job.execute()方法                │
│  - 捕获所有异常                         │
│  - 记录执行时间                         │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│              执行后处理                  │
│  - 通知Job监听器完成                    │
│  - 更新触发器状态                        │
│  - 通知触发器监听器完成                  │
└───────────────────┬─────────────────────┘
                    ▼
┌─────────────────────────────────────────┐
│              完成处理                    │
│  - 处理触发器指令                        │
│  - 通知JobStore任务完成                  │
│  - 清理资源                             │
└─────────────────────────────────────────┘
```

## ListenerManager

> 负责**管理各种监听器(Listeners)**。它提供了一套完整的API，用于注册、配置和移除不同类型的事件监听器，使应用程序能够响应调度系统中发生的各种事件
>
> 管理JobListener、TriggerListener和SchedulerListener

设计原则：

- 关注点分离：将监听器管理从Scheduler接口中分离出来
- 单一职责：专注于监听器的注册和管理
- 开放/封闭原则：系统行为可以通过添加新的监听器扩展，而无需修改核心代码
- 观察者模式：提供了一个标准化的观察者模式实现
