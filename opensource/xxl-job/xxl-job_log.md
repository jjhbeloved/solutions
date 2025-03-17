# XXL-JOB日志机制设计笔记

## 1. 概述

XXL-JOB作为一个分布式任务调度平台，其日志机制是整个系统中非常重要的一部分，它不仅记录了任务执行的过程和结果，还为问题排查和系统监控提供了重要依据。本文将深入分析XXL-JOB的日志机制设计，解答它是否采用了redo/undo log的设计思路，以及整体日志架构和工作流程。

## 2. XXL-JOB日志架构设计

XXL-JOB的日志机制采用了**双层存储设计**，而非传统数据库的redo/undo log机制：

1. **元数据层**：使用数据库表存储任务执行的关键元数据
2. **内容层**：使用文件系统存储详细的执行日志内容

这种设计有效地平衡了日志查询效率和存储空间的使用，同时避免了大量日志内容对数据库性能的影响。

### 2.1 元数据存储 - 数据库层

XXL-JOB使用MySQL数据库中的`xxl_job_log`表存储任务执行的元数据信息：

```sql
CREATE TABLE `xxl_job_log` (
    `id`                        bigint(20) NOT NULL AUTO_INCREMENT,
    `job_group`                 int(11)    NOT NULL COMMENT '执行器主键ID',
    `job_id`                    int(11)    NOT NULL COMMENT '任务，主键ID',
    `executor_address`          varchar(255)        DEFAULT NULL COMMENT '执行器地址，本次执行的地址',
    `executor_handler`          varchar(255)        DEFAULT NULL COMMENT '执行器任务handler',
    `executor_param`            varchar(512)        DEFAULT NULL COMMENT '执行器任务参数',
    `executor_sharding_param`   varchar(20)         DEFAULT NULL COMMENT '执行器任务分片参数，格式如 1/2',
    `executor_fail_retry_count` int(11)    NOT NULL DEFAULT '0' COMMENT '失败重试次数',
    `trigger_time`              datetime            DEFAULT NULL COMMENT '调度-时间',
    `trigger_code`              int(11)    NOT NULL COMMENT '调度-结果',
    `trigger_msg`               text COMMENT '调度-日志',
    `handle_time`               datetime            DEFAULT NULL COMMENT '执行-时间',
    `handle_code`               int(11)    NOT NULL COMMENT '执行-状态',
    `handle_msg`                text COMMENT '执行-日志',
    `alarm_status`              tinyint(4) NOT NULL DEFAULT '0' COMMENT '告警状态：0-默认、1-无需告警、2-告警成功、3-告警失败',
    PRIMARY KEY (`id`),
    KEY `I_trigger_time` (`trigger_time`),
    KEY `I_handle_code` (`handle_code`),
    KEY `I_jobid_jobgroup` (`job_id`,`job_group`),
    KEY `I_job_id` (`job_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

这张表存储了：
- 任务的基本信息（job_id, job_group等）
- 执行器信息（executor_address, executor_handler等）
- 触发信息（trigger_time, trigger_code, trigger_msg）
- 处理结果（handle_time, handle_code, handle_msg）
- 告警状态（alarm_status）

元数据存储主要用于任务统计、查询和监控，不包含详细的执行日志内容。

### 2.2 内容存储 - 文件系统层

XXL-JOB将详细的执行日志内容存储在文件系统中，具体实现在`XxlJobFileAppender`类中：

```java
/**
 * log base path
 *
 * strut like:
 *  ---/
 *  ---/gluesource/
 *  ---/gluesource/10_1514171108000.js
 *  ---/gluesource/10_1514171108000.js
 *  ---/2017-12-25/
 *  ---/2017-12-25/639.log
 *  ---/2017-12-25/821.log
 *
 */
private static String logBasePath = "/data/applogs/xxl-job/jobhandler";
```

日志文件按照日期和任务ID进行组织，使用以下格式：
- 目录结构：`/日期/任务日志ID.log`
- 例如：`/2022-01-25/1001.log`

文件系统存储了详细的执行过程和结果，包括执行时的标准输出、错误信息等。

## 3. 日志生成与存储流程

### 3.1 任务触发阶段日志

1. 调度中心触发任务时，首先在数据库中创建日志记录：
```java
// 1、保存日志记录
XxlJobLog jobLog = new XxlJobLog();
jobLog.setJobGroup(jobInfo.getJobGroup());
jobLog.setJobId(jobInfo.getId());
jobLog.setTriggerTime(new Date());
XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().save(jobLog);
```

2. 生成与日志记录关联的日志文件路径：
```java
// 文件路径格式：logPath/yyyy-MM-dd/9999.log
String logFileName = XxlJobFileAppender.makeLogFileName(triggerDate, logId);
```

3. 记录任务触发相关信息到数据库：
```java
// 更新触发信息
jobLog.setExecutorAddress(address);
jobLog.setExecutorHandler(jobInfo.getExecutorHandler());
jobLog.setExecutorParam(jobInfo.getExecutorParam());
jobLog.setExecutorShardingParam(shardingParam);
jobLog.setExecutorFailRetryCount(finalFailRetryCount);
jobLog.setTriggerCode(triggerResult.getCode());
jobLog.setTriggerMsg(triggerResult.getMsg());
XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().updateTriggerInfo(jobLog);
```

