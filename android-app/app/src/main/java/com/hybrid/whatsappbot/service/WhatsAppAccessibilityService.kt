package com.hybrid.whatsappbot.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hybrid.whatsappbot.automation.ActiveAutomationSession
import com.hybrid.whatsappbot.automation.AutomationStateManager
import com.hybrid.whatsappbot.automation.UiBotSubState
import com.hybrid.whatsappbot.data.AppDatabase
import com.hybrid.whatsappbot.data.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

class WhatsAppAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stepMutex = Mutex()

    @Volatile
    private var pollingJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "WhatsAppAccessibilityService connected. Starting session watcher.")

        serviceScope.launch {
            AutomationStateManager.sessionFlow.collectLatest { session ->
                if (session != null && !session.subState.isTerminal()) {
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
            serviceScope.launch {
                inspectAndAdvanceSynchronized()
            }
        }
    }

    /**
     * Active 500ms Coroutine Polling Loop:
     * Calls the same Mutex-guarded [inspectAndAdvanceSynchronized] entry point as
     * [onAccessibilityEvent] so overlapping inspections or clicks can never occur.
     */
    private fun ensurePollingLoopActive() {
        val currentJob = pollingJob
        if (currentJob != null && currentJob.isActive) return

        pollingJob = serviceScope.launch {
            Log.i(TAG, "Starting 500ms UI automation ticker loop.")
            while (AutomationStateManager.isTaskActive()) {
                try {
                    inspectAndAdvanceSynchronized()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "Exception inside 500ms automation ticker", e)
                }
                delay(500L)
            }
            Log.i(TAG, "500ms UI automation ticker loop finished.")
        }
    }

    /**
     * Centralized Mutex-serialized screen inspection and state transition function.
     * Both [onAccessibilityEvent] and the 500ms polling loop execute exclusively through this lock.
     */
    private suspend fun inspectAndAdvanceSynchronized() {
        if (!AutomationStateManager.isTaskActive()) return

        stepMutex.withLock {
            val session = AutomationStateManager.getValidSession() ?: return@withLock
            if (session.subState.isTerminal() || AutomationStateManager.isFinalSendLocked()) {
                return@withLock
            }

            val rootNode = getBestWhatsAppRootNode() ?: return@withLock

            // Mandatory Live Chat Screen Guard before any state inspection
            if (isInsideLiveChatScreen(rootNode)) {
                abortOnLiveChatDetected(session)
                return@withLock
            }

            when (session.subState) {
                UiBotSubState.IDLE,
                UiBotSubState.COMPLETED_UI,
                UiBotSubState.STATE_5_DONE -> return@withLock

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
                    executeState4PostSelectionFlow(rootNode, session)
                }
            }
        }
    }

    /**
     * Retrieves a fresh active WhatsApp window root node, even if the soft keyboard (IME)
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
     * STATE 1: Locate the search button on the "Send to..." picker screen
     * (or detect if the search EditText is already open) and transition to STATE_2_TYPE_QUERY.
     */
    private suspend fun executeState1ClickSearch(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        if (isInsideLiveChatScreen(rootNode)) {
            abortOnLiveChatDetected(session)
            return
        }

        // If WhatsApp opened directly into Media Preview, transition to STATE_4
        if (isDirectMediaPreviewScreen(rootNode)) {
            AutomationStateManager.transitionToState(
                session.taskId,
                UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND
            )
            return
        }

        // If the search EditText is already open and visible, advance immediately to State 2
        val existingSearchInput = findSearchEditTextNode(rootNode)
        if (existingSearchInput != null) {
            AutomationStateManager.transitionToState(
                session.taskId,
                UiBotSubState.STATE_2_TYPE_QUERY
            )
            val refreshedSession = AutomationStateManager.getValidSession() ?: return
            executeState2TypeQuery(rootNode, refreshedSession)
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
            AutomationStateManager.incrementAttempt(session.taskId)
            clickNodeOrClickableParent(searchButton)
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
        if (isInsideLiveChatScreen(rootNode)) {
            abortOnLiveChatDetected(session)
            return
        }

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
        searchEditText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        delay(800L)

        AutomationStateManager.transitionToState(
            session.taskId,
            UiBotSubState.STATE_3_SELECT_GROUP
        )
    }

    /**
     * STATE 3: Select the target group row once, wait 800ms while holding [stepMutex],
     * obtain a fresh accessibility root, and evaluate Case A (Direct Media Preview),
     * Case B (Multi-Select Contact Picker), or Case C (Unknown/Unexpected Screen).
     */
    private suspend fun executeState3SelectGroup(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        // 1. Verify current screen is not a live chat
        if (isInsideLiveChatScreen(rootNode)) {
            abortOnLiveChatDetected(session)
            return
        }

        // 2. Verify current task is active & 3. final-send lock has not been consumed
        val currentSession = AutomationStateManager.getValidSession() ?: return
        if (currentSession.taskId != session.taskId || AutomationStateManager.isFinalSendLocked()) {
            return
        }

        // 4. Locate the intended group row using existing selection logic
        val groupRowNode = findGroupRowNodeExcludingSearchEditText(
            root = rootNode,
            targetGroupName = currentSession.targetGroupName
        )

        if (groupRowNode == null) {
            val attempt = AutomationStateManager.incrementAttempt(currentSession.taskId)
            if (attempt >= 5) {
                abortAutomationSafely(
                    session = currentSession,
                    reason = "Target group row not found in search results after $attempt attempts"
                )
            }
            return
        }

        // 5. Click the intended group row exactly once
        Log.i(TAG, "[ENGINE1] STATE_3: Group selection initiated")
        val rowClicked = clickNodeOrClickableParent(groupRowNode)
        if (!rowClicked) {
            abortAutomationSafely(
                session = currentSession,
                reason = "Group row ACTION_CLICK returned false"
            )
            return
        }

        AutomationStateManager.transitionToState(
            taskId = currentSession.taskId,
            newState = UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND,
            pickerFabClicked = false
        )

        // 6. Wait 800ms while stepMutex remains held
        Log.i(TAG, "[ENGINE1] STATE_3: Waiting 800ms for screen transition")
        delay(800L)

        // 7. Obtain a fresh accessibility root after the delay (never reuse rootNode)
        val freshRootAfterSelect = getBestWhatsAppRootNode()
        val postWaitSession = AutomationStateManager.getValidSession() ?: return
        if (postWaitSession.taskId != currentSession.taskId) return

        if (freshRootAfterSelect == null) {
            abortAutomationSafely(
                session = postWaitSession,
                reason = "Accessibility root became null after 800ms group selection transition"
            )
            return
        }

        // 8. Inspect the new window before performing any other click
        executeState4PostSelectionFlow(freshRootAfterSelect, postWaitSession)
    }

    /**
     * Evaluates the screen after group selection (STATE_4):
     * - Guard: Live Chat Screen -> immediate abort
     * - CASE A: Direct transition to Media Preview -> skip Picker FAB, execute single-shot Send
     * - CASE B: Multi-Select Contact Picker with verified selection -> click FAB once, wait 900ms,
     *           obtain fresh root, verify Media Preview, execute single-shot Send
     * - CASE C: Unknown or unexpected screen -> log diagnostics and abort safely without clicking
     */
    private suspend fun executeState4PostSelectionFlow(
        currentRoot: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        val activeSession = AutomationStateManager.getValidSession() ?: return
        if (activeSession.taskId != session.taskId || AutomationStateManager.isFinalSendLocked()) {
            return
        }

        // Guard 1: Check Live Chat screen first
        if (isInsideLiveChatScreen(currentRoot)) {
            abortOnLiveChatDetected(activeSession)
            return
        }

        // =====================================================================
        // CASE A — Direct Transition to Media Preview
        // =====================================================================
        if (isDirectMediaPreviewScreen(currentRoot) && findSendButton(currentRoot) != null) {
            Log.i(TAG, "[ENGINE1] SCREEN: Media Preview detected")
            executeSingleShotFinalSend(currentRoot, activeSession)
            return
        }

        // =====================================================================
        // CASE B — Still on Multi-Select Contact Picker
        // =====================================================================
        if (!activeSession.pickerFabClicked && isMultiSelectContactPickerScreen(currentRoot)) {
            Log.i(TAG, "[ENGINE1] SCREEN: Multi-Select Contact Picker detected")

            val selectionVerified = isSelectedGroupVerifiedOnPicker(
                root = currentRoot,
                targetGroupName = activeSession.targetGroupName
            )
            if (!selectionVerified) {
                logUnknownOrUnverifiedScreen(
                    prefix = "Contact Picker detected but selected group state could not be verified",
                    root = currentRoot
                )
                abortAutomationSafely(
                    session = activeSession,
                    reason = "Selected group checkmark/state not verified on Contact Picker"
                )
                return
            }

            Log.i(TAG, "[ENGINE1] PICKER: Selected group verified")

            val pickerFab = findPickerFabButton(currentRoot)
            if (pickerFab == null) {
                abortAutomationSafely(
                    session = activeSession,
                    reason = "Picker FAB not found on verified Multi-Select Contact Picker"
                )
                return
            }

            // Mark pickerFabClicked = true before clicking so FAB can never be clicked twice
            AutomationStateManager.transitionToState(
                taskId = activeSession.taskId,
                newState = UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND,
                pickerFabClicked = true
            )

            val fabClicked = clickNodeOrClickableParent(pickerFab)
            if (!fabClicked) {
                abortAutomationSafely(
                    session = activeSession,
                    reason = "Picker FAB ACTION_CLICK returned false"
                )
                return
            }

            Log.i(TAG, "[ENGINE1] PICKER: FAB clicked once")
            Log.i(TAG, "[ENGINE1] PICKER: Waiting 900ms for Media Preview")
            delay(900L)

            // Obtain a fresh accessibility root after the 900ms wait
            val freshPreviewRoot = getBestWhatsAppRootNode()
            val sessionAfterFab = AutomationStateManager.getValidSession() ?: return
            if (sessionAfterFab.taskId != activeSession.taskId) return

            if (freshPreviewRoot == null) {
                abortAutomationSafely(
                    session = sessionAfterFab,
                    reason = "Fresh root was null after 900ms Picker FAB wait"
                )
                return
            }

            // Check Live Chat guard first on the new root
            if (isInsideLiveChatScreen(freshPreviewRoot)) {
                abortOnLiveChatDetected(sessionAfterFab)
                return
            }

            // Verify that the new screen is genuinely Media Preview and search_src_text is absent
            if (isDirectMediaPreviewScreen(freshPreviewRoot) &&
                !hasSearchSrcText(freshPreviewRoot) &&
                findSendButton(freshPreviewRoot) != null
            ) {
                Log.i(TAG, "[ENGINE1] SCREEN: Media Preview detected")
                executeSingleShotFinalSend(freshPreviewRoot, sessionAfterFab)
                return
            }

            // New screen after Picker FAB is not verified Media Preview -> abort safely
            logUnknownOrUnverifiedScreen(
                prefix = "Screen after 900ms Picker FAB wait is not a verified Media Preview",
                root = freshPreviewRoot
            )
            abortAutomationSafely(
                session = sessionAfterFab,
                reason = "Media Preview could not be positively verified after Picker FAB click"
            )
            return
        }

        // =====================================================================
        // CASE C — Unknown or Unexpected Screen
        // =====================================================================
        logUnknownOrUnverifiedScreen(
            prefix = "Unknown or unexpected screen encountered in STATE_4",
            root = currentRoot
        )
        abortAutomationSafely(
            session = activeSession,
            reason = "Unknown or unexpected WhatsApp screen in STATE_4; aborting safely without clicking"
        )
    }

    /**
     * PHASE 6 — CENTRALIZED SINGLE-SHOT FINAL SEND TRANSACTION
     *
     * Guaranteed execution sequence:
     * 1. Validate active session, Media Preview screen, Live Chat guard == false, and
     *    non-blacklisted enabled Send button.
     * 2. Acquire per-task AtomicBoolean via AutomationStateManager.markFinalSendClicked().
     * 3. Synchronously mark task COMPLETED_UI, cancel pollingJob, and clear AutomationStateManager.
     * 4. Immediately dispatch exactly one sendButton.performAction(ACTION_CLICK) with zero delay,
     *    zero yield, and zero coordinate gesture fallback.
     * 5. Handle Boolean result accurately in Room DB while keeping the lock permanently consumed.
     */
    private fun executeSingleShotFinalSend(
        root: AccessibilityNodeInfo?,
        session: ActiveAutomationSession
    ) {
        // STEP 1 — Validate screen
        if (root == null) return

        val currentSession = AutomationStateManager.getValidSession() ?: return
        if (currentSession.taskId != session.taskId || !currentSession.subState.canDispatchFinalSend()) {
            return
        }

        if (isInsideLiveChatScreen(root)) {
            abortOnLiveChatDetected(currentSession)
            return
        }

        if (!isDirectMediaPreviewScreen(root) || hasSearchSrcText(root)) {
            return
        }

        // Populate caption if stripped by WhatsApp (performed prior to final send validation
        // so zero delay or suspension occurs between lock acquisition and click dispatch)
        if (currentSession.caption.isNotBlank()) {
            val captionNode = findCaptionEditTextOnPreview(root)
            if (captionNode != null) {
                val existingText = captionNode.text?.toString()?.trim() ?: ""
                val isPlaceholderOrEmpty = existingText.isEmpty() ||
                    existingText.equals("Add a caption...", ignoreCase = true) ||
                    existingText.equals("Add a caption", ignoreCase = true)
                if (isPlaceholderOrEmpty) {
                    val captionArgs = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            currentSession.caption
                        )
                    }
                    captionNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    captionNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, captionArgs)
                }
            }
        }

        // Re-verify Live Chat guard and locate validated, non-blacklisted Send button
        if (isInsideLiveChatScreen(root)) {
            abortOnLiveChatDetected(currentSession)
            return
        }

        val sendButton = findSendButton(root) ?: return
        if (!sendButton.isVisibleToUser || !sendButton.isEnabled) {
            return
        }

        // STEP 2 — Acquire the atomic lock
        if (!AutomationStateManager.markFinalSendClicked()) {
            Log.w(TAG, "[ENGINE1] SEND: Duplicate attempt rejected")
            return
        }
        Log.i(TAG, "[ENGINE1] SEND: Atomic lock acquired")

        // STEP 3 — Immediately terminate further automation BEFORE dispatching click
        AutomationStateManager.markTaskCompletedUi(currentSession.taskId)
        Log.i(TAG, "[ENGINE1] SEND: Task marked COMPLETED_UI")

        pollingJob?.cancel()
        pollingJob = null
        Log.i(TAG, "[ENGINE1] SEND: Polling job cancelled")

        AutomationStateManager.clearSession()

        // STEP 4 — Dispatch Store-and-Forward Single ACTION_CLICK (no delay, no gesture fallback)
        Log.i(TAG, "[ENGINE1] SEND: Single ACTION_CLICK dispatched")
        val clickResult = sendButton.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        )

        // STEP 5 — Handle the action result
        if (clickResult) {
            persistTaskCompletionInRoom(currentSession)
        } else {
            Log.e(TAG, "[ENGINE1] SAFETY: Send coordinate fallback prohibited")
            persistTaskFailureInRoom(
                session = currentSession,
                reason = "Media Preview Send ACTION_CLICK returned false; coordinate fallback prohibited."
            )
        }
    }

    // =========================================================================
    // PHASE 3 — STRICT LIVE GROUP CHAT SCREEN PROTECTION
    // =========================================================================

    /**
     * Recursively inspects the accessibility node hierarchy to detect whether WhatsApp is
     * currently displaying a live Chat / Conversation screen.
     */
    private fun isInsideLiveChatScreen(
        root: AccessibilityNodeInfo
    ): Boolean {
        return checkNodeTreeForLiveChat(root, depth = 0)
    }

    private fun checkNodeTreeForLiveChat(
        node: AccessibilityNodeInfo?,
        depth: Int
    ): Boolean {
        if (node == null || depth > 40) return false

        val viewId = node.viewIdResourceName ?: ""
        val contentDesc = if (node.isVisibleToUser) {
            node.contentDescription?.toString()
        } else {
            null
        }

        if (isLiveChatNodeAttributes(
                viewIdResourceName = viewId,
                contentDescription = contentDesc,
                isVisibleToUser = node.isVisibleToUser
            )
        ) {
            return true
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            if (checkNodeTreeForLiveChat(child, depth + 1)) {
                return true
            }
        }
        return false
    }

    /**
     * Immediate abort handler when the Live Chat Screen Guard triggers.
     */
    private fun abortOnLiveChatDetected(session: ActiveAutomationSession) {
        Log.w(TAG, "[ENGINE1] SAFETY: Live Chat detected - aborting interaction")
        pollingJob?.cancel()
        pollingJob = null
        AutomationStateManager.markTaskCompletedUi(session.taskId)
        AutomationStateManager.clearSession()
        persistTaskCompletionInRoom(session)
    }

    private fun abortAutomationSafely(session: ActiveAutomationSession, reason: String) {
        Log.w(TAG, "[ENGINE1] ABORT: $reason (taskId=${session.taskId})")
        pollingJob?.cancel()
        pollingJob = null
        AutomationStateManager.clearSession()
        persistTaskFailureInRoom(session, reason)
    }

    private fun persistTaskCompletionInRoom(session: ActiveAutomationSession) {
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
                Log.e(TAG, "Failed updating Room DB after COMPLETED_UI", e)
            }
        }
    }

    private fun persistTaskFailureInRoom(session: ActiveAutomationSession, reason: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getInstance(applicationContext).taskDao()
                dao.updateStatusIfNotCompleted(
                    taskId = session.taskId,
                    newStatus = TaskStatus.FAILED,
                    logMessage = "Engine 1 aborted safely: $reason",
                    completedAt = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed recording abort status in Room DB", e)
            }
        }
    }

    // =========================================================================
    // PHASE 4 — STRICT SEND BUTTON IDENTIFICATION & BLACKLIST
    // =========================================================================

    /**
     * Strictly locates the Media Preview Send button while rejecting any voice, microphone,
     * audio, video, or call controls, and returning null if the Live Chat guard triggers.
     */
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isInsideLiveChatScreen(root)) {
            return null
        }

        val allNodes = collectAllNodes(root)
        for (candidate in allNodes) {
            if (!candidate.isVisibleToUser || !candidate.isEnabled) continue

            val viewId = candidate.viewIdResourceName ?: ""
            val contentDesc = candidate.contentDescription?.toString() ?: ""

            // 1. Apply Mandatory Blacklist BEFORE accepting any positive match
            if (isBlacklistedSendCandidate(viewId, contentDesc)) {
                continue
            }

            // 2. Check Allowed Positive Send-Button Identification
            if (!isAllowedSendCandidate(viewId, contentDesc)) {
                continue
            }

            // 3. Resolve clickable enabled node (verifying ancestors also pass the blacklist)
            val clickableTarget = resolveClickableSendNode(candidate)
            if (clickableTarget != null) {
                return clickableTarget
            }
        }
        return null
    }

    private fun resolveClickableSendNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 5) {
            if (!current.isEnabled || !current.isVisibleToUser) return null
            val currentId = current.viewIdResourceName ?: ""
            val currentDesc = current.contentDescription?.toString() ?: ""
            if (isBlacklistedSendCandidate(currentId, currentDesc)) {
                return null
            }
            if (current.isClickable) {
                return current
            }
            current = current.parent
            depth++
        }
        return null
    }

    // =========================================================================
    // PHASE 5 — MEDIA PREVIEW vs. CONTACT PICKER SCREEN VERIFICATION
    // =========================================================================

    private fun hasSearchSrcText(root: AccessibilityNodeInfo): Boolean {
        val allNodes = collectAllNodes(root)
        return allNodes.any { node ->
            node.isVisibleToUser &&
                node.viewIdResourceName?.endsWith(":id/search_src_text", ignoreCase = true) == true
        }
    }

    /**
     * CASE A Predicate:
     * True iff:
     * - Not a Live Chat screen
     * - com.whatsapp:id/search_src_text is absent
     * - At least one of (:id/caption, :id/crop_image, :id/send) is visible on screen
     */
    private fun isDirectMediaPreviewScreen(root: AccessibilityNodeInfo): Boolean {
        if (isInsideLiveChatScreen(root)) return false
        if (hasSearchSrcText(root)) return false

        val allNodes = collectAllNodes(root)
        val visibleIds = allNodes
            .filter { it.isVisibleToUser }
            .mapNotNull { it.viewIdResourceName }

        return isMediaPreviewByResourceIds(
            visibleViewIds = visibleIds,
            hasSearchField = false,
            isLiveChat = false
        )
    }

    /**
     * CASE B Predicate (Part 1 - Contact Picker screen presence):
     */
    private fun isMultiSelectContactPickerScreen(root: AccessibilityNodeInfo): Boolean {
        if (isInsideLiveChatScreen(root)) return false

        val allNodes = collectAllNodes(root)
        val hasPickerRowsOrList = allNodes.any { node ->
            if (!node.isVisibleToUser) return@any false
            val id = node.viewIdResourceName ?: ""
            id.endsWith(":id/contactpicker_row_name", ignoreCase = true) ||
                id.endsWith(":id/conversations_row_contact_name", ignoreCase = true) ||
                id.endsWith(":id/contactpicker_list", ignoreCase = true) ||
                id.endsWith(":id/list", ignoreCase = true)
        }
        return hasPickerRowsOrList
    }

    /**
     * CASE B Predicate (Part 2 - Verified selected group checkmark/state on Contact Picker):
     * Does NOT assume that search_src_text alone proves selection.
     * Requires an explicit checked/selected node, selection checkmark view, or selected contacts strip.
     */
    private fun isSelectedGroupVerifiedOnPicker(
        root: AccessibilityNodeInfo,
        targetGroupName: String
    ): Boolean {
        val allNodes = collectAllNodes(root)

        // 1. Check explicit selection checkmark / selected strip views or checked state
        val hasExplicitSelectionIndicator = allNodes.any { node ->
            if (!node.isVisibleToUser) return@any false
            val id = node.viewIdResourceName ?: ""
            val desc = node.contentDescription?.toString() ?: ""

            node.isChecked ||
                node.isSelected ||
                id.endsWith(":id/selection_check", ignoreCase = true) ||
                id.endsWith(":id/selected_list", ignoreCase = true) ||
                id.endsWith(":id/selected_items", ignoreCase = true) ||
                id.endsWith(":id/contact_selector", ignoreCase = true) ||
                id.contains("selection_check", ignoreCase = true) ||
                desc.contains("Selected", ignoreCase = true) ||
                desc.contains("Checked", ignoreCase = true)
        }
        if (hasExplicitSelectionIndicator) {
            return true
        }

        // 2. Check if the matching group row or any of its parent/sibling nodes is marked selected/checked
        val groupNode = findGroupRowNodeExcludingSearchEditText(root, targetGroupName)
        if (groupNode != null) {
            var current: AccessibilityNodeInfo? = groupNode
            var depth = 0
            while (current != null && depth <= 4) {
                if (current.isChecked || current.isSelected) {
                    return true
                }
                val childCount = current.childCount
                for (i in 0 until childCount) {
                    val sibling = current.getChild(i) ?: continue
                    val sibId = sibling.viewIdResourceName ?: ""
                    if (sibling.isVisibleToUser &&
                        (sibling.isChecked ||
                            sibling.isSelected ||
                            sibId.contains("selection_check", ignoreCase = true) ||
                            sibId.contains("check", ignoreCase = true))
                    ) {
                        return true
                    }
                }
                current = current.parent
                depth++
            }
        }

        return false
    }

    private fun findPickerFabButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isInsideLiveChatScreen(root)) return null

        val allNodes = collectAllNodes(root)
        for (node in allNodes) {
            if (!node.isVisibleToUser || !node.isEnabled) continue
            val viewId = node.viewIdResourceName ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            if (isBlacklistedSendCandidate(viewId, desc)) continue

            val isFabMatch = viewId.endsWith(":id/send", ignoreCase = true) ||
                viewId.endsWith(":id/fab", ignoreCase = true) ||
                viewId.endsWith(":id/next_btn", ignoreCase = true) ||
                desc.equals("Send", ignoreCase = true) ||
                desc.equals("Next", ignoreCase = true)

            if (isFabMatch) {
                val clickable = resolveClickableSendNode(node)
                if (clickable != null) return clickable
            }
        }
        return null
    }

    // =========================================================================
    // SEARCH & GROUP ROW NODE HELPERS
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

    /**
     * Traverses up to 6 parent levels to perform ACTION_CLICK on the first clickable,
     * enabled ancestor. Never uses coordinate-based gesture tapping.
     */
    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 6) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

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
            "com.whatsapp.w4b:id/caption"
        )
        for (id in explicitCaptionIds) {
            val match = root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isVisibleToUser }
            if (match != null) return match
        }
        return null
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

    private fun logUnknownOrUnverifiedScreen(prefix: String, root: AccessibilityNodeInfo) {
        val visibleResourceIds = collectAllNodes(root)
            .filter { it.isVisibleToUser }
            .mapNotNull { it.viewIdResourceName }
            .distinct()
            .take(20)
        Log.w(TAG, "[ENGINE1] $prefix | Visible IDs: $visibleResourceIds")
    }

    override fun onInterrupt() {
        pollingJob?.cancel()
        pollingJob = null
        AutomationStateManager.clearSession()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        pollingJob = null
        AutomationStateManager.clearSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WhatsAppAccessService"

        private val LIVE_CHAT_RESOURCE_SUFFIXES = listOf(
            ":id/voice_note_btn",
            ":id/entry",
            ":id/input_layout",
            ":id/conversation_contact_name"
        )

        private val LIVE_CHAT_CONTENT_DESCRIPTIONS = listOf(
            "voice message",
            "voice call",
            "video call"
        )

        private val SEND_VIEW_ID_BLACKLIST = listOf(
            "voice_note",
            "mic",
            "call",
            "audio",
            "video"
        )

        private val SEND_CONTENT_DESC_BLACKLIST = listOf(
            "voice",
            "record",
            "call",
            "audio"
        )

        /**
         * Pure predicate for Phase 3.1 Live Chat Screen detection.
         */
        internal fun isLiveChatNodeAttributes(
            viewIdResourceName: String?,
            contentDescription: String?,
            isVisibleToUser: Boolean
        ): Boolean {
            val id = viewIdResourceName?.trim() ?: ""
            if (id.isNotEmpty()) {
                val matchesChatId = id.equals("com.whatsapp:id/voice_note_btn", ignoreCase = true) ||
                    id.equals("com.whatsapp:id/entry", ignoreCase = true) ||
                    id.equals("com.whatsapp:id/input_layout", ignoreCase = true) ||
                    id.equals("com.whatsapp:id/conversation_contact_name", ignoreCase = true) ||
                    LIVE_CHAT_RESOURCE_SUFFIXES.any { suffix -> id.endsWith(suffix, ignoreCase = true) }
                if (matchesChatId) return true
            }

            if (isVisibleToUser && !contentDescription.isNullOrBlank()) {
                val normalizedDesc = contentDescription.trim()
                if (LIVE_CHAT_CONTENT_DESCRIPTIONS.any { target ->
                        normalizedDesc.equals(target, ignoreCase = true) ||
                            normalizedDesc.contains(target, ignoreCase = true)
                    }
                ) {
                    return true
                }
            }

            return false
        }

        /**
         * Pure predicate for Phase 4.1 Mandatory Send-Button Blacklist.
         */
        internal fun isBlacklistedSendCandidate(
            viewIdResourceName: String?,
            contentDescription: String?
        ): Boolean {
            val id = viewIdResourceName ?: ""
            if (id.isNotEmpty() && SEND_VIEW_ID_BLACKLIST.any { id.contains(it, ignoreCase = true) }) {
                return true
            }

            val desc = contentDescription ?: ""
            if (desc.isNotEmpty() && SEND_CONTENT_DESC_BLACKLIST.any { desc.contains(it, ignoreCase = true) }) {
                return true
            }

            return false
        }

        /**
         * Pure predicate for Phase 4.2 Allowed Positive Send-Button Match.
         */
        internal fun isAllowedSendCandidate(
            viewIdResourceName: String?,
            contentDescription: String?
        ): Boolean {
            if (isBlacklistedSendCandidate(viewIdResourceName, contentDescription)) {
                return false
            }

            val id = viewIdResourceName?.trim() ?: ""
            if (id.endsWith(":id/send", ignoreCase = true) ||
                id.endsWith(":id/send_media_btn", ignoreCase = true)
            ) {
                return true
            }

            val desc = contentDescription?.trim() ?: ""
            return desc.equals("Send", ignoreCase = true)
        }

        /**
         * Pure predicate for Phase 5 Case A (Direct Media Preview verification).
         */
        internal fun isMediaPreviewByResourceIds(
            visibleViewIds: Iterable<String>,
            hasSearchField: Boolean,
            isLiveChat: Boolean
        ): Boolean {
            if (isLiveChat || hasSearchField) return false
            return visibleViewIds.any { id ->
                id.endsWith(":id/caption", ignoreCase = true) ||
                    id.endsWith(":id/crop_image", ignoreCase = true) ||
                    id.endsWith(":id/send", ignoreCase = true) ||
                    id.endsWith(":id/send_media_btn", ignoreCase = true)
            }
        }
    }
}
