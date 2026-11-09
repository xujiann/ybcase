package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Caller holds the case lock and current actor authorization before recording immutable history. */
@Service
@RequiredArgsConstructor
public class CaseFactService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MaterialAccess access;

    public Map<String,Object> snapshot(long caseId) {
        var value=jdbc.queryForObject("""
          select jsonb_build_object('institutionCode',institution_code,'institutionOwnership',institution_ownership,
             'institutionGrade',institution_grade,'institutionKind',institution_kind,
             'confirmedViolationStart',confirmed_violation_start,'confirmedViolationEnd',confirmed_violation_end,
             'confirmedViolationFacts',confirmed_violation_facts,'confirmedViolationAmount',confirmed_violation_amount)::text
          from case_file where id=?
          """,String.class,caseId);
        return object(value);
    }

    public void record(long caseId,String type,Long requestId,Long reviewId,LocalDate effective,
                       Map<String,Object> facts,Map<String,Object> previous,String reason,String reference,String username) {
        var actor=access.readActor(username);
        jdbc.update("""
          insert into case_fact_revision(case_id,revision_type,request_id,review_id,effective_at,facts,
             previous_facts,reason,source_reference,actor_id,actor_username)
          values(?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?)
          """,caseId,type,requestId,reviewId,effective,json(facts),json(previous),reason,reference,actor.id(),username);
    }

    public List<Map<String,Object>> history(long caseId,String username) {
        access.requireCaseReader(caseId,username);
        return jdbc.queryForList("select * from case_fact_revision where case_id=? order by id desc",caseId).stream().map(row->{
            var result=new LinkedHashMap<String,Object>(row);
            result.put("facts",object(String.valueOf(row.get("facts"))));
            result.put("previous_facts",object(String.valueOf(row.get("previous_facts"))));
            return (Map<String,Object>)result;
        }).toList();
    }

    private Map<String,Object> object(String value) {
        try {return mapper.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}
        catch(Exception ex){throw new IllegalStateException("Fact snapshot unavailable",ex);}
    }
    private String json(Object value) {
        try{return mapper.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public static String text(String value,int max,String label) {
        if(value==null || value.isBlank() || value.codePointCount(0,value.length())>max || value.indexOf('\0')>=0)
            throw new BizException(2194,label+"必填且须为有效文本");
        return value.strip();
    }
}
