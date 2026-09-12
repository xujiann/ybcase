package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysRole;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysUserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import java.util.List;
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
    private final org.springframework.core.env.Environment env;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() == 0) {
            boolean pilot = List.of(env.getActiveProfiles()).contains("pilot");
            if (pilot) {
                // 生产/试运行：仓库是公开的，"admin123"等于没有口令。初始口令只从环境变量取，
                // 缺失即拒绝启动（与 JWT 密钥门禁同一思路）；且不建 banban/fazhi/juzhang 演示账号，
                // 真实局方的人员由管理员在用户管理里建。
                String init = env.getProperty("YBCASE_ADMIN_INIT_PASSWORD");
                if (init == null || init.isBlank() || "admin123".equals(init))
                    throw new IllegalStateException(
                            "拒绝在空库上以默认口令初始化 pilot：请设置环境变量 YBCASE_ADMIN_INIT_PASSWORD"
                                    + "（管理员初始口令，首次登录会强制修改）后重启。");
                create("admin", "系统管理员", "ADMIN", init);
                log.warn("已按环境变量创建管理员账号 admin，首次登录须修改口令");
            } else {
                create("admin", "系统管理员", "ADMIN", "admin123");
                create("banban", "王办案", "HANDLER", "admin123");
                create("fazhi", "李法制", "LEGAL", "admin123");
                create("juzhang", "赵局长", "LEADER", "admin123");
                log.warn("已创建默认账号 admin/banban/fazhi/juzhang（口令 admin123），仅限开发/测试！");
            }
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

    private void create(String username, String realName, String roleCode, String initPassword) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(initPassword));
        u.setMustChangePassword(true);   // 初始口令不是本人设的，首次登录必须改
        u.setRealName(realName);
        SysRole role = entityManager
                .createQuery("from SysRole where code = :c", SysRole.class)
                .setParameter("c", roleCode)
                .getSingleResult();
        u.getRoles().add(role);
        userRepository.save(u);
    }
}
