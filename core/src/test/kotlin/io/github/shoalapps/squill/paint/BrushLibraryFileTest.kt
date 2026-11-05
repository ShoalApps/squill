package io.github.shoalapps.squill.paint

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrushLibraryFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun queuedWritesReplaceTheFileAndANewStoreLoadsTheLatestState() {
        val file = File(temporary.root, "config/brush-library.json")
        val store = BrushLibraryFile(file)
        val first = BrushLibrary().saveNew(BrushPresets.all.first(), "Saved ink")
        val second = first.toggleFavourite("Saved ink").newPalette("Night").addColour(123)
        store.save(first)
        store.save(second)
        assertEquals(second, BrushLibraryFile(file).load())
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
        file.writeText("broken")
        assertEquals(BrushLibrary(), store.load())
    }
}
