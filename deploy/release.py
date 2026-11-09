#!/usr/bin/env python3
"""Versioned staged release with reviewed artifacts, isolated rehearsal and guarded cutover.

Gates tests.passed/pilot.passed are JSON {passed:true,jarSha256,indexSha256}.
pilot.passed also pins pilotId. git.json pins commit, jarSha256, indexSha256
and pushed:true. All backups and diagnostics stay inside the private release.
"""
import argparse
import collections
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shutil
import subprocess
import tarfile
import tempfile
import time
import traceback
import urllib.request

HOME = Path("/opt/ybcase")
RELEASE = HOME / "releases/unconfigured"
AUDIT = Path(__file__).with_name("data-audit.py")
DATABASE = "ybcase"
CONTAINERS = ("ybcase-app-1", "ybcase-db-1", "ybcase-caddy-1")
BASELINE_SCHEMA = 50
TARGET_SCHEMA = 59
OLD_JAR = ""
OLD_INDEX = ""
CONFIG_SHA256 = ""
FIXED_FILES = {"ybcase-server.jar", "docker-compose.yml", "Caddyfile",
               "backup.sh", "backup-set.py", "upgrade.sh", "healthcheck.sh", "release.py", "data-audit.py",
               "backup-transfer.py", "restore-rehearsal.py", "capacity-check.py"}
NEW_TOOL_FILES = {"backup-set.py", "release.py", "data-audit.py", "backup-transfer.py", "restore-rehearsal.py", "capacity-check.py"}
SEED_TABLES = {"flyway_schema_history", "sys_dept", "sys_menu",
               "sys_role", "sys_role_menu", "sys_config"}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(*args, **kwargs):
    # Captured command output can contain operational data. Persist failures privately.
    result = subprocess.run(list(map(str, args)), stdout=kwargs.pop("stdout", subprocess.PIPE),
                            stderr=kwargs.pop("stderr", subprocess.PIPE), **kwargs)
    if result.returncode:
        with (RELEASE / "command-errors.log").open("ab") as output:
            for value in (result.stdout, result.stderr):
                if value:
                    output.write(value.encode() if isinstance(value, str) else value)
        raise RuntimeError("Command failed. See restricted command-errors.log")
    return result


def compose(*args, **kwargs):
    return run("docker", "compose", *args, cwd=HOME, **kwargs)


def db(*args, **kwargs):
    return compose("exec", "-T", "db", *args, **kwargs)


def sql(database, statement):
    return db("psql", "-XqAt", "-v", "ON_ERROR_STOP=1", "-U", "hip", "-d", database,
              input=statement.encode()).stdout.decode().strip()


def inspect(name):
    return json.loads(run("docker", "inspect", name).stdout)[0]


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            result.update(block)
    return result.hexdigest()


def json_file(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save(name, value):
    target = RELEASE / name
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=target.parent,
                                     prefix=".release-", delete=False) as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
        temporary = Path(stream.name)
    os.chmod(temporary, 0o600)
    temporary.replace(target)


def state():
    value = json_file(RELEASE / "state.json")
    require(value["release"] == str(RELEASE) and value["database"] == DATABASE, "Release state mismatch")
    require(value.get("configSha256") == CONFIG_SHA256, "Release configuration changed")
    return value


def mark(name):
    (RELEASE / name).touch(mode=0o600, exist_ok=False)


def schema(database=DATABASE):
    return int(sql(database, "select max(version::integer) from flyway_schema_history where success"))


def audit(database, output, baseline=None):
    args = ["python3", AUDIT, database, RELEASE / output]
    if baseline:
        args.append(RELEASE / baseline)
    run(*args)
    return json_file(RELEASE / output)


def accounts(database=DATABASE):
    # The audit file contains one-way digests, never usernames or password hashes.
    return json.loads(sql(database, """
        select jsonb_build_object(
          'users', (select coalesce(jsonb_object_agg(id, digest), '{}'::jsonb) from (
            select id, md5((to_jsonb(u)-'row_version')::text) digest from sys_user u) x),
          'roles', (select coalesce(jsonb_agg(digest order by digest), '[]'::jsonb) from (
            select md5(row_to_json(r)::text) digest from sys_user_role r) y))
        """))


def assert_rows(result, baseline_name, allow_logins=False):
    baseline = json_file(RELEASE / baseline_name)
    for table, before in baseline["tables"].items():
        after = result["tables"][table]
        if table == "sys_user" and allow_logins:
            continue
        lost = collections.Counter(before["hashes"]) - collections.Counter(after["hashes"])
        require(not lost, "An original row changed in " + table)
        additions_allowed = table in SEED_TABLES or allow_logins and table == "sys_audit_log"
        if not additions_allowed:
            require(before == after, "Unexpected new rows in " + table)


def validate_member(item):
    path = PurePosixPath(item.name)
    require(not path.is_absolute() and ".." not in path.parts and path.parts
            and path.parts[0] == "attachments", "Unsafe attachment archive path")
    require(item.isdir() or item.isfile(), "Attachment volume contains unsupported links or devices")
    return path


def attachment_manifest(path):
    result = {}
    with tarfile.open(path) as archive:
        for item in archive:
            normalized = str(validate_member(item))
            require(normalized not in result, "Duplicate attachment archive entry")
            if item.isdir():
                result[normalized] = {"directory": True}
            else:
                checksum = hashlib.sha256()
                with archive.extractfile(item) as stream:
                    for block in iter(lambda: stream.read(65536), b""):
                        checksum.update(block)
                result[normalized] = {"size": item.size, "sha256": checksum.hexdigest()}
    require("attachments" in result, "Missing attachments root")
    return result


