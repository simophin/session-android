package org.session.libsession.messaging.jobs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsignal.utilities.Log
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.schedule
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

class JobQueue {
    private var hasResumedPendingJobs = false // Just for debugging
    private val jobTimestampMap = ConcurrentHashMap<Long, AtomicInteger>()
    private val rxDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val rxMediaDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private val openGroupDispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
    private val txDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(Dispatchers.Default) + SupervisorJob()
    private val queue = Channel<Job>(UNLIMITED)
    private val pendingJobIds = mutableSetOf<String>()
    private val openGroupChannels = mutableMapOf<String, Channel<Job>>()
    private val permanentlyFailedJobKeys = mutableSetOf<JobTypeAndKey>()

    private val timer = Timer()

    private fun CoroutineScope.processWithOpenGroupDispatcher(
        channel: Channel<Job>,
        dispatcher: CoroutineDispatcher,
        name: String
    ) = launch(dispatcher) {
        for (job in channel) {
            if (!isActive) break
            val openGroupId = when (job) {
                is BatchMessageReceiveJob -> job.openGroupID
                is OpenGroupDeleteJob -> job.openGroupId
                is TrimThreadJob -> job.openGroupId
                is BackgroundGroupAddJob -> job.openGroupId
                is GroupAvatarDownloadJob -> "${job.server}.${job.room}"
                else -> null
            }
            if (openGroupId.isNullOrEmpty()) {
                Log.e("OpenGroupDispatcher", "Open Group ID was null on ${job.javaClass.simpleName}")
                handleJobFailedPermanently(job, name, NullPointerException("Open Group ID was null"))
            } else {
                val groupChannel = if (!openGroupChannels.containsKey(openGroupId)) {
                    Log.d("OpenGroupDispatcher", "Creating ${openGroupId.hashCode()} channel")
                    val newGroupChannel = Channel<Job>(UNLIMITED)
                    launch(dispatcher) {
                        for (groupJob in newGroupChannel) {
                            if (!isActive) break
                            processJob(groupJob, name)
                        }
                    }
                    openGroupChannels[openGroupId] = newGroupChannel
                    newGroupChannel
                } else {
                    Log.d("OpenGroupDispatcher", "Re-using channel")
                    openGroupChannels[openGroupId]!!
                }
                Log.d("OpenGroupDispatcher", "Sending to channel $groupChannel")
                groupChannel.send(job)
            }
        }
    }

    private fun CoroutineScope.processWithDispatcher(
        channel: Channel<Job>,
        dispatcher: CoroutineDispatcher,
        name: String,
        asynchronous: Boolean = true
    ) = launch(dispatcher) {
        for (job in channel) {
            if (!isActive) break
            if (asynchronous) {
                launch(dispatcher) {
                    processJob(job, name)
                }
            } else {
                processJob(job, name)
            }
        }
    }

    private suspend fun processJob(job: Job, dispatcherName: String) {
        Log.d(dispatcherName,"processJob: ${job.javaClass.simpleName} (id: ${job.id}, key: ${job.jobKey})")

        try {
            job.execute(dispatcherName)
            handleJobSucceeded(job, dispatcherName)
        }
        catch (e: JobPermanentlyFailedException) {
            handleJobFailedPermanently(job, dispatcherName, e.cause)
        }
        catch (e: Exception) {
            handleJobFailed(job, dispatcherName, e)
        }
    }

