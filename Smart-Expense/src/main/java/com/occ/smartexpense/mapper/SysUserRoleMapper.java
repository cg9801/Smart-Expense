package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.SysUserRole;

/** 用户-角色关联 Mapper，整批覆盖策略（先删后插） */
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
