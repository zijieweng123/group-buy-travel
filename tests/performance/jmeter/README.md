# 真实拼团交易链路压测

目标项目是 `group-buy-travel`，请求实际的订单与结算接口，不经过票务助手的 SSE 对话接口。

## 第一轮性能优化进展（2026-10-24）

已完成独立通知线程池、5 秒商品配置缓存、查询总时间预算与两条索引迁移脚本。7 项 Java 单元测试和 3 项脚本保护测试通过，JMeter 零业务请求校验通过。用户选择稍后压测，新版本尚未执行真实交易回归或性能对照，索引尚未写入数据库。

稍后使用 compare_real_trade.py --run 交替运行基线和优化版，各三轮 50 并发；提升比例按新一轮对照计算。准备记录、构建哈希、数据保护与运行命令见 [优化准备说明](OPTIMIZATION-2026-10-24.md)。

## 已完成的优化前基线（2026-10-24）

真实交易冒烟、六类并发正确性检查及 10/25/50/50/50 并发五轮测试均已完成。50 并发三轮中位数：成功下单 **33.511 单/s**、下单 **P95 1599 ms**；性能业务失败数为 0，库存、团队和 Kafka 消费核验全部通过。

本机整机 CPU 采样均值约 83%–93%，结果属于当前环境的真实交易基线。测试期间修复了启动参数、空标签范围、责任链补偿、重复结算和并发成团问题。详细条件、原始证据与限制见 [真实压测结果](RESULTS-2026-10-24.md)，早先准备阶段记录见 [准备检查记录](PREPARATION-2026-10-24.md)。专用测试进程和容器已停止，数据卷与结果保留。

## 压测内容与完成边界

一个完整流程包含两个真实订单和一个双人团：

1. 团长 `POST /api/v1/group-buy/orders`：实名格式校验、请求防重、日历库存占用、MySQL 团队与订单明细提交。
2. 团长 `PUT /api/v1/group-buy/orders/settlement`：将订单置为已支付，返回真实 teamId。
3. 成员携带 teamId 下单：走团队库存与数据库锁单数量条件更新。
4. 成员结算：完成团队计数并事务写入 notify_task。
5. 异步 Kafka broker ACK、消费端接收，并写入 Redis 消费幂等标记。

支付结算由测试生成通知请求；不调用支付网关。当前消费者只记录消息与幂等标记，景区出票接口仍是预留位置。因此结果可表述为“锁单落库/支付结算/成团消息已消费”，不能表述为外部支付或景区出票成功量。

库存模型为 MySQL 配置容量、Redis 记录占用量；核验“容量-占用”和实际订单数，不假定 MySQL available_stock 会逐单扣减。

## 隔离环境

[compose.loadtest.yml](compose.loadtest.yml) 只使用本机环回地址，不复用开发服务数据：

| 服务 | 专用容器 | 本机端口 |
| --- | --- | ---: |
| MySQL 8.0.32 | group-buy-perf-mysql | 33306 |
| Redis 7.4 | group-buy-perf-redis | 36379 |
| Kafka 3.9.1 | group-buy-perf-kafka | 39092 |
| 真实拼团应用 | 独立 Java 进程 | 38091 |

测试初始化副本从项目原 SQL 生成，仅在专用库内建立数据。所有测试订单使用合成游客、测试护照号、独立活动和用户；notifyType 固定为 MQ，避免发送 HTTP 通知。

