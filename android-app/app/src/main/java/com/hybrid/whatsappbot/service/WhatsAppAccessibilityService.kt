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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class WhatsAppAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastStepActionTimeMillis: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // =========================================================================
        // CRITICAL GUARDRAIL #1: Strict 20-Second Session & Anti-Loop Gatekeeper
        // =========================================================================
        val session = AutomationStateManager.getValidSession() ?: return

        val packageName = event?.packageName?.toString() ?: ""
        if (!packageName.startsWith("com.whatsapp")) {
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            when (session.subState) {
                UiBotSubState.IDLE,
                UiBotSubState.COMPLETED -> {
                    // Never process events in IDLE or COMPLETED states
                    return
                }

                UiBotSubState.SEARCHING_GROUP -> {
                    handleStateASearchGroup(rootNode, session)
                }

                UiBotSubState.SELECTING_GROUP -> {
                    handleStateASelectGroupRow(rootNode, session)
                }

                UiBotSubState.FILLING_CAPTION_AND_SENDING -> {
                    handleStateBCaptionAndSend(rootNode, session)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in WhatsAppAccessibilityService state machine", e)
        }
    }

    /**
     * STATE A (Part 1 - Contact/Group Picker Screen):
     * Finds the Search button/bar, clicks it if needed, and injects targetGroupName via ACTION_SET_TEXT.
     */
    private fun handleStateASearchGroup(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        val now = System.currentTimeMillis()
        if (now - lastStepActionTimeMillis < 450L) return

        // Check if we somehow landed directly on Media Preview (e.g., direct JID share)
        if (isMediaPreviewScreen(rootNode)) {
            AutomationStateManager.transitionToCaptionAndSend(session.taskId)
            return
        }

        // 1. Try to locate an active search EditText first
        val searchEditText = findResilientNode(
            root = rootNode,
            viewIds = listOf(
                "com.whatsapp:id/search_src_text",
                "com.whatsapp:id/search_input",
                "com.whatsapp.w4b:id/search_src_text",
                "com.whatsapp.w4b:id/search_input"
            ),
            classNameFilter = "android.widget.EditText"
        )

        if (searchEditText != null) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    session.targetGroupName
                )
            }
            searchEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val textSet = searchEditText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "State A: Injected group name '${session.targetGroupName}' into search box (success=$textSet)")

            lastStepActionTimeMillis = System.currentTimeMillis()
            AutomationStateManager.transitionToSelectingGroup(session.taskId)
            return
        }

        // 2. Otherwise click the Search icon / toolbar action to reveal the search EditText
        val searchButton = findResilientNode(
            root = rootNode,
            viewIds = listOf(
                "com.whatsapp:id/menuitem_search",
                "com.whatsapp:id/search_bar_inner_layout",
                "com.whatsapp:id/search_holder",
                "com.whatsapp.w4b:id/menuitem_search",
                "com.whatsapp.w4b:id/search_bar_inner_layout"
            ),
            contentDescKeywords = listOf("Search", "search")
        )

        if (searchButton != null) {
            val clicked = performClickOnNodeOrParent(searchButton)
            lastStepActionTimeMillis = System.currentTimeMillis()
            Log.i(TAG, "State A: Clicked search button to open group filter (clicked=$clicked)")
        }
    }

    /**
     * STATE A (Part 2 - Contact/Group Picker Results):
     * Waits briefly for search results to render, locates the matching group node, and clicks it.
     */
    private fun handleStateASelectGroupRow(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        val now = System.currentTimeMillis()
        // Wait at least 650ms after typing the group name so RecyclerView filter finishes
        if (now - session.lastActionTimestampMillis < 650L) return
        if (now - lastStepActionTimeMillis < 500L) return

        val matchingGroupNode = findGroupRowNode(rootNode, session.targetGroupName)
        if (matchingGroupNode != null) {
            val clicked = performClickOnNodeOrParent(matchingGroupNode)
            lastStepActionTimeMillis = System.currentTimeMillis()
            Log.i(TAG, "State A: Clicked matching group row '${session.targetGroupName}' (clicked=$clicked)")
            AutomationStateManager.transitionToCaptionAndSend(session.taskId)
        } else {
            Log.d(TAG, "State A: Waiting for group row matching '${session.targetGroupName}'...")
        }
    }

    /**
     * STATE B (Media Preview / Caption Screen) & STATE C (Confirmation & Cleanup):
     * 1. If still on multi-select Contact Picker with the bottom-right "Next/Send" FAB visible, clicks it to enter Media Preview.
     * 2. On Media Preview screen, verifies caption is present (injects if stripped).
     * 3. Flips subState = COMPLETED *before* clicking Send to prevent any infinite loop.
     * 4. Marks task COMPLETED_UI in Room DB, clears AutomationStateManager, and navigates Home.
     */
    private fun handleStateBCaptionAndSend(
        rootNode: AccessibilityNodeInfo,
        session: ActiveAutomationSession
    ) {
        val now = System.currentTimeMillis()
        if (now - session.lastActionTimestampMillis < 600L) return
        if (now - lastStepActionTimeMillis < 600L) return

        // Check if we are still on the Contact Picker screen where selecting a group
        // reveals a bottom-right "Send/Next" FAB before opening Media Preview
        if (isContactPickerScreen(rootNode) && !isMediaPreviewScreen(rootNode)) {
            val pickerNextFab = findSendOrNextButton(rootNode)
            if (pickerNextFab != null) {
                val clicked = performClickOnNodeOrParent(pickerNextFab)
                lastStepActionTimeMillis = System.currentTimeMillis()
                Log.i(TAG, "State B (Picker FAB): Clicked contact picker Next/Send FAB (clicked=$clicked)")
                return
            }
        }

        // Locate the final Send button on the Media Preview / Composer screen
        val sendButton = findSendOrNextButton(rootNode) ?: return

        // Verify or re-inject the caption if WhatsApp stripped EXTRA_TEXT
        if (session.caption.isNotBlank()) {
            val captionEditNode = findResilientNode(
                root = rootNode,
                viewIds = listOf(
                    "com.whatsapp:id/caption",
                    "com.whatsapp:id/entry",
                    "com.whatsapp.w4b:id/caption",
                    "com.whatsapp.w4b:id/entry"
                ),
                classNameFilter = "android.widget.EditText"
            )
            if (captionEditNode != null) {
                val existingText = captionEditNode.text?.toString()?.trim() ?: ""
                if (existingText.isEmpty() || !existingText.contains(session.caption.take(15), ignoreCase = true)) {
                    val captionArgs = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            session.caption
                        )
                    }
                    captionEditNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    captionEditNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, captionArgs)
                    Log.i(TAG, "State B: Injected caption text on Media Preview screen.")
                }
            }
        }

        // =========================================================================
        // CRITICAL GUARDRAIL #1: Lock state to COMPLETED *BEFORE* clicking Send!
        // =========================================================================
        if (!AutomationStateManager.tryLockForFinalSendClick(session.taskId)) {
            return
        }

        val sendClicked = performClickOnNodeOrParent(sendButton)
        Log.i(TAG, "State B -> State C: Clicked final Send button for taskId=${session.taskId} (clicked=$sendClicked)")

        // State C: Persist COMPLETED_UI in Room DB, clear state immediately, and return Home
        completeTaskAndReturnHome(session)
    }

    private fun completeTaskAndReturnHome(session: ActiveAutomationSession) {
        AutomationStateManager.clearSession()

        serviceScope.launch {
            try {
                val dao = AppDatabase.getInstance(applicationContext).taskDao()
                dao.updateTaskStatus(
                    taskId = session.taskId,
                    newStatus = TaskStatus.COMPLETED_UI,
                    logMessage = "Sent via Engine 1 (Foreground UI Automation Bot) to \"${session.targetGroupName}\".",
                    completedAt = System.currentTimeMillis()
                )
                // Allow WhatsApp 1.2s to finalize message dispatch before returning to Home screen
                delay(1_200L)
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                Log.e(TAG, "Failed updating Room DB after UI completion for task=${session.taskId}", e)
            }
        }
    }

    // =========================================================================
    // RESILIENT NODE MATCHING HELPERS (View ID + ContentDescription + Class)
    // =========================================================================

    private fun isContactPickerScreen(root: AccessibilityNodeInfo): Boolean {
        val pickerIds = listOf(
            "com.whatsapp:id/contactpicker_row_name",
            "com.whatsapp:id/search_src_text",
            "com.whatsapp.w4b:id/contactpicker_row_name",
            "com.whatsapp.w4b:id/search_src_text"
        )
        for (id in pickerIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) return true
        }
        return false
    }

    private fun isMediaPreviewScreen(root: AccessibilityNodeInfo): Boolean {
        val previewIds = listOf(
            "com.whatsapp:id/caption",
            "com.whatsapp:id/crop_image",
            "com.whatsapp:id/doodle_entry",
            "com.whatsapp.w4b:id/caption"
        )
        for (id in previewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) return true
        }
        return false
    }

    private fun findSendOrNextButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findResilientNode(
            root = root,
            viewIds = listOf(
                "com.whatsapp:id/send",
                "com.whatsapp:id/send_btn",
                "com.whatsapp:id/fab",
                "com.whatsapp.w4b:id/send",
                "com.whatsapp.w4b:id/send_btn"
            ),
            contentDescKeywords = listOf("Send", "send")
        )
    }

    /**
     * Locates the contact/group row matching targetGroupName while strictly ignoring
     * EditText nodes so the bot never clicks the search input bar itself.
     */
    private fun findGroupRowNode(
        root: AccessibilityNodeInfo,
        targetGroupName: String
    ): AccessibilityNodeInfo? {
        val normalizedTarget = targetGroupName.trim()

        // Strategy 1: Known WhatsApp row title view IDs
        val rowViewIds = listOf(
            "com.whatsapp:id/contactpicker_row_name",
            "com.whatsapp:id/conversations_row_contact_name",
            "com.whatsapp.w4b:id/contactpicker_row_name",
            "com.whatsapp.w4b:id/conversations_row_contact_name"
        )
        for (viewId in rowViewIds) {
            val candidates = root.findAccessibilityNodeInfosByViewId(viewId) ?: continue
            // Exact case-insensitive match first
            candidates.firstOrNull { node ->
                node.isVisibleToUser &&
                    node.text?.toString()?.trim().equals(normalizedTarget, ignoreCase = true)
            }?.let { return it }

            // Substring match second
            candidates.firstOrNull { node ->
                node.isVisibleToUser &&
                    (node.text?.toString()?.trim()?.contains(normalizedTarget, ignoreCase = true) == true)
            }?.let { return it }
        }

        // Strategy 2: BFS traversal looking for non-EditText nodes matching the group name
        val allNodes = collectAllNodes(root)
        allNodes.firstOrNull { node ->
            val cls = node.className?.toString() ?: ""
            !cls.contains("EditText", ignoreCase = true) &&
                node.isVisibleToUser &&
                node.text?.toString()?.trim().equals(normalizedTarget, ignoreCase = true)
        }?.let { return it }

        return allNodes.firstOrNull { node ->
            val cls = node.className?.toString() ?: ""
            !cls.contains("EditText", ignoreCase = true) &&
                node.isVisibleToUser &&
                (node.text?.toString()?.trim()?.contains(normalizedTarget, ignoreCase = true) == true)
        }
    }

    /**
     * Multi-strategy node finder checking:
     * 1. Explicit resource-id list
     * 2. ContentDescription keywords
     * 3. Class name fallback
     */
    private fun findResilientNode(
        root: AccessibilityNodeInfo,
        viewIds: List<String> = emptyList(),
        contentDescKeywords: List<String> = emptyList(),
        classNameFilter: String? = null
    ): AccessibilityNodeInfo? {
        // 1. Check by View IDs
        for (id in viewIds) {
            val matches = root.findAccessibilityNodeInfosByViewId(id)
            val visibleMatch = matches?.firstOrNull { it.isVisibleToUser }
            if (visibleMatch != null) return visibleMatch
        }

        val allNodes = collectAllNodes(root)

        // 2. Check by contentDescription
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

        // 3. Check by Class Name fallback
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

        while (queue.isNotEmpty() && result.size < 400) {
            val current = queue.removeFirst()
            result.add(current)
            val childCount = current.childCount
            for (i in 0 until childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    /**
     * Walks up the AccessibilityNodeInfo hierarchy until it finds a clickable node
     * and dispatches ACTION_CLICK.
     */
    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun onInterrupt() {
        AutomationStateManager.clearSession()
    }

    override fun onDestroy() {
        AutomationStateManager.clearSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WhatsAppAccessService"
    }
}
