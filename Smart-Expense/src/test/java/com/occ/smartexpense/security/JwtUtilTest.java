package com.occ.smartexpense.security;

import com.occ.smartexpense.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 签发与解析单元测试，见 docs/12-认证与权限.md §3。
 *
 * <p>钉住四件事：正常往返、过期拒绝、签名被改拒绝、换密钥拒绝。
 */
class JwtUtilTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private JwtUtil util(long ttlMinutes) {
        return new JwtUtil(SECRET, ttlMinutes);
    }

    @Test
    void issueThenParse_roundTripsIdentityAndRoles() {
        String token = util(120).issue(7L, "linyi", List.of("EMPLOYEE", "SUPERVISOR"));

        CurrentUser user = util(120).parse(token);

        assertEquals(7L, user.userId());
        assertEquals("linyi", user.username());
        assertTrue(user.roles().containsAll(Set.of("EMPLOYEE", "SUPERVISOR")));
    }

    @Test
    void expiredToken_isRejected() {
        // 负 TTL 签出来的 token 一出生就过期
        String token = util(-1).issue(1L, "linyi", List.of("EMPLOYEE"));

        assertThrows(BizException.class, () -> util(120).parse(token));
    }

    @Test
    void tamperedSignature_isRejected() {
        String token = util(120).issue(1L, "linyi", List.of("EMPLOYEE"));

        assertThrows(BizException.class, () -> util(120).parse(token + "x"));
    }

    @Test
    void tokenSignedWithAnotherSecret_isRejected() {
        String token = util(120).issue(1L, "linyi", List.of("EMPLOYEE"));
        JwtUtil another = new JwtUtil("ffffffffffffffffffffffffffffffff", 120);

        assertThrows(BizException.class, () -> another.parse(token));
    }

    @Test
    void malformedToken_isRejectedWithoutLeakingInternals() {
        assertThrows(BizException.class, () -> util(120).parse("not-a-jwt"));
    }
}