package com.occ.smartexpense.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建草稿入参，对应 docs/03-接口清单.md §2.2 POST /reports。
 * 明细合计由服务端校验，不信任前端传入的总额。
 */
@Data
public class ReportCreateDTO {

    @NotNull(message = "申请人不能为空")
    private Long applicantId;

    @NotNull(message = "申请人部门不能为空")
    private Long applicantDeptId;

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
