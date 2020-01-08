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
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.test.TestMediaDriver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TermBufferLengthTest
{
    public static final int TEST_TERM_LENGTH = LogBufferDescriptor.TERM_MIN_LENGTH * 2;

    public static final int STREAM_ID = 1001;

    @ParameterizedTest
    @ValueSource(strings =
        {
        "aeron:udp?endpoint=localhost:54325|" + CommonContext.TERM_LENGTH_PARAM_NAME + "=" + TEST_TERM_LENGTH,
        "aeron:ipc?" + CommonContext.TERM_LENGTH_PARAM_NAME + "=" + TEST_TERM_LENGTH
        }
    )
    public void shouldHaveCorrectTermBufferLength(final String channel)
    {
        final MediaDriver.Context ctx = new MediaDriver.Context()
            .errorHandler(Throwable::printStackTrace)
            .dirDeleteOnShutdown(true)
            .publicationTermBufferLength(TEST_TERM_LENGTH * 2)
            .ipcTermBufferLength(TEST_TERM_LENGTH * 2);

        try (TestMediaDriver ignore = TestMediaDriver.launch(ctx);
            Aeron aeron = Aeron.connect();
            Publication publication = aeron.addPublication(channel, STREAM_ID))
        {
            assertEquals(TEST_TERM_LENGTH, publication.termBufferLength());
        }
    }
}
