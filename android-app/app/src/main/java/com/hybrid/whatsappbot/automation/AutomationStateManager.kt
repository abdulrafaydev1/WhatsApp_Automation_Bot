package com.hybrid.whatsappbot.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Sequential State Machine for WhatsApp Media Share Automation (Engine 1).
 */
enum class UiBotSubState {
    IDLE,
    STATE_1_CLICK_SEARCH,
    STATE_2_TYPE_QUERY,
    STATE_3_SELECT_GROUP,
    STATE_4_PICKER_OR_PREVIEW_SEND,
    COMPLETED_UI,
    STATE_5_DONE;

    fun isTerminal(): Boolean {
        return this == IDLE || this == COMPLETED_UI || this == STATE_5_DONE
    }

    fun canDispatchFinalSend(): Boolean {
        return this == STATE_3_SELECT_GROUP || this == STATE_4_PICKER_OR_PREVIEW_SEND
    }
}

data class ActiveAutomationSession(
    val taskId: String,
    val generationId: Long,
    val targetGroupName: String,
    val caption: String,
    val expiryTimestampMillis: Long,
    val subState: UiBotSubState,
    val stateEnteredAtMillis: Long = System.currentTimeMillis(),
    val attemptCountInState: Int = 0,
    val pickerFabClicked: Boolean = false
)

/**
 * Thread-safe singleton managing the 20-second automation lock window,
 * task generation lifecycle, and per-task single-shot AtomicBoolean protection.
 */
object AutomationStateManager {

    private const val DEFAULT_WINDOW_DURATION_MS = 20_000L

    private val sessionRef = AtomicReference<ActiveAutomationSession?>(null)
    private val lastActivityMillis = AtomicLong(0L)
    private val generationCounter = AtomicLong(0L)

    /**
     * Per-task atomic single-shot Send protection lock.
     * Initialized to false when a new task starts; once acquired (true), it is never
     * reset during STATE_3, STATE_4, screen transitions, or task cleanup.
     */
    private val finalSendClicked = AtomicBoolean(false)

    private val _sessionFlow = MutableStateFlow<ActiveAutomationSession?>(null)
    val sessionFlow: StateFlow<ActiveAutomationSession?> = _sessionFlow.asStateFlow()

    /**
     * Activates UI automation for a genuinely new scheduled task with a fresh, unconsumed
     * finalSendClicked lock and a unique generation identifier.
     */
    fun activateSession(
        taskId: String,
        targetGroupName: String,
        caption: String,
        windowDurationMs: Long = DEFAULT_WINDOW_DURATION_MS
    ) {
        val now = System.currentTimeMillis()
        val genId = generationCounter.incrementAndGet()
        lastActivityMillis.set(now)

        val session = ActiveAutomationSession(
            taskId = taskId,
            generationId = genId,
            targetGroupName = targetGroupName.trim(),
            caption = caption,
            expiryTimestampMillis = now + windowDurationMs,
            subState = UiBotSubState.STATE_1_CLICK_SEARCH,
            stateEnteredAtMillis = now,
            attemptCountInState = 0,
            pickerFabClicked = false
        )
        sessionRef.set(session)
        // Reset the single-shot send lock ONLY when a genuinely new task is initialized
        finalSendClicked.set(false)
        _sessionFlow.value = session
    }

    /**
     * Single-shot atomic lock acquisition before dispatching the final Media Preview Send click.
     * Returns true at most once per task lifecycle.
     */
    fun markFinalSendClicked(): Boolean {
        val current = getValidSession() ?: return false
        if (!current.subState.canDispatchFinalSend()) {
            return false
        }
        return finalSendClicked.compareAndSet(false, true)
    }

    /**
     * Task-bound overload verifying that the caller's [expectedTaskId] still matches the
     * currently active task before attempting to acquire [finalSendClicked].
     */
    fun markFinalSendClicked(expectedTaskId: String): Boolean {
        val current = getValidSession() ?: return false
        if (current.taskId != expectedTaskId || !current.subState.canDispatchFinalSend()) {
            return false
        }
        return finalSendClicked.compareAndSet(false, true)
    }

    /**
     * Returns whether the current task's final-send lock has already been consumed.
     */
    fun isFinalSendLocked(): Boolean = finalSendClicked.get()

