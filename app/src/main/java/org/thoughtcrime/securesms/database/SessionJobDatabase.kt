package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import org.session.libsession.messaging.jobs.AttachmentDownloadJob
import org.session.libsession.messaging.jobs.AttachmentUploadJob
import org.session.libsession.messaging.jobs.BackgroundGroupAddJob
import org.session.libsession.messaging.jobs.GroupAvatarDownloadJob
import org.session.libsession.messaging.jobs.Job
import org.session.libsession.messaging.jobs.MessageReceiveJob
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.jobs.SessionJobInstantiator
import org.session.libsession.messaging.jobs.SessionJobManagerFactories
import org.session.libsession.messaging.utilities.Data
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.jobmanager.impl.JsonDataSerializer

class SessionJobDatabase(context: Context, helper: SQLCipherOpenHelper) : Database(context, helper) {

    companion object {
        const val sessionJobTable = "session_job_database"
        const val jobID = "job_id"
        const val jobType = "job_type"
        const val failureCount = "failure_count"
        const val serializedData = "serialized_data"
        @JvmStatic val createSessionJobTableCommand
            = "CREATE TABLE $sessionJobTable ($jobID INTEGER PRIMARY KEY, $jobType STRING, $failureCount INTEGER DEFAULT 0, $serializedData TEXT);"

        const val dropAttachmentDownloadJobs =
                "DELETE FROM $sessionJobTable WHERE $jobType = '${AttachmentDownloadJob.KEY}';"
    }

    fun persistJob(job: Job) {
        val database = databaseHelper.writableDatabase
        val contentValues = ContentValues(4)
        contentValues.put(jobID, job.id!!)
        contentValues.put(jobType, job.getFactoryKey())
        contentValues.put(failureCount, job.failureCount)
        contentValues.put(serializedData, SessionJobHelper.dataSerializer.serialize(job.serialize()))
        database.insertOrUpdate(sessionJobTable, contentValues, "$jobID = ?", arrayOf( job.id!! ))
    }

    fun markJobAsSucceeded(jobID: String) {
        removeJob(jobID)
    }

    fun markJobAsFailedPermanently(jobID: String) {
        removeJob(jobID)
    }

    fun removeJob(jobID: String) {
        databaseHelper.writableDatabase.delete(sessionJobTable, "${Companion.jobID} = ?", arrayOf( jobID ))
    }

    fun getAllJobs(type: String): Map<String, Job?> {
        val database = databaseHelper.readableDatabase
        return database.getAll(sessionJobTable, "$jobType = ?", arrayOf( type )) { cursor ->
            val jobID = cursor.getString(jobID)
            try {
                jobID to jobFromCursor(cursor)
            } catch (e: Exception) {
                Log.e("Loki", "Error deserializing job of type: $type.", e)
                jobID to null
            }
        }.toMap()
    }

    fun getAttachmentUploadJobs(attachmentIDs: Collection<Long>): List<AttachmentUploadJob> {
        val database = databaseHelper.readableDatabase
        val result = mutableListOf<AttachmentUploadJob>()
        database.getAll(sessionJobTable, "$jobType = ?", arrayOf( AttachmentUploadJob.KEY )) { cursor ->
            val job = jobFromCursor(cursor) as AttachmentUploadJob?
            if (job != null) { result.add(job) }
        }
        return result.filter { job -> attachmentIDs.contains(job.attachmentID) }
    }

    fun getMessageSendJob(messageSendJobID: String): MessageSendJob? {
        val database = databaseHelper.readableDatabase
        return database.get(sessionJobTable, "$jobID = ? AND $jobType = ?", arrayOf( messageSendJobID, MessageSendJob.KEY )) { cursor ->
            jobFromCursor(cursor) as MessageSendJob?
        }
    }

