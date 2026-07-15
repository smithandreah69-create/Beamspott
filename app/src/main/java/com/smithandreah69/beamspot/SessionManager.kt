package com.smithandreah69.beamspot

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Single DataStore instance for the whole app
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "beamspot_session")

/**
 * Manages persistent session state using DataStore.
 * Survives app restarts and process death — unlike SharedPreferences + in-memory ViewModel.
 */
class SessionManager(private val context: Context) {

    companion object {
        private val KEY_JWT_TOKEN = stringPreferencesKey("jwt_token")
        private val KEY_IS_SIGNED_IN = booleanPreferencesKey("is_signed_in")
        private val KEY_HAS_COMPLETED_SETUP = booleanPreferencesKey("has_completed_setup")
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
        private val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        private val KEY_IS_DEMO_MODE = booleanPreferencesKey("is_demo_mode")
        private val KEY_ACTIVE_LISTING_ID = stringPreferencesKey("active_listing_id")
        private val KEY_SELECTED_MODE = stringPreferencesKey("selected_mode")
        private val KEY_BEAM_SPOT_NETWORK_NAME = stringPreferencesKey("beam_spot_network_name")
        private val KEY_PRICE_PER_MIN = stringPreferencesKey("price_per_min")
        private val KEY_PAYOUT_METHOD = stringPreferencesKey("payout_method")
        private val KEY_PAYOUT_NUMBER = stringPreferencesKey("payout_number")
        private val KEY_BANK_NAME = stringPreferencesKey("bank_name")
        private val KEY_BANK_ACCOUNT = stringPreferencesKey("bank_account")
        private val KEY_BANK_HOLDER = stringPreferencesKey("bank_holder")
    }

    // ─── Token ─────────────────────────────────────────────────────────────
    val jwtTokenFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_JWT_TOKEN]
    }

    suspend fun getJwtToken(): String? = jwtTokenFlow.first()

    suspend fun saveJwtToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token != null) prefs[KEY_JWT_TOKEN] = token
            else prefs.remove(KEY_JWT_TOKEN)
        }
    }

    // ─── Signed-in state ───────────────────────────────────────────────────
    val isSignedInFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_IS_SIGNED_IN] ?: false
    }

    suspend fun isSignedIn(): Boolean = isSignedInFlow.first()

    suspend fun setSignedIn(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_IS_SIGNED_IN] = value
        }
    }

    // ─── Setup completion ──────────────────────────────────────────────────
    val hasCompletedSetupFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HAS_COMPLETED_SETUP] ?: false
    }

    suspend fun hasCompletedSetup(): Boolean = hasCompletedSetupFlow.first()

    suspend fun setCompletedSetup(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HAS_COMPLETED_SETUP] = value
        }
    }

    // ─── User profile ──────────────────────────────────────────────────────
    suspend fun saveUserProfile(name: String, email: String, isDemo: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USER_NAME] = name
            prefs[KEY_USER_EMAIL] = email
            prefs[KEY_IS_DEMO_MODE] = isDemo
        }
    }

    suspend fun getUserName(): String = context.dataStore.data.first()[KEY_USER_NAME] ?: ""
    suspend fun getUserEmail(): String = context.dataStore.data.first()[KEY_USER_EMAIL] ?: ""
    suspend fun isDemoMode(): Boolean = context.dataStore.data.first()[KEY_IS_DEMO_MODE] ?: false

    // ─── Host setup ────────────────────────────────────────────────────────
    suspend fun saveHostSetup(
        listingId: String,
        selectedMode: String,
        networkName: String,
        pricePerMin: Double
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_LISTING_ID] = listingId
            prefs[KEY_SELECTED_MODE] = selectedMode
            prefs[KEY_BEAM_SPOT_NETWORK_NAME] = networkName
            prefs[KEY_PRICE_PER_MIN] = pricePerMin.toString()
        }
    }

    suspend fun getActiveListingId(): String = context.dataStore.data.first()[KEY_ACTIVE_LISTING_ID] ?: "1"
    suspend fun getSelectedMode(): String = context.dataStore.data.first()[KEY_SELECTED_MODE] ?: ""
    suspend fun getBeamSpotNetworkName(): String = context.dataStore.data.first()[KEY_BEAM_SPOT_NETWORK_NAME] ?: ""
    suspend fun getPricePerMin(): Double =
        context.dataStore.data.first()[KEY_PRICE_PER_MIN]?.toDoubleOrNull() ?: 2.0

    // ─── Payout ────────────────────────────────────────────────────────────
    suspend fun savePayout(method: String, number: String, bankName: String, bankAccount: String, bankHolder: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PAYOUT_METHOD] = method
            prefs[KEY_PAYOUT_NUMBER] = number
            prefs[KEY_BANK_NAME] = bankName
            prefs[KEY_BANK_ACCOUNT] = bankAccount
            prefs[KEY_BANK_HOLDER] = bankHolder
        }
    }

    suspend fun getPayoutMethod(): String = context.dataStore.data.first()[KEY_PAYOUT_METHOD] ?: "mpesa"
    suspend fun getPayoutNumber(): String = context.dataStore.data.first()[KEY_PAYOUT_NUMBER] ?: ""

    // ─── Clear all (logout) ────────────────────────────────────────────────
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}