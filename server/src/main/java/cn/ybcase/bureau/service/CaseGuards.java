package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseFileRepository;

import java.util.List;

/** 案件状态守卫（拆分服务共用） */
final class CaseGuards {

    private CaseGuards() {}

    static CaseFile get(CaseFileRepository repo, Long id) {
        return repo.findById(id).orElseThrow(() -> new BizException(2043, "案件不存在"));
    }

    /** 调查办理阶段（取证/文书/扣除等操作的合法状态） */
    static void requireActive(CaseFile c) {
        if (!List.of("INVESTIGATING", "REPORTED", "NOTIFIED").contains(c.getStatus()))
            throw new BizException(2044, "案件当前状态（" + c.getStatus() + "）不允许该操作");
    }
}
