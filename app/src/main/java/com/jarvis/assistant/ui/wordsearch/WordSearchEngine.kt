package com.jarvis.assistant.ui.wordsearch

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

private const val MAX_RETRY_ATTEMPTS = 125

enum class WordSearchDifficulty(val label: String, val gridSize: Int, val targetWords: Int) {
    EASY("Easy", 8, 5),
    MEDIUM("Medium", 10, 8),
    HARD("Hard", 12, 12),
    EXPERT("Expert", 15, 15)
}

data class WordCell(val row: Int, val column: Int)

data class WordSearchPuzzle(
    val difficulty: WordSearchDifficulty,
    val category: String,
    val grid: Array<CharArray>,
    val targetWords: List<String>,
    val placements: Map<String, List<WordCell>>
) {
    val rows: Int = grid.size
    val columns: Int = grid.firstOrNull()?.size ?: 0

    fun getWordCells(word: String): List<WordCell> = placements[word.uppercase()] ?: emptyList()
}

class WordSearchEngine(private val random: Random = Random.Default) {
    private val categoryWords: Map<String, List<String>> = mapOf(
        "General Vocabulary" to listOf(
            "PLANET", "BOOK", "CLOUD", "GARDEN", "ROCKET", "RIVER", "STONE", "JOURNEY",
            "BREEZE", "ORANGE", "SAPPHIRE", "BRIGHT", "TURBO", "WINDOW", "BLOSSOM", "LEGEND",
            "BRAIN", "SYSTEM", "GAMER", "TARGET", "BANNER", "GLOW", "LANTERN", "HARBOR"
        ),
        "Animals" to listOf(
            "ELEPHANT", "TIGER", "LION", "MONKEY", "PANDA", "DOLPHIN", "FALCON", "RABBIT",
            "OTTER", "WOLF", "ZEBRA", "HARE", "CROW", "BISON", "KOALA", "SHARK"
        ),
        "Nature" to listOf(
            "FOREST", "RIVER", "MOUNTAIN", "CLOUD", "FLOWER", "STONE", "LEAF", "RAIN",
            "MEADOW", "SUNSET", "BLOSSOM", "VALLEY", "THUNDER", "JUNGLE", "OCEAN", "BEACH"
        ),
        "Technology" to listOf(
            "BINARY", "ROBOT", "SCREEN", "SERVER", "ALGORITHM", "NETWORK", "DEVICE", "BUTTON",
            "CODER", "BATTERY", "MEMORY", "CHIP", "SOLAR", "PROCESS", "STREAM", "LASER"
        ),
        "Science" to listOf(
            "ATOM", "ENERGY", "PHYSICS", "ORBIT", "MOLECULE", "SPECTRUM", "CELLS", "LABORATORY",
            "ELECTRON", "FUSION", "VIBRATION", "METAL", "ROCKET", "VECTOR", "GRAVITY", "GEAR"
        ),
        "Space" to listOf(
            "PLANET", "COMET", "SATURN", "ORBIT", "GALAXY", "ASTEROID", "ROCKET", "MOON",
            "NEBULA", "AURORA", "STAR", "COSMOS", "LUNAR", "SOLAR", "ECLIPSE", "QUASAR"
        ),
        "Food" to listOf(
            "APPLE", "BREAD", "PEAR", "GRAPE", "CHEESE", "COOKIE", "SANDWICH", "LEMON",
            "PIZZA", "TACO", "MANGO", "CHERRY", "COFFEE", "YOGURT", "BERRY", "PASTA"
        ),
        "Sports" to listOf(
            "GOAL", "TRACK", "PLAYER", "RACER", "SWIM", "BAT", "COURT", "ROCKET",
            "PADDLE", "FUTBOL", "CRICKET", "PITCH", "BOOST", "JUMPER", "TREAD", "SCORE"
        )
    )

    private val directions = listOf(
        WordCell(0, 1),
        WordCell(1, 0),
        WordCell(1, 1),
        WordCell(1, -1),
        WordCell(0, -1),
        WordCell(-1, 0),
        WordCell(-1, -1),
        WordCell(-1, 1)
    )

    fun generatePuzzle(difficulty: WordSearchDifficulty, category: String): WordSearchPuzzle {
        val safeCategory = if (categoryWords.containsKey(category)) category else "General Vocabulary"
        val availableWords = categoryWords[safeCategory].orEmpty()
            .map { it.trim().uppercase() }
            .filter { it.length <= difficulty.gridSize && it.length > 1 }
            .distinct()
            .sortedByDescending { it.length }

        val targetWords = availableWords.shuffled(random).take(difficulty.targetWords)
        require(targetWords.isNotEmpty()) { "No valid words for the selected category." }

        for (attempt in 0 until MAX_RETRY_ATTEMPTS) {
            val board = Array(difficulty.gridSize) { CharArray(difficulty.gridSize) { ' ' } }
            val placements = linkedMapOf<String, List<WordCell>>()
            var placedAll = true

            for (word in targetWords.sortedByDescending { it.length }) {
                val placement = placeWord(board, word)
                if (placement == null) {
                    placedAll = false
                    break
                }
                placements[word] = placement
            }

            if (!placedAll) continue

            fillEmptyCells(board)
            if (validateBoard(board, placements.keys.toList())) {
                return WordSearchPuzzle(
                    difficulty = difficulty,
                    category = safeCategory,
                    grid = board,
                    targetWords = targetWords.sortedBy { it.length },
                    placements = placements
                )
            }
        }

        throw IllegalStateException("Unable to generate a valid Word Search puzzle for ${difficulty.label} $safeCategory.")
    }

