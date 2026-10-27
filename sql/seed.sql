-- ============================================================
-- 智能报销审批系统 · 演示数据（V1）
-- 依赖：sql/schema.sql 先执行
-- 说明：所有用户初始密码统一为 123456，password_hash 为占位值，
--       首次启动时由 DataInitializer 用 BCryptPasswordEncoder 重新生成并覆盖。
-- ============================================================
SET NAMES utf8mb4;
USE smart_expense;

-- 部门：总公司(1) ├─ 技术部(2) └─ 市场部(3)
INSERT INTO sys_dept (id, parent_id, name, leader_user_id, ancestors, sort_no) VALUES
  (1, 0, '总公司', 4, '0',   0),
  (2, 1, '技术部', 2, '0,1', 1),
  (3, 1, '市场部', 5, '0,1', 2);

INSERT INTO sys_role (id, code, name) VALUES
  (1, 'EMPLOYEE',   '员工'),
  (2, 'SUPERVISOR', '直属主管'),
  (3, 'FINANCE',    '财务'),
  (4, 'DIRECTOR',   '分管'),
  (5, 'ADMIN',      '管理员');

-- 林一(1) 是演示主账号；张三(2) 是其直属主管；王五(4) 分管技术部
INSERT INTO sys_user (id, employee_no, username, real_name, password_hash, email, dept_id, manager_user_id) VALUES
  (1, 'E1001', 'linyi',    '林一',   'PLACEHOLDER_BCRYPT', 'linyi@example.com',    2, 2),
  (2, 'E1002', 'zhangsan', '张三',     'PLACEHOLDER_BCRYPT', 'zhangsan@example.com', 2, 4),
  (3, 'E2001', 'lisi',     '李四',     'PLACEHOLDER_BCRYPT', 'lisi@example.com',     3, 5),
  (4, 'E3001', 'wangwu',   '王五',     'PLACEHOLDER_BCRYPT', 'wangwu@example.com',   1, NULL),
  (5, 'E3002', 'zhaoliu',  '赵六',     'PLACEHOLDER_BCRYPT', 'zhaoliu@example.com',  1, NULL),
  (6, 'E9001', 'admin',    '系统管理员', 'PLACEHOLDER_BCRYPT', 'admin@example.com',      1, NULL);

INSERT INTO sys_user_role (user_id, role_id) VALUES
  (1, 1),           -- 林一：员工
  (2, 1), (2, 2),   -- 张三：员工 + 主管
  (3, 1),           -- 李四：员工
  (4, 3), (4, 1),   -- 王五：财务 + 员工
  (5, 4), (5, 1),   -- 赵六：分管 + 员工
  (6, 5);           -- admin

-- 2026-10 部门预算
INSERT INTO dept_budget (dept_id, fiscal_year, period, total_amount, used_amount, frozen_amount) VALUES
  (2, 2026, '2026-10', 50000.00, 0.00, 0.00),
  (3, 2026, '2026-10', 20000.00, 0.00, 0.00);
