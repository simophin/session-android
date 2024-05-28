package org.thoughtcrime.securesms.conversation.v2

import android.Manifest
import android.animation.FloatEvaluator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.database.Cursor
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.SpannedString
import android.text.TextUtils
import android.text.style.StyleSpan
import android.util.Pair
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.text.set
import androidx.core.text.toSpannable
import androidx.core.view.drawToBitmap
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.annimon.stream.Stream
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivityConversationV2Binding
import network.loki.messenger.databinding.ViewVisibleMessageBinding
import network.loki.messenger.libsession_util.util.ExpiryMode
import nl.komponents.kovenant.ui.successUi
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.mentions.Mention
import org.session.libsession.messaging.mentions.MentionsManager
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.messaging.messages.applyExpiryMode
import org.session.libsession.messaging.messages.control.DataExtractionNotification
import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage
import org.session.libsession.messaging.messages.visible.Reaction
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.messaging.utilities.SessionId
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.MediaTypes
import org.session.libsession.utilities.Stub
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.concurrent.SimpleTask
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.RecipientModifiedListener
import org.session.libsignal.crypto.MnemonicCodec
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.ListenableFuture
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.session.libsignal.utilities.hexEncodedPrivateKey
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.attachments.ScreenshotObserver
import org.thoughtcrime.securesms.audio.AudioRecorder
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel
import org.thoughtcrime.securesms.contacts.SelectContactsActivity.Companion.selectedContactsKey
import org.thoughtcrime.securesms.conversation.ConversationActionBarDelegate
import org.thoughtcrime.securesms.conversation.disappearingmessages.DisappearingMessagesActivity
import org.thoughtcrime.securesms.conversation.v2.ConversationReactionOverlay.OnActionSelectedListener
import org.thoughtcrime.securesms.conversation.v2.ConversationReactionOverlay.OnReactionSelectedListener
import org.thoughtcrime.securesms.conversation.v2.MessageDetailActivity.Companion.MESSAGE_TIMESTAMP
import org.thoughtcrime.securesms.conversation.v2.MessageDetailActivity.Companion.ON_DELETE
import org.thoughtcrime.securesms.conversation.v2.MessageDetailActivity.Companion.ON_REPLY
import org.thoughtcrime.securesms.conversation.v2.MessageDetailActivity.Companion.ON_RESEND
import org.thoughtcrime.securesms.conversation.v2.dialogs.BlockedDialog
import org.thoughtcrime.securesms.conversation.v2.dialogs.LinkPreviewDialog
import org.thoughtcrime.securesms.conversation.v2.dialogs.SendSeedDialog
import org.thoughtcrime.securesms.conversation.v2.input_bar.InputBarButton
import org.thoughtcrime.securesms.conversation.v2.input_bar.InputBarDelegate
import org.thoughtcrime.securesms.conversation.v2.input_bar.InputBarRecordingViewDelegate
import org.thoughtcrime.securesms.conversation.v2.input_bar.mentions.MentionCandidatesView
import org.thoughtcrime.securesms.conversation.v2.menus.ConversationActionModeCallback
import org.thoughtcrime.securesms.conversation.v2.menus.ConversationActionModeCallbackDelegate
import org.thoughtcrime.securesms.conversation.v2.menus.ConversationMenuHelper
import org.thoughtcrime.securesms.conversation.v2.messages.VisibleMessageView
import org.thoughtcrime.securesms.conversation.v2.messages.VisibleMessageViewDelegate
import org.thoughtcrime.securesms.conversation.v2.search.SearchBottomBar
import org.thoughtcrime.securesms.conversation.v2.search.SearchViewModel
import org.thoughtcrime.securesms.conversation.v2.utilities.AttachmentManager
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities
import org.thoughtcrime.securesms.conversation.v2.utilities.ResendMessageUtilities
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.crypto.MnemonicUtilities
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.LokiMessageDatabase
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.ReactionDatabase
import org.thoughtcrime.securesms.database.SessionContactDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.giph.ui.GiphyActivity
import org.thoughtcrime.securesms.groups.OpenGroupManager
import org.thoughtcrime.securesms.linkpreview.LinkPreviewRepository
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil
import org.thoughtcrime.securesms.linkpreview.LinkPreviewViewModel
import org.thoughtcrime.securesms.linkpreview.LinkPreviewViewModel.LinkPreviewState
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.mediasend.MediaSendActivity
import org.thoughtcrime.securesms.mms.AudioSlide
import org.thoughtcrime.securesms.mms.GifSlide
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.mms.VideoSlide
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.reactions.ReactionsDialogFragment
import org.thoughtcrime.securesms.reactions.any.ReactWithAnyEmojiDialogFragment
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.util.ActivityDispatcher
import org.thoughtcrime.securesms.util.ConfigurationMessageUtilities
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.SaveAttachmentTask
import org.thoughtcrime.securesms.util.SimpleTextWatcher
import org.thoughtcrime.securesms.util.isScrolledToBottom
import org.thoughtcrime.securesms.util.isScrolledToWithin30dpOfBottom
import org.thoughtcrime.securesms.util.push
import org.thoughtcrime.securesms.util.show
import org.thoughtcrime.securesms.util.toPx
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "ConversationActivityV2"

