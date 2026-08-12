import urllib.request, json

token = "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"
req = urllib.request.Request(
    "https://api.github.com/repos/ggvgghb243-hash/ando-projet/releases/tags/v1.0",
    headers={"Authorization": f"token {token}", "User-Agent": "Mozilla/5.0"}
)
res = urllib.request.urlopen(req)
d = json.loads(res.read().decode())
assets = d.get("assets", [])
print(f"Release ID: {d.get('id')}")
print(f"Total assets: {len(assets)}")
for a in assets:
    print(f"  - {a['name']} ({a['size']} bytes)")

# Also check what last_apk is stored in Firebase
req2 = urllib.request.Request(
    "https://its-free-pay-for-premium-acces-default-rtdb.asia-southeast1.firebasedatabase.app/panel_users/master01.json",
    headers={"User-Agent": "Mozilla/5.0"}
)
res2 = urllib.request.urlopen(req2)
d2 = json.loads(res2.read().decode())
print(f"\nFirebase last_apk: {d2.get('last_apk', 'NOT SET')}")
print(f"Firebase build_status: {d2.get('build_status', 'NOT SET')}")