def capture_attachments(container_id, output):
    # docker cp works on stopped containers and preserves the complete attached directory.
    with (RELEASE / output).open("xb") as stream:
        run("docker", "cp", container_id + ":/app/data/attachments", "-", stdout=stream)
    manifest = attachment_manifest(RELEASE / output)
    save(output + ".manifest.json", manifest)
    return manifest


def extract_attachments(path, parent):
    with tarfile.open(path) as archive:
        members = archive.getmembers()
        for item in members:
            validate_member(item)
        archive.extractall(parent, members=members, filter="data")


def backup_dump(folder):
    target = RELEASE / folder / "db.dump"
    with target.open("xb") as stream:
        db("pg_dump", "-U", "hip", "-Fc", "--no-owner", "--no-acl", DATABASE, stdout=stream)
    require(target.stat().st_size > 10240, "Database dump unexpectedly small")
    with target.open("rb") as stream:
        db("pg_restore", "--list", stdin=stream)


def load_manifest(staged=None):
    manifest = json_file(RELEASE / "manifest.json")
    require(isinstance(manifest, dict) and FIXED_FILES | {"dist/index.html"} <= manifest.keys(),
            "Incomplete manifest")
    require(any(name.startswith("dist/assets/") for name in manifest), "Missing frontend assets")
    for name, checksum in manifest.items():
        path = PurePosixPath(name)
        require(isinstance(name, str) and str(path) == name and not path.is_absolute()
                and ".." not in path.parts and not any(part.startswith(".") for part in path.parts),
                "Invalid manifest path")
        require(name in FIXED_FILES or name.startswith("dist/"), "Manifest contains a forbidden file")
        require(isinstance(checksum, str) and re.fullmatch(r"[0-9a-f]{64}", checksum), "Invalid checksum")
        if staged:
            target = staged / name
            require(target.is_file() and not target.is_symlink() and digest(target) == checksum,
                    "Staged checksum mismatch for " + name)
    return manifest


def stage_package():
    staged = RELEASE / "staged"
    staged.mkdir(mode=0o700)
    manifest = load_manifest()
    seen, size = set(), 0
    with tarfile.open(RELEASE / "payload.tar.gz", "r:gz") as archive:
        for item in archive:
            path = PurePosixPath(item.name)
            require(not path.is_absolute() and ".." not in path.parts, "Unsafe payload path")
            name = str(path)
            if item.isdir():
                require(name == "." or name == "dist" or name.startswith("dist/"), "Unexpected directory")
                continue
            require(item.isfile() and name in manifest and name not in seen, "Unexpected payload member")
            size += item.size
            require(size <= 512 * 1024 * 1024, "Payload exceeds release capacity")
            seen.add(name)
            target = staged / name
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with archive.extractfile(item) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output)
            require(digest(target) == manifest[name], "Payload checksum mismatch")
    require(seen == set(manifest), "Payload differs from manifest")
    return load_manifest(staged)


def config_for(path):
    return json.loads(compose("--project-directory", HOME, "--env-file", HOME / ".env",
                              "-f", path, "config", "--format", "json").stdout)


def validate_compose():
    old = config_for(HOME / "docker-compose.yml")
    new = config_for(RELEASE / "staged/docker-compose.yml")
    for name in ("db", "caddy"):
        require(old["services"][name] == new["services"][name], "Non-app compose service changes")
    for section in ("volumes", "networks"):
        require(old.get(section) == new.get(section), "Compose topology changes")
    require(old["services"]["app"].get("volumes") == new["services"]["app"].get("volumes"),
            "Application mounts change")
    env = new["services"]["app"].get("environment", {})
    old_env = old["services"]["app"].get("environment", {})
    for target in ("MONITOR", "TREASURY", "SIGNATURE", "DELIVERY"):
        require(str(env.get("YBCASE_INTEGRATION_" + target + "_ENABLED", "false")).lower()
                == str(old_env.get("YBCASE_INTEGRATION_" + target + "_ENABLED", "false")).lower(),
                "Live external integration unexpectedly enabled")


def atomically_copy(source, target, mode=0o644):
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".release-", delete=False) as stream:
        temporary = Path(stream.name)
        with Path(source).open("rb") as incoming:
            shutil.copyfileobj(incoming, stream)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, mode)
    temporary.replace(target)


def healthy(container_id):
    details = inspect(container_id)
    for _ in range(90):
        details = inspect(container_id)
        if not details["State"]["Running"]:
            break
        addresses = [value["IPAddress"] for value in details["NetworkSettings"]["Networks"].values()]
        require(len(addresses) == 1 and addresses[0], "Unexpected application network")
        try:
            with urllib.request.urlopen("http://" + addresses[0] + ":8090/actuator/health", timeout=3) as response:
                if json.load(response).get("status") == "UP":
                    return addresses[0]
        except (OSError, ValueError):
            pass
        time.sleep(2)
    with (RELEASE / "startup-error.log").open("ab") as output:
        run("docker", "logs", "--tail", "200", container_id, stdout=output, stderr=output)
    raise RuntimeError("Application readiness failed. See restricted startup-error.log")


