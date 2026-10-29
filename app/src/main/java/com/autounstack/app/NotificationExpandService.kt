package com.autounstack.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat

class NotificationExpandService : AccessibilityService() {
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val processedCandidates = mutableSetOf<String>()
    private var lastGlobalClickTime = 0L
    private var activeSessionLocked: Boolean? = null
    private var activeShadeWindowId: Int? = null
    private var hasRunInitialUnlockedBatch = false
    private var isScreenInteractive = true
    private var lockScreenReadyAt = 0L
    private var isScreenStateReceiverRegistered = false

    private val scheduledRescan = Runnable {
        scanCurrentSystemUi("scheduled rescan")
    }

    // Samsung holds accessibility events for about 1 s while the shade opens. Listening for
    // SystemUI's own "shade expanded" broadcast lets the held events through as soon as the
    // shade is open (measured: about 650 ms sooner), and gives the scan a direct trigger.
    private val shadeExpandedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isScreenInteractive) {
                scanCurrentSystemUi("shade expanded broadcast")
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    resetSession("screen turned off")
                }

                Intent.ACTION_SCREEN_ON -> {
                    resetSession("screen turned on")
                    isScreenInteractive = true
                    lockScreenReadyAt =
                        SystemClock.uptimeMillis() + LOCK_SCREEN_WAKE_SETTLE_MS
                    scheduleRescan(LOCK_SCREEN_WAKE_SETTLE_MS)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferencesManager = PreferencesManager(this)
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        isScreenInteractive = powerManager.isInteractive
        registerScreenStateReceiver()
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        if (!isScreenInteractive) {
            return
        }

        val isLocked = keyguardManager.isKeyguardLocked
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            !isLocked &&
            resetIfShadeWindowClosed(event)
        ) {
            return
        }

        updateSessionMode(isLocked)

        if (!isModeEnabled(isLocked)) {
            resetSession("${modeName(isLocked)} mode disabled")
            return
        }

        val evPkg = event.packageName?.toString()
        if (evPkg != null && evPkg != SYSTEM_UI_PACKAGE) {
            // RemoteViews actions (Mark as read, Dismiss, Complete) can report the
            // notification app's package even while the shade is still open.
            Log.d(TAG, "Ignoring event from package=$evPkg")
            return
        }

        scanCurrentSystemUi("eventType=${event.eventType}")
    }

    override fun onInterrupt() {
        resetSession("service interrupted")
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        resetSession("service destroyed")
        unregisterScreenStateReceiver()
        super.onDestroy()
    }

    private fun registerScreenStateReceiver() {
        if (isScreenStateReceiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        val shadeExpandedFilter = IntentFilter(SHADE_EXPANDED_ACTION)

        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Sent by SystemUI, another app, so this receiver must be exported.
        ContextCompat.registerReceiver(
            this,
            shadeExpandedReceiver,
            shadeExpandedFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        isScreenStateReceiverRegistered = true
    }

    private fun unregisterScreenStateReceiver() {
        if (!isScreenStateReceiverRegistered) {
            return
        }

        unregisterReceiver(screenStateReceiver)
        unregisterReceiver(shadeExpandedReceiver)
        isScreenStateReceiverRegistered = false
    }

    private fun scanCurrentSystemUi(reason: String) {
        val isLocked = keyguardManager.isKeyguardLocked
        updateSessionMode(isLocked)

        if (!isModeEnabled(isLocked)) {
            resetSession("${modeName(isLocked)} mode disabled")
            return
        }

        val now = SystemClock.uptimeMillis()
        if (isLocked && now < lockScreenReadyAt) {
            scheduleRescan(lockScreenReadyAt - now)
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            if (!isTrackedShadeWindowPresent()) {
                resetSession("no active window root")
            }
            return
        }

        val rootPackage = root.packageName?.toString()
        if (rootPackage != SYSTEM_UI_PACKAGE) {
            if (!isTrackedShadeWindowPresent()) {
                resetSession("left SystemUI (root=$rootPackage)")
            } else {
                Log.d(TAG, "Skipping scan; active root is $rootPackage but shade window still present")
            }
            return
        }
        if (activeShadeWindowId == null) {
            activeShadeWindowId = root.windowId
        }

        val elapsedSinceClick = now - lastGlobalClickTime
        if (elapsedSinceClick < GLOBAL_CLICK_COOLDOWN_MS) {
            scheduleRescan(GLOBAL_CLICK_COOLDOWN_MS - elapsedSinceClick)
            return
        }

        Log.d(TAG, "Scanning ${modeName(isLocked)} SystemUI tree; reason=$reason")
        // Only bulk-expand once per shade session. Dismiss / Mark as read / Complete
        // must not clear this; resetSession() is the only place it goes false.
        if (!isLocked && !hasRunInitialUnlockedBatch) {
            hasRunInitialUnlockedBatch = processInitialUnlockedBatch(root)
            return
        }

        scanNodeRecursive(root)
    }

    private fun processInitialUnlockedBatch(root: AccessibilityNodeInfo): Boolean {
        val candidates = mutableListOf<InitialCandidate>()
        collectInitialCandidates(root, candidates)

        if (candidates.isEmpty()) {
            Log.d(TAG, "Initial unlocked scan found no grouped notifications")
            return false
        }

        val orderedCandidates = candidates.sortedByDescending { it.bottom }

        processedCandidates.addAll(orderedCandidates.flatMap { it.keys })
        var clickedAny = false

        orderedCandidates.forEach { candidate ->
            try {
                val clicked = candidate.clickableNode.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
                if (clicked) {
                    clickedAny = true
                    Log.d(TAG, "Initial unlocked batch clicked grouped notification")
                } else {
                    processedCandidates.removeAll(candidate.keys.toSet())
                    Log.d(TAG, "Initial unlocked batch click failed")
                }
            } finally {
                candidate.clickableNode.recycle()
            }
        }

        if (clickedAny) {
            lastGlobalClickTime = SystemClock.uptimeMillis()
            scheduleRescan(GLOBAL_CLICK_COOLDOWN_MS)
            Log.d(TAG, "Initial unlocked batch complete; waiting for SystemUI update")
        }

        return true
    }

    private fun collectInitialCandidates(
        node: AccessibilityNodeInfo,
        candidates: MutableList<InitialCandidate>
    ) {
        if (node.viewIdResourceName == GROUP_CHILD_COUNT_ID && node.isVisibleToUser) {
            val clickableParent = findClickableParent(node)
            if (clickableParent != null) {
                try {
                    val keys = candidateKeys(node, clickableParent)
                    val alreadyQueued = candidates.any { candidate ->
                        candidate.keys.any { it in keys }
                    }
                    if (keys.none { it in processedCandidates } && !alreadyQueued) {
                        val bounds = Rect()
                        clickableParent.getBoundsInScreen(bounds)
                        candidates.add(
                            InitialCandidate(
                                keys = keys,
                                bottom = bounds.bottom,
                                clickableNode = copyNode(clickableParent)
                            )
                        )
                    }
                } finally {
                    if (clickableParent !== node) {
                        clickableParent.recycle()
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectInitialCandidates(child, candidates)
            } finally {
                child.recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun copyNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain(node)
    }

    private fun scanNodeRecursive(node: AccessibilityNodeInfo): Boolean {
        if (processNode(node)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (scanNodeRecursive(child)) {
                    return true
                }
            } finally {
                child.recycle()
            }
        }

        return false
    }

    private fun processNode(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()
        if (node.viewIdResourceName != GROUP_CHILD_COUNT_ID) {
            return false
        }

        if (!node.isVisibleToUser) {
            Log.d(TAG, "Skipping hidden group count node: text=$text")
            return false
        }

        Log.d(TAG, "Detected grouped-notification count node: text=$text")

        val clickableParent = findClickableParent(node)
        if (clickableParent == null) {
            Log.d(TAG, "No clickable parent found for group count node: text=$text")
            return false
        }

        try {
            // Identity can change when Samsung rebuilds a row after a child is
            // dismissed. Remember both the count-node identity and the row's app
            // name so a second click does not open the next notification.
            val keys = candidateKeys(node, clickableParent)
            if (keys.any { it in processedCandidates }) {
                Log.d(TAG, "Grouped-notification candidate already handled this session")
                return false
            }

            processedCandidates.addAll(keys)
            Log.d(TAG, "Attempting click on grouped-notification container: text=$text")
            val clicked = clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                processedCandidates.removeAll(keys.toSet())
                Log.d(TAG, "Click failed for grouped-notification count: text=$text")
                return false
            }

            lastGlobalClickTime = SystemClock.uptimeMillis()
            scheduleRescan(GLOBAL_CLICK_COOLDOWN_MS)
            Log.d(TAG, "Click performed; waiting for SystemUI update before next scan")
            return true
        } finally {
            if (clickableParent !== node) {
                clickableParent.recycle()
            }
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                Log.d(TAG, "Found clickable parent: class=${current.className} clickable=true")
                return current
            }

            val parent = current.parent
            if (current !== node) {
                current.recycle()
            }
            current = parent
        }
        return null
    }

    private fun updateSessionMode(isLocked: Boolean) {
        val previousMode = activeSessionLocked
        if (previousMode == null) {
            activeSessionLocked = isLocked
        } else if (previousMode != isLocked) {
            resetSession("lock state changed to ${modeName(isLocked)}")
            activeSessionLocked = isLocked
        }
    }

    private fun resetIfShadeWindowClosed(event: AccessibilityEvent): Boolean {
        if (activeShadeWindowId == null) {
            return false
        }
        if (event.windowChanges and SHADE_WINDOW_LIFECYCLE_CHANGES == 0) {
            return false
        }

        if (isTrackedShadeWindowPresent()) {
            return false
        }

        resetSession("notification shade window closed")
        return true
    }

    private fun isTrackedShadeWindowPresent(): Boolean {
        val trackedWindowId = activeShadeWindowId ?: return false
        return windows.any { window -> window.id == trackedWindowId }
    }

    private fun resetSession(reason: String) {
        mainHandler.removeCallbacks(scheduledRescan)
        if (processedCandidates.isNotEmpty() || activeSessionLocked != null) {
            Log.d(TAG, "Resetting notification session: $reason")
        }
        processedCandidates.clear()
        lastGlobalClickTime = 0L
        activeSessionLocked = null
        activeShadeWindowId = null
        hasRunInitialUnlockedBatch = false
        lockScreenReadyAt = 0L
    }

    private fun scheduleRescan(delayMillis: Long) {
        mainHandler.removeCallbacks(scheduledRescan)
        mainHandler.postDelayed(scheduledRescan, delayMillis)
    }

    private fun isModeEnabled(isLocked: Boolean): Boolean {
        return if (isLocked) {
            preferencesManager.isLockScreenServiceEnabled()
        } else {
            preferencesManager.isServiceEnabled()
        }
    }

    private fun modeName(isLocked: Boolean): String {
        return if (isLocked) "lock-screen" else "unlocked"
    }

    private fun candidateKeys(
        countNode: AccessibilityNodeInfo,
        clickableParent: AccessibilityNodeInfo
    ): List<String> {
        val keys = mutableListOf(identityKey(countNode))
        val signature = rowSignature(clickableParent, countNode.text?.toString())
        if (!signature.isNullOrEmpty()) {
            keys.add("sig:${clickableParent.windowId}:$signature")
        }
        return keys
    }

    private fun identityKey(node: AccessibilityNodeInfo): String {
        return "id:${node.windowId}:${node.hashCode()}"
    }

    private fun rowSignature(row: AccessibilityNodeInfo, countText: String?): String? {
        findTextByViewIds(row, APP_NAME_VIEW_IDS)?.let { return it }
        return findFirstMeaningfulText(row, countText)
    }

    private fun findTextByViewIds(node: AccessibilityNodeInfo, viewIds: Set<String>): String? {
        if (node.viewIdResourceName in viewIds) {
            val text = meaningfulNodeText(node, countText = null)
            if (!text.isNullOrEmpty()) {
                return text
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                findTextByViewIds(child, viewIds)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun findFirstMeaningfulText(node: AccessibilityNodeInfo, countText: String?): String? {
        if (node.viewIdResourceName != GROUP_CHILD_COUNT_ID) {
            val text = meaningfulNodeText(node, countText)
            if (!text.isNullOrEmpty()) {
                return text
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                findFirstMeaningfulText(child, countText)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun meaningfulNodeText(node: AccessibilityNodeInfo, countText: String?): String? {
        val text = node.text?.toString()?.trim().orEmpty()
        if (isUsableSignatureText(text, countText)) {
            return text
        }
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        return description.takeIf { isUsableSignatureText(it, countText) }
    }

    private fun isUsableSignatureText(text: String, countText: String?): Boolean {
        if (text.isEmpty()) {
            return false
        }
        if (!countText.isNullOrEmpty() && text == countText.trim()) {
            return false
        }
        return !GROUP_COUNT_TEXT.matches(text)
    }

    private data class InitialCandidate(
        val keys: List<String>,
        val bottom: Int,
        val clickableNode: AccessibilityNodeInfo
    )

    companion object {
        private const val TAG = "NotificationExpandService"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val SHADE_EXPANDED_ACTION = "com.samsung.systemui.statusbar.EXPANDED"
        private const val GROUP_CHILD_COUNT_ID = "android:id/group_child_count_number"
        private val APP_NAME_VIEW_IDS = setOf(
            "com.android.systemui:id/app_name_text",
            "android:id/app_name_text",
            "com.android.systemui:id/app_name"
        )
        private val GROUP_COUNT_TEXT = Regex("""^\+?\d+$""")
        private const val GLOBAL_CLICK_COOLDOWN_MS = 450L
        private const val LOCK_SCREEN_WAKE_SETTLE_MS = 750L
        private const val SHADE_WINDOW_LIFECYCLE_CHANGES =
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
                AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
                AccessibilityEvent.WINDOWS_CHANGE_FOCUSED
    }
}
