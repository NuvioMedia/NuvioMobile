package com.nuvio.app.features.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed interface TelegramTdAuthState {
    data object Idle : TelegramTdAuthState
    data object Initializing : TelegramTdAuthState
    data object WaitPhone : TelegramTdAuthState
    data class WaitCode(val length: Int) : TelegramTdAuthState
    data object WaitPassword : TelegramTdAuthState
    data class WaitQr(val link: String) : TelegramTdAuthState
    data class Ready(val firstName: String, val userId: Long) : TelegramTdAuthState
    data class Failed(val message: String) : TelegramTdAuthState
}

internal class TelegramTdApiException(val code: Int, override val message: String) : Exception(message)

internal class TelegramTdClient(
    context: Context,
    private val apiIdProvider: () -> Int,
    private val apiHashProvider: () -> String,
) {
    companion object {
        private const val TAG = "NuvioTelegramTD"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _authState = MutableStateFlow<TelegramTdAuthState>(TelegramTdAuthState.Idle)
    val authState: StateFlow<TelegramTdAuthState> = _authState.asStateFlow()

    @Volatile private var client: Client? = null
    @Volatile private var initializationJob: Job? = null
    @Volatile private var nativeAvailable: Boolean? = null

    fun isNativeAvailable(): Boolean {
        nativeAvailable?.let { return it }
        val available = try {
            System.loadLibrary("tdjni")
            true
        } catch (error: Throwable) {
            Log.e(TAG, "TDLib native runtime could not be loaded", error)
            false
        }
        nativeAvailable = available
        return available
    }

    fun isAuthenticated(): Boolean = _authState.value is TelegramTdAuthState.Ready

    @Synchronized
    fun initialize() {
        if (client != null || initializationJob?.isActive == true) return
        initializationJob = scope.launch {
            try {
                if (client != null) return@launch
                val apiId = apiIdProvider()
                val apiHash = apiHashProvider()
                if (!isNativeAvailable()) {
                    _authState.value = TelegramTdAuthState.Failed("TDLib native library is unavailable on this device.")
                    return@launch
                }
                if (apiId <= 0 || !apiHash.matches(Regex("[a-fA-F0-9]{32}"))) {
                    _authState.value = TelegramTdAuthState.Failed("Enter a valid Telegram API ID and 32-character API hash.")
                    return@launch
                }

                _authState.value = TelegramTdAuthState.Initializing
                try {
                    runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
                    val created = Client.create(
                        { update -> handleUpdate(update) },
                        { error -> Log.e(TAG, "TDLib update exception", error) },
                        { error -> Log.e(TAG, "TDLib default exception", error) },
                    )
                    // TDLib first emits AuthorizationStateWaitTdlibParameters; the state handler
                    // sends configuration exactly once instead of racing the startup sequence.
                    client = created
                } catch (error: Throwable) {
                    Log.e(TAG, "TDLib initialization failed", error)
                    client = null
                    _authState.value = TelegramTdAuthState.Failed(error.message ?: "Unable to initialize Telegram.")
                }
            } finally {
                initializationJob = null
            }
        }
    }

    private fun sendTdlibParameters(target: Client, apiId: Int, apiHash: String) {
        val databaseDirectoryFile = File(appContext.filesDir, "telegram/tdlib")
        val filesDirectoryFile = File(appContext.filesDir, "telegram/files")
        databaseDirectoryFile.mkdirs()
        filesDirectoryFile.mkdirs()
        val databaseDirectory = databaseDirectoryFile.absolutePath
        val filesDirectory = filesDirectoryFile.absolutePath
        val parameters = TdApi.SetTdlibParameters().also { value ->
            value.apiId = apiId
            value.apiHash = apiHash
            value.databaseDirectory = databaseDirectory
            value.filesDirectory = filesDirectory
            value.useFileDatabase = true
            value.useChatInfoDatabase = true
            value.useMessageDatabase = false
            value.useSecretChats = false
            value.systemLanguageCode = Locale.getDefault().toLanguageTag().ifBlank { "en" }
            value.deviceModel = Build.MODEL?.takeIf(String::isNotBlank) ?: "Nuvio Android"
            value.systemVersion = "Android ${Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT}"
            value.applicationVersion = "Nuvio"
        }
        target.send(parameters) { result ->
            if (result is TdApi.Error && client === target) {
                Log.e(TAG, "TDLib rejected initialization parameters: ${result.message}")
                _authState.value = TelegramTdAuthState.Failed(
                    result.message ?: "Telegram rejected the TDLib configuration.",
                )
            }
        }
    }

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthorizationState(update.authorizationState)
            is TdApi.Error -> {
                val state = _authState.value
                if (state is TelegramTdAuthState.Initializing ||
                    state is TelegramTdAuthState.WaitPhone ||
                    state is TelegramTdAuthState.WaitCode ||
                    state is TelegramTdAuthState.WaitPassword ||
                    state is TelegramTdAuthState.WaitQr
                ) {
                    _authState.value = TelegramTdAuthState.Failed(update.message ?: "Telegram authorization failed.")
                }
            }
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters ->
                client?.let { sendTdlibParameters(it, apiIdProvider(), apiHashProvider()) }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = TelegramTdAuthState.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> {
                val length = when (val type = state.codeInfo.type) {
                    is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
                    is TdApi.AuthenticationCodeTypeSms -> type.length
                    else -> 5
                }
                _authState.value = TelegramTdAuthState.WaitCode(length)
            }
            is TdApi.AuthorizationStateWaitPassword -> _authState.value = TelegramTdAuthState.WaitPassword
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                _authState.value = TelegramTdAuthState.WaitQr(state.link)
            is TdApi.AuthorizationStateReady -> {
                val readyClient = client ?: return
                scope.launch {
                    try {
                        val user = sendRequest(TdApi.GetMe()) as? TdApi.User
                        if (client !== readyClient) return@launch
                        File(appContext.filesDir, "telegram/session_ready").apply {
                            parentFile?.mkdirs()
                            createNewFile()
                        }
                        _authState.value = TelegramTdAuthState.Ready(
                            firstName = user?.firstName.orEmpty(),
                            userId = user?.id ?: 0L,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (client === readyClient) {
                            _authState.value = TelegramTdAuthState.Failed(error.message ?: "Could not load Telegram account.")
                        }
                    }
                }
            }
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateClosed -> _authState.value = TelegramTdAuthState.Idle
            else -> Unit
        }
    }

    suspend fun requestQrCode() {
        sendAuthenticationRequest(TdApi.RequestQrCodeAuthentication(LongArray(0)))
    }

    suspend fun submitPhone(phone: String) {
        sendAuthenticationRequest(TdApi.SetAuthenticationPhoneNumber(phone, null))
    }

    suspend fun submitCode(code: String) {
        sendAuthenticationRequest(TdApi.CheckAuthenticationCode(code))
    }

    suspend fun submitPassword(password: String) {
        sendAuthenticationRequest(TdApi.CheckAuthenticationPassword(password))
    }

    suspend fun submitBotToken(token: String) {
        sendAuthenticationRequest(TdApi.CheckAuthenticationBotToken(token))
    }

    private suspend fun sendAuthenticationRequest(function: TdApi.Function<out TdApi.Object>) {
        val response = sendRequest(function, timeoutMs = 15_000L)
        if (response == null) {
            throw TelegramTdApiException(0, "Telegram did not respond to the authentication request. Try again.")
        }
    }

    suspend fun sendRequest(
        function: TdApi.Function<out TdApi.Object>,
        timeoutMs: Long = 15_000L,
    ): TdApi.Object? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val current = client
            if (current == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            current.send(function) { result ->
                if (continuation.isActive) {
                    if (result is TdApi.Error) {
                        continuation.resumeWithException(
                            TelegramTdApiException(result.code, result.message ?: "Telegram request failed."),
                        )
                    } else {
                        continuation.resume(result)
                    }
                }
            }
        }
    }

    suspend fun resetAndWait(logOut: Boolean = false) {
        initializationJob?.join()
        val current = client ?: run {
            _authState.value = TelegramTdAuthState.Idle
            return
        }
        try {
            if (logOut) {
                // TDLib invalidates the authorization state and removes the server-side session.
                try {
                    sendRequest(TdApi.LogOut(), timeoutMs = 15_000L)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Telegram logout did not complete cleanly", error)
                }
            }
            withTimeoutOrNull<Unit>(15_000L) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    current.send(TdApi.Close()) { result ->
                        if (continuation.isActive) {
                            if (result is TdApi.Error) {
                                continuation.resumeWithException(
                                    TelegramTdApiException(result.code, result.message ?: "Could not close Telegram session."),
                                )
                            } else {
                                continuation.resume(Unit)
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "TDLib close request did not complete cleanly", error)
        } finally {
            client = null
            _authState.value = TelegramTdAuthState.Idle
        }
    }

    @Synchronized
    fun reset() {
        initializationJob?.cancel()
        initializationJob = null
        val current = client
        client = null
        if (current != null) {
            current.send(TdApi.Close(), null)
        }
        _authState.value = TelegramTdAuthState.Idle
    }
}
