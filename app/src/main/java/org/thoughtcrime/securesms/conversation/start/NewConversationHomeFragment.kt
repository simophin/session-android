package org.thoughtcrime.securesms.conversation.start

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.FragmentNewConversationHomeBinding
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.mms.GlideApp
import javax.inject.Inject

@AndroidEntryPoint
class NewConversationHomeFragment : Fragment() {

    private lateinit var binding: FragmentNewConversationHomeBinding
    private val viewModel: NewConversationHomeViewModel by viewModels()

    @Inject
    lateinit var textSecurePreferences: TextSecurePreferences

    lateinit var delegate: NewConversationDelegate

    lateinit var adapter: ContactListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter = ContactListAdapter(requireContext(), GlideApp.with(requireContext())) {
            delegate.onContactSelected(it.address.serialize())
        }

        viewModel.recipientsGroups
                .observe(this) { groups ->
                    adapter.items = groups
                            .asSequence()
                            .flatMap { group ->
                                sequenceOf(ContactListItem.Header(group.title)) +
                                        group.recipients.asSequence().map {
                                            ContactListItem.Contact(it.recipient, it.displayName)
                                        }
                            }
                            .toList()
                }
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        binding = FragmentNewConversationHomeBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.closeButton.setOnClickListener { delegate.onDialogClosePressed() }
        binding.createPrivateChatButton.setOnClickListener { delegate.onNewMessageSelected() }
        binding.createClosedGroupButton.setOnClickListener { delegate.onCreateGroupSelected() }
        binding.joinCommunityButton.setOnClickListener { delegate.onJoinCommunitySelected() }

        binding.contactsRecyclerView.adapter = adapter
        val divider = ContextCompat.getDrawable(requireActivity(), R.drawable.conversation_menu_divider)!!.let {
            DividerItemDecoration(requireActivity(), RecyclerView.VERTICAL).apply {
                setDrawable(it)
            }
        }
        binding.contactsRecyclerView.addItemDecoration(divider)
    }
}