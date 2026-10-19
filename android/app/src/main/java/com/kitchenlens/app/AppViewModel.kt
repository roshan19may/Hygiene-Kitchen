package com.kitchenlens.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.kitchenlens.app.data.DraftStore
import com.kitchenlens.app.data.FirebaseRepository
import com.kitchenlens.app.data.Kitchen
import com.kitchenlens.app.data.KitchenRepository
import com.kitchenlens.app.data.LocalRepository
import com.kitchenlens.app.data.OrderDraft
import com.kitchenlens.app.data.publicKitchenMap
import com.kitchenlens.app.util.KitchenStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data object NeedsSetup : AuthState
    data class Ready(val kitchenId: String) : AuthState
    data class Failed(val message: String) : AuthState
}

/** Project id used in the placeholder google-services.json committed to the repo. */
const val DEMO_PROJECT_ID = "kitchenlens-demo"

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo: KitchenRepository =
        if (isDemoConfig(app)) LocalRepository(app) else FirebaseRepository()

    private val _auth = MutableStateFlow<AuthState>(AuthState.Loading)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    private val _kitchen = MutableStateFlow<Kitchen?>(null)
    val kitchen: StateFlow<Kitchen?> = _kitchen.asStateFlow()

    /** The kitchen's 30-day hygiene rating (kitchen page, badge, Home screen). */
    private val _kitchenStats = MutableStateFlow<KitchenStats?>(null)
    val kitchenStats: StateFlow<KitchenStats?> = _kitchenStats.asStateFlow()

    private var feedback: List<Pair<Boolean, Boolean>> = emptyList()
    private var feedbackLoadedAt = 0L
    private var lastPublished: Map<String, Any?>? = null

    /**
     * Recomputes the 30-day rating whenever the kitchen changes (new order, spot check, profile)
     * and publishes the public kitchen page when something customers see has changed.
     * Customer feedback is re-read at most every 30 minutes to keep database reads low.
     */
    private fun updateStats(k: Kitchen) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (now - feedbackLoadedAt > 30 * 60_000L) {
                feedback = runCatching { repo.recentFeedback(k.id, now - KitchenStats.WINDOW_DAYS * 24 * 3_600_000L) }
                    .getOrDefault(feedback)
                feedbackLoadedAt = now
            }
            val stats = KitchenStats.compute(k.id, k.orderStats, k.spotAnswers, feedback, now)
            _kitchenStats.value = stats
            val visible = publicKitchenMap(k, stats).filterKeys { it != "updatedAt" } +
                ("stats" to stats.toMap().filterKeys { it != "updatedAt" })
            if (visible != lastPublished) {
                runCatching { repo.publishKitchen(k, stats) }.onSuccess { lastPublished = visible }
            }
        }
    }

    /** Remembered between orders so packers don't retype their name every time. */
    var lastPackerName: String = ""

    /** The order being packed, saved on the phone until its QR seal is created. */
    private val draftStore = DraftStore(app)
    private val _draft = MutableStateFlow(draftStore.load())
    val draft: StateFlow<OrderDraft?> = _draft.asStateFlow()

    fun saveDraft(d: OrderDraft): OrderDraft {
        if (d.isEmpty) { clearDraft(); return d }
        val saved = draftStore.save(d)
        _draft.value = saved
        return saved
    }

    /** Throws away the saved draft and its clip (used after a seal is created). */
    fun clearDraft() {
        draftStore.clear()
        _draft.value = null
    }

    /** What "New order" cleared, kept so the user can undo it. */
    data class NewOrderUndo(val kitchenId: String, val draft: OrderDraft?, val latest: Map<String, Map<String, Any?>>)

    private val _lastReset = MutableStateFlow<NewOrderUndo?>(null)
    val lastReset: StateFlow<NewOrderUndo?> = _lastReset.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun dismissMessage() { _message.value = null }

    /**
     * "New order": immediately clears the order form AND the current hygiene records
     * (inventory, utensils, staff, pest control) so the next order starts from the
     * beginning. Seals already created keep their own records. Can be undone.
     */
    fun startNewOrder() {
        val k = _kitchen.value ?: return
        _lastReset.value = NewOrderUndo(k.id, _draft.value, k.latest)
        draftStore.clearKeepingClip()
        _draft.value = null
        viewModelScope.launch {
            try {
                repo.replaceCurrentRecords(k.id, emptyMap())
            } catch (e: Exception) {
                _message.value = "Could not clear the hygiene records: ${e.message}"
            }
        }
    }

    fun undoNewOrder() {
        val u = _lastReset.value ?: return
        _lastReset.value = null
        u.draft?.let { saveDraft(it) }
        viewModelScope.launch {
            try {
                repo.replaceCurrentRecords(u.kitchenId, u.latest)
            } catch (e: Exception) {
                _message.value = "Could not restore the hygiene records: ${e.message}"
            }
        }
    }

    fun dismissUndo() { _lastReset.value = null }

    /** Deletes the account and all its data, then returns to the sign-in screen. */
    suspend fun deleteAccount(password: String) {
        repo.deleteAccount(password)
        kitchenJob?.cancel()
        draftStore.clear()
        _draft.value = null
        _lastReset.value = null
        _kitchen.value = null
        _kitchenStats.value = null
        lastPublished = null
        runCatching { repo.signOut() }
        _auth.value = AuthState.LoggedOut
        _message.value = null
    }

    private var kitchenJob: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _auth.value = AuthState.Loading
            if (repo.signedInUserId() == null) {
                _auth.value = AuthState.LoggedOut
                return@launch
            }
            try {
                val kid = repo.loadKitchenId()
                if (kid == null) {
                    _auth.value = AuthState.NeedsSetup
                } else {
                    _auth.value = AuthState.Ready(kid)
                    kitchenJob?.cancel()
                    kitchenJob = launch {
                        repo.observeKitchen(kid).collect { k ->
                            _kitchen.value = k
                            if (k != null) updateStats(k)
                        }
                    }
                }
            } catch (e: Exception) {
                _auth.value = AuthState.Failed(e.message ?: "Could not reach the server. Check internet.")
            }
        }
    }

    fun signOut() {
        kitchenJob?.cancel()
        _kitchen.value = null
        repo.signOut()
        _auth.value = AuthState.LoggedOut
    }

    companion object {
        fun isDemoConfig(app: Application): Boolean = runCatching {
            FirebaseApp.getApps(app).isEmpty() || FirebaseApp.getInstance().options.projectId == DEMO_PROJECT_ID
        }.getOrDefault(true)
    }
}
