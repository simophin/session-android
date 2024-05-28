package org.thoughtcrime.securesms.conversation.start

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.loki.messenger.databinding.ContactSectionHeaderBinding
import network.loki.messenger.databinding.ViewContactBinding
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.mms.GlideRequests

sealed class ContactListItem {
    class Header(val name: String) : ContactListItem()
    class Contact(val recipient: Recipient, val displayName: String) : ContactListItem()
}

class ContactListAdapter(
    private val context: Context,
    private val glide: GlideRequests,
    private val listener: (Recipient) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var items = listOf<ContactListItem>()
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    private object ViewType {
        const val Contact = 0
        const val Header = 1
    }

    class ContactViewHolder(private val binding: ViewContactBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: ContactListItem.Contact, glide: GlideRequests, listener: (Recipient) -> Unit) {
            binding.profilePictureView.update(contact.recipient)
            binding.nameTextView.text = contact.displayName
            binding.root.setOnClickListener { listener(contact.recipient) }

            // TODO: When we implement deleting contacts (hide might be safest for now) then probably set a long-click listener here w/ something like:
            /*
            binding.root.setOnLongClickListener {
                Log.w("[ACL]", "Long clicked on contact ${contact.recipient.name}")
                binding.contentView.context.showSessionDialog {
                    title("Delete Contact")
                    text("Are you sure you want to delete this contact?")
                    button(R.string.delete) {
                        val contacts = configFactory.contacts ?: return
                        contacts.upsertContact(contact.recipient.address.serialize()) { priority = PRIORITY_HIDDEN }
                        ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(context)
                        endActionMode()
                    }
                    cancelButton(::endActionMode)
                }
                true
            }
            */
        }

        fun unbind() { binding.profilePictureView.recycle() }
    }

    class HeaderViewHolder(
        private val binding: ContactSectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ContactListItem.Header) {
            with(binding) {
                label.text = item.name
            }
        }
    }

    override fun getItemCount(): Int { return items.size }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ContactViewHolder) { holder.unbind() }
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ContactListItem.Header -> ViewType.Header
            else -> ViewType.Contact
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == ViewType.Contact) {
            ContactViewHolder(ViewContactBinding.inflate(LayoutInflater.from(context), parent, false))
        } else {
            HeaderViewHolder(ContactSectionHeaderBinding.inflate(LayoutInflater.from(context), parent, false))
        }
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (viewHolder is ContactViewHolder) {
            viewHolder.bind(item as ContactListItem.Contact, glide, listener)
        } else if (viewHolder is HeaderViewHolder) {
            viewHolder.bind(item as ContactListItem.Header)
        }
    }

}
