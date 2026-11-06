# -*- coding: utf-8 -*-
"""Initialize bureau settings and register certificates through the admin API."""
import argparse
import csv
import getpass
import os
import sys
from datetime import date
from urllib.parse import urlsplit

import requests

BASE = "http://127.0.0.1:8090/api"

LIAONING = {
    "clue_verify_day_unit": "NATURAL", "legal_review_mode": "ALL",
    "legal_review_days": "7", "legal_review_day_unit": "NATURAL",
    "hearing_threshold_individual": "1000", "hearing_threshold_org": "10000",
    "onsite_collect_limit": "20", "delivery_electronic_decision_allowed": "false",
    "cross_exam_required": "true", "evidence_hold_day_unit": "NATURAL",
}

ENFORCEMENT_ROLES = {"HANDLER", "LEGAL", "LEADER"}


def read_enforcers(path):
    """Read 4–6 positional CSV fields with an optional Chinese header."""
    records = []
    cert_numbers = set()
    with open(path, encoding="utf-8-sig", newline="") as source:
        reader = csv.reader(source, strict=True)
        first = True
        for row in reader:
            line = reader.line_num
            row = [value.strip() for value in row]
            if not any(row):
                continue
            if first and row[0] == "姓名" and len(row) > 1 and row[1] in ("证号", "执法证号"):
                first = False
                continue
            first = False
            if not 4 <= len(row) <= 6:
                raise ValueError(f"执法证 CSV 第 {line} 行应有 4 至 6 列")
            name, cert, dept, expires, qualification, username = row + [""] * (6 - len(row))
            if not name or not cert or any(len(value) > 64 or "\x00" in value for value in (name, cert, dept, username)):
                raise ValueError(f"执法证 CSV 第 {line} 行的姓名、证号或字段长度无效")
            if cert in cert_numbers:
                raise ValueError(f"执法证 CSV 第 {line} 行与前面的证号重复")
            if qualification not in ("", "0", "1"):
                raise ValueError(f"执法证 CSV 第 {line} 行的法律职业资格须为 0 或 1")
            try:
                parsed = date.fromisoformat(expires)
                if parsed.isoformat() != expires:
                    raise ValueError
            except ValueError:
                raise ValueError(f"执法证 CSV 第 {line} 行的日期须为 YYYY-MM-DD") from None
            cert_numbers.add(cert)
            records.append((line, username, {
                "name": name, "certNo": cert, "dept": dept,
                "certExpireAt": expires, "legalQualified": qualification == "1",
            }))
    return records


def all_users(api):
    users = {}
    page = 0
    while True:
        data = api("GET", "/system/users", params={"page": page, "size": 100})
        for row in data["records"]:
            users[row["username"]] = row
        if (page + 1) * 100 >= data["total"]:
            return users
        if not data["records"]:
            raise ValueError("用户清单分页不完整，请刷新后重试")
        page += 1


def prepare_enforcers(records, api):
    if not records:
        return []
    existing = {row["cert_no"]: row for row in api("GET", "/bureau/enforcers")}
    needs_binding = any(username for _, username, _ in records)
    users = all_users(api) if needs_binding else {}
    departments = {row["id"]: row for row in api("GET", "/system/depts")} if needs_binding else {}
    result = []
    for line, username, fields in records:
        value = dict(fields)
        previous = existing.get(value["certNo"])
        if previous:
            value["enabled"] = previous["enabled"]
        if username:
            user = users.get(username)
            if not user or not user["enabled"] or not ENFORCEMENT_ROLES.intersection(user["roleCodes"]):
                raise ValueError(f"执法证 CSV 第 {line} 行的登录账号不存在、已停用或无办案岗位")
            department = departments.get(user["deptId"])
            if user["realName"] != value["name"] or not department or not department["enabled"]:
                raise ValueError(f"执法证 CSV 第 {line} 行的姓名或所属机构与账号不一致")
            if value["dept"] and value["dept"] != department["name"]:
                raise ValueError(f"执法证 CSV 第 {line} 行的部门与账号不一致")
            if previous and previous["user_id"] not in (None, user["id"]):
                raise ValueError(f"执法证 CSV 第 {line} 行的证号已绑定其他账号，请先核对")
            value.update(userId=user["id"], dept=department["name"])
        result.append((line, value))
    return result


