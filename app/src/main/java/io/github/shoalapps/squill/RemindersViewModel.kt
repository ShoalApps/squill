package io.github.shoalapps.squill

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemindersViewModel(app: Application) : AndroidViewModel(app) {
    private val _items = MutableStateFlow(Store.load(app))
    val items: StateFlow<List<Reminder>> = _items.asStateFlow()

    /** Pick up changes made by the notification buttons while the app was in the background. */
    fun refresh() { _items.value = Store.load(getApplication()) }

    fun save(reminder: Reminder) {
        val app = getApplication<Application>()
        _items.value = Store.update(app) { list -> list.filterNot { it.id == reminder.id } + reminder }
        Scheduler.schedule(app, reminder)
    }

    fun toggleDone(reminder: Reminder) = save(reminder.copy(done = !reminder.done))

    fun delete(reminder: Reminder) {
        val app = getApplication<Application>()
        Scheduler.cancel(app, reminder.id)
        _items.value = Store.update(app) { list -> list.filterNot { it.id == reminder.id } }
    }
}
