/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.database;

import static org.thoughtcrime.securesms.database.MmsDatabase.MESSAGE_BOX;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteQueryBuilder;

import org.jetbrains.annotations.NotNull;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.Util;
import org.session.libsignal.utilities.Log;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;

import java.io.Closeable;
import java.util.HashSet;
import java.util.Set;

import kotlin.Pair;

public class MmsSmsDatabase extends Database {

  @SuppressWarnings("unused")
  private static final String TAG = MmsSmsDatabase.class.getSimpleName();

  public static final String TRANSPORT     = "transport_type";
  public static final String MMS_TRANSPORT = "mms";
  public static final String SMS_TRANSPORT = "sms";

  private static final String[] PROJECTION = {MmsSmsColumns.ID, MmsSmsColumns.UNIQUE_ROW_ID,
                                              SmsDatabase.BODY, SmsDatabase.TYPE,
                                              MmsSmsColumns.THREAD_ID,
                                              SmsDatabase.ADDRESS, SmsDatabase.ADDRESS_DEVICE_ID, SmsDatabase.SUBJECT,
                                              MmsSmsColumns.NORMALIZED_DATE_SENT,
                                              MmsSmsColumns.NORMALIZED_DATE_RECEIVED,
                                              MmsDatabase.MESSAGE_TYPE, MmsDatabase.MESSAGE_BOX,
                                              SmsDatabase.STATUS,
                                              MmsSmsColumns.UNIDENTIFIED,
                                              MmsDatabase.PART_COUNT,
                                              MmsDatabase.CONTENT_LOCATION, MmsDatabase.TRANSACTION_ID,
                                              MmsDatabase.MESSAGE_SIZE, MmsDatabase.EXPIRY,
                                              MmsDatabase.STATUS,
                                              MmsSmsColumns.DELIVERY_RECEIPT_COUNT,
                                              MmsSmsColumns.READ_RECEIPT_COUNT,
                                              MmsSmsColumns.MISMATCHED_IDENTITIES,
                                              MmsDatabase.NETWORK_FAILURE,
                                              MmsSmsColumns.SUBSCRIPTION_ID,
                                              MmsSmsColumns.EXPIRES_IN,
                                              MmsSmsColumns.EXPIRE_STARTED,
                                              MmsSmsColumns.NOTIFIED,
                                              TRANSPORT,
                                              AttachmentDatabase.ATTACHMENT_JSON_ALIAS,
                                              MmsDatabase.QUOTE_ID,
                                              MmsDatabase.QUOTE_AUTHOR,
                                              MmsDatabase.QUOTE_BODY,
                                              MmsDatabase.QUOTE_MISSING,
                                              MmsDatabase.QUOTE_ATTACHMENT,
                                              MmsDatabase.SHARED_CONTACTS,
                                              MmsDatabase.LINK_PREVIEWS,
                                              ReactionDatabase.REACTION_JSON_ALIAS,
                                              MmsSmsColumns.HAS_MENTION
  };

  public MmsSmsDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public @Nullable MessageRecord getMessageForTimestamp(long timestamp) {
    try (Cursor cursor = queryTables(PROJECTION, MmsSmsColumns.NORMALIZED_DATE_SENT + " = " + timestamp, null, null)) {
      MmsSmsDatabase.Reader reader = readerFor(cursor);
      return reader.getNext();
    }
  }

  public @Nullable MessageRecord getMessageFor(long timestamp, String serializedAuthor) {
    return getMessageFor(timestamp, serializedAuthor, true);
  }

  public @Nullable MessageRecord getMessageFor(long timestamp, String serializedAuthor, boolean getQuote) {

    try (Cursor cursor = queryTables(PROJECTION, MmsSmsColumns.NORMALIZED_DATE_SENT + " = " + timestamp, null, null)) {
      MmsSmsDatabase.Reader reader = readerFor(cursor, getQuote);

      MessageRecord messageRecord;
      boolean isOwnNumber = Util.isOwnNumber(context, serializedAuthor);

      while ((messageRecord = reader.getNext()) != null) {
        if ((isOwnNumber && messageRecord.isOutgoing()) ||
                (!isOwnNumber && messageRecord.getIndividualRecipient().getAddress().serialize().equals(serializedAuthor)))
        {
          return messageRecord;
        }
      }
    }

    return null;
  }

