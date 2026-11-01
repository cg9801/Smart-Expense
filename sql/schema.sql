-- ============================================================
-- 智能报销审批系统 Smart Expense · V1 建表脚本
-- MySQL 8.0+ / InnoDB / utf8mb4_0900_ai_ci
-- 约定：
--   1) 金额统一 DECIMAL(14,2)，币种默认 CNY；
--   2) 时间统一 DATETIME(3)（毫秒），created_at/updated_at 由库维护；
--   3) 业务状态用 VARCHAR 存枚举码，转换在应用层状态机完成（见 docs/02）；
--   4) 所有"只追加"表（status_log / budget_txn / outbox）不提供 update 语义；
--   5) 索引命名 idx_*，唯一约束命名 uk_*。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < sql/schema.sql
-- ============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS smart_expense
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE smart_expense;

-- ------------------------------------------------------------
-- 1. 组织与权限（MVP：员工 / 主管 / 财务 / 分管 / 管理员）
-- ------------------------------------------------------------
CREATE TABLE sys_dept (
  id             BIGINT UNSIGNED NOT NULL,
  parent_id      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  name           VARCHAR(64)     NOT NULL COMMENT '部门名',
  leader_user_id BIGINT UNSIGNED DEFAULT NULL COMMENT '部门负责人，审批链解析用',
  ancestors      VARCHAR(255)    NOT NULL DEFAULT '0' COMMENT '祖级路径，如 0,1,3',
  sort_no        INT             NOT NULL DEFAULT 0,
  status         TINYINT         NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_parent (parent_id)
) ENGINE=InnoDB COMMENT='部门';

CREATE TABLE sys_user (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_no     VARCHAR(32)     NOT NULL COMMENT '工号',
  username        VARCHAR(64)     NOT NULL COMMENT '登录名',
  real_name       VARCHAR(64)     NOT NULL,
  password_hash   VARCHAR(100)    NOT NULL COMMENT 'BCrypt',
  email           VARCHAR(128)    DEFAULT NULL,
  dept_id         BIGINT UNSIGNED NOT NULL,
  manager_user_id BIGINT UNSIGNED DEFAULT NULL COMMENT '直属主管，审批链第 1 级来源',
  status          TINYINT         NOT NULL DEFAULT 1 COMMENT '1 在职 / 0 离职',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_employee_no (employee_no),
  UNIQUE KEY uk_username (username),
  KEY idx_dept (dept_id),
  KEY idx_manager (manager_user_id)
) ENGINE=InnoDB COMMENT='用户';

