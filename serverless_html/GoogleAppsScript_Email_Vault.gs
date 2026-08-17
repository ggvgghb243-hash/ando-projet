/**
 * =========================================================================
 * OBEY-ME COMPLETE GOOGLE APPS SCRIPT BACKEND (DRIVE VAULT + EMAIL DISPATCHER)
 * =========================================================================
 * Instructions:
 * 1. Open https://script.google.com/
 * 2. Open your existing project or create a new script.
 * 3. Replace all code with this script.
 * 4. Click 'Deploy' > 'New Deployment' > Type: 'Web App'
 *    - Execute as: 'Me (your email)'
 *    - Who has access: 'Anyone'
 * 5. Copy the Web App URL and set as your Webhook URL.
 * =========================================================================
 */

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return ContentService.createTextOutput(JSON.stringify({
        status: "error",
        message: "No POST payload received"
      })).setMimeType(ContentService.MimeType.JSON);
    }

    var data = JSON.parse(e.postData.contents);

    // =========================================================================
    // ACTION 1: SEND AUTOMATED HTML EMAIL NOTIFICATION
    // =========================================================================
    if (data.action === "send_email" || (data.to && data.subject)) {
      var recipient = data.to;
      var subject = data.subject || "OBEY-ME System Alert";
      var htmlBody = data.htmlBody || data.body || ("<p>" + (data.message || "Alert received") + "</p>");
      var plainBody = htmlBody.replace(/<[^>]*>?/gm, '');

      MailApp.sendEmail({
        to: recipient,
        subject: subject,
        body: plainBody,
        htmlBody: htmlBody,
        name: "OBEY-ME Command Center"
      });

      return ContentService.createTextOutput(JSON.stringify({
        status: "success",
        action: "email_sent",
        recipient: recipient,
        quotaRemaining: MailApp.getRemainingDailyQuota()
      })).setMimeType(ContentService.MimeType.JSON);
    }

    // =========================================================================
    // ACTION 2: GOOGLE DRIVE FILE / VAULT UPLOAD
    // =========================================================================
    var fileName = data.fileName || ("Upload_" + new Date().getTime());
    var base64Data = data.base64Data || data.data || "";
    var folderName = data.folderName || "OBEYME_Cloud_Vault";

    if (base64Data.indexOf(",") > -1) {
      base64Data = base64Data.split(",")[1];
    }

    var decoded = Utilities.base64Decode(base64Data);
    var mimeType = getMimeTypeFromFilename(fileName);
    var blob = Utilities.newBlob(decoded, mimeType, fileName);

    // Find or create destination folder in Google Drive
    var folder;
    var folders = DriveApp.getFoldersByName(folderName);
    if (folders.hasNext()) {
      folder = folders.next();
    } else {
      folder = DriveApp.createFolder(folderName);
    }

    var file = folder.createFile(blob);
    file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);

    var fileId = file.getId();
    var viewUrl = "https://drive.google.com/file/d/" + fileId + "/view";
    var directUrl = "https://lh3.googleusercontent.com/d/" + fileId;

    return ContentService.createTextOutput(JSON.stringify({
      status: "success",
      action: "drive_upload",
      fileId: fileId,
      fileName: fileName,
      driveUrl: viewUrl,
      directUrl: directUrl
    })).setMimeType(ContentService.MimeType.JSON);

  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({
      status: "error",
      error: err.toString()
    })).setMimeType(ContentService.MimeType.JSON);
  }
}

function doGet(e) {
  return ContentService.createTextOutput(JSON.stringify({
    status: "active",
    service: "OBEY-ME Google Apps Script Gateway",
    version: "2.0",
    mailQuotaRemaining: MailApp.getRemainingDailyQuota(),
    timestamp: new Date().toISOString()
  })).setMimeType(ContentService.MimeType.JSON);
}

function getMimeTypeFromFilename(fileName) {
  var ext = fileName.split('.').pop().toLowerCase();
  var mimeTypes = {
    'jpg': 'image/jpeg',
    'jpeg': 'image/jpeg',
    'png': 'image/png',
    'webp': 'image/webp',
    'gif': 'image/gif',
    'mp4': 'video/mp4',
    'mp3': 'audio/mpeg',
    'wav': 'audio/wav',
    'm4a': 'audio/m4a',
    'pdf': 'application/pdf',
    'apk': 'application/vnd.android.package-archive',
    'txt': 'text/plain',
    'json': 'application/json'
  };
  return mimeTypes[ext] || 'application/octet-stream';
}