Kafka 使用 Apache 镜像，启动形式参考 [官方快速开始](https://kafka.apache.org/quickstart/)。测试固定版本与资源预算写在 compose 文件中。

## 准备与运行

本机已具备 JDK 17、Maven、JMeter、Python 3.11 与 psutil。新环境需先安装相应依赖。示例从真实项目根目录执行：

```powershell
$env:JAVA_HOME='C:/Users/19445/.jdks/ms-17.0.16'
$env:PERF_JAVA='C:/Users/19445/.jdks/ms-17.0.16/bin/java.exe'
$env:JMETER_JAR='G:/Astudy/Java/autumn-recruitment/Project/travel-ticket-assistant/target/perf-tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar'

# 生成隔离数据库初始化副本；不启动容器或发送请求
python .\tests\performance\jmeter\run_real_trade.py --prepare-only

# 实际加载 JMeter 计划并编译 Groovy；发送零条 HTTP 请求
python .\tests\performance\jmeter\run_real_trade.py --validate-only

# 修改业务源码后先重新打包；脚本会拒绝使用比源码旧的 jar
mvn -DskipTests package

# 释放内存后，先验证两个订单、一次成团及 Kafka 消费
python .\tests\performance\jmeter\run_real_trade.py --smoke-only

# 冒烟通过后，执行正确性用例及 10/25/50 并发阶梯
# 50 并发重复三轮，每轮 60 秒，前 15 秒为预热
python .\tests\performance\jmeter\run_real_trade.py --users 10 25 50 50 50 --duration 60 --warmup 15
```

默认启动需要整机至少 2048 MiB 可用内存；运行时每约 5 秒采样，连续三次可用内存低于 512 MiB 则停止负载。高 CPU 记录为资源约束。应用堆为 64m/320m，JMeter 为 32m/128m；两者均使用 ActiveProcessorCount=2。应用使用 20 个 HTTP 工作线程、最多 200 个连接，Hikari 最大连接数 10。

流程到期后不再开启新交易，已开始的四步流程继续完成；避免强制停止制造半截订单。脚本只终止自己启动的 Java 进程，停止专用容器，保留专用数据卷；不会停止开发容器。可用 `--keep-containers` 在连续调试中保留容器。

## 正确性门槛

吞吐测试开始前执行 [check_consistency.py](check_consistency.py) 中的六个场景：

- 同一用户、同外部单号 20 个并发下单请求：订单只能生成一份，库存只占用一次。
- 同一订单 20 个并发结算请求：双人团完成数只能增加一次，不得提前成团或生成通知任务。
- 日历仅 5 张票、50 个不同用户：最多成功 5 单，其余应按库存不足拒绝。
- 同活动/票种的两个日期分别设置 3/4 张票：两天库存分别耗尽，互不串用。
- 双人团最后一席、50 个成员竞争：只允许一人加入，结算后只生成对应成团通知并被 Kafka 消费。
- 同一团队的两笔未支付订单同时结算：10 个双人团全部达成，仅为各团生成一条成团通知并消费。

每轮性能测试结束核对：

- 所有创建/结算接口必须 HTTP 200、业务码 0000，且订单/用户/团队标识匹配。
- JTL 创建成功数（含预热）= MySQL 实际订单数；无重复订单，全部结算完成。
- Redis 日历占用数=订单数；团队 lock_count/complete_count 不超过 target_count，成团状态为 COMPLETE=1。
- 完成团队数=notify_task 数=broker ACK 成功任务数=Redis 消费幂等标记数，重试/失败任务为 0。

任何业务或数据核验失败均停止后续升压，并保留失败记录，不把接口返回成功替代最终一致性。

## 指标口径与结果

每轮目录保留 `samples.jtl`、JMeter 日志、初始化时间、资源采样、实际运行参数和 `summary.json`；总状态为 `status.json` / `REPORT.md`，最新目录索引为 `results/latest.json`。

- 成功下单 TPS：测量窗口内 order-leader/order-member 的成功请求数除以固定测量时长，两个创建接口合并统计。
- 结算 TPS：两个 settlement 操作单独统计。
- 完整成团流程/s：trade-flow 统计，每次包含两个订单；只计四步 HTTP 流程，不包含异步消息消费耗时，不会把父样本和子请求相加。
- P95/P99：测量窗口内成功操作的最近秩百分位；错误率与业务断言另列，只有零业务错误且数据门槛通过才能记为通过。
- 吞吐及百分位剔除预热；最终数据库核验包含预热。
- CPU/内存采样覆盖 JMeter 生命周期，包含启动与预热，不等同于纯测量窗口。
- 本轮建立真实交易基线，不自动产生优化前后提升比例；此前助手对话链路的 73.4% / 37.7% 不适用于此项目。
