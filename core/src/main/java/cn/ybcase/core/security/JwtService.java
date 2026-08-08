package cn.ybcase.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** JWT 签发与校验 */
@Service
public class JwtService {

    /** 仓库内公开的开发默认值：任何联网环境使用它都等同于无认证 */
    static final String DEV_SECRET = "dev-only-secret-change-me-bureau-case-0123456789";

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(@Value("${ybcase.security.jwt-secret}") String secret,
                      @Value("${ybcase.security.jwt-expiry-hours:12}") long expiryHours,
                      Environment env) {
        if (DEV_SECRET.equals(secret) && List.of(env.getActiveProfiles()).contains("pilot"))
            throw new IllegalStateException(
                    "拒绝以开发默认 JWT 密钥启动 pilot：该密钥公开在代码仓库中，任何人可自行签发管理员令牌。"
                            + "请设置环境变量 YBCASE_SECURITY_JWT_SECRET（32 位以上随机串）后重启。");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiry = Duration.ofHours(expiryHours);
    }

    /** tokenVersion 写入令牌，改密/停用自增后旧令牌校验即失败 */
    public String issue(String username, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("tv", tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key)
                .compact();
    }

    /** 校验并返回声明；无效或过期抛出 JwtException */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /** 令牌携带的版本号（历史令牌无该声明时按 0 处理） */
    public static int tokenVersionOf(Claims claims) {
        Object tv = claims.get("tv");
        return tv instanceof Number n ? n.intValue() : 0;
    }
}
