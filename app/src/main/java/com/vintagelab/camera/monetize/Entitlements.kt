package com.vintagelab.camera.monetize

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vintagelab.camera.looks.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.monetizeStore: DataStore<Preferences> by preferencesDataStore(name = "monetize")

/** What the user may use right now (SPEC 4.1). [pro] is the last state Play Billing confirmed, cached for offline starts. */
data class Entitlements(
    val loaded: Boolean = false,
    val pro: Boolean = false,
    /** look id -> unlocked until (epoch ms), from rewarded ads. */
    val unlocks: Map<String, Long> = emptyMap(),
    /** 1 on the first launch, +1 per cold start. */
    val session: Int = 0,
    /** SPEC 4.4: share points. */
    val wallet: SharePoints.Wallet = SharePoints.Wallet(),
) {
    fun canShoot(p: Preset, nowMs: Long = System.currentTimeMillis()): Boolean = LookLocks.canShoot(p.locked, pro, unlocks[p.id], nowMs)
}

class EntitlementStore(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(Entitlements())
    val state: StateFlow<Entitlements> = _state.asStateFlow()
    /** Play Billing answered in this process: its answer beats the cached flag. */
    @Volatile private var billingConfirmed = false

    init {
        scope.launch {
            val prefs = context.monetizeStore.data.first()
            val now = System.currentTimeMillis()
            val session = (prefs[KEY_SESSIONS] ?: 0) + 1
            val unlocks = decode(prefs[KEY_UNLOCKS]).filterValues { it > now }
            val wallet = SharePoints.Wallet(prefs[KEY_POINTS] ?: 0, prefs[KEY_SHARE_DAY] ?: -1L, prefs[KEY_SHARES_TODAY] ?: 0)
            _state.update {
                it.copy(loaded = true, pro = if (billingConfirmed) it.pro else prefs[KEY_PRO] ?: false, unlocks = unlocks + it.unlocks, session = session, wallet = wallet)
            }
            context.monetizeStore.edit { it[KEY_SESSIONS] = session }
        }
    }

    fun unlock(lookId: String, hours: Int) {
        val until = System.currentTimeMillis() + hours.coerceAtLeast(1) * 3_600_000L
        _state.update { it.copy(unlocks = it.unlocks + (lookId to until)) }
        persist()
    }

    /** A share target was chosen (SPEC 4.4). Waits for the stored wallet so a share that starts a fresh process is not lost. */
    fun onAppShared(cfg: AdConfig, today: Long) {
        scope.launch {
            _state.first { it.loaded }
            _state.update { it.copy(wallet = SharePoints.award(it.wallet, cfg, today)) }
            persist()
        }
    }

    /** Spends [AdConfig.pointsUnlockCost] points on a [AdConfig.pointsUnlockHours] unlock of [lookId]; false if the wallet is short. */
    fun unlockWithPoints(lookId: String, cfg: AdConfig): Boolean {
        var spent = false
        _state.update {
            if (!SharePoints.canSpend(it.wallet, cfg)) return@update it
            spent = true
            val until = System.currentTimeMillis() + cfg.pointsUnlockHours.coerceAtLeast(1) * 3_600_000L
            it.copy(wallet = it.wallet.copy(points = it.wallet.points - cfg.pointsUnlockCost), unlocks = it.unlocks + (lookId to until))
        }
        if (spent) persist()
        return spent
    }

    fun setPro(pro: Boolean) {
        billingConfirmed = true
        if (_state.value.pro == pro) return
        _state.update { it.copy(pro = pro) }
        persist()
    }

    private fun persist() {
        val s = _state.value
        scope.launch {
            context.monetizeStore.edit {
                it[KEY_PRO] = s.pro
                it[KEY_UNLOCKS] = encode(s.unlocks)
                if (s.loaded) {
                    it[KEY_POINTS] = s.wallet.points
                    it[KEY_SHARE_DAY] = s.wallet.shareDay
                    it[KEY_SHARES_TODAY] = s.wallet.sharesToday
                }
            }
        }
    }

    internal companion object {
        val KEY_PRO = booleanPreferencesKey("pro")
        val KEY_UNLOCKS = stringPreferencesKey("unlocks")
        val KEY_SESSIONS = intPreferencesKey("sessions")
        val KEY_POINTS = intPreferencesKey("points")
        val KEY_SHARE_DAY = longPreferencesKey("share_day")
        val KEY_SHARES_TODAY = intPreferencesKey("shares_today")

        fun encode(m: Map<String, Long>): String = m.entries.joinToString(";") { "${it.key}=${it.value}" }
        fun decode(s: String?): Map<String, Long> = s.orEmpty().split(';').mapNotNull { e ->
            val i = e.indexOf('=')
            if (i <= 0) null else e.substring(i + 1).toLongOrNull()?.let { e.substring(0, i) to it }
        }.toMap()
    }
}