CREATE TABLE sys_role (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code       VARCHAR(32)     NOT NULL COMMENT 'EMPLOYEE/SUPERVISOR/FINANCE/DIRECTOR/ADMIN',
  name       VARCHAR(64)     NOT NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB COMMENT='角色';

CREATE TABLE sys_user_role (
  user_id BIGINT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (user_id, role_id),
  KEY idx_role (role_id)
) ENGINE=InnoDB COMMENT='用户-角色';

-- ------------------------------------------------------------
-- 2. 预算（部门 × 月份，冻结 + 实扣 两条水位）
-- ------------------------------------------------------------
CREATE TABLE dept_budget (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dept_id       BIGINT UNSIGNED NOT NULL,
  fiscal_year   SMALLINT        NOT NULL COMMENT '2026',
  period        CHAR(7)         NOT NULL COMMENT 'YYYY-MM',
  total_amount  DECIMAL(14,2)   NOT NULL DEFAULT 0.00,
  used_amount   DECIMAL(14,2)   NOT NULL DEFAULT 0.00 COMMENT '已实扣',
  frozen_amount DECIMAL(14,2)   NOT NULL DEFAULT 0.00 COMMENT '在途冻结',
  version       INT             NOT NULL DEFAULT 0 COMMENT '乐观锁（与条件更新二选一，见 04）',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_dept_period (dept_id, fiscal_year, period),
  CONSTRAINT ck_budget_no_overdraft CHECK (used_amount + frozen_amount <= total_amount)
) ENGINE=InnoDB COMMENT='部门预算';

CREATE TABLE budget_pool (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  period       CHAR(7)         NOT NULL COMMENT 'YYYY-MM，公司级月度总预算',
  total_amount DECIMAL(14,2)   NOT NULL DEFAULT 0.00 COMMENT '总预算池，部门分配之和不得超过它',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_period (period)
) ENGINE=InnoDB COMMENT='公司总预算池（按月），docs/06 §2.1';

CREATE TABLE budget_txn (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  budget_id  BIGINT UNSIGNED NOT NULL,
  report_id  BIGINT UNSIGNED DEFAULT NULL COMMENT '预算调整流水没有单据，允许 NULL；冻结/扣减/释放必有',
  biz_no     VARCHAR(64)     NOT NULL COMMENT '幂等键：FREEZE:{reportId}:{submitToken} / RELEASE:{reportId}:{submitToken} / DEDUCT:{reportId} / ADJUST:{budgetId}:{uuid}',
  txn_type   VARCHAR(16)     NOT NULL COMMENT 'FREEZE/DEDUCT/RELEASE/ADJUST/ADJUST_ADD',
  amount     DECIMAL(14,2)   NOT NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_no (biz_no),
  KEY idx_report (report_id),
  KEY idx_budget_created (budget_id, created_at)
) ENGINE=InnoDB COMMENT='预算流水（只追加，对账依据）';

-- ------------------------------------------------------------
-- 3. 报销单与审批链
-- ------------------------------------------------------------
CREATE TABLE expense_report (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  report_no        VARCHAR(32)     NOT NULL COMMENT '业务单号 EXP20261019-0001',
  submit_token     VARCHAR(64)     DEFAULT NULL COMMENT '提交幂等 token，草稿为 NULL（唯一索引允许多 NULL）',
  applicant_id     BIGINT UNSIGNED NOT NULL,
  applicant_dept_id BIGINT UNSIGNED NOT NULL COMMENT '提交时部门快照',
  title            VARCHAR(128)    NOT NULL,
  expense_type     VARCHAR(24)     NOT NULL COMMENT 'TRAVEL/MEAL/OFFICE/OTHER',
  amount_total     DECIMAL(14,2)   NOT NULL DEFAULT 0.00,
  currency         CHAR(3)         NOT NULL DEFAULT 'CNY',
  status           VARCHAR(24)     NOT NULL DEFAULT 'DRAFT'
                   COMMENT 'DRAFT/PENDING/APPROVING/APPROVED/REJECTED/PAID',
  current_step     INT             NOT NULL DEFAULT 0 COMMENT '当前审批级次，从 1 开始',
  total_steps      INT             NOT NULL DEFAULT 0,
  budget_id        BIGINT UNSIGNED DEFAULT NULL,
  budget_period    CHAR(7)         DEFAULT NULL,
  version          INT             NOT NULL DEFAULT 0 COMMENT '乐观锁：状态流转',
  submitted_at     DATETIME(3)     DEFAULT NULL,
  finished_at      DATETIME(3)     DEFAULT NULL COMMENT '通过/驳回时间',
  paid_at          DATETIME(3)     DEFAULT NULL,
  remark           VARCHAR(512)    DEFAULT NULL,
  deleted          TINYINT         NOT NULL DEFAULT 0,
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_report_no (report_no),
  UNIQUE KEY uk_submit_token (submit_token),
  KEY idx_applicant_status (applicant_id, status, created_at),
  KEY idx_status_created (status, created_at),
  KEY idx_dept_status (applicant_dept_id, status)
) ENGINE=InnoDB COMMENT='报销单主表';

CREATE TABLE expense_item (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  report_id    BIGINT UNSIGNED NOT NULL,
  expense_date DATE            NOT NULL,
  category     VARCHAR(24)     NOT NULL COMMENT '交通/餐饮/办公/其他',
  amount       DECIMAL(14,2)   NOT NULL,
  description  VARCHAR(255)    DEFAULT NULL,
  invoice_id   BIGINT UNSIGNED DEFAULT NULL COMMENT '关联发票（v2）',
  sort_no      INT             NOT NULL DEFAULT 0,
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_report (report_id)
) ENGINE=InnoDB COMMENT='报销明细';

CREATE TABLE expense_approval (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  report_id         BIGINT UNSIGNED NOT NULL,
  step_no           INT             NOT NULL COMMENT '第几级，1..total_steps',
  step_name         VARCHAR(32)     NOT NULL COMMENT '直属主管/财务/分管',
  approval_mode     VARCHAR(8)      NOT NULL DEFAULT 'SINGLE' COMMENT 'SINGLE 单签 / AND 会签 / OR 或签',
  approver_id       BIGINT UNSIGNED NOT NULL COMMENT '快照审批人',
  approver_name     VARCHAR(64)     NOT NULL COMMENT '快照姓名（组织变更不影响在途单）',
  approver_dept_name VARCHAR(64)    NOT NULL COMMENT '快照部门名',
  source_role       VARCHAR(24)     NOT NULL COMMENT 'SUPERVISOR/FINANCE/DIRECTOR',
  status            VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
                    COMMENT 'PENDING/APPROVED/REJECTED/TRANSFERRED/SKIPPED/CANCELLED',
  deadline_at       DATETIME(3)     DEFAULT NULL COMMENT '超时提醒/升级基准',
  acted_at          DATETIME(3)     DEFAULT NULL,
  comment           VARCHAR(512)    DEFAULT NULL,
  transfer_from_id  BIGINT UNSIGNED DEFAULT NULL,
  version           INT             NOT NULL DEFAULT 0,
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_report_step_approver (report_id, step_no, approver_id),
  KEY idx_report_step (report_id, step_no, status),
  KEY idx_approver_todo (approver_id, status, deadline_at)
) ENGINE=InnoDB COMMENT='审批链快照 + 审批任务';

CREATE TABLE expense_status_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trace_id      VARCHAR(64)     NOT NULL COMMENT '全链路 traceId',
  report_id     BIGINT UNSIGNED NOT NULL,
  action        VARCHAR(32)     NOT NULL COMMENT 'SUBMIT/WITHDRAW/APPROVE/REJECT/TRANSFER/PAY/RECONCILE',
  from_status   VARCHAR(24)     DEFAULT NULL,
  to_status     VARCHAR(24)     DEFAULT NULL,
  operator_id   BIGINT UNSIGNED DEFAULT NULL COMMENT 'NULL 表示系统动作',
  operator_name VARCHAR(64)     DEFAULT NULL,
  detail        JSON            DEFAULT NULL COMMENT '动作上下文（节点、意见、金额等）',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_report_created (report_id, created_at),
  KEY idx_trace (trace_id)
) ENGINE=InnoDB COMMENT='状态流转审计日志（只追加）';

