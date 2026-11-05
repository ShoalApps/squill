package io.github.shoalapps.squill.paint

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ColourPalette(val name: String, val colours: List<Int> = emptyList())

@Serializable
data class BrushAdjustment(val size: Float, val opacity: Float)

/** Device-local painting tools. No files, platform state or painting data belong here. */
data class BrushLibrary(
    val customs: List<BrushSettings> = emptyList(),
    val favourites: Set<String> = emptySet(),
    val palettes: List<ColourPalette> = listOf(defaultPalette),
    val lastBrush: String = BrushPresets.all.first().name,
    val adjustments: Map<String, BrushAdjustment> = emptyMap(),
    val colour: Int = 0xFF000000.toInt(),
    val recent: List<Int> = emptyList(),
    val palette: String = defaultPalette.name,
) {
    val brushes get() = BrushPresets.all + customs
    val orderedBrushes get() = brushes.filter { it.name in favourites } +
        customs.filter { it.name !in favourites } + BrushPresets.all.filter { it.name !in favourites }
    fun brush(name: String = lastBrush): BrushSettings {
        val base = brushes.firstOrNull { it.name == name } ?: BrushPresets.all.first()
        val adjustment = adjustments[base.name] ?: return base
        return base.copy(size = adjustment.size, opacity = adjustment.opacity)
    }
    fun used(brush: BrushSettings, colour: Int = this.colour, recent: List<Int> = this.recent) = copy(
        lastBrush = brush.name,
        adjustments = adjustments + (brush.name to BrushAdjustment(brush.size.safe(1f, 600f), brush.opacity.safe(0f, 1f))),
        colour = colour, recent = recent.distinct().take(12),
    )
    fun toggleFavourite(name: String) = if (brushes.none { it.name == name }) this else
        copy(favourites = if (name in favourites) favourites - name else favourites + name)

    fun saveNew(settings: BrushSettings, name: String): BrushLibrary {
        if (customs.size >= 100 || name.isBlank()) return this
        val saved = settings.sane().copy(name = unique(name, brushes.map { it.name }))
        return copy(customs = customs + saved).used(saved)
    }
    fun update(settings: BrushSettings) = copy(customs = customs.map { if (it.name == settings.name) settings.sane() else it })
    fun rename(name: String, requested: String): BrushLibrary {
        if (customs.none { it.name == name } || requested.isBlank()) return this
        val next = unique(requested, brushes.filter { it.name != name }.map { it.name })
        return copy(customs = customs.map { if (it.name == name) it.copy(name = next) else it },
            favourites = if (name in favourites) favourites - name + next else favourites,
            lastBrush = if (lastBrush == name) next else lastBrush,
            adjustments = (adjustments - name).let { rest -> adjustments[name]?.let { rest + (next to it) } ?: rest })
    }
    fun delete(name: String): BrushLibrary = if (customs.none { it.name == name }) this else copy(customs = customs.filter { it.name != name },
        favourites = favourites - name, adjustments = adjustments - name,
        lastBrush = if (lastBrush == name) BrushPresets.all.first().name else lastBrush)

    fun newPalette(name: String): BrushLibrary {
        if (name.isBlank() || palettes.size >= 10) return this
        val next = unique(name, palettes.map { it.name })
        return copy(palettes = palettes + ColourPalette(next), palette = next)
    }
    fun renamePalette(name: String): BrushLibrary {
        if (name.isBlank()) return this
        val next = unique(name, palettes.filter { it.name != palette }.map { it.name })
        return copy(palettes = palettes.map { if (it.name == palette) it.copy(name = next) else it }, palette = next)
    }
    fun deletePalette() = copy(palettes = palettes.filter { it.name != palette }.ifEmpty { listOf(defaultPalette) })
        .let { it.copy(palette = it.palettes.first().name) }
    fun restorePalette(): BrushLibrary {
        val existing = palettes.indexOfFirst { it.name == defaultPalette.name }
        if (existing < 0 && palettes.size >= 10) return this
        val next = if (existing >= 0) palettes.map { if (it.name == defaultPalette.name) defaultPalette else it }
            else palettes + defaultPalette
        return copy(palettes = next, palette = defaultPalette.name)
    }
    fun addColour(c: Int) = copy(palettes = palettes.map {
        if (it.name == palette) it.copy(colours = (it.colours + c).distinct().take(64)) else it
    })
    fun removeColour(c: Int) = copy(palettes = palettes.map {
        if (it.name == palette) it.copy(colours = it.colours - c) else it
    })

    fun encode(): String = buildJsonObject {
        put("version", 1)
        put("customs", JsonArray(customs.take(100).map { json.encodeToJsonElement(it.sane()) }))
        put("favourites", json.encodeToJsonElement(favourites.toList()))
        put("palettes", json.encodeToJsonElement(palettes.take(10).map { it.copy(colours = it.colours.take(64)) }))
        put("lastBrush", lastBrush); put("adjustments", json.encodeToJsonElement(adjustments))
        put("colour", colour); put("recent", json.encodeToJsonElement(recent.take(12))); put("palette", palette)
    }.toString()

    companion object {
        val defaultPalette = ColourPalette("Default", listOf(0xFF000000, 0xFFFFFFFF, 0xFF5F5F66, 0xFFC62828,
            0xFFE65100, 0xFFF9A825, 0xFF2E7D32, 0xFF0B7A75, 0xFF1565C0, 0xFF4A4FA3,
            0xFF6A1B9A, 0xFFAD1457, 0xFF6D4C41, 0xFFFFCC80).map { it.toInt() })
        private val json = Json { ignoreUnknownKeys = true }
        private fun unique(raw: String, taken: List<String>): String {
            val base = raw.trim()
            var next = base; var suffix = 2
            while (next in taken) { next = "$base $suffix"; suffix++ }
            return next
        }
        /** Each record is independent: an invalid brush must not discard its neighbours. */
        fun decode(text: String): BrushLibrary {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return BrushLibrary()
            if ((root["version"] as? JsonPrimitive)?.intOrNull != 1) return BrushLibrary()
            fun string(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull
            var library = BrushLibrary()
            (root["customs"] as? JsonArray)?.forEach { entry ->
                val b = runCatching { json.decodeFromJsonElement<BrushSettings>(entry) }.getOrNull()
                if (b != null) library = library.saveNew(b, b.name)
            }
            val palettes = mutableListOf<ColourPalette>()
            (root["palettes"] as? JsonArray)?.forEach { entry ->
                val obj = entry as? JsonObject
                val name = (obj?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()
                val colours = obj?.get("colours") as? JsonArray
                if (!name.isNullOrBlank() && colours != null && palettes.size < 10) palettes += ColourPalette(
                    unique(name, palettes.map { it.name }), colours.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.distinct().take(64))
            }
            val names = library.brushes.map { it.name }.toSet()
            val adjustments = mutableMapOf<String, BrushAdjustment>()
            (root["adjustments"] as? JsonObject)?.forEach { (name, entry) ->
                val a = runCatching { json.decodeFromJsonElement<BrushAdjustment>(entry) }.getOrNull()
                if (name in names && a != null) adjustments[name] = BrushAdjustment(a.size.safe(1f, 600f), a.opacity.safe(0f, 1f))
            }
            val chosenPalettes = palettes.ifEmpty { listOf(defaultPalette) }
            return library.copy(
                favourites = (root["favourites"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.filter { it in names }?.toSet() ?: emptySet(),
                palettes = chosenPalettes,
                lastBrush = string("lastBrush")?.takeIf { it in names } ?: BrushPresets.all.first().name,
                adjustments = adjustments,
                colour = (root["colour"] as? JsonPrimitive)?.intOrNull ?: 0xFF000000.toInt(),
                recent = (root["recent"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.distinct()?.take(12) ?: emptyList(),
                palette = string("palette")?.takeIf { n -> chosenPalettes.any { it.name == n } } ?: chosenPalettes.first().name,
            )
        }
    }
}

private fun Float.safe(min: Float, max: Float) = if (isNaN()) min else coerceIn(min, max)
private fun BrushSettings.sane() = copy(size = size.safe(1f, 600f), opacity = opacity.safe(0f, 1f),
    flow = flow.safe(.01f, 1f), hardness = hardness.safe(0f, 1f), spacing = spacing.safe(.02f, 1.5f),
    scatter = scatter.safe(0f, 3f), sizeJitter = sizeJitter.safe(0f, 1f), opacityJitter = opacityJitter.safe(0f, 1f),
    roundness = roundness.safe(.05f, 1f), angle = angle.safe(0f, 180f), stabiliser = stabiliser.safe(0f, .97f), strength = strength.safe(0f, 1f))
