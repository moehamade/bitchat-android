# Share the Installed APK In Place — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Share the already-installed APK directly instead of forcing a ~73MB GitHub download, and stop leaving an unreclaimable copy in the cache.

**Architecture:** A pure `ShareSourceResolver` decides between the installed APK, a cached download, and "a download is required" — returning the *reason* rather than swallowing it. The hotspot server reads the installed APK's path directly; the Android share sheet reaches it through a small read-only `ContentProvider`, because `FileProvider` cannot expose `/data/app`.

**Tech Stack:** Kotlin, Android SDK 26+ (compile 37), JUnit 4, Robolectric, Jetpack Compose, NanoHTTPD.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-29-share-installed-apk-in-place-design.md`
- minSdk is 26. Guard any API above that.
- Unit tests live in `app/src/test/kotlin/com/bitchat/android/...`, JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`).
- Run a single test class with: `./gradlew :app:testDebugUnitTest --tests "com.bitchat.android.util.ClassName"`
- Never copy the installed APK. If a step would write it into the cache, the step is wrong.
- Existing user-facing strings live in `app/src/main/res/values/strings.xml`. Add new copy there, never inline literals in composables.
- The universal download action must remain available in every state. Removing it is a capability regression.

## File Structure

| File | Responsibility |
| --- | --- |
| `app/src/main/java/com/bitchat/android/util/ShareSource.kt` | **Create.** The `ShareSource` type and the pure resolver. No Android imports. |
| `app/src/test/kotlin/com/bitchat/android/util/ShareSourceResolverTest.kt` | **Create.** Full decision matrix, JVM only. |
| `app/src/main/java/com/bitchat/android/util/InstalledApkProvider.kt` | **Create.** Read-only provider exposing `applicationInfo.sourceDir`. |
| `app/src/test/kotlin/com/bitchat/android/util/InstalledApkProviderTest.kt` | **Create.** Robolectric: query columns, openFile, read-only enforcement. |
| `app/src/main/AndroidManifest.xml` | **Modify.** Register the provider next to the existing FileProvider (line ~88). |
| `app/src/main/java/com/bitchat/android/util/UniversalApkManager.kt` | **Modify.** Add `resolveShareSource()`, drop the copy, purge stale `INSTALLED` cache entries. |
| `app/src/main/java/com/bitchat/android/hotspot/ApkWebServer.kt` | **Modify.** Accept a variant; render compatibility on the landing page. |
| `app/src/main/java/com/bitchat/android/hotspot/HotspotActivity.kt` | **Modify.** Resolve the share source instead of `getCachedApk()`. |
| `app/src/main/java/com/bitchat/android/ui/ApkDownloadViewModel.kt` | **Modify.** Share-sheet URI comes from the provider for in-place sources. |
| `app/src/main/java/com/bitchat/android/ui/AboutSheet.kt` | **Modify.** Compatibility note; always-available universal download. |
| `app/src/main/res/values/strings.xml` | **Modify.** New copy. |

---

### Task 1: `ShareSource` and the pure resolver

**Files:**
- Create: `app/src/main/java/com/bitchat/android/util/ShareSource.kt`
- Test: `app/src/test/kotlin/com/bitchat/android/util/ShareSourceResolverTest.kt`

**Interfaces:**
- Consumes: `ShareableApkVariant` (`app/src/main/java/com/bitchat/android/util/DistributionInfoProvider.kt:211`), `UniversalApkManager.ApkInfo` and `UniversalApkManager.ApkSource` (`UniversalApkManager.kt:810`, `:820`).
- Produces: `ShareSource` (sealed interface with `InPlace`, `Downloaded`, `DownloadRequired`, and `ShareSource.Reason`), and `ShareSourceResolver.resolve(...)`. Tasks 3–6 depend on these exact names.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/bitchat/android/util/ShareSourceResolverTest.kt`:

```kotlin
package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Which artifact gets shared is a decision with real consequences -- a split install
 * shared as base.apk leaves the recipient with an app missing its native libraries --
 * so it is kept free of Android dependencies and tested directly.
 */
