package org.session.libsession.messaging.jobs

import org.session.libsession.messaging.utilities.Data

interface Job {
    var id: String?
    var failureCount: Int

    val maxFailureCount: Int

    /**
     * The key used to identify the job. Jobs that have the same key (and same class implicitly) will
     * be regarded as the same job. This is different from [id] where it's primarily used for
     * identifying the job instance in the database.
     *
     * The value will be test against its true equality (i.e. calling `.equals()`)
     *
     * If this value is null, the job will be considered unique and will not be de-duplicated upon
     * permanent failure.
     */
    val jobKey: Any?

    companion object {

        // Keys used for database storage
        private val ID_KEY = "id"
        private val FAILURE_COUNT_KEY = "failure_count"
        internal const val MAX_BUFFER_SIZE = 1_000_000 // bytes
    }

    /**
     * Execute the job in a coroutine. Once this method returns, the job must have completed.
     *
     * If there's a problem with the job, an [Exception] can be thrown.
     *
     * If an error is permanent, throw a [JobPermanentlyFailedException] with the cause of the error.
     */
    suspend fun execute(dispatcherName: String)

    fun serialize(): Data

    fun getFactoryKey(): String

    interface Factory<T : Job> {

        fun create(data: Data): T?
    }
}

/**
 * Exception that indicates that the job has failed permanently and should not be retried.
 */
class JobPermanentlyFailedException(override val cause: Exception) : RuntimeException()