package com.jarvis.assistant.ui.memory

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.supabase.SupabaseConfiguration
import com.jarvis.assistant.databinding.ActivityMemoryBinding
import com.jarvis.assistant.license.LicenseManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MemoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemoryBinding
    private val memoryRepo by lazy { JarvisApp.instance.memoryRepository }
    private lateinit var adapter: MemoryAdapter
    private lateinit var cloudMemoryListener: CompoundButton.OnCheckedChangeListener

    private var allMemories: List<MemoryEntity> = emptyList()
    private var selectedCategory: String = "All"
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        setupToolbar()
        setupCloudMemoryControls()
        setupRecyclerView()
        setupCategoryChips()
        setupSearch()
        observeMemories()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddMemory.setOnClickListener { showAddMemoryDialog() }
    }

    private fun setupCloudMemoryControls() {
        val preferences = JarvisApp.instance.preferences
        val currentUserId = LicenseManager.currentUser(this)?.uid
        if (currentUserId != null && preferences.isCloudMemoryEnabled &&
            preferences.cloudMemoryOwnerId != currentUserId
        ) {
            preferences.isCloudMemoryEnabled = false
        }
        binding.switchCloudMemory.isChecked =
            currentUserId != null && preferences.isCloudMemoryEnabled &&
                preferences.cloudMemoryOwnerId == currentUserId
        cloudMemoryListener = object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(button: CompoundButton, enabled: Boolean) {
                if (!enabled) {
                    preferences.isCloudMemoryEnabled = false
                    return
                }

                button.setOnCheckedChangeListener(null)
                button.isChecked = false
                button.setOnCheckedChangeListener(this)

                if (!SupabaseConfiguration.publicClient.isConfigured) {
                    Toast.makeText(this@MemoryActivity, "Cloud memory backend is not configured.", Toast.LENGTH_LONG).show()
                    return
                }
                if (LicenseManager.currentUser(this@MemoryActivity) == null) {
                    Toast.makeText(this@MemoryActivity, "Sign in to your existing HOPE account before enabling cloud memory.", Toast.LENGTH_LONG).show()
                    return
                }

                MaterialAlertDialogBuilder(this@MemoryActivity)
                    .setTitle("Enable Cloud Long-Term Memory?")
                    .setMessage(
                        "When enabled, completed text chats and regular voice transcripts are sent to HOPE's Supabase database and Mem0 under your Firebase account ID. Up to 100 existing local turns already associated with this Firebase account are backed up once; older untagged history is not copied. Mem0 extracts searchable memories, and HOPE may use relevant saved memories in later replies. Voice audio is not stored; clipboard and automation-only turns are excluded. You can turn this off or clear cloud memories here at any time."
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Enable") { _, _ ->
                        val userId = LicenseManager.currentUser(this@MemoryActivity)?.uid
                        if (userId == null) {
                            Toast.makeText(this@MemoryActivity, "Sign-in expired. Sign in again to enable cloud memory.", Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }
                        preferences.cloudMemoryOwnerId = userId
                        preferences.isCloudMemoryEnabled = true
                        binding.switchCloudMemory.setOnCheckedChangeListener(null)
                        binding.switchCloudMemory.isChecked = true
                        binding.switchCloudMemory.setOnCheckedChangeListener(cloudMemoryListener)
                        if (preferences.cloudMemoryHistoryBackedUpForUserId != userId) {
                            val canImportHistory = preferences.cloudMemoryHistoryOwnerId.isBlank() ||
                                preferences.cloudMemoryHistoryOwnerId == userId
                            if (!canImportHistory) {
                                preferences.cloudMemoryHistoryBackedUpForUserId = userId
                                Toast.makeText(
                                    this@MemoryActivity,
                                    "Cloud memory enabled. Previous device history was not copied because it may belong to another Firebase account.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@setPositiveButton
                            }
                            lifecycleScope.launch {
                                try {
                                    JarvisApp.instance.cloudMemoryRepository.backUpHistory(
                                        JarvisApp.instance.chatRepository.turns.value
                                    )
                                    preferences.cloudMemoryHistoryOwnerId = userId
                                    preferences.cloudMemoryHistoryBackedUpForUserId = userId
                                    Toast.makeText(
                                        this@MemoryActivity,
                                        "Existing chat history backed up to cloud memory.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    Toast.makeText(
                                        this@MemoryActivity,
                                        "Cloud memory is on, but old history backup failed: ${error.message ?: "service unavailable"}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                    .show()
            }
        }
        binding.switchCloudMemory.setOnCheckedChangeListener(cloudMemoryListener)

        binding.btnClearCloudMemory.setOnClickListener {
            if (!SupabaseConfiguration.publicClient.isConfigured) {
                Toast.makeText(this, "Cloud memory backend is not configured.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (LicenseManager.currentUser(this) == null) {
                Toast.makeText(this, "Sign in to your existing HOPE account to clear cloud memory.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear all cloud memories?")
                .setMessage(
                    "This deletes stored chat and voice transcripts and queues deletion of your Mem0 memories. This cannot be undone."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear all") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            JarvisApp.instance.cloudMemoryRepository.clearAll()
                            Toast.makeText(
                                this@MemoryActivity,
                                "Cloud transcripts deleted; Mem0 deletion queued.",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (error: Exception) {
                            Toast.makeText(
                                this@MemoryActivity,
                                error.message ?: "Cloud memories could not be cleared.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .show()
        }
    }

    private fun setupRecyclerView() {
        adapter = MemoryAdapter(
            onEdit = { memory -> showEditMemoryDialog(memory) },
            onDelete = { memory -> confirmDeleteMemory(memory) }
        )
        binding.rvMemories.layoutManager = LinearLayoutManager(this)
        binding.rvMemories.adapter = adapter
    }

    private fun setupCategoryChips() {
        val chips = listOf(
            Pair(binding.chipCategoryAll, "All"),
            Pair(binding.chipCategoryPersonal, MemoryEntity.CATEGORY_PERSONAL),
            Pair(binding.chipCategoryPreferences, MemoryEntity.CATEGORY_PREFERENCES),
            Pair(binding.chipCategoryContacts, MemoryEntity.CATEGORY_CONTACTS),
            Pair(binding.chipCategoryRoutines, MemoryEntity.CATEGORY_ROUTINES),
            Pair(binding.chipCategoryNotes, MemoryEntity.CATEGORY_NOTES)
        )

        for ((view, category) in chips) {
            view.setOnClickListener {
                selectedCategory = category
                updateChipStyles(chips, view)
                filterAndDisplay()
            }
        }
    }

    private fun updateChipStyles(chips: List<Pair<TextView, String>>, selectedView: TextView) {
        val tvAccent = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
        val accentColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.primary_cyan)

        val tvSecondary = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextSecondary, tvSecondary, true)
        val secondaryColor = if (tvSecondary.data != 0) tvSecondary.data else ContextCompat.getColor(this, R.color.text_secondary)

        for ((view, _) in chips) {
            if (view == selectedView) {
                view.setBackgroundResource(R.drawable.bg_chip_selected)
                view.setTextColor(accentColor)
            } else {
                view.setBackgroundResource(R.drawable.bg_chip_unselected)
                view.setTextColor(secondaryColor)
            }
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                filterAndDisplay()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeMemories() {
        lifecycleScope.launch {
            memoryRepo.getAllMemoriesFlow().collectLatest { memories ->
                allMemories = memories
                filterAndDisplay()
            }
        }
    }

    private fun filterAndDisplay() {
        val filtered = allMemories.filter { mem ->
            val matchesCategory = (selectedCategory == "All" || mem.category.equals(selectedCategory, ignoreCase = true))
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                mem.rawText.contains(searchQuery, ignoreCase = true) ||
                mem.key.contains(searchQuery, ignoreCase = true) ||
                mem.category.contains(searchQuery, ignoreCase = true)
            }
            matchesCategory && matchesSearch
        }

        binding.tvMemoryCount.text = "SAVED MEMORIES (${filtered.size})"
        adapter.submitList(filtered)

        if (filtered.isEmpty()) {
            binding.tvNoMemories.visibility = View.VISIBLE
        } else {
            binding.tvNoMemories.visibility = View.GONE
        }
    }

    private fun showAddMemoryDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MemoryActivity,
                android.R.layout.simple_spinner_dropdown_item,
                MemoryEntity.ALL_CATEGORIES
            )
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(16, 16, 16, 16)
        }

        val tvPrimary = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextPrimary, tvPrimary, true)
        val textPrimaryColor = if (tvPrimary.data != 0) tvPrimary.data else ContextCompat.getColor(this@MemoryActivity, R.color.text_primary)

        val tvMuted = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
        val textMutedColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this@MemoryActivity, R.color.text_muted)

        val editText = EditText(this).apply {
            hint = "e.g., My favorite song is Believer"
            setTextColor(textPrimaryColor)
            setHintTextColor(textMutedColor)
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }

        layout.addView(spinner)
        layout.addView(editText)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add New Memory")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                val fact = editText.text.toString().trim()
                val category = spinner.selectedItem.toString()
                if (fact.isNotBlank()) {
                    lifecycleScope.launch {
                        try {
                            memoryRepo.saveMemory(
                                rawText = fact,
                                category = category,
                                key = fact.take(30).trim(),
                                value = fact
                            )
                            if (JarvisApp.instance.preferences.isCloudMemoryEnabled) {
                                JarvisApp.instance.cloudMemoryRepository.rememberNote(fact)
                            }
                            Toast.makeText(
                                this@MemoryActivity,
                                if (JarvisApp.instance.preferences.isCloudMemoryEnabled) {
                                    "Memory saved on this device and to cloud memory."
                                } else {
                                    "Memory saved on this device."
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (error: Exception) {
                            Toast.makeText(
                                this@MemoryActivity,
                                error.message ?: "Memory could not be saved to cloud.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showEditMemoryDialog(memory: MemoryEntity) {
        val tvPrimary = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextPrimary, tvPrimary, true)
        val textPrimaryColor = if (tvPrimary.data != 0) tvPrimary.data else ContextCompat.getColor(this@MemoryActivity, R.color.text_primary)

        val editText = EditText(this).apply {
            setText(memory.rawText)
            setTextColor(textPrimaryColor)
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(24, 24, 24, 24)
        }

        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 16)
            addView(editText)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Memory")
            .setView(container)
            .setPositiveButton("UPDATE") { _, _ ->
                val newFact = editText.text.toString().trim()
                if (newFact.isNotBlank()) {
                    lifecycleScope.launch {
                        memoryRepo.updateMemory(
                            memory.copy(
                                rawText = newFact,
                                value = newFact,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        Toast.makeText(this@MemoryActivity, "Memory updated", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmDeleteMemory(memory: MemoryEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Memory")
            .setMessage("Are you sure you want HOPE to forget this fact?\n\n\"${memory.rawText}\"")
            .setPositiveButton("DELETE") { _, _ ->
                lifecycleScope.launch {
                    memoryRepo.deleteMemoryById(memory.id)
                    Toast.makeText(this@MemoryActivity, "Memory deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
