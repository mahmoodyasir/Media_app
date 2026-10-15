# Media App

Media App is a native Android gallery with its own video player. Photos and videos open in the app, including files other apps hand over with a view intent. A locked vault encrypts selected files so the system gallery and file managers can no longer see them.

Package `com.mediaapp`, version 1.2.0. Minimum SDK 26. Compile SDK is Android 17 (API 37, minor 2). Target SDK is 37.

## What phase 1 includes

- Library from MediaStore, with a content observer so new files show up. Android 13+ uses `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO`. Android 14+ also handles partial access. Older releases use `READ_EXTERNAL_STORAGE`.
- Source folders from relative path, bucket, and filename. Built-in sources include Camera, Screenshots, Screen recordings, Downloads, WhatsApp, Facebook, Messenger, Instagram, Telegram, Snapchat, TikTok, Twitter/X, Bluetooth, and Edited. Anything else becomes its own folder. The rules table is unit tested.
- All / Images / Videos inside the library and each folder. Day groups use the date taken, then date added, then date modified. Pinch the grid to move between 2 and 6 columns.
- Full-screen viewer with a shared-element transition, pinch and double-tap zoom for photos, and inline video. The info sheet shows size, resolution, date, path, and source.
- Media3 player with horizontal-swipe seek, brightness on the left edge, volume on the right edge, double-tap to skip 10 seconds, playback speed, aspect modes, rotation lock, audio and subtitle tracks, an external `.srt` picker, picture-in-picture, resume position, and a media session for lock-screen controls.
- The app can be the default handler for `image/*` and `video/*` view intents.
- Recycle bin. Android 11+ uses MediaStore trash (about 30 days, with the system consent dialog). Older versions use an app-managed bin. Multi-select supports delete and share. Photos can be set as wallpaper.
- Dark theme by default, optional light theme, and Material You dynamic color on Android 12+.
- Photo editor and video editor, opened from Edit in the viewer (including locked items, not the recycle bin).

## Locked vault

Lock a whole folder or a multi-selection of photos and videos.

1. File bytes are encrypted with Tink streaming AES-256-GCM (`AES256_GCM_HKDF_4KB`) into app-private storage (`filesDir/vault`).
2. The keyset is wrapped by a non-exportable Android Keystore master key.
3. Original name, relative path, source folder, date, MIME type, dimensions, and duration are stored in an encrypted index so a file can be put back.
4. The original is deleted through MediaStore. On Android 11 and later the system shows a delete consent dialog. If you decline, the encrypted copy is discarded and the original stays.
5. Playback decrypts in memory. Video uses a Media3 `DataSource` over a seekable decrypting channel. No plaintext copy is written to shared storage. A share sheet may use an app-private cache file, which is deleted when the vault locks.

The Locked tab asks for an app PIN (at least 4 digits) or a pattern (at least 4 dots). Biometric unlock is optional and only gates entry. Five failures impose a 30 second wait. Vault screens set `FLAG_SECURE`. Leaving the app locks the vault again.

Choosing “also lock new files that arrive in this folder” remembers the folder. New files are not deleted silently. Android 11+ requires a user consent dialog, so the library shows a banner and you confirm the lock.

**Uninstalling Media App or clearing its data destroys locked media.** The key never leaves Android Keystore, and there is no encrypted export. That is a known limitation.

Backup and device transfer exclude vault files, the recycle bin, and the keyset preferences. `android:allowBackup` is false.

## Photo editor

Open a photo and tap Edit.

- Filters: None, Vivid, Mono, Warm, Cool, Vintage, Soft, Sharp, Fade. The preview uses a color matrix. Sharp also runs a 3×3 convolution.
- Crop: Free, 1:1, 4:3, 16:9, Original, plus rotate and flip. Drag the photo to pan. The slider tightens the frame.
- Text and shapes (rectangle, circle, line, arrow) with color, size or stroke, and drag. Undo and redo keep the last 40 steps.
- Collage templates: two side by side, two stacked, three, and a four-up grid. Pick photos from the library. The current filter is applied on export.
- Save writes a new JPEG in `Pictures/Media App` by default. Overwrite replaces the original (Android shows a consent dialog when it must). Save to vault always encrypts a new locked copy. Editing a locked photo can also replace that locked original.

