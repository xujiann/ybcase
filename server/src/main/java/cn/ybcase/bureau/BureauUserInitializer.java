package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysRole;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysUserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 首次启动创建默认账号：admin 及三个演示岗位账号（生产环境须立即修改密码） */
@Slf4j
@Component
@RequiredArgsConstructor
public class BureauUserInitializer implements ApplicationRunner {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() == 0) {
            create("admin", "系统管理员", "ADMIN");
            create("banban", "王办案", "HANDLER");
            create("fazhi", "李法制", "LEGAL");
            create("juzhang", "赵局长", "LEADER");
            log.warn("已创建默认账号 admin/banban/fazhi/juzhang（口令 admin123），请尽快修改！");
        }
        linkEnforcersToAccounts();
    }

    /**
     * 执法证台账 ↔ 账号映射补全（数据范围按账号判定参办人）。
     * V15 的回填在 Flyway 阶段执行，早于本初始化器建账号，故新库须在此补一次；
     * 幂等且只填空值，重名不认——宁可留空由管理员在台账指定，也不退回"同名即同人"。
     */
    private void linkEnforcersToAccounts() {
        int n = entityManager.createNativeQuery("""
                update enforcer e set user_id = su.id
                from sys_user su
                where su.real_name = e.name and e.user_id is null
                  and (select count(*) from sys_user x where x.real_name = e.name) = 1""")
                .executeUpdate();
        if (n > 0) log.info("执法证台账已关联 {} 个系统账号（数据范围据此判定参办）", n);
    }

    private void create(String username, String realName, String roleCode) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("admin123"));
        u.setRealName(realName);
        SysRole role = entityManager
                .createQuery("from SysRole where code = :c", SysRole.class)
                .setParameter("c", roleCode)
                .getSingleResult();
        u.getRoles().add(role);
        userRepository.save(u);
    }
}
