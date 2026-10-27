# 07 Outbox 与消息一致性（完整流程）

> 本文是「异步消息」这条业务线的唯一设计依据：先读完，再写代码。
> 关联：`01-表结构.md` §4.4（outbox）、`02-状态机.md` T4/T6（写 Outbox、付款）、`04-任务拆解.md` 第 3 周。
> 定稿日期 2026-10-25。

---

## 1. 要解决什么问题

审批通过后，系统还要做两件「不只属于审批」的事：

1. 生成**付款单**（下游财务动作）；
2. 将来还会挂新的下游（通知、发票归档、报表）。

如果直接在审批事务里调下游，会出现两个问题：

- **下游挂了，审批回滚**——财务系统抖动不该阻塞审批；
- **先提交事务再发消息**——事务提交成功、发消息前进程挂了，消息永久丢失。

Outbox 解决的是「**数据库提交了，消息一定要发出去**」：

> 把“要发的消息”和“业务数据”写进**同一个本地事务**。事务成功 = 消息一定躺在表里；再由独立调度器把表里的消息搬到 MQ。发送失败就重试，重试到顶就进死信，最后由对账兜底。

一句话：**业务表与消息表同生共死，MQ 只负责传递，不负责可靠。**

---

## 2. 一个先要澄清的变更：预算实扣**不**走消息

`04-任务拆解.md` 第 3 周 D2 原本写的是“预算实扣消费者（冻结转实扣）”。实现时已经改成：

> **实扣仍在审批事务内同步完成**（`docs/06` §4.3，已并发验证），MQ 只负责**付款单生成**。

理由：

| 方案 | 问题 |
|---|---|
| 实扣放消费者 | 审批已 `APPROVED` 但钱还没扣，存在“已通过未扣预算”的窗口；要靠对账/重放补偿，复杂度陡增 |
| 实扣同步（现方案） | 与审批同事务，强一致；MQ 挂了也不影响预算正确性；消费者只做可重试的建单动作 |

所以本项目的消息链路是：**T4 实扣（同步）+ 写 Outbox → 调度器投递 → 付款单消费者（异步）**。`docs/04` D2 的描述已同步改写。

---

## 3. 数据模型（已有表）

### 3.1 `outbox_message` 本地消息表

| 字段 | 说明 |
|---|---|
| `msg_id` | UUID，投递时作为 MQ `messageId`；`uk_msg_id` 防止同一消息被写两次 |
| `aggregate_type` / `aggregate_id` | 聚合根，如 `REPORT` / 单据 id，便于按单排查 |
| `event_type` | `REPORT_APPROVED`（本期唯一）；将来 `REPORT_REJECTED` 等 |
| `payload` | JSON 事件体，消费者只依赖它，不再回查业务表 |
| `status` | `NEW` 待投递 / `SENT` 已投递 / `FAILED` 投递失败待重试 / `DEAD` 超过重试上限 |
| `retry_count` / `next_retry_at` | 指数退避；`idx_status_next` 支撑按到期时间扫描 |
| `last_error` | 最近一次失败的异常摘要（截断 512 字） |

> 注意：表里没有 `SENDING` 状态。多实例抢任务用「**条件更新把 `next_retry_at` 推后**」实现租约，不新增状态，避免状态机变复杂。

### 3.2 `mq_consume_log` 消费幂等表

主键 `(msg_id, consumer)`：同一个消费者处理同一条消息只允许成功一次。**插入成功 = 第一次处理**，主键冲突 = 重复投递，直接丢弃。

`consumer` 用固定字符串（如 `payment-create`），同一套代码将来监听多个队列时能区分。

### 3.3 `payment_order` 付款单