    fun getMessageReceiveJob(messageReceiveJobID: String): MessageReceiveJob? {
        val database = databaseHelper.readableDatabase
        return database.get(sessionJobTable, "$jobID = ? AND $jobType = ?", arrayOf( messageReceiveJobID, MessageReceiveJob.KEY )) { cursor ->
            jobFromCursor(cursor) as MessageReceiveJob?
        }
    }

    fun getGroupAvatarDownloadJob(server: String, room: String, imageId: String?): GroupAvatarDownloadJob? {
        val database = databaseHelper.readableDatabase
        return database.getAll(sessionJobTable, "$jobType = ?", arrayOf(GroupAvatarDownloadJob.KEY)) {
            jobFromCursor(it) as GroupAvatarDownloadJob?
        }.filterNotNull().find { it.server == server && it.room == room && (imageId == null || it.imageId == imageId) }
    }

    fun cancelPendingMessageSendJobs(threadID: Long) {
        val database = databaseHelper.writableDatabase
        val attachmentUploadJobKeys = mutableListOf<String>()
        database.beginTransaction()
        database.getAll(sessionJobTable, "$jobType = ?", arrayOf( AttachmentUploadJob.KEY )) { cursor ->
            val job = jobFromCursor(cursor) as AttachmentUploadJob?
            if (job != null && job.threadID == threadID.toString()) { attachmentUploadJobKeys.add(job.id!!) }
        }
        val messageSendJobKeys = mutableListOf<String>()
        database.getAll(sessionJobTable, "$jobType = ?", arrayOf( MessageSendJob.KEY )) { cursor ->
            val job = jobFromCursor(cursor) as MessageSendJob?
            if (job != null && job.message.threadID == threadID) { messageSendJobKeys.add(job.id!!) }
        }
        if (attachmentUploadJobKeys.isNotEmpty()) {
            attachmentUploadJobKeys.forEach {
                database.delete(sessionJobTable, "${Companion.jobType} = ? AND ${Companion.jobID} = ?",
                    arrayOf( AttachmentUploadJob.KEY, it ))
            }
        }
        if (messageSendJobKeys.isNotEmpty()) {
            messageSendJobKeys.forEach {
                database.delete(sessionJobTable, "${Companion.jobType} = ? AND ${Companion.jobID} = ?",
                    arrayOf( MessageSendJob.KEY, it ))
            }
        }
        database.setTransactionSuccessful()
        database.endTransaction()
    }

    fun isJobCanceled(job: Job): Boolean {
        val database = databaseHelper.readableDatabase
        var cursor: android.database.Cursor? = null
        try {
            cursor = database.rawQuery("SELECT * FROM $sessionJobTable WHERE $jobID = ?", arrayOf( job.id!! ))
            return cursor == null || !cursor.moveToFirst()
        } catch (e: Exception) {
            // Do nothing
        }  finally {
            cursor?.close()
        }
        return false
    }

    private fun jobFromCursor(cursor: Cursor): Job? {
        val type = cursor.getString(jobType)
        val data = SessionJobHelper.dataSerializer.deserialize(cursor.getString(serializedData))
        val job = SessionJobHelper.sessionJobInstantiator.instantiate(type, data) ?: return null
        job.id = cursor.getString(jobID)
        job.failureCount = cursor.getInt(failureCount)
        return job
    }

    fun hasBackgroundGroupAddJob(groupJoinUrl: String): Boolean {
        val database = databaseHelper.readableDatabase
        return database.getAll(sessionJobTable, "$jobType = ?", arrayOf(BackgroundGroupAddJob.KEY)) { cursor ->
            jobFromCursor(cursor) as? BackgroundGroupAddJob
        }.filterNotNull().any { it.joinUrl == groupJoinUrl }
    }
}

object SessionJobHelper {
    val dataSerializer: Data.Serializer = JsonDataSerializer()
    val sessionJobInstantiator: SessionJobInstantiator = SessionJobInstantiator(SessionJobManagerFactories.getSessionJobFactories())
}