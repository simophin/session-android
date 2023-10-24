package org.session.libsession.messaging.jobs

import org.session.libsession.messaging.utilities.Data

class InviteContactJob(val groupSessionId: String, val memberSessionId: String): Job {

    companion object {
        const val KEY = "InviteContactJob"
        private const val GROUP = "group"
        private const val MEMBER = "member"
    }

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0
    override val maxFailureCount: Int = 1

    override suspend fun execute(dispatcherName: String) {
        TODO("Not yet implemented")
    }

    override fun serialize(): Data =
        Data.Builder()
            .putString(GROUP, groupSessionId)
            .putString(MEMBER, memberSessionId)
            .build()

    override fun getFactoryKey(): String = KEY

}