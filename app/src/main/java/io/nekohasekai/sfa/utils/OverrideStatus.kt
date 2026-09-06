package io.nekohasekai.sfa.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OverrideNotice(
    val title: String,
    val reason: String,
    val hint: String,
)

object OverrideStatus {
    private val _notices = MutableStateFlow<List<OverrideNotice>>(emptyList())
    val notices: StateFlow<List<OverrideNotice>> = _notices

    fun clear() {
        _notices.value = emptyList()
    }

    fun set(items: List<OverrideNotice>) {
        _notices.value = items
    }

    fun add(item: OverrideNotice) {
        _notices.value = _notices.value + item
    }
}
