package com.voisetranslator.core

import android.content.Context
import java.io.File

/**
 * The cache directory the FileProvider exposes to WhatsApp.
 *
 * Files are kept (rather than deleted right after sharing) because WhatsApp reads the uri
 * asynchronously — deleting too early produces an empty attachment. Old ones are pruned on
 * the next send instead.
 */
object OutgoingFiles {

    private const val DIR = "outgoing"
    private const val KEEP_MILLIS = 60 * 60 * 1000L

    fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    fun newFile(context: Context, extension: String): File =
        File(dir(context), "voise-${System.currentTimeMillis()}.$extension")

    /** Removes anything older than an hour; sharing has long since finished by then. */
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_MILLIS
        dir(context).listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}
