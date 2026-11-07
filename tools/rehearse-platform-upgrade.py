#!/usr/bin/env python3
"""Rehearse V34 upgrade, attachment restoration and binary rollback in two disposable databases."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tarfile
import tempfile
import time
from collections import Counter
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from zipfile import ZipFile

RELEASE_V34 = "3f0dec99a55306878eba8ed87c0b8415d8d6906a8fba952f46b30fe2daaf470c"


def digest(path):
    sha256 = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            sha256.update(block)
    return sha256.hexdigest()


def request(port, path, body=None, token=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(body, ensure_ascii=False).encode()
    req = Request(f"http://127.0.0.1:{port}{path}", data=body, headers=headers)
    try:
        with urlopen(req, timeout=55) as response:
            return response.status, response.headers, response.read()
    except HTTPError as error:
        return error.code, error.headers, error.read()


def api(port, path, body=None, token=None):
    status, _, raw = request(port, "/api" + path, body, token)
    result = json.loads(raw)
    if status != 200 or result.get("code") != 0:
        raise RuntimeError(f"{path} failed: HTTP {status}, business code {result.get('code')}")
    return result.get("data")


class Drill:
    def __init__(self, folder, args):
        self.folder = folder
        self.args = args
        self.env = os.environ.copy()
        if not self.env.get("PGPASSWORD"):
            raise ValueError("PGPASSWORD must contain the local rehearsal PostgreSQL password")
        self.env["PGTZ"] = "Asia/Shanghai"
        self.admin_password = "Drill-" + secrets.token_hex(16)
        self.jwt = secrets.token_urlsafe(48)
        self.names = []
        self.app = None
        self.port = None
        self.pg_prefix = ["wsl.exe", "-d", args.wsl_distro, "--"] if os.name == "nt" else []
        if self.pg_prefix:
            wsl_env = set(filter(None, self.env.get("WSLENV", "").split(":")))
            self.env["WSLENV"] = ":".join(sorted(wsl_env | {"PGPASSWORD", "PGTZ"}))

    def pg(self, command, *args, **kwargs):
        return subprocess.run(self.pg_prefix + [command, "-h", "127.0.0.1", "-p", str(self.args.db_port),
                              "-U", self.args.db_user, *args], env=self.env, check=True, **kwargs)

    def create(self):
        name = "ybcase_release_" + secrets.token_hex(6)
        self.pg("createdb", name)
        self.names.append(name)
        return name

    def restore(self, database, backup):
        assert database in self.names
        with backup.open("rb") as source:
            self.pg("pg_restore", "--exit-on-error", "--single-transaction", "-d", database, stdin=source)

    def sql(self, database, query):
        return self.pg("psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-d", database, "-f", "-",
                       input=query.rstrip().rstrip(";") + ";\n", encoding="utf-8", capture_output=True).stdout.strip()

    def start(self, jar, database, data_dir, name):
        assert database in self.names and self.app is None
        self.port = None
        env = {key: value for key, value in self.env.items()
               if not key.startswith(("SPRING_", "YBCASE_", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"))}
        env.update(YBCASE_DB_URL=f"jdbc:postgresql://127.0.0.1:{self.args.db_port}/{database}",
                   YBCASE_DB_USER=self.args.db_user, YBCASE_DB_PASSWORD=self.env["PGPASSWORD"],
                   YBCASE_ADMIN_INIT_PASSWORD=self.admin_password, YBCASE_JWT_SECRET=self.jwt,
                   YBCASE_LOG_FILE=str(self.folder / (name + ".application.log")))
        console = self.folder / (name + ".log")
        with console.open("wb") as log:
            self.app = subprocess.Popen(["java", "-Xmx512m", "-Duser.timezone=Asia/Shanghai", "-jar", str(jar),
                                         "--spring.profiles.active=pilot", "--server.address=127.0.0.1",
                                         "--server.port=0"],
                                        cwd=data_dir, env=env, stdout=log, stderr=log)
        end = time.monotonic() + 100
        while time.monotonic() < end:
            if self.app.poll() is not None:
                break
            if self.port is None:
                match = re.search(rb"Tomcat started on port (\d+) ", console.read_bytes())
                if match:
                    self.port = int(match[1])
                else:
                    time.sleep(1)
                    continue
            try:
                status, _, raw = request(self.port, "/actuator/health")
                if status == 200 and json.loads(raw).get("status") == "UP" and self.sql(
                        database, "select count(*) from sys_user where username='admin'") == "1":
                    return
            except (OSError, ValueError, URLError):
                pass
            time.sleep(1)
        raise RuntimeError(f"{name} failed readiness; inspect its isolated log")

    def stop(self):
        if self.app is not None:
            if self.app.poll() is None:
                self.app.terminate()
                try:
                    self.app.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    self.app.kill()
                    self.app.wait(timeout=10)
            self.app = None

    def snapshot(self, database, columns=None):
        if columns is None:
            columns = json.loads(self.sql(database, """
                select jsonb_object_agg(table_name, cols) from (
                    select table_name, jsonb_agg(column_name order by ordinal_position) cols
                    from information_schema.columns where table_schema='public'
                    group by table_name
                ) t
                """))
        def quote(value):
            return '"' + value.replace('"', '""') + '"'
        queries = []
        for table, names in columns.items():
            selected = ",".join(map(quote, names))
            literal = "'" + table.replace("'", "''") + "'"
            queries.append(f"""
                select {literal} as name,
                    coalesce(jsonb_agg(md5(row_to_json(x)::text) order by md5(row_to_json(x)::text)), '[]'::jsonb) as hashes
                from (select {selected} from public.{quote(table)}) x
                """)
        rows = json.loads(self.sql(database, "select jsonb_object_agg(name,hashes) from ("
                                  + " union all ".join(queries) + ") snapshot"))
        return columns, rows

    def login(self, username="admin", password=None):
        result = api(self.port, "/auth/login", {"username": username, "password": password or self.admin_password})
        return result["token"]

    def workbook(self, path, token):
        assert request(self.port, "/api" + path)[0] == 401
        status, headers, data = request(self.port, "/api" + path, token=token)
        if status != 200 or headers.get_content_type() != "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
            raise RuntimeError("Expected authorized Excel response")
        with ZipFile(io.BytesIO(data)) as archive:
            assert archive.testzip() is None
            return b"".join(archive.read(name) for name in archive.namelist() if name.startswith("xl/worksheets/"))

    def verify_attachment(self, attachment_id, token, expected):
        status, _, data = request(self.port, f"/api/bureau/cases/attachments/{attachment_id}/download", token=token)
        if status != 200 or hashlib.sha256(data).hexdigest() != expected:
            error = json.loads(data) if data.startswith(b"{") else {}
            raise RuntimeError(f"Attachment download failed: HTTP {status}, {error.get('code')}, {error.get('message')}")

    def verify_report_audit(self, token):
        audit = "/audit/logs?page=1&size=20&reportType="
        combined = api(self.port, audit + "ALL_REPORTS", token=token)
        assert combined["total"] == 3
        assert {row["report_type"] for row in combined["rows"]} == {"PENALTIES", "ENFORCEMENT_WORK", "INTERVIEWS"}
        for row in combined["rows"]:
            assert row["username"] == "admin" and row["request_id"] and row["report_row_count"] >= 1
            assert row["report_start"] <= row["report_end"] and row["report_cutoff"]
            exact = api(self.port, audit + row["report_type"] + "&requestId=" + row["request_id"], token=token)
            assert exact["total"] == 1 and exact["rows"][0]["id"] == row["id"]

    def verify_reporting_supplement(self, database, token):
        case_id = int(self.sql(database, "select id from case_file where case_no='DRILL-2026-1'"))
        source_query = f"""
            select md5(jsonb_build_object('case',to_jsonb(c),'decision',to_jsonb(d))::text)
            from case_file c join case_decision d on d.case_id=c.id where c.id={case_id}
            """
        original = self.sql(database, source_query)
        route = f"/bureau/cases/{case_id}/report-supplements"
        supplement = api(self.port, route, {
            "reason": "合成旧案报表补录演练", "sourceReference": "合成案卷资料第1页",
            "values": {
                "institutionCode": "DRILL-SUPPLEMENT", "institutionOwnership": "PUBLIC",
                "institutionGrade": "UNGRADED", "confirmedViolationStart": "2026-09-01",
                "confirmedViolationEnd": "2026-09-01", "confirmedViolationFacts": "合成核实事实",
                "confirmedViolationAmount": 0, "penaltyBasis": "合成报表依据",
            },
        }, token)
        reviewer_password = "Reviewer-" + secrets.token_hex(16)
        department = int(self.sql(database, "select id from sys_dept where code='DL_OFF_LEGAL'"))
        api(self.port, "/system/users", {
            "username": "drill_reviewer", "password": reviewer_password, "realName": "演练审核员",
            "deptId": department, "accountCategory": "BUREAU", "roleCodes": ["LEGAL"],
        }, token)
        reviewer = self.login("drill_reviewer", reviewer_password)
        changed_password = "Changed-" + secrets.token_hex(16)
        api(self.port, "/auth/change-password", {
            "oldPassword": reviewer_password, "newPassword": changed_password,
        }, reviewer)
        reviewer = self.login("drill_reviewer", changed_password)
        api(self.port, route + f"/{supplement['id']}/review", {
            "approve": True, "opinion": "合成资料核实通过，仅供报表",
        }, reviewer)
        state = api(self.port, route, token=token)
        assert not state["missingFields"]
        assert state["original"]["institutionCode"] is None
        assert state["effective"]["institutionCode"] == "DRILL-SUPPLEMENT"
        assert self.sql(database, source_query) == original
        quality = api(self.port, "/bureau/stats/penalties/missing-fields?year=2026&page=1&size=20", token=token)
        assert quality["total"] == 0
        assert b"DRILL-SUPPLEMENT" in self.workbook("/bureau/stats/penalties/download?year=2026", token)

    def verify_workflow_automation(self, database, token):
        today = time.strftime("%Y-%m-%d")
        department = api(self.port, "/system/depts", {
            "name": "演练经办部门", "code": "DRILL_AGENCY", "type": "ADMIN", "orgKind": "AGENCY",
        }, token)
        password = "Agency-" + secrets.token_hex(16)
        api(self.port, "/system/users", {
            "username": "drill_agency", "password": password, "realName": "演练经办员",
            "deptId": department["id"], "accountCategory": "AGENCY", "roleCodes": ["AGENCY"],
        }, token)
        agency = self.login("drill_agency", password)
        changed = "Changed-" + secrets.token_hex(16)
        api(self.port, "/auth/change-password", {"oldPassword": password, "newPassword": changed}, agency)
        agency = self.login("drill_agency", changed)
        case_id = int(self.sql(database, "select id from case_file where case_no='DRILL-2026-1'"))
        api(self.port, f"/bureau/cases/{case_id}/agreement-actions", {
            "action": "INTERVIEW", "agencyDeptId": str(department["id"]), "sentAt": today, "dueAt": today,
        }, token)
        inbox = api(self.port, "/agency/agreement-actions", token=agency)
        assert len(inbox) == 1
        agreement = inbox[0]["id"]
        task = api(self.port, "/agency/my/tasks", token=agency)
        assert task["total"] == 1 and task["rows"][0]["sourceId"] == agreement
        before = task["rows"][0]["taskKey"]
        api(self.port, "/bureau/messages/generate-reminders", {}, token)
        assert api(self.port, "/agency/agreement-actions", token=agency)[0]["autoRemindedAt"] == today
        base = f"/agency/agreement-actions/{agreement}"
        api(self.port, base + "/receive", {"cycle": 1}, agency)
        task = api(self.port, "/agency/my/tasks", token=agency)
        assert task["total"] == 1 and task["rows"][0]["taskKey"] != before
        details = {"interviewType": "经办约谈", "reason": "合成演练事由", "content": "合成实际约谈记录",
                   "participants": "演练甲、演练乙", "interviewDate": today}
        api(self.port, base + "/reply", {"cycle": 1, "result": "已落实", "resultType": "COMPLETED",
                                       "interview": details}, agency)
        assert api(self.port, "/agency/my/tasks", token=agency)["total"] == 0
        result = api(self.port, "/agency/agreement-actions", token=agency)[0]
        assert result["interviewId"]
        repeated = api(self.port, base + "/interview", {"cycle": 1, **details}, agency)
        assert repeated["id"] == result["interviewId"]
        assert self.sql(database, f"select count(*) from department_interview where source_agreement_id={agreement}") == "1"
        assert "合成实际约谈记录".encode() in self.workbook(
            "/bureau/stats/interviews/download?year=" + today[:4], token)

    def scale(self, database, token):
        count = self.args.scale_rows
        if not count:
            return {}
        initial_interviews = int(self.sql(database, """
            select count(*) from department_interview
            where interview_date >= date '2026-01-01' and interview_date < date '2027-01-01'
            """))
        self.sql(database, f"""
            insert into case_file (case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status)
            select 'DRILL-SCALE-'||n,'合成演练案件',(select min(id) from case_cause),
                   'DRILL-SCALE-'||n,'PROVIDER',date '2026-10-01',date '2026-12-31','DECIDED'
            from generate_series(1,{count}) n;
            insert into case_decision (case_id,decision_type,fine_amount,content,decided_at)
            select id,'PUNISH',100,'合成演练决定',date '2026-10-15'
            from case_file where case_no like 'DRILL-SCALE-%';
            insert into case_execution (case_id,kind,amount,paid_at,method,receipt_no)
            select id,'FINE',50,date '2026-10-15','BANK',case_no
            from case_file where case_no like 'DRILL-SCALE-%';
            insert into department_interview(dept_id,department_name,institution_code,institution_name,
                   interview_type,reason,content,participants,interview_date,created_by)
            select d.id,d.name,'DRILL-SCALE-'||n,'DRILL-SCALE-'||n,'演练约谈','合成事由','合成内容',
                   '演练员',date '2026-10-15',u.id
            from generate_series(1,{count}) n cross join sys_user u join sys_dept d on d.id=u.dept_id
            where u.username='admin';
            """)
        measurements = {}
        expected = set(range(1, count + 1))
        for label, path in {
            "penalties": "/bureau/stats/penalties/download?year=2026&cutoff=2026-10-15",
            "enforcement": "/bureau/stats/enforcement-work/download?start=2026-01-01&end=2026-10-15&reconcile=true",
            "interviews": "/bureau/stats/interviews/download?year=2026&cutoff=2026-10-15&reconcile=true",
        }.items():
            started = time.monotonic()
            xml = self.workbook(path, token)
            assert {int(item) for item in re.findall(rb"DRILL-SCALE-(\d+)", xml)} == expected, label
            measurements[label] = {"syntheticRows": count, "seconds": round(time.monotonic() - started, 2)}
        page_size = 50
        total = count + initial_interviews
        last_page = (total + page_size - 1) // page_size
        started = time.monotonic()
        first = api(self.port, f"/bureau/interviews/page?year=2026&page=1&size={page_size}", token=token)
        last = api(self.port, f"/bureau/interviews/page?year=2026&page={last_page}&size={page_size}", token=token)
        assert first["total"] == last["total"] == total
        assert len(first["rows"]) == min(total, page_size)
        assert len(last["rows"]) == total - (last_page - 1) * page_size
        measurements["interviewPages"] = {"total": total, "seconds": round(time.monotonic() - started, 2)}
        return measurements

    def run(self):
        old = self.create()
        new = self.create()
        baseline = self.folder / "baseline"
        restored = self.folder / "restored"
        baseline.mkdir()
        restored.mkdir()
        self.start(self.args.previous, old, baseline, "baseline-v34")
        assert self.sql(old, "select max(installed_rank) from flyway_schema_history where success") == "34"
        self.sql(old, """
            update sys_user set must_change_password=false, account_category='BUREAU',
                dept_id=(select id from sys_dept order by id limit 1) where username='admin';
            insert into case_file (case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status)
                values ('DRILL-2026-1','发布演练案件',(select min(id) from case_cause),
                        '发布演练机构','PROVIDER',date '2026-10-01',date '2026-12-31','DECIDED');
            insert into case_decision (case_id,decision_type,fine_amount,content,decided_at)
                select id,'PUNISH',100,'合成演练决定',date '2026-10-15' from case_file where case_no='DRILL-2026-1';
            """)
        relative_attachment = "data/attachments/release-fixture.bin"
        attachment = baseline / relative_attachment
        attachment.parent.mkdir(parents=True, exist_ok=True)
        attachment.write_bytes(secrets.token_bytes(256))
        attachment_id = int(self.sql(old, f"""
            with attachment as (
                insert into case_attachment (case_id,filename,content_type,size_bytes,file_path,uploaded_by)
                select id,'release-fixture.bin','application/octet-stream',256,'{relative_attachment}','admin'
                from case_file where case_no='DRILL-2026-1' returning id
            ) select id from attachment
            """))
        expected_attachment = digest(attachment)
        token = self.login()
        self.verify_attachment(attachment_id, token, expected_attachment)
        assert "发布演练机构".encode() in self.workbook("/bureau/stats/penalties/download", token)
        self.stop()
        backup = self.folder / "v34.dump"
        with backup.open("wb") as output:
            self.pg("pg_dump", "-Fc", "--no-owner", "--no-acl", "-d", old, stdout=output)
        self.restore(new, backup)
        columns, before = self.snapshot(old)
        assert self.snapshot(new, columns)[1] == before
        files = self.folder / "attachments.tar"
        with tarfile.open(files, "w") as archive:
            archive.add(attachment, arcname=relative_attachment)
        with tarfile.open(files) as archive:
            item = archive.getmember(relative_attachment)
            target = restored / item.name
            target.parent.mkdir(parents=True)
            target.write_bytes(archive.extractfile(item).read())
        assert digest(attachment) == digest(target)

        self.start(self.args.candidate, new, restored, "candidate")
        version = int(self.sql(new, "select max(version::integer) from flyway_schema_history where success"))
        assert version >= 42
        after = self.snapshot(new, columns)[1]
        for table in before:
            if table != "flyway_schema_history":
                assert not Counter(before[table]) - Counter(after[table]), table
        token = self.login()
        self.verify_attachment(attachment_id, token, expected_attachment)
        for route in ("/bureau/stats/penalties/download",
                      "/bureau/stats/enforcement-work/download?start=2026-01-01&end=2026-10-15"):
            assert "发布演练机构".encode() in self.workbook(route, token), route
        api(self.port, "/bureau/interviews", {
            "institutionCode": "DRILL-001", "institutionName": "发布演练约谈",
            "interviewType": "工作约谈", "reason": "合成演练", "content": "合成记录",
            "participants": "演练员", "interviewDate": "2026-10-15",
        }, token)
        assert "发布演练约谈".encode() in self.workbook("/bureau/stats/interviews/download?year=2026", token)
        if version >= 44:
            quality = api(self.port, "/bureau/stats/penalties/missing-fields?year=2026&page=1&size=20", token=token)
            assert quality["total"] == 1 and quality["rows"][0]["caseNo"] == "DRILL-2026-1"
            assert quality["rows"][0]["missingFields"]
            self.verify_report_audit(token)
        if version >= 45:
            self.verify_reporting_supplement(new, token)
        if version >= 46:
            self.verify_workflow_automation(new, token)
        measurements = self.scale(new, token)
        self.stop()

        self.pg("dropdb", new)
        self.names.remove(new)
        rollback = self.create()
        self.restore(rollback, backup)
        assert self.snapshot(rollback, columns)[1] == before
        with tarfile.open(files) as archive:
            target.write_bytes(archive.extractfile(relative_attachment).read())
        self.start(self.args.previous, rollback, restored, "rollback-v34")
        token = self.login()
        assert api(self.port, "/auth/me", token=token)["username"] == "admin"
        self.verify_attachment(attachment_id, token, expected_attachment)
        assert self.sql(rollback, "select max(version::integer) from flyway_schema_history where success") == "34"
        assert self.sql(rollback, "select count(*) from case_file where case_no='DRILL-2026-1'") == "1"
        assert digest(attachment) == digest(target)
        return {"previousSchema": 34, "candidateSchema": version, "tablesRestored": len(before),
                "attachmentVerified": True, "threeReportsWithData": True, "rollbackBinaryHealthy": True,
                "reportQualityAndAuditVerified": version >= 44, "historicalSupplementVerified": version >= 45,
                "workflowTasksAndInterviewVerified": version >= 46, "javaHeapMiB": 512, "scale": measurements}

    def cleanup(self):
        self.stop()
        for database in reversed(self.names):
            assert database.startswith("ybcase_release_") and len(database) == len("ybcase_release_") + 12
            self.pg("dropdb", database)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--db-user", default="hip")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--wsl-distro", default="Ubuntu")
    parser.add_argument("--temp-root", type=Path)
    parser.add_argument("--scale-rows", type=int, default=0)
    args = parser.parse_args()
    if not __debug__:
        parser.error("do not disable rehearsal assertions")
    if not 0 <= args.scale_rows <= 10000:
        parser.error("--scale-rows must be between 0 and 10000")
    args.previous, args.candidate = args.previous.resolve(), args.candidate.resolve()
    if digest(args.previous) != RELEASE_V34:
        raise ValueError("Previous JAR is not the verified V34 release")
    with tempfile.TemporaryDirectory(prefix="ybcase-upgrade-", dir=args.temp_root) as folder:
        drill = Drill(Path(folder), args)
        try:
            result = drill.run()
        except BaseException:
            for log in sorted(drill.folder.glob("*.log")):
                print(log.name)
                print(log.read_text(encoding="utf-8", errors="replace")[-2500:])
            raise
        finally:
            drill.cleanup()
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
