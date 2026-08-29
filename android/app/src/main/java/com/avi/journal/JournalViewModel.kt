package com.avi.journal

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.avi.journal.data.AccountManager
import com.avi.journal.data.AppUser
import com.avi.journal.data.CloudSync
import com.avi.journal.data.DayHealth
import com.avi.journal.data.Entry
import com.avi.journal.data.HealthAvailability
import com.avi.journal.data.HealthConnectManager
import com.avi.journal.data.Insights
import com.avi.journal.data.LockState
import com.avi.journal.data.PinLock
import com.avi.journal.data.PlaceLookup
import com.avi.journal.data.ThemeChoice
import com.avi.journal.data.countWords
import com.avi.journal.data.textToHtml
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = true,
    val user: AppUser? = null,
    val firebaseConfigured: Boolean = true,
    val signingIn: Boolean = false,
    val locked: Boolean = false,
    val lock: LockState = LockState.None,
    val entries: Map<LocalDate, Entry> = emptyMap(),
    val health: Map<LocalDate, DayHealth> = emptyMap(),
    val healthAvailability: HealthAvailability = HealthAvailability.UNAVAILABLE,
    val healthGranted: Boolean = false,
    val selectedDate: LocalDate = LocalDate.now(),
    val draft: Entry = Entry(date = LocalDate.now()),
    val saving: Boolean = false,
    val savedAt: Long = 0L,
    val search: String = "",
    val message: String? = null,
    val locatingPlace: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
) {
    val entryDates: Set<LocalDate> get() = entries.keys
    val today: LocalDate get() = LocalDate.now()
}

/**
 * One view model for the whole app.
 *
 * The app is four screens over one collection of days; splitting that across
 * several view models would mean keeping the same entry map in sync in several
 * places, which is the bug this avoids rather than the architecture it lacks.
 */
class JournalViewModel(application: Application) : AndroidViewModel(application) {

    private val accounts = AccountManager(application)
    private val cloud = CloudSync()
    private val health = HealthConnectManager(application)
    private val places = PlaceLookup(application)

