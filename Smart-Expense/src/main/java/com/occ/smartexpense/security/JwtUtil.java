package com.occ.smartexpense.security;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JWT 签发与解析（HS256），见 docs/12 §3。
 * 密钥只从环境变量读取：JWT_SECRET（>=32 字节）。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtUtil(@Value("${auth.jwt-secret}") String secret,
                   @Value("${auth.token-ttl-minutes:120}") long ttlMinutes) {
        // HS256 要求 >= 256 bit，密钥太短会在启动时直接抛异常，属于"早失败"
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
    }

    public String issue(Long userId, String username, Collection<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlMinutes * 60)))
                .signWith(key)
                .compact();
    }

    public CurrentUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            List<?> rawRoles = claims.get("roles", List.class);
            Set<String> roles = rawRoles == null ? Set.of()
                    : rawRoles.stream().map(String::valueOf).collect(Collectors.toSet());
            return new CurrentUser(userId, username, null, null, roles);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期或凭证无效");
        }
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }
}
