import os, sys, base64, urllib.parse, shutil
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
ROOT = sys.argv[2]; PORT = int(sys.argv[1])
os.makedirs(os.path.join(ROOT, "我的坚果云"), exist_ok=True)
EXPECT = "Basic " + base64.b64encode("a@b.c:secretpw".encode()).decode()
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def local(self):
        p = urllib.parse.unquote(self.path.split("?")[0])
        assert p.startswith("/dav/"), p
        return os.path.join(ROOT, p[5:].rstrip("/")), p
    def reply(self, code, body=b"", ctype="application/xml", chunked=False):
        self.send_response(code)
        if body or code == 207:
            self.send_header("Content-Type", ctype)
        if chunked and body:
            self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
            for i in range(0, len(body), 5000):
                c = body[i:i+5000]; self.wfile.write(b"%x\r\n" % len(c) + c + b"\r\n")
            self.wfile.write(b"0\r\n\r\n")
        else:
            self.send_header("Content-Length", str(len(body))); self.send_header("Connection", "close"); self.end_headers(); self.wfile.write(body)
    def auth(self):
        if self.headers.get("Authorization") != EXPECT:
            self.reply(401, b"no"); return False
        return True
    def read_body(self):
        n = int(self.headers.get("Content-Length", 0)); return self.rfile.read(n) if n else b""
    def do_PROPFIND(self):
        body = self.read_body()
        if not self.auth(): return
        path, url = self.local()
        if url == "/dav/": path = ROOT
        if not os.path.exists(path): return self.reply(404, b"")
        depth = self.headers.get("Depth")
        def one(p, u):
            if os.path.isdir(p):
                return f"<d:response><d:href>{urllib.parse.quote(u.rstrip('/'))}/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
            return f"<d:response><d:href>{urllib.parse.quote(u)}</d:href><d:propstat><d:prop><d:getcontentlength>{os.path.getsize(p)}</d:getcontentlength><d:resourcetype/></d:prop></d:propstat></d:response>"
        x = '<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">' + one(path, url)
        if depth == "1" and os.path.isdir(path):
            for n in sorted(os.listdir(path)):
                x += one(os.path.join(path, n), url.rstrip("/") + "/" + n)
        self.reply(207, (x + "</d:multistatus>").encode())
    def do_MKCOL(self):
        if not self.auth(): return
        path, url = self.local()
        if os.path.exists(path): return self.reply(405)
        if not os.path.isdir(os.path.dirname(path)) or os.path.dirname(path) == ROOT.rstrip("/") and False: return self.reply(409)
        if os.path.dirname(path) == ROOT.rstrip("/") : return self.reply(403)   # 模拟:根目录不能随便建
        os.mkdir(path); self.reply(201)
    def do_PUT(self):
        body = self.read_body()
        if not self.auth(): return
        path, url = self.local()
        if not os.path.isdir(os.path.dirname(path)) or os.path.dirname(path) == ROOT.rstrip("/"): return self.reply(409)
        existed = os.path.exists(path)
        open(path, "wb").write(body); self.reply(204 if existed else 201)
    def do_GET(self):
        if not self.auth(): return
        path, url = self.local()
        if not os.path.isfile(path): return self.reply(404)
        self.reply(200, open(path, "rb").read(), "application/octet-stream", chunked=path.endswith("chunk.bin"))
    def do_DELETE(self):
        if not self.auth(): return
        path, url = self.local()
        if not os.path.exists(path): return self.reply(404)
        os.remove(path); self.reply(204)
ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
