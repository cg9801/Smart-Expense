package com.occ.smartexpense.service;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.vo.UserRoleVO;

import java.util.List;

/**
 * 管理端用户与权限：只做"看用户 + 赋权"，不涉及认证逻辑（docs/12）。
 * 赋权采用全量覆盖：传入的 roles 即最终角色集合，未传的角色会被移除。
 */
public interface AdminService {

    List<UserRoleVO> listUsers();

    /** 分页版用户列表（GET /admin/users/page）：演示 6 人用不到，量大时用 */
    PageResult<UserRoleVO> listUsers(int page, int size);

    /**
     * 全量覆盖用户角色。
     * 守卫：不能改自己；至少保留一个角色；移除最后一个 ADMIN 会被拒绝（防自锁）。
     *
     * @param operatorId 当前登录管理员 id
     */
    UserRoleVO updateRoles(Long userId, List<String> roles, Long operatorId);
}
