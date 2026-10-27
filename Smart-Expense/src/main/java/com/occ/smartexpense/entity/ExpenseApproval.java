package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批链快照 + 审批任务 expense_approval，字段定义见 sql/schema.sql §3。
 * 审批人姓名/部门名是提交时的快照，组织变更不影响在途单（docs/02-状态机.md §4）。
 */
@Data
@TableName("expense_approval")
public class ExpenseApproval {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    /** 第几级，1..total_steps */
    private Integer stepNo;

    /** 直属主管 / 财务 / 分管 */
    private String stepName;

    /** SINGLE 单签 / AND 会签 / OR 或签，当前 MVP 固定 SINGLE */
    private String approvalMode;

    private Long approverId;

    /** 提交时的审批人姓名快照 */
    private String approverName;

    /** 提交时的审批人部门名快照 */
    private String approverDeptName;

    /** 审批人来源角色：SUPERVISOR / FINANCE / DIRECTOR */
    private String sourceRole;

    /** PENDING / APPROVED / REJECTED / TRANSFERRED / SKIPPED / CANCELLED */
    private String status;

    /** 超时提醒/升级基准时间 */
    private LocalDateTime deadlineAt;

    private LocalDateTime actedAt;

    private String comment;

    /** 转交来源节点 id */
    private Long transferFromId;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
