import urllib.request, urllib.error, json

token = "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"

# Test 1: Check user
print("=== Test 1: Check /user ===")
try:
    req = urllib.request.Request(
        "https://api.github.com/user",
        headers={"Authorization": f"token {token}", "User-Agent": "Mozilla/5.0"}
    )
    res = urllib.request.urlopen(req)
    data = json.loads(res.read().decode())
    print(f"OK! Logged in as: {data.get('login')}")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"HTTP {e.code}: {body[:500]}")

# Test 2: Check release assets
print("\n=== Test 2: Check Release v1.0 Assets ===")
try:
    req = urllib.request.Request(
        "https://api.github.com/repos/ggvgghb243-hash/ando-projet/releases/tags/v1.0",
        headers={"Authorization": f"token {token}", "User-Agent": "Mozilla/5.0"}
    )
    res = urllib.request.urlopen(req)
    data = json.loads(res.read().decode())
    assets = data.get("assets", [])
    print(f"Release ID: {data.get('id')}")
    print(f"Total assets: {len(assets)}")
    for a in assets:
        print(f"  - {a['name']} ({a['size']} bytes) => {a['browser_download_url']}")
    if not assets:
        print("  NO APK FILES UPLOADED TO THIS RELEASE YET!")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"HTTP {e.code}: {body[:500]}")

# Test 3: Check if repo is public/private
print("\n=== Test 3: Repo visibility ===")
try:
    req = urllib.request.Request(
        "https://api.github.com/repos/ggvgghb243-hash/ando-projet",
        headers={"Authorization": f"token {token}", "User-Agent": "Mozilla/5.0"}
    )
    res = urllib.request.urlopen(req)
    data = json.loads(res.read().decode())
    print(f"Private: {data.get('private')}")
    print(f"Visibility: {data.get('visibility')}")
except urllib.error.HTTPError as e:
    body = e.read().decode()
    print(f"HTTP {e.code}: {body[:500]}")
