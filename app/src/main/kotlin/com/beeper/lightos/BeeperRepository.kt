package com.beeper.lightos

import kotlinx.coroutines.launch

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.login
import net.folivo.trixnity.client.fromStore
import net.folivo.trixnity.client.media.InMemoryMediaStore
import net.folivo.trixnity.client.store.repository.room.createRoomRepositoriesModule
import net.folivo.trixnity.client.media.okio.OkioMediaStore
import okio.FileSystem
import okio.Path.Companion.toPath
import java.util.UUID

object BeeperRepository {
    /** Captured from outgoing OkHttp requests — used for authenticated media downloads. */
    @Volatile private var _accessToken: String? = null
    fun getAccessToken(): String? = _accessToken

    private val matrixClientConfiguration: net.folivo.trixnity.client.MatrixClientConfiguration.() -> Unit = {
        android.util.Log.d("BeeperInterceptor", "Setting up MatrixClientConfiguration!")
        httpClientEngine = io.ktor.client.engine.okhttp.OkHttp.create {
            addInterceptor(okhttp3.Interceptor { chain ->
                val request = chain.request()
                android.util.Log.d("BeeperInterceptor", "OkHttp HTTP request to: ${request.url}")

                // Capture the Matrix access token from the first authenticated request.
                // Exclude the hardcoded Beeper public API token.
                if (_accessToken == null) {
                    request.header("Authorization")
                        ?.removePrefix("Bearer ")
                        ?.takeIf { it.isNotBlank() && it != "BEEPER-PRIVATE-API-PLEASE-DONT-USE" }
                        ?.also { _accessToken = it }
                }

                val response = chain.proceed(request)
                if (request.url.encodedPath.contains("/keys/claim")) {
                    android.util.Log.d("BeeperInterceptor", "OkHttp Intercepted /keys/claim!")
                    val body = response.body
                    if (body != null) {
                        val stringBody = body.string()
                        android.util.Log.d("BeeperInterceptor", "Original response: $stringBody")
                        val newString = if (!stringBody.contains("\"failures\"")) {
                            stringBody.replaceFirst("{", "{\"failures\":{},")
                        } else stringBody
                        android.util.Log.d("BeeperInterceptor", "New response: $newString")
                        val newBody = okhttp3.ResponseBody.create(body.contentType(), newString)
                        return@Interceptor response.newBuilder().body(newBody).build()
                    }
                }
                response
            })
        }
    }

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private var matrixClient: MatrixClient? = null
    private val httpClient = HttpClient()
    private var isInitialized = false
    var appContext: android.content.Context? = null

