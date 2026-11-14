package com.jarvis.assistant.ui.wordsearch

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityWordSearchBinding
import kotlin.math.max
import kotlin.random.Random

class WordSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWordSearchBinding
    private val random = Random(System.currentTimeMillis())
    private val engine = WordSearchEngine(random)
    private lateinit var currentPuzzle: WordSearchPuzzle
    private val selectedCells = mutableListOf<WordCell>()
    private val foundWords = mutableSetOf<String>()
    private var gamePaused = false
    private var hintCells: Set<WordCell> = emptySet()
    private var currentDifficulty = WordSearchDifficulty.EASY
    private var currentCategory = "General Vocabulary"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWordSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.hintButton.setOnClickListener { showHint() }
        binding.pauseButton.setOnClickListener { togglePause() }
        binding.newGameButton.setOnClickListener { startNewGame() }
        binding.restartButton.setOnClickListener { restartCurrentGame() }
        binding.exitButton.setOnClickListener { finish() }

        startNewGame()
    }

    private fun startNewGame() {
        currentCategory = engine.categoryNames().random(random)
        currentDifficulty = WordSearchDifficulty.values().first()
        generatePuzzle(currentDifficulty, currentCategory)
    }

    private fun restartCurrentGame() {
        generatePuzzle(currentDifficulty, currentCategory)
    }

    private fun generatePuzzle(difficulty: WordSearchDifficulty, category: String) {
        selectedCells.clear()
        foundWords.clear()
        hintCells = emptySet()
        currentPuzzle = engine.generatePuzzle(difficulty, category)
        renderBoard()
        renderWordList()
        refreshStatus()
    }

    private fun renderBoard() {
        binding.puzzleGrid.removeAllViews()
        binding.puzzleGrid.columnCount = currentPuzzle.columns
        binding.puzzleGrid.rowCount = currentPuzzle.rows

        val gridSize = currentPuzzle.rows
        val cellSizePx = dpToPx(if (gridSize <= 8) 42 else if (gridSize <= 10) 34 else 28)
        val rowMargin = dpToPx(2)
        val columnMargin = dpToPx(2)

        for (row in 0 until currentPuzzle.rows) {
            for (column in 0 until currentPuzzle.columns) {
                val cell = TextView(this).apply {
                    text = currentPuzzle.grid[row][column].toString()
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(ContextCompat.getColor(context, android.R.color.transparent))
                        setStroke(dpToPx(1), ContextCompat.getColor(context, R.color.accent_sapphire))
                        cornerRadius = dpToPx(6).toFloat()
                    }
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        if (gamePaused) {
                            Toast.makeText(this@WordSearchActivity, "Game is paused.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        toggleCellSelection(WordCell(row, column))
                    }
                    isFocusable = true
                    isClickable = true
                }

                val params = GridLayout.LayoutParams().apply {
                    width = cellSizePx
                    height = cellSizePx
                    setMargins(columnMargin, rowMargin, columnMargin, rowMargin)
                    rowSpec = GridLayout.spec(row)
                    columnSpec = GridLayout.spec(column)
                }
                binding.puzzleGrid.addView(cell, params)
            }
        }
        binding.difficultyText.text = "${currentPuzzle.difficulty.label} • ${currentPuzzle.category}"
    }

    private fun renderWordList() {
        binding.wordsList.removeAllViews()
        for (word in currentPuzzle.targetWords) {
            val wordView = TextView(this).apply {
                text = word
                textSize = 15f
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(
                    if (word in foundWords) ContextCompat.getColor(context, R.color.status_green)
                    else ContextCompat.getColor(context, R.color.text_primary)
                )
                alpha = if (word in foundWords) 0.75f else 1f
                paint.isStrikeThruText = word in foundWords
            }
            binding.wordsList.addView(wordView)
        }
    }

    private fun toggleCellSelection(cell: WordCell) {
        if (selectedCells.contains(cell)) {
            selectedCells.remove(cell)
            refreshBoardSelection()
            return
        }

        if (selectedCells.isEmpty()) {
            selectedCells.add(cell)
            refreshBoardSelection()
            return
        }

        val lastCell = selectedCells.last()
        val rowDelta = cell.row - lastCell.row
        val columnDelta = cell.column - lastCell.column
        val sameLine = rowDelta == 0 || columnDelta == 0 || kotlin.math.abs(rowDelta) == kotlin.math.abs(columnDelta)
        if (!sameLine) {
            selectedCells.clear()
            selectedCells.add(cell)
            refreshBoardSelection()
            return
        }

        val nextStep = selectedCells.size
        if (selectedCells.size == 1) {
            selectedCells.add(cell)
            refreshBoardSelection()
            evaluateSelection()
            return
        }

        val previousCell = selectedCells.last()
        val previousDeltaRow = previousCell.row - selectedCells[selectedCells.size - 2].row
        val previousDeltaColumn = previousCell.column - selectedCells[selectedCells.size - 2].column
        val isAligned = previousDeltaRow == rowDelta || previousDeltaColumn == columnDelta
        if (isAligned && selectedCells.size < currentPuzzle.targetWords.size) {
            selectedCells.add(cell)
            refreshBoardSelection()
            evaluateSelection()
            return
        }

        selectedCells.clear()
        selectedCells.add(cell)
        refreshBoardSelection()
    }

    private fun evaluateSelection() {
        if (selectedCells.size < 2) return

        val matchedWord = engine.validateSelection(selectedCells, currentPuzzle.grid, currentPuzzle.targetWords)
        if (matchedWord != null) {
            if (matchedWord in foundWords) {
                selectedCells.clear()
                refreshBoardSelection()
                return
            }
            foundWords.add(matchedWord)
            val highlight = currentPuzzle.getWordCells(matchedWord).toSet()
            hintCells = highlight
            selectedCells.clear()
            refreshBoardSelection()
            renderWordList()
            refreshStatus()
            Toast.makeText(this, "Found: $matchedWord", Toast.LENGTH_SHORT).show()
            if (foundWords.size == currentPuzzle.targetWords.size) {
                Toast.makeText(this, "Puzzle complete!", Toast.LENGTH_LONG).show()
            }
            return
        }

        Toast.makeText(this, "Not a valid word.", Toast.LENGTH_SHORT).show()
        selectedCells.clear()
        refreshBoardSelection()
    }

    private fun refreshBoardSelection() {
        for (index in 0 until binding.puzzleGrid.childCount) {
            val child = binding.puzzleGrid.getChildAt(index) as? TextView ?: continue
            val row = index / currentPuzzle.columns
            val column = index % currentPuzzle.columns
            val currentCell = WordCell(row, column)
            val isSelected = selectedCells.contains(currentCell)
            val isHint = hintCells.contains(currentCell)
            val baseBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ContextCompat.getColor(this@WordSearchActivity, android.R.color.transparent))
                setStroke(dpToPx(1), ContextCompat.getColor(this@WordSearchActivity, R.color.accent_sapphire))
                cornerRadius = dpToPx(6).toFloat()
            }
            child.background = when {
                isHint -> GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(ContextCompat.getColor(this@WordSearchActivity, R.color.status_green))
                    setStroke(dpToPx(1), ContextCompat.getColor(this@WordSearchActivity, R.color.status_green))
                    cornerRadius = dpToPx(6).toFloat()
                }
                isSelected -> GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(ContextCompat.getColor(this@WordSearchActivity, R.color.accent_sapphire))
                    setStroke(dpToPx(1), ContextCompat.getColor(this@WordSearchActivity, R.color.accent_sapphire))
                    cornerRadius = dpToPx(6).toFloat()
                }
                else -> baseBg
            }
        }
    }

    private fun showHint() {
        val remainingWord = currentPuzzle.targetWords.firstOrNull { it !in foundWords }
            ?: return
        val cells = currentPuzzle.getWordCells(remainingWord)
        if (cells.isEmpty()) {
            Toast.makeText(this, "No hint available for this word.", Toast.LENGTH_SHORT).show()
            return
        }
        hintCells = cells.toSet()
        refreshBoardSelection()
        Toast.makeText(this, "Hint: ${remainingWord} starts at ${cells.first().row + 1}, ${cells.first().column + 1}", Toast.LENGTH_SHORT).show()
    }

    private fun togglePause() {
        gamePaused = !gamePaused
        binding.pauseButton.text = if (gamePaused) "Resume" else "Pause"
        binding.statusText.text = if (gamePaused) "Paused" else "Ready"
    }

    private fun refreshStatus() {
        val totalWords = currentPuzzle.targetWords.size
        val foundCount = foundWords.size
        val percent = if (totalWords == 0) 0 else (foundCount * 100) / totalWords
        binding.foundWordsText.text = "Found $foundCount/$totalWords"
        binding.progressText.text = "$percent%"
        binding.statusText.text = if (gamePaused) "Paused" else if (foundCount == totalWords) "Complete" else "Ready"
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
