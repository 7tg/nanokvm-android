#!/usr/bin/env python3
"""Fake Tapo P100 plugs for emulator development: UDP discovery on 20002 and KLAP v2 over HTTP.

Run: python3 tools/mock_tapo.py   (plugs on HTTP ports 8091 and 8092, discovery on UDP 20002)
TP-Link login: test@example.com / tapo. In the emulator the host is 10.0.2.2; broadcasts don't reach it, so
seed a plug at 10.0.2.2 (see README) and the app's discovery asks it directly.
"""
import hashlib, json, os, socket, struct, threading, base64, zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

EMAIL, PASSWORD = "test@example.com", "tapo"
AUTH = hashlib.sha256(hashlib.sha1(EMAIL.encode()).digest() + hashlib.sha1(PASSWORD.encode()).digest()).digest()
PLUGS = {8091: {"name": "Speakers", "mac": "AA-BB-CC-00-00-01", "on": True},
         8092: {"name": "Monitor", "mac": "AA-BB-CC-00-00-02", "on": False}}
HOST_IP = os.environ.get("MOCK_IP", "10.0.2.2")
sha = lambda b: hashlib.sha256(b).digest()


def aes(key, iv, data, enc):
    c = Cipher(algorithms.AES(key), modes.CBC(iv))
    if enc:
        p = padding.PKCS7(128).padder()
        e = c.encryptor()
        return e.update(p.update(data) + p.finalize()) + e.finalize()
    d = c.decryptor()
    u = padding.PKCS7(128).unpadder()
    return u.update(d.update(data) + d.finalize()) + u.finalize()


def handler_for(port):
    plug = PLUGS[port]
    sessions = {}

    class H(BaseHTTPRequestHandler):
        def reply(self, code, body=b"", cookie=None):
            self.send_response(code)
            if cookie:
                self.send_header("Set-Cookie", f"TP_SESSIONID={cookie};TIMEOUT=86400")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            data = self.rfile.read(int(self.headers.get("Content-Length") or 0))
            sid = (self.headers.get("Cookie") or "").split("TP_SESSIONID=")[-1].split(";")[0]
            path = self.path.split("?")[0]
            if path == "/app/handshake1":
                remote = os.urandom(16)
                sid = os.urandom(8).hex()
                sessions[sid] = (data, remote)
                return self.reply(200, remote + sha(data + remote + AUTH), sid)
            if sid not in sessions:
                return self.reply(403)
            local, remote = sessions[sid]
            if path == "/app/handshake2":
                return self.reply(200 if data == sha(remote + local + AUTH) else 403)
            if path == "/app/request":
                seq = int(self.path.split("seq=")[1])
                seed = local + remote + AUTH
                key = sha(b"lsk" + seed)[:16]
                iv = sha(b"iv" + seed)[:12] + struct.pack(">i", seq)
                sig = sha(b"ldk" + seed)[:28]
                if data[:32] != sha(sig + struct.pack(">i", seq) + data[32:]):
                    return self.reply(400)
                req = json.loads(aes(key, iv, data[32:], False))
                if req["method"] == "set_device_info":
                    plug["on"] = req["params"]["device_on"]
                    print(f"{plug['name']} -> {'on' if plug['on'] else 'off'}")
                    res = {"error_code": 0}
                else:
                    res = {"error_code": 0, "result": {"device_on": plug["on"], "model": "P100",
                                                         "nickname": base64.b64encode(plug["name"].encode()).decode()}}
                enc = aes(key, iv, json.dumps(res).encode(), True)
                return self.reply(200, sha(sig + struct.pack(">i", seq) + enc) + enc)
            self.reply(404)

        def log_message(self, format, *args):
            pass

    return H


def discovery():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("0.0.0.0", 20002))
    while True:
        data, addr = s.recvfrom(4096)
        if len(data) < 16 or data[0] != 2:
            continue
        for port, plug in PLUGS.items():
            body = json.dumps({"error_code": 0, "result": {
                "device_type": "SMART.TAPOPLUG", "device_model": "P100(EU)", "ip": HOST_IP, "mac": plug["mac"],
                "mgt_encrypt_schm": {"is_support_https": False, "encrypt_type": "KLAP", "http_port": port, "lv": 2}}}).encode()
            hdr = struct.pack(">BBHHBBII", 2, 0, 1, len(body), 17, 0, 0, 0x5A6B7C8D)
            pkt = bytearray(hdr + body)
            pkt[12:16] = struct.pack(">I", zlib.crc32(pkt) & 0xFFFFFFFF)
            s.sendto(bytes(pkt), addr)


if __name__ == "__main__":
    for port in PLUGS:
        threading.Thread(target=ThreadingHTTPServer(("0.0.0.0", port), handler_for(port)).serve_forever, daemon=True).start()
    print("mock Tapo plugs on", list(PLUGS), "discovery on UDP 20002")
    discovery()
