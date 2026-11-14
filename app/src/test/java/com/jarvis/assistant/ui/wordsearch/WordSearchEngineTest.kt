package com.jarvis.assistant.ui.wordsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WordSearchEngineTest {

    @Test
    fun generatePuzzle_hasCorrectGridDimensions() {
        val engine = WordSearchEngine(Random(7))
        val puzzle = engine.generatePuzzle(WordSearchDifficulty.EASY, "General Vocabulary")

        assertEquals(8, puzzle.rows)
        assertEquals(8, puzzle.columns)
        assertTrue(puzzle.targetWords.isNotEmpty())
        assertTrue(puzzle.targetWords.size <= 5)
    }

    @Test
    fun generatedPuzzle_containsAllTargetWords() {
        val engine = WordSearchEngine(Random(11))
        val puzzle = engine.generatePuzzle(WordSearchDifficulty.MEDIUM, "Animals")

        assertTrue(engine.validateBoard(puzzle.grid, puzzle.targetWords))
    }

    @Test
    fun selection_acceptsStraightWordMatches() {
        val engine = WordSearchEngine(Random(23))
        val board = arrayOf(
            charArrayOf('A', 'B', 'C'),
            charArrayOf('D', 'E', 'F'),
            charArrayOf('G', 'H', 'I')
        )

        assertEquals("ABC", engine.validateSelection(listOf(WordCell(0, 0), WordCell(0, 1), WordCell(0, 2)), board, listOf("ABC")))
        assertEquals("AEI", engine.validateSelection(listOf(WordCell(0, 0), WordCell(1, 1), WordCell(2, 2)), board, listOf("AEI")))
        assertEquals("ABC", engine.validateSelection(listOf(WordCell(0, 2), WordCell(0, 1), WordCell(0, 0)), board, listOf("ABC")))
    }

    @Test
    fun selection_rejectsInvalidOrNonStraightPath() {
        val engine = WordSearchEngine(Random(37))
        val board = arrayOf(
            charArrayOf('A', 'B', 'C'),
            charArrayOf('D', 'E', 'F'),
            charArrayOf('G', 'H', 'I')
        )

        assertNull(engine.validateSelection(listOf(WordCell(0, 0), WordCell(1, 0), WordCell(2, 2)), board, listOf("AGI")))
        assertNull(engine.validateSelection(listOf(WordCell(0, 0), WordCell(1, 1), WordCell(1, 2)), board, listOf("ABC")))
    }

    @Test
    fun selection_rejectsDuplicateCells() {
        val engine = WordSearchEngine(Random(41))
        val board = arrayOf(
            charArrayOf('A', 'B', 'C'),
            charArrayOf('D', 'E', 'F'),
            charArrayOf('G', 'H', 'I')
        )

        val result = engine.validateSelection(
            listOf(WordCell(0, 0), WordCell(0, 1), WordCell(0, 0)),
            board,
            listOf("ABC")
        )

        assertNull(result)
    }
}
