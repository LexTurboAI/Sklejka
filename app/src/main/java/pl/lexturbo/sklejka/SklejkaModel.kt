package pl.lexturbo.sklejka

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lexturbo.sklejka.core.*
import java.util.concurrent.atomic.AtomicBoolean

class SklejkaModel(app: Application, private val state: SavedStateHandle) : AndroidViewModel(app) {
    val photos = mutableStateListOf<Photo>()
    var direction by mutableStateOf(Direction.valueOf(state["direction"] ?: "VERTICAL")); private set
    var format by mutableStateOf(Format.valueOf(state["format"] ?: "PNG")); private set
    var background by mutableStateOf(Background.valueOf(state["background"] ?: "WHITE")); private set
    var gap by mutableIntStateOf(state["gap"] ?: 0); private set
    var quality by mutableIntStateOf(state["quality"] ?: 100); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<SavedImage?>(null); private set
    private val cancelled = AtomicBoolean(false)
    init {
        val restored: ArrayList<String>? = state["uris"]
        if (!restored.isNullOrEmpty()) add(restored.map(Uri::parse))
    }
    fun direction(value: Direction) { direction = value; state["direction"] = value.name }
    fun format(value: Format) {
        format = value; state["format"] = value.name
        if (value == Format.JPG && background == Background.TRANSPARENT) background(Background.WHITE)
    }
    fun background(value: Background) { background = value; state["background"] = value.name }
    fun gap(value: Int) { gap = value.coerceIn(0, 50); state["gap"] = gap }
    fun quality(value: Int) { quality = value.coerceIn(80, 100); state["quality"] = quality }
    private fun persist() { state["uris"] = ArrayList(photos.map { it.uri.toString() }) }
    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            // Share intents received during export are queued until that operation finishes.
            while (busy) kotlinx.coroutines.delay(100)
            busy = true; status = "Wczytywanie zdjęć…"; progress = 0f
            val failures = mutableListOf<String>()
            try {
                val loaded = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        try { inspectPhoto(getApplication(), uri) }
                        catch (e: Exception) { failures.add(e.message ?: "Nie można odczytać zdjęcia."); null }
                    }
                }
                photos.addAll(loaded); persist()
                if (failures.isNotEmpty()) error = "Nie wczytano ${failures.size} zdjęć. ${failures.first()}"
            } catch (_: OutOfMemoryError) { error = "Za mało pamięci na wczytanie listy. Dodaj mniej zdjęć jednocześnie." }
            finally { busy = false; status = "" }
        }
    }
    fun remove(id: String) { if (!busy) { photos.removeAll { it.id == id }; persist() } }
    fun clear() { if (!busy) { photos.clear(); persist() } }
    fun reverse() { if (!busy) { photos.reverse(); persist() } }
    fun move(id: String, to: Int) {
        if (busy) return
        val from = photos.indexOfFirst { it.id == id }
        if (from >= 0 && to in photos.indices && from != to) { photos.add(to, photos.removeAt(from)); persist() }
    }
    fun options() = ExportOptions(direction, false, format, background, gap, quality)
    fun export(target: Uri? = null) {
        if (busy || photos.isEmpty()) return
        val selected = photos.toList(); val settings = options()
        viewModelScope.launch {
            busy = true; cancelled.set(false); progress = 0f; status = "Sklejanie i zapis…"
            try {
                result = withContext(Dispatchers.IO) {
                    Exporter.export(getApplication(), selected, settings, target, cancelled::get) { p ->
                        viewModelScope.launch { progress = p }
                    }
                }
            } catch (_: OutOfMemoryError) {
                val s = runCatching { Layout.plan(selected.map { it.size }, settings.direction, settings.reverse, settings.gap).size }.getOrNull()
                error = "Za mało pamięci dla wyniku ${s?.width} × ${s?.height} px. Wybierz PNG lub usuń część zdjęć. Nie zmniejszono zdjęć."
            } catch (e: Exception) {
                error = if (e is InterruptedException) "Anulowano zapis." else {
                    val size = runCatching { Layout.plan(selected.map { it.size }, settings.direction, settings.reverse, settings.gap).size }.getOrNull()
                    val reason = e.message ?: "Nie udało się zapisać obrazu. Sprawdź dostępne miejsce i dostęp do zdjęć."
                    if (size != null) "$reason\n\nPlanowany wynik: ${size.width} × ${size.height} px. Nie zmniejszono zdjęć." else reason
                }
            } finally { busy = false; status = "" }
        }
    }
    fun cancel() { cancelled.set(true) }
    fun copyResult(target: Uri) {
        val saved = result ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true; status = "Zapisywanie kopii…"
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(saved.uri)?.use { input ->
                        resolver.openOutputStream(target, "w")?.use { output -> input.copyTo(output, 65536) }
                            ?: error("Nie można zapisać kopii.")
                    } ?: error("Nie można otworzyć zapisanego obrazu.")
                }
                result = saved.copy(uri = target, location = target.toString())
            } catch (e: Exception) {
                runCatching { getApplication<Application>().contentResolver.delete(target, null, null) }
                error = e.message ?: "Nie udało się zapisać kopii."
            }
            finally { busy = false; status = "" }
        }
    }
}
