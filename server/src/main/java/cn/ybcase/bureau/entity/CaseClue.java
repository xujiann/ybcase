package cn.ybcase.bureau.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** 违法行为线索（第14条：15个工作日内核查并决定是否立案，可延长15个工作日） */
@Getter
@Setter
@Entity
@Table(name = "case_clue")
public class CaseClue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String clueNo;

    /** INSPECTION 监督检查 / COMPLAINT 投诉举报 / TRANSFER 部门移送 / ASSIGNED 上级交办 / MONITOR 智能监控 */
    @Column(nullable = false, length = 16)
    private String source;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false, length = 128)
    private String suspectName;

    @Column(nullable = false, length = 16)
    private String suspectType;

    @Column(nullable = false)
    private LocalDate receivedAt;

    /** 核查期限（收到之日起 15 个工作日） */
    @Column(nullable = false)
    private LocalDate deadlineAt;

    /** 核查期限扣除天数（辽15条：鉴定、检验时间不计入） */
    @Column(nullable = false)
    private Integer excludedDays = 0;

    @Column(nullable = false)
    private Boolean extended = false;

    @Column(length = 255)
    private String extendReason;

    /** PENDING / FILED 已立案 / REJECTED 不予立案 */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(columnDefinition = "text")
    private String verifyResult;

    @Column(length = 64)
    private String handler;

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