// Some things that seemingly belong to the input bar (e.g. the voice message recording UI) are actually
// part of the conversation activity layout. This is just because it makes the layout a lot simpler. The
// price we pay is a bit of back and forth between the input bar and the conversation activity.
@AndroidEntryPoint
class ConversationActivityV2 : PassphraseRequiredActionBarActivity(), InputBarDelegate,
    InputBarRecordingViewDelegate, AttachmentManager.AttachmentListener, ActivityDispatcher,
    ConversationActionModeCallbackDelegate, VisibleMessageViewDelegate, RecipientModifiedListener,
    SearchBottomBar.EventListener, LoaderManager.LoaderCallbacks<Cursor>, ConversationActionBarDelegate,
    OnReactionSelectedListener, ReactWithAnyEmojiDialogFragment.Callback, ReactionsDialogFragment.Callback,
    ConversationMenuHelper.ConversationMenuListener {

    private var binding: ActivityConversationV2Binding? = null

    @Inject lateinit var textSecurePreferences: TextSecurePreferences
    @Inject lateinit var threadDb: ThreadDatabase
    @Inject lateinit var mmsSmsDb: MmsSmsDatabase
    @Inject lateinit var lokiThreadDb: LokiThreadDatabase
    @Inject lateinit var sessionContactDb: SessionContactDatabase
    @Inject lateinit var groupDb: GroupDatabase
    @Inject lateinit var smsDb: SmsDatabase
    @Inject lateinit var mmsDb: MmsDatabase
    @Inject lateinit var lokiMessageDb: LokiMessageDatabase
    @Inject lateinit var storage: Storage
    @Inject lateinit var reactionDb: ReactionDatabase
    @Inject lateinit var viewModelFactory: ConversationViewModel.AssistedFactory

    private val screenshotObserver by lazy {
        ScreenshotObserver(this, Handler(Looper.getMainLooper())) {
            // post screenshot message
            sendScreenshotNotification()
        }
    }

    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    private val linkPreviewViewModel: LinkPreviewViewModel by lazy {
        ViewModelProvider(this, LinkPreviewViewModel.Factory(LinkPreviewRepository()))
            .get(LinkPreviewViewModel::class.java)
    }
    private val viewModel: ConversationViewModel by viewModels {
        var threadId = intent.getLongExtra(THREAD_ID, -1L)
        if (threadId == -1L) {
            intent.getParcelableExtra<Address>(ADDRESS)?.let { it ->
                threadId = threadDb.getThreadIdIfExistsFor(it.serialize())
                if (threadId == -1L) {
                    val sessionId = SessionId(it.serialize())
                    val openGroup = lokiThreadDb.getOpenGroupChat(intent.getLongExtra(FROM_GROUP_THREAD_ID, -1))
                    val address = if (sessionId.prefix == IdPrefix.BLINDED && openGroup != null) {
                        storage.getOrCreateBlindedIdMapping(sessionId.hexString, openGroup.server, openGroup.publicKey).sessionId?.let {
                            fromSerialized(it)
                        } ?: GroupUtil.getEncodedOpenGroupInboxID(openGroup, sessionId)
                    } else {
                        it
                    }
                    val recipient = Recipient.from(this, address, false)
                    threadId = storage.getOrCreateThreadIdFor(recipient.address)
                }
            } ?: finish()
        }
        viewModelFactory.create(threadId, MessagingModuleConfiguration.shared.getUserED25519KeyPair())
    }
    private var actionMode: ActionMode? = null
    private var unreadCount = Int.MAX_VALUE
    // Attachments
    private val audioRecorder = AudioRecorder(this)
    private val stopAudioHandler = Handler(Looper.getMainLooper())
    private val stopVoiceMessageRecordingTask = Runnable { sendVoiceMessage() }
    private val attachmentManager by lazy { AttachmentManager(this, this) }
    private var isLockViewExpanded = false
    private var isShowingAttachmentOptions = false
    // Mentions
    private val mentions = mutableListOf<Mention>()
    private var mentionCandidatesView: MentionCandidatesView? = null
    private var previousText: CharSequence = ""
    private var currentMentionStartIndex = -1
    private var isShowingMentionCandidatesView = false
    // Search
    val searchViewModel: SearchViewModel by viewModels()
    var searchViewItem: MenuItem? = null

    private val bufferedLastSeenChannel = Channel<Long>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var emojiPickerVisible = false

    private val isScrolledToBottom: Boolean
        get() = binding?.conversationRecyclerView?.isScrolledToBottom ?: true

    private val isScrolledToWithin30dpOfBottom: Boolean
        get() = binding?.conversationRecyclerView?.isScrolledToWithin30dpOfBottom ?: true

    private val layoutManager: LinearLayoutManager?
        get() { return binding?.conversationRecyclerView?.layoutManager as LinearLayoutManager? }

    private val messageAttachmentDownloadHelper: MessageAttachmentDownloadHelper by lazy {
        MessageAttachmentDownloadHelper(JobQueue.shared, scope = lifecycleScope)
    }

    private val seed by lazy {
        var hexEncodedSeed = IdentityKeyUtil.retrieve(this, IdentityKeyUtil.LOKI_SEED)
        if (hexEncodedSeed == null) {
            hexEncodedSeed = IdentityKeyUtil.getIdentityKeyPair(this).hexEncodedPrivateKey // Legacy account
        }

        val appContext = applicationContext
        val loadFileContents: (String) -> String = { fileName ->
            MnemonicUtilities.loadFileContents(appContext, fileName)
        }
        MnemonicCodec(loadFileContents).encode(hexEncodedSeed!!, MnemonicCodec.Language.Configuration.english)
    }

    // There is a bug when initially joining a community where all messages will immediately be marked
    // as read if we reverse the message list so this is now hard-coded to false
    private val reverseMessageList = false

    private val adapter by lazy {
        val cursor = mmsSmsDb.getConversation(viewModel.threadId, reverseMessageList)
        val adapter = ConversationAdapter(
            this,
            cursor,
            storage.getLastSeen(viewModel.threadId),
            reverseMessageList,
            onItemPress = { message, position, view, event ->
                handlePress(message, position, view, event)
            },
            onItemSwipeToReply = { message, _ ->
                handleSwipeToReply(message)
            },
            onItemLongPress = { message, position, view ->
                if (!viewModel.isMessageRequestThread &&
                    viewModel.canReactToMessages
                ) {
                    showEmojiPicker(message, view)
                } else {
                    handleLongPress(message, position)
                }
            },
            onDeselect = { message, position ->
                actionMode?.let {
                    onDeselect(message, position, it)
                }
            },
            onAttachmentNeedsDownload = messageAttachmentDownloadHelper::onAttachmentDownloadRequested,
            onVisibleMessageBound = messageAttachmentDownloadHelper::onMessageBecomeVisible,
            glide = glide,
            lifecycleCoroutineScope = lifecycleScope
        )
        adapter.visibleMessageViewDelegate = this

        // Register an AdapterDataObserver to scroll us to the bottom of the RecyclerView for if
        // we're already near the the bottom and the data changes.
        adapter.registerAdapterDataObserver(ConversationAdapterDataObserver(binding?.conversationRecyclerView!!, adapter))

        adapter
    }

    private val glide by lazy { GlideApp.with(this) }
    private val lockViewHitMargin by lazy { toPx(40, resources) }
    private val gifButton by lazy { InputBarButton(this, R.drawable.ic_gif_white_24dp, hasOpaqueBackground = true, isGIFButton = true) }
    private val documentButton by lazy { InputBarButton(this, R.drawable.ic_document_small_dark, hasOpaqueBackground = true) }
    private val libraryButton by lazy { InputBarButton(this, R.drawable.ic_baseline_photo_library_24, hasOpaqueBackground = true) }
    private val cameraButton by lazy { InputBarButton(this, R.drawable.ic_baseline_photo_camera_24, hasOpaqueBackground = true) }
    private val messageToScrollTimestamp = AtomicLong(-1)
    private val messageToScrollAuthor = AtomicReference<Address?>(null)
    private val firstLoad = AtomicBoolean(true)

    private lateinit var reactionDelegate: ConversationReactionDelegate
    private val reactWithAnyEmojiStartPage = -1

    // Properties for what message indices are visible previously & now, as well as the scroll state
    private var previousLastVisibleRecyclerViewIndex: Int = RecyclerView.NO_POSITION
    private var currentLastVisibleRecyclerViewIndex:  Int = RecyclerView.NO_POSITION
    private var recyclerScrollState: Int = RecyclerView.SCROLL_STATE_IDLE

    // region Settings
    companion object {
        // Extras
        const val THREAD_ID = "thread_id"
        const val ADDRESS = "address"
        const val FROM_GROUP_THREAD_ID = "from_group_thread_id"
        const val SCROLL_MESSAGE_ID = "scroll_message_id"
        const val SCROLL_MESSAGE_AUTHOR = "scroll_message_author"
        // Request codes
        const val PICK_DOCUMENT = 2
        const val TAKE_PHOTO = 7
        const val PICK_GIF = 10
        const val PICK_FROM_LIBRARY = 12
        const val INVITE_CONTACTS = 124
    }
    // endregion

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        binding = ActivityConversationV2Binding.inflate(layoutInflater)
        setContentView(binding!!.root)

        // messageIdToScroll
        messageToScrollTimestamp.set(intent.getLongExtra(SCROLL_MESSAGE_ID, -1))
        messageToScrollAuthor.set(intent.getParcelableExtra(SCROLL_MESSAGE_AUTHOR))
        val recipient = viewModel.recipient
        val openGroup = recipient.let { viewModel.openGroup }
        if (recipient == null || (recipient.isCommunityRecipient && openGroup == null)) {
            Toast.makeText(this, "This thread has been deleted.", Toast.LENGTH_LONG).show()
            return finish()
        }

        setUpToolBar()
        setUpInputBar()
        setUpLinkPreviewObserver()
        restoreDraftIfNeeded()
        setUpUiStateObserver()

        binding!!.scrollToBottomButton.setOnClickListener {
            val layoutManager = (binding?.conversationRecyclerView?.layoutManager as? LinearLayoutManager) ?: return@setOnClickListener
            val targetPosition = if (reverseMessageList) 0 else adapter.itemCount

            if (layoutManager.isSmoothScrolling) {
                binding?.conversationRecyclerView?.scrollToPosition(targetPosition)
            } else {
                // It looks like 'smoothScrollToPosition' will actually load all intermediate items in
                // order to do the scroll, this can be very slow if there are a lot of messages so
                // instead we check the current position and if there are more than 10 items to scroll
                // we jump instantly to the 10th item and scroll from there (this should happen quick
                // enough to give a similar scroll effect without having to load everything)
//                val position = if (reverseMessageList) layoutManager.findFirstVisibleItemPosition() else layoutManager.findLastVisibleItemPosition()
//                val targetBuffer = if (reverseMessageList) 10 else Math.max(0, (adapter.itemCount - 1) - 10)
//                if (position > targetBuffer) {
//                    binding?.conversationRecyclerView?.scrollToPosition(targetBuffer)
//                }

                binding?.conversationRecyclerView?.post {
                    binding?.conversationRecyclerView?.smoothScrollToPosition(targetPosition)
                }
            }
        }

        updateUnreadCountIndicator()
        updatePlaceholder()
        setUpBlockedBanner()
        binding!!.searchBottomBar.setEventListener(this)
        updateSendAfterApprovalText()
        setUpMessageRequestsBar()

        // Note: Do not `showOrHideInputIfNeeded` here - we'll never start this activity w/ the
        // keyboard visible and have no need to immediately display it.

        val weakActivity = WeakReference(this)

        lifecycleScope.launch(Dispatchers.IO) {
            // Note: We are accessing the `adapter` property because we want it to be loaded on
            // the background thread to avoid blocking the UI thread and potentially hanging when
            // transitioning to the activity
            weakActivity.get()?.adapter ?: return@launch

            // 'Get' instead of 'GetAndSet' here because we want to trigger the highlight in 'onFirstLoad'
            // by triggering 'jumpToMessage' using these values
            val messageTimestamp = messageToScrollTimestamp.get()
            val author = messageToScrollAuthor.get()
            val targetPosition = if (author != null && messageTimestamp >= 0) mmsSmsDb.getMessagePositionInConversation(viewModel.threadId, messageTimestamp, author, reverseMessageList) else -1

            withContext(Dispatchers.Main) {
                setUpRecyclerView()
                setUpTypingObserver()
                setUpRecipientObserver()
                getLatestOpenGroupInfoIfNeeded()
                setUpSearchResultObserver()
                scrollToFirstUnreadMessageIfNeeded()
                setUpOutdatedClientBanner()

                if (author != null && messageTimestamp >= 0 && targetPosition >= 0) {
                    binding?.conversationRecyclerView?.scrollToPosition(targetPosition)
                }
                else {
                    scrollToFirstUnreadMessageIfNeeded(true)
                }
            }
        }

        val reactionOverlayStub: Stub<ConversationReactionOverlay> =
            ViewUtil.findStubById(this, R.id.conversation_reaction_scrubber_stub)
        reactionDelegate = ConversationReactionDelegate(reactionOverlayStub)
        reactionDelegate.setOnReactionSelectedListener(this)
        lifecycleScope.launch {
                // only update the conversation every 3 seconds maximum
                // channel is rendezvous and shouldn't block on try send calls as often as we want
                bufferedLastSeenChannel.receiveAsFlow()
                    .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
                    .collectLatest {
                        withContext(Dispatchers.IO) {
                            try {
                                if (it > storage.getLastSeen(viewModel.threadId)) {
                                    storage.markConversationAsRead(viewModel.threadId, it)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "bufferedLastSeenChannel collectLatest", e)
                            }
                        }
                    }
        }
    }

    override fun onResume() {
        super.onResume()
        ApplicationContext.getInstance(this).messageNotifier.setVisibleThread(viewModel.threadId)

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            screenshotObserver
        )
        viewModel.run {
            binding?.toolbarContent?.update(recipient ?: return, openGroup, expirationConfiguration)
        }
    }

    override fun onPause() {
        super.onPause()
        ApplicationContext.getInstance(this).messageNotifier.setVisibleThread(-1)
        contentResolver.unregisterContentObserver(screenshotObserver)
    }

    override fun getSystemService(name: String): Any? {
        if (name == ActivityDispatcher.SERVICE) {
            return this
        }
        return super.getSystemService(name)
    }

    override fun dispatchIntent(body: (Context) -> Intent?) {
        body(this)?.let { push(it, false) }
    }

    override fun showDialog(dialogFragment: DialogFragment, tag: String?) {
        dialogFragment.show(supportFragmentManager, tag)
    }

    override fun onCreateLoader(id: Int, bundle: Bundle?): Loader<Cursor> {
        return ConversationLoader(viewModel.threadId, reverseMessageList, this@ConversationActivityV2)
    }

    override fun onLoadFinished(loader: Loader<Cursor>, cursor: Cursor?) {
        val oldCount = adapter.itemCount
        val newCount = cursor?.count ?: 0
        adapter.changeCursor(cursor)

        if (cursor != null) {
            val messageTimestamp = messageToScrollTimestamp.getAndSet(-1)
            val author = messageToScrollAuthor.getAndSet(null)
            val initialUnreadCount = mmsSmsDb.getUnreadCount(viewModel.threadId)

            // Update the unreadCount value to be loaded from the database since we got a new message
            if (firstLoad.get() || oldCount != newCount || initialUnreadCount != unreadCount) {
                // Update the unreadCount value to be loaded from the database since we got a new
                // message (we need to store it in a local variable as it can get overwritten on
                // another thread before the 'firstLoad.getAndSet(false)' case below)
                unreadCount = initialUnreadCount
                updateUnreadCountIndicator()
            }

            if (author != null && messageTimestamp >= 0) {
                jumpToMessage(author, messageTimestamp, firstLoad.get(), null)
            } else {
                if (firstLoad.getAndSet(false)) scrollToFirstUnreadMessageIfNeeded(true)
                handleRecyclerViewScrolled()
            }
        }
        updatePlaceholder()
        viewModel.recipient?.let {
            maybeUpdateToolbar(recipient = it)
            setUpOutdatedClientBanner()
        }
    }

    override fun onLoaderReset(cursor: Loader<Cursor>) {
        adapter.changeCursor(null)
    }

    // called from onCreate
    private fun setUpRecyclerView() {
        binding!!.conversationRecyclerView.adapter = adapter
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, reverseMessageList)
        binding!!.conversationRecyclerView.layoutManager = layoutManager
        // Workaround for the fact that CursorRecyclerViewAdapter doesn't auto-update automatically (even though it says it will)
        LoaderManager.getInstance(this).restartLoader(0, null, this)
        binding!!.conversationRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // The unreadCount check is to prevent us scrolling to the bottom when we first enter a conversation
                if (recyclerScrollState == RecyclerView.SCROLL_STATE_IDLE && unreadCount != Int.MAX_VALUE) {
                    scrollToMostRecentMessageIfWeShould()
                }
                handleRecyclerViewScrolled()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                recyclerScrollState = newState
            }
        })
    }

    private fun scrollToMostRecentMessageIfWeShould() {
        // Grab an initial 'previous' last visible message..
        if (previousLastVisibleRecyclerViewIndex == RecyclerView.NO_POSITION) {
            previousLastVisibleRecyclerViewIndex = layoutManager?.findLastVisibleItemPosition()!!
        }

        // ..and grab the 'current' last visible message.
        currentLastVisibleRecyclerViewIndex = layoutManager?.findLastVisibleItemPosition()!!

        // If the current last visible message index is less than the previous one (i.e. we've
        // lost visibility of one or more messages due to showing the IME keyboard) AND we're
        // at the bottom of the message feed..
        val atBottomAndTrueLastNoLongerVisible = currentLastVisibleRecyclerViewIndex!! <= previousLastVisibleRecyclerViewIndex!! && !binding?.scrollToBottomButton?.isVisible!!

        // ..OR we're at the last message or have received a new message..
        val atLastOrReceivedNewMessage = currentLastVisibleRecyclerViewIndex == (adapter.itemCount - 1)

        // ..then scroll the recycler view to the last message on resize. Note: We cannot just call
        // scroll/smoothScroll - we have to `post` it or nothing happens!
        if (atBottomAndTrueLastNoLongerVisible || atLastOrReceivedNewMessage) {
            binding?.conversationRecyclerView?.post {
                binding?.conversationRecyclerView?.smoothScrollToPosition(adapter.itemCount)
            }
        }

        // Update our previous last visible view index to the current one
        previousLastVisibleRecyclerViewIndex = currentLastVisibleRecyclerViewIndex
    }

    // called from onCreate
    private fun setUpToolBar() {
        val binding = binding ?: return
        setSupportActionBar(binding.toolbar)
        val actionBar = supportActionBar ?: return
        val recipient = viewModel.recipient ?: return
        actionBar.title = ""
        actionBar.setDisplayHomeAsUpEnabled(true)
        actionBar.setHomeButtonEnabled(true)
        binding!!.toolbarContent.bind(
            this,
            viewModel.threadId,
            recipient,
            viewModel.expirationConfiguration,
            viewModel.openGroup
        )
        maybeUpdateToolbar(recipient)
    }

    // called from onCreate
    private fun setUpInputBar() {
        val binding = binding ?: return
        binding.inputBar.isGone = viewModel.hidesInputBar()
        binding.inputBar.delegate = this
        binding.inputBarRecordingView.delegate = this
        // GIF button
        binding.gifButtonContainer.addView(gifButton)
        gifButton.layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
        gifButton.onUp = { showGIFPicker() }
        gifButton.snIsEnabled = false
        // Document button
        binding.documentButtonContainer.addView(documentButton)
        documentButton.layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
        documentButton.onUp = { showDocumentPicker() }
        documentButton.snIsEnabled = false
        // Library button
        binding.libraryButtonContainer.addView(libraryButton)
        libraryButton.layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
        libraryButton.onUp = { pickFromLibrary() }
        libraryButton.snIsEnabled = false
        // Camera button
        binding.cameraButtonContainer.addView(cameraButton)
        cameraButton.layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
        cameraButton.onUp = { showCamera() }
        cameraButton.snIsEnabled = false
    }

    // called from onCreate
    private fun restoreDraftIfNeeded() {
        val mediaURI = intent.data
        val mediaType = AttachmentManager.MediaType.from(intent.type)
        if (mediaURI != null && mediaType != null) {
            if (AttachmentManager.MediaType.IMAGE == mediaType || AttachmentManager.MediaType.GIF == mediaType || AttachmentManager.MediaType.VIDEO == mediaType) {
                val media = Media(mediaURI, MediaUtil.getMimeType(this, mediaURI)!!, 0, 0, 0, 0, Optional.absent(), Optional.absent())
                startActivityForResult(MediaSendActivity.buildEditorIntent(this, listOf( media ), viewModel.recipient!!, ""), PICK_FROM_LIBRARY)
                return
            } else {
                prepMediaForSending(mediaURI, mediaType).addListener(object : ListenableFuture.Listener<Boolean> {

                    override fun onSuccess(result: Boolean?) {
                        sendAttachments(attachmentManager.buildSlideDeck().asAttachments(), null)
                    }

                    override fun onFailure(e: ExecutionException?) {
                        Toast.makeText(this@ConversationActivityV2, R.string.activity_conversation_attachment_prep_failed, Toast.LENGTH_LONG).show()
                    }
                })
                return
            }
        } else if (intent.hasExtra(Intent.EXTRA_TEXT)) {
            val dataTextExtra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: ""
            binding!!.inputBar.text = dataTextExtra.toString()
        } else {
            viewModel.getDraft()?.let { text ->
                binding!!.inputBar.text = text
            }
        }
    }

    // called from onCreate
    private fun setUpTypingObserver() {
        ApplicationContext.getInstance(this).typingStatusRepository.getTypists(viewModel.threadId).observe(this) { state ->
            val recipients = if (state != null) state.typists else listOf()
            // FIXME: Also checking isScrolledToBottom is a quick fix for an issue where the
            //        typing indicator overlays the recycler view when scrolled up
            val viewContainer = binding?.typingIndicatorViewContainer ?: return@observe
            viewContainer.isVisible = recipients.isNotEmpty() && isScrolledToBottom
            viewContainer.setTypists(recipients)
        }
        if (textSecurePreferences.isTypingIndicatorsEnabled()) {
            binding!!.inputBar.addTextChangedListener(object : SimpleTextWatcher() {

                override fun onTextChanged(text: String?) {
                    ApplicationContext.getInstance(this@ConversationActivityV2).typingStatusSender.onTypingStarted(viewModel.threadId)
                }
            })
        }
    }

    private fun setUpRecipientObserver() {
        viewModel.recipient?.addListener(this)
    }

    private fun tearDownRecipientObserver() {
        viewModel.recipient?.removeListener(this)
    }

    private fun getLatestOpenGroupInfoIfNeeded() {
        val openGroup = viewModel.openGroup ?: return
        OpenGroupApi.getMemberCount(openGroup.room, openGroup.server) successUi {
            binding?.toolbarContent?.updateSubtitle(viewModel.recipient!!, openGroup, viewModel.expirationConfiguration)
            maybeUpdateToolbar(viewModel.recipient!!)
        }
    }

    // called from onCreate
    private fun setUpBlockedBanner() {
        val recipient = viewModel.recipient?.takeUnless { it.isGroupRecipient } ?: return
        val sessionID = recipient.address.toString()
        val name = sessionContactDb.getContactWithSessionID(sessionID)?.displayName(Contact.ContactContext.REGULAR) ?: sessionID
        binding?.blockedBannerTextView?.text = resources.getString(R.string.activity_conversation_blocked_banner_text, name)
        binding?.blockedBanner?.isVisible = recipient.isBlocked
        binding?.blockedBanner?.setOnClickListener { viewModel.unblock() }
    }

    private fun setUpOutdatedClientBanner() {
        val legacyRecipient = viewModel.legacyBannerRecipient(this)

        val shouldShowLegacy = ExpirationConfiguration.isNewConfigEnabled &&
                legacyRecipient != null

        binding?.outdatedBanner?.isVisible = shouldShowLegacy
        if (shouldShowLegacy) {
            binding?.outdatedBannerTextView?.text =
                resources.getString(R.string.activity_conversation_outdated_client_banner_text, legacyRecipient!!.name)
        }
    }

    private fun setUpLinkPreviewObserver() {
        if (!textSecurePreferences.isLinkPreviewsEnabled()) {
            linkPreviewViewModel.onUserCancel(); return
        }
        linkPreviewViewModel.linkPreviewState.observe(this) { previewState: LinkPreviewState? ->
            if (previewState == null) return@observe
            when {
                previewState.isLoading -> {
                    binding?.inputBar?.draftLinkPreview()
                }
                previewState.linkPreview.isPresent -> {
                    binding?.inputBar?.updateLinkPreviewDraft(glide, previewState.linkPreview.get())
                }
                else -> {
                    binding?.inputBar?.cancelLinkPreviewDraft()
                }
            }
        }
    }

    private fun setUpUiStateObserver() {
        lifecycleScope.launchWhenStarted {
            viewModel.uiState.collect { uiState ->
                uiState.uiMessages.firstOrNull()?.let {
                    Toast.makeText(this@ConversationActivityV2, it.message, Toast.LENGTH_LONG).show()
                    viewModel.messageShown(it.id)
                }
                if (uiState.isMessageRequestAccepted == true) {
                    binding?.messageRequestBar?.visibility = View.GONE
                }
                if (!uiState.conversationExists && !isFinishing) {
                    // Conversation should be deleted now, just go back
                    finish()
                }
            }
        }
    }

    private fun scrollToFirstUnreadMessageIfNeeded(isFirstLoad: Boolean = false, shouldHighlight: Boolean = false): Int {
        val lastSeenTimestamp = threadDb.getLastSeenAndHasSent(viewModel.threadId).first()
        val lastSeenItemPosition = adapter.findLastSeenItemPosition(lastSeenTimestamp) ?: return -1

        // If this is triggered when first opening a conversation then we want to position the top
        // of the first unread message in the middle of the screen
        if (isFirstLoad && !reverseMessageList) {
            layoutManager?.scrollToPositionWithOffset(lastSeenItemPosition, ((layoutManager?.height ?: 0) / 2))
            if (shouldHighlight) { highlightViewAtPosition(lastSeenItemPosition) }
            return lastSeenItemPosition
        }

        if (lastSeenItemPosition <= 3) { return lastSeenItemPosition }

        binding?.conversationRecyclerView?.scrollToPosition(lastSeenItemPosition)
        return lastSeenItemPosition
    }

    private fun highlightViewAtPosition(position: Int) {
        binding?.conversationRecyclerView?.post {
            (layoutManager?.findViewByPosition(position) as? VisibleMessageView)?.playHighlight()
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val recipient = viewModel.recipient ?: return false
        if (!viewModel.isMessageRequestThread) {
            ConversationMenuHelper.onPrepareOptionsMenu(
                menu,
                menuInflater,
                recipient,
                this
            )
        }
        maybeUpdateToolbar(recipient)
        return true
    }

    override fun onDestroy() {
        viewModel.saveDraft(binding?.inputBar?.text?.trim() ?: "")
        cancelVoiceMessage()
        tearDownRecipientObserver()
        super.onDestroy()
        binding = null
    }
    // endregion

    // region Animation & Updating
    override fun onModified(recipient: Recipient) {
        viewModel.updateRecipient()

        runOnUiThread {
            val threadRecipient = viewModel.recipient ?: return@runOnUiThread
            if (threadRecipient.isContactRecipient) {
                binding?.blockedBanner?.isVisible = threadRecipient.isBlocked
            }
            setUpMessageRequestsBar()
            invalidateOptionsMenu()
            updateSendAfterApprovalText()
            showOrHideInputIfNeeded()

            maybeUpdateToolbar(threadRecipient)
        }
    }

    private fun maybeUpdateToolbar(recipient: Recipient) {
        binding?.toolbarContent?.update(recipient, viewModel.openGroup, viewModel.expirationConfiguration)
    }

    private fun updateSendAfterApprovalText() {
        binding?.textSendAfterApproval?.isVisible = viewModel.showSendAfterApprovalText
    }

    private fun showOrHideInputIfNeeded() {
        binding?.inputBar?.showInput = viewModel.recipient?.takeIf { it.isClosedGroupRecipient }
            ?.run { address.toGroupString().let(groupDb::getGroup).orNull()?.isActive == true }
            ?: true
    }

    private fun setUpMessageRequestsBar() {
        binding?.inputBar?.showMediaControls = !isOutgoingMessageRequestThread()
        binding?.messageRequestBar?.isVisible = isIncomingMessageRequestThread()
        binding?.acceptMessageRequestButton?.setOnClickListener {
            acceptMessageRequest()
        }
        binding?.messageRequestBlock?.setOnClickListener {
            block(deleteThread = true)
        }
        binding?.declineMessageRequestButton?.setOnClickListener {
            viewModel.declineMessageRequest()
            lifecycleScope.launch(Dispatchers.IO) {
                ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(this@ConversationActivityV2)
            }
            finish()
        }
    }

    private fun acceptMessageRequest() {
        binding?.messageRequestBar?.isVisible = false
        viewModel.acceptMessageRequest()

        lifecycleScope.launch(Dispatchers.IO) {
            ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(this@ConversationActivityV2)
        }
    }

    private fun isOutgoingMessageRequestThread(): Boolean = viewModel.recipient?.run {
        !isGroupRecipient && !isLocalNumber &&
        !(hasApprovedMe() || viewModel.hasReceived())
    } ?: false

    private fun isIncomingMessageRequestThread(): Boolean = viewModel.recipient?.run {
        !isGroupRecipient && !isApproved && !isLocalNumber &&
        !threadDb.getLastSeenAndHasSent(viewModel.threadId).second() && threadDb.getMessageCount(viewModel.threadId) > 0
    } ?: false

    override fun inputBarEditTextContentChanged(newContent: CharSequence) {
        val inputBarText = binding?.inputBar?.text ?: return // TODO check if we should be referencing newContent here instead
        if (textSecurePreferences.isLinkPreviewsEnabled()) {
            linkPreviewViewModel.onTextChanged(this, inputBarText, 0, 0)
        }
        showOrHideMentionCandidatesIfNeeded(newContent)
        if (LinkPreviewUtil.findWhitelistedUrls(newContent.toString()).isNotEmpty()
            && !textSecurePreferences.isLinkPreviewsEnabled() && !textSecurePreferences.hasSeenLinkPreviewSuggestionDialog()) {
            LinkPreviewDialog {
                setUpLinkPreviewObserver()
                linkPreviewViewModel.onEnabled()
                linkPreviewViewModel.onTextChanged(this, inputBarText, 0, 0)
            }.show(supportFragmentManager, "Link Preview Dialog")
            textSecurePreferences.setHasSeenLinkPreviewSuggestionDialog()
        }
    }

    private fun showOrHideMentionCandidatesIfNeeded(text: CharSequence) {
        if (text.length < previousText.length) {
            currentMentionStartIndex = -1
            hideMentionCandidates()
            val mentionsToRemove = mentions.filter { !text.contains(it.displayName) }
            mentions.removeAll(mentionsToRemove)
        }
        if (text.isNotEmpty()) {
            val lastCharIndex = text.lastIndex
            val lastChar = text[lastCharIndex]
            // Check if there is whitespace before the '@' or the '@' is the first character
            val isCharacterBeforeLastWhiteSpaceOrStartOfLine: Boolean
            if (text.length == 1) {
                isCharacterBeforeLastWhiteSpaceOrStartOfLine = true // Start of line
            } else {
                val charBeforeLast = text[lastCharIndex - 1]
                isCharacterBeforeLastWhiteSpaceOrStartOfLine = Character.isWhitespace(charBeforeLast)
            }
            if (lastChar == '@' && isCharacterBeforeLastWhiteSpaceOrStartOfLine) {
                currentMentionStartIndex = lastCharIndex
                showOrUpdateMentionCandidatesIfNeeded()
            } else if (Character.isWhitespace(lastChar) || lastChar == '@') { // the lastCharacter == "@" is to check for @@
                currentMentionStartIndex = -1
                hideMentionCandidates()
            } else if (currentMentionStartIndex != -1) {
                val query = text.substring(currentMentionStartIndex + 1) // + 1 to get rid of the "@"
                showOrUpdateMentionCandidatesIfNeeded(query)
            }
        } else {
            currentMentionStartIndex = -1
            hideMentionCandidates()
        }
        previousText = text
    }

    private fun showOrUpdateMentionCandidatesIfNeeded(query: String = "") {
        val additionalContentContainer = binding?.additionalContentContainer ?: return
        val recipient = viewModel.recipient ?: return
        if (!isShowingMentionCandidatesView) {
            additionalContentContainer.removeAllViews()
            val view = MentionCandidatesView(this).apply {
                contentDescription = context.getString(R.string.AccessibilityId_mentions_list)
            }
            view.glide = glide
            view.onCandidateSelected = { handleMentionSelected(it) }
            additionalContentContainer.addView(view)
            val candidates = MentionsManager.getMentionCandidates(query, viewModel.threadId, recipient.isCommunityRecipient)
            this.mentionCandidatesView = view
            view.show(candidates, viewModel.threadId)
        } else {
            val candidates = MentionsManager.getMentionCandidates(query, viewModel.threadId, recipient.isCommunityRecipient)
            this.mentionCandidatesView!!.setMentionCandidates(candidates)
        }
        isShowingMentionCandidatesView = true
    }

    private fun hideMentionCandidates() {
        if (isShowingMentionCandidatesView) {
            val mentionCandidatesView = mentionCandidatesView ?: return
            val animation = ValueAnimator.ofObject(FloatEvaluator(), mentionCandidatesView.alpha, 0.0f)
            animation.duration = 250L
            animation.addUpdateListener { animator ->
                mentionCandidatesView.alpha = animator.animatedValue as Float
                if (animator.animatedFraction == 1.0f) { binding?.additionalContentContainer?.removeAllViews() }
            }
            animation.start()
        }
        isShowingMentionCandidatesView = false
    }

    override fun toggleAttachmentOptions() {
        val targetAlpha = if (isShowingAttachmentOptions) 0.0f else 1.0f
        val allButtonContainers = listOfNotNull(
            binding?.cameraButtonContainer,
            binding?.libraryButtonContainer,
            binding?.documentButtonContainer,
            binding?.gifButtonContainer
        )
        val isReversed = isShowingAttachmentOptions // Run the animation in reverse
        val count = allButtonContainers.size
        allButtonContainers.indices.forEach { index ->
            val view = allButtonContainers[index]
            val animation = ValueAnimator.ofObject(FloatEvaluator(), view.alpha, targetAlpha)
            animation.duration = 250L
            animation.startDelay = if (isReversed) 50L * (count - index.toLong()) else 50L * index.toLong()
            animation.addUpdateListener { animator ->
                view.alpha = animator.animatedValue as Float
            }
            animation.start()
        }
        isShowingAttachmentOptions = !isShowingAttachmentOptions
        val allButtons = listOf( cameraButton, libraryButton, documentButton, gifButton )
        allButtons.forEach { it.snIsEnabled = isShowingAttachmentOptions }
    }

    override fun showVoiceMessageUI() {
        binding?.inputBarRecordingView?.show(lifecycleScope)
        binding?.inputBar?.alpha = 0.0f
        val animation = ValueAnimator.ofObject(FloatEvaluator(), 1.0f, 0.0f)
        animation.duration = 250L
        animation.addUpdateListener { animator ->
            binding?.inputBar?.alpha = animator.animatedValue as Float
        }
        animation.start()
    }

    private fun expandVoiceMessageLockView() {
        val lockView = binding?.inputBarRecordingView?.lockView ?: return
        val animation = ValueAnimator.ofObject(FloatEvaluator(), lockView.scaleX, 1.10f)
        animation.duration = 250L
        animation.addUpdateListener { animator ->
            lockView.scaleX = animator.animatedValue as Float
            lockView.scaleY = animator.animatedValue as Float
        }
        animation.start()
    }

    private fun collapseVoiceMessageLockView() {
        val lockView = binding?.inputBarRecordingView?.lockView ?: return
        val animation = ValueAnimator.ofObject(FloatEvaluator(), lockView.scaleX, 1.0f)
        animation.duration = 250L
        animation.addUpdateListener { animator ->
            lockView.scaleX = animator.animatedValue as Float
            lockView.scaleY = animator.animatedValue as Float
        }
        animation.start()
    }

    private fun hideVoiceMessageUI() {
        val chevronImageView = binding?.inputBarRecordingView?.chevronImageView ?: return
        val slideToCancelTextView = binding?.inputBarRecordingView?.slideToCancelTextView ?: return
        listOf( chevronImageView, slideToCancelTextView ).forEach { view ->
            val animation = ValueAnimator.ofObject(FloatEvaluator(), view.translationX, 0.0f)
            animation.duration = 250L
            animation.addUpdateListener { animator ->
                view.translationX = animator.animatedValue as Float
            }
            animation.start()
        }
        binding?.inputBarRecordingView?.hide()
    }

    override fun handleVoiceMessageUIHidden() {
        val inputBar = binding?.inputBar ?: return
        inputBar.alpha = 1.0f
        val animation = ValueAnimator.ofObject(FloatEvaluator(), 0.0f, 1.0f)
        animation.duration = 250L
        animation.addUpdateListener { animator ->
            inputBar.alpha = animator.animatedValue as Float
        }
        animation.start()
    }

    private fun handleRecyclerViewScrolled() {
        val binding = binding ?: return

        // Note: The typing indicate is whether the other person / other people are typing - it has
        // nothing to do with the IME keyboard state.
        val wasTypingIndicatorVisibleBefore = binding.typingIndicatorViewContainer.isVisible
        binding.typingIndicatorViewContainer.isVisible = wasTypingIndicatorVisibleBefore && isScrolledToBottom

        showScrollToBottomButtonIfApplicable()
        val maybeTargetVisiblePosition = if (reverseMessageList) layoutManager?.findFirstVisibleItemPosition() else layoutManager?.findLastVisibleItemPosition()
        val targetVisiblePosition = maybeTargetVisiblePosition ?: RecyclerView.NO_POSITION
        if (!firstLoad.get() && targetVisiblePosition != RecyclerView.NO_POSITION) {
            adapter.getTimestampForItemAt(targetVisiblePosition)?.let { visibleItemTimestamp ->
                bufferedLastSeenChannel.trySend(visibleItemTimestamp).apply {
                    if (isFailure) Log.e(TAG, "trySend failed", exceptionOrNull())
                }
            }
        }

        if (reverseMessageList) {
            unreadCount = min(unreadCount, targetVisiblePosition).coerceAtLeast(0)
        } else {
            val layoutUnreadCount = layoutManager?.let { (it.itemCount - 1) - it.findLastVisibleItemPosition() }
                ?: RecyclerView.NO_POSITION
            unreadCount = min(unreadCount, layoutUnreadCount).coerceAtLeast(0)
        }
        updateUnreadCountIndicator()
    }

    private fun updatePlaceholder() {
        val recipient = viewModel.recipient
            ?: return Log.w("Loki", "recipient was null in placeholder update")
        val blindedRecipient = viewModel.blindedRecipient
        val binding = binding ?: return
        val openGroup = viewModel.openGroup

        val (textResource, insertParam) = when {
            recipient.isLocalNumber -> R.string.activity_conversation_empty_state_note_to_self to null
            openGroup != null && !openGroup.canWrite -> R.string.activity_conversation_empty_state_read_only to recipient.toShortString()
            blindedRecipient?.blocksCommunityMessageRequests == true -> R.string.activity_conversation_empty_state_blocks_community_requests to recipient.toShortString()
            else -> R.string.activity_conversation_empty_state_default to recipient.toShortString()
        }
        val showPlaceholder = adapter.itemCount == 0
        binding.placeholderText.isVisible = showPlaceholder
        if (showPlaceholder) {
            if (insertParam != null) {
                val span = getText(textResource) as SpannedString
                val annotations = span.getSpans(0, span.length, StyleSpan::class.java)
                val boldSpan = annotations.first()
                val spannedParam = insertParam.toSpannable()
                spannedParam[0 until spannedParam.length] = StyleSpan(boldSpan.style)
                val originalStart = span.getSpanStart(boldSpan)
                val originalEnd = span.getSpanEnd(boldSpan)
                val newString = SpannableStringBuilder(span)
                    .replace(originalStart, originalEnd, spannedParam)
                binding.placeholderText.text = newString
            } else {
                binding.placeholderText.setText(textResource)
            }
        }
    }

    private fun showScrollToBottomButtonIfApplicable() {
        binding?.scrollToBottomButton?.isVisible = !emojiPickerVisible && !isScrolledToBottom && adapter.itemCount > 0
    }

    private fun updateUnreadCountIndicator() {
        val binding = binding ?: return
        val formattedUnreadCount = if (unreadCount < 10000) unreadCount.toString() else "9999+"
        binding.unreadCountTextView.text = formattedUnreadCount
        val textSize = if (unreadCount < 10000) 12.0f else 9.0f
        binding.unreadCountTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize)
        binding.unreadCountTextView.setTypeface(Typeface.DEFAULT, if (unreadCount < 100) Typeface.BOLD else Typeface.NORMAL)
        binding.unreadCountIndicator.isVisible = (unreadCount != 0)
    }

    // endregion

    // region Interaction
    override fun onDisappearingMessagesClicked() {
        viewModel.recipient?.let { showDisappearingMessages(it) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            return false
        }
        return viewModel.recipient?.let { recipient ->
            ConversationMenuHelper.onOptionItemSelected(this, item, recipient)
        } ?: false
    }

    override fun block(deleteThread: Boolean) {
        showSessionDialog {
            title(R.string.RecipientPreferenceActivity_block_this_contact_question)
            text(R.string.RecipientPreferenceActivity_you_will_no_longer_receive_messages_and_calls_from_this_contact)
            destructiveButton(R.string.RecipientPreferenceActivity_block, R.string.AccessibilityId_block_confirm) {
                viewModel.block()
                if (deleteThread) {
                    viewModel.deleteThread()
                    finish()
                }
            }
            cancelButton()
        }
    }

    override fun copySessionID(sessionId: String) {
        val clip = ClipData.newPlainText("Session ID", sessionId)
        val manager = getSystemService(PassphraseRequiredActionBarActivity.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    override fun copyOpenGroupUrl(thread: Recipient) {
        if (!thread.isCommunityRecipient) { return }

        val threadId = threadDb.getThreadIdIfExistsFor(thread) ?: return
        val openGroup = lokiThreadDb.getOpenGroupChat(threadId) ?: return

        val clip = ClipData.newPlainText("Community URL", openGroup.joinURL)
        val manager = getSystemService(PassphraseRequiredActionBarActivity.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    override fun showDisappearingMessages(thread: Recipient) {
        if (thread.isClosedGroupRecipient) {
            groupDb.getGroup(thread.address.toGroupString()).orNull()?.run { if (!isActive) return }
        }
        Intent(this, DisappearingMessagesActivity::class.java)
            .apply { putExtra(DisappearingMessagesActivity.THREAD_ID, viewModel.threadId) }
            .also { show(it, true) }
    }

    override fun unblock() {
        showSessionDialog {
            title(R.string.ConversationActivity_unblock_this_contact_question)
            text(R.string.ConversationActivity_you_will_once_again_be_able_to_receive_messages_and_calls_from_this_contact)
            destructiveButton(
                R.string.ConversationActivity_unblock,
                R.string.AccessibilityId_block_confirm
            ) { viewModel.unblock() }
            cancelButton()
        }
    }

    // `position` is the adapter position; not the visual position
    private fun handlePress(message: MessageRecord, position: Int, view: VisibleMessageView, event: MotionEvent) {
        val actionMode = this.actionMode
        if (actionMode != null) {
            onDeselect(message, position, actionMode)
        } else {
            // NOTE:
            // We have to use onContentClick (rather than a click listener directly on
            // the view) so as to not interfere with all the other gestures. Do not add
            // onClickListeners directly to message content views.
            view.onContentClick(event)
        }
    }

    private fun onDeselect(message: MessageRecord, position: Int, actionMode: ActionMode) {
        adapter.toggleSelection(message, position)
        val actionModeCallback = ConversationActionModeCallback(adapter, viewModel.threadId, this)
        actionModeCallback.delegate = this
        actionModeCallback.updateActionModeMenu(actionMode.menu)
        if (adapter.selectedItems.isEmpty()) {
            actionMode.finish()
            this.actionMode = null
        }
    }

    // `position` is the adapter position; not the visual position
    private fun handleSwipeToReply(message: MessageRecord) {
        if (message.isOpenGroupInvitation) return
        val recipient = viewModel.recipient ?: return
        binding?.inputBar?.draftQuote(recipient, message, glide)
    }

    // `position` is the adapter position; not the visual position
    private fun handleLongPress(message: MessageRecord, position: Int) {
        val actionMode = this.actionMode
        val actionModeCallback = ConversationActionModeCallback(adapter, viewModel.threadId, this)
        actionModeCallback.delegate = this
        searchViewItem?.collapseActionView()
        if (actionMode == null) { // Nothing should be selected if this is the case
            adapter.toggleSelection(message, position)
            this.actionMode = startActionMode(actionModeCallback, ActionMode.TYPE_PRIMARY)
        } else {
            adapter.toggleSelection(message, position)
            actionModeCallback.updateActionModeMenu(actionMode.menu)
            if (adapter.selectedItems.isEmpty()) {
                actionMode.finish()
                this.actionMode = null
            }
        }
    }

    private fun showEmojiPicker(message: MessageRecord, visibleMessageView: VisibleMessageView) {
        val messageContentBitmap = try {
            visibleMessageView.messageContentView.drawToBitmap()
        } catch (e: Exception) {
            Log.e("Loki", "Failed to show emoji picker", e)
            return
        }

        val binding = binding ?: return

        emojiPickerVisible = true
        ViewUtil.hideKeyboard(this, visibleMessageView)
        binding.reactionsShade.isVisible = true
        binding.scrollToBottomButton.isVisible = false
        binding.conversationRecyclerView.suppressLayout(true)
        reactionDelegate.setOnActionSelectedListener(ReactionsToolbarListener(message))
        reactionDelegate.setOnHideListener(object: ConversationReactionOverlay.OnHideListener {
            override fun startHide() {
                emojiPickerVisible = false
                binding.reactionsShade.let {
                    ViewUtil.fadeOut(it, resources.getInteger(R.integer.reaction_scrubber_hide_duration), View.GONE)
                }
                showScrollToBottomButtonIfApplicable()
            }

            override fun onHide() {
                binding.conversationRecyclerView.suppressLayout(false)

                WindowUtil.setLightStatusBarFromTheme(this@ConversationActivityV2);
                WindowUtil.setLightNavigationBarFromTheme(this@ConversationActivityV2);
            }

        })
        val topLeft = intArrayOf(0, 0).also { visibleMessageView.messageContentView.getLocationInWindow(it) }
        val selectedConversationModel = SelectedConversationModel(
            messageContentBitmap,
            topLeft[0].toFloat(),
            topLeft[1].toFloat(),
            visibleMessageView.messageContentView.width,
            message.isOutgoing,
            visibleMessageView.messageContentView
        )
        reactionDelegate.show(this, message, selectedConversationModel, viewModel.blindedPublicKey)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return reactionDelegate.applyTouchEvent(ev) || super.dispatchTouchEvent(ev)
    }

    override fun onReactionSelected(messageRecord: MessageRecord, emoji: String) {
        reactionDelegate.hide()
        val oldRecord = messageRecord.reactions.find { it.author == textSecurePreferences.getLocalNumber() }
        if (oldRecord != null && oldRecord.emoji == emoji) {
            sendEmojiRemoval(emoji, messageRecord)
        } else {
            sendEmojiReaction(emoji, messageRecord)
            RecentEmojiPageModel.onCodePointSelected(emoji) // Save to recently used reaction emojis
        }
    }

    private fun sendEmojiReaction(emoji: String, originalMessage: MessageRecord) {
        // Create the message
        val recipient = viewModel.recipient ?: return
        val reactionMessage = VisibleMessage()
        val emojiTimestamp = SnodeAPI.nowWithOffset
        reactionMessage.sentTimestamp = emojiTimestamp
        val author = textSecurePreferences.getLocalNumber()!!
        // Put the message in the database
        val reaction = ReactionRecord(
            messageId = originalMessage.id,
            isMms = originalMessage.isMms,
            author = author,
            emoji = emoji,
            count = 1,
            dateSent = emojiTimestamp,
            dateReceived = emojiTimestamp
        )
        reactionDb.addReaction(MessageId(originalMessage.id, originalMessage.isMms), reaction, false)
        val originalAuthor = if (originalMessage.isOutgoing) {
            fromSerialized(viewModel.blindedPublicKey ?: textSecurePreferences.getLocalNumber()!!)
        } else originalMessage.individualRecipient.address
        // Send it
        reactionMessage.reaction = Reaction.from(originalMessage.timestamp, originalAuthor.serialize(), emoji, true)
        if (recipient.isCommunityRecipient) {
            val messageServerId = lokiMessageDb.getServerID(originalMessage.id, !originalMessage.isMms) ?: return
            viewModel.openGroup?.let {
                OpenGroupApi.addReaction(it.room, it.server, messageServerId, emoji)
            }
        } else {
            MessageSender.send(reactionMessage, recipient.address)
        }
        LoaderManager.getInstance(this).restartLoader(0, null, this)
    }

    private fun sendEmojiRemoval(emoji: String, originalMessage: MessageRecord) {
        val recipient = viewModel.recipient ?: return
        val message = VisibleMessage()
        val emojiTimestamp = SnodeAPI.nowWithOffset
        message.sentTimestamp = emojiTimestamp
        val author = textSecurePreferences.getLocalNumber()!!
        reactionDb.deleteReaction(emoji, MessageId(originalMessage.id, originalMessage.isMms), author, false)

        val originalAuthor = if (originalMessage.isOutgoing) {
            fromSerialized(viewModel.blindedPublicKey ?: textSecurePreferences.getLocalNumber()!!)
        } else originalMessage.individualRecipient.address

        message.reaction = Reaction.from(originalMessage.timestamp, originalAuthor.serialize(), emoji, false)
        if (recipient.isCommunityRecipient) {
            val messageServerId = lokiMessageDb.getServerID(originalMessage.id, !originalMessage.isMms) ?: return
            viewModel.openGroup?.let {
                OpenGroupApi.deleteReaction(it.room, it.server, messageServerId, emoji)
            }
        } else {
            MessageSender.send(message, recipient.address)
        }
        LoaderManager.getInstance(this).restartLoader(0, null, this)
    }

    override fun onCustomReactionSelected(messageRecord: MessageRecord, hasAddedCustomEmoji: Boolean) {
        val oldRecord = messageRecord.reactions.find { record -> record.author == textSecurePreferences.getLocalNumber() }

        if (oldRecord != null && hasAddedCustomEmoji) {
            reactionDelegate.hide()
            sendEmojiRemoval(oldRecord.emoji, messageRecord)
        } else {
            reactionDelegate.hideForReactWithAny()

            ReactWithAnyEmojiDialogFragment
                .createForMessageRecord(messageRecord, reactWithAnyEmojiStartPage)
                .show(supportFragmentManager, "BOTTOM");
        }
    }

    override fun onReactWithAnyEmojiDialogDismissed() {
        reactionDelegate.hide()
    }

    override fun onReactWithAnyEmojiSelected(emoji: String, messageId: MessageId) {
        reactionDelegate.hide()
        val message = if (messageId.mms) {
            mmsDb.getMessageRecord(messageId.id)
        } else {
            smsDb.getMessageRecord(messageId.id)
        }
        val oldRecord = reactionDb.getReactions(messageId).find { it.author == textSecurePreferences.getLocalNumber() }
        if (oldRecord?.emoji == emoji) {
            sendEmojiRemoval(emoji, message)
        } else {
            sendEmojiReaction(emoji, message)
        }
    }

    override fun onRemoveReaction(emoji: String, messageId: MessageId) {
        val message = if (messageId.mms) {
            mmsDb.getMessageRecord(messageId.id)
        } else {
            smsDb.getMessageRecord(messageId.id)
        }
        sendEmojiRemoval(emoji, message)
    }

    override fun onClearAll(emoji: String, messageId: MessageId) {
        reactionDb.deleteEmojiReactions(emoji, messageId)
        viewModel.openGroup?.let { openGroup ->
            lokiMessageDb.getServerID(messageId.id, !messageId.mms)?.let { serverId ->
                OpenGroupApi.deleteAllReactions(openGroup.room, openGroup.server, serverId, emoji)
            }
        }
        threadDb.notifyThreadUpdated(viewModel.threadId)
    }

    override fun onMicrophoneButtonMove(event: MotionEvent) {
        val rawX = event.rawX
        val chevronImageView = binding?.inputBarRecordingView?.chevronImageView ?: return
        val slideToCancelTextView = binding?.inputBarRecordingView?.slideToCancelTextView ?: return
        if (rawX < screenWidth / 2) {
            val translationX = rawX - screenWidth / 2
            val sign = -1.0f
            val chevronDamping = 4.0f
            val labelDamping = 3.0f
            val chevronX = (chevronDamping * (sqrt(abs(translationX)) / sqrt(chevronDamping))) * sign
            val labelX = (labelDamping * (sqrt(abs(translationX)) / sqrt(labelDamping))) * sign
            chevronImageView.translationX = chevronX
            slideToCancelTextView.translationX = labelX
        } else {
            chevronImageView.translationX = 0.0f
            slideToCancelTextView.translationX = 0.0f
        }
        if (isValidLockViewLocation(event.rawX.roundToInt(), event.rawY.roundToInt())) {
            if (!isLockViewExpanded) {
                expandVoiceMessageLockView()
                isLockViewExpanded = true
            }
        } else {
            if (isLockViewExpanded) {
                collapseVoiceMessageLockView()
                isLockViewExpanded = false
            }
        }
    }

    override fun onMicrophoneButtonCancel(event: MotionEvent) {
        hideVoiceMessageUI()
    }

    override fun onMicrophoneButtonUp(event: MotionEvent) {
        val x = event.rawX.roundToInt()
        val y = event.rawY.roundToInt()
        if (isValidLockViewLocation(x, y)) {
            binding?.inputBarRecordingView?.lock()
        } else {
            val recordButtonOverlay = binding?.inputBarRecordingView?.recordButtonOverlay ?: return
            val location = IntArray(2) { 0 }
            recordButtonOverlay.getLocationOnScreen(location)
            val hitRect = Rect(location[0], location[1], location[0] + recordButtonOverlay.width, location[1] + recordButtonOverlay.height)
            if (hitRect.contains(x, y)) {
                sendVoiceMessage()
            } else {
                cancelVoiceMessage()
            }
        }
    }

    private fun isValidLockViewLocation(x: Int, y: Int): Boolean {
        // We can be anywhere above the lock view and a bit to the side of it (at most `lockViewHitMargin`
        // to the side)
        val binding = binding ?: return false
        val lockViewLocation = IntArray(2) { 0 }
        binding.inputBarRecordingView.lockView.getLocationOnScreen(lockViewLocation)
        val hitRect = Rect(lockViewLocation[0] - lockViewHitMargin, 0,
            lockViewLocation[0] + binding.inputBarRecordingView.lockView.width + lockViewHitMargin, lockViewLocation[1] + binding.inputBarRecordingView.lockView.height)
        return hitRect.contains(x, y)
    }

    private fun handleMentionSelected(mention: Mention) {
        val binding = binding ?: return
        if (currentMentionStartIndex == -1) { return }
        mentions.add(mention)
        val previousText = binding.inputBar.text
        val newText = previousText.substring(0, currentMentionStartIndex) + "@" + mention.displayName + " "
        binding.inputBar.text = newText
        binding.inputBar.setSelection(newText.length)
        currentMentionStartIndex = -1
        hideMentionCandidates()
        this.previousText = newText
    }

    override fun scrollToMessageIfPossible(timestamp: Long) {
        val lastSeenItemPosition = adapter.getItemPositionForTimestamp(timestamp) ?: return
        binding?.conversationRecyclerView?.scrollToPosition(lastSeenItemPosition)
    }

    override fun onReactionClicked(emoji: String, messageId: MessageId, userWasSender: Boolean) {
        val message = if (messageId.mms) {
            mmsDb.getMessageRecord(messageId.id)
        } else {
            smsDb.getMessageRecord(messageId.id)
        }
        if (userWasSender) {
            sendEmojiRemoval(emoji, message)
        } else {
            sendEmojiReaction(emoji, message)
        }
    }

    override fun onReactionLongClicked(messageId: MessageId) {
        if (viewModel.recipient?.isGroupRecipient == true) {
            val isUserModerator = viewModel.openGroup?.let { openGroup ->
                val userPublicKey = textSecurePreferences.getLocalNumber() ?: return@let false
                OpenGroupManager.isUserModerator(this, openGroup.id, userPublicKey, viewModel.blindedPublicKey)
            } ?: false
            val fragment = ReactionsDialogFragment.create(messageId, isUserModerator)
            fragment.show(supportFragmentManager, null)
        }
    }

    override fun playVoiceMessageAtIndexIfPossible(indexInAdapter: Int) {
        if (!textSecurePreferences.autoplayAudioMessages()) return

        if (indexInAdapter < 0 || indexInAdapter >= adapter.itemCount) { return }
        val viewHolder = binding?.conversationRecyclerView?.findViewHolderForAdapterPosition(indexInAdapter) as? ConversationAdapter.VisibleMessageViewHolder ?: return
        val visibleMessageView = ViewVisibleMessageBinding.bind(viewHolder.view).visibleMessageView
        visibleMessageView.playVoiceMessage()
    }

    override fun sendMessage() {
        val recipient = viewModel.recipient ?: return
        if (recipient.isContactRecipient && recipient.isBlocked) {
            BlockedDialog(recipient, this).show(supportFragmentManager, "Blocked Dialog")
            return
        }
        val binding = binding ?: return
        val sentMessageInfo = if (binding.inputBar.linkPreview != null || binding.inputBar.quote != null) {
            sendAttachments(listOf(), getMessageBody(), binding.inputBar.quote, binding.inputBar.linkPreview)
        } else {
            sendTextOnlyMessage()
        }

        // Jump to the newly sent message once it gets added
        if (sentMessageInfo != null) {
            messageToScrollAuthor.set(sentMessageInfo.first)
            messageToScrollTimestamp.set(sentMessageInfo.second)
        }
    }

    override fun commitInputContent(contentUri: Uri) {
        val recipient = viewModel.recipient ?: return
        val media = Media(contentUri, MediaUtil.getMimeType(this, contentUri)!!, 0, 0, 0, 0, Optional.absent(), Optional.absent())
        startActivityForResult(MediaSendActivity.buildEditorIntent(this, listOf( media ), recipient, getMessageBody()), PICK_FROM_LIBRARY)
    }

    private fun processMessageRequestApproval() {
        if (isIncomingMessageRequestThread()) {
            acceptMessageRequest()
        } else if (viewModel.recipient?.isApproved == false) {
            // edge case for new outgoing thread on new recipient without sending approval messages
            viewModel.setRecipientApproved()
        }
    }

    private fun sendTextOnlyMessage(hasPermissionToSendSeed: Boolean = false): Pair<Address, Long>? {
        val recipient = viewModel.recipient ?: return null
        val sentTimestamp = SnodeAPI.nowWithOffset
        processMessageRequestApproval()
        val text = getMessageBody()
        val userPublicKey = textSecurePreferences.getLocalNumber()
        val isNoteToSelf = (recipient.isContactRecipient && recipient.address.toString() == userPublicKey)
        if (text.contains(seed) && !isNoteToSelf && !hasPermissionToSendSeed) {
            val dialog = SendSeedDialog { sendTextOnlyMessage(true) }
            dialog.show(supportFragmentManager, "Send Seed Dialog")
            return null
        }
        // Create the message
        val message = VisibleMessage().applyExpiryMode(viewModel.threadId)
        message.sentTimestamp = sentTimestamp
        message.text = text
        val expiresInMillis = viewModel.expirationConfiguration?.expiryMode?.expiryMillis ?: 0
        val expireStartedAt = if (viewModel.expirationConfiguration?.expiryMode is ExpiryMode.AfterSend) {
            message.sentTimestamp!!
        } else 0
        val outgoingTextMessage = OutgoingTextMessage.from(message, recipient, expiresInMillis, expireStartedAt)
        // Clear the input bar
        binding?.inputBar?.text = ""
        binding?.inputBar?.cancelQuoteDraft()
        binding?.inputBar?.cancelLinkPreviewDraft()
        // Clear mentions
        previousText = ""
        currentMentionStartIndex = -1
        mentions.clear()
        // Put the message in the database
        message.id = smsDb.insertMessageOutbox(viewModel.threadId, outgoingTextMessage, false, message.sentTimestamp!!, null, true)
        // Send it
        MessageSender.send(message, recipient.address)
        // Send a typing stopped message
        ApplicationContext.getInstance(this).typingStatusSender.onTypingStopped(viewModel.threadId)
        return Pair(recipient.address, sentTimestamp)
    }

    private fun sendAttachments(
        attachments: List<Attachment>,
        body: String?,
        quotedMessage: MessageRecord? = binding?.inputBar?.quote,
        linkPreview: LinkPreview? = null
    ): Pair<Address, Long>? {
        val recipient = viewModel.recipient ?: return null
        val sentTimestamp = SnodeAPI.nowWithOffset
        processMessageRequestApproval()
        // Create the message
        val message = VisibleMessage().applyExpiryMode(viewModel.threadId)
        message.sentTimestamp = sentTimestamp
        message.text = body
        val quote = quotedMessage?.let {
            val quotedAttachments = (it as? MmsMessageRecord)?.slideDeck?.asAttachments() ?: listOf()
            val sender = if (it.isOutgoing) {
                fromSerialized(viewModel.blindedPublicKey ?: textSecurePreferences.getLocalNumber()!!)
            } else it.individualRecipient.address
            QuoteModel(it.dateSent, sender, it.body, false, quotedAttachments)
        }
        val localQuote = quotedMessage?.let {
            val sender =
                if (it.isOutgoing) fromSerialized(textSecurePreferences.getLocalNumber()!!)
                else it.individualRecipient.address
            quote?.copy(author = sender)
        }
        val expiresInMs = viewModel.expirationConfiguration?.expiryMode?.expiryMillis ?: 0
        val expireStartedAtMs = if (viewModel.expirationConfiguration?.expiryMode is ExpiryMode.AfterSend) {
            sentTimestamp
        } else 0
        val outgoingTextMessage = OutgoingMediaMessage.from(message, recipient, attachments, localQuote, linkPreview, expiresInMs, expireStartedAtMs)
        // Clear the input bar
        binding?.inputBar?.text = ""
        binding?.inputBar?.cancelQuoteDraft()
        binding?.inputBar?.cancelLinkPreviewDraft()
        // Clear mentions
        previousText = ""
        currentMentionStartIndex = -1
        mentions.clear()
        // Reset the attachment manager
        attachmentManager.clear()
        // Reset attachments button if needed
        if (isShowingAttachmentOptions) { toggleAttachmentOptions() }
        // Put the message in the database
        message.id = mmsDb.insertMessageOutbox(outgoingTextMessage, viewModel.threadId, false, null, runThreadUpdate = true)
        // Send it
        MessageSender.send(message, recipient.address, attachments, quote, linkPreview)
        // Send a typing stopped message
        ApplicationContext.getInstance(this).typingStatusSender.onTypingStopped(viewModel.threadId)
        return Pair(recipient.address, sentTimestamp)
    }

    private fun showGIFPicker() {
        val hasSeenGIFMetaDataWarning: Boolean = textSecurePreferences.hasSeenGIFMetaDataWarning()
        if (!hasSeenGIFMetaDataWarning) {
            showSessionDialog {
                title(R.string.giphy_permission_title)
                text(R.string.giphy_permission_message)
                button(R.string.continue_2) {
                    textSecurePreferences.setHasSeenGIFMetaDataWarning()
                    selectGif()
                }
                cancelButton()
            }
        } else {
            selectGif()
        }
    }

    private fun selectGif() = AttachmentManager.selectGif(this, PICK_GIF)

    private fun showDocumentPicker() {
        AttachmentManager.selectDocument(this, PICK_DOCUMENT)
    }

    private fun pickFromLibrary() {
        val recipient = viewModel.recipient ?: return
        binding?.inputBar?.text?.trim()?.let { text ->
            AttachmentManager.selectGallery(this, PICK_FROM_LIBRARY, recipient, text)
        }
    }

    private fun showCamera() {
        attachmentManager.capturePhoto(this, TAKE_PHOTO, viewModel.recipient);
    }

    override fun onAttachmentChanged() {
        // Do nothing
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        val mediaPreppedListener = object : ListenableFuture.Listener<Boolean> {

            override fun onSuccess(result: Boolean?) {
                sendAttachments(attachmentManager.buildSlideDeck().asAttachments(), null)
            }

            override fun onFailure(e: ExecutionException?) {
                Toast.makeText(this@ConversationActivityV2, R.string.activity_conversation_attachment_prep_failed, Toast.LENGTH_LONG).show()
            }
        }
        when (requestCode) {
            PICK_DOCUMENT -> {
                val uri = intent?.data ?: return
                prepMediaForSending(uri, AttachmentManager.MediaType.DOCUMENT).addListener(mediaPreppedListener)
            }
            PICK_GIF -> {
                intent ?: return
                val uri = intent.data ?: return
                val type = AttachmentManager.MediaType.GIF
                val width = intent.getIntExtra(GiphyActivity.EXTRA_WIDTH, 0)
                val height = intent.getIntExtra(GiphyActivity.EXTRA_HEIGHT, 0)
                prepMediaForSending(uri, type, width, height).addListener(mediaPreppedListener)
            }
            PICK_FROM_LIBRARY,
            TAKE_PHOTO -> {
                intent ?: return
                val body = intent.getStringExtra(MediaSendActivity.EXTRA_MESSAGE)
                val media = intent.getParcelableArrayListExtra<Media>(MediaSendActivity.EXTRA_MEDIA) ?: return
                val slideDeck = SlideDeck()
                for (item in media) {
                    when {
                        MediaUtil.isVideoType(item.mimeType) -> {
                            slideDeck.addSlide(VideoSlide(this, item.uri, 0, item.caption.orNull()))
                        }
                        MediaUtil.isGif(item.mimeType) -> {
                            slideDeck.addSlide(GifSlide(this, item.uri, 0, item.width, item.height, item.caption.orNull()))
                        }
                        MediaUtil.isImageType(item.mimeType) -> {
                            slideDeck.addSlide(ImageSlide(this, item.uri, 0, item.width, item.height, item.caption.orNull()))
                        }
                        else -> {
                            Log.d("Loki", "Asked to send an unexpected media type: '" + item.mimeType + "'. Skipping.")
                        }
                    }
                }
                sendAttachments(slideDeck.asAttachments(), body)
            }
            INVITE_CONTACTS -> {
                if (viewModel.recipient?.isCommunityRecipient != true) { return }
                val extras = intent?.extras ?: return
                if (!intent.hasExtra(selectedContactsKey)) { return }
                val selectedContacts = extras.getStringArray(selectedContactsKey)!!
                val recipients = selectedContacts.map { contact ->
                    Recipient.from(this, fromSerialized(contact), true)
                }
                viewModel.inviteContacts(recipients)
            }
        }
    }

    private fun prepMediaForSending(uri: Uri, type: AttachmentManager.MediaType): ListenableFuture<Boolean> {
        return prepMediaForSending(uri, type, null, null)
    }

    private fun prepMediaForSending(uri: Uri, type: AttachmentManager.MediaType, width: Int?, height: Int?): ListenableFuture<Boolean> {
        return attachmentManager.setMedia(glide, uri, type, MediaConstraints.getPushMediaConstraints(), width ?: 0, height ?: 0)
    }

    override fun startRecordingVoiceMessage() {
        if (Permissions.hasAll(this, Manifest.permission.RECORD_AUDIO)) {
            showVoiceMessageUI()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            audioRecorder.startRecording()
            stopAudioHandler.postDelayed(stopVoiceMessageRecordingTask, 300000) // Limit voice messages to 5 minute each
        } else {
            Permissions.with(this)
                .request(Manifest.permission.RECORD_AUDIO)
                .withRationaleDialog(getString(R.string.ConversationActivity_to_send_audio_messages_allow_signal_access_to_your_microphone), R.drawable.ic_baseline_mic_48)
                .withPermanentDenialDialog(getString(R.string.ConversationActivity_signal_requires_the_microphone_permission_in_order_to_send_audio_messages))
                .execute()
        }
    }

    override fun sendVoiceMessage() {
        hideVoiceMessageUI()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val future = audioRecorder.stopRecording()
        stopAudioHandler.removeCallbacks(stopVoiceMessageRecordingTask)
        future.addListener(object : ListenableFuture.Listener<Pair<Uri, Long>> {

            override fun onSuccess(result: Pair<Uri, Long>) {
                val audioSlide = AudioSlide(this@ConversationActivityV2, result.first, result.second, MediaTypes.AUDIO_AAC, true)
                val slideDeck = SlideDeck()
                slideDeck.addSlide(audioSlide)
                sendAttachments(slideDeck.asAttachments(), null)
            }

            override fun onFailure(e: ExecutionException) {
                Toast.makeText(this@ConversationActivityV2, R.string.ConversationActivity_unable_to_record_audio, Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun cancelVoiceMessage() {
        hideVoiceMessageUI()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audioRecorder.stopRecording()
        stopAudioHandler.removeCallbacks(stopVoiceMessageRecordingTask)
    }

    override fun selectMessages(messages: Set<MessageRecord>) {
        handleLongPress(messages.first(), 0) //TODO: begin selection mode
    }

    // The option to "Delete just for me" or "Delete for everyone"
    private fun showDeleteOrDeleteForEveryoneInCommunityUI(messages: Set<MessageRecord>) {
        val bottomSheet = DeleteOptionsBottomSheet()
        bottomSheet.recipient = viewModel.recipient!!
        bottomSheet.onDeleteForMeTapped = {
            messages.forEach(viewModel::deleteLocally)
            bottomSheet.dismiss()
            endActionMode()
        }
        bottomSheet.onDeleteForEveryoneTapped = {
            messages.forEach(viewModel::deleteForEveryone)
            bottomSheet.dismiss()
            endActionMode()
        }
        bottomSheet.onCancelTapped = {
            bottomSheet.dismiss()
            endActionMode()
        }
        bottomSheet.show(supportFragmentManager, bottomSheet.tag)
    }

    private fun showDeleteLocallyUI(messages: Set<MessageRecord>) {
        val messageCount = 1
        showSessionDialog {
            title(resources.getQuantityString(R.plurals.ConversationFragment_delete_selected_messages, messageCount, messageCount))
            text(resources.getQuantityString(R.plurals.ConversationFragment_this_will_permanently_delete_all_n_selected_messages, messageCount, messageCount))
            button(R.string.delete) { messages.forEach(viewModel::deleteLocally); endActionMode() }
            cancelButton(::endActionMode)
        }
    }

    // Note: The messages in the provided set may be a single message, or multiple if there are a
    // group of selected messages.
    override fun deleteMessages(messages: Set<MessageRecord>) {
        val recipient = viewModel.recipient
        if (recipient == null) {
            Log.w("ConversationActivityV2", "Asked to delete messages but could not obtain viewModel recipient - aborting.")
            return
        }

        val allSentByCurrentUser = messages.all { it.isOutgoing }
        val allHasHash = messages.all { lokiMessageDb.getMessageServerHash(it.id, it.isMms) != null }

        // If the recipient is a community OR a Note-to-Self then we delete the message for everyone
        if (recipient.isCommunityRecipient || recipient.isLocalNumber) {
            val messageCount = 1 // Only used for plurals string
            showSessionDialog {
                title(resources.getQuantityString(R.plurals.ConversationFragment_delete_selected_messages, messageCount, messageCount))
                text(resources.getQuantityString(R.plurals.ConversationFragment_this_will_permanently_delete_all_n_selected_messages, messageCount, messageCount))
                button(R.string.delete) {
                    messages.forEach(viewModel::deleteForEveryone); endActionMode()
                }
                cancelButton { endActionMode() }
            }
        // Otherwise if this is a 1-on-1 conversation we may decided to delete just for ourselves or delete for everyone
        } else if (allSentByCurrentUser && allHasHash) {
            val bottomSheet = DeleteOptionsBottomSheet()
            bottomSheet.recipient = recipient
            bottomSheet.onDeleteForMeTapped = {
                messages.forEach(viewModel::deleteLocally)
                bottomSheet.dismiss()
                endActionMode()
            }
            bottomSheet.onDeleteForEveryoneTapped = {
                messages.forEach(viewModel::deleteForEveryone)
                bottomSheet.dismiss()
                endActionMode()
            }
            bottomSheet.onCancelTapped = {
                bottomSheet.dismiss()
                endActionMode()
            }
            bottomSheet.show(supportFragmentManager, bottomSheet.tag)
        }
        else // Finally, if this is a closed group and you are deleting someone else's message(s) then we can only delete locally.
        {
            val messageCount = 1
            showSessionDialog {
                title(resources.getQuantityString(R.plurals.ConversationFragment_delete_selected_messages, messageCount, messageCount))
                text(resources.getQuantityString(R.plurals.ConversationFragment_this_will_permanently_delete_all_n_selected_messages, messageCount, messageCount))
                button(R.string.delete) {
                    messages.forEach(viewModel::deleteLocally); endActionMode()
                }
                cancelButton(::endActionMode)
            }
        }
    }

    override fun banUser(messages: Set<MessageRecord>) {
        showSessionDialog {
            title(R.string.ConversationFragment_ban_selected_user)
            text("This will ban the selected user from this room. It won't ban them from other rooms.")
            button(R.string.ban) { viewModel.banUser(messages.first().individualRecipient); endActionMode() }
            cancelButton(::endActionMode)
        }
    }

    override fun banAndDeleteAll(messages: Set<MessageRecord>) {
        showSessionDialog {
            title(R.string.ConversationFragment_ban_selected_user)
            text("This will ban the selected user from this room and delete all messages sent by them. It won't ban them from other rooms or delete the messages they sent there.")
            button(R.string.ban) { viewModel.banAndDeleteAll(messages.first()); endActionMode() }
            cancelButton(::endActionMode)
        }
    }

    override fun copyMessages(messages: Set<MessageRecord>) {
        val sortedMessages = messages.sortedBy { it.dateSent }
        val messageSize = sortedMessages.size
        val builder = StringBuilder()
        val messageIterator = sortedMessages.iterator()
        while (messageIterator.hasNext()) {
            val message = messageIterator.next()
            val body = MentionUtilities.highlightMentions(message.body, viewModel.threadId, this)
            if (TextUtils.isEmpty(body)) { continue }
            if (messageSize > 1) {
                val formattedTimestamp = DateUtils.getDisplayFormattedTimeSpanString(this, Locale.getDefault(), message.timestamp)
                builder.append("$formattedTimestamp: ")
            }
            builder.append(body)
            if (messageIterator.hasNext()) {
                builder.append('\n')
            }
        }
        if (builder.isNotEmpty() && builder[builder.length - 1] == '\n') {
            builder.deleteCharAt(builder.length - 1)
        }
        val result = builder.toString()
        if (TextUtils.isEmpty(result)) { return }
        val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Message Content", result))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        endActionMode()
    }

    override fun copySessionID(messages: Set<MessageRecord>) {
        val sessionID = messages.first().individualRecipient.address.toString()
        val clip = ClipData.newPlainText("Session ID", sessionID)
        val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        endActionMode()
    }

    override fun resyncMessage(messages: Set<MessageRecord>) {
        messages.iterator().forEach { messageRecord ->
            ResendMessageUtilities.resend(this, messageRecord, viewModel.blindedPublicKey, isResync = true)
        }
        endActionMode()
    }

    override fun resendMessage(messages: Set<MessageRecord>) {
        messages.iterator().forEach { messageRecord ->
            ResendMessageUtilities.resend(this, messageRecord, viewModel.blindedPublicKey)
        }
        endActionMode()
    }

    private val handleMessageDetail = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        val message = result.data?.extras?.getLong(MESSAGE_TIMESTAMP)
            ?.let(mmsSmsDb::getMessageForTimestamp)

        val set = setOfNotNull(message)

        when (result.resultCode) {
            ON_REPLY -> reply(set)
            ON_RESEND -> resendMessage(set)
            ON_DELETE -> deleteMessages(set)
        }
    }

    override fun showMessageDetail(messages: Set<MessageRecord>) {
        Intent(this, MessageDetailActivity::class.java)
            .apply { putExtra(MESSAGE_TIMESTAMP, messages.first().timestamp) }
            .let { handleMessageDetail.launch(it) }

        endActionMode()
    }

    override fun saveAttachment(messages: Set<MessageRecord>) {
        val message = messages.first() as MmsMessageRecord

        // Do not allow the user to download a file attachment before it has finished downloading
        if (message.isMediaPending) {
            Toast.makeText(this, resources.getString(R.string.conversation_activity__wait_until_attachment_has_finished_downloading), Toast.LENGTH_LONG).show()
            return
        }

        SaveAttachmentTask.showWarningDialog(this) {
            Permissions.with(this)
                .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .maxSdkVersion(Build.VERSION_CODES.P)
                .withPermanentDenialDialog(getString(R.string.MediaPreviewActivity_signal_needs_the_storage_permission_in_order_to_write_to_external_storage_but_it_has_been_permanently_denied))
                .onAnyDenied {
                    endActionMode()
                    Toast.makeText(this@ConversationActivityV2, R.string.MediaPreviewActivity_unable_to_write_to_external_storage_without_permission, Toast.LENGTH_LONG).show()
                }
                .onAllGranted {
                    endActionMode()
                    val attachments: List<SaveAttachmentTask.Attachment?> = Stream.of(message.slideDeck.slides)
                        .filter { s: Slide -> s.uri != null && (s.hasImage() || s.hasVideo() || s.hasAudio() || s.hasDocument()) }
                        .map { s: Slide -> SaveAttachmentTask.Attachment(s.uri!!, s.contentType, message.dateReceived, s.fileName.orNull()) }
                        .toList()
                    if (attachments.isNotEmpty()) {
                        val saveTask = SaveAttachmentTask(this)
                        saveTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, *attachments.toTypedArray())
                        if (!message.isOutgoing) {
                            sendMediaSavedNotification()
                        }
                        return@onAllGranted
                    }
                    Toast.makeText(this,
                        resources.getQuantityString(R.plurals.ConversationFragment_error_while_saving_attachments_to_sd_card, 1),
                        Toast.LENGTH_LONG).show()
                }
                .execute()
        }
    }

    override fun reply(messages: Set<MessageRecord>) {
        val recipient = viewModel.recipient ?: return
        messages.firstOrNull()?.let { binding?.inputBar?.draftQuote(recipient, it, glide) }
        endActionMode()
    }

    override fun destroyActionMode() {
        this.actionMode = null
    }

    private fun sendScreenshotNotification() {
        val recipient = viewModel.recipient ?: return
        if (recipient.isGroupRecipient) return
        val kind = DataExtractionNotification.Kind.Screenshot()
        val message = DataExtractionNotification(kind)
        MessageSender.send(message, recipient.address)
    }

    private fun sendMediaSavedNotification() {
        val recipient = viewModel.recipient ?: return
        if (recipient.isGroupRecipient) { return }
        val timestamp = SnodeAPI.nowWithOffset
        val kind = DataExtractionNotification.Kind.MediaSaved(timestamp)
        val message = DataExtractionNotification(kind)
        MessageSender.send(message, recipient.address)
    }

    private fun endActionMode() {
        actionMode?.finish()
        actionMode = null
    }
    // endregion

    // region General
    private fun getMessageBody(): String {
        var result = binding?.inputBar?.text?.trim() ?: return ""
        for (mention in mentions) {
            try {
                val startIndex = result.indexOf("@" + mention.displayName)
                val endIndex = startIndex + mention.displayName.count() + 1 // + 1 to include the "@"
                result = result.substring(0, startIndex) + "@" + mention.publicKey + result.substring(endIndex)
            } catch (exception: Exception) {
                Log.d("Loki", "Failed to process mention due to error: $exception")
            }
        }
        return result
    }
    // endregion

    // region Search
    private fun setUpSearchResultObserver() {
        searchViewModel.searchResults.observe(this, Observer { result: SearchViewModel.SearchResult? ->
            if (result == null) return@Observer
            if (result.getResults().isNotEmpty()) {
                result.getResults()[result.position]?.let {
                    jumpToMessage(it.messageRecipient.address, it.sentTimestampMs, true) {
                        searchViewModel.onMissingResult() }
                }
            }
            binding?.searchBottomBar?.setData(result.position, result.getResults().size)
        })
    }

    fun onSearchOpened() {
        searchViewModel.onSearchOpened()
        binding?.searchBottomBar?.visibility = View.VISIBLE
        binding?.searchBottomBar?.setData(0, 0)
        binding?.inputBar?.visibility = View.INVISIBLE
    }

    fun onSearchClosed() {
        searchViewModel.onSearchClosed()
        binding?.searchBottomBar?.visibility = View.GONE
        binding?.inputBar?.visibility = View.VISIBLE
        adapter.onSearchQueryUpdated(null)
        invalidateOptionsMenu()
    }

    fun onSearchQueryUpdated(query: String) {
        searchViewModel.onQueryUpdated(query, viewModel.threadId)
        binding?.searchBottomBar?.showLoading()
        adapter.onSearchQueryUpdated(query)
    }

    override fun onSearchMoveUpPressed() {
        this.searchViewModel.onMoveUp()
    }

    override fun onSearchMoveDownPressed() {
        this.searchViewModel.onMoveDown()
    }

    private fun jumpToMessage(author: Address, timestamp: Long, highlight: Boolean, onMessageNotFound: Runnable?) {
        SimpleTask.run(lifecycle, {
            mmsSmsDb.getMessagePositionInConversation(viewModel.threadId, timestamp, author, reverseMessageList)
        }) { p: Int -> moveToMessagePosition(p, highlight, onMessageNotFound) }
    }

    private fun moveToMessagePosition(position: Int, highlight: Boolean, onMessageNotFound: Runnable?) {
        if (position >= 0) {
            binding?.conversationRecyclerView?.scrollToPosition(position)

            if (highlight) {
                runOnUiThread {
                    highlightViewAtPosition(position)
                }
            }
        } else {
            onMessageNotFound?.run()
        }
    }
    // endregion

    inner class ReactionsToolbarListener constructor(val message: MessageRecord) : OnActionSelectedListener {

        override fun onActionSelected(action: ConversationReactionOverlay.Action) {
            val selectedItems = setOf(message)
            when (action) {
                ConversationReactionOverlay.Action.REPLY -> reply(selectedItems)
                ConversationReactionOverlay.Action.RESYNC -> resyncMessage(selectedItems)
                ConversationReactionOverlay.Action.RESEND -> resendMessage(selectedItems)
                ConversationReactionOverlay.Action.DOWNLOAD -> saveAttachment(selectedItems)
                ConversationReactionOverlay.Action.COPY_MESSAGE -> copyMessages(selectedItems)
                ConversationReactionOverlay.Action.VIEW_INFO -> showMessageDetail(selectedItems)
                ConversationReactionOverlay.Action.SELECT -> selectMessages(selectedItems)
                ConversationReactionOverlay.Action.DELETE -> deleteMessages(selectedItems)
                ConversationReactionOverlay.Action.BAN_AND_DELETE_ALL -> banAndDeleteAll(selectedItems)
                ConversationReactionOverlay.Action.BAN_USER -> banUser(selectedItems)
                ConversationReactionOverlay.Action.COPY_SESSION_ID -> copySessionID(selectedItems)
            }
        }
    }

    // AdapterDataObserver implementation to scroll us to the bottom of the ConversationRecyclerView
    // when we're already near the bottom and we send or receive a message.
    inner class ConversationAdapterDataObserver(val recyclerView: ConversationRecyclerView, val adapter: ConversationAdapter) : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            super.onChanged()
            if (recyclerView.isScrolledToWithin30dpOfBottom) {
                // Note: The adapter itemCount is zero based - so calling this with the itemCount in
                // a non-zero based manner scrolls us to the bottom of the last message (including
                // to the bottom of long messages as required by Jira SES-789 / GitHub 1364).
                recyclerView.scrollToPosition(adapter.itemCount)
            }
        }
    }

}