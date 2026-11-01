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

    /** 同一角色的其他在职用户（超时升级用，排除当前审批人） */
    @Select("""
            SELECT u.*
              FROM sys_user u
              JOIN sys_user_role ur ON ur.user_id = u.id
              JOIN sys_role r ON r.id = ur.role_id
             WHERE r.code = #{roleCode}
               AND u.status = 1
               AND u.id != #{excludeUserId}
             ORDER BY u.id
            """)
    java.util.List<SysUser> selectActiveByRoleCodeExclude(@Param("roleCode") String roleCode,
                                                          @Param("excludeUserId") Long excludeUserId);

    /** 查用户角色 code 列表（docs/12 登录与权限校验用） */
    @Select("""
            SELECT r.code
              FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id
             WHERE ur.user_id = #{userId}
             ORDER BY r.id
            """)
    java.util.List<String> selectRoleCodes(@Param("userId") Long userId);

    /** 管理端用户列表：联表带部门名，角色由调用方按人补齐（演示规模 < 100 人） */
    @Select("""
            SELECT u.id, u.username, u.real_name, d.name AS dept_name, u.status
              FROM sys_user u
              LEFT JOIN sys_dept d ON d.id = u.dept_id
             ORDER BY u.id
            """)
    java.util.List<com.occ.smartexpense.vo.UserRoleVO> selectUserList();

    /** 管理端用户分页（GET /admin/users/page，docs/03 §2.6）：大表时避免全量返回 */
    @Select("""
            SELECT u.id, u.username, u.real_name, d.name AS dept_name, u.status
              FROM sys_user u
              LEFT JOIN sys_dept d ON d.id = u.dept_id
             ORDER BY u.id
             LIMIT #{size} OFFSET #{offset}
            """)
    java.util.List<com.occ.smartexpense.vo.UserRoleVO> selectUserPage(@Param("offset") int offset,
                                                                      @Param("size") int size);

    @Select("SELECT COUNT(*) FROM sys_user")
    long countUsers();

    /** 持有某个角色的用户数（防"最后一个管理员被降级"自锁） */
    @Select("""
            SELECT COUNT(DISTINCT ur.user_id)
              FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id
             WHERE r.code = #{roleCode}
            """)
    long countUsersByRoleCode(@Param("roleCode") String roleCode);

    /**
     * 转交候选人（docs/02 §5）：在职、非本人；给了 reportId 时排除该单历史审批人（防环）。
     * 前端下拉用它，服务端 transfer 还会再校验一次，双保险。
     */
    @Select("""
            <script>
            SELECT u.id AS user_id, u.real_name, d.name AS dept_name
              FROM sys_user u
              LEFT JOIN sys_dept d ON d.id = u.dept_id
             WHERE u.status = 1
               AND u.id != #{selfId}
            <if test="reportId != null">
               AND u.id NOT IN (SELECT approver_id FROM expense_approval WHERE report_id = #{reportId})
            </if>
             ORDER BY u.id
            </script>
            """)
    java.util.List<com.occ.smartexpense.vo.TransferCandidateVO> selectTransferCandidates(
            @Param("selfId") Long selfId, @Param("reportId") Long reportId);
}
