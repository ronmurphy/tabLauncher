"""A stand-in for Android's "Pair device with pairing code", for testing TabDesk's pairing code on its own.

It speaks Android's wireless-debugging pairing protocol as the tablet does (TLS 1.3, then SPAKE2 over the
pairing code, then an AES-GCM exchange of keys), with its own SPAKE2 written to match BoringSSL's exactly, so:
  * `adb pair 127.0.0.1:<port> <code>` against it checks this SPAKE2 against BoringSSL's (adb's own), and
  * TabDesk pairing against it checks TabDesk's, without opening the dialog on the tablet.

    python3 -m venv venv && venv/bin/pip install pyopenssl
    venv/bin/python tools/pairing/fake_pair_server.py [port] [code]      (default 37111, 123456)

It accepts one pairing, prints the key the other side sent, and exits. Nothing is stored.
"""
import hashlib, os, socket, struct, subprocess, sys, tempfile

from OpenSSL import SSL
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

# ---- edwards25519, plainly (slow but exact) ----
P = 2**255 - 19
D = -121665 * pow(121666, P - 2, P) % P
L = 2**252 + 27742317777372353535851937790883648493
I = pow(2, (P - 1) // 4, P)  # sqrt(-1)

def inv(x): return pow(x, P - 2, P)

def add(a, b):
    (x1, y1), (x2, y2) = a, b
    t = D * x1 * x2 * y1 * y2 % P
    return ((x1 * y2 + x2 * y1) * inv(1 + t) % P, (y1 * y2 + x1 * x2) * inv(1 - t) % P)

def neg(a): return ((-a[0]) % P, a[1])

def mul(k, pt):
    r = (0, 1)
    while k:
        if k & 1: r = add(r, pt)
        pt = add(pt, pt); k >>= 1
    return r

def decode(s):
    y = int.from_bytes(s, "little") & ((1 << 255) - 1)
    sign = s[31] >> 7
    u, v = (y * y - 1) % P, (D * y * y + 1) % P
    x = pow(u * inv(v), (P + 3) // 8, P)
    if (x * x - u * inv(v)) % P: x = x * I % P
    if (x * x - u * inv(v)) % P: raise ValueError("not on the curve")
    if x & 1 != sign: x = (-x) % P
    return (x, y)

def encode(pt):
    x, y = pt
    return (y | ((x & 1) << 255)).to_bytes(32, "little")

BASE = decode((4 * inv(5) % P).to_bytes(32, "little"))
N = decode(bytes.fromhex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))
M = decode(bytes.fromhex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
assert N[0] == 49918732221787544735331783592030787422991506689877079631459872391322455579424
assert M[1] == 21177308356423958466833845032658859666296341766942662650232962324899758529114

class Spake2:
    """BoringSSL's SPAKE2 (spake25519.cc), including its password-scalar fix. alice = adb's client."""
    def __init__(self, alice, my_name, their_name, password):
        self.alice, self.my_name, self.their_name = alice, my_name, their_name
        self.private = (int.from_bytes(os.urandom(64), "little") % L) * 8
        self.pw_hash = hashlib.sha512(password).digest()
        s = int.from_bytes(self.pw_hash, "little") % L
        for bit in range(3):                       # add l, 2l, 4l as needed so s is a multiple of 8
            if s & (1 << bit): s += L << bit
        self.pw_scalar = s
        self.msg = encode(add(mul(self.private, BASE), mul(s, M if alice else N)))

    def finish(self, their_msg):
        q = add(decode(their_msg), neg(mul(self.pw_scalar, N if self.alice else M)))
        shared = encode(mul(self.private, q))
        h = hashlib.sha512()
        def put(b): h.update(struct.pack("<Q", len(b))); h.update(b)
        if self.alice: parts = [self.my_name, self.their_name, self.msg, their_msg]
        else: parts = [self.their_name, self.my_name, their_msg, self.msg]
        for b in parts + [shared, self.pw_hash]: put(b)
        return h.digest()

class Cipher:
    def __init__(self, key_material):
        key = HKDF(hashes.SHA256(), 16, None, b"adb pairing_auth aes-128-gcm key").derive(key_material)
        self.gcm, self.enc, self.dec = AESGCM(key), 0, 0
    def seal(self, data):
        out = self.gcm.encrypt(struct.pack("<Q", self.enc) + bytes(4), data, None); self.enc += 1; return out
    def open(self, data):
        out = self.gcm.decrypt(struct.pack("<Q", self.dec) + bytes(4), data, None); self.dec += 1; return out

CLIENT, SERVER = b"adb pair client\0", b"adb pair server\0"
SPAKE2_MSG, PEER_INFO = 0, 1

def read_exact(conn, n):
    buf = b""
    while len(buf) < n:
        try: chunk = conn.recv(n - len(buf))
        except SSL.WantReadError: continue
        if not chunk: raise EOFError("connection closed")
        buf += chunk
    return buf

def read_packet(conn, want):
    version, kind, size = struct.unpack(">BBI", read_exact(conn, 6))
    assert version == 1 and kind == want, (version, kind)
    return read_exact(conn, size)

def send_packet(conn, kind, payload):
    conn.sendall(struct.pack(">BBI", 1, kind, len(payload)) + payload)

def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 37111
    code = (sys.argv[2] if len(sys.argv) > 2 else "123456").encode()
    tmp = tempfile.mkdtemp()
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=fake-adbd",
                    "-keyout", f"{tmp}/k.pem", "-out", f"{tmp}/c.pem", "-days", "1"], check=True, capture_output=True)
    ctx = SSL.Context(SSL.TLS_METHOD)
    ctx.set_min_proto_version(SSL.TLS1_3_VERSION)
    ctx.use_privatekey_file(f"{tmp}/k.pem"); ctx.use_certificate_file(f"{tmp}/c.pem")
    ctx.set_verify(SSL.VERIFY_PEER | SSL.VERIFY_FAIL_IF_NO_PEER_CERT, lambda *a: True)
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port)); srv.listen(1)
    print(f"waiting on port {port}, code {code.decode()}", flush=True)
    raw, addr = srv.accept()
    conn = SSL.Connection(ctx, raw); conn.set_accept_state(); conn.do_handshake()
    print(f"TLS from {addr[0]}: {conn.get_protocol_version_name()}, client cert "
          f"{conn.get_peer_certificate().get_subject().CN if conn.get_peer_certificate() else None}", flush=True)
    exported = conn.export_keying_material(b"adb-label\0", 64)
    spake = Spake2(False, SERVER, CLIENT, code + exported)
    their = read_packet(conn, SPAKE2_MSG)
    send_packet(conn, SPAKE2_MSG, spake.msg)
    cipher = Cipher(spake.finish(their))
    try:
        info = cipher.open(read_packet(conn, PEER_INFO))
    except Exception:
        print("FAILED: couldn't decrypt their key (wrong code, or the SPAKE2 doesn't match)"); return 1
    assert len(info) == 8192 and info[0] == 0, (len(info), info[0])
    print("their key:", info[1:].split(b"\0")[0].decode())
    guid = b"\x01" + b"adb-FAKE00000-test".ljust(8191, b"\0")
    send_packet(conn, PEER_INFO, cipher.seal(guid))
    conn.shutdown(); raw.close()
    print("paired OK")

if __name__ == "__main__":
    sys.exit(main())
