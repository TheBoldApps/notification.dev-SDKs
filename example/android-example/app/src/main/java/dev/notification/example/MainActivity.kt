package dev.notification.example

import android.R.attr.label
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dev.notification.sdk.NotificationDev
import dev.notification.sdk.SdkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class MainActivity : ComponentActivity() {
    private lateinit var identity: TextView
    private lateinit var emailSummary: TextView
    private lateinit var permission: TextView
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var eventResult: TextView
    private lateinit var login: Button
    private lateinit var logout: Button
    private lateinit var editEmail: Button
    private lateinit var removeEmail: Button
    private lateinit var push: Switch
    private lateinit var emailConsent: Switch
    private lateinit var tags: LinearLayout
    private val actions = mutableListOf<View>()
    private var rendering = false
    private var busy = false
    private var diagnosticsExpanded = false
    private var captureDraft: (() -> Bundle)? = null
    private var activeDialog: AlertDialog? = null
    private var renderedTags: Map<String, JsonPrimitive>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val content = column().apply { setPadding(dp(20), dp(24), dp(20), dp(24)) }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)

                insets
            }
        })
        label(content, "notification.dev", 26f)
        label(content, "Android SDK sample")
        status = label(content, "")

        section(content, "Identity")
        identity = label(content, "")
        login = action(content, "Login") { showEditor("identity") }
        logout = action(content, "Logout") { submit { NotificationDev.logout() } }

        section(content, "Push")
        push = toggle(content, "Push subscription") { enabled ->
            submit { NotificationDev.setPushOptedIn(enabled) }
        }
        permission = label(content, "")
        action(content, "Request permission") {
            submit { toast(NotificationDev.requestPushPermission(this, settingFallback = true).toString()) }
        }
        action(content, "Open notification settings") {
            toast(NotificationDev.openPushSettings(this).toString())
        }

        section(content, "Email")
        emailSummary = label(content, "")
        editEmail = action(content, "Add email") { showEditor("email") }
        emailConsent = toggle(content, "Email campaign subscription") { enabled ->
            submit { NotificationDev.setEmailOptedIn(enabled) }
        }
        removeEmail = action(content, "Remove email") { submit { NotificationDev.removeEmail() } }

        section(content, "Tags")
        tags = column().also { content.addView(it) }
        action(content, "Add tag") { showEditor("tag") }

        section(content, "Events")
        label(content, "Send a named event with optional properties.")
        action(content, "Track event") { showEditor("event") }
        eventResult = label(content, savedInstanceState?.getString("eventResult") ?: "")
        eventResult.setTextIsSelectable(true)

        diagnosticsExpanded = savedInstanceState?.getBoolean("diagnostics") ?: false
        val diagnostics = column()
        action(content, "Diagnostics") {
            diagnosticsExpanded = !diagnosticsExpanded
            diagnostics.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
        }
        content.addView(diagnostics)
        diagnostics.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
        action(diagnostics, "Refresh server state") { submit { NotificationDev.refresh() } }
        output = label(diagnostics, "").apply { setTextIsSelectable(true) }

        render(NotificationDev.state.value)
        lifecycleScope.launch {
            NotificationDev.state.collect { render(it) }
        }
        lifecycleScope.launch {
            NotificationDev.pendingOpens.collect { opens ->
                opens.forEach {
                    toast("Opened ${it.notification.title}; link: ${it.notification.deepLink}")
                    NotificationDev.acknowledgeOpen(it.interactionId)
                }
            }
        }
        lifecycleScope.launch { NotificationDev.handleIntent(intent) }
        savedInstanceState?.getBundle("draft")?.let { showEditor(it.getString("kind")!!, it) }
    }

    override fun onResume() {
        super.onResume()
        NotificationDev.getPushPermissionStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("draft", captureDraft?.invoke())
        outState.putBoolean("diagnostics", diagnosticsExpanded)
        outState.putString("eventResult", eventResult.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { NotificationDev.handleIntent(intent) }
    }

    private fun render(state: SdkState) {
        rendering = true
        actions.forEach { it.isEnabled = !busy }
        identity.text = state.externalId ?: "Anonymous installation"
        login.text = if (state.externalId == null) "Login" else "Change user"
        logout.isEnabled = !busy && state.externalId != null
        push.isChecked = state.pushOptedIn
        push.isEnabled = !busy
        val email = state.user.email
        emailSummary.text = email?.let { it.address + if (it.suppressed) " • suppressed" else "" } ?: "No email registered"
        editEmail.text = if (email == null) "Add email" else "Edit email"
        emailConsent.isChecked = email?.optedIn == true
        emailConsent.isEnabled = !busy && email != null
        removeEmail.isEnabled = !busy && email != null
        permission.text = "OS notifications: ${if (state.permission.areNotificationsEnabled) "Allowed" else "Not allowed"}\nChannel: ${NotificationDev.getPushPermissionStatus().channelStatus}"
        status.text = when {
            state.pushRegistration.status == "failed" -> "Push registration failed: ${state.pushRegistration.errorCode}"
            state.lastError != null -> "${state.lastError?.code}: ${state.lastError?.message}"
            state.syncing -> "Syncing…"
            state.hasPendingChanges -> "Changes pending sync"
            else -> state.availability.toString().lowercase().replaceFirstChar { it.uppercase() }
        }
        output.text = """
            State: ${state.availability}
            Syncing: ${state.syncing}
            Installation: ${state.installationId}
            Association: ${state.associationId}
            Pending sync: ${state.hasPendingChanges}
            OS permission: ${state.permission}
            Error: ${state.lastError?.code ?: "none"}
        """.trimIndent()

        if (renderedTags != state.user.tags) {
            renderedTags = state.user.tags.toMap()
            tags.removeAllViews()

            if (state.user.tags.isEmpty()) {
                label(tags, "No tags yet")
            }
            state.user.tags.toSortedMap().forEach { (key, value) ->
                val row = column()
                tags.addView(row)
                label(row, "$key · ${valueType(value)}\n${value.content}")
                action(row, "Edit $key", register = false) {
                    showEditor("tag", Bundle().apply {
                        putString("key", key)
                        putString("value", value.content)
                        putString("type", valueType(value))
                        putBoolean("editing", true)
                    })
                }
                action(row, "Delete $key", register = false) {
                    submit { NotificationDev.removeTags(setOf(key)) }
                }
            }
        }
        enableTree(tags, !busy)
        rendering = false
    }

    private fun submit(action: suspend () -> Unit) {
        if (busy) {
            return
        }
        busy = true
        render(NotificationDev.state.value)
        lifecycleScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Operation failed")
            } finally {
                busy = false
                render(NotificationDev.state.value)
            }
        }
    }

    private fun showEditor(kind: String, draft: Bundle = Bundle()) {
        val form = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val state = NotificationDev.state.value
        val title = when (kind) {
            "identity" -> "Login / change user"
            "email" -> if (state.user.email == null) "Add email" else "Edit email"
            "tag" -> if (draft.getBoolean("editing")) "Edit tag" else "Add tag"
            else -> "Track event"
        }
        val name = when (kind) {
            "identity" -> field(form, "External user ID", draft.getString("name") ?: state.externalId.orEmpty())
            "email" -> field(form, "Email address", draft.getString("name") ?: state.user.email?.address.orEmpty()).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            "event" -> field(form, "Event name", draft.getString("name").orEmpty())
            else -> null
        }
        val consent = if (kind == "email") {
            Switch(this).apply {
                text = "Opt this email into campaigns"
                minHeight = dp(48)
                isChecked = if (draft.containsKey("consent")) draft.getBoolean("consent") else state.user.email?.optedIn == true
                form.addView(this)
            }
        } else null
        val tagRow = if (kind == "tag") ValueRow(form, draft, draft.getBoolean("editing")) else null
        val rows = mutableListOf<ValueRow>()
        val propertyContainer = column()

        if (kind == "event") {
            label(form, "Properties (optional)")
            form.addView(propertyContainer)
            fun addRow(saved: Bundle = Bundle()) {
                val row = ValueRow(propertyContainer, saved)
                rows.add(row)
                action(row.container, "Remove property", register = false) {
                    rows.remove(row)
                    propertyContainer.removeView(row.container)
                }
            }
            repeat(draft.getInt("count")) { index -> addRow(draft.getBundle("row$index")!!) }
            action(form, "Add property", register = false) { addRow() }
        }
        val error = label(form, "").apply {
            setTextColor(android.graphics.Color.rgb(170, 30, 30))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (kind == "event") "Track event" else "Save", null)
            .create()
        activeDialog = dialog
        captureDraft = {
            (tagRow?.save() ?: Bundle()).apply {
                putString("kind", kind)
                putString("name", name?.text?.toString())
                putBoolean("consent", consent?.isChecked == true)
                putBoolean("editing", draft.getBoolean("editing"))
                putInt("count", rows.size)
                rows.forEachIndexed { index, row -> putBundle("row$index", row.save()) }
            }
        }
        dialog.setOnDismissListener {
            captureDraft = null
            activeDialog = null
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (busy) {
                return@setOnClickListener
            }
            error.text = ""
            lifecycleScope.launch {
                busy = true
                render(NotificationDev.state.value)
                enableTree(form, false)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                try {
                    val text = name?.text?.toString()?.trim().orEmpty()
                    when (kind) {
                        "identity" -> {
                            require(text.isNotBlank()) { "Enter an external user ID." }
                            NotificationDev.login(text)
                        }
                        "email" -> {
                            require(android.util.Patterns.EMAIL_ADDRESS.matcher(text).matches()) { "Enter a valid email address." }
                            NotificationDev.setEmail(text, consent!!.isChecked)
                        }
                        "tag" -> {
                            val (key, value) = tagRow!!.read(tag = true)
                            require(draft.getBoolean("editing") || key !in NotificationDev.getTags()) { "This tag already exists. Edit it from the tag list." }
                            val typed: Any = when {
                                value.isString -> value.content
                                value.content == "true" || value.content == "false" -> value.content.toBoolean()
                                else -> java.math.BigDecimal(value.content)
                            }
                            NotificationDev.setTags(mapOf(key to typed))
                        }
                        "event" -> {
                            require(text.isNotBlank() && text.length <= 256) { "Event name must contain 1–256 characters." }
                            val properties = linkedMapOf<String, JsonPrimitive>()
                            rows.forEach { row ->
                                val (key, value) = row.read(tag = false)
                                require(key !in properties) { "Duplicate property key: $key" }
                                properties[key] = value
                            }
                            val json = JsonObject(properties)
                            require(json.toString().toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Event properties exceed 64 KB." }
                            val id = NotificationDev.track(text, json)
                            eventResult.text = "Queued $text\nEvent ID: $id"
                        }
                    }
                    dialog.dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error.text = e.message ?: "Operation failed. Please try again."
                } finally {
                    busy = false
                    render(NotificationDev.state.value)
                    enableTree(form, true)
                    tagRow?.key?.isEnabled = !draft.getBoolean("editing")
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    dialog.setCancelable(true)
                }
            }
        }
    }

    private inner class ValueRow(parent: LinearLayout, saved: Bundle, fixedKey: Boolean = false) {
        val container = column().also { parent.addView(it) }
        val key = field(container, "Key", saved.getString("key").orEmpty()).apply { isEnabled = !fixedKey }
        private val types = listOf("String", "Number", "Boolean")
        private val type = Spinner(this@MainActivity).apply {
            contentDescription = "Value type"
            minimumHeight = dp(48)
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, types)
            setSelection(types.indexOf(saved.getString("type") ?: "String").coerceAtLeast(0))
            container.addView(this)
        }
        private val value = field(container, "Value", saved.getString("value").orEmpty())
        private val boolean = Switch(this@MainActivity).apply {
            text = "Value"
            minHeight = dp(48)
            isChecked = saved.getString("value") == "true"
            container.addView(this)
        }

        init {
            fun updateType() {
                val selected = type.selectedItem.toString()
                value.visibility = if (selected == "Boolean") View.GONE else View.VISIBLE
                boolean.visibility = if (selected == "Boolean") View.VISIBLE else View.GONE
                value.inputType = if (selected == "Number") {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
            }
            type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateType()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            updateType()
        }

        fun save() = Bundle().apply {
            putString("key", key.text.toString())
            putString("type", type.selectedItem.toString())
            putString("value", if (type.selectedItem == "Boolean") boolean.isChecked.toString() else value.text.toString())
        }

        fun read(tag: Boolean): Pair<String, JsonPrimitive> {
            val name = key.text.toString().trim()
            require(name.isNotBlank()) { "Enter a key for every value." }
            require(!tag || name.length <= 128) { "Tag keys cannot exceed 128 characters." }
            val parsed = when (type.selectedItem.toString()) {
                "Boolean" -> JsonPrimitive(boolean.isChecked)
                "Number" -> {
                    val number = value.text.toString().trim().toBigDecimalOrNull()
                    require(number != null && number.toDouble().isFinite()) { "Enter a finite number for $name." }
                    JsonPrimitive(number)
                }
                else -> {
                    val text = value.text.toString()
                    require(!tag || text.length <= 4096) { "Tag values cannot exceed 4096 characters." }
                    JsonPrimitive(text)
                }
            }

            return name to parsed
        }
    }

    private fun valueType(value: JsonPrimitive) = when {
        value.isString -> "String"
        value.content == "true" || value.content == "false" -> "Boolean"
        else -> "Number"
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(parent: LinearLayout, value: String, size: Float = 16f) = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, dp(4), 0, dp(8))
        parent.addView(this)
    }

    private fun section(parent: LinearLayout, title: String) {
        label(parent, title, 20f).apply { setPadding(0, dp(24), 0, dp(8)) }
    }

    private fun action(parent: LinearLayout, title: String, register: Boolean = true, block: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        minHeight = dp(48)
        setOnClickListener { block() }
        parent.addView(this)

        if (register) {
            actions.add(this)
        }
    }

    private fun toggle(parent: LinearLayout, title: String, changed: (Boolean) -> Unit) = Switch(this).apply {
        text = title
        minHeight = dp(48)
        parent.addView(this)
        setOnCheckedChangeListener { _, checked ->
            if (!rendering && !busy) {
                changed(checked)
            }
        }
    }

    private fun field(parent: LinearLayout, title: String, initial: String) = EditText(this).apply {
        hint = title
        contentDescription = title
        minHeight = dp(48)
        inputType = InputType.TYPE_CLASS_TEXT
        setText(initial)
        parent.addView(this)
    }

    private fun enableTree(view: View, enabled: Boolean) {
        view.isEnabled = enabled

        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                enableTree(view.getChildAt(index), enabled)
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
