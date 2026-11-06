package cn.ybcase.core.web;

import cn.ybcase.core.common.R;
import cn.ybcase.core.entity.SysDept;
import cn.ybcase.core.repository.SysDeptRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/depts")
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptRepository deptRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final cn.ybcase.core.security.AdminWriteAccess adminWrites;
    private final jakarta.persistence.EntityManager entityManager;

    public record SaveDeptRequest(@NotBlank String name, @NotBlank String code,
                                  @NotBlank String type, Long parentId, Integer sortNo,
                                  String orgKind, String responsibility) {
        public SaveDeptRequest(String name, String code, String type, Long parentId, Integer sortNo) {
            this(name, code, type, parentId, sortNo, null, null);
        }
    }

    /** 所有登录用户可读（下拉选择用） */
    @GetMapping
    public R<List<SysDept>> list() {
        return R.ok(deptRepository.findAll(Sort.by("sortNo", "id")));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<SysDept> create(@RequestBody SaveDeptRequest req) {
        lockDirectory();
        adminWrites.requireCurrentAdmin();
        String error = fieldError(req, null);
        if (error != null) return R.fail(1202, error);
        SysDept d = new SysDept();
        d.setOrgKind(req.orgKind());
        d.setName(req.name());
        d.setCode(req.code());
        d.setType(req.type());
        d.setParentId(req.parentId());
        if (req.responsibility() != null) d.setResponsibility(req.responsibility().strip());
        if (req.sortNo() != null) d.setSortNo(req.sortNo());
        return R.ok(deptRepository.save(d));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<SysDept> update(@PathVariable Long id, @RequestBody SaveDeptRequest req) {
        lockDirectory();
        if (!lockDept(id)) return R.fail(1201, "科室不存在");
        adminWrites.requireCurrentAdmin();
        String error = fieldError(req, id);
        if (error != null) return R.fail(1202, error);
        SysDept d = currentDept(id);
        if (d == null) return R.fail(1201, "科室不存在");
        if (req.orgKind() != null && !req.orgKind().equals(d.getOrgKind()))
            return R.fail(1202, "已建立机构的账号归属类别固定，请选用相应类别的机构");
        d.setName(req.name());
        d.setCode(req.code());
        d.setType(req.type());
        d.setParentId(req.parentId());
        if (req.responsibility() != null) d.setResponsibility(req.responsibility().strip());
        if (req.sortNo() != null) d.setSortNo(req.sortNo());
        return R.ok(deptRepository.save(d));
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        lockDirectory();
        if (!lockDept(id)) return R.fail(1201, "科室不存在");
        if (enabled) {
            adminWrites.requireCurrentAdmin();
        } else {
            var builtIn = jdbc.queryForList("select id from sys_user where username='admin'", Long.class);
            adminWrites.requireCurrentAdmin(builtIn.isEmpty() ? null : builtIn.getFirst());
            // The account lock serializes a concurrent administrator reassignment with this check.
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from sys_user where username='admin' and dept_id=?)", Boolean.class, id)))
                return R.fail(1202, "请先将内置管理员调整到启用的机构，再停用此机构");
        }
        SysDept d = currentDept(id);
        if (d == null) return R.fail(1201, "科室不存在");
        d.setEnabled(enabled);
        deptRepository.save(d);
        return R.ok();
    }

    private void lockDirectory() {
        // Serializes the existing tree/code mutations, including competing inserts with no row yet.
        jdbc.queryForList("select pg_advisory_xact_lock(20261028,1201)");
    }

    private boolean lockDept(Long id) {
        // Compatible with the FK KEY SHARE taken while a user selects this department.
        return !jdbc.queryForList("select id from sys_dept where id=? for no key update", id).isEmpty();
    }

    private SysDept currentDept(Long id) {
        SysDept dept = deptRepository.findById(id).orElse(null);
        if (dept != null) entityManager.refresh(dept);
        return dept;
    }

    private String fieldError(SaveDeptRequest req, Long id) {
        if (req == null || !validText(req.name(),64) || !validText(req.code(),32) || !validText(req.type(),16))
            return "名称、编码和类型不能为空，不能含空字符且须符合字段长度";
        // These are the four existing choices in DeptsView and SysDept's persisted type contract.
        if (!java.util.Set.of("CLINICAL","MEDTECH","ADMIN","NURSING").contains(req.type()))
            return "科室类型无效";
        if (req.orgKind() != null && !java.util.Set.of("ROOT", "BUREAU", "DISTRICT", "AGENCY").contains(req.orgKind()))
            return "机构账号归属类别无效";
        if (req.responsibility() != null && (req.responsibility().length() > 1000 || req.responsibility().indexOf(0) >= 0))
            return "机构职责须在1000字以内";
        Integer duplicate = id == null
                ? jdbc.queryForObject("select count(*) from sys_dept where code=?", Integer.class, req.code())
                : jdbc.queryForObject("select count(*) from sys_dept where code=? and id<>?", Integer.class, req.code(), id);
        if (duplicate != null && duplicate > 0) return "科室编码已存在";
        java.util.Set<Long> visited = new java.util.HashSet<>();
        Long parent = req.parentId();
        while (parent != null) {
            if (parent.equals(id) || !visited.add(parent)) return "上级科室不能形成循环";
            var rows = jdbc.queryForList("select parent_id from sys_dept where id=?", parent);
            if (rows.isEmpty()) return "上级科室不存在";
            Object next = rows.get(0).get("parent_id");
            parent = next instanceof Number number ? number.longValue() : null;
        }
        return null;
    }

    private boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.indexOf('\0') < 0
                && value.codePointCount(0,value.length()) <= max;
    }
}
