package io.github.shoalapps.squill

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RemindersViewModel(app: Application) : AndroidViewModel(app) {
    private val _items = MutableStateFlow(Store.load(app))
    val items: StateFlow<List<Reminder>> = _items.asStateFlow()

    private val _notificationsOn = MutableStateFlow(true)
    /** Without notifications a reminder can't ring at all, so the screen warns about it. */
    val notificationsOn: StateFlow<Boolean> = _notificationsOn.asStateFlow()

    init {
        viewModelScope.launch { Store.changes.collect { _items.value = Store.load(getApplication()) } }
    }

    /** Pick up changes made while the app was in the background. */
    fun refresh() {
        val app = getApplication<Application>()
        _items.value = Store.load(app)
        _notificationsOn.value = NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    fun save(reminder: Reminder) {
        val app = getApplication<Application>()
        Store.update(app) { list -> list.filterNot { it.id == reminder.id } + reminder }
        Scheduler.schedule(app, reminder)
    }

    fun toggleDone(reminder: Reminder) {
        NotificationManagerCompat.from(getApplication<Application>()).cancel(reminder.id.toInt())
        save(reminder.copy(done = !reminder.done, snoozedUntil = 0))
    }

    fun delete(reminder: Reminder) {
        val app = getApplication<Application>()
        NotificationManagerCompat.from(app).cancel(reminder.id.toInt())
        Scheduler.cancel(app, reminder.id)
        Store.update(app) { list -> list.filterNot { it.id == reminder.id } }
    }
}
