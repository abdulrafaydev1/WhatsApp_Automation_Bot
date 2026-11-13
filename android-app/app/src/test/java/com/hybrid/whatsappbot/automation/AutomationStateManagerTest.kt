package com.hybrid.whatsappbot.automation

import com.hybrid.whatsappbot.service.WhatsAppAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AutomationStateManagerTest {

    @Before
    fun setUp() {
        AutomationStateManager.clearSession()
    }

    @After
    fun tearDown() {
        AutomationStateManager.clearSession()
    }

    // =========================================================================
    // PHASE 10.1 — ATOMIC LOCK TESTS
    // =========================================================================

    @Test
    fun `first markFinalSendClicked returns true and second call returns false`() {
        AutomationStateManager.activateSession(
            taskId = "task-1",
            targetGroupName = "Marketing Group",
            caption = "Hello"
        )
        AutomationStateManager.transitionToState("task-1", UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND)

        assertTrue("First call must acquire atomic send lock", AutomationStateManager.markFinalSendClicked())
        assertFalse("Second call must be rejected", AutomationStateManager.markFinalSendClicked())
        assertTrue("Lock must remain consumed", AutomationStateManager.isFinalSendLocked())
    }

    @Test
    fun `multiple concurrent calls allow exactly one successful acquisition`() = runBlocking {
        AutomationStateManager.activateSession(
            taskId = "task-concurrent",
            targetGroupName = "Sales Group",
            caption = "Promo"
        )
        AutomationStateManager.transitionToState("task-concurrent", UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND)

        val acquisitions = (1..64).map {
            async(Dispatchers.Default) {
                if (AutomationStateManager.markFinalSendClicked()) 1 else 0
            }
        }.awaitAll().sum()

        assertEquals("Exactly one concurrent caller may acquire the final send lock", 1, acquisitions)
        assertFalse("Subsequent call after race must fail", AutomationStateManager.markFinalSendClicked())
    }

    @Test
    fun `newly initialized task receives a fresh lock`() {
        AutomationStateManager.activateSession("task-A", "Group A", "Caption A")
        AutomationStateManager.transitionToState("task-A", UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND)
        assertTrue(AutomationStateManager.markFinalSendClicked())
        AutomationStateManager.markTaskCompletedUi("task-A")
        AutomationStateManager.clearSession()

        // Start a brand-new independent task
        AutomationStateManager.activateSession("task-B", "Group B", "Caption B")
        assertFalse("New task must start with unconsumed lock", AutomationStateManager.isFinalSendLocked())
        AutomationStateManager.transitionToState("task-B", UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND)
        assertTrue("New task must acquire its fresh lock once", AutomationStateManager.markFinalSendClicked())
        assertFalse("Second attempt on new task must be rejected", AutomationStateManager.markFinalSendClicked())
    }

    @Test
    fun `clearing an old task does not permit stale callbacks to operate`() {
        AutomationStateManager.activateSession("stale-task", "Group Old", "Caption")
        AutomationStateManager.transitionToState("stale-task", UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND)

        // Clear session before send
        AutomationStateManager.clearSession()
        assertFalse(
            "markFinalSendClicked must return false after clearSession",
            AutomationStateManager.markFinalSendClicked()
        )
        assertNull(AutomationStateManager.getValidSession())

        // Even if a new task starts, a stale callback bound to "stale-task" must be rejected
        AutomationStateManager.activateSession("fresh-task", "Group New", "Caption")
        AutomationStateManager.transitionToState("fresh-task", UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND)

        assertFalse(
            "Stale callback with old taskId must be rejected",
            AutomationStateManager.markFinalSendClicked("stale-task")
        )
        assertFalse(
            "Fresh task lock must remain unconsumed after rejecting stale taskId",
            AutomationStateManager.isFinalSendLocked()
        )
        assertTrue(
            "Fresh task callback with matching taskId must succeed",
            AutomationStateManager.markFinalSendClicked("fresh-task")
        )
    }

    @Test
    fun `completed task cannot return to a sendable state`() {
        AutomationStateManager.activateSession("task-terminal", "Group T", "Caption")
        AutomationStateManager.transitionToState("task-terminal", UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND)
        assertTrue(AutomationStateManager.markFinalSendClicked())
        assertTrue(AutomationStateManager.markTaskCompletedUi("task-terminal"))

        assertNull("Completed session must not be returned as valid", AutomationStateManager.getValidSession())
        assertFalse("isTaskActive must be false", AutomationStateManager.isTaskActive())

        val transitionedBack = AutomationStateManager.transitionToState(
            "task-terminal",
            UiBotSubState.STATE_3_SELECT_GROUP
        )
        assertFalse("Completed task must never transition back to STATE_3", transitionedBack)
        assertFalse("Completed task must never re-acquire send lock", AutomationStateManager.markFinalSendClicked())
    }

    // =========================================================================
    // PHASE 10.2 — STATE-FLOW & GUARD VALIDATION (TESTS A through I)
    // =========================================================================

    @Test
    fun `Test A - Direct Media Preview skips Picker FAB and dispatches one Send click`() = runBlocking {
        val harness = Engine1FlowHarness()
        AutomationStateManager.activateSession("test-A", "VIP Group", "Hello World")
        AutomationStateManager.transitionToState("test-A", UiBotSubState.STATE_3_SELECT_GROUP)

        // Screen after 800ms group click is Direct Media Preview (no search_src_text)
        harness.screenAfterGroupSelect = SimulatedScreen(
            visibleIds = listOf("com.whatsapp:id/caption", "com.whatsapp:id/send"),
            hasValidSendButton = true
        )

        harness.runState3GroupSelectAndTransition()

        assertEquals("Group row clicked once", 1, harness.groupRowClicks.get())
        assertEquals("Picker FAB must be skipped on Direct Media Preview", 0, harness.pickerFabClicks.get())
        assertEquals("Exactly one final Send click must occur", 1, harness.finalSendClicks.get())
        assertFalse("Task must be terminal after Send", AutomationStateManager.isTaskActive())
    }

    @Test
    fun `Test B - Normal Contact Picker clicks FAB once then Media Preview Send once`() = runBlocking {
        val harness = Engine1FlowHarness()
        AutomationStateManager.activateSession("test-B", "Deals Group", "Caption B")
        AutomationStateManager.transitionToState("test-B", UiBotSubState.STATE_3_SELECT_GROUP)

        // Screen after 800ms group click is Multi-Select Contact Picker with verified selection
        harness.screenAfterGroupSelect = SimulatedScreen(
            visibleIds = listOf(
                "com.whatsapp:id/search_src_text",
                "com.whatsapp:id/contactpicker_row_name",
                "com.whatsapp:id/selection_check",
                "com.whatsapp:id/send"
            ),
            isPickerWithVerifiedSelection = true,
            hasPickerFab = true
        )
        // Screen after 900ms FAB click is Media Preview
        harness.screenAfterPickerFab = SimulatedScreen(
            visibleIds = listOf("com.whatsapp:id/caption", "com.whatsapp:id/crop_image", "com.whatsapp:id/send"),
            hasValidSendButton = true
        )

        harness.runState3GroupSelectAndTransition()

        assertEquals("Group row clicked once", 1, harness.groupRowClicks.get())
        assertEquals("Picker FAB clicked once", 1, harness.pickerFabClicks.get())
        assertEquals("Final Media Preview Send clicked once", 1, harness.finalSendClicks.get())
        assertFalse("Task must be terminal after Send", AutomationStateManager.isTaskActive())
    }

    @Test
    fun `Test C - Direct Group Chat triggers immediate abort with zero Send or FAB clicks`() = runBlocking {
        // Verify all 4 required live chat resource IDs and 3 content descriptions
        assertTrue(
            WhatsAppAccessibilityService.isLiveChatNodeAttributes(
                "com.whatsapp:id/voice_note_btn", null, true
            )
        )
        assertTrue(
            WhatsAppAccessibilityService.isLiveChatNodeAttributes(
                "com.whatsapp:id/entry", null, true
            )
        )
        assertTrue(
            WhatsAppAccessibilityService.isLiveChatNodeAttributes(
                "com.whatsapp:id/input_layout", null, true
            )
        )
        assertTrue(
            WhatsAppAccessibilityService.isLiveChatNodeAttributes(
                "com.whatsapp:id/conversation_contact_name", null, true
            )
        )
        assertTrue(
            WhatsAppAccessibilityService.isLiveChatNodeAttributes(
                null, "Voice message", true
            )
        )
        assertTrue(
            WhatsAppAccessibilityService.isLiveChatNodeAttributes(
                null, "Voice call", true
            )
        )
        assertTrue(
            WhatsAppAccessibilityService.isLiveChatNodeAttributes(
                null, "Video call", true
            )
        )

        val harness = Engine1FlowHarness()
        AutomationStateManager.activateSession("test-C", "Chat Group", "Caption C")
        AutomationStateManager.transitionToState("test-C", UiBotSubState.STATE_3_SELECT_GROUP)

        harness.screenAfterGroupSelect = SimulatedScreen(
            visibleIds = listOf("com.whatsapp:id/voice_note_btn", "com.whatsapp:id/entry"),
            isLiveChat = true,
            hasValidSendButton = true
        )

        harness.runState3GroupSelectAndTransition()

        assertEquals("Picker FAB must not be clicked on Live Chat", 0, harness.pickerFabClicks.get())
        assertEquals("Final Send must not be clicked on Live Chat", 0, harness.finalSendClicks.get())
        assertTrue("Live chat safety guard must trigger", harness.liveChatAborted)
        assertFalse("AutomationStateManager must be cleared", AutomationStateManager.isTaskActive())
    }

    @Test
    fun `Test D and E - Duplicate Accessibility Events and Polling Race dispatch Send only once`() = runBlocking {
        val harness = Engine1FlowHarness()
        AutomationStateManager.activateSession("test-DE", "Race Group", "Caption DE")
        AutomationStateManager.transitionToState("test-DE", UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND)

        val previewScreen = SimulatedScreen(
            visibleIds = listOf("com.whatsapp:id/caption", "com.whatsapp:id/send"),
            hasValidSendButton = true
        )

        // Launch 20 concurrent accessibility event + polling inspections racing on Media Preview
        val jobs = (1..20).map {
            async(Dispatchers.Default) {
                harness.inspectMediaPreviewSynchronized(previewScreen, "test-DE")
            }
        }
        jobs.awaitAll()

        assertEquals(
            "Mutex + AtomicBoolean must permit only 1 final Send click across all concurrent events",
            1,
            harness.finalSendClicks.get()
        )
        assertFalse("Task must be inactive after single send", AutomationStateManager.isTaskActive())
    }

    @Test
    fun `Test F - Post-Send transition to Group Chat ignores subsequent events`() = runBlocking {
        val harness = Engine1FlowHarness()
        AutomationStateManager.activateSession("test-F", "PostSend Group", "Caption F")
        AutomationStateManager.transitionToState("test-F", UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND)

        val previewScreen = SimulatedScreen(
            visibleIds = listOf("com.whatsapp:id/caption", "com.whatsapp:id/send"),
            hasValidSendButton = true
        )
        harness.inspectMediaPreviewSynchronized(previewScreen, "test-F")
        assertEquals(1, harness.finalSendClicks.get())

        // Now WhatsApp transitions to Live Group Chat with voice_note_btn and another event arrives
        val liveChatScreen = SimulatedScreen(
            visibleIds = listOf("com.whatsapp:id/voice_note_btn", "com.whatsapp:id/entry"),
            isLiveChat = true,
            hasValidSendButton = true
        )
        harness.inspectMediaPreviewSynchronized(liveChatScreen, "test-F")

        assertEquals("No second click may occur after post-send transition", 1, harness.finalSendClicks.get())
    }

    @Test
    fun `Test G - Media Preview with missing Send node performs zero clicks and no coordinate fallback`() = runBlocking {
        val harness = Engine1FlowHarness()
        AutomationStateManager.activateSession("test-G", "MissingSend Group", "Caption G")
        AutomationStateManager.transitionToState("test-G", UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND)

        val previewWithoutSend = SimulatedScreen(
            visibleIds = listOf("com.whatsapp:id/caption", "com.whatsapp:id/crop_image"),
            hasValidSendButton = false
        )
        harness.inspectMediaPreviewSynchronized(previewWithoutSend, "test-G")

        assertEquals("Zero clicks when Send node is missing", 0, harness.finalSendClicks.get())
        assertEquals("Coordinate fallback must never be used", 0, harness.coordinateFallbacks.get())
    }

    @Test
    fun `Test H - Blacklisted voice, mic, call, audio, video, and record nodes are rejected`() {
        // Blacklisted viewIdResourceName substrings
        assertFalse(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/voice_note_btn", "Send"))
        assertFalse(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/mic_button", "Send"))
        assertFalse(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/call_btn", "Send"))
        assertFalse(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/audio_send", "Send"))
        assertFalse(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/video_send", "Send"))

        // Blacklisted contentDescription substrings
        assertFalse(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/send", "Voice message"))
        assertFalse(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/send", "Record audio"))
        assertFalse(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/send", "Voice call"))
        assertFalse(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/send", "Audio message"))

        // Allowed genuine Send buttons
        assertTrue(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/send", "Send"))
        assertTrue(WhatsAppAccessibilityService.isAllowedSendCandidate("com.whatsapp:id/send_media_btn", null))
        assertTrue(WhatsAppAccessibilityService.isAllowedSendCandidate(null, "  send "))
        assertFalse(WhatsAppAccessibilityService.isAllowedSendCandidate(null, "Send message to group"))
    }

    @Test
    fun `Test I - Unexpected screen after group selection aborts safely without clicking`() = runBlocking {
        val harness = Engine1FlowHarness()
        AutomationStateManager.activateSession("test-I", "Unknown Group", "Caption I")
        AutomationStateManager.transitionToState("test-I", UiBotSubState.STATE_3_SELECT_GROUP)

        harness.screenAfterGroupSelect = SimulatedScreen(
            visibleIds = listOf("com.whatsapp:id/unknown_dialog_view"),
            isLiveChat = false,
            isPickerWithVerifiedSelection = false,
            hasValidSendButton = false
        )

        harness.runState3GroupSelectAndTransition()

        assertEquals("Group row clicked in STATE_3", 1, harness.groupRowClicks.get())
        assertEquals("Picker FAB must not be clicked on unknown screen", 0, harness.pickerFabClicks.get())
        assertEquals("Send button must not be clicked on unknown screen", 0, harness.finalSendClicks.get())
        assertTrue("Unknown screen must trigger safe abort", harness.unknownScreenAborted)
        assertFalse("Session must be cleared on safe abort", AutomationStateManager.isTaskActive())
    }

    // =========================================================================
    // SIMULATION HARNESS MIRRORING WhatsAppAccessibilityService STATE FLOW
    // =========================================================================

    private data class SimulatedScreen(
        val visibleIds: List<String>,
        val isLiveChat: Boolean = false,
        val isPickerWithVerifiedSelection: Boolean = false,
        val hasPickerFab: Boolean = false,
        val hasValidSendButton: Boolean = false
    ) {
        val hasSearchField: Boolean
            get() = visibleIds.any { it.endsWith(":id/search_src_text", ignoreCase = true) }

        val isDirectMediaPreview: Boolean
            get() = WhatsAppAccessibilityService.isMediaPreviewByResourceIds(
                visibleViewIds = visibleIds,
                hasSearchField = hasSearchField,
                isLiveChat = isLiveChat
            )
    }

    private class Engine1FlowHarness {
        val stepMutex = Mutex()
        val groupRowClicks = AtomicInteger(0)
        val pickerFabClicks = AtomicInteger(0)
        val finalSendClicks = AtomicInteger(0)
        val coordinateFallbacks = AtomicInteger(0)
        var liveChatAborted = false
        var unknownScreenAborted = false

        var screenAfterGroupSelect: SimulatedScreen? = null
        var screenAfterPickerFab: SimulatedScreen? = null

        suspend fun runState3GroupSelectAndTransition() {
            stepMutex.withLock {
                val session = AutomationStateManager.getValidSession() ?: return@withLock
                if (AutomationStateManager.isFinalSendLocked()) return@withLock

                groupRowClicks.incrementAndGet()
                AutomationStateManager.transitionToState(
                    session.taskId,
                    UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND,
                    pickerFabClicked = false
                )

                // Simulate 800ms wait while holding stepMutex
                delay(10L)

                val postSelectScreen = screenAfterGroupSelect ?: return@withLock
                val activeSession = AutomationStateManager.getValidSession() ?: return@withLock

                if (postSelectScreen.isLiveChat) {
                    liveChatAborted = true
                    AutomationStateManager.markTaskCompletedUi(activeSession.taskId)
                    AutomationStateManager.clearSession()
                    return@withLock
                }

                // Case A: Direct Media Preview
                if (postSelectScreen.isDirectMediaPreview && postSelectScreen.hasValidSendButton) {
                    executeSingleShotSendInternal(postSelectScreen, activeSession.taskId)
                    return@withLock
                }

                // Case B: Multi-Select Contact Picker with verified selection
                if (!activeSession.pickerFabClicked &&
                    postSelectScreen.isPickerWithVerifiedSelection &&
                    postSelectScreen.hasPickerFab
                ) {
                    AutomationStateManager.transitionToState(
                        activeSession.taskId,
                        UiBotSubState.STATE_4_PICKER_OR_PREVIEW_SEND,
                        pickerFabClicked = true
                    )
                    pickerFabClicks.incrementAndGet()

                    // Simulate 900ms wait for Media Preview while holding stepMutex
                    delay(10L)

                    val previewScreen = screenAfterPickerFab ?: return@withLock
                    if (previewScreen.isLiveChat) {
                        liveChatAborted = true
                        AutomationStateManager.markTaskCompletedUi(activeSession.taskId)
                        AutomationStateManager.clearSession()
                        return@withLock
                    }

                    if (previewScreen.isDirectMediaPreview &&
                        !previewScreen.hasSearchField &&
                        previewScreen.hasValidSendButton
                    ) {
                        executeSingleShotSendInternal(previewScreen, activeSession.taskId)
                        return@withLock
                    }
                }

                // Case C: Unknown or unexpected screen
                unknownScreenAborted = true
                AutomationStateManager.clearSession()
            }
        }

        suspend fun inspectMediaPreviewSynchronized(screen: SimulatedScreen, taskId: String) {
            if (!AutomationStateManager.isTaskActive()) return
            stepMutex.withLock {
                val session = AutomationStateManager.getValidSession() ?: return@withLock
                if (session.taskId != taskId || session.subState.isTerminal()) return@withLock

                if (screen.isLiveChat) {
                    liveChatAborted = true
                    AutomationStateManager.markTaskCompletedUi(session.taskId)
                    AutomationStateManager.clearSession()
                    return@withLock
                }

                if (screen.isDirectMediaPreview && screen.hasValidSendButton) {
                    executeSingleShotSendInternal(screen, session.taskId)
                }
            }
        }

        private fun executeSingleShotSendInternal(screen: SimulatedScreen, taskId: String) {
            if (screen.isLiveChat || !screen.isDirectMediaPreview || !screen.hasValidSendButton) {
                return
            }
            if (!AutomationStateManager.markFinalSendClicked(taskId)) {
                return
            }
            AutomationStateManager.markTaskCompletedUi(taskId)
            AutomationStateManager.clearSession()
            finalSendClicks.incrementAndGet()
        }
    }
}