    init {
        // Process jobs
        scope.launch {
            val rxQueue = Channel<Job>(capacity = UNLIMITED)
            val txQueue = Channel<Job>(capacity = UNLIMITED)
            val mediaQueue = Channel<Job>(capacity = UNLIMITED)
            val openGroupQueue = Channel<Job>(capacity = UNLIMITED)

            val receiveJob = processWithDispatcher(rxQueue, rxDispatcher, "rx", asynchronous = false)
            val txJob = processWithDispatcher(txQueue, txDispatcher, "tx")
            val mediaJob = processWithDispatcher(mediaQueue, rxMediaDispatcher, "media")
            val openGroupJob = processWithOpenGroupDispatcher(openGroupQueue, openGroupDispatcher, "openGroup")

            while (isActive) {
                when (val job = queue.receive()) {
                    is NotifyPNServerJob, is AttachmentUploadJob, is MessageSendJob, is ConfigurationSyncJob -> {
                        txQueue.send(job)
                    }
                    is RetrieveProfileAvatarJob,
                    is AttachmentDownloadJob -> {
                        mediaQueue.send(job)
                    }
                    is GroupAvatarDownloadJob,
                    is BackgroundGroupAddJob,
                    is OpenGroupDeleteJob -> {
                        openGroupQueue.send(job)
                    }
                    is MessageReceiveJob, is TrimThreadJob,
                    is BatchMessageReceiveJob -> {
                        if ((job is BatchMessageReceiveJob && !job.openGroupID.isNullOrEmpty())
                            || (job is TrimThreadJob && !job.openGroupId.isNullOrEmpty())) {
                            openGroupQueue.send(job)
                        } else {
                            rxQueue.send(job)
                        }
                    }
                    else -> {
                        throw IllegalStateException("Unexpected job type: ${job.getFactoryKey()}")
                    }
                }
            }

            // The job has been cancelled
            receiveJob.cancel()
            txJob.cancel()
            mediaJob.cancel()
            openGroupJob.cancel()
        }
    }

    companion object {

        @JvmStatic
        val shared: JobQueue by lazy { JobQueue() }
    }

    fun add(job: Job) {
        val jobKey = job.jobKey

        if (jobKey != null) {
            synchronized(permanentlyFailedJobKeys) {
                if (permanentlyFailedJobKeys.contains(JobTypeAndKey(job::class.java, jobKey))) {
                    Log.d("Loki", "Skipping previously permanently failed job: ${job::class.simpleName} (key: ${job.jobKey})")
                    return
                }
            }
        }

        addWithoutExecuting(job)
        queue.trySend(job) // offer always called on unlimited capacity
    }

    private fun addWithoutExecuting(job: Job) {
        // When adding multiple jobs in rapid succession, timestamps might not be good enough as a unique ID. To
        // deal with this we keep track of the number of jobs with a given timestamp and add that to the end of the
        // timestamp to make it a unique ID. We can't use a random number because we do still want to keep track
        // of the order in which the jobs were added.
        val currentTime = System.currentTimeMillis()
        jobTimestampMap.putIfAbsent(currentTime, AtomicInteger())
        job.id = currentTime.toString() + jobTimestampMap[currentTime]!!.getAndIncrement().toString()
        MessagingModuleConfiguration.shared.storage.persistJob(job)
    }

    fun resumePendingSendMessage(job: Job) {
        val id = job.id ?: run {
            Log.e("Loki", "tried to resume pending send job with no ID")
            return
        }
        if (!pendingJobIds.add(id)) {
            Log.e("Loki","tried to re-queue pending/in-progress job (id: $id)")
            return
        }
        queue.trySend(job)
        Log.d("Loki", "resumed pending send message $id")
    }

    fun resumePendingJobs(typeKey: String) {
        val allPendingJobs = MessagingModuleConfiguration.shared.storage.getAllPendingJobs(typeKey)
        val pendingJobs = mutableListOf<Job>()
        for ((id, job) in allPendingJobs) {
            if (job == null) {
                // Job failed to deserialize, remove it from the DB
                MessagingModuleConfiguration.shared.storage.removeJob(id)
            } else {
                pendingJobs.add(job)
            }
        }
        pendingJobs.sortedBy { it.id }.forEach { job ->
            Log.i("Loki", "Resuming pending job of type: ${job::class.simpleName} (id: ${job.id}).")
            queue.trySend(job) // Offer always called on unlimited capacity
        }
    }

