# -*- coding: utf-8 -*-
"""轻量压测：登录后并发打 概览/督办/案件分页/详情 四类只读接口，输出 RPS 与 P95。
用法：python tools/loadtest.py [threads=8] [seconds=20]
"""
import statistics
import sys
import threading
import time

import requests

BASE = "http://127.0.0.1:8090/api"
THREADS = int(sys.argv[1]) if len(sys.argv) > 1 else 8
SECONDS = int(sys.argv[2]) if len(sys.argv) > 2 else 20

token = requests.post(f"{BASE}/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=10).json()["data"]["token"]
H = {"Authorization": f"Bearer {token}"}
case_id = requests.get(f"{BASE}/bureau/cases", headers=H, timeout=10).json()["data"][0]["id"]

PATHS = ["/bureau/stats/overview", "/bureau/stats/supervision",
         "/bureau/cases?page=1&size=20", f"/bureau/cases/{case_id}"]

latencies, errors = [], [0]
lock = threading.Lock()
deadline = time.time() + SECONDS


def worker(idx):
    s = requests.Session()
    s.headers.update(H)
    i = 0
    while time.time() < deadline:
        p = PATHS[(idx + i) % len(PATHS)]
        t0 = time.perf_counter()
        try:
            r = s.get(BASE + p, timeout=15)
            ok = r.status_code == 200 and r.json().get("code") == 0
        except Exception:
            ok = False
        dt = (time.perf_counter() - t0) * 1000
        with lock:
            latencies.append(dt)
            if not ok:
                errors[0] += 1
        i += 1


threads = [threading.Thread(target=worker, args=(i,)) for i in range(THREADS)]
t_start = time.time()
[t.start() for t in threads]
[t.join() for t in threads]
elapsed = time.time() - t_start

lat = sorted(latencies)
p95 = lat[int(len(lat) * 0.95)] if lat else 0
print(f"threads={THREADS} duration={elapsed:.1f}s requests={len(lat)} errors={errors[0]}")
print(f"RPS={len(lat)/elapsed:.1f}  avg={statistics.mean(lat):.0f}ms  P50={lat[len(lat)//2]:.0f}ms  P95={p95:.0f}ms  max={max(lat):.0f}ms")
