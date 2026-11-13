package com.hybrid.whatsappbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hybrid.whatsappbot.automation.ActiveAutomationSession
import com.hybrid.whatsappbot.automation.AutomationStateManager
import com.hybrid.whatsappbot.automation.UiBotSubState
import com.hybrid.whatsappbot.data.AppDatabase
import com.hybrid.whatsappbot.data.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class WhatsAppAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var pollingJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "WhatsAppAccessibilityService connected. Starting session watcher.")

        // Automatically start the 500ms ticker whenever AutomationStateManager activates a task
        serviceScope.launch {
            AutomationStateManager.sessionFlow.collectLatest { session ->
                if (session != null && session.subState != UiBotSubState.STATE_5_DONE) {
                    ensurePollingLoopActive()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AutomationStateManager.isTaskActive()) return

        val pkg = event?.packageName?.toString() ?: ""
        if (pkg.startsWith("com.whatsapp")) {
            ensurePollingLoopActive()
        }
    }

    /**
     * Active 500ms Coroutine Polling Loop:
     * Actively inspects the WhatsApp UI hierarchy and advances through STATE_1 -> STATE_5
     * even when WhatsApp stops firing AccessibilityEvents after typing finishes.
     */
    private fun ensurePollingLoopActive() {
        val currentJob = pollingJob
        if (currentJob != null && currentJob.isActive) return

        pollingJob = serviceScope.launch {
            Log.i(TAG, "Starting 500ms UI automation ticker loop.")
            while (AutomationStateManager.isTaskActive()) {
                try {
                    val rootNode = getBestWhatsAppRootNode()
                    if (rootNode != null) {
                        inspectAndAdvance(rootNode)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception inside 500ms automation ticker", e)
                }
                delay(500L)
            }
            Log.i(TAG, "500ms UI automation ticker loop finished.")
        }
    }

    /**
     * Retrieves the active WhatsApp window root node, even if the soft keyboard (IME)
     * window is currently focused on screen.
     */
    private fun getBestWhatsAppRootNode(): AccessibilityNodeInfo? {
        try {
            val interactiveWindows = windows
            if (!interactiveWindows.isNullOrEmpty()) {
                for (win in interactiveWindows) {
                    val root = win.root ?: continue
                    val pkg = root.packageName?.toString() ?: ""
                    if (pkg.startsWith("com.whatsapp")) {
                        return root
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to rootInActiveWindow below
        }

        val activeRoot = rootInActiveWindow ?: return null
        val pkg = activeRoot.packageName?.toString() ?: ""
        return if (pkg.startsWith("com.whatsapp")) activeRoot else null
    }

    /**
     * Core 5-State Machine Dispatcher executed every 500ms while a task is active.
     */
    private suspend fun inspectAndAdvance(rootNode: AccessibilityNodeInfo) {
        val session = AutomationStateManager.getValidSession() ?: return

        when (session.subState) {
            UiBotSubState.IDLE,
            UiBotSubState.STATE_5_DONE -> return

            UiBotSubState.STATE_1_CLICK_SEARCH -> {
                executeState1ClickSearch(rootNode, session)
            }

            UiBotSubState.STATE_2_TYPE_QUERY -> {
                executeState2TypeQuery(rootNode, session)
            }

            UiBotSubState.STATE_3_SELECT_GROUP -> {
                executeState3SelectGroup(rootNode, session)
            }

            UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND -> {
                executeState4PickerOrPreviewSend(rootNode, session)
            }
        }
    }

    /**
     * STATE 1: Locate the search button on the "Send to..." picker screen
     * (or detect if the search EditText is already open) and transition to STATE_2_TYPE_QUERY.
     */
    private suspend fun executeState1ClickSearch(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        // If we are already on the Media Preview screen, jump straight to State 4
        if (isMediaPreviewScreen(rootNode)) {
            AutomationStateManager.transitionToState(
                session.taskId,
                UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND
            )
            return
        }

        // If the search EditText is already open and visible, advance immediately to State 2
        val existingSearchInput = findSearchEditTextNode(rootNode)
        if (existingSearchInput != null) {
            Log.i(TAG, "STATE_1: Search EditText already visible -> advancing to STATE_2_TYPE_QUERY")
            AutomationStateManager.transitionToState(
                session.taskId,
                UiBotSubState.STATE_2_TYPE_QUERY
            )
            executeState2TypeQuery(rootNode, AutomationStateManager.getValidSession() ?: return)
            return
        }

        val searchButton = findResilientNode(
            root = rootNode,
            viewIds = listOf(
                "com.whatsapp:id/menuitem_search",
                "com.whatsapp:id/search_bar_inner_layout",
                "com.whatsapp:id/search_holder",
                "com.whatsapp:id/search_btn",
                "com.whatsapp.w4b:id/menuitem_search",
                "com.whatsapp.w4b:id/search_bar_inner_layout"
            ),
            contentDescKeywords = listOf("Search", "search")
        )

        if (searchButton != null) {
            val attempt = AutomationStateManager.incrementAttempt(session.taskId)
            val clicked = clickNodeOrParentOrGesture(
                node = searchButton,
                forceGestureFallback = attempt >= 2
            )
            Log.i(TAG, "STATE_1: Clicked search button (attempt=$attempt, clicked=$clicked)")
            AutomationStateManager.transitionToState(
                session.taskId,
                UiBotSubState.STATE_2_TYPE_QUERY
            )
        }
    }

    /**
     * STATE 2: Locate the focused/visible search EditText, inject targetGroupName via ACTION_SET_TEXT,
     * wait 800ms for WhatsApp's RecyclerView filter to settle, and transition to STATE_3_SELECT_GROUP.
     */
    private suspend fun executeState2TypeQuery(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        val searchEditText = findSearchEditTextNode(rootNode)
        if (searchEditText == null) {
            val attempt = AutomationStateManager.incrementAttempt(session.taskId)
            if (attempt >= 2) {
                Log.w(TAG, "STATE_2: Search EditText not open yet, returning to STATE_1_CLICK_SEARCH")
                AutomationStateManager.transitionToState(
                    session.taskId,
                    UiBotSubState.STATE_1_CLICK_SEARCH
                )
            }
            return
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                session.targetGroupName
            )
        }
        searchEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        searchEditText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val setOk = searchEditText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        Log.i(TAG, "STATE_2: Injected '${session.targetGroupName}' into search EditText (setOk=$setOk). Waiting 800ms...")
        delay(800L)

        AutomationStateManager.transitionToState(
            session.taskId,
            UiBotSubState.STATE_3_SELECT_GROUP
        )
    }

    /**
     * STATE 3: Find the group title node in the filtered results (strictly excluding EditText /
     * search_src_text / editable nodes). Click the group row using clickNodeOrParentOrGesture(),
     * wait 700ms, and transition to STATE_4_PICKER_OR_PREVIEW_SEND.
     */
    private suspend fun executeState3SelectGroup(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        val groupRowNode = findGroupRowNodeExcludingSearchEditText(
            root = rootNode,
            targetGroupName = session.targetGroupName
        )

        if (groupRowNode == null) {
            val attempt = AutomationStateManager.incrementAttempt(session.taskId)
            Log.d(TAG, "STATE_3: Group row '${session.targetGroupName}' not found yet (attempt=$attempt)")
            // If the text was somehow cleared from the search bar, re-inject it
            if (attempt == 4) {
                AutomationStateManager.transitionToState(
                    session.taskId,
                    UiBotSubState.STATE_2_TYPE_QUERY
                )
            }
            return
        }

        val attempt = AutomationStateManager.incrementAttempt(session.taskId)
        val clicked = clickNodeOrParentOrGesture(
            node = groupRowNode,
            forceGestureFallback = attempt >= 2
        )
        Log.i(
            TAG,
            "STATE_3: Clicked group result '${session.targetGroupName}' " +
                "(attempt=$attempt, clicked=$clicked). Waiting 700ms..."
        )
        delay(700L)

        AutomationStateManager.transitionToState(
            session.taskId,
            UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND
        )
    }

    /**
     * STATE 4: Two-Stage Send Handler
     * - Stage 4A (Multi-select Picker FAB): If still on the Contact/Group Picker screen,
     *   clicks the floating Send/Next FAB to advance to the Media Preview screen (or re-taps
     *   the group row via hardware gesture if the checkmark didn't register).
     * - Stage 4B (Media Preview & Caption Screen): Verifies/populates the caption EditText,
     *   clicks the final Send button, marks task COMPLETED_UI, and transitions to STATE_5_DONE.
     */
    private suspend fun executeState4PickerOrPreviewSend(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        val onPreviewScreen = isMediaPreviewScreen(rootNode)
        val onPickerScreen = isContactPickerScreen(rootNode) && !onPreviewScreen

        if (onPickerScreen) {
            // =================================================================
            // STAGE 4A: Still on Contact/Group Picker Screen -> Click FAB
            // =================================================================
            val pickerFab = findSendOrNextButton(rootNode)
            if (pickerFab != null) {
                val attempt = AutomationStateManager.incrementAttempt(
                    taskId = session.taskId,
                    markPickerFabClicked = true
                )
                val clicked = clickNodeOrParentOrGesture(
                    node = pickerFab,
                    forceGestureFallback = session.pickerFabClicked || attempt >= 2
                )
                Log.i(
                    TAG,
                    "STATE_4 (Stage 4A - Picker FAB): Clicked floating Next/Send FAB " +
                        "(attempt=$attempt, clicked=$clicked). Waiting 750ms for Media Preview..."
                )
                delay(750L)
                return
            }

            // If we are still on the picker screen AND no floating FAB is visible yet,
            // the previous group row click in STATE_3 didn't select the group.
            // Immediately tap the group row using coordinate gesture fallback!
            val groupNode = findGroupRowNodeExcludingSearchEditText(
                root = rootNode,
                targetGroupName = session.targetGroupName
            )
            if (groupNode != null) {
                val attempt = AutomationStateManager.incrementAttempt(session.taskId)
                val tapped = clickNodeOrParentOrGesture(
                    node = groupNode,
                    forceGestureFallback = true
                )
                Log.i(
                    TAG,
                    "STATE_4 (Stage 4A Retry): Picker FAB not visible yet; gesture-tapped " +
                        "group row '${session.targetGroupName}' (attempt=$attempt, tapped=$tapped)"
                )
                delay(700L)
            }
            return
        }

        // =====================================================================
        // STAGE 4B: Media Preview & Caption Screen -> Fill Caption & Final Send
        // =====================================================================
        val finalSendButton = findSendOrNextButton(rootNode)
        if (finalSendButton == null) {
            Log.d(TAG, "STATE_4 (Stage 4B): Waiting for Media Preview Send button to appear...")
            return
        }

        // 1. Check if the caption field on Media Preview is empty; populate if needed
        if (session.caption.isNotBlank()) {
            val captionEditNode = findCaptionEditTextOnPreview(rootNode)
            if (captionEditNode != null) {
                val currentText = captionEditNode.text?.toString()?.trim() ?: ""
                val isPlaceholderOrEmpty = currentText.isEmpty() ||
                    currentText.equals("Add a caption...", ignoreCase = true) ||
                    currentText.equals("Add a caption", ignoreCase = true)

                if (isPlaceholderOrEmpty) {
                    val captionArgs = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            session.caption
                        )
                    }
                    captionEditNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    captionEditNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, captionArgs)
                    Log.i(TAG, "STATE_4 (Stage 4B): Populated caption field on Media Preview screen.")
                    delay(350L)
                }
            }
        }

        // 2. Immediately lock to STATE_5_DONE before clicking final Send so it never double-sends
        if (!AutomationStateManager.tryLockForFinalSendClick(session.taskId)) {
            return
        }

        val sendClicked = clickNodeOrParentOrGesture(
            node = finalSendButton,
            forceGestureFallback = false
        )
        Log.i(
            TAG,
            "STATE_4 -> STATE_5_DONE: Clicked final Media Preview Send button " +
                "for taskId=${session.taskId} (sendClicked=$sendClicked)"
        )

        // 3. Persist COMPLETED_UI in Room DB, clear AutomationStateManager, and return Home
        markTaskCompletedInDatabase(session)
    }

    private fun markTaskCompletedInDatabase(session: ActiveAutomationSession) {
        AutomationStateManager.clearSession()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getInstance(applicationContext).taskDao()
                dao.updateTaskStatus(
                    taskId = session.taskId,
                    newStatus = TaskStatus.COMPLETED_UI,
                    logMessage = "Sent via Engine 1 (Foreground UI Bot) to \"${session.targetGroupName}\".",
                    completedAt = System.currentTimeMillis()
                )
                delay(1_200L)
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                Log.e(TAG, "Failed updating Room DB after STATE_5_DONE", e)
            }
        }
    }

    // =========================================================================
    // ROOT CAUSE FIX #1: Strictly Exclude Search EditText When Finding Group Row
    // =========================================================================

    private fun isSearchOrEditableNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (className.contains("EditText", ignoreCase = true)) return true
        if (viewId.contains("search_src_text", ignoreCase = true)) return true
        if (viewId.contains("search_input", ignoreCase = true)) return true
        if (viewId.contains("search_bar", ignoreCase = true)) return true
        if (node.isEditable) return true

        // Also verify no parent up to 3 levels is an EditText or search input bar
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            val pClass = parent.className?.toString() ?: ""
            val pId = parent.viewIdResourceName ?: ""
            if (pClass.contains("EditText", ignoreCase = true) ||
                pId.contains("search_src_text", ignoreCase = true) ||
                pId.contains("search_input", ignoreCase = true) ||
                parent.isEditable
            ) {
                return true
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    private fun findGroupRowNodeExcludingSearchEditText(
        root: AccessibilityNodeInfo,
        targetGroupName: String
    ): AccessibilityNodeInfo? {
        val normalizedTarget = targetGroupName.trim()
        if (normalizedTarget.isEmpty()) return null

        // Pass 1: Check explicit WhatsApp Contact Picker / Conversation row title IDs
        val rowTitleIds = listOf(
            "com.whatsapp:id/contactpicker_row_name",
            "com.whatsapp:id/conversations_row_contact_name",
            "com.whatsapp.w4b:id/contactpicker_row_name",
            "com.whatsapp.w4b:id/conversations_row_contact_name"
        )
        for (id in rowTitleIds) {
            val idNodes = root.findAccessibilityNodeInfosByViewId(id)
                ?.filter { it.isVisibleToUser && !isSearchOrEditableNode(it) }
                ?: continue

            // Exact trimmed case-insensitive match
            idNodes.firstOrNull { node ->
                node.text?.toString()?.trim().equals(normalizedTarget, ignoreCase = true)
            }?.let { return it }

            // Substring fallback match
            idNodes.firstOrNull { node ->
                node.text?.toString()?.trim()?.contains(normalizedTarget, ignoreCase = true) == true
            }?.let { return it }
        }

        // Pass 2: Use findAccessibilityNodeInfosByText with strict EditText exclusion
        val textMatches = root.findAccessibilityNodeInfosByText(normalizedTarget)
            ?.filter { it.isVisibleToUser && !isSearchOrEditableNode(it) }
            .orEmpty()

        textMatches.firstOrNull { node ->
            node.text?.toString()?.trim().equals(normalizedTarget, ignoreCase = true)
        }?.let { return it }

        textMatches.firstOrNull { node ->
            node.text?.toString()?.trim()?.contains(normalizedTarget, ignoreCase = true) == true
        }?.let { return it }

        // Pass 3: Full BFS tree scan excluding EditText / search_src_text / editable nodes
        val allCandidates = collectAllNodes(root)
            .filter { it.isVisibleToUser && !isSearchOrEditableNode(it) }

        allCandidates.firstOrNull { node ->
            node.text?.toString()?.trim().equals(normalizedTarget, ignoreCase = true)
        }?.let { return it }

        return allCandidates.firstOrNull { node ->
            node.text?.toString()?.trim()?.contains(normalizedTarget, ignoreCase = true) == true
        }
    }

    // =========================================================================
    // ROOT CAUSE FIX #2: Clickable Parent Traversal + Coordinate Gesture Fallback
    // =========================================================================

    /**
     * 1. Traverses up to 6 parent levels looking for the first ancestor where
     *    current.isClickable == true && current.isEnabled == true and calls ACTION_CLICK.
     * 2. If parent ACTION_CLICK returns false (or forceGestureFallback is true on retry ticks),
     *    reads node.getBoundsInScreen(rect) and dispatches a 60ms hardware tap gesture at
     *    (rect.centerX(), rect.centerY()).
     */
    private fun clickNodeOrParentOrGesture(
        node: AccessibilityNodeInfo,
        forceGestureFallback: Boolean = false
    ): Boolean {
        if (!forceGestureFallback) {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth <= 6) {
                if (current.isClickable && current.isEnabled) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        return true
                    }
                }
                current = current.parent
                depth++
            }
        }

        // Coordinate Gesture Fallback via dispatchGesture (60ms StrokeDescription)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.centerX() <= 0 || rect.centerY() <= 0) {
            // Try parent bounds if child rect is empty
            node.parent?.getBoundsInScreen(rect)
        }

        val tapX = rect.centerX().toFloat()
        val tapY = rect.centerY().toFloat()
        if (tapX > 0f && tapY > 0f) {
            val tapPath = Path().apply {
                moveTo(tapX, tapY)
            }
            val stroke = GestureDescription.StrokeDescription(tapPath, 0L, 60L)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            val dispatched = dispatchGesture(gesture, null, null)
            Log.i(TAG, "Dispatched 60ms coordinate tap gesture at ($tapX, $tapY) -> dispatched=$dispatched")
            if (dispatched) return true
        }

        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // =========================================================================
    // SCREEN & NODE DETECTION HELPERS
    // =========================================================================

    private fun findSearchEditTextNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findResilientNode(
            root = root,
            viewIds = listOf(
                "com.whatsapp:id/search_src_text",
                "com.whatsapp:id/search_input",
                "com.whatsapp:id/search_chat_input",
                "com.whatsapp.w4b:id/search_src_text",
                "com.whatsapp.w4b:id/search_input"
            ),
            classNameFilter = "android.widget.EditText"
        )
    }

    private fun findCaptionEditTextOnPreview(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val explicitCaptionIds = listOf(
            "com.whatsapp:id/caption",
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/caption",
            "com.whatsapp.w4b:id/entry"
        )
        for (id in explicitCaptionIds) {
            val match = root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isVisibleToUser }
            if (match != null) return match
        }

        return collectAllNodes(root).firstOrNull { node ->
            node.isVisibleToUser &&
                !node.viewIdResourceName.orEmpty().contains("search", ignoreCase = true) &&
                (node.className?.toString()?.contains("EditText", ignoreCase = true) == true || node.isEditable)
        }
    }

    private fun isContactPickerScreen(root: AccessibilityNodeInfo): Boolean {
        val pickerIds = listOf(
            "com.whatsapp:id/contactpicker_row_name",
            "com.whatsapp:id/search_src_text",
            "com.whatsapp:id/search_input",
            "com.whatsapp:id/menuitem_search",
            "com.whatsapp.w4b:id/contactpicker_row_name",
            "com.whatsapp.w4b:id/search_src_text"
        )
        return pickerIds.any { id ->
            root.findAccessibilityNodeInfosByViewId(id)?.any { it.isVisibleToUser } == true
        }
    }

    private fun isMediaPreviewScreen(root: AccessibilityNodeInfo): Boolean {
        val previewIds = listOf(
            "com.whatsapp:id/caption",
            "com.whatsapp:id/send_media_btn",
            "com.whatsapp:id/crop_image",
            "com.whatsapp:id/doodle_entry",
            "com.whatsapp:id/media_preview_layout",
            "com.whatsapp.w4b:id/caption",
            "com.whatsapp.w4b:id/send_media_btn"
        )
        if (previewIds.any { id ->
                root.findAccessibilityNodeInfosByViewId(id)?.any { it.isVisibleToUser } == true
            }
        ) {
            return true
        }

        // Also detect Media Preview by caption hint or Add a caption text when search bar is absent
        val hasPickerSearch = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/search_src_text")
            ?.any { it.isVisibleToUser } == true
        if (!hasPickerSearch) {
            val captionMatches = root.findAccessibilityNodeInfosByText("caption")
                ?.any { it.isVisibleToUser } == true
            if (captionMatches) return true
        }

        return false
    }

    private fun findSendOrNextButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findResilientNode(
            root = root,
            viewIds = listOf(
                "com.whatsapp:id/send",
                "com.whatsapp:id/send_media_btn",
                "com.whatsapp:id/send_btn",
                "com.whatsapp:id/fab",
                "com.whatsapp:id/next_btn",
                "com.whatsapp.w4b:id/send",
                "com.whatsapp.w4b:id/send_media_btn",
                "com.whatsapp.w4b:id/fab"
            ),
            contentDescKeywords = listOf("Send", "Next", "send", "next")
        )
    }

    private fun findResilientNode(
        root: AccessibilityNodeInfo,
        viewIds: List<String> = emptyList(),
        contentDescKeywords: List<String> = emptyList(),
        classNameFilter: String? = null
    ): AccessibilityNodeInfo? {
        for (id in viewIds) {
            val matches = root.findAccessibilityNodeInfosByViewId(id)
            val visibleMatch = matches?.firstOrNull { it.isVisibleToUser }
            if (visibleMatch != null) return visibleMatch
        }

        val allNodes = collectAllNodes(root)

        if (contentDescKeywords.isNotEmpty()) {
            val descMatch = allNodes.firstOrNull { node ->
                if (!node.isVisibleToUser) return@firstOrNull false
                val desc = node.contentDescription?.toString() ?: return@firstOrNull false
                contentDescKeywords.any { keyword ->
                    desc.equals(keyword, ignoreCase = true) || desc.contains(keyword, ignoreCase = true)
                }
            }
            if (descMatch != null) return descMatch
        }

        if (classNameFilter != null) {
            return allNodes.firstOrNull { node ->
                node.isVisibleToUser &&
                    node.className?.toString()?.equals(classNameFilter, ignoreCase = true) == true
            }
        }

        return null
    }

    private fun collectAllNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty() && result.size < 450) {
            val current = queue.removeFirst()
            result.add(current)
            val childCount = current.childCount
            for (i in 0 until childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    override fun onInterrupt() {
        pollingJob?.cancel()
        AutomationStateManager.clearSession()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        AutomationStateManager.clearSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WhatsAppAccessService"
    }
}