    fun resumePendingJobs() {
        if (hasResumedPendingJobs) {
            Log.d("Loki", "resumePendingJobs() should only be called once.")
            return
        }
        hasResumedPendingJobs = true
        val allJobTypes = listOf(
            AttachmentUploadJob.KEY,
            AttachmentDownloadJob.KEY,
            MessageReceiveJob.KEY,
            MessageSendJob.KEY,
            NotifyPNServerJob.KEY,
            BatchMessageReceiveJob.KEY,
            GroupAvatarDownloadJob.KEY,
            BackgroundGroupAddJob.KEY,
            OpenGroupDeleteJob.KEY,
            RetrieveProfileAvatarJob.KEY,
            ConfigurationSyncJob.KEY,
        )
        allJobTypes.forEach { type ->
            resumePendingJobs(type)
        }
    }

    private fun handleJobSucceeded(job: Job, dispatcherName: String) {
        val jobId = job.id ?: return
        MessagingModuleConfiguration.shared.storage.markJobAsSucceeded(jobId)
        pendingJobIds.remove(jobId)
    }

    private fun handleJobFailed(job: Job, dispatcherName: String, error: Exception) {
        // Canceled
        val storage = MessagingModuleConfiguration.shared.storage
        if (storage.isJobCanceled(job)) {
            return Log.i("Loki", "${job::class.simpleName} canceled (id: ${job.id}).")
        }
        // Message send jobs waiting for the attachment to upload
        if (job is MessageSendJob && error is MessageSendJob.AwaitingAttachmentUploadException) {
            Log.i("Loki", "Message send job waiting for attachment upload to finish (id: ${job.id}).")
            return
        }

        // Batch message receive job, re-queue non-permanently failed jobs
        if (job is BatchMessageReceiveJob && job.failureCount <= 0) {
            val replacementParameters = job.failures.toList()
            if (replacementParameters.isNotEmpty()) {
                val newJob = BatchMessageReceiveJob(replacementParameters, job.openGroupID)
                newJob.failureCount = job.failureCount + 1
                add(newJob)
            }
        }

        // Regular job failure
        job.failureCount += 1

        if (job.failureCount >= job.maxFailureCount) {
            handleJobFailedPermanently(job, dispatcherName, error)
        } else {
            storage.persistJob(job)
            val retryInterval = getRetryInterval(job)
            Log.i("Loki", "${job::class.simpleName} failed (id: ${job.id}); scheduling retry (failure count is ${job.failureCount}).")
            timer.schedule(delay = retryInterval) {
                Log.i("Loki", "Retrying ${job::class.simpleName} (id: ${job.id}).")
                queue.trySend(job)
            }
        }
    }

    private fun handleJobFailedPermanently(job: Job, dispatcherName: String, error: Exception) {
        val jobId = job.id ?: return
        MessagingModuleConfiguration.shared.storage.markJobAsFailedPermanently(jobId)
        Log.d(dispatcherName,"permanentlyFailedJob: ${job.javaClass.simpleName} (id: ${job.id}, key: ${job.jobKey})", error)

        val jobKey = job.jobKey
        if (jobKey != null) {
            synchronized(permanentlyFailedJobKeys) {
                permanentlyFailedJobKeys.add(JobTypeAndKey(job::class.java, jobKey))
            }
        }
    }

    private fun getRetryInterval(job: Job): Long {
        // Arbitrary backoff factor...
        // try  1 delay: 0.5s
        // try  2 delay: 1s
        // ...
        // try  5 delay: 16s
        // ...
        // try 11 delay: 512s
        val maxBackoff = (10 * 60).toDouble() // 10 minutes
        return (1000 * 0.25 * min(maxBackoff, (2.0).pow(job.failureCount))).roundToLong()
    }

    private data class JobTypeAndKey(val type: Class<out Job>, val key: Any)
}