| 字段 | 说明 |
|---|---|
| `payment_no` | 付款单号 `PAY{yyyyMMdd}{6位随机}`，`uk_payment_no` |
| `report_id` | **`uk_report` 一单一付**：重复消费不可能生成第二张单 |
| `payee_user_id` / `payee_name` | 收款人（= 报销单申请人，取自 payload 快照） |
| `amount` | 付款金额，必须等于单据 `amount_total` |
| `status` | `CREATED` → `PAID`（T6）/ `FAILED` |

**三层唯一键 = 三层幂等**：`outbox.uk_msg_id`、`mq_consume_log` 联合主键、`payment_order.uk_report`。

---

## 4. 完整链路（时序）

```
审批末级通过（同一事务）
  ├─ 节点 APPROVED
  ├─ 单据 APPROVED + finished_at
  ├─ 预算 DEDUCT（docs/06 §4.3，同步）
  ├─ INSERT outbox_message (NEW, msg_id=UUID, event=REPORT_APPROVED)
  └─ 审计 APPROVE
        │ 事务提交
        ▼
OutboxRelay（@Scheduled 每 2s）
  ├─ SELECT ... WHERE status='NEW' AND next_retry_at <= NOW() LIMIT 50
  ├─ 租约：UPDATE ... SET next_retry_at = NOW()+30s WHERE id=? AND status='NEW'（抢到才发）
  ├─ rabbitTemplate.convertAndSend(exchange, routingKey, payload, messageId=msg_id)
  │    成功 → UPDATE status='SENT', sent_at=NOW()
  │    失败 → retry_count+1, last_error=…, next_retry_at=指数退避；>=5 次 → DEAD
        ▼
RabbitMQ  exchange se.report.exchange ──routingKey report.approved──► queue se.payment.queue
                                                                           │
                                                          消费失败且不可重试 → se.dlx.exchange → se.payment.dlq
        ▼
PaymentConsumer（手动 ack）
  1. INSERT mq_consume_log(msg_id,'payment-create')  ← 冲突 = 已消费 → ack 丢弃
  2. INSERT payment_order(report_id, amount, payee…)  ← uk_report 冲突 = 已建单 → 视为成功
  3. 事务提交后 basicAck
```

---

## 5. 生产者：写 Outbox 的精确步骤

调用点：`ApprovalServiceImpl.approve` 的末级分支，**单据 UPDATE 成功之后、审计之前**。

```
OutboxService.append(eventType, aggregateType, aggregateId, payloadJson)：
  1. msgId = UUID.randomUUID()
  2. INSERT outbox_message(msg_id=msgId, aggregate_type, aggregate_id,
                           event_type, payload, status='NEW', next_retry_at=NOW())
  3. return msgId
```

要点：

- **必须调用方自带 `@Transactional`**（`append` 用 `REQUIRED` 加入同一个事务），不允许 `REQUIRES_NEW`；
- 事件体是**快照**，不要只放 id 让消费者回查——回查会引入时序依赖（消费者比提交更早看到数据）；
- 审计仍然最后写。

`REPORT_APPROVED` 的 payload 约定（字段名固定，消费者按此解析）：

```json
{
  "reportId": 1,
  "reportNo": "EXP20261020112615249",
  "applicantId": 1,
  "applicantName": "林一",
  "amountTotal": "1500.50",
  "currency": "CNY",
  "approvedAt": "2026-10-25 12:00:00"
}
```

金额用**字符串**，避免浮点误差；时间用 `yyyy-MM-dd HH:mm:ss`。

---

## 6. 调度器：把表里的消息搬出去

```sql
SELECT * FROM outbox_message
WHERE status IN ('NEW', 'FAILED') AND next_retry_at <= NOW()
ORDER BY id
LIMIT 50;
```

> `FAILED` 也必须参与扫描：第一次投递失败后 `status` 变成 `FAILED`，若只扫 `NEW`，这条消息永远不会再被重试——实现时实测踩到过。

单条处理：