    private fun placeWord(board: Array<CharArray>, word: String): List<WordCell>? {
        val candidateDirections = directions.shuffled(random)
        for (direction in candidateDirections) {
            val rowCandidates = (0 until board.size).shuffled(random)
            val columnCandidates = (0 until board.size).shuffled(random)
            for (row in rowCandidates) {
                for (column in columnCandidates) {
                    val endRow = row + direction.row * (word.length - 1)
                    val endColumn = column + direction.column * (word.length - 1)
                    if (endRow !in board.indices || endColumn !in board.indices) continue

                    val cells = mutableListOf<WordCell>()
                    var fits = true
                    for (index in word.indices) {
                        val currentRow = row + direction.row * index
                        val currentColumn = column + direction.column * index
                        val current = board[currentRow][currentColumn]
                        if (current != ' ' && current != word[index]) {
                            fits = false
                            break
                        }
                        cells.add(WordCell(currentRow, currentColumn))
                    }
                    if (!fits) continue

                    for (index in word.indices) {
                        val currentRow = row + direction.row * index
                        val currentColumn = column + direction.column * index
                        board[currentRow][currentColumn] = word[index]
                    }
                    return cells
                }
            }
        }
        return null
    }

    private fun fillEmptyCells(board: Array<CharArray>) {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        for (row in board.indices) {
            for (column in board[row].indices) {
                if (board[row][column] == ' ') {
                    board[row][column] = alphabet[random.nextInt(alphabet.length)]
                }
            }
        }
    }

    fun validateSelection(selectedCells: List<WordCell>, board: Array<CharArray>, targetWords: List<String>): String? {
        if (selectedCells.isEmpty()) return null
        if (selectedCells.size == 1) return null

        val unique = selectedCells.distinct()
        if (unique.size != selectedCells.size) return null

        val first = selectedCells.first()
        val last = selectedCells.last()

        val rowDelta = last.row - first.row
        val columnDelta = last.column - first.column
        val rowStep = when {
            rowDelta == 0 -> 0
            rowDelta > 0 -> 1
            else -> -1
        }
        val columnStep = when {
            columnDelta == 0 -> 0
            columnDelta > 0 -> 1
            else -> -1
        }

        val isStraight = when {
            rowDelta == 0 && columnDelta != 0 -> true
            columnDelta == 0 && rowDelta != 0 -> true
            abs(rowDelta) == abs(columnDelta) -> true
            else -> false
        }

        if (!isStraight) return null

        val expectedCount = max(abs(rowDelta), abs(columnDelta)) + 1
        if (selectedCells.size != expectedCount) return null

        val path = mutableListOf<WordCell>()
        var currentRow = first.row
        var currentColumn = first.column
        while (true) {
            path.add(WordCell(currentRow, currentColumn))
            if (currentRow == last.row && currentColumn == last.column) break
            currentRow += rowStep
            currentColumn += columnStep
        }

        if (path.distinct().size != selectedCells.size) return null
        val coordinates = selectedCells.toSet()
        if (!path.all { it in coordinates }) return null

        val letters = path.map { board[it.row][it.column] }.joinToString("")
        val reversed = letters.reversed()
        val candidates = listOf(letters, reversed)
        return targetWords.firstOrNull { candidate ->
            candidates.any { it.equals(candidate, ignoreCase = true) }
        }
    }

    fun validateBoard(board: Array<CharArray>, words: List<String>): Boolean {
        val upperWords = words.map { it.uppercase() }
        for (word in upperWords) {
            var found = false
            for (row in board.indices) {
                for (column in board[row].indices) {
                    for (direction in directions) {
                        val match = matchWord(board, word, row, column, direction.row, direction.column)
                        if (match) {
                            found = true
                            break
                        }
                    }
                    if (found) break
                }
                if (found) break
            }
            if (!found) return false
        }
        return true
    }

    private fun matchWord(
        board: Array<CharArray>,
        word: String,
        startingRow: Int,
        startingColumn: Int,
        deltaRow: Int,
        deltaColumn: Int
    ): Boolean {
        val charCount = word.length
        val endRow = startingRow + deltaRow * (charCount - 1)
        val endColumn = startingColumn + deltaColumn * (charCount - 1)
        if (endRow !in board.indices || endColumn !in board[startingRow].indices) {
            return false
        }

        val letters = mutableListOf<Char>()
        for (index in word.indices) {
            val row = startingRow + deltaRow * index
            val column = startingColumn + deltaColumn * index
            letters.add(board[row][column])
        }
        val forward = letters.joinToString("")
        val reversed = forward.reversed()
        return forward.equals(word, ignoreCase = true) || reversed.equals(word, ignoreCase = true)
    }

    fun categoryNames(): List<String> = categoryWords.keys.toList()
}
