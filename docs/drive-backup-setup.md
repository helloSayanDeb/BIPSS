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

Console → APIs & Services → Library → search **Google Drive API** → **Enable**.

## 2. Configure the OAuth consent screen

APIs & Services → OAuth consent screen (Google Auth Platform → Branding /
Audience / Data access):

- **User type:** External.
- **App name:** BIPSS. Add a support email and a developer contact email.
- **Data access → Add or remove scopes:** add
  `https://www.googleapis.com/auth/drive.appdata` ("See, create, and delete
  its own configuration data in your Google Drive").
- **Audience → Test users:** add every Google account that will test backup,
  such as your own. While the app is in "Testing", only these accounts can
  grant access. Publishing to production needs Google's verification.

## 3. Create the Android OAuth client

APIs & Services → Credentials → **Create credentials → OAuth client ID**:

- **Application type:** Android.
- **Package name:** `com.bipss.app`.
- **SHA-1 certificate fingerprint:** the certificate that signs the build
  being tested.
  - Debug builds on this machine (`~/.android/debug.keystore`):

    ```
    0E:EF:C4:9D:CF:D5:5A:1C:CE:7F:32:7E:A6:76:4C:43:D6:84:F2:04
    ```

    Reproduce it with:

    ```
    keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
    ```

  - A release build needs its own client with the release key's SHA-1. For
    Play App Signing, use the SHA-1 from Play Console → App integrity.

No client ID or secret goes into the app. Android clients are matched by
package name and certificate.

## 4. Check it end to end

1. In BIPSS: Settings → Privacy & data → Cloud backup on → **Back up now**.
   Google's consent screen appears once; allow it. The status line then lists
   what was backed up.
2. Uninstall BIPSS, reinstall, and complete onboarding.
3. Settings → Cloud backup → **Restore**. The status line lists what came
   back. Contacts are not duplicated, and newer local data is kept.

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
  environment history, SOS history. These stay on the phone that recorded
  them.

Backups made before schema 2 still restore. Their old "HRV" baseline key is
read as PRV.

The optional **data sharing** (Firebase) is separate and is never a backup:
nothing is ever restored from it.
