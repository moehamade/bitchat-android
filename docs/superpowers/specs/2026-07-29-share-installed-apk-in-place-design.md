# Share the installed APK in place

**Date:** 2026-07-29
**Status:** Approved, not yet implemented

## Problem

A clean install prompts the user to download a ~73MB universal APK from GitHub before
the sharing options appear, even when the APK already installed on the device is a
perfectly good sharing artifact. The download is then hard to reclaim: deleting it
appears to do nothing, because the next update check immediately re-copies the
installed APK back into the cache.

## Evidence

The intent to share the installed APK already exists in `cacheInstalledApkIfPreferred()`,
and on the reporting device it had in fact succeeded — the cache held:

```json
{"version":"1.7.5","size":76501642,"fileName":"bitchat-universal-1.7.5.apk","source":"INSTALLED"}
```

The debug build packages `arm64-v8a, armeabi-v7a, x86, x86_64`, which equals
`UNIVERSAL_RELEASE_ABIS`, so `shareableApkVariant()` classifies it `UNIVERSAL`. The
install is not split — `pm path` returns `base.apk` only.

Three defects explain the reported behaviour:

1. **The fallback is silent.** Every failure inside `cacheInstalledApkIfPreferred()` is
   caught, logged at `Log.w`, and returned as `null`, which the caller cannot
   distinguish from "no installed APK is usable". The UI then falls through to a GitHub
   download with no stated reason. `checkDiskSpace()` alone requires 1.5× the APK size
   (~110MB free) and fails this way.

2. **Resolution copies 73MB synchronously.** The copy runs inside `checkForUpdate()`, so
   there is a multi-second window in which the UI can present a download prompt before
   the direct path resolves.

3. **The cached copy is effectively undeletable.** `deleteCachedApk()` removes the file,
   but the next `checkForUpdate()` re-copies the installed APK, so the space returns.

## Decisions

| Decision | Rationale |
| --- | --- |
| Serve the installed APK in place; never copy it | Removes the 73MB duplicate, the disk-space failure that causes the bogus download prompt, and the undeletable-artifact problem, because there is no artifact |
| Custom `ContentProvider` for the share sheet | `FileProvider` can only serve roots declared in `file_paths.xml`, all app-private; `/data/app/…/base.apk` cannot be added. A provider returning a `ParcelFileDescriptor` opened in our own process can |
| Share in place by default, with the universal download always available | Sharing works immediately; the user chooses wider coverage rather than being blocked by a mandatory download, and never loses the ability to fetch the universal artifact |
| Tell both the sharer and the recipient about ABI limits | The recipient suffers the mismatch but only the sharer can fix it |

### Non-goals

- Detecting the recipient's architecture from the browser User-Agent. It is unreliable
  on Android, and a wrong "incompatible" banner is worse than an accurate static one.
- Changing anything about the GitHub download path itself beyond when it is required.

### Deferred to a follow-up: choosing the source when hosting

When both a usable installed APK and a cached universal download are present, this
change picks between them by the precedence rule below. A later change should let the
user choose explicitly at hosting time instead.

It is deliberately not in scope here. This change is a bug fix — a forced download and
an unreclaimable 73MB cache — while the picker is an enhancement to behaviour that will
already be correct. Keeping them apart also keeps this change reviewable; it already
touches eight call sites.

`ShareSource` is the abstraction a picker sits on: once both candidates are values of the
same type, presenting them as a choice is a UI concern rather than a resolution one, and
the precedence rule below becomes the default selection rather than a hidden decision.

## Design

### 1. `ShareSource` resolution

A pure resolver, modelled on `HotspotStartupPolicy`, which kept the hotspot retry logic
testable without Robolectric.

```kotlin
sealed interface ShareSource {
    data class InPlace(val path: String, val version: String, val variant: ShareableApkVariant) : ShareSource
    data class Downloaded(val file: File, val version: String, val variant: ShareableApkVariant) : ShareSource
    data class DownloadRequired(val reason: Reason) : ShareSource

    enum class Reason { SPLIT_INSTALL, UNSUPPORTED_VARIANT }
}

fun resolveShareSource(
    hasSplits: Boolean,
    installedVariant: ShareableApkVariant?,
    installedVersion: String,
    installedPath: String,
    cached: ApkInfo?,
): ShareSource
```

Precedence:

1. A cached `GITHUB` `UNIVERSAL` artifact — widest recipient coverage, and the user
   explicitly chose to fetch it.
