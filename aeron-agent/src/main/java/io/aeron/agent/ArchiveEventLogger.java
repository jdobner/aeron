/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.agent;

import io.aeron.archive.codecs.*;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;

import java.nio.ByteBuffer;
import java.util.EnumSet;

import static io.aeron.agent.ArchiveEventCode.*;
import static java.util.EnumSet.complementOf;
import static java.util.EnumSet.of;

/**
 * Event logger interface used by interceptors for recording events into a {@link RingBuffer} for an
 * {@link io.aeron.archive.Archive} for via a Java Agent.
 */
public final class ArchiveEventLogger
{
    static final long ENABLED_EVENT_CODES = EventConfiguration.getEnabledArchiveEventCodes();

    static final EnumSet<ArchiveEventCode> CONTROL_REQUEST_EVENTS = complementOf(of(CMD_OUT_RESPONSE));

    public static final ArchiveEventLogger LOGGER = new ArchiveEventLogger(EventConfiguration.EVENT_RING_BUFFER);

    private final UnsafeBuffer encodedBuffer =
        new UnsafeBuffer(ByteBuffer.allocateDirect(EventConfiguration.MAX_EVENT_LENGTH));
    private final ControlResponseEncoder controlResponseEncoder = new ControlResponseEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final ManyToOneRingBuffer ringBuffer;

    ArchiveEventLogger(final ManyToOneRingBuffer eventRingBuffer)
    {
        ringBuffer = eventRingBuffer;
    }

    @SuppressWarnings("MethodLength")
    public void logControlRequest(final DirectBuffer buffer, final int offset, final int length)
    {
        headerDecoder.wrap(buffer, offset);

        final int templateId = headerDecoder.templateId();
        switch (templateId)
        {
            case ConnectRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_CONNECT);
                break;

            case CloseSessionRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_CLOSE_SESSION);
                break;

            case StartRecordingRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_START_RECORDING);
                break;

            case StopRecordingRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_STOP_RECORDING);
                break;

            case ReplayRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_REPLAY);
                break;

            case StopReplayRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_STOP_REPLAY);
                break;

            case ListRecordingsRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_LIST_RECORDINGS);
                break;

            case ListRecordingsForUriRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_LIST_RECORDINGS_FOR_URI);
                break;

            case ListRecordingRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_LIST_RECORDING);
                break;

            case ExtendRecordingRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_EXTEND_RECORDING);
                break;

            case RecordingPositionRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_RECORDING_POSITION);
                break;

            case TruncateRecordingRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_TRUNCATE_RECORDING);
                break;

            case StopRecordingSubscriptionRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_STOP_RECORDING_SUBSCRIPTION);
                break;

            case StopPositionRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_STOP_POSITION);
                break;

            case FindLastMatchingRecordingRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_FIND_LAST_MATCHING_RECORD);
                break;

            case ListRecordingSubscriptionsRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_LIST_RECORDING_SUBSCRIPTIONS);
                break;

            case BoundedReplayRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_START_BOUNDED_REPLAY);
                break;

            case StopAllReplaysRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_STOP_ALL_REPLAYS);
                break;

            case ReplicateRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_REPLICATE);
                break;

            case StopReplicationRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_STOP_REPLICATION);
                break;

            case StartPositionRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_START_POSITION);
                break;

            case DetachSegmentsRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_DETACH_SEGMENTS);
                break;

            case DeleteDetachedSegmentsRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_DELETE_DETACHED_SEGMENTS);
                break;

            case PurgeSegmentsRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_PURGE_SEGMENTS);
                break;

            case AttachSegmentsRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_ATTACH_SEGMENTS);
                break;

            case MigrateSegmentsRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_MIGRATE_SEGMENTS);
                break;

            case AuthConnectRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_AUTH_CONNECT);
                break;

            case KeepAliveRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_KEEP_ALIVE);
                break;

            case TaggedReplicateRequestDecoder.TEMPLATE_ID:
                dispatchIfEnabled(buffer, offset, length, CMD_IN_TAGGED_REPLICATE);
                break;
        }
    }

    public static int toEventCodeId(final ArchiveEventCode code)
    {
        return ArchiveEventCode.EVENT_CODE_TYPE << 16 | (code.id() & 0xFFFF);
    }

    private void dispatchIfEnabled(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final ArchiveEventCode eventCode)
    {
        if (ArchiveEventCode.isEnabled(eventCode, ENABLED_EVENT_CODES))
        {
            ringBuffer.write(toEventCodeId(eventCode), buffer, offset, length);
        }
    }

    public void logControlResponse(
        final long controlSessionId,
        final long correlationId,
        final long relevantId,
        final ControlResponseCode code,
        final String errorMessage)
    {
        controlResponseEncoder.wrap(encodedBuffer, 0)
            .controlSessionId(controlSessionId)
            .correlationId(correlationId)
            .relevantId(relevantId)
            .code(code)
            .errorMessage(errorMessage);

        ringBuffer.write(toEventCodeId(CMD_OUT_RESPONSE), encodedBuffer, 0, controlResponseEncoder.encodedLength());
    }
}