def database_identity(name):
    require(re.fullmatch(r"ybcase_release_drill_[0-9]+", name), "Unsafe clone name")
    raw = sql(DATABASE, "select json_build_object('oid',oid,'owner',pg_get_userbyid(datdba),"
              "'comment',shobj_description(oid,'pg_database')) from pg_database where datname='" + name + "'")
    return json.loads(raw) if raw else None


def require_clone(record):
    actual = database_identity(record["name"])
    require(actual and all(actual[key] == record[key] for key in ("oid", "owner", "comment")),
            "Clone identity changed")


def require_pilot(record):
    actual = inspect(record["id"])
    require(actual["Id"] == record["id"] and actual["Name"] == "/" + record["name"]
            and actual["Config"].get("Labels", {}).get("ybcase.release") == RELEASE.name
            and not actual["HostConfig"].get("PortBindings"), "Pilot identity changed")
    return actual


def attachment_volume_identity(name):
    require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,254}", name),
            "Unsafe attachment volume name")
    rows = json.loads(run("docker", "volume", "inspect", name).stdout)
    require(len(rows) == 1 and rows[0].get("Name") == name, "Attachment volume missing")
    return {key: rows[0].get(key) for key in
            ("Name", "Driver", "Mountpoint", "CreatedAt", "Scope", "Labels", "Options")}


def require_attachment_volume(record):
    expected = record.get("attachmentVolume")
    require(isinstance(expected, dict) and expected.get("Name"), "Attachment volume baseline missing")
    actual = attachment_volume_identity(expected["Name"])
    require(actual == expected, "Original attachment volume identity changed")
    return actual


def caddy_fingerprint(details):
    return {"id": details["Id"], "startedAt": details["State"]["StartedAt"],
            "restartCount": details["RestartCount"]}


def pause_caddy(record):
    # An old prepare state has no pause claim. Never adopt a pre-existing pause.
    require(not record.get("caddyPause"), "Caddy pause already attempted; inspect recorded ownership before recovery")
    identifier = record["containers"]["ybcase-caddy-1"]
    details = inspect("ybcase-caddy-1")
    require(details["Id"] == identifier and details["State"]["Running"]
            and not details["State"].get("Paused", False), "Original Caddy must be running and unpaused")
    ownership = {"fingerprint": caddy_fingerprint(details), "token": secrets.token_hex(16), "status": "PAUSE_INTENT"}
    record["caddyPause"] = ownership
    save("state.json", record)
    run("docker", "pause", identifier)
    # Persist successful command ownership before any subsequent validation can fail.
    ownership["status"] = "PAUSED"
    save("state.json", record)
    details = inspect(identifier)
    require(caddy_fingerprint(details) == ownership["fingerprint"] and details["State"]["Running"]
            and details["State"].get("Paused") is True, "Caddy pause identity/state mismatch; manual recovery required")


def unpause_owned_caddy(record, reason):
    ownership = record.get("caddyPause")
    require(ownership and ownership.get("status") == "PAUSED", "No verified owned Caddy pause to release")
    identifier = record["containers"]["ybcase-caddy-1"]
    details = inspect(identifier)
    require(caddy_fingerprint(details) == ownership["fingerprint"] and details["State"]["Running"]
            and details["State"].get("Paused") is True, "Owned Caddy changed; refusing to unpause")
    ownership.update(status="UNPAUSE_INTENT", releaseReason=reason)
    save("state.json", record)
    run("docker", "unpause", identifier)
    details = inspect(identifier)
    require(caddy_fingerprint(details) == ownership["fingerprint"] and details["State"]["Running"]
            and details["State"].get("Paused") is False, "Caddy resume outcome uncertain; inspect state manually")
    ownership["status"] = "RELEASED"
    save("state.json", record)


def record_traffic_hold(record, error):
    pause_status = record.get("caddyPause", {}).get("status")
    record["trafficRecovery"] = {"status": "MANUAL_RECOVERY_REQUIRED", "error": str(error),
                                 "pauseStatus": pause_status,
                                 "trafficState": "PAUSED" if pause_status == "PAUSED" else "UNCONFIRMED",
                                 "note": "Do not toggle Caddy or restore the frozen database without checking live state and recorded ownership; UNPAUSE_INTENT may already have released traffic"}
    save("state.json", record)


