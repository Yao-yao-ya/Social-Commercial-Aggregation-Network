# Social-Commercial-Aggregation-Network (S.C.A.N)
> 🚀 社交与本地生活聚合网络

## 📖 项目简介
Social-Commercial-Aggregation-Network (S.C.A.N) 是一个整合了**高并发商品秒杀**与**用户社交探店笔记**的综合性 O2O 平台。
本项目致力于解决真实商业场景中的痛点，通过深度使用 Redis 高阶数据结构与消息队列机制，实现了从“单机架构”到“分布式高可用架构”的演进，保证了在亿级流量下的数据一致性与系统高吞吐量。

## 🛠️ 技术栈
* **核心框架**: Spring Boot 2.x, MyBatis-Plus
* **底层存储**: MySQL 8.0+
* **缓存与中间件**: Redis (核心), Redisson
* **基础设施**: Docker, Nginx, 阿里云 OSS (可选)

## ✨ 核心架构与技术亮点

### 1. 极致性能的异步秒杀架构 (SecKill)
彻底重构了传统的同步扣减库存流程，实现了金融级防丢单的秒杀模块。
* **Lua 脚本预检**: 将库存校验与一人一单校验合并为一段 Lua 脚本在 Redis 中原子化执行，避免了并发安全问题，将接口 QPS 提升数倍。
* **Redis Stream 消息队列**: 摒弃了传统的 JVM 阻塞队列或 List/PubSub 方案。利用 Redis 5.0+ 的 `Stream` 数据结构实现消费者组（Consumer Group）监听。
* **PEL 异常兜底机制**: 采用双重循环模型。主线程非阻塞拉取增量消息，副线程利用 `XACK` 与 `PEL (Pending Entries List)` 机制实现异常宕机后的断点续传，确保秒杀订单 **At-least-once** 绝对不丢失。
* **分布式锁**: 引入 `Redisson` 解决集群部署下的并发竞争问题，利用其底层的 WatchDog 看门狗机制防止锁超时释放导致的安全漏洞。

### 2. 高性能社交探店与点赞排行榜 (Social Feed)
针对大 V 笔记发布后的瞬间高频点赞风暴，设计了基于缓存的防刷与排序方案。
* **ZSet 时间戳排序**: 摒弃 MySQL 的全表扫描排序，利用 Redis `ZSet` 数据结构。以用户 ID 为 Member 进行天然去重，以点赞操作的**毫秒级时间戳为 Score** 进行自动排序。
* **极速 TopN 查询**: 通过 `ZRANGE` 指令实现了点赞用户头像排行榜的极速响应，完美解决了传统分页查询在数据高频变动下的跳帧问题。
* **动静分离存储**: 笔记中的图片资源彻底脱离应用服务器，采用 Nginx 反向代理或阿里云 OSS 对象存储，配合 UUID 解决文件名冲突，大幅减轻了 Tomcat 宽带压力。

### 3. 多级缓存与高可用保障
* **缓存穿透**: 采用缓存空对象 (Cache Null Value) 策略应对恶意攻击。
* **缓存雪崩**: 通过为不同业务数据的 TTL 添加随机波动值，防止热点 Key 同时失效引发数据库雪崩。
* **缓存击穿**: 实现了基于**互斥锁 (Mutex)** 和**逻辑过期 (Logical Expiration)** 两种不同策略的热点数据重建方案，兼顾了数据一致性与系统可用性。


**启动 MySQL 8.0:**
```bash
docker run -d --name mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=1234 mysql:8.0.37
