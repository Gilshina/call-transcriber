package il.calltranscriber.data

import android.content.Context
import java.io.File

data class ModelPreset(
    val title: String,
    val description: String,
    val fileName: String,
    val url: String,
    val approxMb: Int,
)

/** Where speech models live on the phone, and which ones we know how to download. */
class ModelStore(context: Context) {
    val dir: File = File(context.applicationContext.filesDir, "models").apply { mkdirs() }

    fun vadFile(): File = File(dir, VAD_FILE_NAME)

    fun hasVad(): Boolean = vadFile().let { it.isFile && isValidGgml(it) }

    /** Installed speech models (everything *.bin except the VAD model, ignoring partial downloads). */
    fun models(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") && f.name != VAD_FILE_NAME }
            ?.sortedBy { it.name }
            .orEmpty()

    /** The model chosen in settings, else the first valid installed one. */
    fun resolveActive(settings: AppSettings): File? {
        val chosen = settings.activeModel?.let { File(dir, it) }
        if (chosen != null && chosen.isFile && isValidGgml(chosen)) return chosen
        return models().firstOrNull { isValidGgml(it) }
    }

    companion object {
        const val VAD_FILE_NAME = "ggml-silero-v5.1.2.bin"
        const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin"

        val PRESETS = listOf(
            ModelPreset(
                title = "ivrit.ai – large-v3-turbo (מותאם לעברית)",
                description = "הדיוק הטוב ביותר בעברית. כ-1.6GB, איטי יותר.",
                fileName = "ivrit-ai-whisper-large-v3-turbo.bin",
                url = "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml/resolve/main/ggml-model.bin",
                approxMb = 1620,
            ),
            ModelPreset(
                title = "Whisper large-v3-turbo (q5_0, רשמי)",
                description = "מודל רב-לשוני רשמי של whisper.cpp, מכווץ. כ-570MB. עברית סבירה, מהיר יותר.",
                fileName = "ggml-large-v3-turbo-q5_0.bin",
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
                approxMb = 574,
            ),
            ModelPreset(
                title = "Whisper medium (q5_0, רשמי)",
                description = "קטן ומהיר יותר, דיוק נמוך יותר בעברית. כ-540MB.",
                fileName = "ggml-medium-q5_0.bin",
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin",
                approxMb = 539,
            ),
        )

        /** ggml files start with the little-endian magic 0x67676d6c ("lmgg" on disk). */
        fun isValidGgml(file: File): Boolean = try {
            file.inputStream().use { input ->
                val head = ByteArray(4)
                input.read(head) == 4 &&
                    head[0] == 0x6c.toByte() && head[1] == 0x6d.toByte() &&
                    head[2] == 0x67.toByte() && head[3] == 0x67.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }
}
