package cn.ybcase.core.security;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DbUserDetailsService implements UserDetailsService {

    private final SysUserRepository userRepository;
    private final cn.ybcase.core.repository.SysDeptRepository departmentRepository;

    @Override
    public AuthUser loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.getCode()))
                .toList();
        boolean departmentEnabled = user.getDeptId() == null
                || departmentRepository.findById(user.getDeptId()).map(dept -> Boolean.TRUE.equals(dept.getEnabled())).orElse(false);
        return new AuthUser(user.getUsername(), user.getPassword(), Boolean.TRUE.equals(user.getEnabled()) && departmentEnabled,
                user.getTokenVersion(), user.getId(), Boolean.TRUE.equals(user.getMustChangePassword()), authorities);
    }
}
