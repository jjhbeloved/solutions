# RingCentral SMC架构方案

## 文档概述

本文档详细描述了RingCentral自主研发的SMC(Session Management Controller)架构，这是RingCentral将Connect First(Engage Voice)接入自有电信网络过程中的核心技术组件。本设计方案于2019年Q3-Q4确定，并在2020-2021年间实施完成。

## 1. SMC简介

### 1.1 什么是SMC

SMC(Session Management Controller)是RingCentral自主研发的新一代会话管理控制器，作为企业级通信平台的核心组件，负责管理、控制和路由所有类型的通信会话。SMC替代了传统的开源Asterisk系统，为RingCentral提供更高级的呼叫控制能力、更好的可扩展性和更高的可靠性。

### 1.2 设计目标

- **统一架构**：建立统一的会话管理架构，支持RingCentral所有通信产品线
- **高可靠性**：实现N+1冗余架构，保证99.999%的服务可用性
- **高扩展性**：支持水平扩展，满足不断增长的业务需求
- **低延迟**：通信路径优化，端到端延迟<50ms
- **媒体优化**：优化媒体处理和路由，提高语音质量
- **安全加密**：支持端到端加密通信
- **运营商级功能**：实现符合CLEC标准的电信级功能集

## 2. 架构设计原则

### 2.1 云原生设计

- 基于Kubernetes容器化部署
- 微服务架构，功能模块化
- 无状态设计，支持弹性扩展
- API驱动的服务交互

### 2.2 多层冗余

- 地理分散式部署
- 组件级高可用设计
- 数据多副本存储
- 自动故障检测和恢复

### 2.3 安全优先

- 媒体和信令加密
- 多层访问控制
- 实时安全监控
- 合规认证支持(HIPAA, GDPR, PCI DSS等)

## 3. SMC核心组件

### 3.1 会话管理层

```plantuml
@startuml SMC核心组件
package "SMC核心架构" {
  [会话控制器(SC)] as SC
  [媒体服务器(MS)] as MS
  [信令网关(SG)] as SG
  [路由引擎(RE)] as RE
  [策略服务器(PS)] as PS
  [会话数据库(SDB)] as SDB
  [监控系统(MS)] as MON
  
  SC --> MS : 媒体控制
  SC --> SG : 信令转换
  SC --> RE : 路由请求
  SC --> PS : 策略查询
  SC <--> SDB : 会话存储/查询
  SC <-- MON : 健康监测
}

cloud "外部系统" {
  [PSTN网络] as PSTN
  [SIP中继提供商] as SIP
  [企业PBX] as PBX
  [RingCentral应用] as APP
}

SG <--> PSTN : SIP/SS7协议
SG <--> SIP : SIP协议
SG <--> PBX : SIP/H.323协议
SC <--> APP : API接口

@enduml
```

- **会话控制器(Session Controller)**：处理所有通信会话的建立、维护和终止
- **信令网关(Signaling Gateway)**：处理不同信令协议之间的转换和互通
- **路由引擎(Routing Engine)**：基于策略和状态进行智能呼叫路由
- **策略服务器(Policy Server)**：管理通信策略和规则配置
- **会话数据库(Session Database)**：存储会话状态和历史记录

### 3.2 媒体处理层

- **媒体服务器(Media Server)**：处理媒体流转发、混合和处理
- **转码引擎(Transcoding Engine)**：处理不同编解码器之间的转换
- **媒体质量监控(Media Quality Monitor)**：实时监控音视频质量
- **录音服务(Recording Service)**：提供通话录音和存储功能

### 3.3 互联层

- **PSTN互联网关**：连接传统电话网络
- **SIP互联服务**：连接SIP中继提供商
- **WebRTC网关**：支持基于浏览器的通信
- **API网关**：提供外部系统集成接口

## 4. 网络拓扑结构

