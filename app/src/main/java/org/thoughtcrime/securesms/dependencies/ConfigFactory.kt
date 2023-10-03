package org.thoughtcrime.securesms.dependencies

import android.content.Context
import android.os.Trace
import network.loki.messenger.libsession_util.Config
import network.loki.messenger.libsession_util.ConfigBase
import network.loki.messenger.libsession_util.Contacts
import network.loki.messenger.libsession_util.ConversationVolatileConfig
import network.loki.messenger.libsession_util.GroupInfoConfig
import network.loki.messenger.libsession_util.GroupKeysConfig
import network.loki.messenger.libsession_util.GroupMembersConfig
import network.loki.messenger.libsession_util.UserGroupsConfig
import network.loki.messenger.libsession_util.UserProfile
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.ConfigFactoryProtocol
import org.session.libsession.utilities.ConfigFactoryUpdateListener
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.protos.SignalServiceProtos.SharedConfigMessage
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.SessionId
import org.thoughtcrime.securesms.database.ConfigDatabase
import org.thoughtcrime.securesms.dependencies.DatabaseComponent.Companion.get
import org.thoughtcrime.securesms.groups.GroupManager
import org.thoughtcrime.securesms.util.ConfigurationMessageUtilities

class ConfigFactory(
    private val context: Context,
    private val configDatabase: ConfigDatabase,
    private val maybeGetUserInfo: () -> Pair<ByteArray, String>?
) :
    ConfigFactoryProtocol {
    companion object {
        // This is a buffer period within which we will process messages which would result in a
        // config change, any message which would normally result in a config change which was sent
        // before `lastConfigMessage.timestamp - configChangeBufferPeriod` will not  actually have
        // it's changes applied (control text will still be added though)
        val configChangeBufferPeriod: Long = (2 * 60 * 1000)
    }

    fun keyPairChanged() { // this should only happen restoring or clearing data
        _userConfig?.free()
        _contacts?.free()
        _convoVolatileConfig?.free()
        _userConfig = null
        _contacts = null
        _convoVolatileConfig = null
    }

    private val userLock = Object()
    private var _userConfig: UserProfile? = null
    private val contactsLock = Object()
    private var _contacts: Contacts? = null
    private val convoVolatileLock = Object()
    private var _convoVolatileConfig: ConversationVolatileConfig? = null
    private val userGroupsLock = Object()
    private var _userGroups: UserGroupsConfig? = null

    private val isConfigForcedOn by lazy { TextSecurePreferences.hasForcedNewConfig(context) }

    private val listeners: MutableList<ConfigFactoryUpdateListener> = mutableListOf()
    fun registerListener(listener: ConfigFactoryUpdateListener) {
        listeners += listener
    }

    fun unregisterListener(listener: ConfigFactoryUpdateListener) {
        listeners -= listener
    }

    private inline fun <T> synchronizedWithLog(lock: Any, body: () -> T): T {
        Trace.beginSection("synchronizedWithLog")
        val result = synchronized(lock) {
            body()
        }
        Trace.endSection()
        return result
    }

    override val user: UserProfile?
        get() = synchronizedWithLog(userLock) {
            if (!ConfigBase.isNewConfigEnabled(
                    isConfigForcedOn,
                    SnodeAPI.nowWithOffset
                )
            ) return null
            if (_userConfig == null) {
                val (secretKey, publicKey) = maybeGetUserInfo() ?: return null
                val userDump = configDatabase.retrieveConfigAndHashes(
                    SharedConfigMessage.Kind.USER_PROFILE.name,
                    publicKey
                )
                _userConfig = if (userDump != null) {
                    UserProfile.newInstance(secretKey, userDump)
                } else {
                    ConfigurationMessageUtilities.generateUserProfileConfigDump()?.let { dump ->
                        UserProfile.newInstance(secretKey, dump)
                    } ?: UserProfile.newInstance(secretKey)
                }
            }
            _userConfig
        }

    override val contacts: Contacts?
        get() = synchronizedWithLog(contactsLock) {
            if (!ConfigBase.isNewConfigEnabled(
                    isConfigForcedOn,
                    SnodeAPI.nowWithOffset
                )
            ) return null
            if (_contacts == null) {
                val (secretKey, publicKey) = maybeGetUserInfo() ?: return null
                val contactsDump = configDatabase.retrieveConfigAndHashes(
                    SharedConfigMessage.Kind.CONTACTS.name,
                    publicKey
                )
                _contacts = if (contactsDump != null) {
                    Contacts.newInstance(secretKey, contactsDump)
                } else {
                    ConfigurationMessageUtilities.generateContactConfigDump()?.let { dump ->
                        Contacts.newInstance(secretKey, dump)
                    } ?: Contacts.newInstance(secretKey)
                }
            }
            _contacts
        }

    override val convoVolatile: ConversationVolatileConfig?
        get() = synchronizedWithLog(convoVolatileLock) {
            if (!ConfigBase.isNewConfigEnabled(
                    isConfigForcedOn,
                    SnodeAPI.nowWithOffset
                )
            ) return null
            if (_convoVolatileConfig == null) {
                val (secretKey, publicKey) = maybeGetUserInfo() ?: return null
                val convoDump = configDatabase.retrieveConfigAndHashes(
                    SharedConfigMessage.Kind.CONVO_INFO_VOLATILE.name,
                    publicKey
                )
                _convoVolatileConfig = if (convoDump != null) {
                    ConversationVolatileConfig.newInstance(secretKey, convoDump)
                } else {
                    ConfigurationMessageUtilities.generateConversationVolatileDump(context)
                        ?.let { dump ->
                            ConversationVolatileConfig.newInstance(secretKey, dump)
                        } ?: ConversationVolatileConfig.newInstance(secretKey)
                }
            }
            _convoVolatileConfig
        }

    override val userGroups: UserGroupsConfig?
        get() = synchronizedWithLog(userGroupsLock) {
            if (!ConfigBase.isNewConfigEnabled(
                    isConfigForcedOn,
                    SnodeAPI.nowWithOffset
                )
            ) return null
            if (_userGroups == null) {
                val (secretKey, publicKey) = maybeGetUserInfo() ?: return null
                val userGroupsDump = configDatabase.retrieveConfigAndHashes(
                    SharedConfigMessage.Kind.GROUPS.name,
                    publicKey
                )
                _userGroups = if (userGroupsDump != null) {
                    UserGroupsConfig.Companion.newInstance(secretKey, userGroupsDump)
                } else {
                    ConfigurationMessageUtilities.generateUserGroupDump(context)?.let { dump ->
                        UserGroupsConfig.Companion.newInstance(secretKey, dump)
                    } ?: UserGroupsConfig.newInstance(secretKey)
                }
            }
            _userGroups
        }

    private fun getGroupAuthInfo(groupSessionId: SessionId) = userGroups?.getClosedGroup(groupSessionId.hexString())?.let {
        it.adminKey to it.authData
    }

    override fun getGroupInfoConfig(groupSessionId: SessionId): GroupInfoConfig? = getGroupAuthInfo(groupSessionId)?.let { (sk, _) ->
        // get any potential initial dumps
        val dump = configDatabase.retrieveConfigAndHashes(
            SharedConfigMessage.Kind.CLOSED_GROUP_INFO.name,
            groupSessionId.hexString()
        ) ?: byteArrayOf()

        GroupInfoConfig.newInstance(Hex.fromStringCondensed(groupSessionId.publicKey), sk, dump)
    }

    override fun getGroupKeysConfig(groupSessionId: SessionId): GroupKeysConfig? = getGroupAuthInfo(groupSessionId)?.let { (sk, _) ->
        // Get the user info or return early
        val (userSk, _) = maybeGetUserInfo() ?: return@let null

        // Get the group info or return early
        val info = getGroupInfoConfig(groupSessionId) ?: return@let null

        // Get the group members or return early
        val members = getGroupMemberConfig(groupSessionId) ?: return@let null

        // Get the dump or empty
        val dump = configDatabase.retrieveConfigAndHashes(
            SharedConfigMessage.Kind.ENCRYPTION_KEYS.name,
            groupSessionId.hexString()
        ) ?: byteArrayOf()

        // Put it all together
        GroupKeysConfig.newInstance(
            userSk,
            Hex.fromStringCondensed(groupSessionId.publicKey),
            sk,
            dump,
            info,
            members
        )
    }

    override fun getGroupMemberConfig(groupSessionId: SessionId): GroupMembersConfig? = getGroupAuthInfo(groupSessionId)?.let { (sk, auth) ->
        // Get initial dump if we have one
        val dump = configDatabase.retrieveConfigAndHashes(
            SharedConfigMessage.Kind.CLOSED_GROUP_MEMBERS.name,
            groupSessionId.hexString()
        ) ?: byteArrayOf()

        GroupMembersConfig.newInstance(
            Hex.fromStringCondensed(groupSessionId.publicKey),
            sk,
            dump
        )
    }

    override fun constructGroupKeysConfig(
        groupSessionId: SessionId,
        info: GroupInfoConfig,
        members: GroupMembersConfig
    ): GroupKeysConfig? = getGroupAuthInfo(groupSessionId)?.let { (sk, _) ->
        val (userSk, _) = maybeGetUserInfo() ?: return null
        GroupKeysConfig.newInstance(
            userSk,
            Hex.fromStringCondensed(groupSessionId.hexString()),
            sk,
            info = info,
            members = members
        )
    }

    override fun getUserConfigs(): List<ConfigBase> =
        listOfNotNull(user, contacts, convoVolatile, userGroups)


    private fun persistUserConfigDump(timestamp: Long) = synchronized(userLock) {
        val dumped = user?.dump() ?: return
        val (_, publicKey) = maybeGetUserInfo() ?: return
        configDatabase.storeConfig(
            SharedConfigMessage.Kind.USER_PROFILE.name,
            publicKey,
            dumped,
            timestamp
        )
    }

    private fun persistContactsConfigDump(timestamp: Long) = synchronized(contactsLock) {
        val dumped = contacts?.dump() ?: return
        val (_, publicKey) = maybeGetUserInfo() ?: return
        configDatabase.storeConfig(
            SharedConfigMessage.Kind.CONTACTS.name,
            publicKey,
            dumped,
            timestamp
        )
    }

    private fun persistConvoVolatileConfigDump(timestamp: Long) = synchronized(convoVolatileLock) {
        val dumped = convoVolatile?.dump() ?: return
        val (_, publicKey) = maybeGetUserInfo() ?: return
        configDatabase.storeConfig(
            SharedConfigMessage.Kind.CONVO_INFO_VOLATILE.name,
            publicKey,
            dumped,
            timestamp
        )
    }

    private fun persistUserGroupsConfigDump(timestamp: Long) = synchronized(userGroupsLock) {
        val dumped = userGroups?.dump() ?: return
        val (_, publicKey) = maybeGetUserInfo() ?: return
        configDatabase.storeConfig(
            SharedConfigMessage.Kind.GROUPS.name,
            publicKey,
            dumped,
            timestamp
        )
    }

    override fun persist(forConfigObject: Config, timestamp: Long) {
        try {
            listeners.forEach { listener ->
                listener.notifyUpdates(forConfigObject)
            }
            when (forConfigObject) {
                is UserProfile -> persistUserConfigDump(timestamp)
                is Contacts -> persistContactsConfigDump(timestamp)
                is ConversationVolatileConfig -> persistConvoVolatileConfigDump(timestamp)
                is UserGroupsConfig -> persistUserGroupsConfigDump(timestamp)
                else -> throw UnsupportedOperationException("Can't support type of ${forConfigObject::class.simpleName} yet")
            }
        } catch (e: Exception) {
            Log.e("Loki", "failed to persist ${forConfigObject.javaClass.simpleName}", e)
        }
    }

    override fun conversationInConfig(
        publicKey: String?,
        groupPublicKey: String?,
        openGroupId: String?,
        visibleOnly: Boolean
    ): Boolean {
        if (!ConfigBase.isNewConfigEnabled(isConfigForcedOn, SnodeAPI.nowWithOffset)) return true

        val (_, userPublicKey) = maybeGetUserInfo() ?: return true

        if (openGroupId != null) {
            val userGroups = userGroups ?: return false
            val threadId = GroupManager.getOpenGroupThreadID(openGroupId, context)
            val openGroup =
                get(context).lokiThreadDatabase().getOpenGroupChat(threadId) ?: return false

            // Not handling the `hidden` behaviour for communities so just indicate the existence
            return (userGroups.getCommunityInfo(openGroup.server, openGroup.room) != null)
        } else if (groupPublicKey != null) {
            val userGroups = userGroups ?: return false

            // Not handling the `hidden` behaviour for legacy groups so just indicate the existence
            return if (groupPublicKey.startsWith(IdPrefix.GROUP.value)) {
                userGroups.getClosedGroup(groupPublicKey) != null
            } else {
                userGroups.getLegacyGroupInfo(groupPublicKey) != null
            }
        } else if (publicKey == userPublicKey) {
            val user = user ?: return false

            return (!visibleOnly || user.getNtsPriority() != ConfigBase.PRIORITY_HIDDEN)
        } else if (publicKey != null) {
            val contacts = contacts ?: return false
            val targetContact = contacts.get(publicKey) ?: return false

            return (!visibleOnly || targetContact.priority != ConfigBase.PRIORITY_HIDDEN)
        }

        return false
    }

    override fun canPerformChange(
        variant: String,
        publicKey: String,
        changeTimestampMs: Long
    ): Boolean {
        if (!ConfigBase.isNewConfigEnabled(isConfigForcedOn, SnodeAPI.nowWithOffset)) return true

        val lastUpdateTimestampMs =
            configDatabase.retrieveConfigLastUpdateTimestamp(variant, publicKey)

        // Ensure the change occurred after the last config message was handled (minus the buffer period)
        return (changeTimestampMs >= (lastUpdateTimestampMs - ConfigFactory.configChangeBufferPeriod))
    }

    override fun saveGroupConfigs(
        groupKeys: GroupKeysConfig,
        groupInfo: GroupInfoConfig,
        groupMembers: GroupMembersConfig
    ) {
        val pubKey = groupInfo.id().hexString()
        val timestamp = SnodeAPI.nowWithOffset
        configDatabase.storeGroupConfigs(pubKey, groupKeys.dump(), groupInfo.dump(), groupMembers.dump(), timestamp)
    }

    override fun scheduleUpdate(destination: Destination) {
        // there's probably a better way to do this
        ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(destination)
    }
}