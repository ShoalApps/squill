package io.github.shoalapps.squill.paint

import org.junit.Assert.*
import org.junit.Test

class BrushLibraryTest {
    @Test fun roundTripPreservesBrushesAndDeviceState() {
        val b = BrushPresets.all.first().copy(kind = BrushKind.SMUDGE, tip = TipShape.SQUARE,
            flow = .3f, hardness = .2f, spacing = .4f, scatter = 2f, sizeJitter = .4f,
            opacityJitter = .7f, roundness = .3f, angle = 45f, pressureSize = false,
            pressureOpacity = true, pressureFlow = true, tiltAngle = true, blend = Blend.SCREEN,
            aliased = true, stabiliser = .7f, strength = .2f)
        var library = BrushLibrary().saveNew(b, "  My \"brush\"\n☀  ")
        val name = library.lastBrush
        library = library.toggleFavourite(name).toggleFavourite("Pencil")
            .newPalette("Evening").addColour(0xFF123456.toInt()).addColour(0x80654321.toInt())
            .used(library.brush().copy(size = 129f, opacity = .42f), 0xFFABCDEF.toInt(), listOf(1, 2, 3))
        assertEquals(library, BrushLibrary.decode(library.encode()))
        assertEquals(129f, BrushLibrary.decode(library.encode()).brush().size, 0f)
        assertEquals(b.copy(name = name), library.customs.single())
    }

    @Test fun malformedRecordsDoNotDiscardValidNeighbours() {
        val library = BrushLibrary.decode("""{"version":1,"customs":[
            {"name":"Before"}, null, 17, {"name":"Bad","size":"oops"}, {"name":"After"}
            ],"palettes":[null,{"name":"Colours","colours":[1,"bad",2,{},3]}],
            "adjustments":{"Before":{"size":-4,"opacity":9},"After":false},
            "recent":[1,null,"bad",2],"lastBrush":"Before","colour":{}}
        """)
        assertEquals(listOf("Before", "After"), library.customs.map { it.name })
        assertEquals(listOf(1, 2, 3), library.palettes.single().colours)
        assertEquals(listOf(1, 2), library.recent)
        assertEquals(1f, library.brush().size, 0f)
        assertEquals(1f, library.brush().opacity, 0f)
        listOf("", "garbage", "[]", "null", "{", "{\"version\":999}", "{\"version\":{}}").forEach {
            assertEquals(BrushLibrary(), BrushLibrary.decode(it))
        }
    }

    @Test fun unknownEnumSkipsOnlyThatBrushAndUnknownKeysAreTolerated() {
        val library = BrushLibrary.decode("""{"version":1,"customs":[
            {"name":"Kind","kind":"FUTURE"},{"name":"Tip","tip":"FUTURE"},
            {"name":"Blend","blend":"FUTURE"},{"name":"Good","future":42}
        ],"favourites":["Good","Missing","Ink pen"]}""")
        assertEquals(listOf("Good"), library.customs.map { it.name })
        assertEquals(setOf("Good", "Ink pen"), library.favourites)
    }

    @Test fun numbersAreClampedAndNamesAreUnique() {
        var library = BrushLibrary().saveNew(BrushPresets.all.first(), " Ink pen ")
            .saveNew(BrushPresets.all.first(), "Ink pen").saveNew(BrushPresets.all.first(), "  ")
        assertEquals(listOf("Ink pen 2", "Ink pen 3"), library.customs.map { it.name })
        library = library.toggleFavourite("Ink pen 3").rename("Ink pen 3", "Ink pen 2")
        assertEquals("Ink pen 2 2", library.lastBrush)
        assertTrue("Ink pen 2 2" in library.favourites)
        val b = BrushLibrary.decode("""{"version":1,"customs":[{"name":"Clamp","size":99999,
            "opacity":-2,"flow":9,"hardness":8,"spacing":-1,"scatter":9,"sizeJitter":-1,
            "opacityJitter":8,"roundness":0,"angle":999,"stabiliser":8,"strength":-1}]}""").customs.single()
        assertEquals(600f, b.size, 0f); assertEquals(0f, b.opacity, 0f)
        assertEquals(1f, b.flow, 0f); assertEquals(1f, b.hardness, 0f)
        assertEquals(.02f, b.spacing, 0f); assertEquals(3f, b.scatter, 0f)
        assertEquals(0f, b.sizeJitter, 0f); assertEquals(1f, b.opacityJitter, 0f)
        assertEquals(.05f, b.roundness, 0f); assertEquals(180f, b.angle, 0f)
        assertEquals(.97f, b.stabiliser, 0f); assertEquals(0f, b.strength, 0f)
    }

    @Test fun capsApplyWhenReadingAndEditing() {
        val brushes = (1..110).joinToString(",") { "{\"name\":\"Custom $it\"}" }
        val colours = (1..70).joinToString(",")
        val palettes = (1..15).joinToString(",") { "{\"name\":\"Palette $it\",\"colours\":[$colours]}" }
        val library = BrushLibrary.decode("""{"version":1,"customs":[$brushes],"palettes":[$palettes]}""")
        assertEquals(100, library.customs.size)
        assertEquals(10, library.palettes.size)
        assertTrue(library.palettes.all { it.colours.size == 64 })
        assertEquals(library, library.saveNew(BrushPresets.all.first(), "Extra"))
        assertEquals(library, library.newPalette("Extra"))
        assertEquals(library, library.addColour(999))
    }

    @Test fun presetDefinitionsStayUnchangedAndDefaultPaletteIsRestorable() {
        val presets = BrushPresets.all.toList()
        var library = BrushLibrary().used(presets.first().copy(size = 111f, opacity = .25f))
        assertEquals(111f, library.brush().size, 0f)
        library = library.update(library.brush()).delete("Ink pen")
        assertEquals(presets, BrushPresets.all)
        assertEquals(emptyList<BrushSettings>(), library.customs)
        library = library.removeColour(BrushLibrary.defaultPalette.colours.first()).restorePalette()
        assertEquals(BrushLibrary.defaultPalette, library.palettes.single())
        library = library.newPalette("Second").renamePalette("Default")
        assertEquals("Default 2", library.palette)
        assertEquals("Default", library.deletePalette().palette)
    }
}