def api_base(value):
    url = urlsplit(value)
    if (not url.hostname or url.username or url.password or url.query or url.fragment
            or url.path.rstrip("/") != "/api"
            or not (url.scheme == "https" or url.scheme == "http"
                    and url.hostname in {"127.0.0.1", "localhost", "::1"})):
        raise ValueError("服务地址须为 HTTPS 的 /api 地址，或本机回环 HTTP 地址")
    return value.rstrip("/")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=BASE)
    parser.add_argument("--org")
    parser.add_argument("--short")
    parser.add_argument("--prefix-case")
    parser.add_argument("--prefix-decision")
    parser.add_argument("--phone")
    parser.add_argument("--province", choices=["national", "liaoning"], default="national")
    parser.add_argument("--enforcers", help="CSV 列顺序为姓名、执法证号、部门、有效期、资格0/1、登录账号")
    parser.add_argument("--admin-user", default="admin")
    parser.add_argument("--admin-password", help=argparse.SUPPRESS)
    parser.add_argument("--dry-run", action="store_true", help="验证输入和账号映射，跳过业务写入")
    args = parser.parse_args()

    settings = {key: value for key, value in (
        ("org_name", args.org), ("org_short", args.short), ("case_no_prefix", args.prefix_case),
        ("decision_no_prefix", args.prefix_decision), ("contact_phone", args.phone),
    ) if value is not None}
    if args.province == "liaoning":
        settings.update(LIAONING)
    if not settings and not args.enforcers:
        parser.error("请指定机构参数、辽宁口径或执法证 CSV")

    try:
        base = api_base(args.base_url)
        records = read_enforcers(args.enforcers) if args.enforcers else []
        password = args.admin_password or os.environ.get("YBCASE_ADMIN_PASSWORD") or getpass.getpass("管理员密码：")
        with requests.Session() as session:
            def api(method, path, **kwargs):
                try:
                    response = session.request(method, base + path, timeout=15, allow_redirects=False, **kwargs)
                    if response.status_code != 200:
                        raise ValueError(f"{path} 请求失败（HTTP {response.status_code}）")
                    payload = response.json()
                except requests.RequestException:
                    raise ValueError(f"{path} 网络请求失败，请检查目标地址和连接") from None
                if payload.get("code") != 0:
                    raise ValueError(f"{path} 请求被拒绝（业务码 {payload.get('code')}）")
                return payload.get("data")

            auth = api("POST", "/auth/login", json={"username": args.admin_user, "password": password})
            if auth.get("mustChangePassword"):
                raise ValueError("管理员须先完成首次登录改密")
            session.headers["Authorization"] = "Bearer " + auth["token"]
            if "ADMIN" not in api("GET", "/auth/me")["roles"]:
                raise ValueError("须使用系统管理员账号")
            certificates = prepare_enforcers(records, api)
            print(f"已校验 {len(settings)} 项参数和 {len(certificates)} 条执法证记录")
            if args.dry_run:
                print("预检通过，已跳过参数和执法证写入")
                return 0

            completed_settings = completed_certificates = 0
            try:
                for key, value in settings.items():
                    api("PUT", f"/config/{key}", params={"value": value})
                    completed_settings += 1
                for line, payload in certificates:
                    try:
                        api("POST", "/bureau/enforcers", json=payload)
                    except ValueError as exc:
                        raise ValueError(f"执法证 CSV 第 {line} 行写入失败，{exc}") from None
                    completed_certificates += 1
            except ValueError as exc:
                raise ValueError(f"{exc}。已完成 {completed_settings} 项参数、{completed_certificates} 条执法证，请核对后继续") from None
            print(f"已写入 {completed_settings} 项参数和 {completed_certificates} 条执法证记录")
            return 0
    except (OSError, ValueError, csv.Error) as exc:
        print(f"初始化失败，{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