  public @Nullable MessageRecord getSentMessageFor(long timestamp, String serializedAuthor) {
    // Early exit if the author is not us
    boolean isOwnNumber = Util.isOwnNumber(context, serializedAuthor);
    if (!isOwnNumber) {
      Log.i(TAG, "Asked to find sent messages but provided author is not us - returning null.");
      return null;
    }

    try (Cursor cursor = queryTables(PROJECTION, MmsSmsColumns.NORMALIZED_DATE_SENT + " = " + timestamp, null, null)) {
      MmsSmsDatabase.Reader reader = readerFor(cursor);

      MessageRecord messageRecord;
      while ((messageRecord = reader.getNext()) != null) {
        if (messageRecord.isOutgoing())
        {
          return messageRecord;
        }
      }
    }
    Log.i(TAG, "Could not find any message sent from us at provided timestamp - returning null.");
    return null;
  }

  public MessageRecord getLastSentMessageRecordFromSender(long threadId, String serializedAuthor) {
    // Early exit if the author is not us
    boolean isOwnNumber = Util.isOwnNumber(context, serializedAuthor);
    if (!isOwnNumber) {
      Log.i(TAG, "Asked to find last sent message but provided author is not us - returning null.");
      return null;
    }

    String order = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    // Try everything with resources so that they auto-close on end of scope
    try (Cursor cursor = queryTables(PROJECTION, selection, order, null)) {
      try (MmsSmsDatabase.Reader reader = readerFor(cursor)) {
        MessageRecord messageRecord;
        while ((messageRecord = reader.getNext()) != null) {
          if (messageRecord.isOutgoing()) { return messageRecord; }
        }
      }
    }
    Log.i(TAG, "Could not find last sent message from us in given thread - returning null.");
    return null;
  }

  public @Nullable MessageRecord getMessageFor(long timestamp, Address author) {
    return getMessageFor(timestamp, author.serialize());
  }

  public long getPreviousPage(long threadId, long fromTime, int limit) {
    String order = MmsSmsColumns.NORMALIZED_DATE_SENT+" ASC";
    String selection = MmsSmsColumns.THREAD_ID+" = "+threadId
            + " AND "+MmsSmsColumns.NORMALIZED_DATE_SENT+" > "+fromTime;
    String limitStr = ""+limit;
    long sent = -1;
    Cursor cursor = queryTables(PROJECTION, selection, order, limitStr);
    if (cursor == null) return sent;
    Reader reader = readerFor(cursor);
    if (!cursor.move(limit)) {
      cursor.moveToLast();
    }
    MessageRecord record = reader.getCurrent();
    sent = record.getDateSent();
    reader.close();
    return sent;
  }

  public Cursor getConversationPage(long threadId, long fromTime, long toTime, int limit) {
    String order = MmsSmsColumns.NORMALIZED_DATE_SENT+" DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = "+threadId
            + " AND "+MmsSmsColumns.NORMALIZED_DATE_SENT+" <= " + fromTime;
    String limitStr = null;
    if (toTime != -1L) {
      selection += " AND "+MmsSmsColumns.NORMALIZED_DATE_SENT+" > "+toTime;
    } else {
      limitStr = ""+limit;
    }

    return queryTables(PROJECTION, selection, order, limitStr);
  }

  public boolean hasNextPage(long threadId, long toTime) {
    String order = MmsSmsColumns.NORMALIZED_DATE_SENT+" DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = "+threadId
            + " AND "+MmsSmsColumns.NORMALIZED_DATE_SENT+" < " + toTime; // check if there's at least one message before the `toTime`
    Cursor cursor = queryTables(PROJECTION, selection, order, null);
    boolean hasNext = false;
    if (cursor != null) {
      hasNext = cursor.getCount() > 0;
      cursor.close();
    }
    return hasNext;
  }

  public boolean hasPreviousPage(long threadId, long fromTime) {
    String order = MmsSmsColumns.NORMALIZED_DATE_SENT+" DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = "+threadId
            + " AND "+MmsSmsColumns.NORMALIZED_DATE_SENT+" > " + fromTime; // check if there's at least one message after the `fromTime`
    Cursor cursor = queryTables(PROJECTION, selection, order, null);
    boolean hasNext = false;
    if (cursor != null) {
      hasNext = cursor.getCount() > 0;
      cursor.close();
    }
    return hasNext;
  }

  public Cursor getConversation(long threadId, boolean reverse, long offset, long limit) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_SENT + (reverse ? " DESC" : " ASC");
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;
    String limitStr  = limit > 0 || offset > 0 ? offset + ", " + limit : null;

    Cursor cursor = queryTables(PROJECTION, selection, order, limitStr);
    setNotifyConversationListeners(cursor, threadId);

