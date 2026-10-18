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

    /** "New order": throws away the saved draft and its clip. */
    fun clearDraft() {
        draftStore.clear()
        _draft.value = null
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
                    kitchenJob = launch { repo.observeKitchen(kid).collect { _kitchen.value = it } }
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
