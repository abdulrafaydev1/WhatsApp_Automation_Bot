package com.hybrid.whatsappbot.network

import android.content.Context
import com.hybrid.whatsappbot.data.AppPreferences
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

data class LinkedUserDto(
    val id: String?,
    val name: String?
)

data class ServerStatusResponse(
    val success: Boolean = false,
    val status: String = "DISCONNECTED", // CONNECTED | QR_READY | DISCONNECTED
    val qrCode: String? = null,
    val user: LinkedUserDto? = null,
    val processedCount: Int = 0,
    val timestamp: Long = 0L,
    val error: String? = null
)

data class WhatsAppGroupDto(
    val id: String,
    val subject: String,
    val participantsCount: Int = 0
)

data class GroupsResponse(
    val success: Boolean = false,
    val count: Int = 0,
    val groups: List<WhatsAppGroupDto> = emptyList(),
    val error: String? = null
)

data class SendMediaResponse(
    val success: Boolean = false,
    val duplicate: Boolean = false,
    val taskId: String? = null,
    val groupJid: String? = null,
    val messageId: String? = null,
    val timestamp: Long? = null,
    val error: String? = null
)

interface BotApiService {

    @GET("/api/v1/status")
    suspend fun getServerStatus(): Response<ServerStatusResponse>

    @GET("/api/v1/groups")
    suspend fun getParticipatingGroups(): Response<GroupsResponse>

    @Multipart
    @POST("/api/v1/send-group-media")
    suspend fun sendGroupMedia(
        @Part("taskId") taskId: RequestBody,
        @Part("groupName") groupName: RequestBody,
        @Part("groupJid") groupJid: RequestBody,
        @Part("caption") caption: RequestBody,
        @Part image: MultipartBody.Part
    ): Response<SendMediaResponse>
}

object RetrofitClient {

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedService: BotApiService? = null

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    fun getApiService(context: Context): BotApiService {
        val rawUrl = AppPreferences(context).backendBaseUrl.trim()
        val normalizedBaseUrl = buildValidBaseUrl(rawUrl)

        val currentService = cachedService
        if (currentService != null && cachedBaseUrl == normalizedBaseUrl) {
            return currentService
        }

        return synchronized(this) {
            val recheckService = cachedService
            if (recheckService != null && cachedBaseUrl == normalizedBaseUrl) {
                recheckService
            } else {
                val retrofit = Retrofit.Builder()
                    .baseUrl(normalizedBaseUrl)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                val newService = retrofit.create(BotApiService::class.java)
                cachedBaseUrl = normalizedBaseUrl
                cachedService = newService
                newService
            }
        }
    }

    private fun buildValidBaseUrl(url: String): String {
        val withProtocol = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "http://$url"
        }
        return if (withProtocol.endsWith("/")) withProtocol else "$withProtocol/"
    }
}
