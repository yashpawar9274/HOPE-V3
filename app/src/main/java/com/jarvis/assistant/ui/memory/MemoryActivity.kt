package com.jarvis.assistant.ui.memory

import android.os.Bundle
import android.content.Intent
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
import com.jarvis.assistant.data.memory.CloudMemoryItem
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
    private lateinit var cloudAgentListener: CompoundButton.OnCheckedChangeListener

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
        setupCloudAgentControls()
        setupRecyclerView()
        setupCategoryChips()
        setupSearch()
        observeMemories()
        refreshCloudMemoryStatus()
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
                    lifecycleScope.launch {
                        try {
                            JarvisApp.instance.cloudMemoryRepository.pausePendingJobs()
                            refreshCloudMemoryStatus()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Toast.makeText(
                                this@MemoryActivity,
                                "Cloud memory is off on this device, but pending server jobs could not be paused.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
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
                        refreshCloudMemoryStatus()
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
                                        "Existing transcripts uploaded; Mem0 extraction is queued and will update in Cloud Memory Status.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    refreshCloudMemoryStatus()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    Toast.makeText(
                                        this@MemoryActivity,
                                        "Cloud memory is on, but old history backup failed: ${error.message ?: "service unavailable"}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                refreshCloudMemoryStatus()
                            }
                        }
                    }
                    .show()
            }
        }
        binding.switchCloudMemory.setOnCheckedChangeListener(cloudMemoryListener)
        binding.btnRefreshCloudMemoryStatus.setOnClickListener { refreshCloudMemoryStatus() }
        binding.btnSearchCloudMemories.setOnClickListener { showCloudMemorySearchDialog() }
        binding.btnRetryCloudMemory.setOnClickListener {
            if (!preferences.isCloudMemoryEnabled) {
                Toast.makeText(this, "Enable Cloud Long-Term Memory before retrying jobs.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                try {
                    val queued = JarvisApp.instance.cloudMemoryRepository.retryFailedJobs()
                    Toast.makeText(
                        this@MemoryActivity,
                        if (queued > 0) "$queued failed extraction job(s) queued for retry."
                        else "There are no failed extraction jobs to retry.",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshCloudMemoryStatus()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(
                        this@MemoryActivity,
                        error.message ?: "Failed extraction jobs could not be retried.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

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
                    "This deletes stored chat and voice transcripts and requests deletion of your Mem0 memories. Mem0 deletion is asynchronous and will be verified in Cloud Memory Status. This cannot be undone."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear all") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            val status = JarvisApp.instance.cloudMemoryRepository.clearAll()
                            Toast.makeText(
                                this@MemoryActivity,
                                if (status == "pending") {
                                    "Cloud transcripts deleted; Mem0 deletion is pending. Refresh status to verify."
                                } else {
                                    "Cloud transcripts deleted; Mem0 deletion status: $status."
                                },
                                Toast.LENGTH_LONG
                            ).show()
                            refreshCloudMemoryStatus()
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

    private fun setupCloudAgentControls() {
        val repository = JarvisApp.instance.cloudAgentRepository
        val preferences = JarvisApp.instance.preferences
        val currentUserId = LicenseManager.currentUser(this)?.uid
        if (currentUserId != null && preferences.cloudAgentOwnerId != currentUserId) {
            preferences.isCloudAgentEnabled = false
        }
        binding.switchCloudAgent.isChecked = repository.isEnabledForCurrentUser()
        binding.btnOpenCloudTasks.setOnClickListener {
            startActivity(Intent(this, com.jarvis.assistant.ui.agent.CloudAgentTaskActivity::class.java))
        }
        cloudAgentListener = CompoundButton.OnCheckedChangeListener { button, enabled ->
            if (!enabled) {
                lifecycleScope.launch {
                    try {
                        repository.setEnabled(false)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        binding.switchCloudAgent.setOnCheckedChangeListener(null)
                        binding.switchCloudAgent.isChecked = repository.isEnabledForCurrentUser()
                        binding.switchCloudAgent.setOnCheckedChangeListener(cloudAgentListener)
                        Toast.makeText(
                            this@MemoryActivity,
                            "Cloud Agent remains enabled because the server could not confirm the change. ${error.message.orEmpty()}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                return@OnCheckedChangeListener
            }

            button.setOnCheckedChangeListener(null)
            button.isChecked = false
            button.setOnCheckedChangeListener(cloudAgentListener)
            if (!SupabaseConfiguration.publicClient.isConfigured) {
                Toast.makeText(this, "Cloud Agent backend is not configured.", Toast.LENGTH_LONG).show()
                return@OnCheckedChangeListener
            }
            if (LicenseManager.currentUser(this) == null) {
                Toast.makeText(this, "Sign in to your existing HOPE account before enabling Cloud Agent.", Toast.LENGTH_LONG).show()
                return@OnCheckedChangeListener
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Enable Cloud Agent Tasks?")
                .setMessage(
                    "Task objectives are sent to Gemini to create a plan, and task status and the plan are stored in HOPE's cloud gateway under your existing Firebase account. Device readings are shared only after you approve a specific request. Cloud Memory is separate and will not be read unless separately enabled. No device action runs without your confirmation here. You can turn Cloud Agent off here at any time."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Enable") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            repository.setEnabled(true)
                            binding.switchCloudAgent.setOnCheckedChangeListener(null)
                            binding.switchCloudAgent.isChecked = true
                            binding.switchCloudAgent.setOnCheckedChangeListener(cloudAgentListener)
                            Toast.makeText(
                                this@MemoryActivity,
                                "Cloud Agent enabled for task coordination.",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Toast.makeText(
                                this@MemoryActivity,
                                error.message ?: "Cloud Agent consent could not be saved.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .show()
        }
        binding.switchCloudAgent.setOnCheckedChangeListener(cloudAgentListener)
    }

    private fun refreshCloudMemoryStatus() {
        val user = LicenseManager.currentUser(this)
        val enabled = user != null &&
            JarvisApp.instance.preferences.isCloudMemoryEnabled &&
            JarvisApp.instance.preferences.cloudMemoryOwnerId == user.uid
        val configReady = SupabaseConfiguration.publicClient.isConfigured
        binding.tvCloudMemoryStatus.text =
            "Supabase: ${if (configReady) "configured" else "not configured"} · Firebase: ${if (user != null) "signed in" else "signed out"} · Cloud Memory: ${if (enabled) "on" else "off"}"
        if (!configReady || user == null) return

        binding.btnRefreshCloudMemoryStatus.isEnabled = false
        lifecycleScope.launch {
            try {
                val status = JarvisApp.instance.cloudMemoryRepository.getSyncStatus()
                val currentUser = LicenseManager.currentUser(this@MemoryActivity)
                val currentEnabled = currentUser != null &&
                    JarvisApp.instance.preferences.isCloudMemoryEnabled &&
                    JarvisApp.instance.preferences.cloudMemoryOwnerId == currentUser.uid
                binding.tvCloudMemoryStatus.text = buildString {
                    append("Supabase: configured · Firebase: ${if (status.authenticated) "authenticated" else "not authenticated"}")
                    append(" · Cloud Memory: ${if (currentEnabled) "on" else "off"}")
                    append("\nMem0 API: ${status.mem0KeyStatus}")
                    append("\nExtraction: ${status.queued} queued, ${status.processing} processing, ${status.pending} pending, ${status.retryWait} retrying, ${status.failed} failed")
                    append("\nSuccessful extractions: ${status.succeeded}")
                    status.lastSuccessfulExtractionAt?.let { append("\nLast successful extraction: $it") }
                    status.lastRetrievalAt?.let {
                        append("\nLast retrieval: $it (${status.lastMem0ResultCount} Mem0, ${status.lastTranscriptResultCount} transcript results)")
                    }
                    val appPreferences = JarvisApp.instance.preferences
                    val injectedAt = appPreferences.cloudMemoryLastContextInjectionAt
                    if (injectedAt > 0L && appPreferences.cloudMemoryContextInjectionOwnerId == currentUser?.uid) {
                        append("\nGemini context injection: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(injectedAt))}")
                    } else {
                        append("\nGemini context injection: no non-empty cloud results injected on this device yet")
                    }
                    status.lastErrorCategory?.let { append("\nLast error category: $it") }
                    if (status.lastDeleteStatus != "none") {
                        append("\nLast clear-all status: ${status.lastDeleteStatus}")
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                binding.tvCloudMemoryStatus.text =
                    "Cloud memory status unavailable: ${error.message ?: "service unavailable"}"
            } finally {
                binding.btnRefreshCloudMemoryStatus.isEnabled = true
            }
        }
    }

    private fun showCloudMemorySearchDialog() {
        if (!JarvisApp.instance.preferences.isCloudMemoryEnabled) {
            Toast.makeText(this, "Enable Cloud Long-Term Memory to search Mem0.", Toast.LENGTH_LONG).show()
            return
        }
        val queryInput = EditText(this).apply {
            hint = "Search saved cloud memories"
            setTextColor(ContextCompat.getColor(this@MemoryActivity, R.color.text_primary))
            setPadding(24, 20, 24, 20)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Search Mem0 Memories")
            .setView(queryInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("SEARCH", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val query = queryInput.text.toString().trim()
                        if (query.isBlank()) {
                            queryInput.error = "Enter a search phrase."
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        lifecycleScope.launch {
                            try {
                                val memories = JarvisApp.instance.cloudMemoryRepository.searchCloudMemories(query)
                                showCloudMemoryResults(memories)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Toast.makeText(
                                    this@MemoryActivity,
                                    error.message ?: "Cloud memories could not be searched.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showCloudMemoryResults(memories: List<CloudMemoryItem>) {
        if (memories.isEmpty()) {
            Toast.makeText(this, "No matching Mem0 memories were found.", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Mem0 Memories (${memories.size})")
            .setItems(memories.map { it.text }.toTypedArray()) { _, index ->
                showCloudMemoryActions(memories[index])
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showCloudMemoryActions(memory: CloudMemoryItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Cloud Memory")
            .setMessage(memory.text)
            .setPositiveButton("EDIT") { _, _ -> showEditCloudMemoryDialog(memory) }
            .setNegativeButton("DELETE") { _, _ -> confirmDeleteCloudMemory(memory) }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun showEditCloudMemoryDialog(memory: CloudMemoryItem) {
        val editor = EditText(this).apply {
            setText(memory.text)
            setSelection(text.length)
            setPadding(24, 20, 24, 20)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Correct Cloud Memory")
            .setView(editor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("SAVE") { _, _ ->
                lifecycleScope.launch {
                    try {
                        JarvisApp.instance.cloudMemoryRepository.updateCloudMemory(
                            memory.id,
                            editor.text.toString()
                        )
                        Toast.makeText(this@MemoryActivity, "Cloud memory updated and verified.", Toast.LENGTH_LONG).show()
                        refreshCloudMemoryStatus()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Toast.makeText(
                            this@MemoryActivity,
                            error.message ?: "Cloud memory could not be updated.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteCloudMemory(memory: CloudMemoryItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this cloud memory?")
            .setMessage("This removes the selected Mem0 memory for your account.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("DELETE") { _, _ ->
                lifecycleScope.launch {
                    try {
                        JarvisApp.instance.cloudMemoryRepository.deleteCloudMemory(memory.id)
                        Toast.makeText(this@MemoryActivity, "Cloud memory deleted and verified.", Toast.LENGTH_LONG).show()
                        refreshCloudMemoryStatus()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Toast.makeText(
                            this@MemoryActivity,
                            error.message ?: "Cloud memory could not be deleted.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
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
