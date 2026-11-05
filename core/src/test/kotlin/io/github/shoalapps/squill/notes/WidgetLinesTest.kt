package io.github.shoalapps.squill.notes

import org.junit.Assert.*
import org.junit.Test

class WidgetLinesTest {
    @Test fun exactLineAndUnicodeAndCrLf() {
        val body = "# 買い物\r\n- [ ] 牛乳 🥛\r\n- [x] café\r\n"
        assertEquals("# 買い物\r\n- [x] 牛乳 🥛\r\n- [x] café\r\n", WidgetLines.flip(body, 1, "- [ ] 牛乳 🥛"))
        assertEquals("# 買い物\r\n- [ ] 牛乳 🥛\r\n- [ ] café\r\n", WidgetLines.flip(body, 2, "- [x] café"))
        assertNull(WidgetLines.flip(body, 0, "- [ ] 牛乳 🥛"))
        assertNull(WidgetLines.flip(body, 1, "- [ ] water"))
        assertNull(WidgetLines.flip(body, -1, ""))
    }
    @Test fun onlyEligibleTasks() {
        val body = "  - [ ] nested\n\t- [ ] tab\n1. [ ] ordered\n- [X] upper\n- [-] other\n````kt\n- [ ] code\n```\n- [ ] still code\n````\n~~~\n- [x] code\n~~~\n* [ ] yes\n+ [x] yes"
        val lines = WidgetLines.parse(body)
        assertEquals(listOf(13, 14), lines.filter { it.checked != null }.map { it.index })
        body.lines().take(13).forEachIndexed { i, line -> assertNull(WidgetLines.flip(body, i, line)) }
        assertEquals(false, lines[13].checked)
        assertNotNull(WidgetLines.flip(body, 14, "+ [x] yes"))
    }
    @Test fun capAndStrippingAndFingerprint() {
        assertEquals(200, WidgetLines.parse((0..250).joinToString("\n") { "- [ ] $it" }).size)
        assertEquals("Title bold link", WidgetLines.parse("# Title **bold** [link](https://example.org)").single().text)
        val body = "- [ ] one\n- [ ] two"
        val token = WidgetLines.parse(body)[1].fingerprint
        assertEquals("- [ ] one\n- [x] two", WidgetLines.flipFingerprint(body, 1, token))
        assertNull(WidgetLines.flipFingerprint("new\n$body", 1, token))
    }
}
