#!/usr/bin/env python3
"""Fake NanoKVM for emulator development: the endpoints the app uses, plus a PC that turns on and off.

Run: python3 tools/mock_nanokvm.py [port]   (default 8088)
In the emulator, the host is 10.0.2.2, so enter 10.0.2.2:8088 on the login screen.
Login: admin / admin. The password must arrive CryptoJS-encrypted, as from the real web UI.
"""
import base64, hashlib, json, sys, time, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

TOKEN = "mock-token"
state = {"on": True, "sleep": False}  # GET /mock/sleep puts the fake PC to sleep (LED blinks, 0.6 s on / 0.6 s off)


def decrypt(data: str) -> str:
    raw = base64.b64decode(urllib.parse.unquote(data))
    assert raw[:8] == b"Salted__"
    salt, ct = raw[8:16], raw[16:]
    d, block = b"", b""
    while len(d) < 48:
        block = hashlib.md5(block + b"nanokvm-sipeed-2024" + salt).digest()
        d += block
    dec = Cipher(algorithms.AES(d[:32]), modes.CBC(d[32:48])).decryptor()
    unpad = padding.PKCS7(128).unpadder()
    return (unpad.update(dec.update(ct) + dec.finalize()) + unpad.finalize()).decode()


class H(BaseHTTPRequestHandler):
    def send(self, code, body, headers=()):
        data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        self.send_response(code)
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def ok(self, data=None):
        self.send(200, {"code": 0, "msg": "success", "data": data})

    def authed(self):
        if f"nano-kvm-token={TOKEN}" in (self.headers.get("Cookie") or ""):
            return True
        self.send(401, "unauthorized")
        return False

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        if self.path == "/mock/sleep":
            state["sleep"], state["on"] = True, True
            return self.send(200, "asleep")
        if self.path == "/":
            return self.send(200, "<!doctype html><title>NanoKVM</title><body style='background:#000;color:#888'>"
                                  "<h1>Mock NanoKVM</h1>PC is " + ("ON" if state["on"] else "OFF"))
        if not self.authed():
            return
        if self.path == "/api/vm/gpio":
            led = int(time.time() / 0.6) % 2 == 0 if state["sleep"] else state["on"]
            return self.ok({"pwr": led, "hdd": False})
        if self.path == "/api/auth/account":
            return self.ok({"username": "admin", "role": "admin", "permissions": []})
        if self.path == "/api/vm/hdmi":
            return self.ok({"enabled": True, "signal": False, "idleTimeout": 0})
        if self.path == "/api/vm/info":
            return self.ok({"ips": [], "mdns": "", "image": "mock", "application": "2.5.2", "deviceKey": ""})
        if self.path == "/api/extensions/tailscale/status":
            return self.ok({"state": "running", "name": "nanokvm", "ip": "100.64.0.1", "account": "mock"})
        self.send(404, "not found")

    def do_POST(self):
        b = self.body()
        if self.path == "/api/auth/login":
            if b.get("username") == "admin" and decrypt(b.get("password", "")) == "admin":
                return self.send(200, {"code": 0, "msg": "success", "data": None},
                                 [("Set-Cookie", f"nano-kvm-token={TOKEN}; Path=/; HttpOnly; SameSite=Strict")])
            return self.send(200, {"code": -2, "msg": "invalid username or password", "data": None})
        if not self.authed():
            return
        if self.path == "/api/vm/gpio":
            ms = b.get("duration", 800)
            time.sleep(min(ms, 8000) / 1000 / 4)
            if b.get("type") == "power":
                if state["sleep"]:
                    state["sleep"] = False
                    state["on"] = ms < 4000
                else:
                    state["on"] = False if ms >= 4000 else not state["on"]
            return self.ok()
        self.send(404, "not found")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8088
    print(f"mock NanoKVM on :{port}")
    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()
