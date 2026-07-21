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

// Router fingerprinting data models
data class HttpBanner(
    val server_header: String?,
    val page_title: String?,
    val meta_vendor: String?
)

data class RouterFingerprintRequest(
    val gateway_ip: String,
    val mac_prefix: String,
    val http_banner: HttpBanner?
)

data class ExecutionProfile(
    val target_login_url: String?,
    val request_method: String?,
    val payload_format: String?,
    val username_field: String?,
    val password_field: String?
)

data class RealTimeCredential(
    val username: String,
    val password: String
)

data class RouterFingerprintResponse(
    val status: String,
    val brand: String,
    val requires_unique_sticker_password: Boolean,
    val execution_profile: ExecutionProfile?,
    val real_time_credentials: List<RealTimeCredential>
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

    @POST("api/v1/router/fingerprint")
    suspend fun fingerprintRouter(@Body request: RouterFingerprintRequest): RouterFingerprintResponse

    @POST("api/listings")
    suspend fun createListing(@Body request: CreateListingRequest): ListingResponse
}

data class CreateListingRequest(
    val connectionType: String,
    val pricePerMin: Double,
    val ssid: String,
    val bssid: String,
    val beamSpotSsid: String?,
    val routerIp: String? = null,
    val routerApiPort: Int? = null,
    val routerUsername: String? = null,
    val routerPassword: String? = null
)

data class ListingDetail(
    val id: String,
    val host_id: String,
    val connection_type: String,
    val price_per_min: Double,
    val ssid: String,
    val bssid: String,
    val beamspot_ssid: String?,
    val status: String
)

data class ListingResponse(
    val listing: ListingDetail
)

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
