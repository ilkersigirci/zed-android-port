package com.zdroid

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.hardware.input.InputManager
import android.app.ActivityOptions
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.androidgamesdk.GameActivity
import java.io.File
import java.security.MessageDigest

/// Hosts gpui's main window, the one backing `android_app.native_window()`
/// through GameActivity. Every other `cx.open_window` gets an
/// [ExtraWindowActivity], which OS-managed freeform windowing gives its own
/// chrome. See `multi_window.rs`.
class MainActivity : GameActivity(), ZdroidWindow {
    /// The animated splash, until the first frame.
    private var splashOverlay: FrameLayout? = null

    /// The programming extras row (Esc, Tab, Ctrl, Alt, arrows). Created the
    /// first time it shows, and gone while the setting is off.
    private var extraKeysView: ExtraKeysView? = null
    private var programmingExtrasRowEnabled: Boolean = false

    /// The modifier state [ExtraKeysView] publishes for [ZdroidInputConnection].
    @Volatile
    private var extraKeysPendingMeta: Int = 0
    @Volatile
    private var extraKeysLockedMeta: Int = 0

    override val ime = WindowIme(
        this,
        PRIMARY_WINDOW_ID,
        // The row covers the window above the keyboard, so it counts as covered.
        keyboardAccessoryHeight = {
            extraKeysView?.takeIf { it.visibility == View.VISIBLE }?.height ?: 0
        },
        // Edge-to-edge windows aren't moved above the keyboard, and the row
        // sits at the window's bottom, so it follows every frame of the slide.
        onImeBottomChanged = { imeBottom ->
            extraKeysView?.translationY = -imeBottom.toFloat()
        },
        onVisibilityChanged = { updateExtrasRowVisibility() },
        modifierState = { extraKeysPendingMeta or extraKeysLockedMeta },
        clearPendingModifier = { extraKeysView?.consumePendingModifier() },
    )

    override val pointer = WindowPointer(
        this,
        PRIMARY_WINDOW_ID,
        surfaceView = { surfaceView() },
        obscuredBottom = { ime.insetsReporter.obscuredBottom },
    )

    /// GameActivity's surface. It's looked up once: touch and hover events
    /// ask for it, and it doesn't change.
    private var gameSurfaceView: SurfaceView? = null

    private fun surfaceView(): SurfaceView? {
        gameSurfaceView?.let { return it }
        return findSurfaceView(window.decorView).also { gameSurfaceView = it }
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findSurfaceView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /// Keeps [PhysicalKeyboard.blocksIme], and Rust's copy of it, current for
    /// every window: the status bar hides its keyboard button while a physical
    /// keyboard blocks the on-screen one.
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshPhysicalKeyboard()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshPhysicalKeyboard()
        override fun onInputDeviceChanged(deviceId: Int) = refreshPhysicalKeyboard()
    }

