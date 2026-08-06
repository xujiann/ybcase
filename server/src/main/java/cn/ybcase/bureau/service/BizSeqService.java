package cn.ybcase.bureau.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 业务编号序列：insert on conflict do update returning，行锁原子取号（消除 count+1 并发撞号） */
@Service
@RequiredArgsConstructor
public class BizSeqService {

    private final JdbcTemplate jdbc;

    public int next(String kind, int year) {
        Integer v = jdbc.queryForObject("""
                insert into biz_seq (kind, year, val) values (?, ?, 1)
                on conflict (kind, year) do update set val = biz_seq.val + 1
                returning val""", Integer.class, kind, year);
        return v == null ? 1 : v;
    }
}
