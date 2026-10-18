package com.jarvis.assistant.telephony

import android.Manifest
import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.CallLog
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HopeDialerActivity : AppCompatActivity() {
    private enum class Page { DIAL, CONTACTS, RECENTS }
    private enum class RecentFilter(val label: String, val callType: Int?) {
        ALL("All", null),
        MISSED("Missed", CallLog.Calls.MISSED_TYPE),
        INCOMING("Incoming", CallLog.Calls.INCOMING_TYPE),
        OUTGOING("Outgoing", CallLog.Calls.OUTGOING_TYPE)
    }

    private data class PhoneEntry(
        val name: String,
        val number: String,
        val detail: String = "",
        val contactId: Long? = null,
        val lookupKey: String? = null,
        val lookupUri: String? = null,
        val callType: Int? = null,
        val starred: Boolean = false
    )

    private lateinit var content: LinearLayout
    private lateinit var numberInput: EditText
    private lateinit var simButton: Button
    private lateinit var contentScroll: ScrollView
    private val pageTabs = mutableMapOf<Page, Button>()
    private var activePage = Page.DIAL
    private var pendingNumber: String? = null
    private var selectedAccount: PhoneAccountHandle? = null
    private var availableAccounts: List<PhoneAccountHandle> = emptyList()
    private var callUpdatesRegistered = false
    private var contactsJob: Job? = null
    private var recentCallsJob: Job? = null
    private var dialSuggestionsJob: Job? = null
    private var dialNumber = ""
    private var recentFilter = RecentFilter.ALL
    private var pendingFavoriteContactId: Long? = null
    private var pendingFavoriteState: Boolean = false

    private val favoritePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val contactId = pendingFavoriteContactId
        pendingFavoriteContactId = null
        if (granted && contactId != null) setFavorite(contactId, pendingFavoriteState)
        else Toast.makeText(this, "Contacts write permission is required to change favorites.", Toast.LENGTH_LONG).show()
    }

    private val callUpdates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != HopeCallController.ACTION_CALL_CHANGED) return
            val call = HopeCallController.currentCall(this@HopeDialerActivity) ?: return
            if (call.state !in setOf(
                    android.telecom.Call.STATE_RINGING,
                    android.telecom.Call.STATE_DIALING,
                    android.telecom.Call.STATE_CONNECTING,
                    android.telecom.Call.STATE_ACTIVE
                )
            ) return
            startActivity(
                Intent(this@HopeDialerActivity, IncomingCallActivity::class.java)
                    .putExtra(HopeCallController.EXTRA_CALL_ID, call.id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private val callPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val number = pendingNumber
        pendingNumber = null
        if (granted && !number.isNullOrBlank()) placeCall(number)
        else Toast.makeText(this, "Phone permission is required to place a call.", Toast.LENGTH_LONG).show()
    }

    private val contactsPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showContacts("") else showPermissionMessage("Contacts access was denied. Dial pad remains available.")
    }

    private val callLogPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showRecents() else showPermissionMessage("Call history access was denied. Dial pad remains available.")
    }

    private val phoneStatePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) refreshPhoneAccounts(showChooser = true)
        else Toast.makeText(this, "SIM selection permission was denied; Android will choose the SIM.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        if (!PhoneRoleHelper.isDefaultPhoneApp(this)) {
            startActivity(Intent(this, PhoneSetupActivity::class.java))
            finish()
            return
        }
        buildUi()
        handleDialIntent(intent)
        refreshPhoneAccounts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
        showPage(Page.DIAL)
    }

    override fun onResume() {
        super.onResume()
        if (!callUpdatesRegistered) {
            ContextCompat.registerReceiver(
                this,
                callUpdates,
                IntentFilter(HopeCallController.ACTION_CALL_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            callUpdatesRegistered = true
        }
        if (::content.isInitialized && activePage == Page.RECENTS) showPage(Page.RECENTS)
    }

    override fun onPause() {
        if (callUpdatesRegistered) {
            unregisterReceiver(callUpdates)
            callUpdatesRegistered = false
        }
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(dp(20) + bars.left, dp(20) + bars.top, dp(20) + bars.right, dp(18) + bars.bottom)
            insets
        }
        ThemeManager.applyDynamicBackground(root)
        val titleColor = themeColor(R.attr.themeTextPrimary)
        root.addView(TextView(this).apply {
            text = "HOPE PHONE"
            textSize = 13f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@HopeDialerActivity, R.color.accent_sapphire))
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "Calls & contacts"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(titleColor)
            setPadding(0, dp(4), 0, dp(14))
        }, matchWidth())
        val tabs = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }
        listOf(
            "Dial pad" to Page.DIAL,
            "Contacts" to Page.CONTACTS,
            "Recent" to Page.RECENTS
        ).forEach { (label, page) ->
        val tab = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { showPage(page) }
        }
        pageTabs[page] = tab
        tabs.addView(tab, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        })
        }
        root.addView(tabs, matchWidth())
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        contentScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showPage(Page.DIAL)
    }

    private fun showPage(page: Page) {
        activePage = page
        pageTabs.forEach { (tabPage, button) ->
            button.isSelected = tabPage == page
            button.alpha = if (tabPage == page) 1f else 0.72f
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (tabPage == page) {
                    ContextCompat.getColor(this, R.color.accent_sapphire)
                } else {
                    themeColor(R.attr.themeSurface)
                }
            )
            button.setTextColor(
                if (tabPage == page) ContextCompat.getColor(this, android.R.color.white)
                else themeColor(R.attr.themeTextPrimary)
            )
        }
        dialSuggestionsJob?.cancel()
        recentCallsJob?.cancel()
        content.removeAllViews()
        when (page) {
            Page.DIAL -> showDialPad()
            Page.CONTACTS -> {
                if (hasPermission(Manifest.permission.READ_CONTACTS)) showContacts("")
                else {
                    showPermissionMessage("Allow Contacts permission to search and call people saved on this device.")
                    addActionButton("Allow Contacts Access") {
                        contactsPermissionRequest.launch(Manifest.permission.READ_CONTACTS)
                    }
                }
            }
            Page.RECENTS -> {
                if (hasPermission(Manifest.permission.READ_CALL_LOG)) showRecents()
                else {
                    showPermissionMessage("Allow Call History permission to view recent incoming, outgoing, and missed calls.")
                    addActionButton("Allow Call History Access") {
                        callLogPermissionRequest.launch(Manifest.permission.READ_CALL_LOG)
                    }
                }
            }
        }
    }

    private fun showDialPad() {
        val ink = themeColor(R.attr.themeTextPrimary)
        val suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        numberInput = EditText(this).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textSize = 27f
            gravity = Gravity.CENTER
            setSingleLine(true)
            setTextColor(ink)
            setText(dialNumber)
            addTextChangedListener(SimpleTextWatcher {
                dialNumber = it
                if (hasPermission(Manifest.permission.READ_CONTACTS)) {
                    loadDialSuggestions(it, suggestions)
                } else {
                    suggestions.removeAllViews()
                    suggestions.visibility = View.GONE
                }
            })
        }
        content.addView(numberInput, matchWidth().apply {
            topMargin = dp(18)
            bottomMargin = dp(8)
        })
        simButton = Button(this).apply {
            text = simButtonLabel()
            isAllCaps = false
            setOnClickListener { chooseSim() }
        }
        content.addView(simButton, matchWidth())
        val keys = GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
        }
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#").forEach { digit ->
            keys.addView(Button(this).apply {
                text = digit
                textSize = 22f
                setOnClickListener { numberInput.append(digit) }
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(58)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        content.addView(keys, matchWidth().apply { topMargin = dp(8) })
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }
        actions.addView(Button(this@HopeDialerActivity).apply {
            text = "Clear"
            isAllCaps = false
            setOnClickListener {
                numberInput.text.clear()
            }
            setOnLongClickListener {
                numberInput.text.clear()
                true
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        actions.addView(Button(this@HopeDialerActivity).apply {
            text = "Delete"
            isAllCaps = false
            setOnClickListener { deleteLastDigit() }
            setOnLongClickListener {
                numberInput.text.clear()
                true
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        actions.addView(Button(this@HopeDialerActivity).apply {
            text = "Call"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@HopeDialerActivity, android.R.color.white))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@HopeDialerActivity, R.color.status_green)
            )
            setOnClickListener {
                val number = numberInput.text.toString().trim()
                if (number.isBlank()) {
                    Toast.makeText(this@HopeDialerActivity, "Enter a phone number.", Toast.LENGTH_SHORT).show()
                } else if (!PhoneRoleHelper.isDefaultPhoneApp(this@HopeDialerActivity)) {
                    Toast.makeText(this@HopeDialerActivity, "Set HOPE as the default phone app before placing calls.", Toast.LENGTH_LONG).show()
                } else if (!hasPermission(Manifest.permission.CALL_PHONE)) {
                    pendingNumber = number
                    callPermissionRequest.launch(Manifest.permission.CALL_PHONE)
                } else {
                    placeCall(number)
                }
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        content.addView(actions, matchWidth().apply { topMargin = dp(12) })
        content.addView(suggestions, matchWidth())
        if (dialNumber.isNotBlank() && hasPermission(Manifest.permission.READ_CONTACTS)) {
            loadDialSuggestions(dialNumber, suggestions)
        }
    }

    private fun showContacts(initialQuery: String) {
        activePage = Page.CONTACTS
        content.removeAllViews()
        val search = EditText(this).apply {
            hint = "Search contacts"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(initialQuery)
        }
        content.addView(search, matchWidth().apply { topMargin = dp(12); bottomMargin = dp(8) })
        val index = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        val letters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        index.addView(letters)
        content.addView(index, matchWidth())
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results, matchWidth())
        fun load(query: String) {
            contactsJob?.cancel()
            contactsJob = lifecycleScope.launch {
                val entries = withContext(Dispatchers.IO) { queryContacts(query) }
                if (activePage != Page.CONTACTS || isFinishing) return@launch
                results.removeAllViews()
                letters.removeAllViews()
                if (entries.isEmpty()) {
                    index.visibility = View.GONE
                    addListMessage(results, if (query.isBlank()) "No contacts found on this device." else "No matching contacts.")
                } else {
                    val sections = entries.groupBy { contactInitial(it.name) }.toSortedMap()
                    sections.forEach { (initial, section) ->
                        val header = TextView(this@HopeDialerActivity).apply {
                            text = initial
                            tag = "contact-index-$initial"
                            textSize = 14f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(themeColor(R.attr.themeTextMuted))
                            setPadding(dp(8), dp(12), dp(8), dp(4))
                        }
                        results.addView(header, matchWidth())
                        section.forEach { entry ->
                            addEntry(results, entry) { placeCallAfterPermission(entry.number) }
                        }
                    }
                    index.visibility = if (query.isBlank() && sections.size > 1) View.VISIBLE else View.GONE
                    if (index.visibility == View.VISIBLE) {
                        sections.keys.forEach { initial ->
                            letters.addView(TextView(this@HopeDialerActivity).apply {
                                text = initial
                                textSize = 14f
                                gravity = Gravity.CENTER
                                setTextColor(ContextCompat.getColor(this@HopeDialerActivity, R.color.accent_sapphire))
                                setPadding(dp(9), dp(9), dp(9), dp(9))
                                contentDescription = "Jump to contacts starting with $initial"
                                setOnClickListener {
                                    val target = results.findViewWithTag<View>("contact-index-$initial")
                                    if (target != null) contentScroll.smoothScrollTo(0, results.top + target.top)
                                }
                            })
                        }
                    }
                }
            }
        }
        search.addTextChangedListener(SimpleTextWatcher { load(it) })
        load(initialQuery)
    }

    private fun showRecents() {
        activePage = Page.RECENTS
        content.removeAllViews()
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        RecentFilter.values().forEach { filter ->
            filters.addView(Button(this).apply {
                text = filter.label
                isAllCaps = false
                isSelected = filter == recentFilter
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (filter == recentFilter) ContextCompat.getColor(this@HopeDialerActivity, R.color.accent_sapphire)
                    else themeColor(R.attr.themeSurface)
                )
                setTextColor(
                    if (filter == recentFilter) ContextCompat.getColor(this@HopeDialerActivity, android.R.color.white)
                    else themeColor(R.attr.themeTextPrimary)
                )
                setOnClickListener {
                    recentFilter = filter
                    showRecents()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(1), dp(4), dp(1), dp(8))
            })
        }
        content.addView(filters, matchWidth())
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results, matchWidth())
        recentCallsJob = lifecycleScope.launch {
            val requestedFilter = recentFilter
            val entries = withContext(Dispatchers.IO) { queryRecentCalls(requestedFilter) }
            if (activePage != Page.RECENTS || recentFilter != requestedFilter || isFinishing) return@launch
            results.removeAllViews()
            if (entries.isEmpty()) {
                addListMessage(results, if (recentFilter == RecentFilter.ALL) "No recent calls found." else "No ${recentFilter.label.lowercase()} calls found.")
            }
            entries.forEach { entry ->
                addEntry(results, entry) { placeCallAfterPermission(entry.number) }
            }
        }
    }

    private fun loadDialSuggestions(query: String, results: LinearLayout) {
        dialSuggestionsJob?.cancel()
        if (query.isBlank()) {
            results.visibility = View.GONE
            results.removeAllViews()
            return
        }
        results.visibility = View.VISIBLE
        dialSuggestionsJob = lifecycleScope.launch {
            val matches = withContext(Dispatchers.IO) { queryContacts(query).take(5) }
            if (activePage != Page.DIAL || isFinishing || numberInput.text.toString() != query) return@launch
            results.removeAllViews()
            if (matches.isEmpty()) {
                addListMessage(results, "No matching contacts")
            } else {
                matches.forEach { entry ->
                    addEntry(results, entry) { placeCallAfterPermission(entry.number) }
                }
            }
        }
    }

    private fun deleteLastDigit() {
        val length = numberInput.text.length
        if (length > 0) numberInput.text.delete(length - 1, length)
    }

    private fun contactInitial(name: String): String =
        name.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"

    private fun queryContacts(query: String): List<PhoneEntry> {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()
        val entries = mutableListOf<PhoneEntry>()
        val selection = if (query.isBlank()) null else
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                    ContactsContract.CommonDataKinds.Phone.STARRED
                ),
                selection,
                args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && entries.size < MAX_PHONE_LIST_ITEMS) {
                    val number = cursor.getString(1).orEmpty()
                    val displayName = cursor.getString(0).orEmpty()
                    entries += PhoneEntry(
                        displayName.ifBlank { formatPhoneNumber(number) },
                        number,
                        contactId = cursor.getLong(2),
                        lookupKey = cursor.getString(3),
                        starred = cursor.getInt(4) != 0
                    )
                }
            }
        } catch (error: SecurityException) {
            android.util.Log.w(TAG, "Contacts permission was revoked while listing contacts.", error)
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Could not read device contacts.", error)
        }
        return entries.filter { it.number.isNotBlank() }
    }

    private fun queryRecentCalls(filter: RecentFilter): List<PhoneEntry> {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) return emptyList()
        val entries = mutableListOf<PhoneEntry>()
        val typeColumn = CallLog.Calls.TYPE
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.NUMBER,
                    typeColumn,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.CACHED_LOOKUP_URI
                ),
                filter.callType?.let { "$typeColumn = ?" },
                filter.callType?.let { arrayOf(it.toString()) },
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && entries.size < MAX_PHONE_LIST_ITEMS) {
                    val callType = cursor.getInt(2)
                    val date = cursor.getLong(3)
                    val duration = cursor.getLong(4)
                    val type = when (callType) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Declined"
                        else -> "Call"
                    }
                    val number = cursor.getString(1).orEmpty()
                    val caller = cursor.getString(0).orEmpty()
                    val dateTime = "${android.text.format.DateFormat.getMediumDateFormat(this).format(date)} ${android.text.format.DateFormat.getTimeFormat(this).format(date)}"
                    val durationLabel = if (callType == CallLog.Calls.OUTGOING_TYPE ||
                        callType == CallLog.Calls.INCOMING_TYPE
                    ) " • ${formatDuration(duration)}" else ""
                    entries += PhoneEntry(
                        caller.ifBlank { number.takeIf(String::isNotBlank)?.let(::formatPhoneNumber) ?: "Private number" },
                        number,
                        "$type • $dateTime$durationLabel",
                        lookupUri = cursor.getString(5),
                        callType = callType
                    )
                }
            }
        } catch (error: SecurityException) {
            android.util.Log.w(TAG, "Call history permission was revoked while reading recents.", error)
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Could not read recent calls.", error)
        }
        return entries
    }

    private fun addEntry(parent: LinearLayout, entry: PhoneEntry, onCall: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = ContextCompat.getDrawable(this@HopeDialerActivity, R.drawable.bg_jarvis_action_card)
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = entry.name
            textSize = 16f
            maxLines = 2
            setTextColor(themeColor(R.attr.themeTextPrimary))
        }, matchWidth())
        labels.addView(TextView(this).apply {
            val numberLabel = entry.number.takeIf(String::isNotBlank)
                ?.let(::formatPhoneNumber)
                ?.takeIf { it != entry.name }
            text = listOf(numberLabel, entry.detail).filterNotNull().filter { it.isNotBlank() }.joinToString(" • ")
            textSize = 12f
            maxLines = 2
            setTextColor(themeColor(R.attr.themeTextMuted))
        }, matchWidth())
        labels.setOnClickListener { showEntryDetails(entry) }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Call"
            isAllCaps = false
            isEnabled = entry.number.isNotBlank()
            setOnClickListener { onCall() }
        })
        val more = Button(this).apply {
            text = if (entry.contactId != null) {
                if (entry.starred) "★" else "☆"
            } else {
                "⋮"
            }
            contentDescription = if (entry.contactId != null) {
                if (entry.starred) "Contact actions; favorite" else "Contact actions"
            } else {
                "More call actions"
            }
            isAllCaps = false
            setOnClickListener { showEntryMenu(this, entry) }
        }
        row.addView(more)
        parent.addView(row, matchWidth().apply { topMargin = dp(4); bottomMargin = dp(4) })
    }

    private fun showEntryMenu(anchor: View, entry: PhoneEntry) {
        PopupMenu(this, anchor).apply {
            menu.add("Contact details").setOnMenuItemClickListener {
                showEntryDetails(entry)
                true
            }
            if (entry.contactId != null) {
                menu.add(if (entry.starred) "Remove from favorites" else "Add to favorites")
                    .setOnMenuItemClickListener {
                        requestFavoriteChange(entry.contactId, entry.starred)
                        true
                    }
            } else if (entry.number.isNotBlank() && entry.lookupUri.isNullOrBlank()) {
                menu.add("Save number to contacts").setOnMenuItemClickListener {
                    saveNumber(entry)
                    true
                }
            }
            show()
        }
    }

    private fun showEntryDetails(entry: PhoneEntry) {
        if (entry.lookupUri.isNullOrBlank() && entry.contactId == null && entry.number.isBlank()) {
            Toast.makeText(this, "Contact details are not available for this call.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = entry.lookupUri?.let(Uri::parse) ?: if (entry.contactId != null && !entry.lookupKey.isNullOrBlank()) {
            ContactsContract.Contacts.getLookupUri(entry.contactId, entry.lookupKey)
        } else {
            Uri.fromParts("tel", entry.number, null)
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Could not open contact or number details.", error)
            Toast.makeText(this, "Contact details are not available for this number.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveNumber(entry: PhoneEntry) {
        val number = entry.number.trim()
        if (number.isBlank()) {
            Toast.makeText(this, "This call has no saveable phone number.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
                if (entry.name.isNotBlank() && entry.name != formatPhoneNumber(number) && entry.name != "Private number") {
                    putExtra(ContactsContract.Intents.Insert.NAME, entry.name)
                }
            }
            startActivity(intent)
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Could not open the contact editor for this number.", error)
            Toast.makeText(this, "No contacts app is available to save this number.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestFavoriteChange(contactId: Long, wasStarred: Boolean) {
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            pendingFavoriteContactId = contactId
            pendingFavoriteState = wasStarred
            favoritePermissionRequest.launch(Manifest.permission.WRITE_CONTACTS)
            return
        }
        setFavorite(contactId, wasStarred)
    }

    private fun setFavorite(contactId: Long, wasStarred: Boolean) {
        val values = ContentValues().apply {
            put(ContactsContract.Contacts.STARRED, if (wasStarred) 0 else 1)
        }
        try {
            val uri = android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            if (contentResolver.update(uri, values, null, null) > 0) {
                showContacts("")
            } else {
                Toast.makeText(this, "This contact's favorite status could not be changed.", Toast.LENGTH_LONG).show()
            }
        } catch (error: SecurityException) {
            android.util.Log.e(TAG, "Android denied the contact favorite update.", error)
            Toast.makeText(this, "Android denied the favorite update.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Could not update contact favorite status.", error)
            Toast.makeText(this, "Could not update this contact's favorite status.", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatPhoneNumber(number: String): String =
        PhoneNumberUtils.formatNumber(number, java.util.Locale.getDefault().country)?.takeIf(String::isNotBlank) ?: number

    private fun formatDuration(durationSeconds: Long): String {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun addListMessage(parent: LinearLayout, message: String) {
        parent.addView(TextView(this).apply {
            text = message
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(dp(12), dp(28), dp(12), dp(28))
        }, matchWidth())
    }

    private fun showPermissionMessage(message: String) {
        content.addView(TextView(this).apply {
            text = message
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(dp(12), dp(24), dp(12), dp(16))
        }, matchWidth())
    }

    private fun addActionButton(label: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, matchWidth())
    }

    private fun placeCallAfterPermission(number: String) {
        if (!PhoneRoleHelper.isDefaultPhoneApp(this)) {
            startActivity(Intent(this, PhoneSetupActivity::class.java))
        } else if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            pendingNumber = number
            callPermissionRequest.launch(Manifest.permission.CALL_PHONE)
        } else {
            placeCall(number)
        }
    }

    private fun refreshPhoneAccounts(showChooser: Boolean = false) {
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        try {
            availableAccounts = telecom.callCapablePhoneAccounts
            if (selectedAccount !in availableAccounts) selectedAccount = availableAccounts.singleOrNull()
            if (::simButton.isInitialized) simButton.text = simButtonLabel()
            if (showChooser && availableAccounts.size > 1) showSimChooser()
        } catch (error: SecurityException) {
            android.util.Log.i(TAG, "SIM phone accounts require phone-state permission.", error)
            if (showChooser) {
                phoneStatePermissionRequest.launch(Manifest.permission.READ_PHONE_STATE)
            }
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Could not list SIM phone accounts.", error)
            Toast.makeText(this, "SIM selection is unavailable; Android will choose the SIM.", Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseSim() {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            phoneStatePermissionRequest.launch(Manifest.permission.READ_PHONE_STATE)
            return
        }
        refreshPhoneAccounts(showChooser = true)
        if (availableAccounts.size <= 1) {
            Toast.makeText(this, "Only one call-capable SIM/account is available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSimChooser() {
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        val labels = availableAccounts.map { handle ->
            try {
                telecom.getPhoneAccount(handle)?.label?.toString() ?: "SIM"
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Could not read a phone account label.", error)
                "SIM"
            }
        }
        val selectedIndex = availableAccounts.indexOf(selectedAccount).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Choose SIM for calls")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, index ->
                selectedAccount = availableAccounts.getOrNull(index)
                if (::simButton.isInitialized) simButton.text = simButtonLabel()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun simButtonLabel(): String {
        val account = selectedAccount ?: return "SIM: Android default"
        return try {
            val label = (getSystemService(TELECOM_SERVICE) as TelecomManager)
                .getPhoneAccount(account)?.label?.toString()
            "SIM: ${label?.takeIf(String::isNotBlank) ?: "Selected"}"
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Could not display the selected SIM label.", error)
            "SIM: Selected"
        }
    }

    private fun handleDialIntent(intent: Intent?) {
        val incomingNumber = intent?.data
            ?.takeIf { it.scheme.equals("tel", ignoreCase = true) }
            ?.schemeSpecificPart
        if (!incomingNumber.isNullOrBlank() && ::numberInput.isInitialized) {
            numberInput.setText(Uri.decode(incomingNumber))
            showPage(Page.DIAL)
        }
    }

    private fun placeCall(number: String) {
        try {
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            val extras = Bundle()
            selectedAccount?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
        } catch (error: SecurityException) {
            android.util.Log.e(TAG, "Android denied the call request.", error)
            Toast.makeText(this, "Android denied the call request. Check phone permission and default phone-app status.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Could not place the requested call.", error)
            Toast.makeText(this, "Could not place this call: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun matchWidth() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun themeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        contactsJob?.cancel()
        recentCallsJob?.cancel()
        dialSuggestionsJob?.cancel()
        super.onDestroy()
    }

    private class SimpleTextWatcher(private val onChanged: (String) -> Unit) :
        android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onChanged(s?.toString().orEmpty())
        }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    private companion object {
        const val TAG = "HopeDialerActivity"
        const val MAX_PHONE_LIST_ITEMS = 150
    }
}
