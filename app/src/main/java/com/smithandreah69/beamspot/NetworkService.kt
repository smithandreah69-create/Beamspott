package com.smithandreah69.beamspot

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.http.*

// ─── Network Request & Response Models ────────────────────────────────────

data class GoogleAuthRequest(
    val idToken: String
)

data class UserInfo(
    val id: String,
    val name: String,
    val email: String,
    val pictureUrl: String?,
    val hasSetupPayout: Boolean,
    val hasSetupListing: Boolean
)

data class GoogleAuthResponse(
    val token: String,
    val user: UserInfo
)

data class ScannedNetwork(
    val ssid: String,
    val bssid: String
)

data class NetworkVerifyRequest(
    val scanned: List<ScannedNetwork>
)

data class VerifiedNetwork(
    val id: String,
    val display_name: String,
    val ssid: String,
    val bssid: String,
    val price_per_min: Double,
    val connection_type: String,
    val host_name: String,
    val active_guests: Int
)

data class NetworkVerifyResponse(
    val verified: List<VerifiedNetwork>
)

data class CreateSessionRequest(
    val listingId: String,
    val guestDeviceId: String,
    val durationMin: Int,
    val paymentMethod: String,
    val phone: String,
    val guestIp: String? = null,
    val testBypassPassword: String? = null
)

data class CreateSessionResponse(
    val sessionId: String,
    val amountTotal: Double,
    val platformFee: Double,
    val hostPayout: Double,
    val checkoutUrl: String?
)

data class SessionStatusResponse(
    val status: String,
    val expiresAt: String?
)

data class PendingSessionResponse(
    val sessionId: String,
    val guestIp: String?,
    val guestDeviceId: String,
    val durationMin: Int,
    val status: String
)

data class HostStatsResponse(
    val earningsToday: Double,
    val activeGuests: Int,
    val minutesSold: Int,
    val pendingWithdrawal: Double
)

data class PayoutDetailsRequest(
    val payoutMethod: String,
    val payoutNumber: String,
    val bankName: String?,
    val bankAccount: String?,
    val bankHolder: String?
)

data class PayoutDetailsResponse(
    val payoutMethod: String?,
    val payoutNumber: String?,
    val bankName: String?,
    val bankAccount: String?,
    val bankHolder: String?
)

// ─── Retrofit Service Interface ──────────────────────────────────────────

interface ApiService {
    @POST("api/auth/google")
    suspend fun verifyGoogleIdToken(@Body request: GoogleAuthRequest): GoogleAuthResponse

    @POST("api/networks/verify")
    suspend fun verifyNetworks(@Body request: NetworkVerifyRequest): NetworkVerifyResponse

    @POST("api/sessions")
    suspend fun createSession(@Body request: CreateSessionRequest): CreateSessionResponse

    @GET("api/sessions/{id}")
    suspend fun getSessionStatus(@Path("id") id: String): SessionStatusResponse

    @GET("api/hosts/stats")
    suspend fun getHostStats(): HostStatsResponse

    @GET("api/hosts/sessions/pending")
    suspend fun getPendingSessions(): List<PendingSessionResponse>

    @POST("api/hosts/payout")
    suspend fun savePayout(@Body request: PayoutDetailsRequest): Any

    @GET("api/hosts/payout")
    suspend fun getPayout(): PayoutDetailsResponse

    @POST("api/hosts/withdraw")
    suspend fun withdrawEarnings(): Any
}

// ─── Retrofit Client Singleton ────────────────────────────────────────────

object RetrofitClient {
    private var jwtToken: String? = null

    fun setToken(token: String?) {
        jwtToken = token
    }

    fun getToken(): String? {
        return jwtToken
    }

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
            jwtToken?.let { token ->
                requestBuilder.header("Authorization", "Bearer $token")
            }
            chain.proceed(requestBuilder.build())
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }
}