def prepare():
    require(not (RELEASE / "state.json").exists(), "Release preparation already started")
    require(schema() == BASELINE_SCHEMA and digest(HOME / "ybcase-server.jar") == OLD_JAR
            and digest(HOME / "dist/index.html") == OLD_INDEX, "Unexpected live release")
    live = {name: inspect(name) for name in CONTAINERS}
    running = run("docker", "ps", "--format", "{{.Names}}").stdout.decode().splitlines()
    require(not any(re.search(r"ybcase.*pilot", name, re.I) for name in running),
            "Another pilot is already running")
    require(all(item["State"]["Running"] for item in live.values()), "Live service is not running")
    require(len(live["ybcase-app-1"]["NetworkSettings"]["Networks"]) == 1, "Unexpected live network")
    attachments = [mount for mount in live["ybcase-app-1"]["Mounts"]
                   if mount["Destination"] == "/app/data/attachments"]
    require(len(attachments) == 1 and attachments[0]["Type"] == "volume", "Unexpected attachment volume")
    attachment_volume = attachment_volume_identity(attachments[0].get("Name"))
    require(attachment_volume["Mountpoint"] == attachments[0].get("Source"),
            "Attachment mount does not match its named volume")
    manifest = stage_package()
    require(digest(Path(__file__)) == manifest["release.py"] and digest(AUDIT) == manifest["data-audit.py"],
            "Executing release tools differ from reviewed payload")
    require(manifest["Caddyfile"] == digest(HOME / "Caddyfile"), "Caddy configuration changes")
    validate_compose()
    for folder in ("backup", "rollback", "pilot-data", "pilot-logs"):
        (RELEASE / folder).mkdir(mode=0o700)
    absent_before = []
    for name in FIXED_FILES | {".env"}:
        source = HOME / name
        if not source.exists() and name in NEW_TOOL_FILES:
            absent_before.append(name)
            continue
        require(source.is_file() and not source.is_symlink(), "Missing or unsafe live rollback file")
        shutil.copy2(source, RELEASE / "rollback" / name)
        os.chmod(RELEASE / "rollback" / name, 0o600)
    shutil.copy2(HOME / "dist/index.html", RELEASE / "rollback/index.html")
    with tarfile.open(RELEASE / "rollback/dist.tar.gz", "w:gz") as archive:
        archive.add(HOME / "dist", arcname="dist")
    before = audit(DATABASE, "before.json")
    save("accounts-before.json", accounts())
    backup_dump("backup")
    capture_attachments(live["ybcase-app-1"]["Id"], "backup/attachments.tar")
    extract_attachments(RELEASE / "backup/attachments.tar", RELEASE / "pilot-data")

    suffix = time.strftime("%Y%m%d%H%M%S", time.gmtime()) + str(secrets.randbelow(10 ** 8)).zfill(8)
    clone = {"name": "ybcase_release_drill_" + suffix, "owner": "hip",
             "comment": RELEASE.name + ":" + secrets.token_hex(16)}
    require(database_identity(clone["name"]) is None, "Clone name exists")
    record = {"release": str(RELEASE), "database": DATABASE,
              "containers": {name: item["Id"] for name, item in live.items()},
              "attachmentVolume": attachment_volume,
              "hashes": {name: digest(HOME / name) for name in FIXED_FILES | {".env", "dist/index.html"} if (HOME / name).is_file()},
              "absentBefore": absent_before, "configSha256": CONFIG_SHA256,
              "manifestSha256": digest(RELEASE / "manifest.json"),
              "backups": {name: digest(RELEASE / "backup" / name) for name in ("db.dump", "attachments.tar")},
              "clone": clone, "prepared": False}
    save("state.json", record)
    db("createdb", "-U", "hip", "-O", "hip", clone["name"])
    sql(DATABASE, 'comment on database "' + clone["name"] + '" is \'' + clone["comment"] + "'")
    clone.update(database_identity(clone["name"]))
    save("state.json", record)
    with (RELEASE / "backup/db.dump").open("rb") as stream:
        db("pg_restore", "-U", "hip", "--single-transaction", "--exit-on-error", "-d", clone["name"], stdin=stream)
    restored = audit(clone["name"], "restored.json", "before.json")
    require(restored["comparison"]["strictLegacyDifferences"] == [], "Restored clone differs")

    app = live["ybcase-app-1"]
    app_env = dict(item.split("=", 1) for item in app["Config"]["Env"])
    require(app_env.get("YBCASE_DB_URL") == "jdbc:postgresql://db:5432/ybcase", "Unexpected live datasource")
    pilot_env = {key: value for key, value in app_env.items()
                 if (key.startswith("YBCASE_") or key == "TZ") and not key.startswith("YBCASE_INTEGRATION_")}
    pilot_env.update(YBCASE_DB_URL="jdbc:postgresql://db:5432/" + clone["name"],
                     YBCASE_JWT_SECRET=secrets.token_hex(48), YBCASE_DB_POOL="3",
                     YBCASE_LOG_FILE="/app/data/logs/ybcase.log", TZ="Asia/Shanghai")
    pilot_env["YBCASE_SECURITY_JWT_SECRET"] = pilot_env["YBCASE_JWT_SECRET"]
    for target in ("MONITOR", "TREASURY", "SIGNATURE", "DELIVERY"):
        pilot_env["YBCASE_INTEGRATION_" + target + "_ENABLED"] = "false"
        pilot_env["YBCASE_INTEGRATION_" + target + "_URL"] = ""
        pilot_env["YBCASE_INTEGRATION_" + target + "_TOKEN"] = ""
    network = next(iter(app["NetworkSettings"]["Networks"]))
    pilot_name = "ybcase-release-pilot-" + suffix
    env_args = [arg for key in sorted(pilot_env) for arg in ("--env", key)]
    identifier = run("docker", "run", "-d", "--name", pilot_name, "--label", "ybcase.release=" + RELEASE.name,
                     "--network", network, "--workdir", "/app", "--memory", "640m",
                     "--volume", str(RELEASE / "staged/ybcase-server.jar") + ":/app/ybcase-server.jar:ro",
                     "--volume", str(RELEASE / "pilot-data/attachments") + ":/app/data/attachments",
                     "--volume", str(RELEASE / "pilot-logs") + ":/app/data/logs",
                     *env_args, app["Image"], "java", "-Xms128m", "-Xmx384m",
                     "-XX:+ExitOnOutOfMemoryError", "-Duser.timezone=Asia/Shanghai",
                     "-jar", "/app/ybcase-server.jar", "--spring.profiles.active=pilot",
                     env={**os.environ, **pilot_env}).stdout.decode().strip()
    pilot = {"name": pilot_name, "id": identifier, "db": clone["name"], "oid": clone["oid"]}
    record["pilot"] = pilot
    save("state.json", record)
    require_pilot(pilot)
    pilot["ip"] = healthy(identifier)
    save("pilot.json", pilot)
    require(schema(clone["name"]) == TARGET_SCHEMA, "Pilot schema does not match target")
    migrated = audit(clone["name"], "pilot-migrated.json", "before.json")
    assert_rows(migrated, "before.json")
    require(accounts(clone["name"]) == json_file(RELEASE / "accounts-before.json"), "Pilot account initialization changed")
    require(schema() == BASELINE_SCHEMA, "Source database changed during prepare")
    for name, expected in record["containers"].items():
        require(inspect(name)["Id"] == expected, "Source container changed during prepare")
    require(digest(HOME / ".env") == record["hashes"][".env"], "Source environment changed")
    record["prepared"] = True
    save("state.json", record)
    mark("prepare.complete")
    return {"passed": True, "sourceSchema": BASELINE_SCHEMA, "pilotSchema": TARGET_SCHEMA, "legacyTables": len(before["tables"]), "pilot": pilot}


