package com.lumacam.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.lumacam.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Lo que publica GitHub Actions en la Release junto al APK (version.json). */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** Falta "Instalar apps desconocidas" para LumaCam (se pide una sola vez). */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float?) : UpdateState
    /** Android espera que el usuario toque "Actualizar" en su diálogo. */
    data class Confirm(val info: UpdateInfo, val intent: Intent) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}

/**
 * Actualizaciones dentro de la app, sin navegador: lee version.json de la última Release, descarga
 * el APK directamente a una sesión de PackageInstaller y Android pide confirmar. Así se evita el
 * problema de Chrome con los .apk (la descarga se queda en "Descargando… 100%").
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val CHECK_EVERY_MS = 30 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastCheck = 0L

    private val busy: Boolean
        get() = when (_state.value) {
            is UpdateState.Downloading, is UpdateState.Confirm, is UpdateState.Installing -> true
            else -> job?.isActive == true
        }

    /** Al volver a la app: sigue si ya se dio el permiso; si no, comprueba (como mucho cada 30 min). */
    fun onAppVisible(context: Context) {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) {
            install(context, s.info)
        } else {
            check(context, force = false)
        }
    }

    fun check(context: Context, force: Boolean) {
        if (busy) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheck != 0L && now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        job = scope.launch {
            if (force) _state.value = UpdateState.Checking
            _state.value = try {
                val info = withContext(Dispatchers.IO) { fetchInfo() }
                if (info.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(info) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo consultar la última versión", e)
                if (force) UpdateState.Failed("No se pudo comprobar. ¿Hay internet?", null) else UpdateState.Idle
            }
        }
    }

    fun install(context: Context, info: UpdateInfo) {
        if (busy) return
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            return
        }
        job = scope.launch {
            _state.value = UpdateState.Downloading(info, 0f)
            try {
                withContext(Dispatchers.IO) {
                    downloadAndCommit(app, info) { p -> _state.value = UpdateState.Downloading(info, p) }
                }
                // El resultado de Android puede haber llegado ya (diálogo de confirmación).
                if (_state.value is UpdateState.Downloading) _state.value = UpdateState.Installing(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Fallo descargando la actualización", e)
                _state.value = UpdateState.Failed(e.message ?: "No se pudo descargar la actualización", info)
            }
        }
    }

    /** Ajuste del sistema "Instalar apps desconocidas" para LumaCam. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Vuelve a abrir el diálogo de Android si se cerró sin contestar. */
    fun openConfirmation(context: Context) {
        val s = _state.value as? UpdateState.Confirm ?: return
        launchConfirmation(context, s.intent)
    }

    fun dismiss() {
        if (_state.value is UpdateState.Confirm || !busy) _state.value = UpdateState.Idle
    }

    internal fun onNeedsConfirmation(context: Context, intent: Intent) {
        currentInfo()?.let { _state.value = UpdateState.Confirm(it, intent) }
        launchConfirmation(context, intent)
    }

    internal fun onFinished(status: Int, message: String?) {
        val info = currentInfo()
        _state.value = when (status) {
            // La app se reinicia sola al terminar de instalarse encima.
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed("Actualización cancelada.", info)
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("No hay espacio suficiente para actualizar.", info)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed("Android no deja instalar encima de esta versión. Desinstala LumaCam e instálala desde la página de descarga.", info)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateState.Failed("Android bloqueó la instalación.", info)
            else -> UpdateState.Failed(message?.takeIf { it.isNotBlank() } ?: "No se pudo instalar la actualización.", info)
        }
    }

    private fun currentInfo(): UpdateInfo? = when (val s = _state.value) {
        is UpdateState.Available -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Confirm -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    }

    private fun launchConfirmation(context: Context, intent: Intent) {
        try {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Sin diálogo de instalación", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "No se pudo abrir el diálogo de instalación", e)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "LumaCam/${BuildConfig.VERSION_NAME} (Android)")
        conn.setRequestProperty("Cache-Control", "no-cache")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("El servidor respondió $code")
        }
        return conn
    }

    private fun fetchInfo(): UpdateInfo {
        val conn = open(BuildConfig.VERSION_URL)
        val text = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val o = JSONObject(text)
        return UpdateInfo(
            versionCode = o.getInt("versionCode"),
            versionName = o.getString("versionName"),
            apkUrl = o.getString("apkUrl"),
            size = o.optLong("size", -1L),
            sha256 = o.optString("sha256").takeIf { it.length == 64 },
        )
    }

    /** Descarga el APK directamente dentro de la sesión de instalación y la confirma. */
    private fun downloadAndCommit(context: Context, info: UpdateInfo, onProgress: (Float?) -> Unit) {
        val conn = open(info.apkUrl)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size.takeIf { it > 0 } ?: -1L
            if (total < 0) onProgress(null)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (total > 0) setSize(total)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                session.openWrite("LumaCam.apk", 0, total).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                    session.fsync(out)
                }
                val expected = info.sha256
                if (expected != null) {
                    val got = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!got.equals(expected, ignoreCase = true)) {
                        throw IOException("La descarga llegó incompleta o cambió la versión. Vuelve a intentarlo.")
                    }
                }
                val callback = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                // MUTABLE: Android añade el resultado (y el diálogo a mostrar) a este Intent explícito.
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutable
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pending.intentSender)
                session.close()
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        } finally {
            conn.disconnect()
        }
    }
}
