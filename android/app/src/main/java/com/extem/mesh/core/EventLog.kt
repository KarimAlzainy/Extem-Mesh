package com.extem.mesh.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny evidence log.
 *
 * Every interesting thing that happens (advertising started, a peer was seen, a frame
 * arrived, ...) is appended to extem-events.csv inside the app's private files folder.
 * The same lines are shown on screen, so you can always see where a number came from.
 *
 * Nothing is invented here: the log only records what the phone actually did.
 */
object EventLog {

    private const val MAX_LINES_ON_SCREEN = 80
    private const val FILE_NAME = "extem-events.csv"

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val csvFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private var file: File? = null

    /** Called once at startup; creates the CSV file with a header row. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        if (!target.exists() || target.length() == 0L) {
            target.writeText("time,node,event,details\n")
        }
        file = target
    }

    fun path(context: Context): String = File(context.applicationContext.filesDir, FILE_NAME).absolutePath

    /** Appends one row. [details] must never contain commas, quotes or line breaks. */
    @Synchronized
    fun write(nodeId: String, event: String, details: String) {
        val now = Date()
        val clean = details
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(',', ';')
            .replace('"', '\'')

        val row = "${csvFormat.format(now)},${csv(nodeId)},${csv(event)},${csv(clean)}\n"
        try {
            file?.appendText(row)
        } catch (e: Exception) {
            // Writing the log must never crash the mesh.
        }

        val line = "${timeFormat.format(now)}  $event  $clean"
        _recent.value = (_recent.value + line).takeLast(MAX_LINES_ON_SCREEN)
    }

    @Synchronized
    fun clearScreen() {
        _recent.value = emptyList()
    }

    private fun csv(value: String): String = if (value.contains(',')) "\"$value\"" else value
}
