"""Isolated relay tests; no SQL and no production relay changes."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "dev-db-relay.py"
spec = importlib.util.spec_from_file_location("relay", SCRIPT)
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)

class RelayTest(unittest.TestCase):
    def test_explicit_specific_address_required(self):
        for value in ["0.0.0.0", "224.0.0.1", "bad"]:
            with self.assertRaises((ValueError, argparse.ArgumentTypeError)):
                relay.ipv4(value)
        self.assertEqual("127.0.0.1", relay.ipv4("127.0.0.1"))
        self.assertNotEqual(0, subprocess.run([sys.executable,str(SCRIPT)],capture_output=True).returncode)

    def test_allowlist_rejects_before_opening_upstream(self):
        server=relay.Server(("127.0.0.1",0),relay.Relay); server.allowed_client="127.0.0.2"
        worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
        try:
            with patch.object(relay.socket,"create_connection") as upstream:
                client=socket.socket(); client.settimeout(2); client.connect(server.server_address)
                self.assertEqual(b"",client.recv(1)); client.close(); upstream.assert_not_called()
        finally: server.shutdown(); server.server_close(); worker.join()

    def test_allowed_client_relays_exact_bytes_bidirectionally(self):
        echo=socket.socket(); echo.bind(("127.0.0.1",0)); echo.listen()
        def respond():
            connection,_=echo.accept()
            with connection:
                data=connection.recv(4096); connection.sendall(data[::-1])
        thread=threading.Thread(target=respond,daemon=True); thread.start()
        server=relay.Server(("127.0.0.1",0),relay.Relay); server.allowed_client="127.0.0.1"
        worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
        original=socket.create_connection
        try:
            with patch.object(relay.socket,"create_connection",side_effect=lambda address,timeout:original(echo.getsockname(),timeout)):
                client=socket.socket(); client.settimeout(2); client.connect(server.server_address)
                data=b"raw\x00bytes\xff"; client.sendall(data); self.assertEqual(data[::-1],client.recv(4096)); client.close()
        finally: server.shutdown(); server.server_close(); echo.close(); worker.join(); thread.join(timeout=2)

    def test_existing_unrelated_listener_is_not_reused(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parents[1] / "data/recovery/matrix-relay-20261021") as root:
            listener=socket.socket(); listener.bind(("127.0.0.1",0)); listener.listen()
            args=[sys.executable,str(SCRIPT),"--listen-host","127.0.0.1","--allow-client","127.0.0.1","--port",str(listener.getsockname()[1]),"--state-file",root+"/state.json"]
            try:
                self.assertNotEqual(0,subprocess.run(args+["--check-available"],capture_output=True).returncode)
                self.assertNotEqual(0,subprocess.run(args,capture_output=True).returncode)
                self.assertFalse(Path(root+"/state.json").exists())
                self.assertNotEqual(0,subprocess.run(args+["--verify-running"],capture_output=True).returncode)
            finally: listener.close()

    def test_live_identity_rejects_wrong_config_modified_state_and_stale_process(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parents[1] / "data/recovery/matrix-relay-20261021") as root:
            probe=socket.socket(); probe.bind(("127.0.0.1",0)); port=probe.getsockname()[1]; probe.close()
            path=Path(root)/"state.json"
            args=[sys.executable,str(SCRIPT),"--listen-host","127.0.0.1","--allow-client","127.0.0.1","--port",str(port),"--state-file",str(path)]
            process=subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
            try:
                for _ in range(100):
                    if path.exists(): break
                    if process.poll() is not None: self.fail(process.stderr.read().decode())
                    time.sleep(0.02)
                self.assertTrue(path.exists())
                def verify(): return subprocess.run(args+["--verify-running"],capture_output=True)
                self.assertEqual(0,verify().returncode)
                wrong=args.copy(); wrong[wrong.index("--allow-client")+1]="127.0.0.2"
                self.assertNotEqual(0,subprocess.run(wrong+["--verify-running"],capture_output=True).returncode)
                state=json.loads(path.read_text()); original=path.read_text()
                state["start_ticks"]="0"; path.write_text(json.dumps(state)); self.assertNotEqual(0,verify().returncode)
                path.write_text(original); state=json.loads(original); state["script_sha256"]="0"*64
                path.write_text(json.dumps(state)); self.assertNotEqual(0,verify().returncode)
                path.write_text(original)
            finally:
                process.terminate(); process.wait(timeout=5); process.stderr.close()
            self.assertNotEqual(0,subprocess.run(args+["--verify-running"],capture_output=True).returncode)

if __name__=="__main__": unittest.main(verbosity=2)
