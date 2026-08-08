package cn.ybcase.bureau.repository;

import cn.ybcase.bureau.entity.CaseDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CaseDecisionRepository extends JpaRepository<CaseDecision, Long> {

    Optional<CaseDecision> findByCaseId(Long caseId);

}
