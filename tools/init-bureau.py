# -*- coding: utf-8 -*-
"""实施初始化工具：机构参数/口径参数/人员执法证批量写入（对全新库运行）

用法：
  python tools/init-bureau.py --org 某某市医疗保障局 --short 某某市医保局 \
      --prefix-case 某医保案 --prefix-decision 某医保罚 --province liaoning
  --province liaoning  一键切换辽宁口径（辽医保发〔2020〕5号 21 项差异参数）
  --enforcers file.csv 批量导入执法证（姓名,证号,部门,有效期,法律职业资格0/1）
"""
import argparse
import csv
import sys

import requests

BASE = "http://127.0.0.1:8090/api"

LIAONING = {
    "clue_verify_day_unit": "NATURAL", "legal_review_mode": "ALL",
    "legal_review_days": "7", "legal_review_day_unit": "NATURAL",
    "hearing_threshold_individual": "1000", "hearing_threshold_org": "10000",
    "onsite_collect_limit": "20", "delivery_electronic_decision_allowed": "false",
    "cross_exam_required": "true", "evidence_hold_day_unit": "NATURAL",
    # 简易限额维持新《行政处罚法》口径（200/3000），如局方法规部门确认按辽宁旧口径再改 50/1000
}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--org", required=True)
    p.add_argument("--short")
    p.add_argument("--prefix-case")
    p.add_argument("--prefix-decision")
    p.add_argument("--phone")
    p.add_argument("--province", choices=["national", "liaoning"], default="national")
    p.add_argument("--enforcers")
    p.add_argument("--admin-password", default="admin123")
    args = p.parse_args()

    r = requests.post(f"{BASE}/auth/login",
                      json={"username": "admin", "password": args.admin_password}, timeout=10)
    token = r.json()["data"]["token"]
    h = {"Authorization": f"Bearer {token}"}

    def set_cfg(k, v):
        resp = requests.put(f"{BASE}/config/{k}", params={"value": v}, headers=h, timeout=10)
        assert resp.json()["code"] == 0, f"{k}: {resp.text}"
        print(f"  {k} = {v}")

    print("== 机构参数 ==")
    set_cfg("org_name", args.org)
    if args.short: set_cfg("org_short", args.short)
    if args.prefix_case: set_cfg("case_no_prefix", args.prefix_case)
    if args.prefix_decision: set_cfg("decision_no_prefix", args.prefix_decision)
    if args.phone: set_cfg("contact_phone", args.phone)

    if args.province == "liaoning":
        print("== 辽宁口径参数 ==")
        for k, v in LIAONING.items():
            set_cfg(k, v)

    if args.enforcers:
        print("== 执法证台账 ==")
        with open(args.enforcers, encoding="utf-8-sig") as f:
            for row in csv.reader(f):
                if len(row) < 4: continue
                resp = requests.post(f"{BASE}/bureau/enforcers", headers=h, timeout=10, json={
                    "name": row[0].strip(), "certNo": row[1].strip(), "dept": row[2].strip(),
                    "certExpireAt": row[3].strip(),
                    "legalQualified": len(row) > 4 and row[4].strip() == "1"})
                assert resp.json()["code"] == 0, resp.text
                print(f"  {row[0]} {row[1]}")

    print("完成。提醒：1) 修改全部默认口令 2) 维护当年节假日表 3) 验收前替换裁量基准种子")


if __name__ == "__main__":
    main()
