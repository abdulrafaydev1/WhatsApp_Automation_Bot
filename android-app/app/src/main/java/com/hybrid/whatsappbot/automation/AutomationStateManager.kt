package com.hybrid.whatsappbot.automation

import android.content.Context
import com.hybrid.whatsappbot.data.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Sequential State Machine for WhatsApp Media Share + 3-Step Previous Post Auto-Deletion.
 */
enum class UiBotSubState {
    IDLE,
    STATE_1_CLICK_SEARCH,
    STATE_2_TYPE_QUERY,
    STATE_3_SELECT_GROUP,
    STATE_4_PICKER_OR_PREVIEW_SEND,
    STATE_5A_FIND_OLD_POST,
    STATE_5B_CLICK_TRASH_ICON,
    STATE_5C_CLICK_DELETE_FOR_EVERYONE,
    STATE_6_DONE
}

data class ActiveAutomationSession(
    val taskId: String,
    val targetGroupName: String,
    val baseCaption: String,
    val taggedCaption: String,
    val currentTag: String,
    val previousTag: String,
    val previousCaptionSnippet: String,
    val expiryTimestampMillis: Long,
    val subState: UiBotSubState,
    val stateEnteredAtMillis: Long = System.currentTimeMillis(),
    val attemptCountInState: Int = 0,
    val scrollUpCount: Int = 0,
    val pickerFabClicked: Boolean = false
)

/**
 * Thread-safe singleton managing the automation lock window, single-shot send lock,
 * and alternating invisible zero-width Unicode tags (TAG_A / TAG_B) to distinguish
 * the 1st (older) post from the 2nd (newly sent) post in the same group.
 */
object AutomationStateManager {

    private const val DEFAULT_WINDOW_DURATION_MS = 25_000L

    // Alternating invisible zero-width Unicode signatures appended to captions
    const val TAG_A = "\u200B\u200C\u200B"
    const val TAG_B = "\u200C\u200B\u200C"

    private val sessionRef = AtomicReference<ActiveAutomationSession?>(null)
    private val finalSendClicked = AtomicBoolean(false)
    private val lastActivityMillis = AtomicLong(0L)

    private val activeGroupCaptionMap = ConcurrentHashMap<String, String>()
    private val activeGroupTagMap = ConcurrentHashMap<String, String>()

    private val _sessionFlow = MutableStateFlow<ActiveAutomationSession?>(null)
    val sessionFlow: StateFlow<ActiveAutomationSession?> = _sessionFlow.asStateFlow()

    /**
     * Strips any existing zero-width tags from [caption].
     */
    fun stripZeroWidthTags(caption: String): String =
        caption.replace(TAG_A, "").replace(TAG_B, "").trim()

    /**
     * Appends the alternating invisible zero-width [tag] to [baseCaption].
     */
    fun buildTaggedCaption(baseCaption: String, tag: String): String {
        val clean = stripZeroWidthTags(baseCaption)
        return if (clean.isEmpty()) tag else "$clean$tag"
    }

    /**
     * Returns the last invisible tag (TAG_A or TAG_B) sent in [groupName], or empty string if none.
     */
    fun getLastSentTag(context: Context, groupName: String): String {
        val key = groupName.trim().lowercase()
        if (key.isEmpty()) return ""
        return activeGroupTagMap[key] ?: AppPreferences(context).getLastSentTag(groupName).also {
            if (it.isNotEmpty()) activeGroupTagMap[key] = it
        }
    }

    /**
     * Returns the opposite zero-width tag from the last one sent in [groupName].
     */
    fun computeNextTagForGroup(context: Context, groupName: String): String {
        val prevTag = getLastSentTag(context, groupName)
        return if (prevTag == TAG_A) TAG_B else TAG_A
    }

    /**
     * Checks if our bot has already sent a previous post in [groupName].
     */
    fun hasPreviousPostInGroup(context: Context, groupName: String): Boolean {
        val key = groupName.trim().lowercase()
        if (key.isEmpty()) return false
        if (activeGroupTagMap.containsKey(key) || activeGroupCaptionMap.containsKey(key)) {
            return true
        }
        val persisted = AppPreferences(context).hasPreviousPostInGroup(groupName)
        if (persisted) {
            val snippet = AppPreferences(context).getLastSentCaptionSnippet(groupName)
            val tag = AppPreferences(context).getLastSentTag(groupName)
            activeGroupCaptionMap[key] = snippet
            if (tag.isNotEmpty()) activeGroupTagMap[key] = tag
        }
        return persisted
    }

    /**
     * Returns the base caption snippet of the last post sent by our bot in [groupName].
     */
    fun lastSentCaptionSnippet(context: Context, groupName: String): String {
        val key = groupName.trim().lowercase()
        return activeGroupCaptionMap[key]
            ?: AppPreferences(context).getLastSentCaptionSnippet(groupName)
    }

    /**
     * Records that our bot has sent a post in [groupName] with [baseCaption] and [sentTag].
     */
    fun markPostSentInGroup(
        context: Context,
        groupName: String,
        baseCaption: String,
        sentTag: String = TAG_A
    ) {
        val key = groupName.trim().lowercase()
        if (key.isEmpty()) return
        val cleanBase = stripZeroWidthTags(baseCaption)
        val snippet = cleanBase.lineSequence().firstOrNull()?.take(40)?.trim()
            ?: cleanBase.take(40)
        activeGroupCaptionMap[key] = snippet
        activeGroupTagMap[key] = sentTag
        AppPreferences(context).markPostSentInGroup(groupName, cleanBase, sentTag)
    }