1. **租约抢占**（多实例防重复投递）：
   ```sql
   UPDATE outbox_message
   SET next_retry_at = DATE_ADD(NOW(), INTERVAL 30 SECOND)
   WHERE id = ? AND status IN ('NEW','FAILED') AND next_retry_at <= NOW();
   ```
   影响 0 行说明别的实例刚抢走，跳过。租约只是“降低重复概率”，**不承诺只投一次**——真正的防重在下游幂等。
2. **投递**：`convertAndSend(exchange, routingKey, payload)`，`messageId = msg_id`，`deliveryMode = PERSISTENT`。
3. **成功**：`UPDATE status='SENT', sent_at=NOW()`。
4. **失败**：`retry_count = retry_count + 1`，`last_error = 异常摘要`，`next_retry_at = NOW() + 退避`；`retry_count >= 5` 置 `DEAD`。

退避公式（秒）：`min(2^retryCount * 5, 300)`，即 5s、10s、20s、40s、80s，封顶 5 分钟。

**为什么不用 `@RabbitListener` 直发**：调度器把“发送”变成一次普通的数据库轮询，宕机后重启自动接着发，不需要任何内存状态。

---

## 7. MQ 拓扑（RabbitMQ）

| 组件 | 名字 | 说明 |
|---|---|---|
| Exchange | `se.report.exchange` | direct，durable |
| Queue | `se.payment.queue` | durable，绑定 routingKey `report.approved` |
| DLX | `se.dlx.exchange` | direct，durable |
| DLQ | `se.payment.dlq` | 消费失败的消息进这里，人工/对账处理 |

`application.yaml` 关键项：

```yaml
spring:
  rabbitmq:
    host: 127.0.0.1
    port: 5672
    username: guest
    password: guest
    publisher-confirm-type: correlated   # 发布确认
    publisher-returns: true              # 不可路由时回调
    listener:
      simple:
        acknowledge-mode: manual         # 手动 ack，事务提交后再确认
        prefetch: 10
```

声明方式：`@Configuration` 里用 `QueueBuilder.durable(...).deadLetterExchange(...)` + `DirectExchange` + `Binding`，启动即创建（幂等）。

**publisher confirm 的边界**：confirm 只证明“broker 收到了”，不证明“消费者处理了”。所以 Outbox 的 `SENT` 语义是“已交给 broker”，业务成功与否由消费者的幂等与对账保证。

---

## 8. 消费者：付款单生成

```java
@RabbitListener(queues = "se.payment.queue")
@Transactional
public void onCreatePayment(Message message) { ... }
```

处理步骤（**顺序不能反**）：

1. `INSERT mq_consume_log(msg_id, 'payment-create')`
   - 主键冲突 → 已经消费过 → 直接 `ack` 返回（不抛异常，否则会无限重投）；
2. 解析 payload，校验 `reportId` 非空、`amountTotal > 0`；
3. `INSERT payment_order(...)`，`report_id` 唯一键冲突 → 说明已建过单 → 视为成功；
4. 事务提交后 `basicAck`。

失败处理：

- **可重试**（数据库连接抖动）：不 ack，`basicNack(requeue=true)`，让 MQ 重新投递——但要有上限，靠队列 `x-delivery-limit` 或计数头；
- **不可重试**（payload 非法、金额异常）：`basicNack(requeue=false)` → 进 DLQ，写日志告警，等对账补单；
- **注意**：`@Transactional` 与手动 ack 混用时，**ack 必须放在事务提交之后**；如果在事务里 ack，随后的回滚会让消息“已确认但没处理”，直接丢单。

`mq_consume_log` 的插入与 `payment_order` 的插入必须在**同一个事务**里：否则消费日志提交了、付款单回滚了，重投时会被误判为“已消费”而丢单。

---

## 9. 付款与 T6

- 付款单生成后状态 `CREATED`；
- `POST /api/v1/payments/{id}/confirm` 模拟银行回执：
  1. 付款单必须 `CREATED`；
  2. 金额与单据一致；
  3. 付款单 `PAID` + `paid_at`，单据 `APPROVED → PAID`（T6），写审计；
  4. 条件更新 `WHERE id=? AND status='CREATED'`，0 行 → 40901。

