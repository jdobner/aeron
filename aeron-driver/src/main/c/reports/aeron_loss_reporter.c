/*
 * Copyright 2014-2019 Real Logic Ltd.
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

#include "reports/aeron_loss_reporter.h"

int aeron_loss_reporter_init(aeron_loss_reporter_t *reporter, uint8_t *buffer, size_t length)
{
    reporter->buffer = buffer;
    reporter->next_record_offset = 0;
    reporter->capacity = length;

    return 0;
}

aeron_loss_reporter_entry_offset_t aeron_loss_reporter_create_entry(
    aeron_loss_reporter_t *reporter,
    int64_t initial_bytes_lost,
    int64_t timestamp_ms,
    int32_t session_id,
    int32_t stream_id,
    const char *channel,
    size_t channel_length,
    const char *source,
    size_t source_length)
{
    aeron_loss_reporter_entry_offset_t entry_offset = -1;
    const size_t required_capacity =
        sizeof(aeron_loss_reporter_entry_t) + (2 * sizeof(int32_t)) + channel_length + source_length;

    if (required_capacity <= (reporter->capacity - reporter->next_record_offset))
    {
        uint8_t *ptr = reporter->buffer + reporter->next_record_offset;
        aeron_loss_reporter_entry_t *entry = (aeron_loss_reporter_entry_t *)ptr;

        entry->total_bytes_lost = initial_bytes_lost;
        entry->first_observation_timestamp = timestamp_ms;
        entry->last_observation_timestamp = timestamp_ms;
        entry->session_id = session_id;
        entry->stream_id = stream_id;

        ptr += sizeof(aeron_loss_reporter_entry_t);
        *(int32_t *)ptr = (int32_t)channel_length;
        ptr += sizeof(int32_t);
        memcpy(ptr, channel, channel_length);

        ptr += channel_length;
        *(int32_t *)ptr = (int32_t)source_length;
        ptr += sizeof(int32_t);
        memcpy(ptr, source, source_length);

        AERON_PUT_ORDERED(entry->observation_count, 1);

        entry_offset = (aeron_loss_reporter_entry_offset_t)reporter->next_record_offset;
        reporter->next_record_offset += AERON_ALIGN(required_capacity, AERON_LOSS_REPORTER_ENTRY_ALIGNMENT);
    }
    else
    {
        errno = ENOMEM;
        aeron_set_err(ENOMEM, "could not create loss report entry: %s", strerror(ENOMEM));
    }

    return entry_offset;
}

extern void aeron_loss_reporter_record_observation(
    aeron_loss_reporter_t *reporter,
    aeron_loss_reporter_entry_offset_t offset,
    int64_t bytes_lost,
    int64_t timestamp_ms);

size_t aeron_loss_reporter_read(
    const uint8_t *buffer, size_t capacity, aeron_loss_reporter_read_entry_func_t entry_func, void *clientd)
{
    size_t records_read = 0;
    size_t offset = 0;

    while (offset < capacity)
    {
        const uint8_t *ptr = buffer + offset;
        aeron_loss_reporter_entry_t *entry = (aeron_loss_reporter_entry_t *)ptr;

        int64_t observation_count;
        AERON_GET_VOLATILE(observation_count, entry->observation_count);
        if (observation_count <= 0)
        {
            break;
        }

        ++records_read;

        ptr += sizeof(aeron_loss_reporter_entry_t);
        int32_t channel_length = *(int32_t *)ptr;
        ptr += sizeof(int32_t);
        const char *channel = (const char *)ptr;

        ptr += channel_length;
        int32_t source_length = *(int32_t *)ptr;
        ptr += sizeof(int32_t);
        const char *source = (const char *)ptr;

        entry_func(
            clientd,
            entry->observation_count,
            entry->total_bytes_lost,
            entry->first_observation_timestamp,
            entry->last_observation_timestamp,
            entry->session_id,
            entry->stream_id,
            channel,
            channel_length,
            source,
            source_length);

        const size_t record_length =
            sizeof(aeron_loss_reporter_entry_t) + (2 * sizeof(int32_t)) + channel_length + source_length;
        offset += AERON_ALIGN(record_length, AERON_LOSS_REPORTER_ENTRY_ALIGNMENT);
    }

    return records_read;
}
