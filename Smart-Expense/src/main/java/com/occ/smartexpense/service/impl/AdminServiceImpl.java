package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.entity.SysDept;
import com.occ.smartexpense.entity.SysRole;
import com.occ.smartexpense.entity.SysUser;
import com.occ.smartexpense.entity.SysUserRole;
import com.occ.smartexpense.mapper.SysDeptMapper;
import com.occ.smartexpense.mapper.SysRoleMapper;
import com.occ.smartexpense.mapper.SysUserMapper;
import com.occ.smartexpense.mapper.SysUserRoleMapper;
import com.occ.smartexpense.service.AdminService;
import com.occ.smartexpense.vo.UserRoleVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端用户/权限实现。
 * 赋权用"先删后插"全量覆盖，在一个事务里完成，避免半套角色。
 */
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private static final String ROLE_ADMIN = "ADMIN";

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysDeptMapper sysDeptMapper;

    @Override
    public List<UserRoleVO> listUsers() {
        List<UserRoleVO> users = sysUserMapper.selectUserList();
        for (UserRoleVO user : users) {
            user.setRoles(sysUserMapper.selectRoleCodes(user.getId()));
        }
        return users;
    }

    @Override
    public com.occ.smartexpense.common.PageResult<UserRoleVO> listUsers(int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        long total = sysUserMapper.countUsers();
        if (total == 0) {
            return com.occ.smartexpense.common.PageResult.empty(p, s);
        }
        List<UserRoleVO> users = sysUserMapper.selectUserPage((p - 1) * s, s);
        for (UserRoleVO user : users) {
            user.setRoles(sysUserMapper.selectRoleCodes(user.getId()));
        }
        return com.occ.smartexpense.common.PageResult.of(users, total, p, s);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserRoleVO updateRoles(Long userId, List<String> roles, Long operatorId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        // 不能改自己：否则误操作把自己降级后再也进不了管理端
        if (userId.equals(operatorId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不能修改自己的角色");
        }
        if (roles == null || roles.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "至少保留一个角色");
        }

        Map<String, Long> roleIdByCode = sysRoleMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysRole::getCode, SysRole::getId));
        List<Long> roleIds = new ArrayList<>();
        for (String code : roles) {
            Long roleId = roleIdByCode.get(code);
            if (roleId == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "不存在的角色：" + code);
            }
            roleIds.add(roleId);
        }

        // 防自锁：移除最后一个 ADMIN 会被拒绝
        boolean currentIsAdmin = sysUserMapper.selectRoleCodes(userId).contains(ROLE_ADMIN);
        if (currentIsAdmin && !roles.contains(ROLE_ADMIN)
                && sysUserMapper.countUsersByRoleCode(ROLE_ADMIN) <= 1) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "系统至少保留一个管理员");
        }

        // 全量覆盖：先删后插，同事务内完成
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId));
        for (Long roleId : roleIds) {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(userId);
            relation.setRoleId(roleId);
            sysUserRoleMapper.insert(relation);
        }

        UserRoleVO vo = new UserRoleVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setStatus(user.getStatus());
        vo.setRoles(sysUserMapper.selectRoleCodes(userId));
        if (user.getDeptId() != null) {
            SysDept dept = sysDeptMapper.selectById(user.getDeptId());
            vo.setDeptName(dept == null ? null : dept.getName());
        }
        return vo;
    }
}
