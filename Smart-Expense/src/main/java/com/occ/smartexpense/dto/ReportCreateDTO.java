package com.occ.smartexpense.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建草稿入参，对应 docs/03-接口清单.md §2.2 POST /reports。
 * 申请人 id 与部门**不在这里**：身份只从登录上下文取（docs/12 §6），客户端传了也无效。
 */
@Data
public class ReportCreateDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题不能超过 128 字")
    private String title;

    @NotBlank(message = "费用类型不能为空")
    private String expenseType;

    @Size(max = 512, message = "备注不能超过 512 字")
    private String remark;

    @Valid
    @NotEmpty(message = "至少需要一条明细")
    private List<ReportItemDTO> items;
}
