import urllib.request, json

token = "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"
repo = "ggvgghb243-hash/ando-projet"

payload = {
    "ref": "main",
    "inputs": {
        "app_name": "MyChat",
        "package_name": "com.mychat.service",
        "webview_url": "https://google.com",
        "user_id": "master01",
        "build_type": "main",
        "app_icon_b64": ""
    }
}

req = urllib.request.Request(
    f"https://api.github.com/repos/{repo}/actions/workflows/build.yml/dispatches",
    data=json.dumps(payload).encode("utf-8"),
    headers={
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0"
    },
    method="POST"
)

res = urllib.request.urlopen(req)
print(f"Workflow dispatch: HTTP {res.status} - SUCCESS!")
