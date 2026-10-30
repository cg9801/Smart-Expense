package com.occ.smartexpense.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 审批动作入参，见 docs/03 §2.3 与 docs/11 §5.7。
 */
@Data
public class ApprovalActionDTO {

    @Size(max = 512, message = "审批意见不能超过512字")
    private String comment;

    /** 采纳的 AI 建议 id；不传表示不表态（docs/11 §5.7） */
    private Long aiReviewId;

    /** true 采纳 / false 忽略 / null 未表态 */
    private Boolean aiAdopted;
}
