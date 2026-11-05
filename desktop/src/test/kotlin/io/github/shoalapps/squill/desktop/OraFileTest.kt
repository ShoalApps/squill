package io.github.shoalapps.squill.desktop

import io.github.shoalapps.squill.paint.*
import io.github.shoalapps.squill.notes.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

class OraFileTest {
    @get:Rule val dir = TemporaryFolder()
    private fun save(copy: PaintingCopy) = ByteArrayOutputStream().also { OraFile.save(copy, it) }.toByteArray()
    private fun png(pixels: IntArray, w: Int, h: Int) = ByteArrayOutputStream().also { out ->
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, w, h, pixels, 0, w); ImageIO.write(image, "png", out)
    }.toByteArray()

    /** Independent PNG writer and the phone's stack writer, rather than testing only our own encoder. */
    private fun phoneFile(): Pair<ByteArray, Painting> {
        val p = Painting(7, 5)
        Blend.entries.forEachIndexed { i, blend ->
            val layer = p.newLayer("Layer $i & <\" snow ☃")
            layer.opacity = i.toFloat() / Blend.entries.size; layer.visible = i % 2 == 0
            layer.blend = blend; layer.alphaLock = i % 3 == 0; layer.clipped = i % 4 == 0
            layer.pixels = IntArray(35) { at -> ((at * 7 and 255) shl 24) or (0xABCDEF xor (at * 913)) }
        }
        val files = linkedMapOf("mimetype" to "image/openraster".toByteArray())
        val entries = p.layers.withIndex().toList().asReversed().map { (i, l) ->
            val src = "data/layer$i.png"; files[src] = png(l.pixels, p.width, p.height)
            OraStack.Entry(l.name, src, l.opacity, l.visible, l.blend, l.alphaLock, l.clipped)
        }
        files["stack.xml"] = OraStack.write(p.width, p.height, entries).toByteArray()
        files["extensions/keep.bin"] = byteArrayOf(0, 1, -1, 20)
        return ByteArrayOutputStream().also { writeZip(it, files) }.toByteArray() to p
    }
    private fun same(expected: Painting, actual: Painting) {
        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
        assertEquals(expected.layers.size, actual.layers.size)
        expected.layers.zip(actual.layers).forEach { (a, b) ->
            assertEquals(a.name, b.name); assertEquals(a.opacity, b.opacity, 0f); assertEquals(a.visible, b.visible)
            assertEquals(a.blend, b.blend); assertEquals(a.alphaLock, b.alphaLock); assertEquals(a.clipped, b.clipped)
            assertArrayEquals(a.pixels, b.pixels)
        }
    }
    @Test fun assistantsRoundTripStayEditableAndAreNotExtras() {
        val p = Painting.blank(7, 5)
        p.assistants = listOf(Ruler(1f, 2f, 6f, 2f), Parallel(0f, 1f, 7f, 1f), Perspective(listOf(Point(3f, 2f))))
        p.assistantsOn = false
        val signature = PaintingCopy(p).signature()
        val loaded = OraFile.load(save(PaintingCopy(p, mapOf(AssistantFile.NAME to "stale".toByteArray()))))
        assertEquals(p.assistants, loaded.painting.assistants)
        assertNull(loaded.readOnlyReason); assertFalse(AssistantFile.NAME in loaded.extras)
        assertTrue(loaded.painting.assistantsOn)
        assertEquals(signature, loaded.signature())
        loaded.painting.assistants = emptyList()
        assertNotEquals(signature, loaded.signature())
        assertFalse(AssistantFile.NAME in readZip(ByteArrayInputStream(save(loaded))))
    }

    @Test fun oldFilesHaveNoAssistants() {
        val loaded = OraFile.load(phoneFile().first)
        assertTrue(loaded.painting.assistants.isEmpty()); assertTrue(loaded.painting.assistantsOn)
    }

    @Test fun phoneToDesktopToPhoneKeepsEveryLayerAndExtension() {
        val (bytes, expected) = phoneFile()
        val loaded = OraFile.load(bytes); assertNull(loaded.readOnlyReason); same(expected, loaded.painting)
        val saved = save(loaded)
        same(expected, OraFile.load(saved).painting)
        assertEquals(loaded.signature(), OraFile.load(saved).signature())
        // Decode desktop output independently, as the phone does, including its top-first order.
        val files = readZip(ByteArrayInputStream(saved)); val stack = OraStack.parse(files.getValue("stack.xml"))
        assertEquals(loaded.sources.values.toSet(), stack.entries.map { it.src }.toSet())
        stack.entries.asReversed().zip(expected.layers).forEach { (entry, layer) ->
            val image = ImageIO.read(ByteArrayInputStream(files.getValue(entry.src)))
            assertArrayEquals(layer.pixels, image.getRGB(0, 0, 7, 5, null, 0, 7))
        }
        assertArrayEquals(byteArrayOf(0, 1, -1, 20), files["extensions/keep.bin"])
    }
    @Test fun addingALayerDoesNotOverwriteAnExtensionWithTheSameName() {
        val loaded = OraFile.load(phoneFile().first)
        val bytes = byteArrayOf(1, 2, 3)
        val name = "data/squill-layer${loaded.painting.layers.size}.png"
        val copy = PaintingCopy(loaded.painting, loaded.extras + (name to bytes), sources = loaded.sources)
        copy.painting.newLayer("New")
        val saved = save(copy)
        assertArrayEquals(bytes, readZip(ByteArrayInputStream(saved))[name])
        same(copy.painting, OraFile.load(saved).painting)
    }

    @Test fun unknownStackDetailsAreReadOnlyAndCannotBeSaved() {
        val files = readZip(ByteArrayInputStream(phoneFile().first)).toMutableMap()
        for (change in listOf<(String) -> String>(
            { it.replace("<stack>", "<stack custom=\"keep\">") },
            { it.replace("svg:src-over", "vendor:unknown") },
            { it.replace("x=\"0\"", "x=\"3\"") },
            { it.replace("<stack>", "<stack><stack custom=\"keep\">").replace("</stack>", "</stack></stack>") }
        )) {
            val modified = files + ("stack.xml" to change(files.getValue("stack.xml").toString(Charsets.UTF_8)).toByteArray())
            val copy = OraFile.load(ByteArrayOutputStream().also { writeZip(it, modified) }.toByteArray())
            assertNotNull(copy.readOnlyReason)
            assertNotEquals(OraFile.load(phoneFile().first).signature(), copy.signature())
            assertTrue(runCatching { save(copy) }.isFailure)
        }
    }
    @Test fun strokeEraserAndHistoryHaveExactPixels() {
        val p = Painting.blank(32, 32)
        val brush = BrushPresets.all.first { it.name == "Pixel" }.copy(size = 5f)
        fun stroke(settings: BrushSettings) {
            val a = p.active
            val s = StrokePainter(a.pixels, 32, 32, settings, 0xFF123456.toInt())
            s.add(10f, 10f); s.add(20f, 10f); s.finish(20f, 10f)
            p.recordPixels(a, s.changed, s.original)
        }
        stroke(brush)
        val painted = p.active.pixels.copyOf()
        assertEquals(0xFF123456.toInt(), painted[10 * 32 + 15]); assertEquals(0, painted[0])
        stroke(brush.copy(kind = BrushKind.ERASE))
        assertEquals(0x00123456, p.active.pixels[10 * 32 + 15])
        p.undo(); assertArrayEquals(painted, p.active.pixels)
        p.undo(); assertTrue(p.active.pixels.all { it == 0 })
        p.redo(); assertArrayEquals(painted, p.active.pixels)
        p.newLayer("Top"); p.moveActive(-1); p.undo(); assertEquals("Top", p.layers.last().name)
    }
    @Test fun paintingSavesThroughAnEncryptedVault() {
        val model = Model(dir.newFolder(), hosting = false); model.start()
        try {
            val copy = OraFile.load(phoneFile().first)
            val path = model.vault.createFile("", "Painting", "ora") { OraFile.save(copy, it) }
            val encrypted = java.util.concurrent.CountDownLatch(1)
            model.encrypt("a long password") { it.getOrThrow(); encrypted.countDown() }
            assertTrue(encrypted.await(20, java.util.concurrent.TimeUnit.SECONDS))
            copy.painting.layers.first().name = "Changed"
            model.savePainting(path, copy)
            val until = System.currentTimeMillis() + 20000
            while (OraFile.load(model.vault.readBytes(path)).painting.layers.first().name != "Changed") {
                assertTrue(System.currentTimeMillis() < until); Thread.sleep(50)
            }
            assertTrue(NoteCipher.isSealed(java.io.File(model.vault.root, path).readBytes()))
            val files = readZip(ByteArrayInputStream(phoneFile().first)).toMutableMap()
            files["stack.xml"] = files.getValue("stack.xml").toString(Charsets.UTF_8).replace("<stack>", "<stack extra=\"keep\">").toByteArray()
            val otherBytes = ByteArrayOutputStream().also { writeZip(it, files) }.toByteArray()
            val other = OraFile.load(otherBytes)
            assertNotNull(other.readOnlyReason)
            model.keepOtherPainting(path, other)
            while (model.vault.stats().none { it.path.contains("from another device") }) {
                assertTrue(System.currentTimeMillis() < until); Thread.sleep(50)
            }
            val otherPath = model.vault.stats().single { it.path.contains("from another device") }.path
            assertArrayEquals(otherBytes, model.vault.readBytes(otherPath))
            assertTrue(NoteCipher.isSealed(java.io.File(model.vault.root, otherPath).readBytes()))
        } finally { model.stop() }
    }

    @Test fun aSmallFileAskingForHugeLayersIsRefusedBeforeUnpacking() {
        val layers = (0 until 2000).joinToString("") { "<layer name=\"L$it\" src=\"data/one.png\" x=\"0\" y=\"0\"/>" }
        val xml = "<image version=\"0.0.5\" w=\"8192\" h=\"8192\"><stack>$layers</stack></image>"
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z -> z.putNextEntry(java.util.zip.ZipEntry("stack.xml")); z.write(xml.toByteArray()); z.closeEntry() }
        val failure = runCatching { OraFile.load(out.toByteArray()) }.exceptionOrNull()
        org.junit.Assert.assertTrue(failure?.message, failure?.message?.contains("too") == true)
    }
    @Test fun nestedGroupsRoundTripWithExactPixelsAndSourceAssociations() {
        val p = Painting.blank(7, 5)
        p.active.name = "Bottom"; p.active.pixels.fill(0xFF123456.toInt())
        p.newGroup("Outer <&\""); val outer = p.activeNode as Group
        p.newLayer("First").pixels.fill(0x802468AC.toInt())
        p.newGroup("Inner"); val inner = p.activeNode as Group
        p.newLayer("Second").pixels.fill(0xC013579B.toInt())
        p.activeId = inner.id; p.newGroup("Empty")
        outer.opacity = .6f; outer.blend = Blend.SCREEN; outer.clipped = true
        inner.passThrough = true; inner.visible = false
        val initial = PaintingCopy(p)
        val loaded = OraFile.load(save(initial))
        assertNull(loaded.readOnlyReason); same(p, loaded.painting)
        fun structure(p: Painting) = OraStack.write(p.width, p.height, p.toOra { it.name })
        assertEquals(structure(p), structure(loaded.painting))
        val files = readZip(ByteArrayInputStream(save(loaded)))
        loaded.painting.layers.forEach { layer ->
            val image = ImageIO.read(ByteArrayInputStream(files.getValue(loaded.sources.getValue(layer.id))))
            assertArrayEquals(layer.pixels, image.getRGB(0, 0, 7, 5, null, 0, 7))
        }
        val again = OraFile.load(save(loaded)); same(p, again.painting)
        assertEquals(structure(p), structure(again.painting)); assertEquals(initial.signature(), again.signature())
        assertArrayEquals(p.flattened(), again.painting.flattened())
    }

    @Test fun nestedStackUnknownAttributesAreReadOnlyButOffsetsAndAutoMultiplyAreSupported() {
        val p = Painting.blank(1, 1); p.groupActive()
        val files = readZip(ByteArrayInputStream(save(PaintingCopy(p))))
        val xml = files.getValue("stack.xml").toString(Charsets.UTF_8)
        fun load(stack: String) = OraFile.load(ByteArrayOutputStream().also { writeZip(it, files + ("stack.xml" to stack.toByteArray())) }.toByteArray())
        assertNotNull(load(xml.replace("<stack name=", "<stack custom=\"keep\" name=")).readOnlyReason)
        val supported = load(xml.replace("<stack name=", "<stack x=\"12\" y=\"-3\" name=").replace("isolation=\"isolate\"", "isolation=\"auto\"").replace("composite-op=\"svg:src-over\" isolation", "composite-op=\"svg:multiply\" isolation"))
        assertNull(supported.readOnlyReason)
        assertFalse((supported.painting.root.children.single() as Group).passThrough)
    }

    @Test fun signaturesIncludeStructureAndGroupFieldsButIgnoreIds() {
        val p = Painting.blank(1, 1); p.newLayer("top")
        val flat = PaintingCopy(p).signature()
        p.groupActive(); val grouped = PaintingCopy(p).signature(); assertNotEquals(flat, grouped)
        val g = p.activeNode as Group
        val snapshot = p.snapshot(true); snapshot.nodes.forEach { it.id += 100 }; assertEquals(grouped, PaintingCopy(snapshot).signature())
        g.opacity = .5f; assertNotEquals(grouped, PaintingCopy(p).signature())
        g.opacity = 1f; g.passThrough = true; assertNotEquals(grouped, PaintingCopy(p).signature())
    }
}
