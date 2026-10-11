package com.marketflow.identity.application;

import com.marketflow.identity.config.IdentityProperties;
import com.marketflow.platform.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-client-IP sliding-minute counter for login and password-reset attempts, stored in Redis so it is shared
 * by all identity-service replicas. Complements the per-account lockout persisted in PostgreSQL.
 *
 * <p>If Redis is unavailable the throttle fails OPEN (logs a warning): the database-backed account lockout still
 * bounds online password guessing, and availability of login is preferred over a hard dependency on Redis.
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private final StringRedisTemplate redis;
    private final IdentityProperties props;

    public LoginThrottle(StringRedisTemplate redis, IdentityProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void check(String scope, String clientIp) {
        String key = "mf:throttle:" + scope + ":" + (clientIp == null ? "unknown" : clientIp);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofMinutes(1));
            }
            if (count != null && count > props.loginAttemptsPerIpPerMinute()) {
                throw ApiException.tooManyRequests("Too many attempts. Try again in a minute.");
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Login throttle unavailable (failing open): {}", e.getClass().getSimpleName());
        }
    }
}