2. `InPlace` when the install is self-contained (`!hasSplits && installedVariant != null`).
3. `DownloadRequired(SPLIT_INSTALL)` when `hasSplits`; sharing `base.apk` alone would
   give recipients an app missing its native libraries.
4. `DownloadRequired(UNSUPPORTED_VARIANT)` otherwise.

No Android dependencies, so the whole matrix is unit-testable, and the reason a download
is required becomes a value instead of a swallowed exception.

### 2. `InstalledApkProvider`

```xml
<provider
    android:name=".util.InstalledApkProvider"
    android:authorities="${applicationId}.installedapk"
    android:exported="false"
    android:grantUriPermissions="true" />
```

- `openFile()` returns `ParcelFileDescriptor.open(File(applicationInfo.sourceDir), MODE_READ_ONLY)`.
- `query()` supplies `OpenableColumns.DISPLAY_NAME` (`bitchat-<version>.apk`) and `SIZE`,
  so the share sheet shows a sensible name rather than the authority.
- `insert`/`update`/`delete` throw `UnsupportedOperationException`; `getType()` returns
  `application/vnd.android.package-archive`.

Not exported, per-share URI grants only. The receiving app gets a file descriptor, never
a path.

The hotspot path needs none of this: `ApkWebServer` already accepts a `File`, so it
receives `File(sourceDir)` directly.

### 3. Compatibility messaging

**Sharer**, on the share screen:

- `UNIVERSAL` — one line: "Works on all Android devices."
- `ARM64` — a note explaining the limit:
  > **Works on most phones, but not all**
  > This copy installs on 64-bit ARM devices — nearly every phone from the last few
  > years. It won't install on older 32-bit phones or Chromebooks.

The `[ Get universal version · <size> ]` action is offered **in every case**, not only
when the installed APK is ABI-limited. Sharing in place is the default, never the only
option: a user may want the universal artifact for reasons this code cannot infer, and
removing the ability to fetch it would be a capability regression. When a universal
artifact is already cached, the action is replaced by an indication that it is present
and in use.

**Recipient**, on the landing page `ApkWebServer` already serves: a third cell in the
existing info grid, so it is visible before downloading rather than after a failed
install.

| Version | Size | Works on |
| --- | --- | --- |
| 1.7.5 | 73 MB | All devices — or — 64-bit ARM |

When ABI-limited, extend the existing warning box:

> **⚠️ Note:** This build supports 64-bit ARM devices only. If installation fails with
> "App not installed", your device needs the universal version — ask the sender for it.

Android's failure message for an ABI mismatch is a bare "App not installed", so without
this the recipient has nothing to go on.

`ApkWebServer`'s constructor gains a `ShareableApkVariant` parameter to render this.

### 4. Migration and delete semantics

Whenever resolution encounters a cached artifact with `source == INSTALLED`, it is
deleted, reclaiming ~73MB. No version gate is needed: after this change nothing ever
writes one again, so any that exists is a leftover by definition. Artifacts with
`source == GITHUB` are kept.

Delete then applies only to downloaded artifacts. It can no longer resurrect itself,
because nothing re-copies the installed APK.

## Testing

- **Unit (JVM, no Robolectric):** `resolveShareSource` across the matrix — split install,
  universal install, arm64-only install, each with and without a cached GitHub artifact;
  plus precedence of a cached universal over in-place.
- **Robolectric:** `InstalledApkProvider.query()` returns display name and size;
  `openFile()` returns a readable descriptor; mutating methods throw.
- **On device:** both consumer paths — hotspot (recipient downloads and installs from the
  landing page) and the Android share sheet — plus confirmation that the stale
  `source == INSTALLED` cache entry is removed on upgrade.

## Risks

- **ARM64 default changes.** Today an arm64-only install auto-prefers a downloaded
  universal APK. It will now share in place by default, so a user who shares without
  reading the note could hand an arm64-only APK to an x86 recipient. Mitigated by the
  messaging above on both sides; accepted deliberately in exchange for never blocking a
  share behind a download.
- **Blast radius.** `getCachedApk()` / `getCachedApkInfo()` have eight call sites across
  `ApkDownloadViewModel`, `ApkDownloadWorker` and `HotspotActivity`. The change is not
  confined to `UniversalApkManager`.
- **APK replaced mid-share.** If the app updates while a share is in progress, the open
  descriptor keeps serving the old inode, which is correct behaviour; a new share picks
  up the new path.