class ShareSourceResolverTest {

    private val installedPath = "/data/app/com.bitchat.droid/base.apk"
    private val installedVersion = "1.7.5"

    private fun cached(
        source: UniversalApkManager.ApkSource,
        variant: ShareableApkVariant,
        version: String = "1.7.4",
    ) = UniversalApkManager.ApkInfo(
        version = version,
        checksum = "abc",
        downloadDate = 0L,
        size = 100L,
        file = File("/cache/universal_apk/bitchat-universal-$version.apk"),
        source = source,
        variant = variant,
    )

    @Test
    fun `a split install cannot be shared as it stands`() {
        val result = ShareSourceResolver.resolve(
            hasSplits = true,
            installedVariant = ShareableApkVariant.UNIVERSAL,
            installedVersion = installedVersion,
            installedPath = installedPath,
            cached = null,
        )

        assertEquals(ShareSource.DownloadRequired(ShareSource.Reason.SPLIT_INSTALL), result)
    }

    @Test
    fun `an install with no recognised variant cannot be shared as it stands`() {
        val result = ShareSourceResolver.resolve(
            hasSplits = false,
            installedVariant = null,
            installedVersion = installedVersion,
            installedPath = installedPath,
            cached = null,
        )

        assertEquals(
            ShareSource.DownloadRequired(ShareSource.Reason.UNSUPPORTED_VARIANT),
            result
        )
    }

    @Test
    fun `a self-contained universal install is shared in place`() {
        val result = ShareSourceResolver.resolve(
            hasSplits = false,
            installedVariant = ShareableApkVariant.UNIVERSAL,
            installedVersion = installedVersion,
            installedPath = installedPath,
            cached = null,
        )

        assertEquals(
            ShareSource.InPlace(installedPath, installedVersion, ShareableApkVariant.UNIVERSAL),
            result
        )
    }

    @Test
    fun `an arm64-only install is shared in place rather than forcing a download`() {
        val result = ShareSourceResolver.resolve(
            hasSplits = false,
            installedVariant = ShareableApkVariant.ARM64,
            installedVersion = installedVersion,
            installedPath = installedPath,
            cached = null,
        )

        assertEquals(
            ShareSource.InPlace(installedPath, installedVersion, ShareableApkVariant.ARM64),
            result
        )
    }

    @Test
    fun `an already downloaded universal wins, since the user asked for that coverage`() {
        val downloaded = cached(UniversalApkManager.ApkSource.GITHUB, ShareableApkVariant.UNIVERSAL)

        val result = ShareSourceResolver.resolve(
            hasSplits = false,
            installedVariant = ShareableApkVariant.ARM64,
            installedVersion = installedVersion,
            installedPath = installedPath,
            cached = downloaded,
        )

        assertEquals(
            ShareSource.Downloaded(downloaded.file, downloaded.version, ShareableApkVariant.UNIVERSAL),
            result
        )
    }

