# 第一轮真实交易性能优化准备（2026-10-24）

用户选择先完成代码、稍后压测。本轮完成代码与脚本验证，尚未启动新版本业务应用、交易冒烟或性能负载。当前正式性能数据仍是优化前的 33.511 单/s、下单 P95 1599 ms；第一轮优化的提升幅度待前后对照确认。

## 已实现

| 改动 | 行为 |
| --- | --- |
| 独立通知线程池 | 通知发送使用 notifyThreadPoolExecutor，默认 4 线程、128 队列；营销查询保留原查询线程池，开发配置队列调整为 128。通知池满时由调用线程执行，形成背压，消息可靠性仍依赖落库的 notify_task。 |
| 商品配置缓存 | 使用已有 Guava，缓存上限 1024 项、默认存活 5 秒，合并相同商品的并发加载。遵守缓存总开关，异常不缓存；提供本机失效方法。订单状态和库存占用量不进入此缓存。 |
| 查询时间预算 | 将原来的 500 分钟等待改为可配置的总预算，默认 3000 ms；两个 Future 共用预算，异常或超时取消未完成任务。 |
| 两条订单索引 | 新增 (team_id, status, out_trade_no) 与 (user_id, out_trade_no, status) 的迁移脚本。尚未执行到数据库。 |
| 测试配置 | 更新 Surefire 并允许通过命令行显式运行指定新测试；默认仍跳过原有需要开发数据库的测试。 |

商品缓存允许最多约 5 秒的配置刷新延迟；需要即时生效的调价应在数据库提交后调用失效方法，多实例还需同步失效。日历库存容量仍逐请求核对数据库，原有库存原子计数与数据库事务保护保持生效。当前未改造团队库存 Lua，也未调整 MySQL 刷盘持久性或 Hikari 最大连接数。

## 已完成的验证

- JDK 17 构建七个模块成功。
- 7 项 Java 单元测试通过：并发商品加载只查一次、显式失效重载、关闭缓存走新读、失败后可恢复、超时取消、两个线程池实际装配、通知阻塞时查询仍可执行。
- 3 项 Python 保护测试通过：容器归属错误阻止数据变更、初始化 SQL 只指向固定对照库、低内存阻止启动。
- Python 脚本编译通过。
- JMeter 5.6.3 实际加载计划并编译 Groovy，业务 HTTP 请求为 0。
- 新版本真实交易回归、索引执行计划和前后性能提升尚待运行验证。

构建日志、JUnit XML 与校验记录位于 results/optimization-compare-20261024-165756/。

## 基线与优化构建

基线已经修复上轮发现的交易正确性问题，并通过六类真实 HTTP 回归。两版本均有独立归档：

- 基线 SHA-256：d7282bd0d3f226bf8161f285738a642bccc435473b1d3d843f51f4bf391f0479
- 优化版 SHA-256：103d4e58884adc169ae2a0178b45514dbb33543b46ae952ce682ef1bbccaa52d
- 基线 jar、源码快照及优化 jar、源码快照、文件哈希和初始化脚本已保存。当前比较包见 results/optimization-compare-20261024-165756/prepared.json。

## 稍后运行

从真实项目 group-buy-travel 根目录执行：

    python tests/performance/jmeter/compare_real_trade.py --prepare-only
    python tests/performance/jmeter/compare_real_trade.py --validate-only

用户恢复压测后执行：

    python tests/performance/jmeter/compare_real_trade.py --run

该命令先对两版本分别执行交易冒烟和六类并发正确性检查，再按“基线、优化、优化、基线、基线、优化”执行六轮 50 并发对照。每轮 60 秒、预热 15 秒；两版本分别取三轮中位数计算吞吐变化与 P95 降幅。此前 33.511 单/s 的历史结果只作参考，提升比例使用同一次对照中新测的基线。

每轮保持 JVM 堆、HTTP 线程数、Hikari 最大连接数 10、数据库持久性配置、种子数据及请求模型相同。版本差异包括本轮代码、查询队列和两条新增索引。脚本额外对两版本使用相同的本机 Actuator 指标及关闭端点，尝试正常关闭 Kafka 消费者，采集 Hikari 活跃/等待连接、获取连接计时，以及各轮 SQL 次数和耗时差值。

## 数据与运行保护

- 使用专用 group-buy-perf 三只容器，启动与重置前核验 Compose 项目标签。
- 每轮重建固定的 cultural_tourism_group_buy_compare 库；原 cultural_tourism_group_buy 库保留。
- 重置前先保存并校验专用测试 Redis 的 RDB 备份，再清空专用测试 Redis；不会连接开发 Redis。对照库与 Redis 最终保留最后一轮状态，各轮原始请求和核验结果留档。
- 每阶段使用新测试 Kafka 主题，原测试主题保留。
- 初次启动要求至少 2048 MiB 可用内存；容器已分配后的阶段切换至少预留 1024 MiB。负载期间连续三次低于 512 MiB 则停止升压。
- 支付由测试回调驱动，Kafka 验证到消费标记。性能业务错误、库存差异或通知核验失败会停止后续轮次。
- 仅当两版本各三轮完整通过，才输出前后变化百分比。

## 单元验证命令

    mvn -B -DskipTests=false -Dtest=TradePerformanceUnitTest -DfailIfNoTests=false package
    python -m unittest discover -s tests/performance/jmeter -p test_comparison_guards.py -v