    /**
     * Marks the active task as COMPLETED_UI (terminal) and consumes the send lock so
     * no subsequent callback, polling tick, or accessibility event can ever interact again.
     */
    fun markTaskCompletedUi(taskId: String? = null): Boolean {
        finalSendClicked.set(true)
        while (true) {
            val current = sessionRef.get() ?: return false
            if (taskId != null && current.taskId != taskId) return false
            if (current.subState.isTerminal()) return false

            val now = System.currentTimeMillis()
            lastActivityMillis.set(now)
            val completedSession = current.copy(
                subState = UiBotSubState.COMPLETED_UI,
                stateEnteredAtMillis = now
            )
            if (sessionRef.compareAndSet(current, completedSession)) {
                _sessionFlow.value = completedSession
                return true
            }
        }
    }

    /**
     * Records a bot send event (UI or Background) so NotificationListenerService ignores
     * self-triggered notifications for the next 15 seconds.
     */
    fun markBotActivityNow() {
        lastActivityMillis.set(System.currentTimeMillis())
    }

    /**
     * Returns true if a task is currently active OR finished within the last [cooldownWindowMs].
     */
    fun wasRecentlyActive(cooldownWindowMs: Long = 15_000L): Boolean {
        if (isTaskActive()) return true
        val last = lastActivityMillis.get()
        if (last <= 0L) return false
        return (System.currentTimeMillis() - last) < cooldownWindowMs
    }

    /**
     * Returns the current session ONLY if it is active, not expired, and not terminal.
     */
    fun getValidSession(): ActiveAutomationSession? {
        val current = sessionRef.get() ?: return null
        val now = System.currentTimeMillis()
        if (now >= current.expiryTimestampMillis || current.subState.isTerminal()) {
            if (now >= current.expiryTimestampMillis) {
                clearSession()
            }
            return null
        }
        return current
    }

    fun isTaskActive(): Boolean = getValidSession() != null

    /**
     * Transitions the active session to a new sequential state and resets attempt counters.
     * Terminal tasks can never transition back to an active or sendable state.
     */
    fun transitionToState(
        taskId: String,
        newState: UiBotSubState,
        pickerFabClicked: Boolean? = null
    ): Boolean {
        while (true) {
            val current = getValidSession() ?: return false
            if (current.taskId != taskId) return false
            if (current.subState.isTerminal()) return false
            if (finalSendClicked.get() && !newState.isTerminal()) return false

            val now = System.currentTimeMillis()
            lastActivityMillis.set(now)
            val updated = current.copy(
                subState = newState,
                stateEnteredAtMillis = now,
                attemptCountInState = 0,
                pickerFabClicked = pickerFabClicked ?: current.pickerFabClicked
            )
            if (sessionRef.compareAndSet(current, updated)) {
                _sessionFlow.value = updated
                return true
            }
        }
    }

    /**
     * Increments the attempt count within the current state.
     */
    fun incrementAttempt(taskId: String, markPickerFabClicked: Boolean? = null): Int {
        while (true) {
            val current = getValidSession() ?: return 0
            if (current.taskId != taskId) return 0
            if (current.subState.isTerminal()) return 0

            val nextAttempt = current.attemptCountInState + 1
            val updated = current.copy(
                attemptCountInState = nextAttempt,
                pickerFabClicked = markPickerFabClicked ?: current.pickerFabClicked
            )
            if (sessionRef.compareAndSet(current, updated)) {
                _sessionFlow.value = updated
                return nextAttempt
            }
        }
    }

    /**
     * Backwards-compatible alias that delegates to [markFinalSendClicked] and marks
     * the session terminal if acquired.
     */
    fun tryLockForFinalSendClick(taskId: String): Boolean {
        if (!markFinalSendClicked(taskId)) {
            return false
        }
        markTaskCompletedUi(taskId)
        return true
    }

    /**
     * Clears the active automation session while keeping [finalSendClicked] consumed (true)
     * so stale callbacks from the cleared task can never acquire the lock afterwards.
     */
    fun clearSession() {
        lastActivityMillis.set(System.currentTimeMillis())
        finalSendClicked.set(true)
        sessionRef.set(null)
        _sessionFlow.value = null
    }
}
