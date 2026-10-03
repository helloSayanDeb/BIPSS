# Drive backup set-up (one-time, in Google Cloud Console)

Drive backup uses Google Identity's AuthorizationClient with the
`drive.appdata` scope. That scope lets BIPSS use a hidden folder in the
user's own Drive that only BIPSS can read. Google grants it only to an app
whose package name and signing certificate are registered as an **Android
OAuth client** in a Google Cloud project that has the Drive API enabled.
Until that client exists, the app says "Drive backup isn't set up for this
build yet", which is Google's DEVELOPER_ERROR.

Use the project the app already belongs to: **bipss-sih2026**, the Firebase
project in `app/google-services.json`.

## 1. Enable the Drive API

[Open the Drive API page for bipss-sih2026](https://console.cloud.google.com/apis/library/drive.googleapis.com?project=bipss-sih2026) → **Enable**.

## 2. Configure the OAuth consent screen

[Open Google Auth Platform](https://console.cloud.google.com/auth/overview?project=bipss-sih2026). Go to Branding /
Audience / Data access):

- **User type:** External.
- **App name:** BIPSS. Add a support email and a developer contact email.
- **Data access → Add or remove scopes:** add
  `https://www.googleapis.com/auth/drive.appdata` ("See, create, and delete
  its own configuration data in your Google Drive").
- **Audience → Test users:** add every Google account that will test backup,
  such as your own. While the app is in "Testing", only these accounts can
  grant access. Follow the console's publishing requirements. The `drive.appdata`
  scope is non-sensitive; do not request broad Drive access for this feature.

## 3. Create the Android OAuth client

[Open OAuth clients](https://console.cloud.google.com/auth/clients?project=bipss-sih2026) → **Create client**:

- **Application type:** Android.
- **Package name:** `com.bipss.app`.
- **SHA-1 certificate fingerprint:** the certificate that signs the build
  being tested.
  - Debug builds on this machine (`~/.android/debug.keystore`), verified 2 October 2026:

    ```
    0E:EF:C4:9D:CF:D5:5A:1C:CE:7F:32:7E:A6:76:4C:43:D6:84:F2:04
    ```

    Reproduce it with:

    ```
    keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
    ```

  - A release build needs its own client with the release key's SHA-1. For
    Play App Signing, use the SHA-1 from Play Console → App integrity.

No client ID or secret goes into this on-device flow. Android clients are matched by
package name and certificate. The app does not request a server authorization code;
there is no backend secret to configure. Do not send a private signing key, password,
access token or client secret to Codex. After setup, just say the registration is complete.

## 4. Check it end to end

1. In BIPSS: Settings → Privacy & data → **Connect Google Drive**.
   Choose your test account and grant the requested app-folder access. This action
   does not upload or restore anything. The status reports access granted only
   after Google returns an access token.
2. Turn Cloud backup on, read the listed contents, then choose **Back up now**.
   A successful upload updates the last-backup time. Authorization success alone
   does not prove the Drive API is enabled or that a backup was written.
3. For a restore check, use a spare test installation with the same registered
   signing certificate and Google account. Choose **Restore** and review the result.
   Keep the original installation and its local records until recovery is verified.

## Troubleshooting

- **DEVELOPER_ERROR / not set up for this build:** verify the Android client uses
  exactly `com.bipss.app` and the fingerprint above. A release/Play build has a
  different signing fingerprint and needs its own matching registration.
- **Access blocked / testing:** add the selected account under Audience → Test users.
- **Access not allowed:** retry Connect and complete Google's consent screen.
- **Upload HTTP 403 after connection succeeds:** verify Drive API is enabled in
  the same project as the Android OAuth client; check the returned error details.
- **No Google account/services:** use a device or Google Play emulator with Google
  Play services and a signed-in Google account.

References: [Android authorization](https://developer.android.com/identity/authorization),
[Drive app-data scope](https://developers.google.com/workspace/drive/api/guides/appdata).

## What is and isn't in the backup

- **In it (schema 2):**
  - Profile, including gender.
  - Theme.
  - Emergency contacts.
  - Baselines: the nightly recovery baselines and the calendar days they
    count.
  - Water log.
  - Period log and day notes.
- **Not in it:** raw sensor readings, sleep sessions, signal epochs,
  environment history, SOS history, band step history, activity/food logs,
  daily score snapshots, sleep routine and weekly habits. These stay on the phone
  that recorded them. This continuation does not broaden the backup payload.

Backups made before schema 2 still restore. Their old "HRV" baseline key is
read as PRV.

The optional **data sharing** (Firebase) is separate and is never a backup:
nothing is ever restored from it.
