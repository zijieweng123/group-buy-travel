# 文旅票务拼团营销系统

基于 Spring Boot 2.7、MyBatis、MySQL、Redis 和 Kafka 的轻量 DDD 多模块项目。代码由原始拼团营销项目迁移，并补齐文旅票务场景的多日历库存、实名票务校验、请求防重与可靠异步通知。

## 模块

- `cultural-tourism-group-buy-api`：DTO 与服务契约
- `cultural-tourism-group-buy-domain`：活动、交易、标签领域模型，以及规则树/责任链
- `cultural-tourism-group-buy-infrastructure`：MyBatis 仓储、Redis、Kafka 和外部通知适配
- `cultural-tourism-group-buy-trigger`：REST 接口、Kafka 消费者、定时补偿任务
- `cultural-tourism-group-buy-types`：通用异常、枚举和设计框架
- `cultural-tourism-group-buy-app`：Spring Boot 启动与配置装配

锁单责任链：活动有效性 → 实名校验 → 参与次数 → SETNX 防重 → 日历库存 → 团队库存。任一后置步骤失败时，已占用的防重键和库存都会补偿释放。

## 启动

要求 JDK 8+、Maven 3.8+、Docker Compose。

```bash
docker compose up -d
mvn -DskipTests package
java -jar cultural-tourism-group-buy-app/target/cultural-tourism-group-buy-app.jar
```

数据库首次初始化会依次执行基础表结构和 [`3-1-travel-ticket-extension.sql`](docs/dev-ops/mysql/sql/3-1-travel-ticket-extension.sql)。服务默认监听 `8091`。

## REST API

### 查询营销报价

```http
GET /api/v1/group-buy/activities/quote?userId=xfg01&source=s01&channel=c01&goodsId=9890001
```

### 创建票务拼团订单

```http
POST /api/v1/group-buy/orders
Content-Type: application/json

{
  "userId": "xfg01",
  "activityId": 100123,
  "goodsId": "9890001",
  "source": "s01",
  "channel": "c01",
  "outTradeNo": "202609160001",
  "travelDate": "2026-09-17",
  "visitorName": "张三",
  "visitorIdType": "ID_CARD",
  "visitorIdNo": "110101199001011234",
  "notifyConfigVO": { "notifyType": "MQ" }
}
```

`visitorIdType` 支持 `ID_CARD` 和 `PASSPORT`。证件号不写业务日志、不进入 Kafka 消息。

### 支付结算

```http
PUT /api/v1/group-buy/orders/settlement
Content-Type: application/json

{
  "userId": "xfg01",
  "source": "s01",
  "channel": "c01",
  "outTradeNo": "202609160001",
  "outTradeTime": "2026-09-16T12:00:00.000+08:00"
}
```

成团后事务内写入 `notify_task`，定时任务投递 Kafka；生产端等待 broker ACK 并重试，消费端以 `teamId` 做业务幂等。

## 构建验证

本项目已使用 JDK 17 以 Java 8 目标级别执行 `mvn -DskipTests package`，七个 Maven 模块均构建成功。原项目中的测试多为依赖 MySQL/Redis 的集成测试，需先启动上述基础设施后再按需执行。
