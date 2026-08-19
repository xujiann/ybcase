package cn.ybcase.bureau.repository;

import cn.ybcase.bureau.entity.CaseClue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseClueRepository extends JpaRepository<CaseClue, Long> {

    List<CaseClue> findTop200ByOrderByIdDesc();

    /** 与案件列表同限流：线索终态只增不减，无 limit 会把全表（含 content 大文本）一次载入 */
    List<CaseClue> findTop200ByStatusOrderByIdDesc(String status);

}
