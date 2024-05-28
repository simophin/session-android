package org.thoughtcrime.securesms.conversation.v2

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.util.SparseArray
import android.util.SparseBooleanArray
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.WorkerThread
import androidx.core.util.getOrDefault
import androidx.core.util.set
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewVisibleMessageBinding
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.conversation.v2.messages.ControlMessageView
import org.thoughtcrime.securesms.conversation.v2.messages.VisibleMessageView
import org.thoughtcrime.securesms.conversation.v2.messages.VisibleMessageViewDelegate
import org.thoughtcrime.securesms.database.CursorRecyclerViewAdapter
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.mms.GlideRequests
import org.thoughtcrime.securesms.preferences.PrivacySettingsActivity
import org.thoughtcrime.securesms.showSessionDialog
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class ConversationAdapter(
        context: Context,
        cursor: Cursor,
        originalLastSeen: Long,
        private val isReversed: Boolean,
        private val onItemPress: (MessageRecord, Int, VisibleMessageView, MotionEvent) -> Unit,
        private val onItemSwipeToReply: (MessageRecord, Int) -> Unit,
        private val onItemLongPress: (MessageRecord, Int, VisibleMessageView) -> Unit,
        private val onDeselect: (MessageRecord, Int) -> Unit,
        private val onAttachmentNeedsDownload: (Attachment) -> Unit,
        private val onVisibleMessageBound: (MessageRecord) -> Unit,
        private val glide: GlideRequests,
        lifecycleCoroutineScope: LifecycleCoroutineScope
) : CursorRecyclerViewAdapter<ViewHolder>(context, cursor) {
    private val messageDB by lazy { DatabaseComponent.get(context).mmsSmsDatabase() }
    private val contactDB by lazy { DatabaseComponent.get(context).sessionContactDatabase() }
    var selectedItems = mutableSetOf<MessageRecord>()
    private var searchQuery: String? = null
    var visibleMessageViewDelegate: VisibleMessageViewDelegate? = null

    private val updateQueue = Channel<String>(1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val contactCache = SparseArray<Contact>(100)
    private val contactLoadedCache = SparseBooleanArray(100)
    private val lastSeen = AtomicLong(originalLastSeen)
    private var lastSentMessageId: Long = -1L

    init {
        lifecycleCoroutineScope.launch(IO) {
            while (isActive) {
                val item = updateQueue.receive()
                val contact = getSenderInfo(item) ?: continue
                contactCache[item.hashCode()] = contact
                contactLoadedCache[item.hashCode()] = true
            }
        }
    }

    @WorkerThread
    private fun getSenderInfo(sender: String): Contact? {
        return contactDB.getContactWithSessionID(sender)
    }

    sealed class ViewType(val rawValue: Int) {
        object Visible : ViewType(0)
        object Control : ViewType(1)

        companion object {

            val allValues: Map<Int, ViewType> get() = mapOf(
                Visible.rawValue to Visible,
                Control.rawValue to Control
            )
        }
    }

    class VisibleMessageViewHolder(val view: View) : ViewHolder(view)
    class ControlMessageViewHolder(val view: ControlMessageView) : ViewHolder(view)

    override fun getItemViewType(cursor: Cursor): Int {
        val message = getMessage(cursor)!!
        if (message.isControlMessage) { return ViewType.Control.rawValue }
        return ViewType.Visible.rawValue
    }

    override fun onCreateItemViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        @Suppress("NAME_SHADOWING")
        val viewType = ViewType.allValues[viewType]
        return when (viewType) {
            ViewType.Visible -> VisibleMessageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.view_visible_message, parent, false))
            ViewType.Control -> ControlMessageViewHolder(ControlMessageView(context))
            else -> throw IllegalStateException("Unexpected view type: $viewType.")
        }
    }

    override fun onBindItemViewHolder(viewHolder: ViewHolder, cursor: Cursor) {
        val message = getMessage(cursor)!!
        val position = viewHolder.adapterPosition
        val messageBefore = getMessageBefore(position, cursor)
        when (viewHolder) {
            is VisibleMessageViewHolder -> {
                val visibleMessageView = ViewVisibleMessageBinding.bind(viewHolder.view).visibleMessageView
                val isSelected = selectedItems.contains(message)
                visibleMessageView.snIsSelected = isSelected
                visibleMessageView.indexInAdapter = position
                val senderId = message.individualRecipient.address.serialize()
                val senderIdHash = senderId.hashCode()
                updateQueue.trySend(senderId)
                if (contactCache[senderIdHash] == null && !contactLoadedCache.getOrDefault(senderIdHash, false)) {
                    getSenderInfo(senderId)?.let { contact ->
                        contactCache[senderIdHash] = contact
                    }
                }
                val contact = contactCache[senderIdHash]

                visibleMessageView.bind(
                        message,
                        messageBefore,
                        getMessageAfter(position, cursor),
                        glide,
                        searchQuery,
                        contact,
                        senderId,
                        lastSeen.get(),
                        visibleMessageViewDelegate,
                        onAttachmentNeedsDownload,
                        lastSentMessageId
                )

                onVisibleMessageBound(message)

                if (!message.isDeleted) {
                    visibleMessageView.onPress = { event -> onItemPress(message, viewHolder.adapterPosition, visibleMessageView, event) }
                    visibleMessageView.onSwipeToReply = { onItemSwipeToReply(message, viewHolder.adapterPosition) }
                    visibleMessageView.onLongPress = { onItemLongPress(message, viewHolder.adapterPosition, visibleMessageView) }
                } else {
                    visibleMessageView.onPress = null
                    visibleMessageView.onSwipeToReply = null
                    visibleMessageView.onLongPress = null
                }
            }
            is ControlMessageViewHolder -> {
                viewHolder.view.bind(message, messageBefore)
                if (message.isCallLog && message.isFirstMissedCall) {
                    viewHolder.view.setOnClickListener {
                        context.showSessionDialog {
                            title(R.string.CallNotificationBuilder_first_call_title)
                            text(R.string.CallNotificationBuilder_first_call_message)
                            button(R.string.activity_settings_title) {
                                Intent(context, PrivacySettingsActivity::class.java)
                                    .let(context::startActivity)
                            }
                            cancelButton()
                        }
                    }
                } else {
                    viewHolder.view.setOnClickListener(null)
                }
            }
        }
    }

    fun toggleSelection(message: MessageRecord, position: Int) {
        if (selectedItems.contains(message)) selectedItems.remove(message) else selectedItems.add(message)
        notifyItemChanged(position)
    }

    override fun onItemViewRecycled(viewHolder: ViewHolder?) {
        when (viewHolder) {
            is VisibleMessageViewHolder -> viewHolder.view.findViewById<VisibleMessageView>(R.id.visibleMessageView).recycle()
            is ControlMessageViewHolder -> viewHolder.view.recycle()
        }
        super.onItemViewRecycled(viewHolder)
    }

    private fun getMessage(cursor: Cursor): MessageRecord? {
        return messageDB.readerFor(cursor).current
    }

    private fun getMessageBefore(position: Int, cursor: Cursor): MessageRecord? {
        // The message that's visually before the current one is actually after the current
        // one for the cursor because the layout is reversed
        if (isReversed && !cursor.moveToPosition(position + 1)) { return null }
        if (!isReversed && !cursor.moveToPosition(position - 1)) { return null }

        return messageDB.readerFor(cursor).current
    }

    private fun getMessageAfter(position: Int, cursor: Cursor): MessageRecord? {
        // The message that's visually after the current one is actually before the current
        // one for the cursor because the layout is reversed
        if (isReversed && !cursor.moveToPosition(position - 1)) { return null }
        if (!isReversed && !cursor.moveToPosition(position + 1)) { return null }

        return messageDB.readerFor(cursor).current
    }

    override fun changeCursor(cursor: Cursor?) {
        super.changeCursor(cursor)

        val toRemove = mutableSetOf<MessageRecord>()
        val toDeselect = mutableSetOf<Pair<Int, MessageRecord>>()
        for (selected in selectedItems) {
            val position = getItemPositionForTimestamp(selected.timestamp)
            if (position == null || position == -1) {
                toRemove += selected
            } else {
                val item = getMessage(getCursorAtPositionOrThrow(position))
                if (item == null || item.isDeleted) {
                    toDeselect += position to selected
                }
            }
        }
        selectedItems -= toRemove
        toDeselect.iterator().forEach { (pos, record) ->
            onDeselect(record, pos)
        }
    }

    fun findLastSeenItemPosition(lastSeenTimestamp: Long): Int? {
        val cursor = this.cursor
        if (cursor == null || !isActiveCursor) return null
        if (lastSeenTimestamp == 0L) {
            if (isReversed && cursor.moveToLast()) { return cursor.position }
            if (!isReversed && cursor.moveToFirst()) { return cursor.position }
        }

        // Loop from the newest message to the oldest until we find one older (or equal to)
        // the lastSeenTimestamp, then return that message index
        for (i in 0 until itemCount) {
            if (isReversed) {
                cursor.moveToPosition(i)
                val (outgoing, dateSent) = messageDB.timestampAndDirectionForCurrent(cursor)
                if (outgoing || dateSent <= lastSeenTimestamp) {
                    return i
                }
            }
            else {
                val index = ((itemCount - 1) - i)
                cursor.moveToPosition(index)
                val (outgoing, dateSent) = messageDB.timestampAndDirectionForCurrent(cursor)
                if (outgoing || dateSent <= lastSeenTimestamp) {
                    return min(itemCount - 1, (index + 1))
                }
            }
        }
        return null
    }

    fun getItemPositionForTimestamp(timestamp: Long): Int? {
        val cursor = this.cursor
        if (timestamp <= 0L || cursor == null || !isActiveCursor) return null
        for (i in 0 until itemCount) {
            cursor.moveToPosition(i)
            val (_, dateSent) = messageDB.timestampAndDirectionForCurrent(cursor)
            if (dateSent == timestamp) { return i }
        }
        return null
    }

    fun onSearchQueryUpdated(query: String?) {
        this.searchQuery = query
        notifyDataSetChanged()
    }

    fun getTimestampForItemAt(firstVisiblePosition: Int): Long? {
        val cursor = this.cursor ?: return null
        if (!cursor.moveToPosition(firstVisiblePosition)) return null
        val message = messageDB.readerFor(cursor).current ?: return null
        return message.timestamp
    }
}