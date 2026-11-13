package com.hybrid.whatsappbot.util

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object MediaStorageHelper {

    private const val SCHEDULED_MEDIA_DIR = "scheduled_media"

    /**
     * Immediately copies the user-selected gallery URI into the app's private internal storage
     * (context.filesDir/scheduled_media/) so the file remains permanently accessible when the
     * exact alarm fires hours or days later.
     */
    suspend fun copyUriToInternalStorage(context: Context, sourceUri: Uri): File =
        withContext(Dispatchers.IO) {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(sourceUri) ?: "image/jpeg"
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?.takeIf { it.isNotBlank() }
                ?: "jpg"

            val targetDir = File(context.filesDir, SCHEDULED_MEDIA_DIR)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val fileName = "task_img_${UUID.randomUUID()}.$extension"
            val targetFile = File(targetDir, fileName)

            contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 16 * 1024)
                    outputStream.flush()
                }
            } ?: throw IllegalStateException("Unable to open InputStream for selected image URI: $sourceUri")

            if (!targetFile.exists() || targetFile.length() == 0L) {
                throw IllegalStateException("Copied image file is empty or could not be saved.")
            }

            targetFile
        }
}
