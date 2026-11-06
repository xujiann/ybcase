package cn.ybcase.core.web;

import cn.ybcase.core.common.R;
import cn.ybcase.core.entity.SysMenu;
import cn.ybcase.core.entity.SysRole;
import cn.ybcase.core.repository.SysMenuRepository;
import cn.ybcase.core.repository.SysRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleRepository roleRepository;
    private final SysMenuRepository menuRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final cn.ybcase.core.security.AdminWriteAccess adminWrites;

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(roleRepository.findAll().stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(),
                        "name", r.getName(),
                        "code", r.getCode(),
                        "remark", r.getRemark() == null ? "" : r.getRemark(),
                        "menuCount", r.getMenus().size(),
                        "menuIds", r.getMenus().stream().map(SysMenu::getId).toList()))
                .toList());
    }

    /** 全部菜单（授权树用） */
    @GetMapping("/menus")
    @PreAuthorize("hasRole('ADMIN')")
    public R<List<SysMenu>> allMenus() {
        return R.ok(menuRepository.findAll());
    }

    public record GrantRequest(List<Long> menuIds) {}

    /** 保存角色授权（整表替换）。内置 ADMIN 角色不可改，防止把自己锁在门外 */
    @PutMapping("/{id}/menus")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Void> grant(@PathVariable Long id, @RequestBody GrantRequest req) {
        // NO KEY UPDATE serializes replacements while allowing user-role FK KEY SHARE, so a
        // concurrent account update can finish before this request locks its administrator.
        var rows = jdbc.queryForList("select code from sys_role where id=? for no key update", id);
        if (rows.isEmpty()) return R.fail(1301, "角色不存在");
        adminWrites.requireCurrentAdmin();
        if ("ADMIN".equals(rows.get(0).get("code"))) return R.fail(1302, "内置管理员角色不可修改授权");
        if (req == null || req.menuIds() == null || req.menuIds().stream().anyMatch(menu -> menu == null || menu <= 0))
            return R.fail(1303, "请选择有效菜单，清空授权请提交空数组");
        var ids = req.menuIds().stream().distinct().sorted().toList();
        var menus = menuRepository.findAllById(ids);
        if (menus.size() != ids.size()) return R.fail(1303, "包含不存在的菜单，请刷新后重新选择");
        // Explicit SQL replacement starts from the latest locked row, not an eager JPA collection
        // cached before another grant committed. Invalid input never clears existing permissions.
        jdbc.update("delete from sys_role_menu where role_id=?", id);
        for (Long menu : ids) jdbc.update("insert into sys_role_menu(role_id,menu_id) values (?,?)", id, menu);
        return R.ok();
    }
}
