package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.dto.UpdateRolesDTO;
import com.occ.smartexpense.security.RequireRole;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.AdminService;
import com.occ.smartexpense.vo.UserRoleVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端用户与权限接口（仅 ADMIN）。
 * 契约见 docs/03 §2.6 待确认项的落地：用户列表 + 全量角色覆盖。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@RequireRole({"ADMIN"})
public class AdminUserController {

    private final AdminService adminService;

    @GetMapping
    public Result<List<UserRoleVO>> listUsers() {
        return Result.ok(adminService.listUsers());
    }

    /** 分页版用户列表（docs/03 §2.6）：量大时用，默认 10 条/页 */
    @GetMapping("/page")
    public Result<com.occ.smartexpense.common.PageResult<UserRoleVO>> listUsersPage(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") int size) {
        return Result.ok(adminService.listUsers(page, size));
    }

    /** 赋权：body 的 roles 即最终角色集合（全量覆盖） */
    @PutMapping("/{id}/roles")
    public Result<UserRoleVO> updateRoles(@PathVariable Long id, @Valid @RequestBody UpdateRolesDTO dto) {
        Long operatorId = UserContext.requireUserId();
        return Result.ok(adminService.updateRoles(id, dto.getRoles(), operatorId));
    }
}
