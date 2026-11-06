import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("init_bureau", pathlib.Path(__file__).parents[1] / "init-bureau.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class InitBureauTest(unittest.TestCase):
    def load_csv(self, data):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "certs.csv"
            path.write_text(data, encoding="utf-8-sig")
            return MODULE.read_enforcers(path)

    def test_validates_entire_file_and_retains_optional_header_and_login(self):
        rows = self.load_csv("姓名,证号,部门,有效期,资格,登录账号\n甲,0001,基金监管一处,2028-01-02,1,user_a\n")
        self.assertEqual(rows[0][0:2], (2, "user_a"))
        self.assertEqual(rows[0][2]["certNo"], "0001")
        self.assertTrue(rows[0][2]["legalQualified"])
        for data in (
            "甲,0001,一处,2028-01-02,1\n乙,0001,一处,2028-01-03,0",
            "甲,0001,一处,2028-13-02,1",
            "甲,0001,一处,2028-01-02,yes",
            "甲,0001,一处",
        ):
            with self.assertRaises(ValueError):
                self.load_csv(data)

    def test_mapping_resolves_accounts_across_pages_without_reactivating_certificate(self):
        records = self.load_csv("甲,0001,,2028-01-02,1,user_a\n")
        pages = []
        def api(method, path, **kwargs):
            self.assertEqual(method, "GET")
            if path == "/bureau/enforcers":
                return [{"cert_no": "0001", "enabled": False, "user_id": 17}]
            if path == "/system/depts":
                return [{"id": 31, "name": "基金监管一处", "enabled": True}]
            page = kwargs["params"]["page"]
            pages.append(page)
            if page == 0:
                return {"total": 101, "records": [{"username": "unrelated"}]}
            return {"total": 101, "records": [{"id": 17, "username": "user_a", "realName": "甲",
                     "deptId": 31, "enabled": True, "roleCodes": ["HANDLER"]}]}
        payload = MODULE.prepare_enforcers(records, api)[0][1]
        self.assertEqual(pages, [0, 1])
        self.assertEqual((payload["userId"], payload["dept"], payload["enabled"]), (17, "基金监管一处", False))

    def test_different_certificate_holder_and_department_are_rejected(self):
        def api(_, path, **kwargs):
            return {
                "/bureau/enforcers": [{"cert_no": "0001", "enabled": True, "user_id": 18}],
                "/system/depts": [{"id": 31, "name": "基金监管一处", "enabled": True}],
                "/system/users": {"total": 1, "records": [{"id": 17, "username": "user_a", "realName": "甲",
                                   "deptId": 31, "enabled": True, "roleCodes": ["HANDLER"]}]},
            }[path]
        for department in ("基金监管一处", "其他部门"):
            with self.assertRaises(ValueError):
                MODULE.prepare_enforcers(self.load_csv(f"甲,0001,{department},2028-01-02,1,user_a"), api)

    def test_legacy_records_preserve_existing_bindings(self):
        records = self.load_csv("甲,0001,基金监管一处,2028-01-02,1")
        def api(method, path):
            self.assertEqual((method, path), ("GET", "/bureau/enforcers"))
            return [{"cert_no": "0001", "enabled": True, "user_id": 17}]
        self.assertNotIn("userId", MODULE.prepare_enforcers(records, api)[0][1])

    def test_dry_run_authenticates_and_skips_all_business_writes(self):
        calls = []

        class Response:
            status_code = 200

            def __init__(self, data):
                self.data = data

            def json(self):
                return {"code": 0, "data": self.data}

        class Session:
            headers = {}

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def request(self, method, url, **kwargs):
                calls.append((method, url.rsplit("/api", 1)[1]))
                if url.endswith("/auth/login"):
                    return Response({"token": "fixture", "mustChangePassword": False})
                if url.endswith("/auth/me"):
                    return Response({"roles": ["ADMIN"]})
                raise AssertionError("Dry run attempted a business write")

        with patch.object(MODULE.requests, "Session", Session), \
                patch.dict(MODULE.os.environ, {"YBCASE_ADMIN_PASSWORD": "fixture"}), \
                patch.object(MODULE.sys, "argv", ["init-bureau.py", "--org", "测试局", "--dry-run"]):
            self.assertEqual(MODULE.main(), 0)
        self.assertEqual(calls, [("POST", "/auth/login"), ("GET", "/auth/me")])

    def test_only_loopback_can_use_unencrypted_http(self):
        self.assertEqual(MODULE.api_base("http://localhost:8090/api/"), "http://localhost:8090/api")
        self.assertEqual(MODULE.api_base("https://example.test/api"), "https://example.test/api")
        with self.assertRaises(ValueError):
            MODULE.api_base("http://example.test/api")


if __name__ == "__main__":
    unittest.main()
