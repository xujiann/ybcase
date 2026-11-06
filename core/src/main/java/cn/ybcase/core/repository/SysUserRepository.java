package cn.ybcase.core.repository;

import cn.ybcase.core.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByUsername(String username);

    // Lock the user row alone; eager role joins can otherwise introduce outer-join lock issues.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from SysUser u where u.id = :id")
    Optional<SysUser> findByIdForUpdate(Long id);
}
