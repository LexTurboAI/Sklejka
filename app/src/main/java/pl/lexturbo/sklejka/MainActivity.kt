package pl.lexturbo.sklejka

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lexturbo.sklejka.core.*
import java.util.Locale

class LocalImages : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}
class Destination : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.first).putExtra(Intent.EXTRA_TITLE, input.second).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

class MainActivity : ComponentActivity() {
    private val model: SklejkaModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xff17685c))) {
                Surface(Modifier.fillMaxSize()) { SklejkaScreen(model) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    @Suppress("DEPRECATION")
    private fun receive(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> return
        }.ifEmpty { intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } } ?: emptyList() }
        uris.forEach { persistUri(this, it) }
        model.add(uris)
    }
}

private fun persistUri(context: Context, uri: Uri) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

@Composable
private fun SklejkaScreen(model: SklejkaModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(LocalImages()) { uris ->
        uris.forEach { persistUri(context, it) }; model.add(uris)
    }
    var savingCopy by rememberSaveable { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(Destination()) { uri ->
        if (uri != null) { if (savingCopy) model.copyResult(uri) else model.export(uri) }
    }
    val list = rememberLazyListState()
    LaunchedEffect(model.result?.uri) {
        if (model.result != null) list.animateScrollToItem(model.photos.size + 3)
    }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(dragId) {
        while (dragId != null) {
            val low = list.layoutInfo.viewportStartOffset + 80
            val high = list.layoutInfo.viewportEndOffset - 80
            val delta = when { dragY < low -> -16f; dragY > high -> 16f; else -> 0f }
            if (delta != 0f) {
                list.scroll { scrollBy(delta) }
                list.layoutInfo.visibleItemsInfo.filter { item -> model.photos.any { it.id == item.key } }
                    .minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - dragY) }?.let { target ->
                        model.move(dragId ?: return@let, model.photos.indexOfFirst { it.id == target.key })
                    }
            }
            kotlinx.coroutines.delay(30)
        }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("SKLEJKA", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Zdjęcia w jeden obraz", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !model.busy,
                modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("DODAJ ZDJĘCIA", fontWeight = FontWeight.Bold) }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Zdjęcia: ${model.photos.size}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = model::clear, enabled = !model.busy && model.photos.isNotEmpty()) { Text("WYCZYŚĆ") }
            }
            Text("Przytrzymaj ≡ i przeciągnij, aby zmienić kolejność.", style = MaterialTheme.typography.bodySmall)
            if (model.photos.isEmpty()) Text("Dodaj zdjęcia lub udostępnij je z Galerii do Sklejki.", Modifier.padding(vertical = 20.dp))
        }
        itemsIndexed(model.photos, key = { _, photo -> photo.id }) { index, photo ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(44.dp).height(76.dp).pointerInput(photo.id, model.busy) {
                    if (!model.busy) detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragId = photo.id
                            dragY = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == photo.id }?.let { it.offset + it.size / 2f } ?: 0f
                        }, onDragEnd = { dragId = null }, onDragCancel = { dragId = null },
                        onDrag = { change, amount ->
                            change.consume(); dragY += amount.y
                            val target = list.layoutInfo.visibleItemsInfo.filter { item -> model.photos.any { it.id == item.key } }
                                .minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - dragY) }
                            if (target != null) model.move(photo.id, model.photos.indexOfFirst { it.id == target.key })
                        }
                    )
                }, contentAlignment = Alignment.Center) {
                    Text("≡", fontSize = 28.sp, color = if (dragId == photo.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                PhotoPreview(photo.uri, photo.orientation, Modifier.size(62.dp), 160)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("${index + 1}. ${photo.name}", maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    Text("${photo.size.width} × ${photo.size.height} px", style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    TextButton(onClick = { model.move(photo.id, index - 1) }, enabled = !model.busy && index > 0,
                        contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("↑") }
                    TextButton(onClick = { model.move(photo.id, index + 1) }, enabled = !model.busy && index < model.photos.lastIndex,
                        contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("↓") }
                }
                TextButton(onClick = { model.remove(photo.id) }, enabled = !model.busy, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(0.dp)) { Text("Usuń", fontSize = 12.sp) }
            }
            HorizontalDivider()
        }
        item {
            Text("KIERUNEK", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = model.direction == Direction.VERTICAL, onClick = { model.direction(Direction.VERTICAL) }, label = { Text("PION") }, enabled = !model.busy)
                FilterChip(selected = model.direction == Direction.HORIZONTAL, onClick = { model.direction(Direction.HORIZONTAL) }, label = { Text("POZIOM") }, enabled = !model.busy)
            }
            OutlinedButton(onClick = model::reverse, enabled = !model.busy && model.photos.size > 1) { Text("ODWRÓĆ KOLEJNOŚĆ") }
            Spacer(Modifier.height(16.dp))
            Text("FORMAT", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Format.entries.forEach { f -> FilterChip(selected = model.format == f, onClick = { model.format(f) }, label = { Text(f.name) }, enabled = !model.busy) }
            }
            Text("TŁO", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Background.entries.forEach { b ->
                    FilterChip(selected = model.background == b, onClick = { model.background(b) },
                        label = { Text(when (b) { Background.WHITE -> "Białe"; Background.BLACK -> "Czarne"; Background.TRANSPARENT -> "Przezrocz." }, fontSize = 12.sp) },
                        enabled = !model.busy && (model.format == Format.PNG || b != Background.TRANSPARENT))
                }
            }
            Text("ODSTĘP: ${model.gap} px")
            Slider(value = model.gap.toFloat(), onValueChange = { model.gap(it.toInt()) }, valueRange = 0f..50f, steps = 49, enabled = !model.busy)
            if (model.format == Format.JPG) {
                Text("JAKOŚĆ JPG: ${model.quality}")
                Slider(value = model.quality.toFloat(), onValueChange = { model.quality(it.toInt()) }, valueRange = 80f..100f, steps = 19, enabled = !model.busy)
            }
            if (model.photos.isNotEmpty()) {
                val size = runCatching { Layout.plan(model.photos.map { it.size }, model.direction, gap = model.gap).size }.getOrNull()
                if (size != null) Text("Wynik: ${size.width} × ${size.height} px", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 29) model.export()
                else { savingCopy = false; save.launch(model.format.mime to Exporter.name(model.format)) }
            }, enabled = !model.busy && model.photos.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("SKLEJ I ZAPISZ", fontWeight = FontWeight.Bold)
            }
            if (model.busy) {
                Spacer(Modifier.height(12.dp)); LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                Text(model.status)
                if (model.status.startsWith("Sklejanie")) TextButton(onClick = model::cancel) { Text("ANULUJ") }
            }
        }
        model.result?.let { result ->
            item {
                HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Text("ZAPISANO", fontWeight = FontWeight.Bold)
                PhotoPreview(result.uri, null, Modifier.fillMaxWidth().height(220.dp), 1000)
                Text("${result.size.width} × ${result.size.height} px · ${fileSize(result.bytes)}", fontWeight = FontWeight.Bold)
                Text(result.location, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType(result.mime).putExtra(Intent.EXTRA_STREAM, result.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newUri(context.contentResolver, result.name, result.uri) }
                        open(context, Intent.createChooser(send, "Udostępnij obraz"), model)
                    }) { Text("UDOSTĘPNIJ") }
                    OutlinedButton(onClick = { open(context, Intent(Intent.ACTION_VIEW).setDataAndType(result.uri, result.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), model) }) { Text("OTWÓRZ") }
                }
                OutlinedButton(onClick = { savingCopy = true; save.launch(result.mime to result.name) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("ZAPISZ KOPIĘ W WYBRANEJ LOKALIZACJI") }
            }
        }
    }
    model.error?.let { message ->
        AlertDialog(onDismissRequest = { model.error = null }, title = { Text("Sklejka") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { model.error = null }) { Text("OK") } })
    }
}

private fun open(context: Context, intent: Intent, model: SklejkaModel) {
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { model.error = "Brak aplikacji obsługującej ten obraz." }
    catch (_: SecurityException) { model.error = "Nie można otworzyć pliku w tej aplikacji. Zapisz kopię w wybranej lokalizacji." }
}
private fun fileSize(bytes: Long) = when {
    bytes < 0 -> "rozmiar niedostępny"
    bytes < 1024 -> "$bytes B"
    bytes < 1048576 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.2f MB", bytes / 1048576.0)
}
@Composable
private fun PhotoPreview(uri: Uri, exif: Int?, modifier: Modifier, maxEdge: Int) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri, exif) {
        value = withContext(Dispatchers.IO) { try { thumbnail(context, uri, maxEdge, exif) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null } }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), contentDescription = "Podgląd obrazu", modifier = modifier, contentScale = ContentScale.Fit)
    else Box(modifier, contentAlignment = Alignment.Center) { Text("…") }
}
