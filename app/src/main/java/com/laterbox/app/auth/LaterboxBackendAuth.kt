package com.laterbox.app.auth

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.laterbox.app.network.LaterboxConnectivityObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlin.coroutines.resumeWithException

sealed interface LaterboxBackendCredential {
    val headerName: String
    val headerValue: String

    fun toHeaderPair(): Pair<String, String> = headerName to headerValue

    data class AppCheck(val token: String) : LaterboxBackendCredential {
        override val headerName: String = LaterboxBackendAuthProvider.HEADER_APP_CHECK
        override val headerValue: String = token
    }

    data class GuestAuth(val idToken: String) : LaterboxBackendCredential {
        override val headerName: String = LaterboxBackendAuthProvider.HEADER_GUEST_AUTH
        override val headerValue: String = idToken
    }
}

/**
 * Coordinates automatic backend credential selection while keeping verified Play Integrity / App Check
 * strictly primary and isolated from the lower-trust Firebase anonymous guest session path used on
 * unsupported PC emulators (such as LDPlayer) and non-Play-Integrity Android builds.
 */
class LaterboxBackendAuthProvider(
    private val appCheckFetcher: suspend (forceRefresh: Boolean) -> Result<String?>,
    private val guestTokenFetcher: suspend (forceRefresh: Boolean) -> Result<String?>,
    private val isNetworkValidatedOnline: () -> Boolean = { true },
    private val isEmulatorHint: () -> Boolean = { isLikelyEmulatorEnvironment() },
) {
    private val selectionMutex = Mutex()

    @Volatile
    private var verifiedAppCheckDevice: Boolean = false

    @Volatile
    private var appCheckUnsupportedOnDevice: Boolean = false

    val isUsingVerifiedAppCheck: Boolean
        get() = verifiedAppCheckDevice

    val isUsingGuestFallback: Boolean
        get() = appCheckUnsupportedOnDevice

    suspend fun resolveCredential(forceRefresh: Boolean = false): LaterboxBackendCredential? {
        return selectionMutex.withLock {
            // Once Play Integrity / App Check succeeds on this device, never downgrade it to guest auth.
            if (verifiedAppCheckDevice) {
                val appCheckToken = appCheckFetcher(forceRefresh).getOrNull()?.trim().orEmpty()
                return@withLock appCheckToken.takeIf { it.isNotBlank() }?.let {
                    LaterboxBackendCredential.AppCheck(it)
                }
            }

            if (appCheckUnsupportedOnDevice) {
                val guestToken = guestTokenFetcher(forceRefresh).getOrNull()?.trim().orEmpty()
                return@withLock guestToken.takeIf { it.isNotBlank() }?.let {
                    LaterboxBackendCredential.GuestAuth(it)
                }
            }

            val online = runCatching { isNetworkValidatedOnline() }.getOrDefault(true)
            val appCheckResult = try {
                appCheckFetcher(forceRefresh)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }

            val primaryToken = appCheckResult.getOrNull()?.trim().orEmpty()
            if (primaryToken.isNotBlank()) {
                verifiedAppCheckDevice = true
                appCheckUnsupportedOnDevice = false
                return@withLock LaterboxBackendCredential.AppCheck(primaryToken)
            }

            // Never treat an offline device or a temporary network error as proof that the device is an emulator.
            if (!online) {
                return@withLock null
            }
            val appCheckError = appCheckResult.exceptionOrNull()
            if (appCheckError != null && isTransientNetworkFailure(appCheckError)) {
                return@withLock null
            }

            val shouldAttemptGuestFallback = when {
                appCheckError != null -> isUnsupportedEnvironmentFailure(appCheckError) || isEmulatorHint()
                // Null token without exception typically indicates timeout or unavailable Play Integrity API
                else -> isEmulatorHint()
            }
            if (!shouldAttemptGuestFallback) {
                return@withLock null
            }

            val guestResult = try {
                guestTokenFetcher(forceRefresh)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }

            val guestToken = guestResult.getOrNull()?.trim().orEmpty()
            if (guestToken.isNotBlank()) {
                appCheckUnsupportedOnDevice = true
                return@withLock LaterboxBackendCredential.GuestAuth(guestToken)
            }

            val guestError = guestResult.exceptionOrNull()
            if (guestError != null && !isTransientNetworkFailure(guestError) &&
                (appCheckError == null || isUnsupportedEnvironmentFailure(appCheckError))
            ) {
                appCheckUnsupportedOnDevice = true
            }
            null
        }
    }

    suspend fun resolveHeaderPair(forceRefresh: Boolean = false): Pair<String, String>? {
        return resolveCredential(forceRefresh)?.toHeaderPair()
    }

    suspend fun resolveAppCheckOnlyToken(forceRefresh: Boolean = false): String? {
        return appCheckFetcher(forceRefresh).getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "LaterboxBackendAuth"
        const val HEADER_APP_CHECK = "X-Firebase-AppCheck"
        const val HEADER_GUEST_AUTH = "X-Firebase-Auth"
        private const val TOKEN_FETCH_TIMEOUT_MS = 5_000L

        fun createFirebaseBacked(context: Context): LaterboxBackendAuthProvider {
            val appContext = context.applicationContext
            val guestSessionMutex = Mutex()

            suspend fun fetchAppCheckToken(forceRefresh: Boolean): Result<String?> {
                if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                    return Result.success(null)
                }
                if (FirebaseApp.getApps(appContext).isEmpty()) {
                    return Result.success(null)
                }
                return try {
                    val fetched = withTimeoutOrNull(TOKEN_FETCH_TIMEOUT_MS) {
                        suspendCancellableCoroutine<Result<String?>> { cont ->
                            try {
                                FirebaseAppCheck.getInstance()
                                    .getAppCheckToken(forceRefresh)
                                    .addOnSuccessListener { tokenResult ->
                                        if (cont.isActive) {
                                            cont.resumeWith(
                                                Result.success(
                                                    Result.success(tokenResult.token.takeIf { it.isNotBlank() })
                                                )
                                            )
                                        }
                                    }
                                    .addOnFailureListener { err ->
                                        Log.w(TAG, "App Check token fetch failed: ${err.message}")
                                        if (cont.isActive) {
                                            cont.resumeWith(Result.success(Result.failure(err)))
                                        }
                                    }
                                    .addOnCanceledListener {
                                        if (cont.isActive) {
                                            cont.resumeWith(Result.success(Result.success(null)))
                                        }
                                    }
                            } catch (t: Throwable) {
                                if (cont.isActive) {
                                    cont.resumeWith(Result.success(Result.failure(t)))
                                }
                            }
                        }
                    }
                    fetched ?: Result.success(null)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }

            suspend fun signInAnonymouslySuspending(auth: FirebaseAuth): com.google.firebase.auth.FirebaseUser? {
                return withTimeoutOrNull(8_000L) {
                    suspendCancellableCoroutine { cont ->
                        auth.signInAnonymously()
                            .addOnSuccessListener { res ->
                                if (cont.isActive) {
                                    cont.resumeWith(Result.success(res.user))
                                }
                            }
                            .addOnFailureListener { err ->
                                if (cont.isActive) {
                                    cont.resumeWithException(err)
                                }
                            }
                            .addOnCanceledListener {
                                if (cont.isActive) {
                                    cont.resumeWith(Result.success(null))
                                }
                            }
                    }
                }
            }

            suspend fun awaitIdToken(
                user: com.google.firebase.auth.FirebaseUser,
                forceRefresh: Boolean,
            ): String? {
                return withTimeoutOrNull(8_000L) {
                    suspendCancellableCoroutine { cont ->
                        user.getIdToken(forceRefresh)
                            .addOnSuccessListener { res ->
                                if (cont.isActive) {
                                    cont.resumeWith(Result.success(res.token?.takeIf { it.isNotBlank() }))
                                }
                            }
                            .addOnFailureListener { err ->
                                if (cont.isActive) {
                                    cont.resumeWithException(err)
                                }
                            }
                            .addOnCanceledListener {
                                if (cont.isActive) {
                                    cont.resumeWith(Result.success(null))
                                }
                            }
                    }
                }
            }

            suspend fun fetchGuestIdToken(forceRefresh: Boolean): Result<String?> {
                if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                    return Result.success(null)
                }
                if (FirebaseApp.getApps(appContext).isEmpty()) {
                    return Result.success(null)
                }
                return guestSessionMutex.withLock {
                    try {
                        val auth = FirebaseAuth.getInstance()
                        var user = auth.currentUser ?: signInAnonymouslySuspending(auth)
                        if (user == null) {
                            return@withLock Result.success(null)
                        }
                        try {
                            val token = awaitIdToken(user, forceRefresh)
                            if (!token.isNullOrBlank()) {
                                return@withLock Result.success(token)
                            }
                            return@withLock Result.success(null)
                        } catch (invalidUser: FirebaseAuthInvalidUserException) {
                            Log.w(TAG, "Guest session invalidated (${invalidUser.errorCode}); recreating session")
                            runCatching { auth.signOut() }
                            user = signInAnonymouslySuspending(auth)
                            val recovered = user?.let { awaitIdToken(it, true) }
                            return@withLock Result.success(recovered?.takeIf { it.isNotBlank() })
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "Guest ID token acquisition failed: ${t.message}")
                        Result.failure(t)
                    }
                }
            }

            return LaterboxBackendAuthProvider(
                appCheckFetcher = ::fetchAppCheckToken,
                guestTokenFetcher = ::fetchGuestIdToken,
                isNetworkValidatedOnline = {
                    runCatching {
                        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                            ?: return@runCatching true
                        LaterboxConnectivityObserver.isNetworkValidatedOnline(cm)
                    }.getOrDefault(true)
                },
                isEmulatorHint = { isLikelyEmulatorEnvironment() },
            )
        }

        internal fun isTransientNetworkFailure(error: Throwable): Boolean {
            var cur: Throwable? = error
            var depth = 0
            while (cur != null && depth < 8) {
                if (cur is FirebaseNetworkException ||
                    cur is UnknownHostException ||
                    cur is ConnectException ||
                    cur is SocketTimeoutException ||
                    cur is IOException
                ) {
                    return true
                }
                val msg = cur.message?.lowercase(Locale.ROOT).orEmpty()
                if ((msg.contains("network error") ||
                        msg.contains("unable to resolve host") ||
                        msg.contains("failed to connect") ||
                        msg.contains("timed out") ||
                        msg.contains("timeout")) &&
                    !isUnsupportedEnvironmentFailure(cur)
                ) {
                    return true
                }
                cur = cur.cause
                depth++
            }
            return false
        }

        internal fun isUnsupportedEnvironmentFailure(error: Throwable): Boolean {
            var cur: Throwable? = error
            var depth = 0
            while (cur != null && depth < 8) {
                val cls = cur.javaClass.name.lowercase(Locale.ROOT)
                val msg = cur.message?.lowercase(Locale.ROOT).orEmpty()
                if (cls.contains("integrity") ||
                    cls.contains("playservicesnotavailable") ||
                    cls.contains("appcheck")
                ) {
                    if (!msg.contains("network error") && !msg.contains("timed out")) {
                        return true
                    }
                }
                if (msg.contains("play store") ||
                    msg.contains("play services") ||
                    msg.contains("integrity") ||
                    msg.contains("attestation") ||
                    msg.contains("api_not_available") ||
                    msg.contains("cannot bind to service") ||
                    msg.contains("too many attempts") ||
                    msg.contains("app not installed") ||
                    msg.contains("client transient") ||
                    msg.contains("403") ||
                    msg.contains("400")
                ) {
                    return true
                }
                cur = cur.cause
                depth++
            }
            return false
        }

        fun isLikelyEmulatorEnvironment(): Boolean {
            val fingerprint = Build.FINGERPRINT.orEmpty().lowercase(Locale.ROOT)
            val model = Build.MODEL.orEmpty().lowercase(Locale.ROOT)
            val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
            val brand = Build.BRAND.orEmpty().lowercase(Locale.ROOT)
            val device = Build.DEVICE.orEmpty().lowercase(Locale.ROOT)
            val product = Build.PRODUCT.orEmpty().lowercase(Locale.ROOT)
            val hardware = Build.HARDWARE.orEmpty().lowercase(Locale.ROOT)
            val board = Build.BOARD.orEmpty().lowercase(Locale.ROOT)
            return fingerprint.startsWith("generic") ||
                fingerprint.startsWith("unknown") ||
                fingerprint.contains("test-keys") ||
                model.contains("google_sdk") ||
                model.contains("emulator") ||
                model.contains("android sdk built for") ||
                model.contains("ldplayer") ||
                model.contains("bluestacks") ||
                manufacturer.contains("genymotion") ||
                hardware.contains("goldfish") ||
                hardware.contains("ranchu") ||
                hardware.contains("vbox86") ||
                hardware.contains("nox") ||
                hardware.contains("ttvm") ||
                product.contains("sdk_gphone") ||
                product.contains("emulator") ||
                product.contains("vbox86p") ||
                product.contains("nox") ||
                board.contains("goldfish") ||
                (brand.startsWith("generic") && device.startsWith("generic"))
        }
    }
}
