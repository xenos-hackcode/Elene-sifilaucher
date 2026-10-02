package com.example.scifilauncher

import org.junit.Assert.*
import org.junit.Test

class AppStarterTemplateTest {
    @Test fun titleCannotInjectExecutableMarkup() {
        val payload = "</title><script>alert('x')</script>&"
        for (notes in listOf(false, true)) {
            val html = AppStarterTemplate.render(payload, notes)
            assertFalse(html.contains(payload))
            assertTrue(html.contains("&lt;/title&gt;"))
            assertEquals(1, Regex("<script>").findAll(html).count())
            assertTrue(html.contains("connect-src 'none'"))
        }
    }

    @Test fun templatesHaveSeparateStorageAndExpectedControls() {
        val checklist = AppStarterTemplate.render("Same name", false)
        val notes = AppStarterTemplate.render("Same name", true)
        assertTrue(checklist.contains("id=\"form\""))
        assertFalse(notes.contains("id=\"form\""))
        assertTrue(notes.contains("id=\"notes\""))
        assertTrue(checklist.contains(":checklist'"))
        assertTrue(notes.contains(":notes'"))
    }
}
