package cn.ybcase.bureau.repository;

import cn.ybcase.bureau.entity.CaseFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseFileRepository extends JpaRepository<CaseFile, Long> {

    List<CaseFile> findTop200ByOrderByIdDesc();

    /** 无 limit 会把归档多年的全部案件（含大文本）一次载入，故与 findTop200 对齐 */
    List<CaseFile> findTop200ByStatusOrderByIdDesc(String status);

}
