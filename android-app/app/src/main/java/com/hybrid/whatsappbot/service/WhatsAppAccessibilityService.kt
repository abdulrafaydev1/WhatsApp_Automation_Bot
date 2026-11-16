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
import kotlin.math.abs

class WhatsAppAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Mutex preventing overlapping execution between onAccessibilityEvent and the 500ms ticker
    private val stepMutex = Mutex()

    @Volatile
    private var pollingJob: Job? = null

    private sealed class OldPostLookupResult {
        data class FoundOldPost(val node: AccessibilityNodeInfo) : OldPostLookupResult()
        object NeedScrollUp : OldPostLookupResult()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "WhatsAppAccessibilityService connected. Starting session watcher.")

        serviceScope.launch {
            AutomationStateManager.sessionFlow.collectLatest { session ->
                if (session != null && session.subState != UiBotSubState.STATE_6_DONE) {
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
     * Core Sequential State Machine Dispatcher executed inside [stepMutex].
     */
    private suspend fun inspectAndAdvance(rootNode: AccessibilityNodeInfo) {
        val session = AutomationStateManager.getValidSession() ?: return

        // =====================================================================
        // Disable Premature Chat-Screen Abort During Delete Phase!
        // Only trigger the live chat guard if we landed in the chat screen
        // during STATE_3 or STATE_4 BEFORE STATE_5A/5B/5C.
        // =====================================================================
        if ((session.subState == UiBotSubState.STATE_3_SELECT_GROUP ||
                session.subState == UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND) &&
            isInsideLiveChatScreen(rootNode)
        ) {
            AutomationStateManager.markFinalSendClicked()
            val hadPrevious = AutomationStateManager.hasPreviousPostInGroup(
                context = applicationContext,
                groupName = session.targetGroupName
            )
            if (hadPrevious) {
                Log.i(TAG, "Landed in live chat screen with existing previous post -> transitioning to STATE_5A_FIND_OLD_POST (45s window)")
                AutomationStateManager.transitionToState(
                    taskId = session.taskId,
                    newState = UiBotSubState.STATE_5A_FIND_OLD_POST,
                    extendExpiryMillis = 45_000L
                )
            } else {
                Log.i(TAG, "Landed in live chat screen (1st post in group) -> recording post & completing.")
                AutomationStateManager.markPostSentInGroup(
                    context = applicationContext,
                    groupName = session.targetGroupName,
                    baseCaption = session.baseCaption,
                    sentTag = session.currentTag
                )
                AutomationStateManager.clearSession()
                pollingJob?.cancel()
                markTaskCompletedInDatabase(session, deletedPrevious = false)
            }
            return
        }

        when (session.subState) {
            UiBotSubState.IDLE,
            UiBotSubState.STATE_6_DONE -> return

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

            UiBotSubState.STATE_5A_FIND_OLD_POST -> {
                executeState5AFindOldPost(rootNode, session)
            }

            UiBotSubState.STATE_5B_CLICK_TRASH_ICON -> {
                executeState5BClickTrashIcon(rootNode, session)
            }

            UiBotSubState.STATE_5C_CLICK_DELETE_FOR_EVERYONE -> {
                executeState5CClickDeleteForEveryone(rootNode, session)
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
     * search_src_text / editable nodes). Click the group row, wait 800ms, and advance to STATE_4.
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
     * STATE 4: Media Preview & Picker Send Handler.
     * - Ensures [session.taggedCaption] (with the alternating zero-width Unicode PREFIX tag)
     *   is present at the start of the caption.
     * - Clicks the Media Preview Send button once.
     * - If 1st post in this group: Records tag and finishes immediately.
     * - If 2nd+ post in this group: Does NOT exit WhatsApp; extends expiry to 45_000ms and
     *   transitions to STATE_5A_FIND_OLD_POST.
     */
    private suspend fun executeState4PickerOrPreviewSend(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        if (isInsideLiveChatScreen(rootNode)) {
            return
        }

        val onMediaPreview = isMediaPreviewScreen(rootNode)
        val onContactPicker = isStillOnContactPickerScreen(rootNode) && !onMediaPreview

        // =====================================================================
        // CASE A: On Media Preview Screen -> Ensure Prefix Tagged Caption & Click Send
        // =====================================================================
        if (onMediaPreview || !onContactPicker) {
            val finalSendButton = findSendButton(rootNode)
            if (finalSendButton == null) {
                Log.d(TAG, "STATE_4 (Case A - Media Preview): Waiting for valid Send button...")
                return
            }

            // Ensure the caption field starts with our alternating zero-width Unicode prefix (currentTag)
            val captionEditNode = findCaptionEditTextOnPreview(rootNode)
            if (captionEditNode != null && session.taggedCaption.isNotEmpty()) {
                val currentText = captionEditNode.text?.toString() ?: ""
                if (!currentText.startsWith(session.currentTag) && !currentText.contains(session.currentTag)) {
                    val captionArgs = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            session.taggedCaption
                        )
                    }
                    captionEditNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    captionEditNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, captionArgs)
                    Log.i(TAG, "STATE_4: Injected prefix-tagged caption into Media Preview.")
                    delay(300L)
                }
            }

            val freshRoot = getBestWhatsAppRootNode() ?: rootNode
            if (isInsideLiveChatScreen(freshRoot)) {
                return
            }

            // CRITICAL SINGLE-SHOT SEND LOCK: Ensure Send button is clicked at most once!
            if (!AutomationStateManager.markFinalSendClicked()) {
                return
            }

            val hadPreviousPost = AutomationStateManager.hasPreviousPostInGroup(
                context = applicationContext,
                groupName = session.targetGroupName
            )

            // Perform a single ACTION_CLICK on the Media Preview Send button (no gesture fallback)
            val sendClicked = clickNodeOrParentActionOnly(finalSendButton)
            Log.i(
                TAG,
                "STATE_4: Single-shot Send click executed for group='${session.targetGroupName}' " +
                    "(sendClicked=$sendClicked, hadPreviousPost=$hadPreviousPost)"
            )

            if (!hadPreviousPost) {
                // 1st post in this group: Record tag, mark COMPLETED_UI, and finish normally
                AutomationStateManager.markPostSentInGroup(
                    context = applicationContext,
                    groupName = session.targetGroupName,
                    baseCaption = session.baseCaption,
                    sentTag = session.currentTag
                )
                AutomationStateManager.clearSession()
                pollingJob?.cancel()
                markTaskCompletedInDatabase(session, deletedPrevious = false)
            } else {
                // 2nd+ post in this group: Do NOT mark completed or exit Home yet!
                // Extend expiryTimestamp by +45_000ms and transition to STATE_5A_FIND_OLD_POST
                Log.i(
                    TAG,
                    "STATE_4 -> STATE_5A_FIND_OLD_POST: Previous post exists in '${session.targetGroupName}'. " +
                        "Extending window +45s to scroll up & delete 1st post."
                )
                AutomationStateManager.transitionToState(
                    taskId = session.taskId,
                    newState = UiBotSubState.STATE_5A_FIND_OLD_POST,
                    extendExpiryMillis = 45_000L
                )
            }
            return
        }

        // =====================================================================
        // CASE B: Still on Multi-Select Contact Picker Screen
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

    // =========================================================================
    // EXPLICIT 3-STEP DELETE STATE MACHINE (STATE_5A -> 5B -> 5C)
    // =========================================================================

    /**
     * STATE_5A_FIND_OLD_POST:
     * Supports scrolling up through multiple photos or multiple text messages (up to 12 scrolls)
     * to locate our older 1st post, long-presses it, and waits until the top action-bar
     * Delete (Trash) icon is visible before transitioning to STATE_5B_CLICK_TRASH_ICON.
     */
    private suspend fun executeState5AFindOldPost(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        val elapsedInState = System.currentTimeMillis() - session.stateEnteredAtMillis
        if (elapsedInState < 1_500L) {
            return
        }

        // If the contextual action-bar Delete (Trash) icon is already visible, advance to STATE_5B!
        if (findActionBarDeleteIcon(rootNode) != null) {
            Log.i(TAG, "STATE_5A: Action-bar Delete icon is visible -> transitioning to STATE_5B_CLICK_TRASH_ICON")
            AutomationStateManager.transitionToState(
                taskId = session.taskId,
                newState = UiBotSubState.STATE_5B_CLICK_TRASH_ICON
            )
            return
        }

        when (val lookup = lookupOlderFirstPostBubble(rootNode, session)) {
            is OldPostLookupResult.FoundOldPost -> {
                val attempt = AutomationStateManager.incrementAttempt(session.taskId)
                Log.i(TAG, "STATE_5A: Target 1st post found on screen (scrolls=${session.scrollUpCount}, attempt=$attempt). Long-pressing...")

                // 1. Try ACTION_LONG_CLICK on the long-clickable parent row first
                val actionLongClicked = tryActionLongClickOnParent(lookup.node)
                if (actionLongClicked) {
                    delay(750L)
                }

                // 2. Check if the Delete (Trash) icon appeared; if not, immediately execute
                //    the 1000ms continuous coordinate hold gesture fallback!
                val midRoot = getBestWhatsAppRootNode()
                if (midRoot == null || findActionBarDeleteIcon(midRoot) == null) {
                    dispatchOneSecondHoldGesture(lookup.node)
                    delay(1_250L)
                }

                val postPressRoot = getBestWhatsAppRootNode()
                if (postPressRoot != null && findActionBarDeleteIcon(postPressRoot) != null) {
                    Log.i(TAG, "STATE_5A -> STATE_5B: Older 1st post selected & Trash icon visible!")
                    AutomationStateManager.transitionToState(
                        taskId = session.taskId,
                        newState = UiBotSubState.STATE_5B_CLICK_TRASH_ICON
                    )
                } else if (attempt >= 4) {
                    Log.w(TAG, "STATE_5A: Action bar did not appear after 4 long-press attempts. Finishing cleanly.")
                    finishDeleteFlowAndExit(session, deletedSuccessfully = false)
                }
            }

            is OldPostLookupResult.NeedScrollUp -> {
                if (session.scrollUpCount < MAX_SCROLL_ATTEMPTS) {
                    val scrollNum = AutomationStateManager.incrementScrollUpCount(session.taskId)
                    Log.i(
                        TAG,
                        "STATE_5A: Scrolling UP through chat messages/photos to find 1st post " +
                            "(scroll $scrollNum/$MAX_SCROLL_ATTEMPTS)..."
                    )
                    performScrollUpInChat(rootNode)
                    delay(900L)
                } else {
                    Log.w(
                        TAG,
                        "STATE_5A: Reached maxScrollAttempts ($MAX_SCROLL_ATTEMPTS) without locating 1st post. " +
                            "Finishing cleanly without touching 2nd post."
                    )
                    finishDeleteFlowAndExit(session, deletedSuccessfully = false)
                }
            }
        }
    }

    /**
     * STATE_5B_CLICK_TRASH_ICON:
     * Locates the top action bar trash/delete icon (com.whatsapp:id/menuitem_delete or
     * contentDescription == "Delete") and clicks it once via ACTION_CLICK.
     * Waits 700ms and transitions to STATE_5C_CLICK_DELETE_FOR_EVERYONE.
     */
    private suspend fun executeState5BClickTrashIcon(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        if (findDeleteForEveryoneButton(rootNode) != null) {
            AutomationStateManager.transitionToState(
                taskId = session.taskId,
                newState = UiBotSubState.STATE_5C_CLICK_DELETE_FOR_EVERYONE
            )
            return
        }

        val trashIcon = findActionBarDeleteIcon(rootNode)
        if (trashIcon != null) {
            val attempt = AutomationStateManager.incrementAttempt(session.taskId)
            val clicked = clickNodeOrParentOrGesture(
                node = trashIcon,
                forceGestureFallback = attempt >= 2
            )
            Log.i(TAG, "STATE_5B: Clicked top action-bar Delete (Trash) icon (attempt=$attempt, clicked=$clicked). Waiting 700ms...")
            delay(700L)

            AutomationStateManager.transitionToState(
                taskId = session.taskId,
                newState = UiBotSubState.STATE_5C_CLICK_DELETE_FOR_EVERYONE
            )
        } else {
            val attempt = AutomationStateManager.incrementAttempt(session.taskId)
            if (attempt >= 4) {
                Log.w(TAG, "STATE_5B: Delete icon disappeared before click. Returning to STATE_5A.")
                AutomationStateManager.transitionToState(
                    taskId = session.taskId,
                    newState = UiBotSubState.STATE_5A_FIND_OLD_POST
                )
            }
        }
    }

    /**
     * STATE_5C_CLICK_DELETE_FOR_EVERYONE:
     * Locates the dialog button matching "Delete for everyone", clicks it via ACTION_CLICK,
     * waits 800ms, updates lastSentTag = currentTag for the group, marks the task COMPLETED_UI,
     * and only THEN stops the automation loop and exits cleanly.
     */
    private suspend fun executeState5CClickDeleteForEveryone(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        val deleteForEveryoneBtn = findDeleteForEveryoneButton(rootNode)
        if (deleteForEveryoneBtn != null) {
            val attempt = AutomationStateManager.incrementAttempt(session.taskId)
            val clicked = clickNodeOrParentOrGesture(
                node = deleteForEveryoneBtn,
                forceGestureFallback = attempt >= 2
            )
            Log.i(
                TAG,
                "STATE_5C: Clicked 'Delete for everyone' button (attempt=$attempt, clicked=$clicked)! Waiting 800ms..."
            )
            delay(800L)
            finishDeleteFlowAndExit(session, deletedSuccessfully = true)
        } else {
            val attempt = AutomationStateManager.incrementAttempt(session.taskId)
            if (attempt >= 6) {
                Log.w(TAG, "STATE_5C: 'Delete for everyone' dialog button not found after 6 ticks. Finishing cleanly.")
                finishDeleteFlowAndExit(session, deletedSuccessfully = false)
            }
        }
    }

    private fun finishDeleteFlowAndExit(
        session: ActiveAutomationSession,
        deletedSuccessfully: Boolean
    ) {
        AutomationStateManager.markPostSentInGroup(
            context = applicationContext,
            groupName = session.targetGroupName,
            baseCaption = session.baseCaption,
            sentTag = session.currentTag
        )
        AutomationStateManager.clearSession()
        pollingJob?.cancel()
        markTaskCompletedInDatabase(session, deletedPrevious = deletedSuccessfully)
    }

    // =========================================================================
    // SCROLL-AWARE 1ST POST vs 2ND POST DETECTION (Supports Multi-Photo / Multi-Text)
    // =========================================================================

    private fun lookupOlderFirstPostBubble(
        root: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ): OldPostLookupResult {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.takeIf { it > 0 } ?: 1080
        val screenHeight = displayMetrics.heightPixels.takeIf { it > 0 } ?: 2400

        // Safe clickable chat message list area (15% to 85% of screen height)
        val minSafeChatY = (screenHeight * 0.15f).toInt()
        val maxSafeChatY = (screenHeight * 0.85f).toInt()

        val chatNodes = collectAllNodes(root).filter { node ->
            if (!node.isVisibleToUser) return@filter false
            if (isSearchOrEditableNode(node) || isBlacklistedVoiceOrCallNode(node)) return@filter false
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (viewId.contains("entry") ||
                viewId.contains("input_layout") ||
                viewId.contains("conversation_contact_name") ||
                viewId.contains("toolbar")
            ) {
                return@filter false
            }
            val r = Rect()
            node.getBoundsInScreen(r)
            !r.isEmpty && r.centerY() in minSafeChatY..maxSafeChatY
        }

        // =====================================================================
        // PRIORITY 1: Exact Prefix / Contains Match on previousTag
        // Any node starting with or containing previousTag is 100% our older 1st post!
        // =====================================================================
        if (session.previousTag.isNotEmpty()) {
            val previousTaggedNode = chatNodes
                .filter { node ->
                    val txt = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    val matchesPrev = txt.startsWith(session.previousTag) ||
                        txt.contains(session.previousTag) ||
                        desc.startsWith(session.previousTag) ||
                        desc.contains(session.previousTag)
                    val matchesCurrent = session.currentTag.isNotEmpty() &&
                        (txt.contains(session.currentTag) || desc.contains(session.currentTag))
                    matchesPrev && !matchesCurrent
                }
                .minByOrNull { node ->
                    val r = Rect().also { node.getBoundsInScreen(it) }
                    r.top
                }

            if (previousTaggedNode != null) {
                Log.i(
                    TAG,
                    "STATE_5A (Priority 1): Matched older 1st post directly via prefix previousTag " +
                        "(scrollUpCount=${session.scrollUpCount})!"
                )
                return OldPostLookupResult.FoundOldPost(previousTaggedNode)
            }
        }

        // =====================================================================
        // PRIORITY 2 & 3: Base Caption Match + Scroll-Up Awareness
        // =====================================================================
        val baseSnippet = session.previousCaptionSnippet.trim()
            .ifEmpty { session.baseCaption.lineSequence().firstOrNull()?.take(40)?.trim() ?: "" }

        if (baseSnippet.isNotEmpty()) {
            val allMatchingCaptionNodes = chatNodes.filter { node ->
                val txt = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val cleanTxt = AutomationStateManager.stripZeroWidthTags(txt)
                val cleanDesc = AutomationStateManager.stripZeroWidthTags(desc)
                cleanTxt.contains(baseSnippet, ignoreCase = true) ||
                    cleanDesc.contains(baseSnippet, ignoreCase = true)
            }

            // Sort top-to-bottom (smaller rect.top = higher up on screen = older post)
            val sortedBubblesTopToBottom = deduplicateNodesByVerticalRow(allMatchingCaptionNodes)

            // Priority 2: Both 1st and 2nd posts are visible on screen simultaneously
            if (sortedBubblesTopToBottom.size >= 2) {
                val upperOlderBubble = sortedBubblesTopToBottom.first()
                val upperTxt = upperOlderBubble.text?.toString() ?: ""
                val hasCurrentNewTag = session.currentTag.isNotEmpty() && upperTxt.contains(session.currentTag)

                if (!hasCurrentNewTag) {
                    Log.i(
                        TAG,
                        "STATE_5A (Priority 2): 2+ matching posts visible on screen; " +
                            "selecting upper 1st post (scrollUpCount=${session.scrollUpCount})."
                    )
                    return OldPostLookupResult.FoundOldPost(upperOlderBubble)
                }
            }

            // Priority 3: Only 1 matching post of ours is visible on screen
            if (sortedBubblesTopToBottom.size == 1) {
                val singleNode = sortedBubblesTopToBottom.first()
                val singleText = singleNode.text?.toString() ?: ""
                val singleDesc = singleNode.contentDescription?.toString() ?: ""
                val singleRect = Rect().also { singleNode.getBoundsInScreen(it) }

                val hasCurrentNewTag = session.currentTag.isNotEmpty() &&
                    (singleText.contains(session.currentTag) || singleDesc.contains(session.currentTag))

                // Never select a node that carries currentTag (that is our newly sent 2nd post)
                if (hasCurrentNewTag) {
                    Log.i(TAG, "STATE_5A: Single visible post carries currentTag (2nd post) -> scrolling up.")
                    return OldPostLookupResult.NeedScrollUp
                }

                // At scrollUpCount == 0 (initial bottom screen right after sending):
                // If only 1 post is visible at the bottom, our 1st post was pushed off-screen
                // by incoming photos/messages -> scroll up!
                if (session.scrollUpCount == 0) {
                    Log.i(TAG, "STATE_5A (scroll=0): Only 1 post visible on initial bottom screen -> scrolling up to find 1st post.")
                    return OldPostLookupResult.NeedScrollUp
                }

                // Once scrollUpCount >= 1:
                // We have scrolled up past incoming messages/photos, and a matching post of ours
                // without currentTag is now on screen -> that is our 1st post!
                Log.i(
                    TAG,
                    "STATE_5A (Priority 3): Found 1st post after ${session.scrollUpCount} upward scroll(s) " +
                        "at centerY=${singleRect.centerY()}!"
                )
                return OldPostLookupResult.FoundOldPost(singleNode)
            }
        }

        // If no matching post is visible on the current screen segment yet, scroll up!
        return OldPostLookupResult.NeedScrollUp
    }

    /**
     * Controlled Medium-Step Upward Scroll:
     * Swipes finger DOWN from (screenWidth / 2, screenHeight * 0.28f) to
     * (screenWidth / 2, screenHeight * 0.75f) over 450ms so we never overshoot the 1st post.
     */
    private fun performScrollUpInChat(root: AccessibilityNodeInfo) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.takeIf { it > 0 } ?: 1080
        val screenHeight = displayMetrics.heightPixels.takeIf { it > 0 } ?: 2400

        val centerX = screenWidth / 2f
        val startY = screenHeight * 0.28f
        val endY = screenHeight * 0.75f

        val swipePath = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(swipePath, 0L, 450L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)

        if (!dispatched) {
            val scrollableList = collectAllNodes(root).firstOrNull { it.isScrollable }
            scrollableList?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
    }

    /**
     * Sorts candidate nodes by rect.top ascending (smaller rect.top = higher up on screen = older post)
     * and merges nodes belonging to the same message bubble (within 110px vertical center distance).
     */
    private fun deduplicateNodesByVerticalRow(
        nodes: List<AccessibilityNodeInfo>
    ): List<AccessibilityNodeInfo> {
        val sorted = nodes.sortedBy { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.top
        }
        val distinctRows = mutableListOf<AccessibilityNodeInfo>()
        for (candidate in sorted) {
            val candRect = Rect().also { candidate.getBoundsInScreen(it) }
            val overlapsExistingRow = distinctRows.any { existing ->
                val exRect = Rect().also { existing.getBoundsInScreen(it) }
                abs(candRect.centerY() - exRect.centerY()) < 110
            }
            if (!overlapsExistingRow) {
                distinctRows.add(candidate)
            }
        }
        return distinctRows
    }

    private fun tryActionLongClickOnParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 6) {
            if (current.isLongClickable && current.isEnabled) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    return true
                }
            }
            current = current.parent
            depth++
        }
        return false
    }

    /**
     * Dispatches a 1000ms continuous press gesture at (rect.centerX(), clampedCenterY)
     * where Y is clamped into [18%..82%] of screenHeight so it always hits inside the chat list.
     */
    private fun dispatchOneSecondHoldGesture(node: AccessibilityNodeInfo): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels.takeIf { it > 0 } ?: 2400

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.centerX() <= 0 || rect.centerY() <= 0) {
            node.parent?.getBoundsInScreen(rect)
        }

        val minSafeY = (screenHeight * 0.18f).toInt()
        val maxSafeY = (screenHeight * 0.82f).toInt()

        val tapX = rect.centerX().toFloat()
        val tapY = rect.centerY().coerceIn(minSafeY, maxSafeY).toFloat()
        if (tapX > 0f && tapY > 0f) {
            val pressPath = Path().apply { moveTo(tapX, tapY) }
            val stroke = GestureDescription.StrokeDescription(pressPath, 0L, 1_000L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = dispatchGesture(gesture, null, null)
            Log.i(TAG, "Dispatched 1000ms long-press hold at ($tapX, $tapY) -> dispatched=$dispatched")
            return dispatched
        }
        return false
    }

    private fun findActionBarDeleteIcon(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val deleteIds = listOf(
            "com.whatsapp:id/menuitem_delete",
            "com.whatsapp.w4b:id/menuitem_delete"
        )
        for (id in deleteIds) {
            val match = root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isVisibleToUser }
            if (match != null) return match
        }

        return collectAllNodes(root).firstOrNull { node ->
            if (!node.isVisibleToUser) return@firstOrNull false
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            desc.equals("Delete", ignoreCase = true) || text.equals("Delete", ignoreCase = true)
        }
    }

    private fun findDeleteForEveryoneButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val explicitIds = listOf(
            "com.whatsapp:id/delete_for_everyone",
            "com.whatsapp:id/delete_for_everyone_btn",
            "com.whatsapp.w4b:id/delete_for_everyone",
            "com.whatsapp.w4b:id/delete_for_everyone_btn"
        )
        for (id in explicitIds) {
            val match = root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isVisibleToUser }
            if (match != null) return match
        }

        val byText = root.findAccessibilityNodeInfosByText("Delete for everyone")
            ?.firstOrNull { it.isVisibleToUser }
        if (byText != null) return byText

        return collectAllNodes(root).firstOrNull { node ->
            if (!node.isVisibleToUser) return@firstOrNull false
            val txt = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            txt.equals("Delete for everyone", ignoreCase = true) ||
                desc.equals("Delete for everyone", ignoreCase = true) ||
                txt.contains("Delete for everyone", ignoreCase = true)
        }
    }

    private fun markTaskCompletedInDatabase(
        session: ActiveAutomationSession,
        deletedPrevious: Boolean
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getInstance(applicationContext).taskDao()
                val suffix = if (deletedPrevious) {
                    " (Auto-deleted previous post for everyone)"
                } else {
                    ""
                }
                dao.updateTaskStatus(
                    taskId = session.taskId,
                    newStatus = TaskStatus.COMPLETED_UI,
                    logMessage = "Sent via Engine 1 (Foreground UI Bot) to \"${session.targetGroupName}\"$suffix.",
                    completedAt = System.currentTimeMillis()
                )
                delay(600L)
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                Log.e(TAG, "Failed updating Room DB after task completion", e)
            }
        }
    }

    // =========================================================================
    // STRICT LIVE GROUP CHAT SCREEN GUARD
    // =========================================================================

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
    // EXPLICIT BLACKLIST ON VOICE / MIC / CALL BUTTONS IN SEND FINDER
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
    // GROUP ROW SEARCH (Strictly Excluding Search EditText)
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
        private const val MAX_SCROLL_ATTEMPTS = 12
    }
}