def require_gates(record, manifest):
    require((RELEASE / "prepare.complete").is_file() and record["prepared"], "Preparation incomplete")
    require(digest(RELEASE / "manifest.json") == record["manifestSha256"], "Manifest changed")
    require(record.get("configSha256") == CONFIG_SHA256, "Release configuration changed")
    for filename in ("tests.passed", "pilot.passed", "git.json"):
        gate = json_file(RELEASE / filename)
        require(gate.get("jarSha256") == manifest["ybcase-server.jar"]
                and gate.get("indexSha256") == manifest["dist/index.html"], "Gate belongs to a different build")
        if filename == "git.json":
            require(isinstance(gate.get("commit"), str) and re.fullmatch(r"[0-9a-f]{40,64}", gate["commit"])
                    and gate.get("pushed") is True, "Git verification gate incomplete")
        else:
            require(gate.get("passed") is True, "Test gate did not pass")
        if filename == "tests.passed":
            require(isinstance(gate.get("backendTests"), int) and gate["backendTests"] > 0
                    and isinstance(gate.get("frontendTests"), int) and gate["frontendTests"] > 0
                    and gate.get("buildPassed") is True, "Complete regression and build evidence required")
        if filename == "pilot.passed":
            require(gate.get("pilotId") == record["pilot"]["id"], "Pilot test identity differs")
            require(isinstance(gate.get("checksPassed"), int) and gate["checksPassed"] > 0,
                    "Nonempty pilot acceptance evidence required")


def restore_frozen():
    # Apply DROP SCHEMA and the complete original SQL in one transaction.
    # The custom dump's own public schema entry is retained if present.
    dump = RELEASE / "frozen/db.dump"
    with dump.open("rb") as stream:
        toc = db("pg_restore", "--list", stdin=stream).stdout.decode()
    has_public = bool(re.search(r"^\d+; \d+ \d+ SCHEMA - public ", toc, re.MULTILINE))
    prefix = b"set local lock_timeout = '30s';\ndrop schema public cascade;\n"
    if not has_public:
        prefix += b"create schema public authorization hip;\n"
    error_path = RELEASE / "restore-error.log"
    with dump.open("rb") as incoming, error_path.open("ab") as errors:
        export = subprocess.Popen(["docker", "compose", "exec", "-T", "db", "pg_restore",
                                   "--no-owner", "--no-acl", "--file=-"], cwd=HOME,
                                  stdin=incoming, stdout=subprocess.PIPE, stderr=errors)
        apply = subprocess.Popen(["docker", "compose", "exec", "-T", "db", "psql", "-Xq",
                                  "-v", "ON_ERROR_STOP=1", "--single-transaction", "-U", "hip", "-d", DATABASE],
                                 cwd=HOME, stdin=subprocess.PIPE, stdout=errors, stderr=errors)
        try:
            apply.stdin.write(prefix)
            shutil.copyfileobj(export.stdout, apply.stdin)
            # Keep psql's transaction open until the exporter is known to be complete.
            # Closing stdin first would let a truncated exporter commit a partial restore.
            require(export.wait() == 0, "Frozen database dump could not be rendered")
            apply.stdin.close()
            require(apply.wait() == 0, "Frozen database restore failed")
        finally:
            for process in (export, apply):
                if process.poll() is None:
                    process.kill()
                process.wait()


