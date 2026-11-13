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
import kotlinx.coroutines.sync.Mutex
import java.util.ArrayDeque

class WhatsAppAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Mutex preventing overlapping execution between onAccessibilityEvent and the 500ms ticker
    private val stepMutex = Mutex()

    @Volatile
    private var pollingJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "WhatsAppAccessibilityService connected. Starting session watcher.")

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
     * Active 500ms Coroutine Ticker guarded by [stepMutex] so screen inspection never overlaps.
     */
    private fun ensurePollingLoopActive() {
        val currentJob = pollingJob
        if (currentJob != null && currentJob.isActive) return

        pollingJob = serviceScope.launch {
            Log.i(TAG, "Starting 500ms UI automation ticker loop.")
            while (AutomationStateManager.isTaskActive()) {
                if (stepMutex.tryLock()) {
                    try {
                        val rootNode = getBestWhatsAppRootNode()
                        if (rootNode != null) {
                            inspectAndAdvance(rootNode)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception inside 500ms automation ticker", e)
                    } finally {
                        stepMutex.unlock()
                    }
                }
                delay(500L)
            }
            Log.i(TAG, "500ms UI automation ticker loop finished.")
        }
    }

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
     * Core 5-State Machine Dispatcher executed inside [stepMutex].
     */
    private suspend fun inspectAndAdvance(rootNode: AccessibilityNodeInfo) {
        val session = AutomationStateManager.getValidSession() ?: return

        // =====================================================================
        // STRICT GROUP CHAT SCREEN GUARD:
        // If we are already inside the live Group Chat screen after STATE_3 or STATE_4,
        // abort immediately and NEVER click anything (prevents hitting Mic / Voice Call).
        // =====================================================================
        if ((session.subState == UiBotSubState.STATE_3_SELECT_GROUP ||
                session.subState == UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND) &&
            isInsideLiveChatScreen(rootNode)
        ) {
            Log.i(TAG, "Chat Screen Guard triggered: Inside live Group Chat screen. Aborting clicks & completing task.")
            AutomationStateManager.markFinalSendClicked()
            AutomationStateManager.clearSession()
            pollingJob?.cancel()
            markTaskCompletedInDatabase(session)
            return
        }

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
        if (isMediaPreviewScreen(rootNode)) {
            AutomationStateManager.transitionToState(
                session.taskId,
                UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND
            )
            return
        }

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
     * search_src_text / editable nodes). Click the group row, wait 800ms for the screen to transition,
     * and advance to STATE_4_PICKER_OR_PREVIEW_SEND.
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
                "(attempt=$attempt, clicked=$clicked). Waiting 800ms..."
        )
        delay(800L)

        AutomationStateManager.transitionToState(
            session.taskId,
            UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND
        )
    }

    /**
     * STATE 4: Clean Separation Between Contact Picker Screen vs. Media Preview Screen
     * with Atomic Single-Shot Send Lock and Live Chat Screen Guard.
     */
    private suspend fun executeState4PickerOrPreviewSend(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        // 1. Live Chat Screen Guard: Never click anything if already inside the chat screen
        if (isInsideLiveChatScreen(rootNode)) {
            Log.i(TAG, "STATE_4: Live Chat Screen detected -> aborting clicks and completing task.")
            AutomationStateManager.markFinalSendClicked()
            AutomationStateManager.clearSession()
            pollingJob?.cancel()
            markTaskCompletedInDatabase(session)
            return
        }

        val onMediaPreview = isMediaPreviewScreen(rootNode)
        val onContactPicker = isStillOnContactPickerScreen(rootNode) && !onMediaPreview

        // =====================================================================
        // CASE A: Direct or Completed Transition to Media Preview Screen
        // =====================================================================
        if (onMediaPreview || !onContactPicker) {
            val finalSendButton = findSendButton(rootNode)
            if (finalSendButton == null) {
                Log.d(TAG, "STATE_4 (Case A - Media Preview): Waiting for valid Send button...")
                return
            }

            // Populate caption if empty on the Media Preview screen
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
                        Log.i(TAG, "STATE_4 (Case A): Populated caption field on Media Preview screen.")
                        delay(300L)
                    }
                }
            }

            // Re-verify we are NOT inside the live chat screen right before clicking Send
            val freshRoot = getBestWhatsAppRootNode() ?: rootNode
            if (isInsideLiveChatScreen(freshRoot)) {
                AutomationStateManager.markFinalSendClicked()
                AutomationStateManager.clearSession()
                pollingJob?.cancel()
                markTaskCompletedInDatabase(session)
                return
            }

            // CRITICAL SINGLE-SHOT LOCK: Lock & cancel polling job BEFORE clicking Send!
            if (!AutomationStateManager.markFinalSendClicked()) {
                return
            }
            AutomationStateManager.clearSession()
            pollingJob?.cancel()

            // Perform a single ACTION_CLICK on the Send button (NO dispatchGesture fallback
            // so a delayed coordinate tap can never hit the Voice Note Mic button after screen closes)
            val sendClicked = clickNodeOrParentActionOnly(finalSendButton)
            Log.i(
                TAG,
                "STATE_4 -> STATE_5_DONE: Single-shot Send click executed " +
                    "for taskId=${session.taskId} (sendClicked=$sendClicked)"
            )

            markTaskCompletedInDatabase(session)
            return
        }

        // =====================================================================
        // CASE B: Still on Multi-Select Contact Picker Screen (search_src_text present)
        // =====================================================================
        if (onContactPicker && !session.pickerFabClicked) {
            val pickerFab = findSendButton(rootNode)
            if (pickerFab != null) {
                AutomationStateManager.incrementAttempt(
                    taskId = session.taskId,
                    markPickerFabClicked = true
                )
                val fabClicked = clickNodeOrParentActionOnly(pickerFab)
                Log.i(
                    TAG,
                    "STATE_4 (Case B - Picker FAB): Clicked Contact Picker FAB once " +
                        "(fabClicked=$fabClicked). Waiting 900ms for Media Preview screen..."
                )
                delay(900L)
                return
            }

            // If on the Contact Picker screen and no FAB is visible yet, the group row
            // in STATE_3 was not checked yet -> tap the group row once via gesture
            val groupNode = findGroupRowNodeExcludingSearchEditText(
                root = rootNode,
                targetGroupName = session.targetGroupName
            )
            if (groupNode != null) {
                val attempt = AutomationStateManager.incrementAttempt(session.taskId)
                if (attempt <= 2) {
                    clickNodeOrParentOrGesture(groupNode, forceGestureFallback = true)
                    delay(800L)
                }
            }
        }
    }

    private fun markTaskCompletedInDatabase(session: ActiveAutomationSession) {
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
    // FIX #2: STRICT LIVE GROUP CHAT SCREEN GUARD
    // =========================================================================

    /**
     * Returns true if the current window is WhatsApp's live Conversation/Chat screen.
     * Checks for chat-only elements: voice_note_btn, entry (chat text bar), input_layout,
     * conversation_contact_name, or "Voice message" / "Voice call" / "Video call" descriptions.
     */
    private fun isInsideLiveChatScreen(root: AccessibilityNodeInfo): Boolean {
        val chatOnlyViewIds = listOf(
            "com.whatsapp:id/voice_note_btn",
            "com.whatsapp:id/entry",
            "com.whatsapp:id/input_layout",
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/voice_note_btn",
            "com.whatsapp.w4b:id/entry",
            "com.whatsapp.w4b:id/input_layout",
            "com.whatsapp.w4b:id/conversation_contact_name"
        )
        for (id in chatOnlyViewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes?.any { it.isVisibleToUser } == true) {
                return true
            }
        }

        val allNodes = collectAllNodes(root)
        val chatOnlyDescriptions = listOf("Voice message", "Voice call", "Video call")
        return allNodes.any { node ->
            if (!node.isVisibleToUser) return@any false
            val desc = node.contentDescription?.toString()?.trim() ?: return@any false
            chatOnlyDescriptions.any { desc.equals(it, ignoreCase = true) }
        }
    }

    // =========================================================================
    // FIX #3: EXPLICIT BLACKLIST ON VOICE / MIC / CALL BUTTONS IN SEND FINDER
    // =========================================================================

    private fun isBlacklistedVoiceOrCallNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 3) {
            val viewId = current.viewIdResourceName?.lowercase() ?: ""
            val desc = current.contentDescription?.toString()?.lowercase() ?: ""

            if (viewId.contains("voice_note") ||
                viewId.contains("mic") ||
                viewId.contains("call") ||
                viewId.contains("audio") ||
                viewId.contains("video")
            ) {
                return true
            }

            if (desc.contains("voice") ||
                desc.contains("record") ||
                desc.contains("call") ||
                desc.contains("audio")
            ) {
                return true
            }

            current = current.parent
            depth++
        }
        return false
    }

    /**
     * Strictly locates the Media Preview or Picker Send button while rejecting any
     * Voice Note / Microphone / Call buttons and verifying isInsideLiveChatScreen(root) == false.
     */
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isInsideLiveChatScreen(root)) {
            return null
        }

        val allowedSendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_media_btn",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp.w4b:id/send_media_btn"
        )

        for (id in allowedSendIds) {
            val candidates = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            val valid = candidates.firstOrNull { node ->
                node.isVisibleToUser && !isBlacklistedVoiceOrCallNode(node)
            }
            if (valid != null) return valid
        }

        val allNodes = collectAllNodes(root)
        return allNodes.firstOrNull { node ->
            if (!node.isVisibleToUser || isBlacklistedVoiceOrCallNode(node)) {
                return@firstOrNull false
            }
            val viewId = node.viewIdResourceName ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            viewId.endsWith(":id/send") ||
                viewId.endsWith(":id/send_media_btn") ||
                desc.equals("Send", ignoreCase = true)
        }
    }

    // =========================================================================
    // FIX #1 (GROUP ROW): Strictly Exclude Search EditText When Finding Group Row
    // =========================================================================

    private fun isSearchOrEditableNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (className.contains("EditText", ignoreCase = true)) return true
        if (viewId.contains("search_src_text", ignoreCase = true)) return true
        if (viewId.contains("search_input", ignoreCase = true)) return true
        if (viewId.contains("search_bar", ignoreCase = true)) return true
        if (node.isEditable) return true

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

            idNodes.firstOrNull { node ->
                node.text?.toString()?.trim().equals(normalizedTarget, ignoreCase = true)
            }?.let { return it }

            idNodes.firstOrNull { node ->
                node.text?.toString()?.trim()?.contains(normalizedTarget, ignoreCase = true) == true
            }?.let { return it }
        }

        val textMatches = root.findAccessibilityNodeInfosByText(normalizedTarget)
            ?.filter { it.isVisibleToUser && !isSearchOrEditableNode(it) }
            .orEmpty()

        textMatches.firstOrNull { node ->
            node.text?.toString()?.trim().equals(normalizedTarget, ignoreCase = true)
        }?.let { return it }

        textMatches.firstOrNull { node ->
            node.text?.toString()?.trim()?.contains(normalizedTarget, ignoreCase = true) == true
        }?.let { return it }

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
    // CLICK HELPERS
    // =========================================================================

    /**
     * Performs a pure Accessibility ACTION_CLICK on [node] or its clickable parent (up to 6 levels)
     * WITHOUT dispatching a coordinate gesture. Used for the Send button so a delayed coordinate
     * tap can never hit the Voice Note (Mic) button after the Media Preview screen closes.
     */
    private fun clickNodeOrParentActionOnly(node: AccessibilityNodeInfo): Boolean {
        if (isBlacklistedVoiceOrCallNode(node)) return false

        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 6) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Used for selecting group rows and opening the search bar in the Contact Picker.
     */
    private fun clickNodeOrParentOrGesture(
        node: AccessibilityNodeInfo,
        forceGestureFallback: Boolean = false
    ): Boolean {
        if (isBlacklistedVoiceOrCallNode(node)) return false

        if (!forceGestureFallback) {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth <= 6) {
                if (current.isClickable && current.isEnabled) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                current = current.parent
                depth++
            }
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.centerX() <= 0 || rect.centerY() <= 0) {
            node.parent?.getBoundsInScreen(rect)
        }

        val tapX = rect.centerX().toFloat()
        val tapY = rect.centerY().toFloat()
        if (tapX > 0f && tapY > 0f) {
            val tapPath = Path().apply { moveTo(tapX, tapY) }
            val stroke = GestureDescription.StrokeDescription(tapPath, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = dispatchGesture(gesture, null, null)
            if (dispatched) return true
        }

        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // =========================================================================
    // SCREEN & NODE DETECTION HELPERS
    // =========================================================================

    private fun findSearchEditTextNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val searchIds = listOf(
            "com.whatsapp:id/search_src_text",
            "com.whatsapp:id/search_input",
            "com.whatsapp:id/search_chat_input",
            "com.whatsapp.w4b:id/search_src_text",
            "com.whatsapp.w4b:id/search_input"
        )
        for (id in searchIds) {
            val match = root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isVisibleToUser }
            if (match != null) return match
        }
        return null
    }

    private fun findCaptionEditTextOnPreview(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val explicitCaptionIds = listOf(
            "com.whatsapp:id/caption",
            "com.whatsapp.w4b:id/caption"
        )
        for (id in explicitCaptionIds) {
            val match = root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isVisibleToUser }
            if (match != null) return match
        }
        return null
    }

    private fun isStillOnContactPickerScreen(root: AccessibilityNodeInfo): Boolean {
        val pickerIds = listOf(
            "com.whatsapp:id/search_src_text",
            "com.whatsapp:id/contactpicker_row_name",
            "com.whatsapp.w4b:id/search_src_text",
            "com.whatsapp.w4b:id/contactpicker_row_name"
        )
        return pickerIds.any { id ->
            root.findAccessibilityNodeInfosByViewId(id)?.any { it.isVisibleToUser } == true
        }
    }

    private fun isMediaPreviewScreen(root: AccessibilityNodeInfo): Boolean {
        if (isInsideLiveChatScreen(root)) return false

        val previewIds = listOf(
            "com.whatsapp:id/caption",
            "com.whatsapp:id/send_media_btn",
            "com.whatsapp:id/crop_image",
            "com.whatsapp:id/doodle_entry",
            "com.whatsapp:id/media_preview_layout",
            "com.whatsapp.w4b:id/caption",
            "com.whatsapp.w4b:id/send_media_btn",
            "com.whatsapp.w4b:id/crop_image"
        )
        return previewIds.any { id ->
            root.findAccessibilityNodeInfosByViewId(id)?.any { it.isVisibleToUser } == true
        }
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
