package com.fizaan.timetracker.ui

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fizaan.timetracker.ToolsState
import com.fizaan.timetracker.export.ExportFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The two directions a timesheet travels: out as a file, and back in again.
 *
 * Kimai's own export is a page of its web dashboard rather than part of its API,
 * and the app only ever holds an API token, so the files are written here from
 * the app's own copy of the timesheet. They carry the same entries the export
 * page would; the layout is this app's, not Kimai's.
 *
 * Import is the same columns read back: it exists so a timesheet kept somewhere
 * else — an older phone, a spreadsheet, another Kimai — can be folded into this
 * one without retyping it.
 */
@Composable
fun ImportExportTool(
    state: ToolsState,
    onSetFrom: (LocalDate) -> Unit,
    onSetTo: (LocalDate) -> Unit,
    onSetFormat: (ExportFormat) -> Unit,
    onExport: () -> Unit,
    onPrintHandled: () -> Unit,
    onImport: (Uri) -> Unit,
    onClearImport: () -> Unit,
) {
    val export = state.export
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }
    val context = LocalContext.current

    // A finished print job is a file plus an intent to show a dialog; the
    // dialog needs an Activity, which only the UI side of this has.
    LaunchedEffect(export.printFile) {
        val path = export.printFile ?: return@LaunchedEffect
        onPrintHandled()
        context.findActivity()?.printPdf(File(path))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Time frame")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFrom = true }, modifier = Modifier.weight(1f)) {
                Text(export.from.format(dateFmt))
            }
            Text(
                "→",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedButton(onClick = { pickTo = true }, modifier = Modifier.weight(1f)) {
                Text(export.to.format(dateFmt))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = {
                onSetFrom(LocalDate.now().withDayOfMonth(1)); onSetTo(LocalDate.now())
            }) { Text("This month", fontSize = 13.sp) }
            TextButton(onClick = {
                val last = LocalDate.now().withDayOfMonth(1).minusMonths(1)
                onSetFrom(last); onSetTo(last.plusMonths(1).minusDays(1))
            }) { Text("Last month", fontSize = 13.sp) }
            TextButton(onClick = {
                onSetFrom(LocalDate.now().minusDays(29)); onSetTo(LocalDate.now())
            }) { Text("30d", fontSize = 13.sp) }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Format")
        ExportFormat.entries.forEach { format ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSetFormat(format) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = export.format == format,
                    onClick = { onSetFormat(format) },
                )
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        format.label,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        formatNote(format),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onExport,
            enabled = !export.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    export.running -> "Working…"
                    export.format == ExportFormat.PRINT -> "Print"
                    else -> "Export"
                }
            )
        }

        export.saved?.let { where ->
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(16.dp),
            ) {
                Text(
                    "Saved",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    where,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (state.queued > 0) {
            Spacer(Modifier.height(16.dp))
            Text(
                "${state.queued} change${if (state.queued == 1) "" else "s"} " +
                    "${if (state.queued == 1) "is" else "are"} still waiting to reach the " +
                    "server. The export includes them, so it won't match what Kimai holds " +
                    "until they're sent.",
                fontSize = 12.sp,
                color = CacheAmber,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Written on this device from the entries the app has, not by Kimai — the " +
                "columns are the same, the layout isn't.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )

        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
        Spacer(Modifier.height(20.dp))
        ImportSection(state, onImport, onClearImport)
        Spacer(Modifier.height(24.dp))
    }

    if (pickFrom) {
        ToolDateDialog(initial = export.from, onDismiss = { pickFrom = false }) { onSetFrom(it) }
    }
    if (pickTo) {
        ToolDateDialog(initial = export.to, onDismiss = { pickTo = false }) { onSetTo(it) }
    }
}

/**
 * Picking a file and folding it in.
 *
 * The picker is opened for any file rather than for a MIME type: providers
 * label CSVs as everything from text/csv to application/octet-stream, and a
 * filter that hides the file the user is looking at is worse than reading one
 * that turns out not to be a timesheet — which the reader says plainly.
 */
@Composable
private fun ImportSection(
    state: ToolsState,
    onImport: (Uri) -> Unit,
    onClear: () -> Unit,
) {
    val import = state.import
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

    SectionLabel("Import")
    Text(
        "Read a CSV or Excel timesheet — one this app exported, or anything with the " +
            "same Date, Begin, End and Activity columns — and add what's missing to " +
            "the server. Entries already there are left alone, and an activity is only " +
            "created when there's nothing of that name yet.",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { onClear(); picker.launch(arrayOf("*/*")) },
        enabled = !import.running,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.UploadFile, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (import.running) "Reading…" else "Choose a file")
    }

    if (import.running) {
        Spacer(Modifier.height(10.dp))
        Text(
            import.fileName?.let { "Reading $it and sending what's new…" }
                ?: "Reading the file…",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }

    import.result?.let { r ->
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(12.dp),
                )
                .padding(16.dp),
        ) {
            Text(
                if (r.added > 0) "Imported" else "Nothing to import",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                r.fileName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
            ImportLine("${r.added} ${entries(r.added)} added")
            if (r.activitiesCreated > 0) {
                ImportLine(
                    "${r.activitiesCreated} new " +
                        if (r.activitiesCreated == 1) "activity" else "activities"
                )
            }
            if (r.tagsCreated > 0) {
                ImportLine("${r.tagsCreated} new tag${if (r.tagsCreated == 1) "" else "s"}")
            }
            if (r.duplicates > 0) {
                ImportLine("${r.duplicates} already on the server, left alone")
            }
            if (r.ignored > 0) {
                ImportLine("${r.ignored} ${rows(r.ignored)} skipped — no end time or no date")
            }
            if (r.refused > 0) {
                ImportLine(
                    "${r.refused} refused by Kimai${r.refusedWhy?.let { ": $it" }.orEmpty()}",
                    color = CacheAmber,
                )
            }
        }
    }
}

@Composable
private fun ImportLine(text: String, color: Color = MaterialTheme.colorScheme.onBackground) {
    Text(
        "· $text",
        fontSize = 13.sp,
        color = color.copy(alpha = if (color == CacheAmber) 1f else 0.8f),
        modifier = Modifier.padding(top = 2.dp),
    )
}

private fun entries(n: Int) = if (n == 1) "entry" else "entries"
private fun rows(n: Int) = if (n == 1) "row" else "rows"

private fun formatNote(format: ExportFormat): String = when (format) {
    ExportFormat.CSV -> "Plain table, opens anywhere. Saved to Downloads."
    ExportFormat.EXCEL -> "Spreadsheet with the hours as numbers. Saved to Downloads."
    ExportFormat.PDF -> "Printable page of the same table. Saved to Downloads."
    ExportFormat.PRINT -> "Straight to the print dialog — no file kept."
}

/** The Activity this composable is running in, if it is running in one. */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Hand an already-rendered PDF to the system print dialog. */
private fun Activity.printPdf(file: File) {
    val manager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    manager.print(
        file.nameWithoutExtension,
        FilePrintAdapter(file),
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build(),
    )
}

/**
 * The dumbest possible print adapter: the document is already a PDF on disk, so
 * printing is copying those bytes to wherever the print service asks for them.
 * Re-laying anything out would only produce a second, different document.
 */
private class FilePrintAdapter(private val file: File) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(file.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }
            if (cancellationSignal?.isCanceled == true) {
                callback.onWriteCancelled()
            } else {
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            }
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