    suspend fun getOrInitMatrixClient(context: android.content.Context): MatrixClient? {
        if (matrixClient != null) return matrixClient
        
        try {
            val builder = androidx.room.Room.databaseBuilder(
                context.applicationContext,
                net.folivo.trixnity.client.store.repository.room.TrixnityRoomDatabase::class.java,
                "matrix_client"
            )
            val mediaCacheDir = context.applicationContext.cacheDir.absolutePath + "/matrix_media"
            val clientResult = MatrixClient.fromStore(
                repositoriesModule = createRoomRepositoriesModule(builder),
                mediaStore = OkioMediaStore(mediaCacheDir.toPath(), FileSystem.SYSTEM),
                configuration = matrixClientConfiguration
            )
            if (clientResult.isSuccess) {
                val client = clientResult.getOrThrow()
                if (client != null) {
                    matrixClient = client
                    println("Beeper restored session from store")
                    return client
                } else {
                    println("Beeper session not found in store")
                    val prefs = context.getSharedPreferences("beeper_prefs", android.content.Context.MODE_PRIVATE)
                    val beeperUsername = prefs.getString("beeper_username", null)
                    val accessToken = prefs.getString("beeper_access_token", null)
                    if (beeperUsername != null && accessToken != null) {
                        println("Beeper logging in with saved token...")
                        val loginResult = MatrixClient.login(
                            baseUrl = io.ktor.http.Url("https://matrix.beeper.com"),
                            identifier = net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType.User(beeperUsername),
                            token = accessToken,
                            loginType = net.folivo.trixnity.clientserverapi.model.authentication.LoginType.Token(),
                            deviceId = beeperUsername,
                            initialDeviceDisplayName = "LightOS",
                            repositoriesModule = createRoomRepositoriesModule(builder),
                            mediaStore = OkioMediaStore(mediaCacheDir.toPath(), FileSystem.SYSTEM),
                            configuration = matrixClientConfiguration,
                        )
                        if (loginResult.isSuccess) {
                            val newClient = loginResult.getOrThrow()
                            matrixClient = newClient
                            println("Beeper login successful")
                            return newClient
                        } else {
                            println("Beeper login failed: ${loginResult.exceptionOrNull()}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    suspend fun init(androidContext: android.content.Context) {
        if (isInitialized) return
        appContext = androidContext.applicationContext
        isInitialized = true
        
        val client = getOrInitMatrixClient(androidContext)
        if (client != null) {
            client.startSync()
            _isLoggedIn.value = true
            
            pendingPushEndpoint?.let { endpoint ->
                registerPushEndpoint(endpoint)
            }
            
            com.thelightphone.sdk.LightWork.enqueuePeriodic(
                com.thelightphone.sdk.SealedLightContext(androidContext),
                "beeper-sync",
                kotlin.time.Duration.parse("15m")
            )
        }
    }

    fun restoreMegolmSession(roomId: net.folivo.trixnity.core.model.RoomId, sessionId: String) {
        val currentClient = getClient() ?: return
        kotlinx.coroutines.GlobalScope.launch {
            try {
                val keyBackupService = currentClient.di.get<net.folivo.trixnity.client.key.KeyBackupService>(
                    org.koin.core.qualifier.named<net.folivo.trixnity.client.key.KeyBackupService>()
                )
                android.util.Log.d("BeeperRepository", "Attempting to restore megolm session $sessionId for room $roomId")
                keyBackupService.loadMegolmSession(roomId, sessionId)
            } catch (e: Exception) {
                android.util.Log.e("BeeperRepository", "Failed to restore megolm session $sessionId", e)
            }
        }
    }

    suspend fun syncOnce(context: android.content.Context) {
        println("Beeper background sync started")
        val client = getOrInitMatrixClient(context)
        if (client != null) {
            client.syncOnce()
            println("Beeper background sync finished")
        } else {
            println("Beeper background sync failed: no client")
        }
    }

    suspend fun startLogin(email: String): Result<String> {
        return try {
            val initResponse: HttpResponse = httpClient.post("https://api.beeper.com/user/login") {
                header("Authorization", "Bearer BEEPER-PRIVATE-API-PLEASE-DONT-USE")
                contentType(ContentType.Application.Json)
            }
            if (initResponse.status.value !in 200..299) {
                return Result.failure(Exception("Failed to init login: ${initResponse.status}"))
            }
            val initBody = initResponse.bodyAsText()
            val initJson = Json { ignoreUnknownKeys = true }.parseToJsonElement(initBody).jsonObject
            val requestId = initJson["request"]?.jsonPrimitive?.content ?: throw Exception("Missing request id")

            val response: HttpResponse = httpClient.post("https://api.beeper.com/user/login/email") {
                header("Authorization", "Bearer BEEPER-PRIVATE-API-PLEASE-DONT-USE")
                contentType(ContentType.Application.Json)
                setBody("""{"request":"$requestId","email":"$email"}""")
            }
            if (response.status.value in 200..299) {
                Result.success(requestId)
            } else {
                Result.failure(Exception("Failed to request email code: ${response.status}"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private var pendingPushEndpoint: String? = null

    suspend fun registerPushEndpoint(pushEndpoint: String) {
        val currentClient = matrixClient
        if (currentClient == null) {
            android.util.Log.e("BeeperRepository", "matrixClient is null, caching pusher for later")
            pendingPushEndpoint = pushEndpoint
            return
        }
        
        try {
            val data = net.folivo.trixnity.clientserverapi.model.push.PusherData(
                format = "event_id_only",
                url = pushEndpoint,
                customFields = kotlinx.serialization.json.buildJsonObject {}
            )
            val request = net.folivo.trixnity.clientserverapi.model.push.SetPushers.Request.Set(
                appId = "me.ironfeet.beeper4lightos",
                pushkey = pushEndpoint,
                kind = "http",
                appDisplayName = "Beeper4LightOS",
                deviceDisplayName = "LightOS",
                lang = "en",
                data = data,
                append = false,
                profileTag = ""
            )
            currentClient.api.push.setPushers(request).getOrThrow()
            android.util.Log.d("BeeperRepository", "Successfully registered pusher")
            pendingPushEndpoint = null
        } catch (e: Exception) {
            android.util.Log.e("BeeperRepository", "Failed to register pusher", e)
        }
    }

    suspend fun forceBackgroundSync() {
        android.util.Log.d("BeeperRepository", "forceBackgroundSync called")
        val context = appContext
        if (context == null) {
            android.util.Log.e("BeeperRepository", "appContext is null, cannot sync")
            return
        }
        // This will initialize and startSync if not already done.
        init(context)
    }

    suspend fun verifyLogin(requestId: String, code: String): Result<Unit> {
        return try {
            val response: HttpResponse = httpClient.post("https://api.beeper.com/user/login/response") {
                header("Authorization", "Bearer BEEPER-PRIVATE-API-PLEASE-DONT-USE")
                contentType(ContentType.Application.Json)
                setBody("""{"request":"$requestId","response":"$code"}""")
            }

            if (response.status.value !in 200..299) {
                val errorBody = response.bodyAsText()
                println("Beeper verifyLogin failed with ${response.status}: $errorBody")
                return Result.failure(Exception("Failed to verify code: ${response.status} - $errorBody"))
            }

            val responseBody = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(responseBody).jsonObject
            println("Beeper Login Response: $json")
            val whoami = json["whoami"]?.jsonObject ?: throw Exception("Missing whoami in response")
            val user = whoami["user"]?.jsonObject ?: throw Exception("Missing user in response")
            val userInfo = whoami["userInfo"]?.jsonObject ?: throw Exception("Missing userInfo in response")
            val username = userInfo["username"]?.jsonPrimitive?.content ?: throw Exception("Missing username")
            user["asmuxData"]?.jsonObject ?: throw Exception("Missing asmuxData in response")
            val loginToken = json["token"]?.jsonPrimitive?.content ?: throw Exception("Missing root token")

            // Now perform custom JWT matrix login for Beeper
            val client = MatrixClient.login(
                baseUrl = Url("https://matrix.beeper.com"),
                identifier = net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType.User(username),
                loginType = net.folivo.trixnity.clientserverapi.model.authentication.LoginType.Unknown("org.matrix.login.jwt", kotlinx.serialization.json.buildJsonObject {}),
                password = null,
                token = loginToken,
                repositoriesModule = createRoomRepositoriesModule(
                    androidx.room.Room.databaseBuilder(
                        appContext!!,
                        net.folivo.trixnity.client.store.repository.room.TrixnityRoomDatabase::class.java,
                        "matrix_client"
                    )
                ),
                mediaStore = OkioMediaStore((appContext!!.cacheDir.absolutePath + "/matrix_media").toPath(), FileSystem.SYSTEM),
                initialDeviceDisplayName = "Beeper4LightOS",
                configuration = matrixClientConfiguration
            ).getOrThrow()

            matrixClient = client
            client.startSync()
            _isLoggedIn.value = true
            com.thelightphone.sdk.LightWork.enqueuePeriodic(
                com.thelightphone.sdk.SealedLightContext(appContext!!),
                "beeper-sync",
                kotlin.time.Duration.parse("15m")
            )
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    fun getClient(): MatrixClient? = matrixClient

    suspend fun logout() {
        try {
            appContext?.let { ctx ->
                com.thelightphone.sdk.LightWork.cancel(
                    com.thelightphone.sdk.SealedLightContext(ctx),
                    "beeper-sync"
                )
            }
            matrixClient?.stopSync()
            matrixClient = null
            _isLoggedIn.value = false
            
            appContext?.let { ctx ->
                val dbFile = ctx.getDatabasePath("matrix_client")
                if (dbFile.exists()) {
                    ctx.deleteDatabase("matrix_client")
                }
                val prefs = ctx.getSharedPreferences("beeper_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
                
                val mediaCacheDir = java.io.File(ctx.cacheDir, "matrix_media")
                if (mediaCacheDir.exists()) {
                    mediaCacheDir.deleteRecursively()
                }
            }
            _accessToken = null
            println("Beeper logged out successfully")
        } catch (e: Exception) {
            e.printStackTrace()
            println("Beeper logout failed: ${e.message}")
        }
    }
}