    /// Android's "show on-screen keyboard while a physical keyboard is
    /// connected" setting, changed in the system's language and input settings.
    private val showImeWithHardKeyboardObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refreshPhysicalKeyboard()
        }

    private fun refreshPhysicalKeyboard() {
        if (!PhysicalKeyboard.refresh(this)) return
        val blocksIme = PhysicalKeyboard.blocksIme
        Log.i("zdroid_ime", "physical keyboard blocks the on-screen one: $blocksIme")
        NativeBridge.nativeSetPhysicalKeyboardBlocksIme(blocksIme)
    }

    /// The row shows while the setting is on and the keyboard is up.
    private fun updateExtrasRowVisibility() {
        if (programmingExtrasRowEnabled && ime.visible) {
            if (extraKeysView == null) {
                val view = ExtraKeysView(this) { pending, locked ->
                    extraKeysPendingMeta = pending
                    extraKeysLockedMeta = locked
                }
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                )
                addContentView(view, params)
                view.translationY = -ime.insetsReporter.imeBottom.toFloat()
                // The row has no height until its first layout, and its height
                // follows the display's density and font scale.
                view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop) ime.insetsReporter.refresh()
                }
                extraKeysView = view
            }
            extraKeysView?.visibility = View.VISIBLE
        } else {
            extraKeysView?.visibility = View.GONE
        }
        ime.insetsReporter.refresh()
    }

    /// The `android_input.programming_extras_row` setting, pushed from
    /// `gpui_android::platform`.
    @Suppress("unused")
    fun setProgrammingExtrasRowEnabled(enabled: Boolean) {
        runOnUiThread {
            if (programmingExtrasRowEnabled == enabled) return@runOnUiThread
            programmingExtrasRowEnabled = enabled
            if (!enabled) {
                extraKeysView?.let { (it.parent as? ViewGroup)?.removeView(it) }
                extraKeysView = null
            }
            updateExtrasRowVisibility()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashOverlay()
        ZdRuntimeService.startIfBackgroundMode(this)
        watchDnsServers()
        // Reopening from Recents brings back the intent that started the
        // task, which may be an earlier "Open with".
        val relaunched = savedInstanceState != null ||
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!relaunched) {
            OpenedFiles.handle(this, intent)
        }
        enterEdgeToEdge()
        ime.install()
        pointer.install()
        getSystemService(InputManager::class.java)
            ?.registerInputDeviceListener(inputDeviceListener, null)
        contentResolver.registerContentObserver(
            PhysicalKeyboard.showImeWithHardKeyboardUri,
            false,
            showImeWithHardKeyboardObserver,
        )
        refreshPhysicalKeyboard()
    }

    /// A view above the SurfaceView, so a cold start shows one animation instead
    /// of icon, black, editor. A separate splash Activity couldn't do this: gpui
    /// and its surface only start while this Activity is visible.
    private fun installSplashOverlay() {
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundResource(R.color.zdroid_bg)
        }
        val displaySizePx = (200 * resources.displayMetrics.density).toInt()
        val iconParams = FrameLayout.LayoutParams(displaySizePx, displaySizePx).apply {
            gravity = Gravity.CENTER
        }
        val iconView = ImageView(this).apply {
            layoutParams = iconParams
            setImageResource(R.drawable.splash_icon_animated)
            contentDescription = null
        }
        container.addView(iconView)
        val decor = window.decorView as? ViewGroup
        decor?.addView(container)
        splashOverlay = container
        (iconView.drawable as? Animatable)?.start()
    }

    /// Called once from `gpui_android::platform` after the first paint.
    @Suppress("unused")
    fun onFirstFrameDrawn() {
        runOnUiThread { removeSplashOverlay() }
    }

    private fun removeSplashOverlay() {
        val overlay = splashOverlay ?: return
        overlay.animate()
            .alpha(0f)
            .scaleX(1.10f)
            .scaleY(1.10f)
            .setDuration(350L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                splashOverlay = null
            }
            .start()
    }

    override fun onTrackballEvent(event: MotionEvent): Boolean =
        pointer.onTrackballEvent(event) || super.onTrackballEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (pointer.onGenericMotionEvent(event)) return true
        // Captured events above carry relative motion; only absolute hover and
        // scroll positions are moved into surface space.
        offsetEventToSurface(event)
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        event?.let(pointer::onTouchDispatched)
        return super.dispatchTouchEvent(event)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        pointer.onPointerCaptureChanged(hasCapture)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        offsetEventToSurface(event)
        return super.onTouchEvent(event)
    }

    private val surfaceLocation = IntArray(2)
    private val decorLocation = IntArray(2)

    /**
     * GameActivity forwards input to gpui from the Activity-level
     * onTouchEvent / onGenericMotionEvent, in window coordinates, while gpui
     * expects them relative to the SurfaceView. The two differ when the
     * surface doesn't start at the window's top-left corner, as in DeX
     * freeform windows, where every click and hover landed off by the
     * surface's offset. Applied only there, after the view tree has
     * dispatched the event, so sibling views like the extras row still
     * hit-test with their own coordinates.
     */
    private fun offsetEventToSurface(event: MotionEvent) {
        val surface = surfaceView() ?: return
        surface.getLocationInWindow(surfaceLocation)
        window.decorView.getLocationInWindow(decorLocation)
        val offsetX = surfaceLocation[0] - decorLocation[0]
        val offsetY = surfaceLocation[1] - decorLocation[1]
        if (offsetX != 0 || offsetY != 0) {
            event.offsetLocation(-offsetX.toFloat(), -offsetY.toFloat())
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        pointer.onKeyDispatched(event)
        return super.dispatchKeyEvent(event)
    }

    /// A file another app opened while Zdroid runs (`OpenFileActivity`).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        OpenedFiles.handle(this, intent)
    }

    override fun onResume() {
        super.onResume()
        // The setting may have changed while the system's settings were in
        // front, in case the observer didn't fire.
        refreshPhysicalKeyboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        pointer.onWindowFocusChanged(hasFocus)
    }

    /// Starts the Activity that hosts an extra gpui window, with `bounds` as
    /// its freeform window's rectangle if it has any. `documentLaunchMode` in
    /// the manifest gives it its own task; adding the matching intent flags
    /// here sent this Activity to the background under DeX.
    @Suppress("unused") // called from Rust via JNI
    fun launchExtraWindow(windowId: Long, left: Int, top: Int, right: Int, bottom: Int) {
        val launch = Intent(this, ExtraWindowActivity::class.java)
            .putExtra(ExtraWindowActivity.EXTRA_WINDOW_ID, windowId)
        val options = if (right > left && bottom > top) {
            ActivityOptions.makeBasic()
                .setLaunchBounds(Rect(left, top, right, bottom))
                .toBundle()
        } else {
            null
        }
        startActivity(launch, options)
    }

    /// `ViewConfiguration`'s gesture values for gpui's touch recognizer:
    /// touch slop and minimum fling velocity in logical pixels, then the
    /// double-tap and long-press timeouts in milliseconds.
    @Suppress("unused") // called from Rust via JNI
    fun gestureConfiguration(): FloatArray {
        val configuration = ViewConfiguration.get(this)
        val density = resources.displayMetrics.density
        return floatArrayOf(
            configuration.scaledTouchSlop / density,
            configuration.scaledMinimumFlingVelocity / density,
            ViewConfiguration.getDoubleTapTimeout().toFloat(),
            ViewConfiguration.getLongPressTimeout().toFloat(),
        )
    }

    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }

    @Suppress("unused") // called from Rust via JNI
    fun readClipboardText(): String? =
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()

    /// On the UI thread, in the order of the calls, because the binder call can
    /// outlast a frame.
    @Suppress("unused") // called from Rust via JNI
    fun writeClipboardText(text: String) {
        runOnUiThread {
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("", text))
            } catch (error: RuntimeException) {
                // Binder rejects clips too large for one transaction.
                Log.w(TAG, "couldn't write ${text.length} characters to the clipboard", error)
            }
        }
    }

    /** Backs the Zdroid menu's background-mode toggle. */
    @Suppress("unused") // called from Rust via JNI
    fun isBackgroundModeEnabled(): Boolean = ZdRuntimeService.isBackgroundModeEnabled(this)

    @Suppress("unused") // called from Rust via JNI
    fun setBackgroundModeEnabled(enabled: Boolean) {
        ZdRuntimeService.setBackgroundModeEnabled(this, enabled)
        if (enabled) requestNotificationPermissionOnce()
    }

    /** Backs gpui's credential API; see `gpui_android/src/credentials.rs`. */
    @Suppress("unused") // called from Rust via JNI
    fun writeCredential(url: String, username: String, password: ByteArray): Boolean =
        SecureCredentialStore.write(this, url, username, password)

    @Suppress("unused") // called from Rust via JNI
    fun readCredential(url: String): ByteArray? =
        SecureCredentialStore.read(this, url)

    @Suppress("unused") // called from Rust via JNI
    fun deleteCredential(url: String): Boolean =
        SecureCredentialStore.delete(this, url)

    @Suppress("unused") // called from Rust via JNI
    /**
     * Open an HTTPS URL in the user's default browser. Called via JNI from
     * the Rust side's `cx.open_url(...)`.
     *
     * Safe to call from any thread; `runOnUiThread` so `startActivity` runs
     * on main. Logs and swallows ActivityNotFoundException (rare on a stock
     * Android with a browser installed; never propagate back to gpui).
     */
    fun openUrl(url: String) {
        Log.i(TAG, "openUrl: $url")
        runOnUiThread {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "openUrl: startActivity ACTION_VIEW failed for $url", t)
            }
        }
    }

    /// Raw /sdcard paths need "All files access", a per-app toggle in system
    /// settings. Opened once per install at boot; the Android settings page
    /// opens it again on request. File operations on /sdcard fail with a clean
    /// error without it.
    @Suppress("unused") // called from Rust via JNI
    fun requestStoragePermissions() {
        if (Environment.isExternalStorageManager()) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_ALL_FILES_ASKED, false)) return
        prefs.edit().putBoolean(PREF_ALL_FILES_ASKED, true).apply()
        requestAllFilesAccess()
    }

    /// Android's own Clear storage for this app: deletes all of its data and
    /// ends the process. Backs the clean start that an install from before
    /// 0.10 needs.
    @Suppress("unused") // called from Rust via JNI
    fun clearAppData(): Boolean {
        val activityManager = getSystemService(ActivityManager::class.java) ?: return false
        return activityManager.clearApplicationUserData()
    }

    /// Opens the home folder, `ZedDocumentsProvider`'s root, in Android's
    /// Files app, where folders can be copied to shared storage.
    @Suppress("unused") // called from Rust via JNI
    fun openHomeInFiles(): Boolean {
        val root = DocumentsContract.buildRootUri(
            "com.zdroid.documents",
            File(filesDir, "home").absolutePath,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(root, DocumentsContract.Root.MIME_TYPE_ITEM)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "no app opens the documents provider's root", error)
            false
        }
    }

    /// Open the system "All files access" toggle for this package. Returns true
    /// when a screen opened or access already holds.
    @Suppress("unused")
    fun requestAllFilesAccess(): Boolean {
        if (Environment.isExternalStorageManager()) return true
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        return try {
            startActivity(direct)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "all files access screen unavailable; opening the list", t)
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                true
            } catch (inner: Throwable) {
                Log.w(TAG, "all files access settings unavailable", inner)
                false
            }
        }
    }

    /// Returns Android's currently-active DNS server IPs as a comma-joined
    /// string, which the Rust side writes to the runtime's
    /// /etc/resolv.conf at boot ([watchDnsServers] keeps it current). Falls
    /// back to empty string if no active network — caller layers in
    /// public-DNS defaults.
    @Suppress("unused") // called from Rust via JNI
    fun getActiveDnsServers(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ""
        val network = cm.activeNetwork ?: return ""
        val props = cm.getLinkProperties(network) ?: return ""
        return props.dnsServers
            .mapNotNull { it.hostAddress }
            .joinToString(",")
    }

    private var dnsCallback: ConnectivityManager.NetworkCallback? = null

    /// Reports the default network's DNS servers to Rust whenever they
    /// change (Wi-Fi to mobile data, a VPN connecting), so the runtime's
    /// `/etc/resolv.conf` follows Android's. Its programs use glibc's
    /// resolver, which reads only that file. Android calls back once right
    /// after registering, with the current servers.
    private fun watchDnsServers() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val servers = linkProperties.dnsServers
                    .mapNotNull { it.hostAddress }
                    .joinToString(",")
                if (servers.isNotEmpty()) {
                    NativeBridge.nativeDnsServersChanged(servers)
                }
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            dnsCallback = callback
        } catch (t: Throwable) {
            Log.w(TAG, "DNS server updates unavailable", t)
        }
    }

    /// Returns the running app's versionName (e.g. "0.2.0"). The in-app
    /// updater compares this against GitHub's `releases/latest` tag
    /// (e.g. "v0.2.1" with the `v` stripped) to decide whether to
    /// download an upgrade.
    @Suppress("unused") // called from Rust via JNI
    fun appVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            Log.w(TAG_UPDATE, "appVersionName: PackageManager threw", t)
            ""
        }
    }

    /// Directory the installer extracted this APK's native libraries to.
    /// The rootfs engine's loader (libzdload.so) lives here and is exec'd
    /// by path: it is the only app-owned location SELinux lets a process
    /// execute from regardless of targetSdk.
    @Suppress("unused") // called from Rust via JNI
    fun nativeLibraryDir(): String = applicationInfo.nativeLibraryDir ?: ""

    /// Whether Android lets Zdroid install APKs ("Install unknown apps").
    @Suppress("unused") // called from Rust via JNI
    fun canInstallUpdates(): Boolean = packageManager.canRequestPackageInstalls()

    /// Opens Zdroid's "Install unknown apps" settings page. When the user
    /// comes back, `onActivityResult` reports whether they allowed it.
    @Suppress("unused") // called from Rust via JNI
    fun requestInstallPermission() {
        runOnUiThread {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
            try {
                startActivityForResult(intent, REQ_INSTALL_PERMISSION)
            } catch (t: Throwable) {
                Log.e(TAG_UPDATE, "Couldn't open the install permission settings", t)
                NativeBridge.nativeOnInstallPermissionResult(false)
            }
        }
    }

    /// Installs a downloaded update with a PackageInstaller session, which
    /// Android recommends over handing the APK to the installer in an
    /// intent. [UpdateInstallReceiver] gets the outcome: Android asks the
    /// user to confirm, then replaces the app or says why it couldn't.
    /// Returns null once the session is committed, or why it couldn't be.
    /// Copies the APK, so Rust calls it off the UI thread.
    @Suppress("unused") // called from Rust via JNI
    fun installUpdate(apkPath: String): String? {
        val file = File(apkPath)
        if (!file.exists()) {
            return "The downloaded APK is missing"
        }
        if (!isTrustedUpdateApk(file)) {
            return "The downloaded APK isn't Zdroid signed with this build's key"
        }
        val installer = packageManager.packageInstaller
        var sessionId: Int? = null
        return try {
            // An earlier attempt's session still holds its copy of the APK.
            UpdateInstallReceiver.end(this)
            installer.mySessions.forEach { installer.abandonSession(it.sessionId) }
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                }
            }
            val id = installer.createSession(params)
            sessionId = id
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("zdroid.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(UpdateInstallReceiver.begin(this, id))
            }
            null
        } catch (t: Throwable) {
            Log.e(TAG_UPDATE, "installUpdate failed", t)
            UpdateInstallReceiver.end(this)
            sessionId?.let { id -> runCatching { installer.abandonSession(id) } }
            t.message ?: t.toString()
        }
    }

    /// Shows Android's prompt for the update session in flight again.
    /// Returns false if there's no prompt to show or it couldn't start.
    @Suppress("unused") // called from Rust via JNI
    fun showUpdatePrompt(): Boolean {
        val confirmation = UpdateInstallReceiver.confirmation() ?: return false
        return try {
            startActivity(confirmation)
            true
        } catch (t: Throwable) {
            Log.e(TAG_UPDATE, "Couldn't show the update prompt", t)
            false
        }
    }

    /// Only hand the system installer an APK for this package, signed by
    /// the same certificates as the running build. The installer would
    /// reject a different signer anyway, but a different package would
    /// install as a separate app.
    private fun isTrustedUpdateApk(file: File): Boolean {
        return try {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val current = packageManager.getPackageInfo(packageName, flags)
            val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
                ?: return false
            if (archive.packageName != packageName) return false

            fun signerDigests(info: PackageInfo): Set<String> =
                info.signingInfo?.apkContentsSigners.orEmpty().map { signature ->
                    MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray())
                        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                }.toSet()

            val currentSigners = signerDigests(current)
            currentSigners.isNotEmpty() && currentSigners == signerDigests(archive)
        } catch (t: Throwable) {
            Log.e(TAG_UPDATE, "Could not verify update APK", t)
            false
        }
    }

    /// Rust reconciles the runtime foreground service against the
    /// process tree (`gpui_android::runtime_service`) and pushes the
    /// result here on transitions. Starting is idempotent and re-reads
    /// `keepAwake`, so a settings change while active is one more start.
    /// Returns false when Android refused the start, so Rust retries.
    @Suppress("unused") // called from Rust via JNI
    fun setRuntimeServiceActive(active: Boolean, keepAwake: Boolean): Boolean {
        val applied = ZdRuntimeService.setChildrenActive(this, active, keepAwake)
        if (active) requestNotificationPermissionOnce()
        return applied
    }

    private var notificationPermissionRequested: Boolean = false

    /// Android 13+ posts no notification, the foreground service's
    /// included, until POST_NOTIFICATIONS is granted. Asked once, the
    /// first time the service starts; the service runs either way, a
    /// denial only hides its notification.
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionRequested) return
        notificationPermissionRequested = true
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        runOnUiThread {
            try {
                ActivityCompat.requestPermissions(this, arrayOf(permission), REQ_POST_NOTIFICATIONS)
            } catch (t: Throwable) {
                Log.w(TAG_RUNTIME, "POST_NOTIFICATIONS request failed", t)
            }
        }
    }

    @Suppress("unused")
    fun isIgnoringBatteryOptimizations(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    /// Open the system dialog that exempts this package from battery
    /// optimization (Doze and app standby stop background children
    /// otherwise). Returns true when the dialog opened or the exemption
    /// already holds; falls back to the exemption list screen on builds
    /// that refuse the direct request.
    @Suppress("unused")
    fun requestIgnoreBatteryOptimizations(): Boolean {
        if (isIgnoringBatteryOptimizations()) return true
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        return try {
            startActivity(direct)
            true
        } catch (t: Throwable) {
            Log.w(TAG_RUNTIME, "battery exemption dialog unavailable; opening the list", t)
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                true
            } catch (inner: Throwable) {
                Log.w(TAG_RUNTIME, "battery optimization settings unavailable", inner)
                false
            }
        }
    }

    /// Force a clean process exit when the Activity is destroyed.
    ///
    /// gpui_android has multiple static-state init paths (event channels,
    /// JNI globals, OnceLock guards) that assume process-scoped uniqueness.
    /// Android keeps the .so resident across Activity destroy/recreate
    /// cycles when memory pressure or AL_Kill reaps just the Activity but
    /// not the whole process. The next `android_main` re-entry then tries
    /// to re-initialize those statics, which either panics outright
    /// (multi_window event channel: "called twice") or silently leaves the
    /// new gpui state observing stale callbacks bound to the previous
    /// Activity.
    ///
    /// We've declared every config-change axis we care about in
    /// AndroidManifest.xml (`android:configChanges="orientation|...|
    /// uiMode|fontScale|..."`), so rotation, DeX, dark-mode flips, etc.
    /// don't destroy the Activity in the first place — those keep the
    /// process and Activity continuous, no re-entry. The only paths that
    /// reach `onDestroy` are genuine teardowns: user closed the app,
    /// system killed for memory, finishAndRemoveTask. For those, killing
    /// the process here guarantees the next launch starts fresh with
    /// zero stale static state.
    override fun onDestroy() {
        Log.i(
            TAG,
            "onDestroy isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations — exiting process for clean restart",
        )
        dnsCallback?.let { callback ->
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
        dnsCallback = null
        getSystemService(InputManager::class.java)
            ?.unregisterInputDeviceListener(inputDeviceListener)
        contentResolver.unregisterContentObserver(showImeWithHardKeyboardObserver)
        pointer.release()
        // A swiped-away task reaches here with the Rust side still alive:
        // end the children it spawned before the process goes, then drop
        // the runtime service so no orphan notification lingers.
        try {
            NativeBridge.nativeBeforeProcessExit()
        } catch (t: Throwable) {
            Log.w(TAG_RUNTIME, "nativeBeforeProcessExit failed", t)
        }
        stopService(Intent(this, ZdRuntimeService::class.java))
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INSTALL_PERMISSION) {
            // The settings page always returns RESULT_CANCELED; ask again.
            NativeBridge.nativeOnInstallPermissionResult(packageManager.canRequestPackageInstalls())
        }
    }

    companion object {
        private const val TAG = "zed_android_main"
        private const val TAG_UPDATE = "zed_android_update"
        private const val TAG_RUNTIME = "zed_android_runtime"
        private const val REQ_INSTALL_PERMISSION = 0xA4
        private const val REQ_POST_NOTIFICATIONS = 0xA5
        private const val PREFS = "zdroid"
        private const val PREF_ALL_FILES_ASKED = "all_files_access_asked"
        /// Matches `captured_pointer::PRIMARY_WINDOW_ID` on the Rust side.
        const val PRIMARY_WINDOW_ID: Long = 0
    }
}
