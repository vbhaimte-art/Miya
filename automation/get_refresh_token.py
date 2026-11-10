#!/usr/bin/env python3
"""
Ek baar chalane wali script: YouTube comments ke liye refresh token banati hai.
Termux me chalao:   python get_refresh_token.py
Phone ke browser me wahi link kholo jo ye print karegi. Sirf standard library, kuch install nahi karna.
"""
import http.server
import json
import os
import secrets
import subprocess
import sys
import urllib.parse
import urllib.request

PORT = 8765
REDIRECT = "http://127.0.0.1:%d/" % PORT
SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl"  # comments padhna aur reply karna

client_id = os.environ.get("YT_CLIENT_ID") or input("YT_CLIENT_ID: ").strip()
client_secret = os.environ.get("YT_CLIENT_SECRET") or input("YT_CLIENT_SECRET: ").strip()
state = secrets.token_urlsafe(16)

url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
    "client_id": client_id,
    "redirect_uri": REDIRECT,
    "response_type": "code",
    "scope": SCOPE,
    "access_type": "offline",
    "prompt": "consent",
    "state": state,
})

result = {}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        if q.get("state", [""])[0] == state and "code" in q:
            result["code"] = q["code"][0]
            msg = "Ho gaya! Ab Termux me wapas jao."
        else:
            result["error"] = q.get("error", ["galat ya adhoora jawab"])[0]
            msg = "Kuch gadbad hui: " + result["error"]
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(msg.encode("utf-8"))

    def log_message(self, *args):
        pass


print("\nYe link phone ke browser me kholo aur apna YouTube channel wala Google account chuno:\n")
print(url + "\n")
try:
    subprocess.run(["termux-open-url", url], check=False, capture_output=True)  # Termux me seedha khol deta hai
except FileNotFoundError:
    pass

server = http.server.HTTPServer(("127.0.0.1", PORT), Handler)
while not result:
    server.handle_request()

if "code" not in result:
    sys.exit("❌ Login nahi hua: " + result.get("error", ""))

data = urllib.parse.urlencode({
    "code": result["code"],
    "client_id": client_id,
    "client_secret": client_secret,
    "redirect_uri": REDIRECT,
    "grant_type": "authorization_code",
}).encode()
try:
    with urllib.request.urlopen(urllib.request.Request("https://oauth2.googleapis.com/token", data=data), timeout=30) as r:
        tok = json.load(r)
except Exception as e:
    sys.exit("❌ Token nahi mila: %s" % type(e).__name__)

if "refresh_token" not in tok:
    sys.exit("❌ Refresh token nahi aaya. Google account ke security page se Miya app ka access hata kar dobara try karo.")

print("\n✅ Ye aapka YT_REFRESH_TOKEN hai (kisi ko mat dikhana, sirf GitHub Secret me daalna):\n")
print(tok["refresh_token"] + "\n")
