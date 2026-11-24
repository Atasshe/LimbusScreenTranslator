package com.kimght.limbusscreentranslator.domain.screen

import com.kimght.limbusscreentranslator.testutil.TestZip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class ScreenTranslationTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun pairsReorderedSkillLevelsAndExpandsKeywords() {
        val english = files.newFile("english.zip")
        val portuguese = files.newFile("pt.zip")
        TestZip.write(english, listOf(
            "EN/Skills.json" to """
                {"dataList":[{"id":42,"levelList":[
                  {"level":1,"desc":"On use, gain 2 [TestBuff]."},
                  {"level":4,"desc":"On use, gain 4 [TestBuff]."}
                ]}]}
            """.trimIndent(),
            "EN/Bufs.json" to """{"dataList":[{"id":"TestBuff","name":"Resolve"}]}""",
        ))
        TestZip.write(portuguese, listOf(
            "Lang/pt/Skills.json" to """
                {"dataList":[{"id":"42","levelList":[
                  {"level":"4","desc":"Ao usar, recebe 4 de [TestBuff]."},
                  {"level":"1","desc":"Ao usar, recebe 2 de [TestBuff]."}
                ]}]}
            """.trimIndent(),
            "Lang/pt/Bufs.json" to """{"dataList":[{"id":"TestBuff","name":"Determinação"}]}""",
        ))
        val pairs = ZipFile(english).use { en ->
            ZipFile(portuguese).use { pt -> ScreenTextPacks.read(en, "EN/", pt, "Lang/pt/") }
        }
        val index = ScreenTextIndex(pairs)

        assertEquals("Ao usar, recebe 4 de Determinação.", index.match("On use, gain 4 Resolve.", emptySet())?.translation)
        assertNull(index.match("On use, gain 3 Resolve.", emptySet()))
    }

    @Test
    fun keepsWrappedBoundsAndVisibleNumber() {
        val index = ScreenTextIndex(listOf(
            ScreenTextPair("Collect {0} tokens to advance.", "Colete {0} fichas para avançar.", "task"),
        ))
        val first = ScreenOcrLine("Collect 14 tokens", ScreenRect(90, 180, 240, 197))
        val second = ScreenOcrLine("to advance.", ScreenRect(90, 204, 190, 221))
        val button = ScreenOcrLine("Unrelated Button", ScreenRect(80, 330, 255, 350))

        val labels = ScreenTextLayout.translate(listOf(first, second, button), index)

        assertEquals(1, labels.size)
        assertEquals("Colete 14 fichas para avançar.", labels.single().text)
        assertEquals(ScreenRect(90, 180, 240, 221), labels.single().bounds)
    }

    @Test
    fun disambiguatesExactPhraseWithNearbyTitle() {
        val index = ScreenTextIndex(listOf(
            ScreenTextPair("Use the charge.", "Use a carga.", "device"),
            ScreenTextPair("Use the charge.", "Use a investida.", "skill"),
            ScreenTextPair("Heavy tackle", "Encontrão forte", "skill"),
        ))
        assertNull(index.match("Use the charge.", emptySet()))

        val lines = listOf(
            ScreenOcrLine("Heavy tackle", ScreenRect(80, 30, 190, 48)),
            ScreenOcrLine("Use the charge.", ScreenRect(80, 85, 207, 103)),
        )
        val labels = ScreenTextLayout.translate(lines, index)
        assertEquals(setOf("Encontrão forte", "Use a investida."), labels.map { it.text }.toSet())
    }
}
