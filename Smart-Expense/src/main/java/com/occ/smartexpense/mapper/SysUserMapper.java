package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户数据访问：审批链解析需要"按角色取第一个在职用户"。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按角色 code 取第一个在职用户（id 最小），审批链第 2/3 级来源。
     * 没有匹配用户时返回 null，由调用方判定审批链不完整。
     */
    @Select("""
            SELECT u.*
              FROM sys_user u
              JOIN sys_user_role ur ON ur.user_id = u.id
              JOIN sys_role r ON r.id = ur.role_id
             WHERE r.code = #{roleCode}
               AND u.status = 1
             ORDER BY u.id
             LIMIT 1
            """)
    SysUser selectFirstActiveByRoleCode(@Param("roleCode") String roleCode);
}