    @Test
    fun `a leftover INSTALLED cache entry is ignored in favour of the live APK`() {
        val stale = cached(UniversalApkManager.ApkSource.INSTALLED, ShareableApkVariant.UNIVERSAL)

        val result = ShareSourceResolver.resolve(
            hasSplits = false,
            installedVariant = ShareableApkVariant.UNIVERSAL,
            installedVersion = installedVersion,
            installedPath = installedPath,
            cached = stale,
        )

        assertEquals(
            ShareSource.InPlace(installedPath, installedVersion, ShareableApkVariant.UNIVERSAL),
            result
        )
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bitchat.android.util.ShareSourceResolverTest"
```

Expected: compilation failure — `Unresolved reference 'ShareSource'` and `Unresolved reference 'ShareSourceResolver'`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/bitchat/android/util/ShareSource.kt`:

```kotlin
package com.bitchat.android.util

import java.io.File

/**
 * Which artifact a share should serve.
 *
 * [DownloadRequired] carries why, so the UI can explain itself. The previous code
 * returned null for every failure, which the caller could not tell apart from "nothing
 * usable" -- and so it silently demanded a GitHub download instead.
 */
sealed interface ShareSource {

    /** The running app's own APK, served without copying it. */
    data class InPlace(
        val path: String,
        val version: String,
        val variant: ShareableApkVariant,
    ) : ShareSource

    /** A universal APK previously fetched from GitHub. */
    data class Downloaded(
        val file: File,
        val version: String,
        val variant: ShareableApkVariant,
    ) : ShareSource

    data class DownloadRequired(val reason: Reason) : ShareSource

    enum class Reason {
        /** Installed across several APKs, so base.apk alone would be missing libraries. */
        SPLIT_INSTALL,

        /** Native libraries do not match a variant we know recipients can install. */
        UNSUPPORTED_VARIANT,
    }
}

internal object ShareSourceResolver {

    /**
     * @param hasSplits whether `applicationInfo.splitSourceDirs` is non-empty
     * @param installedVariant result of [DistributionInfoProvider.shareableApkVariant]
     * @param cached the currently cached artifact, if any
     */
    fun resolve(
        hasSplits: Boolean,
        installedVariant: ShareableApkVariant?,
        installedVersion: String,
        installedPath: String,
        cached: UniversalApkManager.ApkInfo?,
    ): ShareSource {
        // Fetching this was an explicit choice for wider recipient coverage, so it
        // outranks the local artifact. A leftover INSTALLED entry is not such a choice.
        if (cached != null &&
            cached.source == UniversalApkManager.ApkSource.GITHUB &&
            cached.variant == ShareableApkVariant.UNIVERSAL
        ) {
            return ShareSource.Downloaded(cached.file, cached.version, cached.variant)
        }

        if (hasSplits) {
            return ShareSource.DownloadRequired(ShareSource.Reason.SPLIT_INSTALL)
        }

        if (installedVariant == null) {
            return ShareSource.DownloadRequired(ShareSource.Reason.UNSUPPORTED_VARIANT)
        }

        return ShareSource.InPlace(installedPath, installedVersion, installedVariant)
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bitchat.android.util.ShareSourceResolverTest"
```

Expected: `BUILD SUCCESSFUL`, 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bitchat/android/util/ShareSource.kt \
        app/src/test/kotlin/com/bitchat/android/util/ShareSourceResolverTest.kt
git commit -m "feat: resolve the share source explicitly instead of returning null"
```

---

### Task 2: `InstalledApkProvider`

**Files:**
- Create: `app/src/main/java/com/bitchat/android/util/InstalledApkProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml` (after the FileProvider block ending at line 97)
- Test: `app/src/test/kotlin/com/bitchat/android/util/InstalledApkProviderTest.kt`

**Interfaces:**
- Produces: `InstalledApkProvider.uriFor(context: Context): Uri`. Task 5 uses this.

- [ ] **Step 0: Confirm the Robolectric test dependencies resolve**

The existing unit tests are plain JUnit, so `androidx.test.core` may not be on the test
classpath even though Robolectric is configured. Check before writing the test:

```bash
grep -rn "robolectric\|androidx-test\|test-core" gradle/libs.versions.toml
```

If `androidx.test:core` is absent, add it to the testing bundle in `libs.versions.toml`
and to `testImplementation` in `app/build.gradle.kts`. If adding it proves awkward,
replace `ApplicationProvider.getApplicationContext()` with
`RuntimeEnvironment.getApplication()` from `org.robolectric.RuntimeEnvironment`, which
needs no extra dependency.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/bitchat/android/util/InstalledApkProviderTest.kt`:

```kotlin
package com.bitchat.android.util

import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric

@RunWith(RobolectricTestRunner::class)
class InstalledApkProviderTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun provider(): InstalledApkProvider =
        Robolectric.buildContentProvider(InstalledApkProvider::class.java).create().get()

    @Test
    fun `the share sheet is given a readable name and a size`() {
        val cursor = provider().query(
            InstalledApkProvider.uriFor(context),
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )

        requireNotNull(cursor).use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getString(0).endsWith(".apk"))
            assertTrue(it.getLong(1) >= 0L)
        }
    }

    @Test
    fun `the APK is offered as an installable package`() {
        assertEquals(
            "application/vnd.android.package-archive",
            provider().getType(InstalledApkProvider.uriFor(context))
        )
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `the provider refuses writes`() {
        provider().insert(InstalledApkProvider.uriFor(context), null)
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bitchat.android.util.InstalledApkProviderTest"
```

Expected: compilation failure — `Unresolved reference 'InstalledApkProvider'`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/bitchat/android/util/InstalledApkProvider.kt`:

```kotlin
package com.bitchat.android.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the running app's own APK to other apps, without copying it.
 *
 * FileProvider can only expose the roots declared in `file_paths.xml`, all of which are
 * app-private; the installed APK lives under /data/app and cannot be added to them.
 * Opening it here works because the descriptor is created in this process -- the
 * receiving app is handed a file descriptor, never a path.
 */
class InstalledApkProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY_SUFFIX = ".installedapk"
        private const val PATH = "installed.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        fun uriFor(context: Context): Uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}$AUTHORITY_SUFFIX")
            .appendPath(PATH)
            .build()
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val apk = installedApk()
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context ?: throw FileNotFoundException("Provider has no context")
        val apk = installedApk()
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        columns.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add("bitchat-$version.apk")
                OpenableColumns.SIZE -> row.add(apk.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = APK_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("The installed APK is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("The installed APK is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("The installed APK is read-only")

    private fun installedApk(): File {
        val sourceDir = context?.applicationInfo?.sourceDir
            ?: throw FileNotFoundException("No application info")
        return File(sourceDir).also {
            if (!it.isFile) throw FileNotFoundException("Installed APK not found at $sourceDir")
        }
    }
}
```

- [ ] **Step 4: Register the provider**

In `app/src/main/AndroidManifest.xml`, immediately after the closing `</provider>` of the FileProvider block (line 97):

```xml
        <!-- Serves the running app's own APK; FileProvider cannot reach /data/app -->
        <provider
            android:name=".util.InstalledApkProvider"
            android:authorities="${applicationId}.installedapk"
            android:exported="false"
            android:grantUriPermissions="true" />
```

- [ ] **Step 5: Run the test and confirm it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.bitchat.android.util.InstalledApkProviderTest"
```

Expected: `BUILD SUCCESSFUL`, 3 tests passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/bitchat/android/util/InstalledApkProvider.kt \
        app/src/test/kotlin/com/bitchat/android/util/InstalledApkProviderTest.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: expose the installed APK to the share sheet without copying it"
```

---

### Task 3: Resolve in `UniversalApkManager` and stop copying

**Files:**
- Modify: `app/src/main/java/com/bitchat/android/util/UniversalApkManager.kt`

**Interfaces:**
- Consumes: `ShareSourceResolver.resolve(...)`, `ShareSource` (Task 1).
- Produces: `UniversalApkManager.resolveShareSource(): ShareSource`. Tasks 4–6 call this.

- [ ] **Step 1: Add `resolveShareSource()` and stale-cache purging**

Add to `UniversalApkManager`:

```kotlin
    /**
     * Decide what to share. Never copies: the installed APK is served from where the
     * system put it.
     */
    fun resolveShareSource(): ShareSource {
        purgeStaleInstalledCache()

        val applicationInfo = context.applicationInfo
        val installedApk = File(applicationInfo.sourceDir)
        val variant = if (installedApk.isFile && installedApk.length() > 0L) {
            DistributionInfoProvider.shareableApkVariant(installedApk)
        } else {
            null
        }

        return ShareSourceResolver.resolve(
            hasSplits = !applicationInfo.splitSourceDirs.isNullOrEmpty(),
            installedVariant = variant,
            installedVersion = installedVersionName(),
            installedPath = applicationInfo.sourceDir,
            cached = getCachedApkInfo(),
        )
    }

    /**
     * Earlier versions copied the installed APK into the cache. Nothing writes one now,
     * so any that exists is a leftover holding tens of megabytes the user cannot reclaim
     * -- deleting it used to be futile because the next check re-copied it.
     */
    private fun purgeStaleInstalledCache() {
        val info = getCachedApkInfo() ?: return
        if (info.source != ApkSource.INSTALLED) return

        Log.i(TAG, "Removing leftover copy of the installed APK (${info.size} bytes)")
        deleteCachedApk()
    }
```

- [ ] **Step 2: Remove the copy from the update check**

In `checkForUpdate()`, replace the opening block:

```kotlin
            val installedApkInfo = cacheInstalledApkIfPreferred()
            if (installedApkInfo?.source == ApkSource.INSTALLED) {
                return@withContext UpdateStatus.UpToDate(installedApkInfo.version)
            }
```

with:

```kotlin
            // A self-contained install is already a sharing artifact; there is nothing
            // to fetch and nothing to copy.
            when (val source = resolveShareSource()) {
                is ShareSource.InPlace -> return@withContext UpdateStatus.UpToDate(source.version)
                is ShareSource.Downloaded -> return@withContext UpdateStatus.UpToDate(source.version)
                is ShareSource.DownloadRequired -> Unit // fall through to the GitHub check
            }
```

- [ ] **Step 3: Delete `cacheInstalledApkIfPreferred()`**

Delete the whole function (`UniversalApkManager.kt`, starting at `private fun cacheInstalledApkIfPreferred(): ApkInfo?`). Confirm nothing else calls it:

```bash
grep -rn "cacheInstalledApkIfPreferred" app/src/
```

Expected: no output.

- [ ] **Step 4: Build and run the full suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. If `ShareableApkVariant.ARM64` preference tests existed for the deleted function, delete them — that behaviour is intentionally gone per the spec's Risks section.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bitchat/android/util/UniversalApkManager.kt
git commit -m "feat: resolve the share source without copying the installed APK"
```

---

### Task 4: Serve the installed APK over the hotspot

**Files:**
- Modify: `app/src/main/java/com/bitchat/android/hotspot/ApkWebServer.kt`
- Modify: `app/src/main/java/com/bitchat/android/hotspot/HotspotActivity.kt:58-80`

**Interfaces:**
- Consumes: `UniversalApkManager.resolveShareSource()` (Task 3), `ShareableApkVariant`.
- Produces: `ApkWebServer(context, apkFile, variant, port)`.

- [ ] **Step 1: Give the server the variant**

In `ApkWebServer.kt`, change the constructor:

```kotlin
class ApkWebServer(
    private val context: Context,
    private val apkFile: File,
    private val variant: ShareableApkVariant = ShareableApkVariant.UNIVERSAL,
    private val port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {
```

Add the import `import com.bitchat.android.util.ShareableApkVariant`.

- [ ] **Step 2: Show compatibility on the landing page**

In the HTML template, add a third cell to the existing `info-grid` (after the Size box):

```html
            <div class="info-box">
                <div class="info-label">Works on</div>
                <div class="info-value">$compatibilityLabel</div>
            </div>
```

And add the backing values near `appVersion`:

```kotlin
    private val compatibilityLabel: String
        get() = when (variant) {
            ShareableApkVariant.UNIVERSAL -> "All devices"
            ShareableApkVariant.ARM64 -> "64-bit ARM"
        }

    // Android reports an ABI mismatch as a bare "App not installed", so a recipient who
    // is not told this has nothing to diagnose from.
    private val compatibilityWarning: String
        get() = when (variant) {
            ShareableApkVariant.UNIVERSAL -> ""
            ShareableApkVariant.ARM64 ->
                "<div class=\"warning\"><strong>⚠️ Note:</strong> This build supports " +
                    "64-bit ARM devices only. If installation fails with \"App not " +
                    "installed\", your device needs the universal version — ask the " +
                    "sender for it.</div>"
        }
```

Insert `$compatibilityWarning` immediately before the existing closing `</div>` of `.container`.

- [ ] **Step 3: Resolve the source in the activity**

In `HotspotActivity.onCreate`, replace the fallback block:

```kotlin
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val apkFile = if (apkPath != null) {
            File(apkPath)
        } else {
            UniversalApkManager(this).getCachedApk()
        }

        if (apkFile == null || !apkFile.exists()) {
            finish()
            return
        }
```

with:

```kotlin
        // Resolving rather than reaching for a cached file is what lets a self-contained
        // install be served straight from where the system put it.
        val shareSource = UniversalApkManager(this).resolveShareSource()
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val apkFile = when {
            apkPath != null -> File(apkPath)
            shareSource is ShareSource.InPlace -> File(shareSource.path)
            shareSource is ShareSource.Downloaded -> shareSource.file
            else -> null
        }
        val variant = when (shareSource) {
            is ShareSource.InPlace -> shareSource.variant
            is ShareSource.Downloaded -> shareSource.variant
            is ShareSource.DownloadRequired -> ShareableApkVariant.UNIVERSAL
        }

        if (apkFile == null || !apkFile.exists()) {
            finish()
            return
        }
```

Add imports: `com.bitchat.android.util.ShareSource`, `com.bitchat.android.util.ShareableApkVariant`.

- [ ] **Step 4: Pass the variant through to the server**

In `HotspotViewModel.startHotspot` (`app/src/main/java/com/bitchat/android/hotspot/HotspotViewModel.kt`), the `ApkWebServer(context, apkFile)` construction becomes `ApkWebServer(context, apkFile, variant)`. Thread `variant` from `HotspotActivity` through `HotspotScreen` into `startHotspot(apkFile, variant)`, defaulting to `ShareableApkVariant.UNIVERSAL`.

- [ ] **Step 5: Build and verify on device**

```bash
./gradlew :app:installDebug
```

Start the hotspot, connect a second device, load `http://192.168.49.1:9999`. Confirm the page shows a "Works on" cell, and that the APK downloads and installs.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/bitchat/android/hotspot/
git commit -m "feat: serve the installed APK over the hotspot and state its compatibility"
```

---

### Task 5: Share sheet uses the provider

**Files:**
- Modify: `app/src/main/java/com/bitchat/android/ui/ApkDownloadViewModel.kt:165-192`

**Interfaces:**
- Consumes: `InstalledApkProvider.uriFor(context)` (Task 2), `UniversalApkManager.resolveShareSource()` (Task 3).

- [ ] **Step 1: Pick the URI by source**

Replace the body of `onConfirmAppShare()` between the `try {` and the `_effect.send(...)`:

```kotlin
                val context = getApplication<Application>()
                val uri = when (val source = apkManager.resolveShareSource()) {
                    is ShareSource.InPlace -> InstalledApkProvider.uriFor(context)

                    is ShareSource.Downloaded -> FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        source.file
                    )

                    is ShareSource.DownloadRequired -> {
                        sendToast(getString(R.string.apk_not_ready_please_prepare_it_first))
                        return@launch
                    }
                }
```

Add imports: `com.bitchat.android.util.InstalledApkProvider`, `com.bitchat.android.util.ShareSource`.

- [ ] **Step 2: Build and verify on device**

```bash
./gradlew :app:installDebug
```

Use the in-app "share the app" action, pick a file-manager or messaging target, and confirm the received file is named `bitchat-<version>.apk` and installs.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/bitchat/android/ui/ApkDownloadViewModel.kt
git commit -m "feat: share the installed APK through the share sheet without copying it"
```

---

### Task 6: Sharer-facing compatibility copy

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/bitchat/android/ui/AboutSheet.kt`

- [ ] **Step 1: Add the strings**

```xml
    <string name="apk_compat_universal">Works on all Android devices.</string>
    <string name="apk_compat_arm64_title">Works on most phones, but not all</string>
    <string name="apk_compat_arm64_body">This copy installs on 64-bit ARM devices — nearly every phone from the last few years. It won\'t install on older 32-bit phones or Chromebooks.</string>
    <string name="apk_get_universal">Get universal version</string>
    <string name="apk_download_required_split">This copy of BitChat was installed in several parts, so it can\'t be shared as it stands. Download the universal version to share it.</string>
    <string name="apk_download_required_unsupported">This build can\'t be shared directly. Download the universal version to share it.</string>
```

- [ ] **Step 2: Render the note**

In `AboutSheet.kt`, where the prepared-APK state is rendered (near the existing delete buttons, around line 560–600), add above the share action:

```kotlin
                                    when (val source = apkUiState.shareSource) {
                                        is ShareSource.InPlace ->
                                            if (source.variant == ShareableApkVariant.ARM64) {
                                                CompatibilityNote(
                                                    title = stringResource(R.string.apk_compat_arm64_title),
                                                    body = stringResource(R.string.apk_compat_arm64_body)
                                                )
                                            } else {
                                                Text(
                                                    text = stringResource(R.string.apk_compat_universal),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }

                                        is ShareSource.Downloaded ->
                                            Text(
                                                text = stringResource(R.string.apk_compat_universal),
                                                style = MaterialTheme.typography.bodySmall
                                            )

                                        is ShareSource.DownloadRequired ->
                                            Text(
                                                text = when (source.reason) {
                                                    ShareSource.Reason.SPLIT_INSTALL ->
                                                        stringResource(R.string.apk_download_required_split)
                                                    ShareSource.Reason.UNSUPPORTED_VARIANT ->
                                                        stringResource(R.string.apk_download_required_unsupported)
                                                },
                                                style = MaterialTheme.typography.bodySmall
                                            )

                                        null -> Unit
                                    }
```

Add `val shareSource: ShareSource? = null` to `ApkUiState` and populate it in the ViewModel's refresh path alongside the existing `getCachedApkInfo()` reads (`ApkDownloadViewModel.kt:246`, `:327`, `:349`).

Add a small private composable in `AboutSheet.kt`:

```kotlin
@Composable
private fun CompatibilityNote(title: String, body: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        Text(text = body, style = MaterialTheme.typography.bodySmall)
    }
}
```

- [ ] **Step 3: Keep the universal download always reachable**

> **Read `AboutSheet.kt` lines 520–660 before editing.** This plan does not reproduce
> that composable, because the surrounding structure was not inspected when the plan was
> written; copying a guessed structure here would be worse than reading the real one.

Requirements for the edit:

1. The action that triggers a universal download currently renders only when nothing is
   prepared. It must also render when the state is `InPlace` or `Downloaded`.
2. When a share source already exists, label it `stringResource(R.string.apk_get_universal)`.
3. For `Downloaded`, render it as already present (disabled, or replaced by a "universal
   version ready" line) rather than offering a redundant re-fetch.
4. Do not remove the existing delete affordance for downloaded artifacts. It stays valid
   — and now actually works, because nothing re-copies the installed APK.

Verify by reading the composable back after editing: every one of the three states must
show a route to the universal download.

- [ ] **Step 4: Build, test, verify**

```bash
./gradlew :app:testDebugUnitTest :app:installDebug
```

Open the About sheet and confirm: the compatibility line matches the build, and the universal download action is present in every state.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/com/bitchat/android/ui/
git commit -m "feat: tell the sharer which devices their copy will install on"
```

---

## Final verification

- [ ] `./gradlew :app:testDebugUnitTest` — full suite green
- [ ] `./gradlew :app:assembleDebug` — no new warnings in touched files
- [ ] `grep -rn "cacheInstalledApkIfPreferred" app/src/` returns nothing
- [ ] On device: `adb shell run-as com.bitchat.droid ls -la cache/universal_apk/` shows no `source: INSTALLED` artifact after opening the About sheet
- [ ] On device: hotspot share end-to-end to a second phone
- [ ] On device: share-sheet share end-to-end
