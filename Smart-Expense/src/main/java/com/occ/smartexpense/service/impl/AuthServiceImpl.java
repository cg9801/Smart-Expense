package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.dto.LoginDTO;
import com.occ.smartexpense.entity.SysUser;
import com.occ.smartexpense.mapper.SysUserMapper;
import com.occ.smartexpense.security.CurrentUser;
import com.occ.smartexpense.security.JwtUtil;
import com.occ.smartexpense.security.LoginAttemptLimiter;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.AuthService;
import com.occ.smartexpense.vo.LoginVO;
import com.occ.smartexpense.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录与当前用户，见 docs/12 §4/§9。
 * 登录失败统一返回 40101，不暴露"用户是否存在"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final int ACTIVE = 1;

    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter loginAttemptLimiter;

    @Override
    public LoginVO login(LoginDTO dto, String clientIp) {
        // 先限流再查库：超限请求不消耗数据库连接，也不给撞库留时间窗口（docs/12 §9）
        loginAttemptLimiter.check(dto.getUsername(), clientIp);
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, dto.getUsername()));
        // 用户不存在与密码错误用同一句话，防止枚举用户名
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            loginAttemptLimiter.onFailure(dto.getUsername(), clientIp);
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != ACTIVE) {
            loginAttemptLimiter.onFailure(dto.getUsername(), clientIp);
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号不可用");
        }

        loginAttemptLimiter.onSuccess(dto.getUsername(), clientIp);
        List<String> roles = rolesOf(user.getId());
        String token = jwtUtil.issue(user.getId(), user.getUsername(), roles);

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setExpiresIn(jwtUtil.getTtlMinutes() * 60);
        vo.setUser(toUserVO(user, roles));
        return vo;
    }

    @Override
    public UserVO me() {
        CurrentUser current = UserContext.requireUser();
        UserVO vo = new UserVO();
        vo.setId(current.userId());
        vo.setUsername(current.username());
        vo.setRealName(current.realName());
        vo.setDeptId(current.deptId());
        // JWT 里只放 userId/username/roles，姓名与部门按需回查（一次主键查询，成本可忽略）
        SysUser user = sysUserMapper.selectById(current.userId());
        if (user != null) {
            vo.setRealName(user.getRealName());
            vo.setDeptId(user.getDeptId());
        }
        vo.setRoles(new ArrayList<>(current.roles()));
        return vo;
    }

    private List<String> rolesOf(Long userId) {
        List<String> codes = sysUserMapper.selectRoleCodes(userId);
        return codes == null ? List.of() : codes;
    }

    private UserVO toUserVO(SysUser user, List<String> roles) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setDeptId(user.getDeptId());
        vo.setRoles(roles);
        return vo;
    }
}
