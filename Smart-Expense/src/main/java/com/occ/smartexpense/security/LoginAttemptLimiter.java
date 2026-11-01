package com.occ.smartexpense.security;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流（docs/12 §9）：同一「用户名 + IP」1 分钟内连续 5 次失败后拒绝继续尝试，成功即清零。
 *
 * <p>三个必须有的保护：
 * <ol>
 *   <li>按「用户名 + IP」双维度计数：只按用户名会被用来锁死别人的账号（DoS）；</li>
 *   <li>Map 有容量上限 + 定时清理：随机用户名的脚本不能把内存撑爆；</li>
 *   <li>超限时直接 429，不查库、不比对密码。</li>
 * </ol>
 * 这是**软限流**：单机内存、多实例各算各的、并发窗口内的超额请求可能漏过。
 * 真要做严格防撞库，需要 Redis 原子计数 + IP 维度 + 验证码，别在简历写"防撞库"。
 */
@Slf4j
@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(1);
    /** 最多跟踪多少个「用户名|IP」键；到达后先清过期，仍超限就放弃计数（宁可少限流，不能 OOM） */
    static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    /** 登录前置检查：超限直接 429 */
    public void check(String username, String clientIp) {
        String key = key(username, clientIp);
        if (key == null) {
            return;
        }
        Deque<Instant> window = failures.get(key);
        if (window == null) {
            return;
        }
        synchronized (window) {
            purge(window);
            if (window.size() >= MAX_FAILURES) {
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "登录尝试过于频繁，请 1 分钟后再试");
            }
        }
    }

    public void onFailure(String username, String clientIp) {
        String key = key(username, clientIp);
        if (key == null) {
            return;
        }
        if (failures.size() >= MAX_TRACKED_KEYS && !failures.containsKey(key)) {
            purgeExpired();
            if (failures.size() >= MAX_TRACKED_KEYS) {
                log.warn("登录限流键数量达到上限 {}，本条失败不再计数", MAX_TRACKED_KEYS);
                return;
            }
        }
        Deque<Instant> window = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            purge(window);
            window.addLast(Instant.now());
        }
    }

    public void onSuccess(String username, String clientIp) {
        String key = key(username, clientIp);
        if (key != null) {
            failures.remove(key);
        }
    }

    /** 每 5 分钟清一次过期窗口，避免 Map 只增不减 */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 300_000L)
    public void purgeExpired() {
        int before = failures.size();
        failures.entrySet().removeIf(entry -> {
            Deque<Instant> window = entry.getValue();
            synchronized (window) {
                purge(window);
                return window.isEmpty();
            }
        });
        int removed = before - failures.size();
        if (removed > 0) {
            log.debug("登录限流清理：移除 {} 个过期窗口，剩余 {}", removed, failures.size());
        }
    }

    /** 用户名大小写不敏感 + IP 维度：alice@1.1.1.1 与 alice@2.2.2.2 互不影响 */
    private String key(String username, String clientIp) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
        return username.trim().toLowerCase(Locale.ROOT) + "|" + ip;
    }

    private void purge(Deque<Instant> window) {
        Instant threshold = Instant.now().minus(WINDOW);
        while (!window.isEmpty() && window.peekFirst().isBefore(threshold)) {
            window.pollFirst();
        }
    }
}
