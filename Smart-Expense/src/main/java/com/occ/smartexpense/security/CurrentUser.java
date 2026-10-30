package com.occ.smartexpense.security;

import java.util.Set;

/**
 * 当前登录用户（只读快照），由 JWT 解析或 header 回退生成，见 docs/12 §6。
 * 不直接暴露实体，避免密码哈希等字段流进业务层。
 */
public record CurrentUser(
        Long userId,
        String username,
        String realName,
        Long deptId,
        Set<String> roles) {
}
