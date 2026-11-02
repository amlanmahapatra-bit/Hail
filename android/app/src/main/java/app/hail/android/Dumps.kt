package app.hail.android

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves accessibility dumps where the user can find and share them (Downloads/Hail). */
object Dumps {
    fun save(ctx: Context, app: String, json: String): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "hail-dump-$app-$ts.json"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hail")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("could not create file")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
            return "Downloads/Hail/$name"
        }
        val dir = File(ctx.getExternalFilesDir(null), "dumps").apply { mkdirs() }
        File(dir, name).writeText(json)
        return File(dir, name).absolutePath
    }
}