    /**
     * Activates UI automation for a specific scheduled task and prepares the alternating
     * zero-width tag for distinguishing the new post from any previous post.
     */
    fun activateSession(
        context: Context,
        taskId: String,
        targetGroupName: String,
        caption: String,
        windowDurationMs: Long = DEFAULT_WINDOW_DURATION_MS
    ): ActiveAutomationSession {
        val now = System.currentTimeMillis()
        finalSendClicked.set(false)
        lastActivityMillis.set(now)

        val cleanBaseCaption = stripZeroWidthTags(caption)
        val prevTag = getLastSentTag(context, targetGroupName)
        val prevSnippet = lastSentCaptionSnippet(context, targetGroupName)
        val nextTag = computeNextTagForGroup(context, targetGroupName)
        val taggedCaption = buildTaggedCaption(cleanBaseCaption, nextTag)

        val session = ActiveAutomationSession(
            taskId = taskId,
            targetGroupName = targetGroupName.trim(),
            baseCaption = cleanBaseCaption,
            taggedCaption = taggedCaption,
            currentTag = nextTag,
            previousTag = prevTag,
            previousCaptionSnippet = prevSnippet,
            expiryTimestampMillis = now + windowDurationMs,
            subState = UiBotSubState.STATE_1_CLICK_SEARCH,
            stateEnteredAtMillis = now,
            attemptCountInState = 0,
            scrollUpCount = 0,
            pickerFabClicked = false
        )
        sessionRef.set(session)
        _sessionFlow.value = session
        return session
    }

    fun markBotActivityNow() {
        lastActivityMillis.set(System.currentTimeMillis())
    }

    fun wasRecentlyActive(cooldownWindowMs: Long = 15_000L): Boolean {
        if (isTaskActive()) return true
        val last = lastActivityMillis.get()
        if (last <= 0L) return false
        return (System.currentTimeMillis() - last) < cooldownWindowMs
    }

    fun getValidSession(): ActiveAutomationSession? {
        val current = sessionRef.get() ?: return null
        val now = System.currentTimeMillis()
        if (now >= current.expiryTimestampMillis || current.subState == UiBotSubState.STATE_6_DONE) {
            if (now >= current.expiryTimestampMillis) {
                clearSession()
            }
            return null
        }
        return current
    }

    fun isTaskActive(): Boolean = getValidSession() != null

    fun isFinalSendAlreadyClicked(): Boolean = finalSendClicked.get()

    /**
     * Transitions the active session to a new sequential state, optionally extending expiry.
     */
    fun transitionToState(
        taskId: String,
        newState: UiBotSubState,
        extendExpiryMillis: Long = 0L
    ): Boolean {
        while (true) {
            val current = getValidSession() ?: return false
            if (current.taskId != taskId) return false
            if (current.subState == UiBotSubState.STATE_6_DONE) return false

            val now = System.currentTimeMillis()
            lastActivityMillis.set(now)
            val newExpiry = if (extendExpiryMillis > 0L) {
                maxOf(current.expiryTimestampMillis, now + extendExpiryMillis)
            } else {
                current.expiryTimestampMillis
            }
            val updated = current.copy(
                subState = newState,
                expiryTimestampMillis = newExpiry,
                stateEnteredAtMillis = now,
                attemptCountInState = 0
            )
            if (sessionRef.compareAndSet(current, updated)) {
                _sessionFlow.value = updated
                return true
            }
        }
    }

    fun incrementAttempt(taskId: String, markPickerFabClicked: Boolean? = null): Int {
        while (true) {
            val current = getValidSession() ?: return 0
            if (current.taskId != taskId) return 0

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

    fun incrementScrollUpCount(taskId: String): Int {
        while (true) {
            val current = getValidSession() ?: return 0
            if (current.taskId != taskId) return 0

            val nextScroll = current.scrollUpCount + 1
            val updated = current.copy(scrollUpCount = nextScroll)
            if (sessionRef.compareAndSet(current, updated)) {
                _sessionFlow.value = updated
                return nextScroll
            }
        }
    }

    /**
     * CRITICAL ATOMIC SINGLE-SHOT SEND LOCK:
     * Ensures the Media Preview Send button is clicked at most once per task.
     */
    fun markFinalSendClicked(): Boolean {
        if (!finalSendClicked.compareAndSet(false, true)) {
            return false
        }
        lastActivityMillis.set(System.currentTimeMillis())
        return true
    }

    /**
     * Completely clears the active automation state and marks STATE_6_DONE.
     */
    fun clearSession() {
        val now = System.currentTimeMillis()
        lastActivityMillis.set(now)
        val current = sessionRef.get()
        if (current != null && current.subState != UiBotSubState.STATE_6_DONE) {
            sessionRef.set(current.copy(subState = UiBotSubState.STATE_6_DONE, stateEnteredAtMillis = now))
        }
        sessionRef.set(null)
        _sessionFlow.value = null
    }
}
