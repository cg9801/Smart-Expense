package com.occ.smartexpense.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 转交入参，对应 docs/03-接口清单.md §2.3 POST /approvals/{approvalId}/transfer。
 * 目标不能是本单历史审批人（防环，docs/02 §5），评论选填。
 */
@Data
public class TransferDTO {

    @NotNull(message = "转交目标用户不能为空")
    private Long targetUserId;

    private String comment;
}
