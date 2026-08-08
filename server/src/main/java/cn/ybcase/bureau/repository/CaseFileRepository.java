package cn.ybcase.bureau.repository;

import cn.ybcase.bureau.entity.CaseFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseFileRepository extends JpaRepository<CaseFile, Long> {

    List<CaseFile> findTop200ByOrderByIdDesc();

    List<CaseFile> findByStatusOrderByIdDesc(String status);

}