```plantuml
@startuml SMC网络拓扑
cloud "互联网" as Internet
cloud "PSTN网络" as PSTN

package "RingCentral骨干网" {
  node "东海岸数据中心" as DC1 {
    [SMC集群1] as SMC1
    [媒体处理集群1] as Media1
    [数据存储1] as DB1
  }
  
  node "西海岸数据中心" as DC2 {
    [SMC集群2] as SMC2
    [媒体处理集群2] as Media2
    [数据存储2] as DB2
  }
  
  node "欧洲数据中心" as DC3 {
    [SMC集群3] as SMC3
    [媒体处理集群3] as Media3
    [数据存储3] as DB3
  }
  
  node "亚太数据中心" as DC4 {
    [SMC集群4] as SMC4
    [媒体处理集群4] as Media4
    [数据存储4] as DB4
  }
  
  DC1 <--> DC2 : 专用光纤
  DC1 <--> DC3 : 专用光纤
  DC1 <--> DC4 : 专用光纤
  DC2 <--> DC3 : 专用光纤
  DC2 <--> DC4 : 专用光纤
  DC3 <--> DC4 : 专用光纤
  
  DB1 <..> DB2 : 数据同步
  DB1 <..> DB3 : 数据同步
  DB1 <..> DB4 : 数据同步
  DB2 <..> DB3 : 数据同步
  DB2 <..> DB4 : 数据同步
  DB3 <..> DB4 : 数据同步
}

node "边缘PoP节点" as Edge {
  [边缘SMC] as EdgeSMC
  [边缘媒体节点] as EdgeMedia
}

package "客户环境" as Customer {
  [Contact Center] as CC
  [UC客户端] as UC
  [WebRTC客户端] as WebRTC
}

Internet <--> Edge
PSTN <--> DC1
PSTN <--> DC2
PSTN <--> DC3
PSTN <--> DC4
Edge <--> DC1
Edge <--> DC2
Edge <--> DC4
Edge <--> DC3
Customer <--> Edge
Customer <--> Internet

@enduml
```

### 4.1 全球分布式架构

- 东西海岸核心数据中心
- 欧洲和亚太区域数据中心
- 200+全球边缘PoP接入点
- 多层级互联网络

### 4.2 流量管理

- BGP Anycast智能路由
- 跨地域负载均衡
- 流量优先级控制
- 动态拥塞管理

## 5. SMC与Asterisk对比

| 功能特性 | SMC架构 | Asterisk架构 |
|---------|--------|-------------|
| **架构设计** | 云原生分布式 | 单体应用 |
| **扩展性** | 水平无限扩展 | 垂直扩展受限 |
| **冗余模式** | 多区域N+1冗余 | 主备模式 |
| **容量** | 单集群支持10万+并发会话 | 单实例1000-2000并发 |
| **会话管理** | 集中式+分布式混合 | 仅本地会话管理 |
| **媒体处理** | 分布式媒体服务器集群 | 集中式媒体处理 |
| **信令协议** | 原生多协议支持 | 需插件扩展 |
| **延迟指标** | <50ms端到端延迟 | 不稳定，可能>200ms |
| **运维复杂度** | 自动化管理 | 手动管理为主 |
| **监控能力** | 全链路实时监控 | 基础监控功能 |

## 6. 通信流程

### 6.1 基本呼叫流程

```plantuml
@startuml 基本呼叫流程
participant "客户端" as Client
participant "信令网关" as SG
participant "会话控制器" as SC
participant "路由引擎" as RE
participant "媒体服务器" as MS

Client -> SG: 呼叫请求(SIP INVITE)
SG -> SC: 转发呼叫请求
SC -> RE: 路由查询
RE -> SC: 返回路由决策
SC -> SG: 建立呼叫响应
SG -> Client: 呼叫接通响应
Client -> MS: 建立媒体流
@enduml
```

### 6.2 Engage Voice集成流程(Latest)

```plantuml
@startuml Engage Voice集成流程
participant "Engage Voice" as EV
participant "SMC网关" as Gateway
participant "SMC核心" as SMC
participant "RingCentral骨干网" as Backbone
participant "PSTN网络" as PSTN

EV -> Gateway: 外呼请求
Gateway -> SMC: 转换为SMC会话请求
SMC -> SMC: 应用呼叫策略和路由决策
SMC -> Backbone: 通过骨干网路由
Backbone -> PSTN: 发送到PSTN网络
PSTN --> Backbone: 呼叫建立响应
Backbone --> SMC: 呼叫状态更新
SMC --> Gateway: 转发呼叫状态
Gateway --> EV: 更新呼叫状态

@enduml
```

### 6.3 Engage Voice集成流程(Old)

