package org.session.libsession.messaging.jobs

import org.session.libsession.messaging.utilities.Data
import org.session.libsignal.utilities.Log

class GroupLeavingJob(val groupPublicKey: String, val deleteThread: Boolean): Job {

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    override val maxFailureCount: Int = 0

    companion object {
        val TAG = GroupLeavingJob::class.simpleName
        val KEY: String = "GroupLeavingJob"

        // Keys used for database storage
        private val GROUP_PUBLIC_KEY_KEY = "group_public_key"
        private val DELETE_THREAD_KEY = "delete_thread"
    }

    override fun execute(dispatcherName: String) {
        TODO("Not yet implemented")
    }

    private fun handleSuccess(dispatcherName: String) {
        Log.w(TAG, "Group left successfully.")
        delegate?.handleJobSucceeded(this, dispatcherName)
    }

    private fun handlePermanentFailure(dispatcherName: String, e: Exception) {
        delegate?.handleJobFailedPermanently(this, dispatcherName, e)
    }

    private fun handleFailure(dispatcherName: String, e: Exception) {
        delegate?.handleJobFailed(this, dispatcherName, e)
    }

    override fun serialize(): Data {
        return Data.Builder()
                .putString(GROUP_PUBLIC_KEY_KEY, groupPublicKey)
                .putBoolean(DELETE_THREAD_KEY, deleteThread)
                .build()
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    class Factory : Job.Factory<GroupLeavingJob> {

        override fun create(data: Data): GroupLeavingJob {
            return GroupLeavingJob(
                    data.getString(GROUP_PUBLIC_KEY_KEY),
                    data.getBoolean(DELETE_THREAD_KEY)
            )
        }
    }
}