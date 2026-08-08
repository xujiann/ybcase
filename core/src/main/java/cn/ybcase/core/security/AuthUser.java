package cn.ybcase.core.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/** 带令牌版本的登录主体：改密/停用会自增版本，使已签发令牌立即失效 */
@Getter
public class AuthUser extends User {

    private final Long userId;
    private final int tokenVersion;

    public AuthUser(String username, String password, boolean enabled, int tokenVersion, Long userId,
                    Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, true, true, true, authorities);
        this.userId = userId;
        this.tokenVersion = tokenVersion;
    }
}