def rollback(record):
    checksums = json_file(RELEASE / "frozen/hashes.json")
    for name, checksum in checksums.items():
        require(name in ("db.dump", "attachments.tar") and digest(RELEASE / "frozen" / name) == checksum,
                "Frozen rollback backup changed")
    require(set(checksums) == {"db.dump", "attachments.tar"}, "Frozen rollback set incomplete")
    volume = require_attachment_volume(record)
    ownership = record.get("caddyPause", {})
    require(ownership.get("status") == "PAUSED", "Rollback requires a verified owned traffic hold")
    caddy = inspect(record["containers"]["ybcase-caddy-1"])
    require(caddy_fingerprint(caddy) == ownership.get("fingerprint")
            and caddy["State"].get("Paused") is True, "Traffic is not safely held; rollback refused")
    for name in ("ybcase-db-1", "ybcase-caddy-1"):
        require(inspect(name)["Id"] == record["containers"][name], "Non-app container changed before rollback")
    compose("stop", "-t", "30", "app")
    require(digest(HOME / ".env") == record["hashes"][".env"], "Environment changed before rollback")
    restore_frozen()
    # Restore the complete attachment tree into the existing named volume, without deleting that volume.
    image = inspect(record["containers"]["ybcase-db-1"])["Image"]
    with (RELEASE / "frozen/attachments.tar").open("rb") as incoming:
        run("docker", "run", "--rm", "--network", "none", "--mount",
            "type=volume,source=" + volume["Name"] + ",target=/app/data/attachments", "-i",
            image, "sh", "-c",
            "find /app/data/attachments -mindepth 1 -maxdepth 1 -exec rm -rf -- {} + && tar -C /app/data -xf -",
            stdin=incoming)
    for name in FIXED_FILES - {"Caddyfile"}:
        previous = RELEASE / "rollback" / name
        if previous.is_file():
            atomically_copy(previous, HOME / name, 0o755 if name.endswith((".sh", ".py")) else 0o644)
        else:
            require(name in record.get("absentBefore", []), "Rollback artifact missing")
            target = HOME / name
            if target.exists():
                require(target.is_file() and not target.is_symlink()
                        and digest(target) == load_manifest()[name], "New tool changed; refuse removal")
                target.unlink()
    atomically_copy(RELEASE / "rollback/index.html", HOME / "dist/index.html")
    compose("up", "-d", "--no-deps", "--force-recreate", "app")
    identifier = inspect("ybcase-app-1")["Id"]
    healthy(identifier)
    require(schema() == BASELINE_SCHEMA and digest(HOME / "ybcase-server.jar") == OLD_JAR, "Rollback release mismatch")
    restored = audit(DATABASE, "rollback-verified.json", "frozen/baseline.json")
    assert_rows(restored, "frozen/baseline.json")
    require(accounts() == json_file(RELEASE / "frozen/accounts.json"), "Rollback account mismatch")
    actual = capture_attachments(identifier, "rollback-attachments.tar")
    require(actual == json_file(RELEASE / "frozen/attachments.tar.manifest.json"), "Rollback attachment mismatch")
    for name in ("ybcase-db-1", "ybcase-caddy-1"):
        require(inspect(name)["Id"] == record["containers"][name], "Non-app container changed")
    mark("rollback.complete")


