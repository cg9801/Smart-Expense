package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.SysRole;

/** 角色 Mapper，赋权时校验 role code 是否合法 */
public interface SysRoleMapper extends BaseMapper<SysRole> {
}
