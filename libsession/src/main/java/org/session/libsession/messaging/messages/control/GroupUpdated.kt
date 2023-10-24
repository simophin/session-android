package org.session.libsession.messaging.messages.control

import org.session.libsignal.protos.SignalServiceProtos.Content
import org.session.libsignal.protos.SignalServiceProtos.DataMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage

class GroupUpdated(val inner: GroupUpdateMessage): ControlMessage() {

    companion object {
        fun fromProto(message: Content): GroupUpdated? =
            if (message.hasDataMessage() && message.dataMessage.hasGroupUpdateMessage())
                GroupUpdated(message.dataMessage.groupUpdateMessage)
            else null
    }

    override fun toProto(): Content {
        val dataMessage = DataMessage.newBuilder()
            .setGroupUpdateMessage(inner)
            .build()
        return Content.newBuilder()
            .setDataMessage(dataMessage)
            .build()
    }
}