def activate():
    record = state()
    require(not (RELEASE / "activate.started").exists(), "Activation already attempted; review its outcome")
    require(not record.get("caddyPause"), "Recorded Caddy pause requires manual review before another activation")
    manifest = load_manifest(RELEASE / "staged")
    require_gates(record, manifest)
    require_clone(record["clone"])
    require_pilot(record["pilot"])
    require_attachment_volume(record)
    require(schema() == BASELINE_SCHEMA, "Source schema changed")
    for name, checksum in record["hashes"].items():
        require(digest(HOME / name) == checksum, "Source artifact changed before cutover")
    for name, identifier in record["containers"].items():
        require(inspect(name)["Id"] == identifier, "Source container changed before cutover")
    require(accounts() == json_file(RELEASE / "accounts-before.json"), "Source account changed before cutover")
    validate_compose()
    for name, checksum in manifest.items():
        target = HOME / name
        if name.startswith("dist/") and name != "dist/index.html" and target.exists():
            require(digest(target) == checksum, "Existing static asset path collision")
    run("docker", "stop", "-t", "30", record["pilot"]["id"])
    (RELEASE / "frozen").mkdir(mode=0o700)
    mark("activate.started")
    stopped = False
    try:
        pause_caddy(record)
        compose("stop", "-t", "30", "app")
        stopped = True
        require(not inspect(record["containers"]["ybcase-app-1"])["State"]["Running"], "Source app did not stop")
        audit(DATABASE, "frozen/baseline.json")
        save("frozen/accounts.json", accounts())
        backup_dump("frozen")
        capture_attachments(record["containers"]["ybcase-app-1"], "frozen/attachments.tar")
        save("frozen/hashes.json", {name: digest(RELEASE / "frozen" / name)
                                   for name in ("db.dump", "attachments.tar")})
        mark("frozen.complete")
        atomically_copy(RELEASE / "staged/docker-compose.yml", HOME / "docker-compose.yml")
        atomically_copy(RELEASE / "staged/ybcase-server.jar", HOME / "ybcase-server.jar")
        compose("up", "-d", "--no-deps", "--force-recreate", "app")
        record["activatedAppId"] = inspect("ybcase-app-1")["Id"]
        save("state.json", record)
        healthy(record["activatedAppId"])
        require(schema() == TARGET_SCHEMA, "Live schema does not match target")
        migrated = audit(DATABASE, "after-migrations.json", "frozen/baseline.json")
        assert_rows(migrated, "frozen/baseline.json")
        require(accounts() == json_file(RELEASE / "frozen/accounts.json"), "Live account initialization changed")
        for name in ("ybcase-db-1", "ybcase-caddy-1"):
            require(inspect(name)["Id"] == record["containers"][name], "Non-app container changed")
        require(digest(HOME / ".env") == record["hashes"][".env"], "Live environment changed")
        require(digest(HOME / "Caddyfile") == manifest["Caddyfile"], "Live Caddy config changed")
        for name in ("backup.sh", "backup-set.py", "upgrade.sh", "healthcheck.sh", "release.py", "data-audit.py",
                     "backup-transfer.py", "restore-rehearsal.py", "capacity-check.py"):
            atomically_copy(RELEASE / "staged" / name, HOME / name, 0o755)
        for name in manifest:
            if name.startswith("dist/") and name != "dist/index.html" and not (HOME / name).exists():
                atomically_copy(RELEASE / "staged" / name, HOME / name)
        atomically_copy(RELEASE / "staged/dist/index.html", HOME / "dist/index.html")
        for name, checksum in manifest.items():
            require(digest(HOME / name) == checksum, "Activated artifact checksum mismatch")
        current = capture_attachments(record["activatedAppId"], "activated-attachments.tar")
        require(current == json_file(RELEASE / "frozen/attachments.tar.manifest.json"), "Activated attachment content changed")
        mark("activate.complete")
    except BaseException:
        try:
            if stopped:
                if (RELEASE / "frozen.complete").exists():
                    rollback(record)
                else:
                    # No source artifact or schema has changed before frozen.complete.
                    compose("up", "-d", "--no-deps", "app")
                    healthy(inspect("ybcase-app-1")["Id"])
            if record.get("caddyPause", {}).get("status") == "PAUSED":
                # A failed stop command may have stopped the old app despite returning an error.
                # Never reopen traffic until the restored/unchanged old release is healthy.
                require(schema() == BASELINE_SCHEMA and digest(HOME / "ybcase-server.jar") == OLD_JAR
                        and digest(HOME / "dist/index.html") == OLD_INDEX, "Old release was not restored; traffic remains held")
                healthy(inspect("ybcase-app-1")["Id"])
                unpause_owned_caddy(record, "OLD_RELEASE_VERIFIED")
            elif record.get("caddyPause"):
                # PAUSE_INTENT may mean interruption around docker pause; do not guess ownership.
                raise RuntimeError("Caddy pause outcome uncertain; refusing automatic unpause")
        except BaseException as recovery_error:
            record_traffic_hold(record, recovery_error)
            raise RuntimeError("Release recovery or traffic resume outcome uncertain; inspect state.json and live Caddy before any manual rollback or resume") from recovery_error
        raise
    # Deliberately outside the rollback handler. Once traffic is released, a later
    # resume/verification failure must never restore a pre-release database over users' writes.
    try:
        unpause_owned_caddy(record, "TARGET_FULLY_VERIFIED")
    except BaseException as resume_error:
        record_traffic_hold(record, resume_error)
        raise RuntimeError("Target release is fully verified; traffic resume outcome uncertain. Never restore the frozen database after possible traffic release") from resume_error
    return {"passed": True, "schema": TARGET_SCHEMA, "jarSha256": manifest["ybcase-server.jar"],
            "indexSha256": manifest["dist/index.html"], "onlyAppRecreated": True,
            "trafficResumedAfterVerification": True}


def final():
    record = state()
    require(record.get("caddyPause", {}).get("status") == "RELEASED", "Traffic release has not been reconciled")
    require((RELEASE / "activate.complete").is_file() and not (RELEASE / "final.complete").exists(),
            "Activation incomplete or final already verified")
    manifest = load_manifest(RELEASE / "staged")
    for name, checksum in manifest.items():
        require(digest(HOME / name) == checksum, "Final artifact checksum mismatch")
    require(digest(HOME / ".env") == record["hashes"][".env"] and schema() == TARGET_SCHEMA, "Final environment/schema mismatch")
    for name in ("ybcase-db-1", "ybcase-caddy-1"):
        require(inspect(name)["Id"] == record["containers"][name], "Non-app container changed")
    require(inspect("ybcase-app-1")["Id"] == record["activatedAppId"], "Activated app identity changed")
    require_attachment_volume(record)
    healthy(record["activatedAppId"])
    result = audit(DATABASE, "final-production.json", "frozen/baseline.json")
    assert_rows(result, "frozen/baseline.json", allow_logins=True)
    require(accounts() == json_file(RELEASE / "frozen/accounts.json"), "Account fields or roles changed")
    current = capture_attachments(record["activatedAppId"], "final-attachments.tar")
    require(current == json_file(RELEASE / "frozen/attachments.tar.manifest.json"), "Attachment content changed")
    require(schema(record["clone"]["name"]) == TARGET_SCHEMA, "Pilot database changed unexpectedly")
    outcome = {"passed": True, "schema": TARGET_SCHEMA, "originalBusinessRowsPreserved": True,
               "accountsAndRolesPreserved": True, "attachmentsUnchanged": True,
               "onlyAppRecreated": True, "jarSha256": manifest["ybcase-server.jar"],
               "indexSha256": manifest["dist/index.html"]}
    save("final-result.json", outcome)
    mark("final.complete")
    return outcome