## Video editor

Open a video and tap Edit. Export uses Media3 Transformer (H.264 / AAC) into `Movies/Media App`.

- Trim the start and end of each clip on a timeline. The preview scrubs the selected clip.
- Crop to Original, 1:1, 4:3, or 16:9.
- Merge extra clips in order. Add, remove, and reorder them.
- The same filter names as the photo editor, mapped to Media3 effects.
- Remove the original audio, or replace it with a picked file that plays over a time range. The replacement covers the whole export; the timeline is silent before the range starts.
- Text and shape overlays on the whole timeline or a selected range. Drag them on the preview. They are burned in on export.
- A progress dialog can cancel the export.
- Save targets match the photo editor: new file, overwrite (or replace the locked original), or save another copy into the vault.

A locked video is decrypted into `cacheDir/editor` for Transformer, which cannot read the vault stream. That file is deleted when you leave the editor.

## Build

Install a JDK 17+, Android SDK platform `android-37.2`, and build-tools 37.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The release build is minified with R8 and signed with the debug keystore so it can be installed without a private upload key. Release output is `app/build/outputs/apk/release/app-release.apk`.

Unit tests cover the source classifier, permission and date rules, the encrypt/decrypt round trip (including a seek in the middle of a ciphertext), and editor helpers: filter matrices, crop geometry, collage frames, undo, and the video timeline. `app/src/androidTest` repeats the crypto round trip for an instrumentation run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Architecture

Single `:app` module. Kotlin, Jetpack Compose, Material 3, MVVM, coroutines and Flow, Hilt, Media3, Coil, DataStore, and Tink.

| Area | Package |
| --- | --- |
| MediaStore, permissions, trash | `com.mediaapp.data.media` |
| Folder rules | `com.mediaapp.data.source` |
| Vault crypto, index, app lock | `com.mediaapp.data.vault` |
| Player, PiP, vault data source | `com.mediaapp.player` |
| Screens | `com.mediaapp.ui` |
| Photo and video editors | `com.mediaapp.editor` |

`GalleryEntry.uri` is a string so the classifier, grouping, and `VaultCrypto` stay free of Android types and can run as JVM tests. Keystore wrapping lives in `AndroidVaultCipher` and is not part of the unit test.

## Later

An encrypted vault backup you can move off the device. Until that exists, locked files cannot survive uninstall or a data clear.

## Limitations

- Uninstall or clear-data deletes the vault. There is no export yet.
- Picture-in-picture is off for locked videos because `FLAG_SECURE` would blank the PiP window.
- Android 11+ delete and trash actions show a system consent dialog. Declining a lock keeps the originals.
- Auto-lock of a watched folder asks you to confirm. It does not delete new files in the background.
- Partial photo access only shows what Android grants.
- Lock-screen controls need notification permission on Android 13+.
- The app-managed recycle bin is only used below Android 11. The system bin is purged by Android, typically after 30 days.
- Sharing a locked item briefly decrypts it into the app cache.
- External subtitles are chosen through the system file picker.
- HEIC decoding can fail on Android 8.0.
- HyperOS and other skins sometimes delay MediaStore scans.
- Photo export uses a working bitmap whose long side is about 2200 pixels, not the original file's full resolution.
- Replacing a video soundtrack drops the original audio for the whole export. The picked file plays only inside the range you set.
- Vault video editing keeps a plaintext file in the app cache until you leave the editor. Share cache is still cleared when the vault locks. Editor cache is not cleared on every background, so an in-progress edit survives leaving the app briefly.
- Instrumentation tests were not run on a device or emulator in this environment. Release APKs are debug-signed.