### 3.2 任务执行阶段日志

1. 执行器接收到任务后，通过`XxlJobHelper`提供的API记录执行日志：
```java
// 记录执行日志示例
XxlJobHelper.log("任务开始执行，参数：{}", param);
```

2. 日志内容通过`XxlJobFileAppender`写入对应的日志文件：
```java
// 追加日志内容到文件
XxlJobFileAppender.appendLog(logFileName, appendLog);
```

3. 任务执行完成后，执行器通过回调API更新任务处理结果：
```java
// 更新任务执行结果
jobLog.setHandleTime(new Date());
jobLog.setHandleCode(handleCode);
jobLog.setHandleMsg(handleMsg);
XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().updateHandleInfo(jobLog);
```

## 4. 日志查询与展示

XXL-JOB管理界面提供了强大的日志查询功能：

1. **分页查询**：根据执行器、任务、时间范围和执行状态等条件查询日志记录
2. **实时查看**：可以实时查看任务执行日志，支持日志内容的增量加载
3. **日志详情**：提供完整的任务执行参数、触发信息、执行结果和详细日志内容

实现原理：
1. 先从数据库查询符合条件的日志元数据
2. 根据日志ID构建日志文件路径
3. 从文件系统读取详细日志内容
4. 将元数据和详细内容整合后展示给用户

## 5. 日志清理机制

XXL-JOB提供了两种日志清理机制，分别针对数据库和文件系统：

### 5.1 数据库日志清理

通过`JobLogReportHelper`线程定期清理过期的数据库日志记录：

```java
// 配置中设置日志保留天数
if (XxlJobAdminConfig.getAdminConfig().getLogretentiondays() > 0
        && System.currentTimeMillis() - lastCleanLogTime > 24*60*60*1000) {
    // 清理过期日志
    Date clearBeforeTime = DateUtil.addDays(new Date(), -1 * XxlJobAdminConfig.getAdminConfig().getLogretentiondays());
    List<Long> logIds = XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().findClearLogIds(0, 0, clearBeforeTime, 0, 1000);
    if (logIds != null && logIds.size() > 0) {
        XxlJobAdminConfig.getAdminConfig().getXxlJobLogDao().clearLog(logIds);
    }
}
```

系统默认每天执行一次清理操作，可通过配置文件设置日志保留天数。

### 5.2 文件系统日志清理

通过`JobLogFileCleanThread`线程定期清理过期的日志文件：

```java
// 清理过期日志文件
// 默认保留30天
if ((todayDate.getTime() - logFileCreateDate.getTime()) >= logRetentionDays * (24 * 60 * 60 * 1000)) {
    FileUtil.deleteRecursively(childFile);
}
```

该线程会检查日志目录中的子目录（按日期命名），删除超过保留期限的日志文件目录。

## 6. 与数据库redo/undo log的区别

**XXL-JOB的日志机制与传统数据库的redo/undo log有本质区别**：

1. **目的不同**：
   - 数据库redo/undo log主要用于保证事务的ACID特性和数据库的崩溃恢复
   - XXL-JOB的日志机制主要用于记录任务执行过程和结果，方便问题追踪和排查

2. **实现机制不同**：
   - 数据库redo log记录数据修改后的值，用于崩溃后重做操作
   - 数据库undo log记录数据修改前的值，用于事务回滚
   - XXL-JOB的日志是业务层面的日志记录，不涉及数据恢复机制

3. **使用场景不同**：
   - redo/undo log主要在数据库内部使用，对应用透明
   - XXL-JOB的日志直接面向用户，提供可视化界面查询和管理

## 7. 设计优势分析

XXL-JOB的日志机制设计有以下几个优势：

1. **分层存储**：
   - 元数据存储在数据库中，方便索引和查询
   - 详细内容存储在文件系统中，降低数据库压力，提高性能

2. **日志隔离**：
   - 每个任务执行实例有独立的日志文件
   - 避免不同任务日志内容相互干扰

3. **实时查看**：
   - 支持实时查看任务执行日志
   - 有助于问题快速定位和处理

4. **自动清理**：
   - 自动清理过期日志，避免日志无限增长
   - 数据库和文件系统日志同步管理

5. **高性能**：
   - 使用文件系统存储大量日志内容
   - 避免了大量文本内容存储在数据库中导致的性能问题

## 8. 结论

XXL-JOB的日志机制不是采用传统数据库的redo/undo log设计，而是一种面向任务调度场景的特定日志解决方案。它采用数据库+文件系统的双层架构，有效平衡了查询效率和存储性能，为任务执行提供了完善的可追溯性和问题排查能力。

这种设计非常适合分布式任务调度系统，既满足了运维人员对任务执行情况的监控需求，又避免了大量日志内容对系统性能的影响。同时，自动化的日志清理机制也降低了系统维护的复杂度。

总之，XXL-JOB的日志机制是一个精心设计的、面向特定场景的日志解决方案，它是整个XXL-JOB系统中不可或缺的重要组成部分。 