-- ------------------------------------------------------------
-- 4. 付款单
-- ------------------------------------------------------------
CREATE TABLE payment_order (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  payment_no   VARCHAR(32)     NOT NULL COMMENT 'PAY20261019-0001',
  report_id    BIGINT UNSIGNED NOT NULL COMMENT '一单一付：唯一约束保证不重复生成',
  payee_user_id BIGINT UNSIGNED NOT NULL,
  payee_name   VARCHAR(64)     NOT NULL,
  amount       DECIMAL(14,2)   NOT NULL,
  status       VARCHAR(16)     NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PAID/FAILED',
  paid_at      DATETIME(3)     DEFAULT NULL,
  remark       VARCHAR(255)    DEFAULT NULL,
  version      INT             NOT NULL DEFAULT 0,
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_no (payment_no),
  UNIQUE KEY uk_report (report_id),
  KEY idx_status_created (status, created_at)
) ENGINE=InnoDB COMMENT='付款单';

-- ------------------------------------------------------------
-- 5. 可靠消息（Outbox）与消费幂等
-- ------------------------------------------------------------
CREATE TABLE outbox_message (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  msg_id         CHAR(36)        NOT NULL COMMENT 'UUID，投递后作为 MQ messageId',
  aggregate_type VARCHAR(32)     NOT NULL COMMENT 'REPORT',
  aggregate_id   BIGINT UNSIGNED NOT NULL,
  event_type     VARCHAR(48)     NOT NULL COMMENT 'REPORT_APPROVED/REPORT_REJECTED',
  payload        JSON            NOT NULL,
  status         VARCHAR(12)     NOT NULL DEFAULT 'NEW' COMMENT 'NEW/SENT/FAILED/DEAD',
  retry_count    INT             NOT NULL DEFAULT 0,
  next_retry_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_error     VARCHAR(512)    DEFAULT NULL,
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at        DATETIME(3)     DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_msg_id (msg_id),
  KEY idx_status_next (status, next_retry_at),
  KEY idx_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB COMMENT='Outbox 本地消息表';

CREATE TABLE mq_consume_log (
  msg_id      CHAR(36)    NOT NULL COMMENT 'MQ messageId，消费前先落库去重',
  consumer    VARCHAR(64) NOT NULL COMMENT '消费者标识，如 budget-deduct',
  consumed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (msg_id, consumer)
) ENGINE=InnoDB COMMENT='消费幂等记录';

-- ------------------------------------------------------------
-- 6. 发票识别（v2）
-- ------------------------------------------------------------
CREATE TABLE invoice (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  report_id     BIGINT UNSIGNED DEFAULT NULL,
  file_url      VARCHAR(512)    NOT NULL,
  file_hash     CHAR(64)        NOT NULL COMMENT 'SHA-256，重复上传即查重',
  original_name VARCHAR(255)    DEFAULT NULL,
  invoice_code  VARCHAR(32)     DEFAULT NULL COMMENT '发票代码',
  invoice_no    VARCHAR(32)     DEFAULT NULL COMMENT '发票号码',
  invoice_date  DATE            DEFAULT NULL,
  amount        DECIMAL(14,2)   DEFAULT NULL,
  tax_amount    DECIMAL(14,2)   DEFAULT NULL,
  ocr_status    VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
                COMMENT 'PENDING/PROCESSING/SUCCESS/FAILED/MANUAL',
  confidence    DECIMAL(4,3)    DEFAULT NULL COMMENT '0.000-1.000，低于阈值转人工',
  model_result  JSON            DEFAULT NULL COMMENT '识别结果：fields[] + needsReview + raw',
  error_msg     VARCHAR(512)    DEFAULT NULL,
  retry_count   INT             NOT NULL DEFAULT 0,
  recognizer    VARCHAR(32)     DEFAULT NULL COMMENT 'mock / qwen-vl / cloud-ocr，实际产出实现',
  degraded      TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否走过降级链（主实现失败）',
  latency_ms    INT             DEFAULT NULL COMMENT '单次识别耗时（毫秒），P95 指标用；不含 MQ 排队',
  uploader_id   BIGINT UNSIGNED NOT NULL,
  reviewed_by   BIGINT UNSIGNED DEFAULT NULL,
  reviewed_at   DATETIME(3)     DEFAULT NULL,
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 同人同图才复用；跨人同图允许落库并标记疑似重复报销（docs/11 §3.4 情形 2）
  UNIQUE KEY uk_uploader_file_hash (uploader_id, file_hash),
  -- 票号重复不拒绝落库，交给硬规则 DUPLICATE_INVOICE_SUSPECT 提示（docs/19 §4）
  KEY idx_invoice_no (invoice_code, invoice_no),
  KEY idx_file_hash (file_hash),
  KEY idx_report (report_id),
  KEY idx_ocr_status (ocr_status, created_at)
) ENGINE=InnoDB COMMENT='发票';

-- ------------------------------------------------------------
-- 7. 对账
-- ------------------------------------------------------------
CREATE TABLE reconcile_record (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  biz_date      DATE            NOT NULL COMMENT '对账业务日（T-1）',
  scanned_count INT             NOT NULL DEFAULT 0,
  diff_count    INT             NOT NULL DEFAULT 0,
  status        VARCHAR(16)     NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/DONE/FAILED',
  detail        JSON            DEFAULT NULL,
  started_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at   DATETIME(3)     DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_date (biz_date)
) ENGINE=InnoDB COMMENT='对账批次';

CREATE TABLE reconcile_diff (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  biz_date    DATE            NOT NULL,
  diff_type   VARCHAR(32)     NOT NULL
              COMMENT 'BUDGET_NOT_DEDUCTED/PAYMENT_MISSING/AMOUNT_MISMATCH/STATUS_STUCK',
  report_id   BIGINT UNSIGNED NOT NULL,
  detail      JSON            DEFAULT NULL,
  status      VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/REPAIRED/IGNORED',
  repaired_at DATETIME(3)     DEFAULT NULL,
  handler_id  BIGINT UNSIGNED DEFAULT NULL COMMENT 'NULL 表示自动补偿',
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_date_type_report (biz_date, diff_type, report_id),
  KEY idx_status (status, biz_date)
) ENGINE=InnoDB COMMENT='对账差异';

-- ------------------------------------------------------------
-- 8. AI 审批 Copilot（见 docs/11 §6.1）
-- ------------------------------------------------------------
CREATE TABLE ai_review (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  report_id      BIGINT UNSIGNED NOT NULL,
  report_version BIGINT UNSIGNED NOT NULL COMMENT '计算时的 expense_report.version，用于过期判定',
  risk_level     VARCHAR(8)      NOT NULL DEFAULT 'LOW' COMMENT 'LOW/MEDIUM/HIGH（= max(硬规则, AI)）',
  confidence     DECIMAL(4,3)    DEFAULT NULL COMMENT 'AI 自评置信度，<0.6 不建议展示',
  ai_reasons     JSON            DEFAULT NULL COMMENT '模型 reasons（已过 evidence 白名单）',
  hard_findings  JSON            DEFAULT NULL COMMENT '硬规则命中，AI 只能升不能降',
  model          VARCHAR(64)     DEFAULT NULL,
  prompt_version VARCHAR(16)     DEFAULT NULL COMMENT 'prompt 迭代对比用',
  latency_ms     INT             DEFAULT NULL,
  agent_steps    INT             DEFAULT NULL COMMENT 'Agent 循环的模型请求次数（<=5）',
  tool_calls     INT             DEFAULT NULL COMMENT '工具调用总次数',
  tool_errors    INT             DEFAULT NULL COMMENT '工具失败/拒绝次数',
  status         VARCHAR(16)     NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED/SKIPPED/STALE',
  adopted        TINYINT(1)      DEFAULT NULL COMMENT '1 采纳 / 0 忽略 / NULL 未表态',
  error_msg      VARCHAR(512)    DEFAULT NULL,
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_report_version (report_id, report_version),
  KEY idx_report_created (report_id, created_at)
) ENGINE=InnoDB COMMENT='AI 审批建议';