def cleanup():
    record = state()
    complete = (RELEASE / "final.complete").exists() or (RELEASE / "rollback.complete").exists()
    if not complete:
        require(not (RELEASE / "activate.complete").exists() and schema() == BASELINE_SCHEMA
                and digest(HOME / "ybcase-server.jar") == OLD_JAR
                and digest(HOME / "dist/index.html") == OLD_INDEX, "Live release has not been verified")
        for name in ("ybcase-db-1", "ybcase-caddy-1"):
            require(inspect(name)["Id"] == record["containers"][name], "Non-app container changed")
    require(not (RELEASE / "cleanup.complete").exists(), "Cleanup already completed")
    pilot = record.get("pilot")
    if pilot and not record.get("pilotRemoved"):
        require_pilot(pilot)
        run("docker", "stop", "-t", "30", pilot["id"])
        run("docker", "rm", pilot["id"])
        record["pilotRemoved"] = True
        save("state.json", record)
    if not record.get("cloneRemoved"):
        require_clone(record["clone"])
        db("dropdb", "-U", "hip", record["clone"]["name"])
        record["cloneRemoved"] = True
        save("state.json", record)
    mark("cleanup.complete")
    return {"passed": True, "ownPilotRemoved": True, "ownCloneRemoved": True, "backupsRetained": True}


def configure(release_dir):
    global HOME, RELEASE, BASELINE_SCHEMA, TARGET_SCHEMA, OLD_JAR, OLD_INDEX, CONFIG_SHA256
    RELEASE = Path(release_dir).resolve()
    config_path = RELEASE / "release-config.json"
    require(config_path.is_file() and not config_path.is_symlink(), "Missing release-config.json")
    config = json_file(config_path)
    require(config.get("format") == 1 and config.get("home") == "/opt/ybcase", "Unsupported deployment home")
    HOME = Path(config["home"])
    require(RELEASE.is_relative_to(HOME) and RELEASE != HOME and RELEASE.name not in ("backup", "dist", "rollback"),
            "Release must be an explicit child directory of deployment home")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,95}", RELEASE.name), "Invalid release identifier")
    BASELINE_SCHEMA, TARGET_SCHEMA = config.get("baselineSchema"), config.get("targetSchema")
    require(isinstance(BASELINE_SCHEMA, int) and not isinstance(BASELINE_SCHEMA, bool)
            and isinstance(TARGET_SCHEMA, int) and not isinstance(TARGET_SCHEMA, bool)
            and 1 <= BASELINE_SCHEMA <= TARGET_SCHEMA, "Invalid migration interval")
    OLD_JAR, OLD_INDEX = config.get("baselineJarSha256"), config.get("baselineIndexSha256")
    require(all(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) for value in (OLD_JAR, OLD_INDEX)),
            "Baseline artifact checksums required")
    CONFIG_SHA256 = digest(config_path)
    require(AUDIT.is_file() and not AUDIT.is_symlink(), "Versioned data-audit.py missing")


def status():
    record = state()
    return {"release": RELEASE.name, "prepared": record.get("prepared", False),
            "activated": (RELEASE / "activate.complete").is_file(),
            "finalVerified": (RELEASE / "final.complete").is_file(),
            "cleanupComplete": (RELEASE / "cleanup.complete").is_file(),
            "traffic": record.get("caddyPause", {}).get("status", "UNCHANGED"),
            "sourceSchema": BASELINE_SCHEMA, "targetSchema": TARGET_SCHEMA, "currentSchema": schema()}


def resume():
    """Reconcile lost traffic-resume responses. Never restores any database."""
    record = state()
    require((RELEASE / "activate.complete").is_file(), "Candidate has not passed full activation verification")
    manifest = load_manifest(RELEASE / "staged")
    require_gates(record, manifest)
    require(schema() == TARGET_SCHEMA, "Candidate schema changed")
    for name, checksum in manifest.items():
        require(digest(HOME / name) == checksum, "Candidate artifact changed")
    require(inspect("ybcase-app-1")["Id"] == record["activatedAppId"], "Candidate app identity changed")
    healthy(record["activatedAppId"])
    ownership = record.get("caddyPause", {})
    require(ownership.get("status") in ("PAUSED", "UNPAUSE_INTENT", "RELEASED"), "Unverified traffic ownership")
    live = inspect(record["containers"]["ybcase-caddy-1"])
    require(caddy_fingerprint(live) == ownership["fingerprint"], "Caddy identity changed")
    if ownership["status"] == "PAUSED":
        require(live["State"].get("Paused") is True, "Owned pause changed")
        unpause_owned_caddy(record, "TARGET_RECONCILED")
    else:
        require(live["State"]["Running"] and live["State"].get("Paused") is False,
                "Traffic outcome uncertain; explicit operational review required")
        ownership["status"] = "RELEASED"
        save("state.json", record)
    return {"passed": True, "trafficReconciled": True, "databaseRestored": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", required=True)
    parser.add_argument("phase", choices=("prepare", "activate", "resume", "status", "final", "cleanup"))
    args = parser.parse_args()
    require(__debug__, "Do not disable release assertions")
    require(os.name == "posix", "Run release phases on the intended Linux deployment host")
    configure(args.release_dir)
    os.umask(0o077)
    os.chmod(RELEASE, 0o700)
    os.chdir(HOME)
    import fcntl
    with (RELEASE / "operation.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            result = {"prepare": prepare, "activate": activate, "resume": resume, "status": status,
                      "final": final, "cleanup": cleanup}[args.phase]()
            print(json.dumps(result, ensure_ascii=True))
        except BaseException:
            with (RELEASE / (args.phase + "-error.log")).open("a", encoding="utf-8") as stream:
                traceback.print_exc(file=stream)
            raise SystemExit("Release phase failed. See restricted " + args.phase + "-error.log")


if __name__ == "__main__":
    main()