```plantuml
@startuml Engage Voice旧集成流程
participant "Engage Voice\n(Connect First)" as EV
participant "Asterisk系统" as Asterisk
participant "SIP中继提供商" as SIPTrunk
participant "PSTN网络" as PSTN

EV -> Asterisk: 外呼请求
Asterisk -> Asterisk: SIP配置处理
Asterisk -> SIPTrunk: SIP INVITE请求
SIPTrunk -> PSTN: 转换为PSTN呼叫
PSTN --> SIPTrunk: 呼叫应答
SIPTrunk --> Asterisk: SIP 200 OK响应
Asterisk --> EV: 呼叫状态更新

note over EV, PSTN
  在SMC实施前，Engage Voice(原Connect First)通过
  传统Asterisk系统连接到第三方SIP中继提供商，
  再由第三方提供商连接到PSTN网络
end note

@enduml
```

## 7. 技术规格与性能指标

### 7.1 基础规格

- 单一SMC集群支持10万+并发通话会话
- 呼叫建立时间<200ms
- 端到端延迟<50ms
- 会话建立成功率>99.999%
- 系统可用性>99.999%

### 7.2 媒体处理能力

- 支持多种编解码(G.711, G.722, Opus, SILK等)
- 支持实时转码和混音
- 媒体加密(SRTP/ZRTP)
- 自适应带宽调整
- 丢包隐藏与恢复

### 7.3 安全特性

- TLS/SRTP加密所有通信
- 多因素认证
- 实时威胁监测
- DDoS防护能力
- 合规安全日志

## 8. 向SMC的迁移策略

### 8.1 迁移阶段

1. **评估阶段**：分析Connect First基础设施和通信模式
2. **设计阶段**：确定SMC集成架构和迁移路径
3. **平行测试**：部署SMC并与Asterisk并行运行
4. **逐步切换**：按客户群分批迁移
5. **完全迁移**：停用Asterisk组件

### 8.2 风险管控

- 双系统并行运行
- 灰度发布策略
- 实时监控与回滚机制
- 客户沟通与支持计划

## 9. 业务价值

### 9.1 成本效益

- 降低40-60%的PSTN互联成本
- 降低基础设施维护成本
- 提高运营效率

### 9.2 质量提升

- 降低30-50%的平均网络延迟
- 提高15-25%的外呼连接率
- 降低40%以上的通话掉线率

### 9.3 功能增强

- 支持大规模外呼活动
- 提供实时质量监控
- 启用高级合规功能

### 9.4 战略价值

- 为AI和自动化提供基础架构
- 创造市场差异化优势
- 支持全渠道通信愿景

## 10. 参考链接

1. [RingCentral Unified Communications Reference Architecture](https://support.ringcentral.com/ca/en/network-and-system-requirements/network-requirements/overview/ringcentral-unified-communications-reference-architecture.html)
2. [RingCentral Contact Center Central 2020 Release Notes](https://support.ringcentral.com/ca/en/release-notes/customer-engagement/contact-center/release-notes-summer-2020.html)
3. [RingCentral Voice Quality Monitoring Technical Overview](https://partnersupport.ringcentral.com/network-and-system-requirements/network-requirements/overview/ringcentral-unified-communications-reference-architecture.html)
4. [Session Management Controller: The Next Evolution of the SBC](https://www.nojitter.com/session-management-controller-next-evolution-sbc) (业界参考文章)
5. [RingCentral Session Border Controller (SBC) Configuration Guide](https://support.ringcentral.com/article/8186.html)

---

## 附录A: SMC核心技术规格

| 组件 | 规格详情 |
|------|---------|
| **会话控制器** | 单节点支持5000会话，水平扩展至N节点 |
| **媒体服务器** | 单节点支持2000流，水平扩展 |
| **信令吞吐量** | 每秒10万+SIP消息处理能力 |
| **路由决策速度** | <5ms路由决策时间 |
| **存储系统** | 分布式NoSQL数据库，多区域复制 |
| **网络要求** | 数据中心间<5ms网络延迟 |
| **计算资源** | 基于Kubernetes编排，自动扩缩容 |
| **监控系统** | 秒级指标采集，毫秒级异常检测 |

## 附录B: 部署检查清单

- [ ] 数据中心网络连接验证
- [ ] SMC核心组件部署
- [ ] 媒体服务器集群配置
- [ ] PSTN互联网关配置
- [ ] SIP中继提供商集成
- [ ] 路由策略配置
- [ ] 负载均衡器设置
- [ ] 安全策略应用
- [ ] 监控告警配置
- [ ] 灾备演练
