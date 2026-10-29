"""Run Android journeys against the real bridge and a task-owned synthetic Herdr.

No real agent or provider process is started. Both fixture HTTP and bridge HTTP
bind loopback; adb reverses only the two owned ports on an explicitly selected
emulator. All temporary files, servers, reverse mappings and bridge children are
scoped to this invocation. Run --self-test for host-only harness verification.
"""

import argparse
import contextlib
import http.server
import json
import os
from pathlib import Path
import socket
import socketserver
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]
WRITES = {"agent.prompt", "pane.send_keys", "pane.send_text"}
APPROVAL = "Approve tool call: run_command\n$ echo synthetic\n1. Allow once\n2. Allow always\n3. Reject\n"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_):
        return None


def request(url, body=None):
    data = None if body is None else json.dumps(body).encode()
    call = urllib.request.Request(url, data, {"Content-Type": "application/json"})
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        response = opener.open(call, timeout=12)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)


class Fixture:
    def __init__(self, home):
        self.lock = threading.RLock()
        self.native = "A"
        self.outage = False
        self.history = True
        self.screen = "plain"
        self.journal = []
        self.hold_next = False
        self.waiting = False
        self.release = threading.Event()
        self.paths = {}
        store = home / ".pi/agent/sessions"
        store.mkdir(parents=True)
        for native in ("A", "B"):
            path = store / f"synthetic-{native}.jsonl"
            records = [
                {"type": "session", "id": f"synthetic-{native}"},
                {"type": "message", "id": "m1", "parentId": None,
                 "message": {"role": "user", "content": [{"type": "text", "text": f"Synthetic history {native}"}]}},
            ]
            path.write_text("".join(json.dumps(row) + "\n" for row in records))
            self.paths[native] = path

    def snapshot(self):
        with self.lock:
            return {"native": self.native, "history": self.history,
                    "waiting": self.waiting, "journal": list(self.journal)}

    def change(self, body):
        with self.lock:
            if "native" in body:
                if body["native"] not in ("A", "B"):
                    raise ValueError("synthetic native must be A or B")
                self.native = body["native"]
            if "history" in body and body["history"] != self.history:
                for path in self.paths.values():
                    held = path.with_suffix(".held")
                    if body["history"]:
                        held.rename(path)
                    else:
                        path.rename(held)
                self.history = body["history"]
            if "screen" in body:
                if body["screen"] not in ("plain", "approval"):
                    raise ValueError("invalid synthetic screen")
                self.screen = body["screen"]
            if "outage" in body:
                self.outage = body["outage"]
            if body.get("clear_journal"):
                self.journal.clear()
            if body.get("hold_next"):
                self.hold_next = True
                self.release.clear()
            if body.get("release"):
                self.release.set()

    def rpc(self, call):
        method = call.get("method")
        with self.lock:
            if self.outage:
                return {"error": {"message": "synthetic source unavailable"}}
            row = {"name": "s-journey", "pane_id": "w1:a", "agent": "pi",
                   "agent_status": "idle", "cwd": "",
                   "agent_session": {"agent": "pi", "kind": "path", "value": str(self.paths[self.native])}}
            if method == "agent.list":
                return {"result": {"agents": [row]}}
            if method == "agent.get":
                return {"result": {"agent": row}}
            if method == "pane.read":
                return {"result": {"read": {"text": APPROVAL if self.screen == "approval" else "Synthetic output\n"}}}
            if method not in WRITES:
                return {"error": {"message": "unsupported synthetic RPC"}}
            self.journal.append({"method": method, "params": call.get("params"), "native": self.native})
            hold = self.hold_next
            self.hold_next = False
            self.waiting = hold
        if hold:
            # A recorded write might have applied, but its acknowledgement is lost.
            self.release.wait(8)
            with self.lock:
                self.waiting = False
            return None
        return {"result": {}}


