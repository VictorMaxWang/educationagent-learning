"""仅向本机 Chrome 提供归档 HTML，禁止原网页脚本与外部网络资源。"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit
import re

LIBRARY = Path(__file__).resolve().parents[1] / '团队成果参考库'

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = unquote(urlsplit(self.path).path)
        if not re.fullmatch(r'/CN\d+[ABU]/原文\.html', path):
            self.send_error(404)
            return
        source = LIBRARY / path.lstrip('/')
        if not source.is_file():
            self.send_error(404)
            return
        content = source.read_bytes()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(content)))
        self.send_header('Content-Security-Policy', "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(content)

if __name__ == '__main__':
    print('Reference HTML ready on http://127.0.0.1:18891', flush=True)
    ThreadingHTTPServer(('127.0.0.1', 18891), Handler).serve_forever()