    private val prefs = application.getSharedPreferences("journal", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var saveJob: Job? = null

    init {
        _state.update {
            it.copy(
                firebaseConfigured = accounts.isConfigured,
                healthAvailability = health.availability(),
                user = accounts.currentUser,
                theme = readSavedTheme(),
            )
        }
        if (accounts.currentUser != null) bootstrap()
        else _state.update { it.copy(loading = false) }
    }

    // ── Session ───────────────────────────────────────────────────────────

    fun signIn(activity: Activity) {
        _state.update { it.copy(signingIn = true, message = null) }
        viewModelScope.launch {
            accounts.signIn(activity)
                .onSuccess { user ->
                    _state.update { it.copy(user = user, signingIn = false) }
                    bootstrap()
                }
                .onFailure { error ->
                    _state.update { it.copy(signingIn = false, message = error.message) }
                }
        }
    }

    fun signOut() {
        accounts.signOut()
        _state.update {
            // Theme is a device preference, not account data, so it survives
            // signing out — otherwise the app would flip appearance underneath
            // someone the moment they switch accounts.
            UiState(
                loading = false,
                firebaseConfigured = accounts.isConfigured,
                healthAvailability = health.availability(),
                theme = readSavedTheme(),
            )
        }
    }

    /**
     * Reads the lock state BEFORE entries, and holds the app locked until the
     * PIN clears — the same ordering the web app uses, so nothing is fetched or
     * rendered behind the lock screen.
     */
    private fun bootstrap() {
        val uid = _state.value.user?.uid ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }

            val lock = cloud.readLockState(uid).getOrDefault(LockState.None)
            _state.update { it.copy(lock = lock, locked = lock is LockState.Pin) }

            if (lock is LockState.Pin) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            loadEverything()
        }
    }

    fun submitPin(pin: String): Boolean {
        val lock = _state.value.lock
        if (lock !is LockState.Pin) return true
        if (!PinLock.verify(pin, lock)) return false
        _state.update { it.copy(locked = false, loading = true) }
        viewModelScope.launch { loadEverything() }
        return true
    }

    // ── Data ──────────────────────────────────────────────────────────────

    private suspend fun loadEverything() {
        val uid = _state.value.user?.uid ?: return

        cloud.pullAll(uid)
            .onSuccess { list ->
                val byDate = list.associateBy { it.date }
                _state.update { current ->
                    current.copy(
                        entries = byDate,
                        draft = byDate[current.selectedDate] ?: Entry(date = current.selectedDate),
                    )
                }
            }
            .onFailure { error -> _state.update { it.copy(message = error.message) } }

        refreshHealth()
        _state.update { it.copy(loading = false) }
    }

    fun refresh() {
        viewModelScope.launch { loadEverything() }
    }

    fun refreshHealth() {
        viewModelScope.launch {
            val granted = health.hasAnyPermission()
            _state.update { it.copy(healthGranted = granted, healthAvailability = health.availability()) }
            if (!granted) return@launch

            // A year back: enough for the weekday and correlation views without
            // pulling the entire history on every resume.
            val to = LocalDate.now()
            val from = to.minusDays(365)
            val readings = health.readRange(from, to)
            _state.update { it.copy(health = readings) }
        }
    }

    fun healthPermissions(): Set<String> = health.permissions

    // ── Writing ───────────────────────────────────────────────────────────

    fun selectDate(date: LocalDate) {
        commitNow()
        _state.update { current ->
            current.copy(
                selectedDate = date,
                draft = current.entries[date] ?: Entry(date = date),
            )
        }
    }

    fun updateText(text: String) = editDraft { it.copy(plain = text, words = countWords(text)) }

    fun updateTitle(title: String) = editDraft { it.copy(title = title) }

    /**
     * Sets pleasantness only. `energy` is deliberately left as-is rather than
     * cleared: entries logged with the old two-axis pad keep their value and
     * still render it, this control just never writes a new one.
     */
    fun updateMood(pleasantness: Float?) = editDraft { it.copy(pleasantness = pleasantness) }

    fun clearMood() = editDraft { it.copy(pleasantness = null, energy = null) }

    fun updatePlace(place: String?) = editDraft { it.copy(place = place?.takeIf { p -> p.isNotBlank() }) }

    fun toggleFavorite() = editDraft { it.copy(favorite = !it.favorite) }

    fun addTag(tag: String) = editDraft { draft ->
        val cleaned = tag.trim().removePrefix("#").take(24)
        if (cleaned.isBlank() || draft.tags.contains(cleaned)) draft
        else draft.copy(tags = draft.tags + cleaned)
    }

    fun removeTag(tag: String) = editDraft { it.copy(tags = it.tags - tag) }

    private fun editDraft(transform: (Entry) -> Entry) {
        _state.update { it.copy(draft = transform(it.draft), saving = true) }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            commitNow()
        }
    }

    /**
     * Writes the draft, or deletes the day if editing emptied it.
     *
     * The HTML body is only regenerated when the text actually changed. An entry
     * written in the browser can carry formatting this screen doesn't render, and
     * rewriting its HTML from the flattened text on every save would quietly
     * destroy that formatting just for opening the day on a phone.
     */
    fun commitNow() {
        saveJob?.cancel()
        val current = _state.value
        val uid = current.user?.uid ?: return
        val draft = current.draft
        val stored = current.entries[draft.date]

        if (draft.isEmpty) {
            if (stored != null) {
                _state.update { it.copy(entries = it.entries - draft.date, saving = false) }
                viewModelScope.launch { cloud.delete(uid, draft.date) }
            } else {
                _state.update { it.copy(saving = false) }
            }
            return
        }

        val textChanged = stored?.plain != draft.plain
        val finished = draft.copy(
            html = if (textChanged) textToHtml(draft.plain) else draft.html.ifBlank { textToHtml(draft.plain) },
            words = countWords(draft.plain),
            updatedAt = System.currentTimeMillis(),
        )

        _state.update {
            it.copy(entries = it.entries + (finished.date to finished), draft = finished, saving = false)
        }

        viewModelScope.launch {
            cloud.push(uid, finished)
                .onSuccess { _state.update { s -> s.copy(savedAt = System.currentTimeMillis()) } }
                .onFailure { error -> _state.update { s -> s.copy(message = error.message) } }
        }
    }

    fun deleteEntry(date: LocalDate) {
        val uid = _state.value.user?.uid ?: return
        _state.update {
            it.copy(
                entries = it.entries - date,
                draft = if (it.selectedDate == date) Entry(date = date) else it.draft,
            )
        }
        viewModelScope.launch { cloud.delete(uid, date) }
    }

    // ── Place ─────────────────────────────────────────────────────────────

    fun hasLocationPermission(): Boolean = places.hasPermission()

    fun locationPermission(): String = places.permission

    fun fetchPlace() {
        _state.update { it.copy(locatingPlace = true) }
        viewModelScope.launch {
            places.currentPlace()
                .onSuccess { place ->
                    _state.update { it.copy(locatingPlace = false) }
                    updatePlace(place)
                }
                .onFailure { error ->
                    _state.update { it.copy(locatingPlace = false, message = error.message) }
                }
        }
    }

    // ── Misc ──────────────────────────────────────────────────────────────

    private fun readSavedTheme(): ThemeChoice =
        runCatching { ThemeChoice.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }
            .getOrDefault(ThemeChoice.SYSTEM)

    fun cycleTheme() {
        val next = _state.value.theme.next()
        prefs.edit().putString(KEY_THEME, next.name).apply()
        _state.update { it.copy(theme = next) }
    }

    fun setSearch(value: String) = _state.update { it.copy(search = value) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun streaks() = Insights.streaks(_state.value.entryDates, LocalDate.now())

    private companion object {
        const val KEY_THEME = "theme_choice"
    }
}
