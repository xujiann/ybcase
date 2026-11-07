package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.spi.IntegrationGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TreasuryReconciliationService {
    private final JdbcTemplate jdbc;
    private final MaterialAccess access;
    private final IntegrationGateway gateway;

    public record Result(String status, boolean amountMatches, boolean dateMatches, boolean paid,
                         BigDecimal externalAmount,LocalDate externalPaidAt) {
        public Result(String status,boolean amountMatches,boolean dateMatches,boolean paid) {
            this(status,amountMatches,dateMatches,paid,null,null);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result reconcile(long caseId, long executionId) {
        access.requireCurrentCaseReader(caseId);
        var rows = jdbc.queryForList("""
                select receipt_no, amount, paid_at from case_execution where id=? and case_id=?
                """, executionId, caseId);
        if (rows.isEmpty()) throw new BizException(2043, "案件执行记录不存在");
        var row = rows.getFirst();
        String receipt = (String) row.get("receipt_no");
        return lookup(receipt, (BigDecimal) row.get("amount"), ((java.sql.Date) row.get("paid_at")).toLocalDate());
    }

    public Result lookup(String receipt, BigDecimal amount, LocalDate paidAt) {
        if (receipt == null || receipt.isBlank()) return new Result("MISSING_RECEIPT", false, false, false);
        if (!gateway.configured("treasury")) return new Result("UNCONFIGURED", false, false, false);

        var response = gateway.post("treasury", "receipts/lookup", Map.of("receiptNo", receipt), null);
        String status = IntegrationGateway.text(response, "status", 16);
        if ("NOT_FOUND".equals(status)) return new Result("NOT_FOUND", false, false, false);
        if (!"FOUND".equals(status) || !receipt.equals(IntegrationGateway.text(response, "receiptNo", 64))
                || !response.path("amount").isNumber() || !response.path("paid").isBoolean())
            throw IntegrationGateway.invalidResponse();
        LocalDate date;
        try {
            date = LocalDate.parse(IntegrationGateway.text(response, "paidDate", 10));
        } catch (DateTimeParseException e) {
            throw IntegrationGateway.invalidResponse();
        }
        BigDecimal externalAmount=response.path("amount").decimalValue();
        if(externalAmount.signum()<0 || externalAmount.precision()>38 || externalAmount.scale()>24 || externalAmount.scale() < -24)
            throw IntegrationGateway.invalidResponse();
        boolean matchesAmount = amount.compareTo(externalAmount) == 0;
        boolean matchesDate = paidAt.equals(date);
        boolean paid = response.path("paid").booleanValue();
        return new Result(matchesAmount && matchesDate && paid ? "MATCH" : "DIFFERENT", matchesAmount, matchesDate, paid,
                externalAmount,date);
    }
}