    return cursor;
  }

  public Cursor getConversation(long threadId, boolean reverse) {
    return getConversation(threadId, reverse, 0, 0);
  }

  public Cursor getConversationSnippet(long threadId) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    return queryTables(PROJECTION, selection, order, null);
  }

  public long getLastMessageID(long threadId) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    try (Cursor cursor = queryTables(PROJECTION, selection, order, "1")) {
      cursor.moveToFirst();
      return cursor.getLong(cursor.getColumnIndexOrThrow(MmsSmsColumns.ID));
    }
  }

  // Builds up and returns a list of all all the messages sent by this user in the given thread.
  // Used to do a pass through our local database to remove records when a user has "Ban & Delete"
  // called on them in a Community.
  public Set<MessageRecord> getAllMessageRecordsFromSenderInThread(long threadId, String serializedAuthor) {
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId + " AND " + MmsSmsColumns.ADDRESS + " = \"" + serializedAuthor + "\"";
    Set<MessageRecord> identifiedMessages = new HashSet<MessageRecord>();

    // Try everything with resources so that they auto-close on end of scope
    try (Cursor cursor = queryTables(PROJECTION, selection, null, null)) {
      try (MmsSmsDatabase.Reader reader = readerFor(cursor)) {
        MessageRecord messageRecord;
        while ((messageRecord = reader.getNext()) != null) {
          identifiedMessages.add(messageRecord);
        }
      }
    }
    return identifiedMessages;
  }

  // Version of the above `getAllMessageRecordsFromSenderInThread` method that returns the message
  // Ids rather than the set of MessageRecords - currently unused by potentially useful in the future.
  public Set<Long> getAllMessageIdsFromSenderInThread(long threadId, String serializedAuthor) {
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId + " AND " + MmsSmsColumns.ADDRESS + " = \"" + serializedAuthor + "\"";

    Set<Long> identifiedMessages = new HashSet<Long>();

    // Try everything with resources so that they auto-close on end of scope
    try (Cursor cursor = queryTables(PROJECTION, selection, null, null)) {
      try (MmsSmsDatabase.Reader reader = readerFor(cursor)) {
        MessageRecord messageRecord;
        while ((messageRecord = reader.getNext()) != null) {
          identifiedMessages.add(messageRecord.id);
        }
      }
    }
    return identifiedMessages;
  }

  public long getLastOutgoingTimestamp(long threadId) {
    String order = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    // Try everything with resources so that they auto-close on end of scope
    try (Cursor cursor = queryTables(PROJECTION, selection, order, null)) {
      try (MmsSmsDatabase.Reader reader = readerFor(cursor)) {
        MessageRecord messageRecord;
        long attempts = 0;
        long maxAttempts = 20;
        while ((messageRecord = reader.getNext()) != null) {
          // Note: We rely on the message order to get us the most recent outgoing message - so we
          // take the first outgoing message we find as the last outgoing message.
          if (messageRecord.isOutgoing()) return messageRecord.getTimestamp();
          if (attempts++ > maxAttempts) break;
        }
      }
    }
    Log.i(TAG, "Could not find last sent message from us - returning -1.");
    return -1;
  }

  public long getLastMessageTimestamp(long threadId) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    try (Cursor cursor = queryTables(PROJECTION, selection, order, "1")) {
      if (cursor.moveToFirst()) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(MmsSmsColumns.NORMALIZED_DATE_SENT));
      }
    }

    return -1;
  }

  public Cursor getUnread() {
    String order           = MmsSmsColumns.NORMALIZED_DATE_SENT + " ASC";
    String selection       = "(" + MmsSmsColumns.READ + " = 0 OR " + MmsSmsColumns.REACTIONS_UNREAD + " = 1) AND " + MmsSmsColumns.NOTIFIED + " = 0";

    return queryTables(PROJECTION, selection, order, null);
  }

  public int getUnreadCount(long threadId) {
    String selection = MmsSmsColumns.READ + " = 0 AND " + MmsSmsColumns.NOTIFIED + " = 0 AND " + MmsSmsColumns.THREAD_ID + " = " + threadId;
    Cursor cursor    = queryTables(PROJECTION, selection, null, null);

    try {
      return cursor != null ? cursor.getCount() : 0;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public long getConversationCount(long threadId) {
    long count = DatabaseComponent.get(context).smsDatabase().getMessageCountForThread(threadId);
    count    += DatabaseComponent.get(context).mmsDatabase().getMessageCountForThread(threadId);

    return count;
  }

  public void incrementDeliveryReceiptCount(SyncMessageId syncMessageId, long timestamp) {
    DatabaseComponent.get(context).smsDatabase().incrementReceiptCount(syncMessageId, true, false);
    DatabaseComponent.get(context).mmsDatabase().incrementReceiptCount(syncMessageId, timestamp, true, false);
  }

  public void incrementReadReceiptCount(SyncMessageId syncMessageId, long timestamp) {
    DatabaseComponent.get(context).smsDatabase().incrementReceiptCount(syncMessageId, false, true);
    DatabaseComponent.get(context).mmsDatabase().incrementReceiptCount(syncMessageId, timestamp, false, true);
  }

  public int getQuotedMessagePosition(long threadId, long quoteId, @NonNull Address address) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_SENT + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    try (Cursor cursor = queryTables(new String[]{ MmsSmsColumns.NORMALIZED_DATE_SENT, MmsSmsColumns.ADDRESS }, selection, order, null)) {
      String  serializedAddress = address.serialize();
      boolean isOwnNumber       = Util.isOwnNumber(context, address.serialize());

      while (cursor != null && cursor.moveToNext()) {
        boolean quoteIdMatches = cursor.getLong(0) == quoteId;
        boolean addressMatches = serializedAddress.equals(cursor.getString(1));

        if (quoteIdMatches && (addressMatches || isOwnNumber)) {
          return cursor.getPosition();
        }
      }
    }
    return -1;
  }

  public int getMessagePositionInConversation(long threadId, long sentTimestamp, @NonNull Address address, boolean reverse) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_SENT + (reverse ? " DESC" : " ASC");
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    try (Cursor cursor = queryTables(new String[]{ MmsSmsColumns.NORMALIZED_DATE_SENT, MmsSmsColumns.ADDRESS }, selection, order, null)) {
      String  serializedAddress = address.serialize();
      boolean isOwnNumber       = Util.isOwnNumber(context, address.serialize());

      while (cursor != null && cursor.moveToNext()) {
        boolean timestampMatches = cursor.getLong(0) == sentTimestamp;
        boolean addressMatches   = serializedAddress.equals(cursor.getString(1));

        if (timestampMatches && (addressMatches || isOwnNumber)) {
          return cursor.getPosition();
        }
      }
    }
    return -1;
  }

  private Cursor queryTables(String[] projection, String selection, String order, String limit) {
    String reactionsColumn = "json_group_array(json_object(" +
            "'" + ReactionDatabase.ROW_ID + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.ROW_ID + ", " +
            "'" + ReactionDatabase.MESSAGE_ID + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.MESSAGE_ID + ", " +
            "'" + ReactionDatabase.IS_MMS + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.IS_MMS + ", " +
            "'" + ReactionDatabase.AUTHOR_ID + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.AUTHOR_ID + ", " +
            "'" + ReactionDatabase.EMOJI + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.EMOJI + ", " +
            "'" + ReactionDatabase.SERVER_ID + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.SERVER_ID + ", " +
            "'" + ReactionDatabase.COUNT + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.COUNT + ", " +
            "'" + ReactionDatabase.SORT_ID + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.SORT_ID + ", " +
            "'" + ReactionDatabase.DATE_SENT + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.DATE_SENT + ", " +
            "'" + ReactionDatabase.DATE_RECEIVED + "', " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.DATE_RECEIVED +
            ")) AS " + ReactionDatabase.REACTION_JSON_ALIAS;
    String[] mmsProjection = {MmsDatabase.DATE_SENT + " AS " + MmsSmsColumns.NORMALIZED_DATE_SENT,
                              MmsDatabase.DATE_RECEIVED + " AS " + MmsSmsColumns.NORMALIZED_DATE_RECEIVED,
                              MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID + " AS " + MmsSmsColumns.ID,
                              "'MMS::' || " + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID
                                  + " || '::' || " + MmsDatabase.DATE_SENT
                                  + " AS " + MmsSmsColumns.UNIQUE_ROW_ID,
                              "json_group_array(json_object(" +
                                  "'" + AttachmentDatabase.ROW_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.ROW_ID + ", " +
                                  "'" + AttachmentDatabase.UNIQUE_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.UNIQUE_ID + ", " +
                                  "'" + AttachmentDatabase.MMS_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.MMS_ID + "," +
                                  "'" + AttachmentDatabase.SIZE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.SIZE + ", " +
                                  "'" + AttachmentDatabase.FILE_NAME + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.FILE_NAME + ", " +
                                  "'" + AttachmentDatabase.DATA + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.DATA + ", " +
                                  "'" + AttachmentDatabase.THUMBNAIL + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.THUMBNAIL + ", " +
                                  "'" + AttachmentDatabase.CONTENT_TYPE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_TYPE + ", " +
                                  "'" + AttachmentDatabase.CONTENT_LOCATION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_LOCATION + ", " +
                                  "'" + AttachmentDatabase.FAST_PREFLIGHT_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.FAST_PREFLIGHT_ID + ", " +
                                  "'" + AttachmentDatabase.VOICE_NOTE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.VOICE_NOTE + ", " +
                                  "'" + AttachmentDatabase.WIDTH + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.WIDTH + ", " +
                                  "'" + AttachmentDatabase.HEIGHT + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.HEIGHT + ", " +
                                  "'" + AttachmentDatabase.QUOTE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.QUOTE + ", " +
                                  "'" + AttachmentDatabase.CONTENT_DISPOSITION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_DISPOSITION + ", " +
                                  "'" + AttachmentDatabase.NAME + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.NAME + ", " +
                                  "'" + AttachmentDatabase.TRANSFER_STATE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.TRANSFER_STATE + ", " +
                                  "'" + AttachmentDatabase.CAPTION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CAPTION + ", " +
                                  "'" + AttachmentDatabase.STICKER_PACK_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_PACK_ID + ", " +
                                  "'" + AttachmentDatabase.STICKER_PACK_KEY + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_PACK_KEY + ", " +
                                  "'" + AttachmentDatabase.STICKER_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_ID +
                                  ")) AS " + AttachmentDatabase.ATTACHMENT_JSON_ALIAS,
                              reactionsColumn,
                              SmsDatabase.BODY, MmsSmsColumns.READ, MmsSmsColumns.THREAD_ID,
                              SmsDatabase.TYPE, SmsDatabase.ADDRESS, SmsDatabase.ADDRESS_DEVICE_ID, SmsDatabase.SUBJECT, MmsDatabase.MESSAGE_TYPE,
                              MmsDatabase.MESSAGE_BOX, SmsDatabase.STATUS, MmsDatabase.PART_COUNT,
                              MmsDatabase.CONTENT_LOCATION, MmsDatabase.TRANSACTION_ID,
                              MmsDatabase.MESSAGE_SIZE, MmsDatabase.EXPIRY, MmsDatabase.STATUS,
                              MmsDatabase.UNIDENTIFIED,
                              MmsSmsColumns.DELIVERY_RECEIPT_COUNT, MmsSmsColumns.READ_RECEIPT_COUNT,
                              MmsSmsColumns.MISMATCHED_IDENTITIES,
                              MmsSmsColumns.SUBSCRIPTION_ID, MmsSmsColumns.EXPIRES_IN, MmsSmsColumns.EXPIRE_STARTED,
                              MmsSmsColumns.NOTIFIED,
                              MmsDatabase.NETWORK_FAILURE, TRANSPORT,
                              MmsDatabase.QUOTE_ID,
                              MmsDatabase.QUOTE_AUTHOR,
                              MmsDatabase.QUOTE_BODY,
                              MmsDatabase.QUOTE_MISSING,
                              MmsDatabase.QUOTE_ATTACHMENT,
                              MmsDatabase.SHARED_CONTACTS,
                              MmsDatabase.LINK_PREVIEWS,
                              MmsSmsColumns.HAS_MENTION
    };

    String[] smsProjection = {SmsDatabase.DATE_SENT + " AS " + MmsSmsColumns.NORMALIZED_DATE_SENT,
                              SmsDatabase.DATE_RECEIVED + " AS " + MmsSmsColumns.NORMALIZED_DATE_RECEIVED,
                              MmsSmsColumns.ID,
                              "'SMS::' || " + MmsSmsColumns.ID
                                  + " || '::' || " + SmsDatabase.DATE_SENT
                                  + " AS " + MmsSmsColumns.UNIQUE_ROW_ID,
                              "NULL AS " + AttachmentDatabase.ATTACHMENT_JSON_ALIAS,
                              reactionsColumn,
                              SmsDatabase.BODY, MmsSmsColumns.READ, MmsSmsColumns.THREAD_ID,
                              SmsDatabase.TYPE, SmsDatabase.ADDRESS, SmsDatabase.ADDRESS_DEVICE_ID, SmsDatabase.SUBJECT, MmsDatabase.MESSAGE_TYPE,
                              MmsDatabase.MESSAGE_BOX, SmsDatabase.STATUS, MmsDatabase.PART_COUNT,
                              MmsDatabase.CONTENT_LOCATION, MmsDatabase.TRANSACTION_ID,
                              MmsDatabase.MESSAGE_SIZE, MmsDatabase.EXPIRY, MmsDatabase.STATUS,
                              MmsDatabase.UNIDENTIFIED,
                              MmsSmsColumns.DELIVERY_RECEIPT_COUNT, MmsSmsColumns.READ_RECEIPT_COUNT,
                              MmsSmsColumns.MISMATCHED_IDENTITIES,
                              MmsSmsColumns.SUBSCRIPTION_ID, MmsSmsColumns.EXPIRES_IN, MmsSmsColumns.EXPIRE_STARTED,
                              MmsSmsColumns.NOTIFIED,
                              MmsDatabase.NETWORK_FAILURE, TRANSPORT,
                              MmsDatabase.QUOTE_ID,
                              MmsDatabase.QUOTE_AUTHOR,
                              MmsDatabase.QUOTE_BODY,
                              MmsDatabase.QUOTE_MISSING,
                              MmsDatabase.QUOTE_ATTACHMENT,
                              MmsDatabase.SHARED_CONTACTS,
                              MmsDatabase.LINK_PREVIEWS,
                              MmsSmsColumns.HAS_MENTION
    };

    SQLiteQueryBuilder mmsQueryBuilder = new SQLiteQueryBuilder();
    SQLiteQueryBuilder smsQueryBuilder = new SQLiteQueryBuilder();

    mmsQueryBuilder.setDistinct(true);
    smsQueryBuilder.setDistinct(true);

    smsQueryBuilder.setTables(SmsDatabase.TABLE_NAME +
                              " LEFT OUTER JOIN " + ReactionDatabase.TABLE_NAME +
                              " ON " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.MESSAGE_ID + " = " + SmsDatabase.TABLE_NAME + "." + SmsDatabase.ID + " AND " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.IS_MMS + " = 0");
    mmsQueryBuilder.setTables(MmsDatabase.TABLE_NAME +
                              " LEFT OUTER JOIN " + AttachmentDatabase.TABLE_NAME +
                              " ON " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.MMS_ID + " = " + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID +
                              " LEFT OUTER JOIN " + ReactionDatabase.TABLE_NAME +
                              " ON " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.MESSAGE_ID + " = " + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID + " AND " + ReactionDatabase.TABLE_NAME + "." + ReactionDatabase.IS_MMS + " = 1");


    Set<String> mmsColumnsPresent = new HashSet<>();
    mmsColumnsPresent.add(MmsSmsColumns.ID);
    mmsColumnsPresent.add(MmsSmsColumns.READ);
    mmsColumnsPresent.add(MmsSmsColumns.THREAD_ID);
    mmsColumnsPresent.add(MmsSmsColumns.BODY);
    mmsColumnsPresent.add(MmsSmsColumns.ADDRESS);
    mmsColumnsPresent.add(MmsSmsColumns.ADDRESS_DEVICE_ID);
    mmsColumnsPresent.add(MmsSmsColumns.DELIVERY_RECEIPT_COUNT);
    mmsColumnsPresent.add(MmsSmsColumns.READ_RECEIPT_COUNT);
    mmsColumnsPresent.add(MmsSmsColumns.MISMATCHED_IDENTITIES);
    mmsColumnsPresent.add(MmsSmsColumns.SUBSCRIPTION_ID);
    mmsColumnsPresent.add(MmsSmsColumns.EXPIRES_IN);
    mmsColumnsPresent.add(MmsSmsColumns.EXPIRE_STARTED);
    mmsColumnsPresent.add(MmsDatabase.MESSAGE_TYPE);
    mmsColumnsPresent.add(MmsDatabase.MESSAGE_BOX);
    mmsColumnsPresent.add(MmsDatabase.DATE_SENT);
    mmsColumnsPresent.add(MmsDatabase.DATE_RECEIVED);
    mmsColumnsPresent.add(MmsDatabase.PART_COUNT);
    mmsColumnsPresent.add(MmsDatabase.CONTENT_LOCATION);
    mmsColumnsPresent.add(MmsDatabase.TRANSACTION_ID);
    mmsColumnsPresent.add(MmsDatabase.MESSAGE_SIZE);
    mmsColumnsPresent.add(MmsDatabase.EXPIRY);
    mmsColumnsPresent.add(MmsDatabase.NOTIFIED);
    mmsColumnsPresent.add(MmsDatabase.STATUS);
    mmsColumnsPresent.add(MmsDatabase.UNIDENTIFIED);
    mmsColumnsPresent.add(MmsDatabase.NETWORK_FAILURE);
    mmsColumnsPresent.add(MmsSmsColumns.HAS_MENTION);

    mmsColumnsPresent.add(AttachmentDatabase.ROW_ID);
    mmsColumnsPresent.add(AttachmentDatabase.UNIQUE_ID);
    mmsColumnsPresent.add(AttachmentDatabase.MMS_ID);
    mmsColumnsPresent.add(AttachmentDatabase.SIZE);
    mmsColumnsPresent.add(AttachmentDatabase.FILE_NAME);
    mmsColumnsPresent.add(AttachmentDatabase.DATA);
    mmsColumnsPresent.add(AttachmentDatabase.THUMBNAIL);
    mmsColumnsPresent.add(AttachmentDatabase.CONTENT_TYPE);
    mmsColumnsPresent.add(AttachmentDatabase.CONTENT_LOCATION);
    mmsColumnsPresent.add(AttachmentDatabase.DIGEST);
    mmsColumnsPresent.add(AttachmentDatabase.FAST_PREFLIGHT_ID);
    mmsColumnsPresent.add(AttachmentDatabase.VOICE_NOTE);
    mmsColumnsPresent.add(AttachmentDatabase.WIDTH);
    mmsColumnsPresent.add(AttachmentDatabase.HEIGHT);
    mmsColumnsPresent.add(AttachmentDatabase.QUOTE);
    mmsColumnsPresent.add(AttachmentDatabase.STICKER_PACK_ID);
    mmsColumnsPresent.add(AttachmentDatabase.STICKER_PACK_KEY);
    mmsColumnsPresent.add(AttachmentDatabase.STICKER_ID);
    mmsColumnsPresent.add(AttachmentDatabase.CAPTION);
    mmsColumnsPresent.add(AttachmentDatabase.CONTENT_DISPOSITION);
    mmsColumnsPresent.add(AttachmentDatabase.NAME);
    mmsColumnsPresent.add(AttachmentDatabase.TRANSFER_STATE);
    mmsColumnsPresent.add(AttachmentDatabase.ATTACHMENT_JSON_ALIAS);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_ID);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_AUTHOR);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_BODY);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_MISSING);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_ATTACHMENT);
    mmsColumnsPresent.add(MmsDatabase.SHARED_CONTACTS);
    mmsColumnsPresent.add(MmsDatabase.LINK_PREVIEWS);
    mmsColumnsPresent.add(ReactionDatabase.MESSAGE_ID);
    mmsColumnsPresent.add(ReactionDatabase.IS_MMS);
    mmsColumnsPresent.add(ReactionDatabase.AUTHOR_ID);
    mmsColumnsPresent.add(ReactionDatabase.EMOJI);
    mmsColumnsPresent.add(ReactionDatabase.SERVER_ID);
    mmsColumnsPresent.add(ReactionDatabase.COUNT);
    mmsColumnsPresent.add(ReactionDatabase.SORT_ID);
    mmsColumnsPresent.add(ReactionDatabase.DATE_SENT);
    mmsColumnsPresent.add(ReactionDatabase.DATE_RECEIVED);
    mmsColumnsPresent.add(ReactionDatabase.REACTION_JSON_ALIAS);

    Set<String> smsColumnsPresent = new HashSet<>();
    smsColumnsPresent.add(MmsSmsColumns.ID);
    smsColumnsPresent.add(MmsSmsColumns.BODY);
    smsColumnsPresent.add(MmsSmsColumns.ADDRESS);
    smsColumnsPresent.add(MmsSmsColumns.ADDRESS_DEVICE_ID);
    smsColumnsPresent.add(MmsSmsColumns.READ);
    smsColumnsPresent.add(MmsSmsColumns.THREAD_ID);
    smsColumnsPresent.add(MmsSmsColumns.DELIVERY_RECEIPT_COUNT);
    smsColumnsPresent.add(MmsSmsColumns.READ_RECEIPT_COUNT);
    smsColumnsPresent.add(MmsSmsColumns.MISMATCHED_IDENTITIES);
    smsColumnsPresent.add(MmsSmsColumns.SUBSCRIPTION_ID);
    smsColumnsPresent.add(MmsSmsColumns.EXPIRES_IN);
    smsColumnsPresent.add(MmsSmsColumns.EXPIRE_STARTED);
    smsColumnsPresent.add(MmsSmsColumns.NOTIFIED);
    smsColumnsPresent.add(SmsDatabase.TYPE);
    smsColumnsPresent.add(SmsDatabase.SUBJECT);
    smsColumnsPresent.add(SmsDatabase.DATE_SENT);
    smsColumnsPresent.add(SmsDatabase.DATE_RECEIVED);
    smsColumnsPresent.add(SmsDatabase.STATUS);
    smsColumnsPresent.add(SmsDatabase.UNIDENTIFIED);
    smsColumnsPresent.add(MmsSmsColumns.HAS_MENTION);
    smsColumnsPresent.add(ReactionDatabase.ROW_ID);
    smsColumnsPresent.add(ReactionDatabase.MESSAGE_ID);
    smsColumnsPresent.add(ReactionDatabase.IS_MMS);
    smsColumnsPresent.add(ReactionDatabase.AUTHOR_ID);
    smsColumnsPresent.add(ReactionDatabase.EMOJI);
    smsColumnsPresent.add(ReactionDatabase.SERVER_ID);
    smsColumnsPresent.add(ReactionDatabase.COUNT);
    smsColumnsPresent.add(ReactionDatabase.SORT_ID);
    smsColumnsPresent.add(ReactionDatabase.DATE_SENT);
    smsColumnsPresent.add(ReactionDatabase.DATE_RECEIVED);
    smsColumnsPresent.add(ReactionDatabase.REACTION_JSON_ALIAS);

    @SuppressWarnings("deprecation")
    String mmsSubQuery = mmsQueryBuilder.buildUnionSubQuery(TRANSPORT, mmsProjection, mmsColumnsPresent, 5, MMS_TRANSPORT, selection, null, MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID, null);
    @SuppressWarnings("deprecation")
    String smsSubQuery = smsQueryBuilder.buildUnionSubQuery(TRANSPORT, smsProjection, smsColumnsPresent, 5, SMS_TRANSPORT, selection, null, SmsDatabase.TABLE_NAME + "." + SmsDatabase.ID, null);

    SQLiteQueryBuilder unionQueryBuilder = new SQLiteQueryBuilder();
    String unionQuery = unionQueryBuilder.buildUnionQuery(new String[] {smsSubQuery, mmsSubQuery}, order, limit);

    SQLiteQueryBuilder outerQueryBuilder = new SQLiteQueryBuilder();
    outerQueryBuilder.setTables("(" + unionQuery + ")");

    @SuppressWarnings("deprecation")
    String query      = outerQueryBuilder.buildQuery(projection, null, null, null, null, null, null);

    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    return db.rawQuery(query, null);
  }

  public Reader readerFor(@NonNull Cursor cursor) {
    return readerFor(cursor, true);
  }

  public Reader readerFor(@NonNull Cursor cursor, boolean getQuote) {
    return new Reader(cursor, getQuote);
  }

  @NotNull
  public Pair<Boolean, Long> timestampAndDirectionForCurrent(@NotNull Cursor cursor) {
    int sentColumn = cursor.getColumnIndex(MmsSmsColumns.NORMALIZED_DATE_SENT);
    String msgType = cursor.getString(cursor.getColumnIndexOrThrow(TRANSPORT));
    long sentTime = cursor.getLong(sentColumn);
    long type = 0;
    if (MmsSmsDatabase.MMS_TRANSPORT.equals(msgType)) {
      int typeIndex = cursor.getColumnIndex(MESSAGE_BOX);
      type = cursor.getLong(typeIndex);
    } else if (MmsSmsDatabase.SMS_TRANSPORT.equals(msgType)) {
      int typeIndex = cursor.getColumnIndex(SmsDatabase.TYPE);
      type = cursor.getLong(typeIndex);
    }

    return new Pair<Boolean, Long>(MmsSmsColumns.Types.isOutgoingMessageType(type), sentTime);
  }

  public class Reader implements Closeable {

    private final Cursor                 cursor;
    private final boolean                getQuote;
    private       SmsDatabase.Reader     smsReader;
    private       MmsDatabase.Reader     mmsReader;

    public Reader(Cursor cursor, boolean getQuote) {
      this.cursor = cursor;
      this.getQuote = getQuote;
    }

    private SmsDatabase.Reader getSmsReader() {
      if (smsReader == null) {
        smsReader = DatabaseComponent.get(context).smsDatabase().readerFor(cursor);
      }

      return smsReader;
    }

    private MmsDatabase.Reader getMmsReader() {
      if (mmsReader == null) {
        mmsReader = DatabaseComponent.get(context).mmsDatabase().readerFor(cursor, getQuote);
      }

      return mmsReader;
    }

    public MessageRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    public MessageRecord getCurrent() {
      String type = cursor.getString(cursor.getColumnIndexOrThrow(TRANSPORT));

      if      (MmsSmsDatabase.MMS_TRANSPORT.equals(type)) return getMmsReader().getCurrent();
      else if (MmsSmsDatabase.SMS_TRANSPORT.equals(type)) return getSmsReader().getCurrent();
      else                                                throw new AssertionError("Bad type: " + type);
    }

    public void close() {
      if (cursor != null) {
        cursor.close();
      }
    }
  }
}