这一步是**同步接口**，不需要消息：它是财务手动触发的动作，失败直接返回给人看。

---

## 10. 失败矩阵

| 故障 | 表现 | 兜底 |
|---|---|---|
| 审批事务回滚 | Outbox 行一起回滚 | 无消息，无脏数据 |
| 事务提交后、投递前宕机 | 消息停在 `NEW` | 重启后调度器继续发 |
| MQ 不可用 | 投递抛异常 → `FAILED` + 退避重试 | MQ 恢复后自动补发；超限 `DEAD` |
| 重复投递同一 `msg_id` | 消费者第二次插入 `mq_consume_log` 冲突 | 直接 ack 丢弃 |
| 消费后、ack 前宕机 | MQ 重投 | `mq_consume_log` 冲突 → ack；**不会重复建单** |
| 建单成功但事务回滚 | 消费日志与付款单同事务 | 一起回滚，重投可正常处理 |
| payload 非法 | 不可重试 | 进 DLQ，对账补单 |

---

## 11. 代码落点（先骨架，后实现）

```
entity/OutboxMessage.java            @TableName("outbox_message")
entity/MqConsumeLog.java             复合主键（msg_id, consumer），用 @TableId 不支持，直接用 Mapper 的 @Insert
entity/PaymentOrder.java             @TableName("payment_order")
mapper/OutboxMessageMapper.java      BaseMapper + 两条条件更新（占位、标记 SENT/FAILED）
mapper/MqConsumeLogMapper.java       @Insert（捕获 DuplicateKeyException 判重）+ selectCount
mapper/PaymentOrderMapper.java       BaseMapper + selectByReportId
service/PaymentService.java          createFromApproved(payload) / confirm(paymentId, userId)
service/impl/PaymentServiceImpl.java 建单幂等 + T6 确认
service/OutboxService.java           append(eventType, aggregateId, payloadJson)
service/impl/OutboxServiceImpl.java  写本地消息表（同事务）
job/OutboxRelay.java                 @Scheduled 扫描 + 租约 + 投递 + 退避
mq/ReportApprovedConsumer.java       @RabbitListener + 手动 ack
config/RabbitConfig.java             交换机/队列/DLX 声明
```

`MqConsumeLog` 没有自增主键，MyBatis-Plus 的 `BaseMapper` 用不了，直接手写 `@Insert`：

```java
@Insert("INSERT INTO mq_consume_log(msg_id, consumer) VALUES(#{msgId}, #{consumer})")
int insert(@Param("msgId") String msgId, @Param("consumer") String consumer);
```

---

## 12. 落地顺序（每步可验收）

| 步 | 内容 | 验收 |
|---|---|---|
| 1 | 实体 + Mapper + `RabbitConfig`，启动无报错 | RabbitMQ 管理页能看到 exchange/queue/DLQ |
| 2 | `OutboxService.append` 接到 T4 末级分支 | 审批通过后 `outbox_message` 有 1 条 `NEW` |
| 3 | `OutboxRelay` 投递 + 标记 SENT | 2 秒内 `status='SENT'`，`sent_at` 非空 |
| 4 | 消费者建付款单（含幂等） | 每单一张 `payment_order`，`amount` 与单据一致 |
| 5 | 重复投递同一条消息 | `payment_order` 仍只有 1 张，`mq_consume_log` 1 行 |
| 6 | 停 MQ 再审批 | 消息停在 `NEW/FAILED`；恢复后自动补发 |
| 7 | `POST /payments/{id}/confirm` | 单据 `PAID`，付款单 `PAID` + `paid_at` |

验证 SQL：

