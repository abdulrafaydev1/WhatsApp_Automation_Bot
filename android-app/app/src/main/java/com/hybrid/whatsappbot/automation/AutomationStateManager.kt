package com.hybrid.whatsappbot.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Strict State Machine states for Engine 1 (WhatsApp UI Accessibility Automation).
 */
enum class UiBotSubState {
    IDLE,
    SEARCHING_GROUP,
    SELECTING_GROUP,
    FILLING_CAPTION_AND_SENDING,
    COMPLETED
}

data class ActiveAutomationSession(
    val taskId: String,
    val targetGroupName: String,
    val caption: String,
    val expiryTimestampMillis: Long,
    val subState: UiBotSubState,
    val lastActionTimestampMillis: Long = System.currentTimeMillis()
)

/**
 * Thread-safe singleton managing the 20-second automation lock window.
 *
 * CRITICAL GUARDRAIL:
 * WhatsAppAccessibilityService ignores 100% of events unless:
 * 1. An ActiveAutomationSession exists
 * 2. System.currentTimeMillis() < session.expiryTimestampMillis
 * 3. session.subState != UiBotSubState.COMPLETED
 */
object AutomationStateManager {

    private const val DEFAULT_WINDOW_DURATION_MS = 20_000L

    private val sessionRef = AtomicReference<ActiveAutomationSession?>(null)

    private val _sessionFlow = MutableStateFlow<ActiveAutomationSession?>(null)
    val sessionFlow: StateFlow<ActiveAutomationSession?> = _sessionFlow.asStateFlow()

    /**
     * Activates UI automation for a specific scheduled task with a strict 20-second expiry.
     */
    fun activateSession(
        taskId: String,
        targetGroupName: String,
        caption: String,
        windowDurationMs: Long = DEFAULT_WINDOW_DURATION_MS
    ) {
        val now = System.currentTimeMillis()
        val session = ActiveAutomationSession(
            taskId = taskId,
            targetGroupName = targetGroupName.trim(),
            caption = caption,
            expiryTimestampMillis = now + windowDurationMs,
            subState = UiBotSubState.SEARCHING_GROUP,
            lastActionTimestampMillis = now
        )
        sessionRef.set(session)
        _sessionFlow.value = session
    }

    /**
     * Returns the current session ONLY if it is active, not expired, and not completed.
     * Automatically clears expired sessions to guarantee normal user WhatsApp usage is unaffected.
     */
    fun getValidSession(): ActiveAutomationSession? {
        val current = sessionRef.get() ?: return null
        val now = System.currentTimeMillis()
        if (now >= current.expiryTimestampMillis || current.subState == UiBotSubState.COMPLETED) {
            if (now >= current.expiryTimestampMillis) {
                clearSession()
            }
            return null
        }
        return current
    }

    val isTaskActive: Boolean
        get() = getValidSession() != null

    /**
     * Transitions from SEARCHING_GROUP -> SELECTING_GROUP once the target group name
     * has been injected into WhatsApp's search bar.
     */
    fun transitionToSelectingGroup(taskId: String): Boolean {
        while (true) {
            val current = getValidSession() ?: return false
            if (current.taskId != taskId) return false
            if (current.subState != UiBotSubState.SEARCHING_GROUP) return false

            val updated = current.copy(
                subState = UiBotSubState.SELECTING_GROUP,
                lastActionTimestampMillis = System.currentTimeMillis()
            )
            if (sessionRef.compareAndSet(current, updated)) {
                _sessionFlow.value = updated
                return true
            }
        }
    }

    /**
     * Transitions from SELECTING_GROUP -> FILLING_CAPTION_AND_SENDING once the target
     * group row in the picker has been clicked.
     */
    fun transitionToCaptionAndSend(taskId: String): Boolean {
        while (true) {
            val current = getValidSession() ?: return false
            if (current.taskId != taskId) return false
            if (current.subState != UiBotSubState.SELECTING_GROUP &&
                current.subState != UiBotSubState.SEARCHING_GROUP
            ) {
                return false
            }

            val updated = current.copy(
                subState = UiBotSubState.FILLING_CAPTION_AND_SENDING,
                lastActionTimestampMillis = System.currentTimeMillis()
            )
            if (sessionRef.compareAndSet(current, updated)) {
                _sessionFlow.value = updated
                return true
            }
        }
    }

    /**
     * CRITICAL ANTI-LOOP LOCK:
     * Atomically transitions subState to COMPLETED *before* the Send button click is dispatched.
     * Guarantees the Send button can never be clicked more than once per task.
     */
    fun tryLockForFinalSendClick(taskId: String): Boolean {
        while (true) {
            val current = getValidSession() ?: return false
            if (current.taskId != taskId) return false
            if (current.subState == UiBotSubState.COMPLETED) return false

            val updated = current.copy(
                subState = UiBotSubState.COMPLETED,
                lastActionTimestampMillis = System.currentTimeMillis()
            )
            if (sessionRef.compareAndSet(current, updated)) {
                _sessionFlow.value = updated
                return true
            }
        }
    }

    /**
     * Completely clears the active automation state.
     */
    fun clearSession() {
        sessionRef.set(null)
        _sessionFlow.value = null
    }
}
