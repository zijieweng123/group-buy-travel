# 2026-10-24 真实拼团压测准备记录

## 已完成

- 找到真实项目 group-buy-travel：Spring Boot 2.7.12 多模块，订单接口 /api/v1/group-buy/orders，结算接口 /api/v1/group-buy/orders/settlement。
- 使用 JDK 17 运行 mvn -B -o -DskipTests package，七个模块构建成功。该命令跳过测试，不是业务验证。
- 新增独立 Compose、JMeter 四步交易计划、业务断言、最终数据核验、五类并发正确性用例、内存保护和运行报告。
- 2026-10-24 13:13:49–13:13:55 运行 --validate-only：JMeter 成功加载计划，Groovy 编译成功，初始化样本一条、业务 HTTP 请求零条。
- 用户选择“先完成脚本和环境准备，稍后运行”，尚未运行应用交易冒烟、并发正确性用例或吞吐测试。

## 环境初始化问题

原始基础脚本创建 group_buy_market，扩展脚本却 USE cultural_tourism_group_buy。首次专用 MySQL 初始化实际返回 ERROR 1049。

修正仅发生在生成的 bootstrap 副本：统一为 cultural_tourism_group_buy，并清除样例订单/通知任务；开发 SQL 与业务代码保持原状。初始化失败的本轮专用容器与数据卷在核验 Compose 标签归属后已清理，日志保留于 results/setup-initialization/mysql-initialization-failure.log。修正后已验证 MySQL/Redis 健康启动，目标库 11 张表存在、订单与通知任务均为 0、Redis PING=PONG；环境检查后测试容器已停止并保留正确初始化的数据卷。记录见 results/setup-initialization/verification.json。仍未启动应用或发送交易请求。

## 后续冒烟应先核实的业务问题

以下是静态代码检查发现，尚未通过交易请求复现或修复：

1. 成团状态更新不一致：group_buy_order_mapper.xml 的 updateOrderStatus2COMPLETE 写 status=2；GroupBuyOrderEnumVO 定义 COMPLETE=1、FAIL=2。数据门槛要求 COMPLETE=1，当前代码很可能无法通过单团冒烟。
2. 重复支付结算风险：group_buy_order_list_mapper.xml 的 updateOrderStatus2COMPLETE 只按 user_id/out_trade_no 更新，没有 status=0 条件。并发重复回调能否重复增加 complete_count，需要五类正确性用例中的 duplicate-settlement 验证。
3. 消费端边界：TeamSuccessTopicListener 仅写 Redis 消费幂等标记并记录日志，外部出票调用仍是注释预留。测试不把消费标记当作景区实际出票完成。

首次运行应先执行 --smoke-only；一旦失败，保留请求/SQL 状态证据、修复并验证，再开展性能测量。不要为让压测变绿而放宽状态枚举或库存核验门槛。

## 运行前提

- 释放整机内存，完整测试启动预留至少 2 GiB。
- Docker 引擎可用，所需 Kafka 镜像完整下载。
- 专用 MySQL、Redis、Kafka 健康。
- 实际交易冒烟与五类正确性门槛通过。

当前没有真实交易 TPS/P95、吞吐提升比例或“全链路通过”的结论。

## 最终准备核验

- 最终版本 --validate-only 再次通过（2026-10-24 13:23），Python 编译、JMeter 计划加载与 Groovy 编译均通过，业务请求为 0。
- 以模拟 100 MiB 可用内存验证启动保护会拒绝继续，未启动应用或 JMeter 负载。
- MySQL/Redis 初始化与连通性、Kafka broker 健康、单分区测试主题创建/描述均通过；Kafka 镜像摘要和健康记录保存在 results/setup-initialization/verification.json。
- 专用容器均已停止，正确初始化的数据卷及镜像保留，便于稍后运行。尚无真实交易 TPS/P95 结果。

## 后续执行（用户于 2026-10-24 授权开始压测）

上述“尚未运行”描述对应准备阶段。正式运行已完成：六类回归和五轮性能测试通过，业务缺陷修复与结果见 [真实压测结果](RESULTS-2026-10-24.md)。50 并发三轮中位数为成功下单 33.511 单/s、下单 P95 1599 ms；测试进程和专用容器已停止。
