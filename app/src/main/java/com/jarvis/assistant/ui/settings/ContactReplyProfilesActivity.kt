package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.autoreply.ContactReplyProfile
import com.jarvis.assistant.autoreply.DiscoveredReplyContact
import com.jarvis.assistant.autoreply.ReplyProfileMode
import com.jarvis.assistant.autoreply.SmartReplyLogic
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch

class ContactReplyProfilesActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private var allDiscoveredContacts = emptyList<DiscoveredReplyContact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        content.addView(TextView(this).apply {
            text = "Contact reply profiles"
            textSize = 24f
        })
        content.addView(TextView(this).apply {
            text = "Profiles are local to this device. Identity matching uses app conversation IDs or phone-number hashes, never display names alone. New profiles only suggest replies until you explicitly enable limited auto-reply."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })
        content.addView(Button(this).apply {
            text = "Clear all local reply profile data"
            setOnClickListener {
                AlertDialog.Builder(this@ContactReplyProfilesActivity)
                    .setTitle("Clear local profile data?")
                    .setMessage("This deletes saved contact profiles, discovered identity labels, and reply event metadata from this device. It does not delete notification history.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                (application as JarvisApp).contactReplyProfileRepository.clearLocalData()
                            } catch (error: Exception) {
                                Toast.makeText(
                                    this@ContactReplyProfilesActivity,
                                    "Could not clear local profile data: ${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    .show()
            }
        })
        search = EditText(this).apply {
            hint = "Search contact or app"
            addTextChangedListener { renderContacts(allDiscoveredContacts) }
        }
        content.addView(search)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        setContentView(ScrollView(this).apply { addView(content) })
        ThemeManager.applyDynamicBackground(content)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as JarvisApp).contactReplyProfileRepository.discoveredContacts
                    .catch { error ->
                        Toast.makeText(
                            this@ContactReplyProfilesActivity,
                            "Could not load contact profiles: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .collect(::renderContacts)
            }
        }
    }

    private fun renderContacts(contacts: List<DiscoveredReplyContact>) {
        allDiscoveredContacts = contacts
        list.removeAllViews()
        if (!com.jarvis.assistant.autoreply.NotificationIntelligenceStore
                .areContactReplyProfilesEnabled(this)
        ) {
            list.addView(TextView(this).apply {
                text = "Enable Contact-specific reply profiles in Auto-Reply settings to discover new notification contacts."
                textSize = 15f
            })
            return
        }
        if (contacts.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No notification contacts discovered yet. New identities appear after supported notifications arrive while profile discovery is enabled."
                textSize = 15f
            })
            return
        }
        val query = search.text.toString().trim()
        val filtered = contacts.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.platformPackage.contains(query, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No discovered contact matches “$query”."
                textSize = 15f
            })
            return
        }
        filtered.forEach { contact ->
            val label = TextView(this).apply {
                text = "${contact.displayName}\n${contact.platformPackage}\n${contact.identityKind}"
                textSize = 15f
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            list.addView(label)
            list.addView(Button(this).apply {
                text = "Configure ${contact.displayName}"
                setOnClickListener { chooseProfileMode(contact) }
            })
        }
    }

    private fun chooseProfileMode(contact: DiscoveredReplyContact) {
        val app = application as JarvisApp
        lifecycleScope.launch {
            val existing = try {
                app.contactReplyProfileRepository.findProfile(
                    contact.platformPackage,
                    contact.identityKey
                )
            } catch (error: Exception) {
                Toast.makeText(
                    this@ContactReplyProfilesActivity,
                    "Could not load this contact profile: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val modes = arrayOf("Disabled", "Suggest only", "Limited auto-reply")
            val current = existing?.mode ?: ReplyProfileMode.SUGGEST_ONLY.name
            val selected = ReplyProfileMode.entries.indexOfFirst { it.name == current }.coerceAtLeast(1)
            AlertDialog.Builder(this@ContactReplyProfilesActivity)
                .setTitle(contact.displayName)
                .setSingleChoiceItems(modes, selected) { dialog, index ->
                    val mode = ReplyProfileMode.entries[index]
                    dialog.dismiss()
                    if (mode == ReplyProfileMode.LIMITED_AUTO_REPLY) {
                        AlertDialog.Builder(this@ContactReplyProfilesActivity)
                            .setTitle("Allow replies for this contact?")
                            .setMessage(
                                "HOPE may submit a reply using the original notification's quick-reply action. Replies remain subject to the global safety checks, limits, quiet hours, exclusions, and group-chat protections."
                            )
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Continue") { _, _ ->
                                editProfile(contact, existing, mode, autoReplyConfirmed = true)
                            }
                            .show()
                    } else {
                        editProfile(contact, existing, mode, autoReplyConfirmed = false)
                    }
                }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Remove profile") { _, _ ->
                    if (existing != null) lifecycleScope.launch {
                        app.contactReplyProfileRepository.deleteProfile(existing.id)
                    }
                }
                .show()
        }
    }

    private fun editProfile(
        contact: DiscoveredReplyContact,
        existing: ContactReplyProfile?,
        mode: ReplyProfileMode,
        autoReplyConfirmed: Boolean
    ) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val language = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ContactReplyProfilesActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto", "English", "Hindi", "Hinglish")
            )
            setSelection(
                listOf("Auto", "English", "Hindi", "Hinglish")
                    .indexOf(existing?.replyLanguage ?: "Auto").coerceAtLeast(0)
            )
        }
        val tone = EditText(this).apply {
            hint = "Tone (optional)"
            setText(existing?.tone.orEmpty())
            maxLines = 2
        }
        val instructions = EditText(this).apply {
            hint = "Contact-specific guidance (optional)"
            setText(existing?.instructions.orEmpty())
            minLines = 3
            maxLines = 5
        }
        form.addView(language)
        form.addView(tone)
        form.addView(instructions)
        val builder = AlertDialog.Builder(this)
            .setTitle("Reply behavior for ${contact.displayName}")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Preview prompt", null)
            .setPositiveButton("Save") { _, _ ->
                val profile = ContactReplyProfile(
                    id = existing?.id ?: 0,
                    platformPackage = contact.platformPackage,
                    identityKey = contact.identityKey,
                    identityKind = contact.identityKind,
                    displayName = contact.displayName,
                    mode = mode.name,
                    replyLanguage = language.selectedItem.toString(),
                    tone = tone.text.toString().trim().take(120),
                    instructions = instructions.text.toString().trim().take(500),
                    maxReplyCharacters = existing?.maxReplyCharacters ?: 300,
                    userConfirmedAutoReply = autoReplyConfirmed
                )
                lifecycleScope.launch {
                    try {
                        (application as JarvisApp).contactReplyProfileRepository.saveProfile(profile)
                        Toast.makeText(this@ContactReplyProfilesActivity, "Profile saved on this device.", Toast.LENGTH_SHORT).show()
                    } catch (error: IllegalArgumentException) {
                        Toast.makeText(this@ContactReplyProfilesActivity, error.message, Toast.LENGTH_LONG).show()
                    } catch (error: Exception) {
                        Toast.makeText(
                            this@ContactReplyProfilesActivity,
                            "Could not save this contact profile: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val preview = SmartReplyLogic.buildSystemInstruction(
                    ownerName = (application as JarvisApp).preferences.userName,
                    language = language.selectedItem.toString(),
                    tone = tone.text.toString().trim(),
                    appName = com.jarvis.assistant.autoreply.AutoReplyCoordinator.appName(
                        contact.platformPackage
                    ),
                    intent = SmartReplyLogic.Intent.OTHER,
                    contactInstructions = instructions.text.toString().trim()
                )
                AlertDialog.Builder(this)
                    .setTitle("Prompt preview")
                    .setMessage(preview)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
