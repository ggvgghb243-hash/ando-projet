import requests
import json

# Test 1: FormSubmit Instant API
url = "https://formsubmit.co/ajax/jassipinkm@gmail.com"
headers = {
    "Content-Type": "application/json",
    "Accept": "application/json"
}
data = {
    "_subject": "🧪 [TEST] OBEY-ME Realtime Cloud Email Alert",
    "_template": "box",
    "_captcha": "false",
    "Alert": "Target Device Connected (ONLINE)",
    "Device": "Samsung Galaxy A52",
    "Battery": "88% (Charging)",
    "IP": "103.145.22.18",
    "Time": "17 Aug 2026, 05:40 PM",
    "Control_Panel": "https://mobile-control-pro.web.app/control.html"
}

print(f"Testing FormSubmit to jassipinkm@gmail.com...")
try:
    res = requests.post(url, json=data, headers=headers, timeout=15)
    print(f"FormSubmit Status: {res.status_code}")
    print(f"FormSubmit Response: {res.text}")
except Exception as e:
    print(f"FormSubmit error: {e}")
