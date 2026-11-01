package com.occ.smartexpense.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 管理端赋权入参：全量覆盖用户角色，见 docs/03 §2.6 待确认项落地 */
@Data
public class UpdateRolesDTO {

    @NotEmpty(message = "至少保留一个角色")
    private List<String> roles;
}
