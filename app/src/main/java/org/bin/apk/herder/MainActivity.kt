package org.bin.apk.herder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bin.apk.herder.data.Models
import org.bin.apk.herder.engine.Backend
import org.bin.apk.herder.engine.BackendStatus
import org.bin.apk.herder.engine.EngineState
import org.bin.apk.herder.engine.MemoryGuard
import org.bin.apk.herder.engine.PREF_KEY_BACKEND_TAG
import org.bin.apk.herder.engine.PREF_KEY_EXTRA_ARGS
import org.bin.apk.herder.engine.PREF_KEY_LOG_TO_FILE
import org.bin.apk.herder.engine.PREF_KEY_SETTINGS_TIER
import org.bin.apk.herder.engine.ServerLog
import org.bin.apk.herder.engine.ServerService
import org.bin.apk.herder.engine.ServerStatus
import org.bin.apk.herder.ui.ChatClient
import org.bin.apk.herder.ui.ChatMessage
import org.bin.apk.herder.ui.TokenKind
import java.io.File

/**
 * Herder's whole UI, in framework Views — no Compose, no AndroidX UI.
 *
 * The v0.2.0 rewrite (the "A Byte Is a Byte" tier-3 pass): the Compose
 * build put 1.91 MB of dex under a three-tab screen; this file plus the
 * unchanged engine is the entire app. Views are built in code; state
 * arrives through [EngineState]'s hand-rolled observables; long work
 * runs on a small activity coroutine scope or plain threads.
 *
 * Structure per tab, mirroring the Compose screen it replaces:
 * a header, a tab row, and a ScrollView of cards (a card = a vertical
 * LinearLayout with the card background and 16dp padding).
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var contentContainer: LinearLayout
    private lateinit var tabButtons: List<Button>
    private var currentTab = 0

    // Engine tab views (rebuilt per show; kept for the log refresher)
    private var logView: TextView? = null
    private var logRefresh: Runnable? = null

    // Chat state (survives tab switches within the activity)
    private val chatMessages = mutableListOf<ChatMessage>()
    private var chatSending = false
    private var chatListContainer: LinearLayout? = null
    private var chatStatusView: TextView? = null
    private var chatInput: EditText? = null
    private var chatSendButton: Button? = null
    private var chatScroll: ScrollView? = null

    // Engine tab selections — ACTIVITY FIELDS, not showEngineTab
    // locals (v0.3.0 review pass): the Engine page rebuilds on every
    // backend/server status emission, progress ticks included, and as
    // locals the picks silently reset to defaults between the user's
    // tap and their next action — pick the Snapdragon chip and the
    // first download-progress tick had already reverted it to
    // Standard. As fields they live for the whole activity.
    // The variant starts at the default and must NOT be initialised
    // from Backend.installedVariant(): reinstalling whatever happens
    // to be installed once trapped the onn gate into re-downloading
    // the experimental Snapdragon tree (v0.2.3).
    private var variant: Backend.Variant = Backend.detectDefaultVariant()
    private var contextSize = ServerService.DEFAULT_CTX
    private var threads = maxOf(2, Runtime.getRuntime().availableProcessors() - 2)

    private val backendListener: (BackendStatus) -> Unit = { if (currentTab == 0) showEngineTab() }
    private val serverListener: (ServerStatus) -> Unit = {
        if (currentTab == 0) showEngineTab()
        if (currentTab == 2) refreshChatChrome()
    }

    companion object {
        private const val PREFS = "herder"
        private const val KEY_MODEL = "selected_model"
        private const val REQ_IMPORT = 1001
        private const val REQ_NOTIFICATIONS = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            // The header sits outside the cards, on the theme's window
            // background — dark grey under night mode, where the inked
            // title text was unreadable (Brian, on the Lenovo). Pin the
            // band to the cards' colour so the ink reads in both modes.
            setBackgroundColor(Color.rgb(0xF0, 0xED, 0xF5))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(ink(TextView(this).apply {
            text = "Herder"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }))
        titleRow.addView(ink(TextView(this).apply {
            // Herder's own version on the title line (v0.2.8
            // cosmetic) — anything Herder says about itself ships
            // with a visible version identifier. Since v0.3.1 it
            // sits immediately left of the hamburger menu: the
            // version is working information while releases land
            // often, so it stays glanceable rather than moving
            // into the menu.
            text = "v${getString(R.string.herder_version)}"
            textSize = 14f
        }))
        titleRow.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Menu"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            setOnClickListener { anchor -> showLevelMenu(anchor) }
        })
        header.addView(titleRow)
        header.addView(ink(TextView(this).apply {
            text = "llama.cpp, herded: a local model server"
            textSize = 14f
        }))
        root.addView(header)

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabButtons = listOf("Engine", "Models", "Chat").mapIndexed { index, title ->
            Button(this).apply {
                text = title
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(index) }
            }.also { tabRow.addView(it) }
        }
        root.addView(tabRow)

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(contentContainer)
        setContentView(root)

        EngineState.refreshBackend(this)
        EngineState.backendStatus.addListener(backendListener)
        EngineState.serverStatus.addListener(serverListener)
        selectTab(0)
    }

    override fun onDestroy() {
        EngineState.backendStatus.removeListener(backendListener)
        EngineState.serverStatus.removeListener(serverListener)
        stopLogRefresh()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- chrome

    private fun selectTab(index: Int) {
        currentTab = index
        stopLogRefresh()
        tabButtons.forEachIndexed { i, button ->
            styleSelectable(button, i == index, tabStockBg(), Color.WHITE)
        }
        when (index) {
            0 -> showEngineTab()
            1 -> showModelsTab()
            else -> showChatTab()
        }
    }

    /**
     * The header menu (v0.3.1): one item — the operational level,
     * the tier that decides how much of Herder's surface is showing.
     * It lives in the header, not in a settings card, so it is
     * reachable from every tab AND from every level: a Basic screen
     * with no visible level control would strand the person setting
     * the device up, and a user who has explored Expert knows the
     * setting exists and where it lives even when Basic hides it.
     * Quiet, not prominent: the Basic user never needs to find it.
     */
    private fun showLevelMenu(anchor: View) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val current = prefs.getString(PREF_KEY_SETTINGS_TIER, "basic") ?: "basic"
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Operational level: ${current.replaceFirstChar { it.uppercase() }}")
        popup.setOnMenuItemClickListener {
            showLevelDialog()
            true
        }
        popup.show()
    }

    /**
     * Pick a new operational level. Choosing persists the tier and
     * rebuilds the current tab so gating updates immediately;
     * setting values themselves are untouched (they persist across
     * level changes by design), and a running server is never
     * touched — the level only decides what the UI shows.
     */
    private fun showLevelDialog() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val levels = listOf("basic" to "Basic", "expert" to "Expert", "guru" to "Guru")
        val current = prefs.getString(PREF_KEY_SETTINGS_TIER, "basic") ?: "basic"
        AlertDialog.Builder(this)
            .setTitle("Operational level")
            .setSingleChoiceItems(
                levels.map { it.second }.toTypedArray(),
                levels.indexOfFirst { it.first == current },
            ) { dialog, which ->
                prefs.edit().putString(PREF_KEY_SETTINGS_TIER, levels[which].first).apply()
                dialog.dismiss()
                selectTab(currentTab)
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Force dark text. The theme (android:Theme.Material.NoActionBar)
     * follows the device's night mode: on a dark-mode device every
     * default text color goes near-white, which is invisible on the
     * always-light card background (caught on the onn at the v0.2.0
     * gate — the Compose build themed both sides, this one must pin
     * the combination it actually ships). Every TextView the UI builds
     * is ink; buttons keep the theme's own contrast.
     */
    private fun ink(view: TextView): TextView = view.apply {
        setTextColor(Color.rgb(0x1C, 0x1B, 0x1F))
    }

    private fun scrollOf(content: View): ScrollView = ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(content)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(16), dp(6), dp(16), dp(6)) }
    }

    private fun pageColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(10))
    }

    /**
     * A card background pinned to [color] — the [card] shape redrawn
     * as a GradientDrawable so a chat card can carry its source's
     * colour (v0.2.8: cards are coloured BY SOURCE — the user's own
     * message, the model's thinking, the model's reply). Same 12dp
     * corners as card_bg, no resource files, no size cost.
     */
    private fun cardBg(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }

    /**
     * The ONE selection look (v0.3.1): tabs, backend variant chips
     * and context chips all used to signal selection with bold
     * typeface alone, which washed out on the grey buttons — visible
     * on the light cards, too weak everywhere else (Brian). Selected
     * now means: slate fill (#263238), amber bold text (#FFB03A),
     * and a near-black outline (#0D1215) — colour and shape together,
     * one look learned once, identical in both card contexts.
     *
     * Unselected restores what the caller passes as stock: for the
     * persistent tab buttons, [tabStockBg] with white text — a
     * defined look matching their stock appearance; for chips (which
     * are rebuilt with every page), the background and text colour
     * captured from the button at creation, before any styling.
     */
    private fun styleSelectable(button: Button, selected: Boolean, stockBg: Drawable, stockTextColor: Int) {
        if (selected) {
            button.background = GradientDrawable().apply {
                setColor(Color.rgb(0x26, 0x32, 0x38))
                setStroke(dp(2), Color.rgb(0x0D, 0x12, 0x15))
                cornerRadius = dp(8).toFloat()
            }
            button.setTextColor(Color.rgb(0xFF, 0xB0, 0x3A))
            button.setTypeface(button.typeface, Typeface.BOLD)
        } else {
            button.background = stockBg
            button.setTextColor(stockTextColor)
            button.setTypeface(button.typeface, Typeface.NORMAL)
        }
    }

    /** The tab buttons' unselected look: medium grey, like the stock
     *  framework button they replace, with white text. A fresh
     *  drawable per call — drawables are not shared between views. */
    private fun tabStockBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(0x61, 0x61, 0x61))
        cornerRadius = dp(8).toFloat()
    }

    /** One message-kind's look: its card colour and its label. */
    private data class ChatKind(val color: Int, val label: String)

    /**
     * The three chat sources and their colours (v0.2.8) — pinned like
     * the header band, never theme-derived, so the ink reads on all
     * three in both day and night mode: slate for the user's own
     * words, a muted parchment for the model's (long, disposable)
     * thinking, and the standard card grey for its actual reply.
     */
    private fun chatKind(role: String): ChatKind = when (role) {
        "user" -> ChatKind(Color.rgb(0xD7, 0xE3, 0xF4), "You")
        "reasoning" -> ChatKind(Color.rgb(0xEF, 0xE6, 0xCF), "Thinking")
        else -> ChatKind(Color.rgb(0xF0, 0xED, 0xF5), "Herder")
    }

    private fun titleText(text: String): TextView = ink(TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(4))
    })

    private fun bodyText(text: String, size: Float = 15f): TextView = ink(TextView(this).apply {
        this.text = text
        textSize = size
    })

    // ------------------------------------------------------------- engine

    private fun showEngineTab() {
        if (currentTab != 0) return
        stopLogRefresh()
        val context = this
        val page = pageColumn()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val tier = prefs.getString(PREF_KEY_SETTINGS_TIER, "basic") ?: "basic"
        // The Expert custom build tag, when one is active (Standard only).
        val customTag = Backend.activeTag(context).takeIf { it != Backend.BUILD_TAG }

        // ---- Backend card
        val backendCard = card()
        // Title left, the backend's version right-flushed on the same
        // line (v0.2.8 cosmetic) — the version belongs on the line
        // about the backend, not buried in prose below it.
        val backendTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backendTitleRow.addView(titleText("Backend — llama.cpp").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        backendTitleRow.addView(ink(TextView(this).apply {
            text = if (customTag != null) "custom build $customTag" else "${Backend.BUILD_VERSION} · ${Backend.BUILD_TAG}"
            textSize = 13f
        }))
        backendCard.addView(backendTitleRow)
        val status = EngineState.backendStatus.value
        if (!Backend.supportsBackend()) {
            backendCard.addView(bodyText("This device is 32-bit only — the llama.cpp backend is a 64-bit program, so the engine can't run here. Herder needs a 64-bit device; downloading the backend would only waste the space."))
        }
        when (status) {
            BackendStatus.NotInstalled ->
                backendCard.addView(bodyText("Not downloaded yet. The backend is fetched from the llama.cpp project's own releases and verified against a SHA-256 pin before it is installed."))
            is BackendStatus.Working -> {
                backendCard.addView(bodyText(status.label))
                backendCard.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = if (status.total > 0) (status.downloaded * 1000 / status.total).toInt() else 0
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, dp(6), 0, dp(6)) }
                })
                backendCard.addView(ink(TextView(this).apply {
                    text = "${Models.formatBytes(status.downloaded)} of ${Models.formatBytes(status.total)}"
                    typeface = Typeface.MONOSPACE
                    textSize = 14f
                }))
            }
            is BackendStatus.Installed -> backendCard.addView(bodyText("Installed: ${status.variant.label}"))
            is BackendStatus.Failed -> backendCard.addView(bodyText("Failed: ${status.message}"))
            is BackendStatus.AwaitingConfirm -> {
                // Trust on first use, made visible: a custom build has
                // no compiled-in pin, so the hash of what arrived IS
                // the decision. Nothing is extracted until the user
                // confirms this exact hash (or discards the download).
                backendCard.addView(bodyText("Downloaded custom build ${status.tag} (${Models.formatBytes(status.sizeBytes)}) — not installed yet. Custom builds have no compiled-in SHA-256 pin; this is the hash of what arrived:"))
                backendCard.addView(ink(TextView(this).apply {
                    text = status.sha256
                    typeface = Typeface.MONOSPACE
                    textSize = 12f
                    setTextIsSelectable(true)
                }))
                backendCard.addView(bodyText("Install it only if that hash is the release you meant to fetch.", 13f))
                val confirmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                confirmRow.addView(Button(this).apply {
                    text = "Install ${status.tag}"
                    setOnClickListener {
                        scope.launch {
                            Backend.installStagedBuild(
                                context,
                                Backend.StagedBuild(status.tag, File(status.tarballPath), status.sha256, status.sizeBytes),
                            ).onSuccess {
                                prefs.edit().putString(PREF_KEY_BACKEND_TAG, status.tag).apply()
                                Backend.pruneStaleDownloads(context)
                                Models.pruneParts(context)
                                EngineState.backendStatus.value = BackendStatus.Installed(Backend.STANDARD)
                            }.onFailure { error ->
                                EngineState.backendStatus.value =
                                    BackendStatus.Failed(error.message ?: "Custom install failed")
                            }
                        }
                    }
                })
                confirmRow.addView(Button(this).apply {
                    text = "Discard"
                    setOnClickListener {
                        Backend.discardStagedBuild(
                            Backend.StagedBuild(status.tag, File(status.tarballPath), status.sha256, status.sizeBytes)
                        )
                        EngineState.refreshBackend(context)
                    }
                })
                backendCard.addView(confirmRow)
            }
        }

        if (Backend.supportsBackend()) {
            if (customTag != null) {
                // Custom builds are Standard-only, so there is no
                // variant choice to offer while one is active: the
                // Snapdragon variant always stays on the pinned build.
                backendCard.addView(bodyText("Custom build $customTag is active (Standard only). Manage it under Settings — Expert.", 13f))
            } else {
            // The install target starts at the default variant (the
            // `variant` activity field) and follows the user's chip
            // pick for the life of the activity — it must NOT follow
            // installedVariant(): reinstalling whatever happens to be
            // installed once trapped the onn gate into re-downloading
            // the experimental Snapdragon tree (v0.2.3 fix).
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val chipButtons = mutableListOf<Button>()
            val chipStock = mutableListOf<Pair<Drawable, Int>>()
            Backend.VARIANTS.forEach { candidate ->
                val chip = Button(this).apply {
                    text = candidate.label
                    isEnabled = status !is BackendStatus.Working
                    setOnClickListener {
                        variant = candidate
                        chipButtons.forEachIndexed { i, b ->
                            styleSelectable(b, Backend.VARIANTS[i] == variant, chipStock[i].first, chipStock[i].second)
                        }
                    }
                }
                // Capture the stock look before the first styling —
                // it is what "unselected" restores for this chip.
                chipStock.add(chip.background to chip.currentTextColor)
                styleSelectable(chip, candidate == variant, chipStock.last().first, chipStock.last().second)
                chipButtons.add(chip)
                chipRow.addView(chip)
            }
            backendCard.addView(chipRow)
            }

            // While a custom build awaits its hash confirmation the
            // choice is blocking: no download button, no cleanup row
            // (the sweep would reclaim the staged tarball).
            if (status !is BackendStatus.Working && status !is BackendStatus.AwaitingConfirm) {
                val effectiveVariant = if (customTag != null) Backend.STANDARD else variant
                backendCard.addView(Button(this).apply {
                    text = when {
                        customTag != null && Backend.isInstalled(context, Backend.STANDARD) ->
                            "Reinstall custom build ($customTag)"
                        customTag != null -> "Download custom build ($customTag)"
                        Backend.isInstalled(context, effectiveVariant) -> "Reinstall backend"
                        else -> "Download backend (${Models.formatBytes(effectiveVariant.sizeBytes)})"
                    }
                    setOnClickListener {
                        if (customTag != null) {
                            startCustomDownload(customTag)
                            return@setOnClickListener
                        }
                        val chosen = effectiveVariant
                        EngineState.backendStatus.value =
                            BackendStatus.Working("Downloading ${chosen.label}…", 0, chosen.sizeBytes)
                        scope.launch {
                            var lastShownBucket = -1L
                            Backend.install(context, chosen) { downloaded, total ->
                                // The bar resolves to 1000 steps; only
                                // publish when the step actually changes,
                                // so a fast download can't flood the UI
                                // thread with page rebuilds (v0.2.3 OOM).
                                val bucket = if (total > 0) downloaded * 1000 / total else 0L
                                if (bucket != lastShownBucket) {
                                    lastShownBucket = bucket
                                    EngineState.backendStatus.value =
                                        BackendStatus.Working("Downloading ${chosen.label}…", downloaded, total)
                                }
                            }.onSuccess {
                                // A fresh install makes every older
                                // backend tree unreachable — sweep them
                                // (and partial model files) automatically.
                                Backend.pruneStaleDownloads(context)
                                Models.pruneParts(context)
                                EngineState.backendStatus.value = BackendStatus.Installed(chosen)
                            }.onFailure { error ->
                                EngineState.backendStatus.value =
                                    BackendStatus.Failed(error.message ?: "Install failed")
                            }
                        }
                    }
                })

                // Old downloads: backend trees from older pins and
                // partial model files — unreachable by construction,
                // shown only when there is something to reclaim.
                val staleBytes = Backend.staleDownloadBytes(context) + Models.stalePartBytes(context)
                if (staleBytes > 0) {
                    backendCard.addView(bodyText("Old downloads are taking up ${Models.formatBytes(staleBytes)} — backend versions Herder no longer uses and partial model files.", 13f))
                    backendCard.addView(Button(this).apply {
                        text = "Clean up old downloads"
                        setOnClickListener {
                            Backend.pruneStaleDownloads(context)
                            Models.pruneParts(context)
                            EngineState.refreshBackend(context)
                            showEngineTab()
                        }
                    })
                }
            }
        }
        page.addView(backendCard)

        // ---- Server card
        val selectedModel = prefs.getString(KEY_MODEL, null)?.let { File(it) }?.takeIf { it.exists() }
        val serverCard = card()
        serverCard.addView(titleText("Server"))
        val serverStatus = EngineState.serverStatus.value
        serverCard.addView(ink(TextView(this).apply {
            text = when (serverStatus) {
                ServerStatus.Stopped -> "Stopped"
                is ServerStatus.Starting -> "Starting ${serverStatus.modelName}… (watch the log)"
                is ServerStatus.Running -> "Running: ${serverStatus.modelName}"
                is ServerStatus.Failed -> "Failed: ${serverStatus.message}"
            }
            textSize = 15f
            setTextIsSelectable(true)
        }))
        serverCard.addView(bodyText("Model: ${selectedModel?.let { Models.displayName(it.name) } ?: "none selected — pick one in the Models tab"}", 13f))

        val ctxRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        ctxRow.addView(bodyText("Context (tokens): "))
        val ctxButtons = mutableListOf<Button>()
        val ctxStock = mutableListOf<Pair<Drawable, Int>>()
        val ctxSizes = listOf(2048, 4096, 8192, 16384, 32768)
        ctxSizes.forEach { size ->
            val chip = Button(this).apply {
                text = size.toString()
                setOnClickListener {
                    contextSize = size
                    ctxButtons.forEachIndexed { i, b ->
                        styleSelectable(b, ctxSizes[i] == contextSize, ctxStock[i].first, ctxStock[i].second)
                    }
                }
            }
            ctxStock.add(chip.background to chip.currentTextColor)
            styleSelectable(chip, size == contextSize, ctxStock.last().first, ctxStock.last().second)
            ctxButtons.add(chip)
            ctxRow.addView(chip)
        }
        serverCard.addView(ctxRow)

        // One unit (v0.3.1): a plain "Threads:" label, then
        // [−] [value] [+] — the old row trailed a small prose label
        // with two full-size buttons and never read as one control
        // (Brian: "a bit silly"). The value is a field in its own
        // right: the steppers nudge it, or the user taps it and
        // types. Typed input commits on IME-done or focus loss —
        // a valid number clamps to 1..maxThreads, invalid or empty
        // input restores the current value. The `threads` activity
        // field stays the single source of truth throughout.
        // The cap is the device's core count (v0.3.2): more
        // llama-server threads than cores buys nothing and can
        // cost scheduling overhead, so 16-everywhere was wrong on
        // an 8-core tablet and would be wronger on a 4-core phone.
        val maxThreads = Runtime.getRuntime().availableProcessors()
        val threadRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        threadRow.addView(bodyText("Threads: "))
        val threadValue = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(2))
            layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextColor(Color.rgb(0x1C, 0x1B, 0x1F))
        }
        fun commitThreads() {
            val parsed = threadValue.text.toString().toIntOrNull()
            if (parsed != null) threads = parsed.coerceIn(1, maxThreads)
            threadValue.setText(threads.toString())
        }
        threadValue.setText(threads.toString())
        threadValue.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitThreads()
                true
            } else {
                false
            }
        }
        threadValue.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitThreads() }
        val threadMinus = Button(this).apply {
            text = "−"
            setOnClickListener {
                if (threads > 1) {
                    threads--
                    threadValue.setText(threads.toString())
                }
            }
        }
        val threadPlus = Button(this).apply {
            text = "+"
            setOnClickListener {
                if (threads < maxThreads) {
                    threads++
                    threadValue.setText(threads.toString())
                }
            }
        }
        // The value is typeset at the buttons' own size, so the
        // unit reads as three pieces of one control.
        threadValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, threadMinus.textSize)
        threadRow.addView(threadMinus)
        threadRow.addView(threadValue)
        threadRow.addView(threadPlus)
        serverCard.addView(threadRow)

        val guardView = bodyText("", 14f).apply { setTextColor(Color.rgb(176, 0, 32)) }
        serverCard.addView(guardView)

        // ONE toggle button (Brian, v0.2.10): Start and Stop are
        // mutually exclusive states, so they are one button whose
        // label and colour follow the server's state — green "Start
        // server" when stopped or failed, red "Stop server" while
        // starting or running. The Engine page rebuilds on every
        // state change (serverListener), so the button is always
        // built for the state it shows; the click just does whatever
        // that state calls for.
        val toggleButton = Button(this).apply {
            // Green Start / red Stop (Brian's cosmetic ask, v0.2.8):
            // background tint on the framework button, no size cost.
            setTextColor(Color.WHITE)
            when (serverStatus) {
                is ServerStatus.Running, is ServerStatus.Starting -> {
                    text = "Stop server"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0xB0, 0x00, 0x20))
                }
                else -> {
                    text = "Start server"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0x2E, 0x7D, 0x32))
                }
            }
            setOnClickListener {
                when (EngineState.serverStatus.value) {
                    is ServerStatus.Running, is ServerStatus.Starting -> {
                        context.startService(ServerService.stopIntent(context))
                        EngineState.serverStatus.value = ServerStatus.Stopped
                    }
                    else -> {
                        guardView.text = ""
                        val installed = Backend.installedVariant(context)
                        when {
                            !Backend.supportsBackend() ->
                                guardView.text = "This device is 32-bit only — the 64-bit backend can't run here."
                            installed == null ->
                                guardView.text = "Install the backend first."
                            selectedModel == null ->
                                guardView.text = "Select a model in the Models tab first."
                            else -> {
                                val estimate = MemoryGuard.estimate(context, selectedModel)
                                if (!estimate.fits) {
                                    guardView.text = String.format(
                                        "Refusing to start: %s needs about %.1f GB but this device's safe budget is %.1f GB of %.1f GB RAM. Pick a smaller model.",
                                        Models.displayName(selectedModel.name), estimate.requiredGb, estimate.budgetGb,
                                        MemoryGuard.totalRamGb(context),
                                    )
                                } else {
                                    ContextCompat.startForegroundService(
                                        context,
                                        ServerService.startIntent(context, selectedModel, installed, ServerService.DEFAULT_PORT, contextSize, threads),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        serverCard.addView(toggleButton)

        // Address rows (v0.3.1): the labels sit in a fixed-width
        // column so the two addresses still line up, as the old
        // two-line text block had them. The Web UI address is a
        // LINK — underlined, link blue, opening in the browser —
        // but only while the server is Running: a stopped server's
        // link would dead-end in the browser, so then the same text
        // renders as plain ink with no click action. The page
        // rebuilds on every status change, so the state is simply
        // read at build time. The API base stays selectable text
        // (its job is to be copied into another app), and the copy
        // button after it does that job in one tap.
        // v0.3.2: the rows are 16sp now (they were the smallest
        // text on the card at 14), the labels right-align inside
        // the column so the two colons share its right edge (at
        // 14sp, left-aligned, they visibly didn't line up), and
        // the column width is measured from the longer label at
        // the new size rather than guessed.
        val addressLabelPaint = Paint().apply {
            textSize = 16f * resources.displayMetrics.scaledDensity
        }
        val addressLabelWidth =
            (addressLabelPaint.measureText("API base:") + dp(6)).toInt()
        // v0.3.3: one space of air between the colon and the URL.
        // The gap has to live on the VALUE side: the labels are
        // right-aligned, so any slack inside the label column
        // lands to the LEFT of the label text and the colon still
        // ends up flush against the address.
        val addressValueGap = Math.round(addressLabelPaint.measureText(" "))
        val webRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        webRow.addView(ink(TextView(this).apply {
            text = "Web UI:"
            textSize = 16f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(addressLabelWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }))
        val webUrl = "http://[::1]:${ServerService.DEFAULT_PORT}/"
        if (serverStatus is ServerStatus.Running) {
            webRow.addView(TextView(this).apply {
                text = webUrl
                textSize = 16f
                setPadding(addressValueGap, 0, 0, 0)
                setTextColor(Color.rgb(0x15, 0x65, 0xC0))
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
                }
            })
        } else {
            webRow.addView(ink(TextView(this).apply {
                text = webUrl
                textSize = 16f
                setPadding(addressValueGap, 0, 0, 0)
            }))
        }
        serverCard.addView(webRow)

        val apiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        apiRow.addView(ink(TextView(this).apply {
            text = "API base:"
            textSize = 16f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(addressLabelWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }))
        val apiUrl = "http://[::1]:${ServerService.DEFAULT_PORT}/v1"
        apiRow.addView(ink(TextView(this).apply {
            text = apiUrl
            textSize = 16f
            setPadding(addressValueGap, 0, 0, 0)
            setTextIsSelectable(true)
        }))
        apiRow.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_copy)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Copy API base address"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Herder API base", apiUrl))
                Toast.makeText(context, "API base copied", Toast.LENGTH_SHORT).show()
            }
        })
        serverCard.addView(apiRow)
        serverCard.addView(bodyText("Open the Web UI in a browser to chat, or give the API base to another app.", 13f))
        // Guru: show the full effective command line when extra
        // arguments are set, so what will actually run is never a
        // surprise (the values are this card's own current picks).
        val extraArgs = prefs.getString(PREF_KEY_EXTRA_ARGS, "")?.trim().orEmpty()
        if (tier == "guru" && extraArgs.isNotEmpty()) {
            val installedNow = Backend.installedVariant(context)
            val commandText = if (installedNow != null && selectedModel != null) {
                "Command: ${Backend.serverBinary(context, installedNow).absolutePath}" +
                    " -m ${selectedModel.absolutePath}" +
                    " --host ::1 --port ${ServerService.DEFAULT_PORT} -c $contextSize -t $threads --jinja $extraArgs"
            } else {
                "Extra arguments: $extraArgs"
            }
            serverCard.addView(ink(TextView(this).apply {
                text = commandText
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                setPadding(0, dp(8), 0, 0)
            }))
        }
        page.addView(serverCard)

        // ---- Settings card: the log switch and (at Expert/Guru)
        // the sections the tiers reveal. The operational level
        // itself is set from the header menu (v0.3.1), not here.
        // Tier gating is display-only — the values themselves
        // persist regardless of which tier is showing. The log
        // switch is an Expert knob (trial, v0.3.1): file logging is
        // a diagnostic, not an operating control, and at Basic
        // Herder cannot write the file at all. At Basic nothing in
        // this card renders, so the card is not added at all.
        val settingsCard = card()
        if (tier != "basic") {
            settingsCard.addView(titleText("Settings"))
            val logSwitch = Switch(this).apply {
                text = "Save server log to a file"
                setTextColor(Color.rgb(0x1C, 0x1B, 0x1F))
                textSize = 15f
                isChecked = prefs.getBoolean(PREF_KEY_LOG_TO_FILE, false)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(PREF_KEY_LOG_TO_FILE, checked).apply()
                }
            }
            settingsCard.addView(logSwitch)
            settingsCard.addView(bodyText("Off by default, so Herder doesn't wear the device's storage writing a log nobody reads. Turn it on when diagnosing a problem — it takes effect the next time the server starts. The on-screen tail below always works either way.", 13f))
        }

        if (tier == "expert" || tier == "guru") {
            // ---- Expert: a custom llama.cpp build instead of the pin
            settingsCard.addView(titleText("Custom backend build"))
            settingsCard.addView(bodyText("Run a different llama.cpp release than the pinned ${Backend.BUILD_TAG}. Enter a release tag (e.g. b6969) and Herder downloads the Standard build for it. There is no compiled-in hash for a custom tag: after the download you confirm the SHA-256 of what arrived before anything is installed — trust on first use. The Snapdragon variant always stays on the pinned build.", 13f))
            val tagInput = EditText(this).apply {
                hint = "llama.cpp build tag"
                setText(customTag ?: "")
                setSingleLine(true)
                setTextColor(Color.rgb(0x1C, 0x1B, 0x1F))
                setHintTextColor(Color.rgb(0x5A, 0x57, 0x5E))
            }
            settingsCard.addView(tagInput)
            val tagError = bodyText("", 13f).apply { setTextColor(Color.rgb(176, 0, 32)) }
            settingsCard.addView(tagError)
            val tagRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            tagRow.addView(Button(this).apply {
                text = "Use custom build"
                setOnClickListener {
                    val tag = tagInput.text.toString().trim()
                    // The tag becomes a URL path segment and a
                    // directory name — letters, digits, dot, dash,
                    // underscore only, and no "..".
                    if (!tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) || tag.contains("..")) {
                        tagError.text = "That doesn't look like a release tag."
                    } else {
                        tagError.text = ""
                        startCustomDownload(tag)
                    }
                }
            })
            tagRow.addView(Button(this).apply {
                text = "Use pinned build"
                setOnClickListener {
                    prefs.edit().remove(PREF_KEY_BACKEND_TAG).apply()
                    EngineState.refreshBackend(context)
                    showEngineTab()
                }
            })
            settingsCard.addView(tagRow)
        }

        if (tier == "guru") {
            // ---- Guru: extra llama-server arguments
            settingsCard.addView(titleText("Extra server arguments"))
            settingsCard.addView(bodyText("Appended after Herder's own arguments when the server starts. Herder always owns the binary, model, host, port, context, threads and --jinja — these can only add flags.", 13f))
            val argsInput = EditText(this).apply {
                hint = "e.g. --flash-attn on"
                setText(prefs.getString(PREF_KEY_EXTRA_ARGS, "") ?: "")
                setSingleLine(true)
                setTextColor(Color.rgb(0x1C, 0x1B, 0x1F))
                setHintTextColor(Color.rgb(0x5A, 0x57, 0x5E))
            }
            settingsCard.addView(argsInput)
            settingsCard.addView(Button(this).apply {
                text = "Apply extra arguments"
                setOnClickListener {
                    prefs.edit().putString(PREF_KEY_EXTRA_ARGS, argsInput.text.toString().trim()).apply()
                    showEngineTab()
                }
            })
        }
        if (tier != "basic") page.addView(settingsCard)

        // ---- Log card
        val logCard = card()
        logCard.addView(titleText("Server log (tail)"))
        val log = ink(TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
        })
        logCard.addView(log)
        logView = log
        page.addView(logCard)

        contentContainer.removeAllViews()
        contentContainer.addView(scrollOf(page))

        // The tail reads the in-process ring buffer, never the file
        // (v0.3.0 log redesign): constant cost at any log size — the
        // old version read the whole append-only server.log into a
        // list every 2 s to keep 30 lines, a read that grew with
        // every hour the server had ever run.
        val refresh = object : Runnable {
            override fun run() {
                log.text = ServerLog.tail(30).joinToString("\n").ifEmpty { "(no log yet)" }
                mainHandler.postDelayed(this, 2000)
            }
        }
        logRefresh = refresh
        refresh.run()
    }

    /**
     * Phase one of an Expert custom build: download + hash, then park
     * the result in [BackendStatus.AwaitingConfirm] — the backend card
     * renders the hash with its Install/Discard buttons, and nothing
     * is extracted (or activated) until the user confirms it.
     */
    private fun startCustomDownload(tag: String) {
        val context = this
        EngineState.backendStatus.value =
            BackendStatus.Working("Downloading custom build $tag…", 0, 0)
        scope.launch {
            var lastShownBucket = -1L
            Backend.downloadCustomBuild(context, tag) { downloaded, total ->
                // Same anti-flood bucketing as the pinned download
                // (v0.2.3 OOM): publish only when the displayed step
                // changes — by bar position when a total is known, by
                // whole megabytes when it isn't.
                val bucket = if (total > 0) downloaded * 1000 / total else downloaded / 1_000_000
                if (bucket != lastShownBucket) {
                    lastShownBucket = bucket
                    EngineState.backendStatus.value =
                        BackendStatus.Working("Downloading custom build $tag…", downloaded, total)
                }
            }.onSuccess { staged ->
                EngineState.backendStatus.value = BackendStatus.AwaitingConfirm(
                    staged.tag, staged.sha256, staged.tarball.absolutePath, staged.sizeBytes,
                )
            }.onFailure { error ->
                EngineState.backendStatus.value =
                    BackendStatus.Failed(error.message ?: "Custom download failed")
            }
        }
    }

    private fun stopLogRefresh() {
        logRefresh?.let { mainHandler.removeCallbacks(it) }
        logRefresh = null
        logView = null
    }

    // ------------------------------------------------------------- models

    private fun showModelsTab() {
        if (currentTab != 1) return
        val context = this
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var selectedPath = prefs.getString(KEY_MODEL, null)

        fun select(file: File) {
            selectedPath = file.absolutePath
            prefs.edit().putString(KEY_MODEL, file.absolutePath).apply()
            showModelsTab()
        }

        val page = pageColumn()
        val heading = bodyText("Download a model", 17f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        page.addView(heading)

        Models.PRESETS.forEach { preset ->
            val modelCard = card()
            modelCard.addView(ink(TextView(this).apply {
                text = preset.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }))
            modelCard.addView(bodyText("${preset.note} · ${Models.formatBytes(preset.sizeBytes)}", 13f))
            val downloaded = Models.isDownloaded(context, preset)
            if (downloaded) {
                modelCard.addView(Button(this).apply {
                    text = if (selectedPath == Models.presetFile(context, preset).absolutePath) "Selected" else "Select"
                    setOnClickListener { select(Models.presetFile(context, preset)) }
                })
            } else {
                val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    visibility = View.GONE
                    max = 1000
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, dp(6), 0, dp(6)) }
                }
                val progressText = ink(TextView(this).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 14f
                    visibility = View.GONE
                })
                modelCard.addView(progressBar)
                modelCard.addView(progressText)
                modelCard.addView(Button(this).apply {
                    text = "Download"
                    setOnClickListener {
                        isEnabled = false
                        progressBar.visibility = View.VISIBLE
                        progressText.visibility = View.VISIBLE
                        scope.launch {
                            Models.download(context, preset) { done, total ->
                                // Models.download invokes this from
                                // Dispatchers.IO; view writes belong on
                                // the main thread (v0.3.0 review pass —
                                // the backend path never had the bug:
                                // its callback only sets Observable
                                // values, which are any-thread).
                                mainHandler.post {
                                    progressBar.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                                    progressText.text = "${Models.formatBytes(done)} of ${Models.formatBytes(total)}"
                                }
                            }.onSuccess { file ->
                                if (selectedPath == null) select(file) else showModelsTab()
                            }.onFailure { error ->
                                progressBar.visibility = View.GONE
                                progressText.visibility = View.VISIBLE
                                progressText.text = error.message ?: "Download failed"
                                isEnabled = true
                            }
                        }
                    }
                })
            }
            page.addView(modelCard)
        }

        val onDevice = bodyText("On this device", 17f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        page.addView(onDevice)
        val importRow = LinearLayout(this).apply { setPadding(dp(16), 0, dp(16), 0) }
        importRow.addView(Button(this).apply {
            text = "Import a GGUF file…"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, REQ_IMPORT)
            }
        })
        page.addView(importRow)

        val local = Models.localModels(context)
        if (local.isEmpty()) {
            page.addView(bodyText("No models yet.", 13f).apply { setPadding(dp(16), dp(4), dp(16), 0) })
        }
        local.forEach { file ->
            val fileCard = card()
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            labels.addView(ink(TextView(this).apply {
                text = Models.displayName(file.name)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }))
            labels.addView(bodyText(Models.formatBytes(file.length()), 13f))
            row.addView(labels)
            if (selectedPath == file.absolutePath) {
                row.addView(bodyText("Selected", 14f).apply { setPadding(0, dp(8), dp(8), 0) })
            } else {
                row.addView(Button(this).apply {
                    text = "Select"
                    setOnClickListener { select(file) }
                })
            }
            row.addView(Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    file.delete()
                    if (selectedPath == file.absolutePath) {
                        prefs.edit().remove(KEY_MODEL).apply()
                    }
                    showModelsTab()
                }
            })
            fileCard.addView(row)
            page.addView(fileCard)
        }

        contentContainer.removeAllViews()
        contentContainer.addView(scrollOf(page))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            scope.launch {
                var displayName: String? = null
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) displayName = cursor.getString(0)
                }
                Models.importFromUri(this@MainActivity, uri, displayName).onSuccess { file ->
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_MODEL, file.absolutePath).apply()
                    if (currentTab == 1) showModelsTab()
                }
            }
        }
    }

    // ------------------------------------------------------------- chat

    private fun showChatTab() {
        if (currentTab != 2) return
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        chatStatusView = ink(TextView(this).apply { textSize = 15f })
        page.addView(chatStatusView)

        chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        chatListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll!!.addView(chatListContainer)
        page.addView(chatScroll)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chatInput = EditText(this).apply {
            hint = "Message"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(chatInput)
        chatSendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { sendChat() }
        }
        inputRow.addView(chatSendButton)
        page.addView(inputRow)

        contentContainer.removeAllViews()
        contentContainer.addView(page)
        rebuildChatList()
        refreshChatChrome()
    }

    private fun rebuildChatList() {
        val list = chatListContainer ?: return
        list.removeAllViews()
        chatMessages.forEach { message -> list.addView(chatCard(message)) }
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun chatCard(message: ChatMessage): View {
        val kind = chatKind(message.role)
        val messageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Coloured BY SOURCE (v0.2.8): the user's card, the
            // model's thinking card and its reply card each carry
            // their own pinned background.
            background = cardBg(kind.color)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(4), 0, dp(4)) }
        }
        messageCard.addView(ink(TextView(this).apply {
            text = kind.label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }))
        messageCard.addView(ink(TextView(this).apply {
            text = message.content.ifEmpty { "…" }
            textSize = 15f
            tag = "bubble"
        }))
        return messageCard
    }

    private fun refreshChatChrome() {
        val status = EngineState.serverStatus.value
        val running = status as? ServerStatus.Running
        chatStatusView?.let { view ->
            if (running != null) {
                view.visibility = View.GONE
            } else {
                view.visibility = View.VISIBLE
                view.text = when (status) {
                    is ServerStatus.Starting ->
                        "The server is starting — ${status.modelName} is loading. Chat unlocks the moment it's ready; this message updates by itself."
                    is ServerStatus.Failed ->
                        "The server failed to start: ${status.message} — the Engine tab shows the server log."
                    else ->
                        "The server is not running. Start it in the Engine tab, then chat here — or in a browser at the Web UI address, http://[::1]:${ServerService.DEFAULT_PORT}/."
                }
            }
        }
        val enabled = running != null && !chatSending
        chatInput?.isEnabled = enabled
        chatSendButton?.isEnabled = enabled
    }

    private fun sendChat() {
        val input = chatInput ?: return
        val running = EngineState.serverStatus.value as? ServerStatus.Running ?: return
        val userText = input.text.toString().trim()
        if (userText.isEmpty() || chatSending) return
        input.text.clear()
        chatMessages.add(ChatMessage("user", userText))
        // The reply arrives as TWO sources (v0.2.8): the model's
        // thinking, then its answer. Each gets its own card, created
        // lazily on its first piece — so a reply with no thinking
        // shows no empty Thinking card, and nothing shows before the
        // first piece lands. An exchange therefore adds two or three
        // list entries beyond the user's own.
        var reasoningIndex = -1
        var contentIndex = -1
        fun indexFor(kind: TokenKind): Int {
            val role = if (kind == TokenKind.REASONING) "reasoning" else "assistant"
            val existing = if (kind == TokenKind.REASONING) reasoningIndex else contentIndex
            if (existing >= 0) return existing
            chatMessages.add(ChatMessage(role, ""))
            val created = chatMessages.lastIndex
            if (kind == TokenKind.REASONING) reasoningIndex = created else contentIndex = created
            // Rebuild so the new card exists; the caller re-fetches
            // its bubble by index afterwards.
            if (currentTab == 2) rebuildChatList()
            return created
        }
        fun bubbleAt(index: Int): TextView? {
            val list = chatListContainer ?: return null
            if (index < 0 || index >= list.childCount) return null
            return list.getChildAt(index).findViewWithTag("bubble")
        }
        // Messages are tracked by INDEX, never by value: ChatMessage
        // is a data class, so indexOf(capturedInstance) compares by
        // content — once the first token lands, the list entry no
        // longer equals the captured empty instance, and the v0.2.6
        // handler computed every frame from that empty original, so
        // each new word replaced the last instead of appending.
        chatSending = true
        rebuildChatList()
        refreshChatChrome()

        // The history sent to the server is everything so far —
        // INCLUDING the user message just added above: the server
        // 500s on an empty message list ("No messages provided"),
        // so filtering this turn's message out would break the very
        // first chat on every install. Only THINKING stays out, from
        // this turn and past turns alike — thinking is display, not
        // conversation. (First slate gate of v0.2.8 caught the
        // dropLast(1) version of this line: in v0.2.7 the placeholder
        // entry it dropped was the empty assistant message; the
        // v0.2.8 lazy-card rewrite removed that placeholder, so the
        // same drop silently ate the user's message instead.)
        //
        // Bounded (v0.3.0 review pass): the history used to be resent
        // in full, forever. The server's context window is finite, so
        // past some length every send is rejected — and since the
        // history only grows, every LATER send fails the same way and
        // the session is poisoned until the activity dies. Keep the
        // newest 40 non-reasoning messages, dropping the oldest
        // user+assistant pairs (two at a time, so pairs stay intact);
        // the message just sent is the last one, so it is never
        // dropped.
        var history = chatMessages.filter { it.role != "reasoning" }
        while (history.size > 40) history = history.drop(2)
        ChatClient.chatAsync(running.port, history,
            onToken = { kind, token ->
                val index = indexFor(kind)
                val current = chatMessages.getOrNull(index) ?: return@chatAsync
                val updated = current.copy(content = current.content + token)
                chatMessages[index] = updated
                bubbleAt(index)?.text = updated.content.ifEmpty { "…" }
                chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
            },
            onDone = { result ->
                result.onFailure { error ->
                    // The error rides on the reply card if one exists;
                    // if the stream died before any piece arrived, give
                    // it a reply card of its own.
                    val index = if (contentIndex >= 0) contentIndex
                        else indexFor(TokenKind.CONTENT)
                    val current = chatMessages.getOrNull(index) ?: return@chatAsync
                    val updated = current.copy(
                        content = current.content +
                            (if (current.content.isEmpty()) "" else "\n") +
                            "[Error: ${error.message}]",
                    )
                    chatMessages[index] = updated
                    bubbleAt(index)?.text = updated.content
                }
                chatSending = false
                if (currentTab == 2) rebuildChatList()
                refreshChatChrome()
            },
        )
    }

    // ------------------------------------------------------------- misc

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }
}