```sql
SELECT msg_id, event_type, status, retry_count, next_retry_at, sent_at
FROM outbox_message ORDER BY id DESC LIMIT 10;

SELECT report_id, payment_no, amount, status FROM payment_order ORDER BY id DESC;

SELECT msg_id, consumer, consumed_at FROM mq_consume_log ORDER BY consumed_at DESC LIMIT 10;
```

---

## 13. 常见坑

1. **把 `append` 写成 `REQUIRES_NEW`**：审批回滚了消息却留下了，消费端拿到一张不存在的单。
2. **payload 只放 id**：消费者回查时单据可能还没提交，读到旧状态；必须放快照。
3. **在事务里 ack**：回滚后消息已被确认，直接丢单。
4. **`mq_consume_log` 与业务写入分两个事务**：日志留下、业务回滚，重投被误判为“已消费”。
5. **把 `SENT` 当成“业务成功”**：它只代表 broker 收到了；业务成功看 `payment_order`。
6. **只重试不封顶**：一条坏消息能打满 CPU，必须 `DEAD` + 对账。
7. **消费者抛异常但 ack 了**：消息静默丢失，比报错更难查——异常路径必须走 `nack`。

---

## 14. 故障注入记录（D6，2026-10-27 实测）

单 JVM + Docker RabbitMQ，用一张 300 元的真实单据做三个实验。

| 实验 | 操作 | 观察结果 | 结论 |
|---|---|---|---|
| A. MQ 宕机 | `docker stop se-rabbitmq` → 三级审批通过 | `outbox` 为 `FAILED`，`retry_count` 1→2→3 递增，`payment_order` 0 条，预算照常实扣 | 消息失败不影响业务事务，调度器按退避重试 |
| A. MQ 恢复 | `docker start se-rabbitmq` → 等待退避到期 | `outbox` 自动变 `SENT`、`sent_at` 有值；`payment_order` 补建 1 张；队列清空 | 无需人工干预，恢复后自愈 |
| B. 重复投递 | 用管理 API 以**相同 `message_id`** 重投 | `payment_order` 仍 1 张，`mq_consume_log` 仍 1 行 | `mq_consume_log` 联合主键挡住重复消费 |
| C. 消费日志丢失 | 删掉该 `msg_id` 的 `mq_consume_log` 后重投 | 消费者重新处理，`payment_order` 仍 1 张，日志恢复 1 行 | 兜底靠 `payment_order.uk_report`，不会重复建单 |

**C 是最值得讲的一条**：它模拟“消费成功但幂等记录丢了”的极端情况，此时应用层幂等表已失效，最后由数据库唯一键兜住。三层防线各自独立失效时，仍有下一层。

复现命令（按 A→B→C 顺序）：

```powershell
docker stop se-rabbitmq
# 提交并三级审批一张单，观察 outbox FAILED
docker start se-rabbitmq
# 等待 next_retry_at 到期，观察 SENT + payment_order 补建

# B：从 outbox 取 msg_id 和 payload，用管理 API 重投
POST http://127.0.0.1:15672/api/exchanges/%2F/se.report.exchange/publish
{"properties":{"message_id":"<msg_id>"},"routing_key":"report.approved","payload":"<payload>","payload_encoding":"string"}

# C：删掉 mq_consume_log 中该 msg_id，再重投一次
```

> 实验数据测完必须清理：删单据、审批链、预算流水、outbox、consume_log、payment_order，并把 `dept_budget` 恢复原值。

---

## 15. 面试表述（做完后可用）

> 审批通过后需要生成付款单，采用 **Outbox 模式**：本地消息表与业务数据同事务提交，定时调度器按 `status + next_retry_at` 扫描投递，失败按指数退避重试、超限进死信；消费端用 `mq_consume_log` 联合主键 + 付款单 `report_id` 唯一键做双重幂等，配合手动 ack 与事务提交顺序，保证“至少一次投递、恰好一次建单”。预算实扣因是资金事实，保留在审批事务内同步完成，不引入异步窗口。