class Peer(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True


class RpcHandler(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        call = json.loads(self.rfile.readline(1024 * 1024))
        result = self.server.fixture.rpc(call)
        if result is not None:
            result["id"] = call.get("id")
            self.wfile.write(json.dumps(result).encode() + b"\n")


class Admin(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.reply(self.server.fixture.snapshot())

    def do_POST(self):
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 4096:
                raise ValueError("invalid fixture body length")
            self.server.fixture.change(json.loads(self.rfile.read(size)))
            self.reply(self.server.fixture.snapshot())
        except (ValueError, KeyError) as error:
            self.reply({"error": str(error)}, 400)

    def reply(self, body, status=200):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


@contextlib.contextmanager
def running_fixture(binary):
    # Short TMPDIR child keeps the Unix path below macOS SUN_LEN; context cleanup
    # removes it even when instrumentation fails. No user stores are opened.
    with tempfile.TemporaryDirectory(prefix="cj-") as directory:
        root = Path(directory)
        home = root / "home"
        home.mkdir()
        machine = str(uuid.uuid4())
        identity = root / "data" / "machine-id"
        identity.parent.mkdir(parents=True)
        identity.write_text(machine)
        fixture = Fixture(home)
        with Peer(str(root / "peer"), RpcHandler) as peer, http.server.ThreadingHTTPServer(("127.0.0.1", 0), Admin) as admin:
            peer.fixture = admin.fixture = fixture
            threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (peer, admin)]
            for thread in threads:
                thread.start()
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                port = reservation.getsockname()[1]
            env = {"PATH": os.environ.get("PATH", ""), "HOME": str(home),
                   "HERDR_SOCKET_PATH": str(root / "peer"), "CAPP_BRIDGE_HOST": "127.0.0.1",
                   "CAPP_BRIDGE_PORT": str(port), "CAPP_BRIDGE_DATA_DIR": str(root / "data"),
                   "CAPP_BRIDGE_CONFIG": str(root / "no-config.json"), "CAPP_BRIDGE_SERVE_AUTO_APPLY": "0"}
            process = None
            try:
                process = subprocess.Popen([str(binary)], env=env)
                base = f"http://127.0.0.1:{port}"
                deadline = time.monotonic() + 10
                while True:
                    if process.poll() is not None:
                        raise RuntimeError("owned bridge exited before readiness")
                    try:
                        status, paired = request(base + "/api/session")
                        if status != 200 or paired.get("machine_id") != machine:
                            raise RuntimeError("bridge port does not belong to this fixture")
                        break
                    except (urllib.error.URLError, ConnectionError):
                        if time.monotonic() >= deadline:
                            raise TimeoutError("owned bridge readiness timed out")
                        threading.Event().wait(0.05)
                yield base, admin.server_port, fixture
            finally:
                fixture.release.set()
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
                for server in (peer, admin):
                    server.shutdown()
                for thread in threads:
                    thread.join(timeout=5)
                    if thread.is_alive():
                        raise RuntimeError("fixture listener failed to stop")


def self_test(base, fixture):
    _, agents = request(base + "/api/agents")
    row = agents["agents"][0]
    binding = {key: row[key] for key in ("machine_id", "pane_id", "native_session_ref")}
    assert row["native_session_ref"]
    assert request(base + "/api/agents/s-journey/conversation")[1]["conversation_id"]
    fixture.change({"native": "B"})
    status, error = request(base + "/api/agents/s-journey/prompt", {
        "type": "prompt", "text": "synthetic", "expected_session": binding})
    assert status == 409 and error["code"] == "stale_session"
    assert fixture.snapshot()["journal"] == []
    _, agents = request(base + "/api/agents")
    binding["native_session_ref"] = agents["agents"][0]["native_session_ref"]
    assert request(base + "/api/agents/s-journey/prompt", {
        "type": "prompt", "text": "synthetic", "expected_session": binding})[0] == 200
    assert fixture.snapshot()["journal"][0]["native"] == "B"
    fixture.change({"history": False})
    _, unavailable = request(base + "/api/agents/s-journey/conversation")
    assert unavailable["source"] == "scrollback" and unavailable.get("conversation_id") is None
    fixture.change({"history": True})
    assert request(base + "/api/agents/s-journey/conversation")[1]["conversation_id"]
    print("Actual-bridge fixture self-test passed", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--serial")
    args = parser.parse_args()
    if not args.self_test and (not args.serial or not args.serial.startswith("emulator-")):
        parser.error("--serial must explicitly select a task-owned emulator")
    subprocess.run(["cargo", "build", "--locked", "--manifest-path", "services/bridge/Cargo.toml"], cwd=ROOT, check=True)
    with running_fixture(ROOT / "services/bridge/target/debug/cappuccino-bridge") as (base, admin, fixture):
        if args.self_test:
            self_test(base, fixture)
            return
        adb = ["adb", "-s", args.serial]
        if subprocess.check_output(adb + ["get-state"], text=True).strip() != "device":
            raise RuntimeError("selected task emulator is not ready")
        port = urllib.parse.urlparse(base).port
        reverses = []
        try:
            existing = subprocess.check_output(adb + ["reverse", "--list"], text=True)
            for number in (port, admin):
                address = f"tcp:{number}"
                if address in existing.split():
                    raise RuntimeError("refusing to replace an existing adb reverse mapping")
                subprocess.run(adb + ["reverse", address, address], check=True)
                reverses.append(address)
            subprocess.run([
                str(ROOT / "apps/android/gradlew"), "--no-daemon", "-p", str(ROOT / "apps/android"),
                ":app:connectedDebugAndroidTest",
                f"-Pandroid.testInstrumentationRunnerArguments.bridgePort={port}",
                f"-Pandroid.testInstrumentationRunnerArguments.fixturePort={admin}",
            ], cwd=ROOT, check=True, timeout=600)
        finally:
            failed = []
            for address in reverses:
                result = subprocess.run(adb + ["reverse", "--remove", address])
                if result.returncode:
                    failed.append(address)
            if failed:
                raise RuntimeError(f"failed to remove owned adb reverse mappings: {failed}")


if __name__ == "__main__":
    main()
