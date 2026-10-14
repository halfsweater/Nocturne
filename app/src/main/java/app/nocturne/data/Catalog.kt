package app.nocturne.data

import org.json.JSONObject

enum class Category(val key: String, val title: String) {
    Acoustic("acoustic", "Acoustic Pianos"),
    Electric("electric", "Electric & Keys"),
    Extra("extra", "Sound Packs");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: Electric
    }
}

data class SoundPack(
    val id: String,
    val title: String,
    val asset: String?,          // bundled in the APK
    val url: String?,            // downloadable
    val sha256: String?,
    val downloadBytes: Long,
    val source: String,
    val license: String,
) {
    val bundled get() = asset != null
}

data class Layer(
    val pack: String,
    val bank: Int,
    val program: Int,
    val transpose: Int,
    val gainDb: Float,
    val velocityScale: Float,
    val velocityOffset: Int,
)

data class Instrument(
    val id: String,
    val name: String,
    val category: Category,
    val character: String,
    val basis: String,
    val layers: List<Layer>,
    val voicing: Map<String, Float>,
) {
    val packs: Set<String> get() = layers.map { it.pack }.toSet()
}

/** The instrument catalog shipped as assets/instruments.json (also used by the host test harness). */
class Catalog(val packs: List<SoundPack>, val instruments: List<Instrument>) {

    private val packById = packs.associateBy { it.id }
    private val instrumentById = instruments.associateBy { it.id }

    fun pack(id: String) = packById.getValue(id)
    fun instrument(id: String) = instrumentById[id]

    companion object {
        fun parse(json: String): Catalog {
            val root = JSONObject(json)
            val packs = root.getJSONArray("packs").let { arr ->
                (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    SoundPack(
                        id = p.getString("id"),
                        title = p.getString("title"),
                        asset = p.optString("asset").ifEmpty { null },
                        url = p.optString("url").ifEmpty { null },
                        sha256 = p.optString("sha256").ifEmpty { null },
                        downloadBytes = p.optLong("downloadBytes", 0L),
                        source = p.optString("source"),
                        license = p.optString("license"),
                    )
                }
            }
            val instruments = root.getJSONArray("instruments").let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val layers = o.getJSONArray("layers").let { la ->
                        (0 until la.length()).map { k ->
                            val l = la.getJSONObject(k)
                            Layer(
                                pack = l.getString("pack"),
                                bank = l.optInt("bank", 0),
                                program = l.getInt("program"),
                                transpose = l.optInt("transpose", 0),
                                gainDb = l.optDouble("gainDb", 0.0).toFloat(),
                                velocityScale = l.optDouble("velocityScale", 1.0).toFloat(),
                                velocityOffset = l.optInt("velocityOffset", 0),
                            )
                        }
                    }
                    val voicing = mutableMapOf<String, Float>()
                    o.optJSONObject("voicing")?.let { v ->
                        v.keys().forEach { key -> voicing[key] = v.getDouble(key).toFloat() }
                    }
                    Instrument(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        category = Category.of(o.getString("category")),
                        character = o.optString("character"),
                        basis = o.optString("basis"),
                        layers = layers,
                        voicing = voicing,
                    )
                }
            }
            val ids = packs.map { it.id }.toSet()
            instruments.forEach { inst ->
                inst.layers.forEach { require(it.pack in ids) { "${inst.id} references unknown pack ${it.pack}" } }
            }
            return Catalog(packs, instruments)
        }
    }
}
