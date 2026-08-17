import requests
import json

webhook_url = "https://script.google.com/macros/s/AKfycbxXY04auYkCIwzpCkhYzanzVLtzagGhbflSdctjrZee10U5T8d7FbhIa6T41hQNTm4E/exec"

payload = {
    "action": "send_email",
    "to": "jassipinkm@gmail.com",
    "subject": "🧪 [TEST] OBEY-ME Automated Cloud Email Verification",
    "htmlBody": """
    <div style="max-width:560px;margin:0 auto;background:#121217;border-radius:20px;border:1px solid rgba(255,255,255,0.1);padding:24px;font-family:sans-serif;color:#ffffff;">
        <h2 style="color:#30d158;margin-top:0;">OBEY-ME Live Email Test</h2>
        <p style="color:#a1a1a6;">Your automated cloud email delivery system is connected and working!</p>
        <p style="color:#86868b;font-size:12px;">Recipient: jassipinkm@gmail.com</p>
    </div>
    """
}

print(f"Sending POST to {webhook_url}...")
try:
    resp = requests.post(webhook_url, json=payload, timeout=20, allow_redirects=True)
    print(f"HTTP Status: {resp.status_code}")
    print(f"Response Text: {resp.text}")
except Exception as e:
    print(f"Error: {e}")
