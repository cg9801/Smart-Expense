package com.occ.smartexpense.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ApprovalActionDTO {
    @Size(max = 512,message = "审批意见不能超过512字")
    private String comment;

}
