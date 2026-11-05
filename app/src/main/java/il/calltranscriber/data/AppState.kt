package il.calltranscriber.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Process-wide live state that background workers publish and the UI observes. */
object AppState {
    data class JobInfo(val title: String, val stage: String, val percent: Int /* -1 = indeterminate */)

    data class DownloadInfo(
        val fileName: String,
        val bytes: Long,
        val total: Long,
        val error: String? = null,
        val done: Boolean = false,
    )

    val job = MutableStateFlow<JobInfo?>(null)
    val download = MutableStateFlow<DownloadInfo?>(null)

    /** Bumped whenever a transcript is added/removed, so lists reload. */
    val dataVersion = MutableStateFlow(0)

    fun bumpData() = dataVersion.update { it + 1 }
}
