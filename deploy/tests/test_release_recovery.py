"""Offline release recovery tests. Docker, SQL and SSH are never executed."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from contextlib import ExitStack
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release", Path(__file__).parents[1] / "release.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.BASELINE_SCHEMA = 46
m.TARGET_SCHEMA = 50


class MissingApplicationRecovery(unittest.TestCase):
    def setUp(self):
        temporary_root = Path.home() / "Temp"
        temporary_root.mkdir(exist_ok=True)
        self.assertNotIn("onedrive", str(temporary_root.resolve()).lower())
        self.temporary = tempfile.TemporaryDirectory(dir=temporary_root, prefix="ybcase-release-rollback-")
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.release = self.home / "release"
        for folder in ("frozen", "rollback"):
            (self.release / folder).mkdir(parents=True)
        (self.home / "dist").mkdir()
        self.old_jar = b"reviewed old jar"
        self.old_index = b"reviewed old index"
        self.environment = b"existing environment remains private and unchanged"
        (self.home / ".env").write_bytes(self.environment)
        (self.home / "dist/index.html").write_bytes(b"new index before failed recovery")
        (self.release / "rollback/index.html").write_bytes(self.old_index)
        for name in m.FIXED_FILES:
            (self.release / "rollback" / name).write_bytes(self.old_jar if name == "ybcase-server.jar" else ("old " + name).encode())
            (self.home / name).write_bytes(("new " + name).encode())
        for name in ("db.dump", "attachments.tar"):
            (self.release / "frozen" / name).write_bytes(("frozen " + name).encode())
        checksums = {name: m.digest(self.release / "frozen" / name) for name in ("db.dump", "attachments.tar")}
        self.write("frozen/hashes.json", checksums)
        self.write("frozen/baseline.json", {"tables": {}})
        self.accounts = {"users": {"1": "one-way-account-digest"}, "roles": ["role-digest"]}
        self.write("frozen/accounts.json", self.accounts)
        self.attachments = {"attachments": {"directory": True}, "attachments/a": {"size": 1, "sha256": "a" * 64}}
        self.write("frozen/attachments.tar.manifest.json", self.attachments)
        self.volume = {"Name": "ybcase_attachments", "Driver": "local", "Scope": "local",
                       "Mountpoint": "/var/lib/docker/volumes/ybcase_attachments/_data",
                       "CreatedAt": "2026-01-01T00:00:00Z", "Labels": {"com.docker.compose.project": "ybcase"}, "Options": None}
        self.live_volume = copy.deepcopy(self.volume)
        self.record = {"attachmentVolume": self.volume, "hashes": {".env": m.digest(self.home / ".env")},
                       "containers": {"ybcase-app-1": "deleted-old-app", "ybcase-db-1": "original-db",
                                      "ybcase-caddy-1": "original-caddy"},
                       "caddyPause": {"status": "PAUSED", "fingerprint": {"id": "original-caddy",
                           "startedAt": "same-start", "restartCount": 0}}}
        self.recreated = False
        self.commands, self.inspected = [], []
        globals_patch = patch.multiple(m, HOME=self.home, RELEASE=self.release,
                                       OLD_JAR=hashlib.sha256(self.old_jar).hexdigest())
        globals_patch.start()
        self.addCleanup(globals_patch.stop)

    def write(self, name, value):
        (self.release / name).write_text(json.dumps(value), encoding="utf-8")

    def command(self, *args, **kwargs):
        self.commands.append(args)
        if args[:3] == ("docker", "volume", "inspect"):
            self.assertEqual("ybcase_attachments", args[3])
            return subprocess.CompletedProcess(args, 0, json.dumps([self.live_volume]).encode(), b"")
        self.assertEqual(("docker", "run", "--rm", "--network", "none"), args[:5])
        self.assertNotIn("--volumes-from", args)
        self.assertEqual("type=volume,source=ybcase_attachments,target=/app/data/attachments", args[args.index("--mount") + 1])
        self.assertIn("stdin", kwargs)
        return subprocess.CompletedProcess(args, 0, b"", b"")

    def inspect(self, name):
        self.inspected.append(name)
        if name == "ybcase-db-1" or name == "original-db":
            return {"Id": "original-db", "Image": "original-db-image"}
        if name in ("ybcase-caddy-1", "original-caddy"):
            return {"Id": "original-caddy", "RestartCount": 0,
                    "State": {"StartedAt": "same-start", "Paused": True, "Running": True}}
        if name == "ybcase-app-1":
            self.assertTrue(self.recreated, "Deleted application was inspected before old app recreation")
            return {"Id": "restored-old-app"}
        self.fail("Unknown container, including deleted original app, must never be inspected: " + name)

    def compose(self, *args):
        if args == ("up", "-d", "--no-deps", "--force-recreate", "app"):
            self.assertEqual(self.old_jar, (self.home / "ybcase-server.jar").read_bytes())
            self.assertEqual(self.old_index, (self.home / "dist/index.html").read_bytes())
            self.recreated = True
        else:
            self.assertEqual(("stop", "-t", "30", "app"), args)

    def test_missing_deleted_app_restores_exact_volume_and_recreates_old_application(self):
        with patch.object(m, "run", side_effect=self.command), patch.object(m, "inspect", side_effect=self.inspect), \
             patch.object(m, "compose", side_effect=self.compose) as compose, patch.object(m, "restore_frozen") as restore, \
             patch.object(m, "healthy") as healthy, patch.object(m, "schema", return_value=46), \
             patch.object(m, "audit", return_value={"tables": {}}), patch.object(m, "accounts", return_value=self.accounts), \
             patch.object(m, "capture_attachments", return_value=self.attachments):
            m.rollback(self.record)
        self.assertTrue(self.recreated)
        restore.assert_called_once_with()
        healthy.assert_called_once_with("restored-old-app")
        self.assertEqual(2, compose.call_count)
        self.assertEqual(self.environment, (self.home / ".env").read_bytes())
        self.assertTrue((self.release / "rollback.complete").is_file())
        self.assertNotIn("deleted-old-app", self.inspected)

    def test_recreated_or_retargeted_volume_is_refused_before_any_destructive_action(self):
        for field, changed in (("CreatedAt", "2026-11-08T00:00:00Z"), ("Mountpoint", "/other/volume"),
                               ("Driver", "different-driver"), ("Labels", {"com.docker.compose.project": "other"})):
            with self.subTest(field=field):
                self.live_volume = copy.deepcopy(self.volume)
                self.live_volume[field] = changed
                with patch.object(m, "run", side_effect=self.command), patch.object(m, "compose") as compose, \
                     patch.object(m, "restore_frozen") as restore:
                    with self.assertRaisesRegex(RuntimeError, "volume identity changed"):
                        m.rollback(self.record)
                    compose.assert_not_called()
                    restore.assert_not_called()
        self.assertEqual(self.environment, (self.home / ".env").read_bytes())

    def test_unrecorded_or_unsafe_volume_name_cannot_start_restore(self):
        for record in ({}, {"attachmentVolume": {"Name": "../../ybcase"}},
                       {"attachmentVolume": {"Name": "safe,target=/other"}}):
            candidate = {**self.record, **record}
            if not record:
                candidate.pop("attachmentVolume")
            with self.subTest(record=record), patch.object(m, "run") as run, \
                 patch.object(m, "compose") as compose, patch.object(m, "restore_frozen") as restore:
                with self.assertRaises(RuntimeError):
                    m.rollback(candidate)
                run.assert_not_called()
                compose.assert_not_called()
                restore.assert_not_called()

    def test_released_or_ambiguous_traffic_never_allows_database_restore(self):
        for status in ("RELEASED", "UNPAUSE_INTENT", "PAUSE_INTENT"):
            self.record["caddyPause"]["status"] = status
            with patch.object(m, "run", side_effect=self.command), patch.object(m, "compose") as compose, \
                 patch.object(m, "restore_frozen") as restore:
                with self.assertRaisesRegex(RuntimeError, "verified owned traffic hold"):
                    m.rollback(self.record)
                compose.assert_not_called()
                restore.assert_not_called()


class ActivationTrafficSequence(unittest.TestCase):
    def fixture(self, recovery=None):
        temporary = tempfile.TemporaryDirectory(dir=Path.home() / "Temp", prefix="ybcase-traffic-sequence-")
        self.addCleanup(temporary.cleanup)
        home = Path(temporary.name); release = home / "release"
        (home / "dist").mkdir(); (release / "staged/dist/assets").mkdir(parents=True)
        (release / "rollback").mkdir()
        files = {name: ("old " + name).encode() for name in m.FIXED_FILES | {"dist/index.html", ".env"}}
        manifest = {}
        for name, content in files.items():
            (home / name).write_bytes(content)
            if name != ".env":
                target = release / "staged" / name
                target.parent.mkdir(exist_ok=True, parents=True)
                target.write_bytes(content if name == "Caddyfile" else ("new " + name).encode())
                manifest[name] = m.digest(target)
        (release / "rollback/ybcase-server.jar").write_bytes(files["ybcase-server.jar"])
        (release / "rollback/index.html").write_bytes(files["dist/index.html"])
        (release / "staged/dist/assets/new.js").write_bytes(b"new resource")
        manifest["dist/assets/new.js"] = m.digest(release / "staged/dist/assets/new.js")
        accounts = {"users": {}, "roles": []}
        (release / "accounts-before.json").write_text(json.dumps(accounts))
        record = {"containers": {"ybcase-app-1": "old-app", "ybcase-db-1": "db", "ybcase-caddy-1": "caddy"},
                  "hashes": {name: m.digest(home / name) for name in files}, "clone": {}, "pilot": {"id": "pilot"}}
        observations = {"paused": False, "appRunning": True, "new": False, "schema": 46, "events": []}
        events = observations["events"]
        def details(name):
            if name in ("ybcase-caddy-1", "caddy"):
                return {"Id": "caddy", "RestartCount": 0,
                        "State": {"Running": True, "Paused": observations["paused"], "StartedAt": "same-start"}}
            if name == "ybcase-db-1": return {"Id": "db"}
            if name == "ybcase-app-1" or name in ("old-app", "new-app"):
                return {"Id": "new-app" if observations["new"] else "old-app", "State": {"Running": observations["appRunning"]}}
            self.fail("Unexpected inspect: " + name)
        def command(*args, **kwargs):
            if args == ("docker", "stop", "-t", "30", "pilot"):
                events.append("stop_pilot")
            elif args == ("docker", "pause", "caddy"):
                self.assertFalse(observations["paused"]); observations["paused"] = True; events.append("pause")
            elif args == ("docker", "unpause", "caddy"):
                self.assertTrue(observations["paused"])
                self.assertTrue((release / "activate.complete").exists() or (release / "rollback.complete").exists())
                observations["paused"] = False; events.append("unpause")
            else: self.fail("Unexpected subprocess: " + str(args))
            return subprocess.CompletedProcess(args, 0, b"", b"")
        def compose(*args):
            self.assertTrue(observations["paused"], "Public traffic must stop before the application")
            if args == ("stop", "-t", "30", "app"):
                observations["appRunning"] = False; events.append("stop_app")
            elif args == ("up", "-d", "--no-deps", "--force-recreate", "app"):
                observations.update(new=True, appRunning=True, schema=50); events.append("start_new")
            else: self.fail("Unexpected compose: " + str(args))
        def healthy(identifier):
            self.assertTrue(observations["paused"])
            events.append("healthy_new" if observations["new"] else "healthy_old")
            if observations["new"] and recovery: raise RuntimeError("fixture startup failure")
        def audit(database, output, baseline=None):
            self.assertTrue(observations["paused"])
            events.append("audit_" + output)
            value = {"tables": {}}
            m.save(output, value)
            return value
        def backup(folder):
            self.assertTrue(observations["paused"])
            (release / folder / "db.dump").write_bytes(b"frozen dump")
        def attachments(identifier, output):
            self.assertTrue(observations["paused"], "Attachment verification must finish before unpause")
            events.append("attachments_" + output)
            (release / output).write_bytes(b"frozen attachments")
            m.save(output + ".manifest.json", {"attachments": {"directory": True}})
            return {"attachments": {"directory": True}}
        def rollback(value):
            self.assertTrue(observations["paused"]); events.append("rollback")
            if recovery == "FAILED": raise RuntimeError("fixture recovery failure")
            (home / "ybcase-server.jar").write_bytes(files["ybcase-server.jar"])
            (home / "dist/index.html").write_bytes(files["dist/index.html"])
            observations.update(new=False, appRunning=True, schema=46)
            m.mark("rollback.complete")
        stack = ExitStack(); self.addCleanup(stack.close)
        stack.enter_context(patch.multiple(m, HOME=home, RELEASE=release,
            OLD_JAR=m.digest(home / "ybcase-server.jar"), OLD_INDEX=m.digest(home / "dist/index.html")))
        for name, replacement in {"state": lambda: record, "load_manifest": lambda *_: manifest,
                "require_gates": lambda *_: None, "require_clone": lambda *_: None,
                "require_pilot": lambda *_: None, "require_attachment_volume": lambda *_: {},
                "validate_compose": lambda: None, "schema": lambda *_: observations["schema"],
                "inspect": details, "run": command, "compose": compose, "healthy": healthy,
                "accounts": lambda *_: accounts, "audit": audit, "backup_dump": backup,
                "capture_attachments": attachments, "rollback": rollback}.items():
            stack.enter_context(patch.object(m, name, replacement))
        return record, observations, release

    def test_success_pauses_before_stop_and_only_releases_after_all_verification(self):
        record, seen, release = self.fixture()
        # Old prepare states have no claim. An externally paused Caddy must never be adopted.
        seen["paused"] = True
        with self.assertRaisesRegex(RuntimeError, "running and unpaused"):
            m.pause_caddy(record)
        self.assertNotIn("caddyPause", record)
        seen["paused"] = False
        result = m.activate()
        self.assertTrue(result["trafficResumedAfterVerification"])
        events = seen["events"]
        self.assertLess(events.index("pause"), events.index("stop_app"))
        self.assertLess(events.index("attachments_activated-attachments.tar"), events.index("unpause"))
        self.assertTrue((release / "activate.complete").exists())
        self.assertFalse(seen["paused"])
        self.assertEqual("RELEASED", record["caddyPause"]["status"])
        before = list(events)
        with self.assertRaisesRegex(RuntimeError, "already attempted"):
            m.activate()
        self.assertEqual(before, events, "A phase retry must not toggle traffic again")
        # A command may succeed and its post-command response may fail. Even then,
        # never restore the frozen database after clients may already have written.
        record, seen, release = self.fixture()
        original_command = m.run
        def fail_after_resume(*args, **kwargs):
            result = original_command(*args, **kwargs)
            if args == ("docker", "unpause", "caddy"):
                raise RuntimeError("fixture response lost after successful unpause")
            return result
        with patch.object(m, "run", fail_after_resume), self.assertRaisesRegex(RuntimeError, "Never restore"):
            m.activate()
        self.assertTrue((release / "activate.complete").exists())
        self.assertFalse(seen["paused"])
        self.assertEqual(50, seen["schema"])
        self.assertNotIn("rollback", seen["events"])
        self.assertEqual("UNCONFIRMED", record["trafficRecovery"]["trafficState"])

    def test_rollback_releases_only_after_recovery_and_failed_recovery_keeps_traffic_paused(self):
        for recovery in ("SUCCESS", "FAILED"):
            with self.subTest(recovery=recovery):
                record, seen, release = self.fixture(recovery)
                with self.assertRaises(RuntimeError):
                    m.activate()
                self.assertLess(seen["events"].index("pause"), seen["events"].index("stop_app"))
                self.assertIn("rollback", seen["events"])
                if recovery == "SUCCESS":
                    self.assertLess(seen["events"].index("rollback"), seen["events"].index("unpause"))
                    self.assertFalse(seen["paused"])
                    self.assertTrue((release / "rollback.complete").exists())
                    self.assertEqual("OLD_RELEASE_VERIFIED", record["caddyPause"]["releaseReason"])
                else:
                    self.assertNotIn("unpause", seen["events"])
                    self.assertTrue(seen["paused"])
                    self.assertEqual("PAUSED", record["caddyPause"]["status"])
                    self.assertEqual("MANUAL_RECOVERY_REQUIRED", record["trafficRecovery"]["status"])
                    self.assertEqual("MANUAL_RECOVERY_REQUIRED", m.json_file(release / "state.json")["trafficRecovery"]["status"])

    def test_lost_unpause_response_can_be_reconciled_without_any_restore(self):
        record, seen, release = self.fixture()
        original = m.run
        def lost_response(*args, **kwargs):
            result = original(*args, **kwargs)
            if args == ("docker", "unpause", "caddy"):
                raise RuntimeError("response lost")
            return result
        with patch.object(m, "run", lost_response), self.assertRaises(RuntimeError):
            m.activate()
        self.assertEqual("UNPAUSE_INTENT", record["caddyPause"]["status"])
        with patch.object(m, "healthy"), patch.object(m, "restore_frozen") as restore:
            outcome = m.resume()
        self.assertFalse(outcome["databaseRestored"])
        self.assertEqual("RELEASED", record["caddyPause"]["status"])
        self.assertNotIn("rollback", seen["events"])
        restore.assert_not_called()


class ArtifactAndEvidenceGates(unittest.TestCase):
    def setUp(self):
        root = Path.home() / "Temp"; root.mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=root, prefix="ybcase-release-gates-")
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        patcher = patch.multiple(m, RELEASE=self.folder, CONFIG_SHA256="e" * 64)
        patcher.start(); self.addCleanup(patcher.stop)
        (self.folder / "prepare.complete").touch()
        self.write("manifest.json", {"candidate": True})
        self.manifest = {"ybcase-server.jar": "a" * 64, "dist/index.html": "b" * 64}
        self.record = {"prepared": True, "configSha256": "e" * 64,
                       "manifestSha256": m.digest(self.folder / "manifest.json"), "pilot": {"id": "owned-pilot"}}
        self.gates = {
            "tests.passed": {"passed": True, "backendTests": 900, "frontendTests": 500, "buildPassed": True},
            "pilot.passed": {"passed": True, "pilotId": "owned-pilot", "checksPassed": 20},
            "git.json": {"pushed": True, "commit": "c" * 40},
        }
        for name, value in self.gates.items():
            value.update(jarSha256="a" * 64, indexSha256="b" * 64)
            self.write(name, value)

    def write(self, name, value):
        (self.folder / name).write_text(json.dumps(value), encoding="utf-8")

    def test_only_same_artifacts_with_complete_regression_pilot_and_push_pass(self):
        m.require_gates(self.record, self.manifest)
        for filename, field, bad in (("tests.passed", "jarSha256", "f" * 64),
                ("tests.passed", "backendTests", 0), ("tests.passed", "buildPassed", False),
                ("pilot.passed", "pilotId", "another-pilot"), ("pilot.passed", "checksPassed", 0),
                ("git.json", "pushed", False)):
            with self.subTest(filename=filename, field=field):
                self.write(filename, {**self.gates[filename], field: bad})
                with self.assertRaises(RuntimeError):
                    m.require_gates(self.record, self.manifest)
                self.write(filename, self.gates[filename])

    def test_configuration_and_manifest_cannot_change_after_prepare(self):
        with self.assertRaisesRegex(RuntimeError, "configuration changed"):
            m.require_gates({**self.record, "configSha256": "f" * 64}, self.manifest)
        self.write("manifest.json", {"changed": True})
        with self.assertRaisesRegex(RuntimeError, "Manifest changed"):
            m.require_gates(self.record, self.manifest)


if __name__ == "__main__":
    unittest.main()
