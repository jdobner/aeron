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

import io.aeron.driver.*;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.test.MediaDriverTestWatcher;
import io.aeron.test.TestMediaDriver;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.SystemUtil;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;

public class FlowControlStrategiesTest
{
    private static final String MULTICAST_URI = "aeron:udp?endpoint=224.20.30.39:54326|interface=localhost";

    private static final int STREAM_ID = 1;

    private static final int TERM_BUFFER_LENGTH = LogBufferDescriptor.TERM_MIN_LENGTH;
    private static final int NUM_MESSAGES_PER_TERM = 64;
    private static final int MESSAGE_LENGTH =
        (TERM_BUFFER_LENGTH / NUM_MESSAGES_PER_TERM) - DataHeaderFlyweight.HEADER_LENGTH;
    private static final String ROOT_DIR =
        SystemUtil.tmpDirName() + "aeron-system-tests-" + UUID.randomUUID().toString() + File.separator;

    private final MediaDriver.Context driverAContext = new MediaDriver.Context();
    private final MediaDriver.Context driverBContext = new MediaDriver.Context();

    private Aeron clientA;
    private Aeron clientB;
    private TestMediaDriver driverA;
    private TestMediaDriver driverB;
    private Publication publication;
    private Subscription subscriptionA;
    private Subscription subscriptionB;

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[MESSAGE_LENGTH]);
    private final FragmentHandler fragmentHandlerA = mock(FragmentHandler.class);
    private final FragmentHandler fragmentHandlerB = mock(FragmentHandler.class);

    @RegisterExtension
    public MediaDriverTestWatcher testWatcher = new MediaDriverTestWatcher();

    private void launch()
    {
        final String baseDirA = ROOT_DIR + "A";
        final String baseDirB = ROOT_DIR + "B";

        buffer.putInt(0, 1);

        driverAContext.publicationTermBufferLength(TERM_BUFFER_LENGTH)
            .aeronDirectoryName(baseDirA)
            .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
            .errorHandler(Throwable::printStackTrace)
            .threadingMode(ThreadingMode.SHARED);

        driverBContext.publicationTermBufferLength(TERM_BUFFER_LENGTH)
            .aeronDirectoryName(baseDirB)
            .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
            .errorHandler(Throwable::printStackTrace)
            .threadingMode(ThreadingMode.SHARED);

        driverA = TestMediaDriver.launch(driverAContext, testWatcher);
        driverB = TestMediaDriver.launch(driverBContext, testWatcher);
        clientA = Aeron.connect(
            new Aeron.Context()
                .errorHandler(Throwable::printStackTrace)
                .aeronDirectoryName(driverAContext.aeronDirectoryName()));

        clientB = Aeron.connect(
            new Aeron.Context()
                .errorHandler(Throwable::printStackTrace)
                .aeronDirectoryName(driverBContext.aeronDirectoryName()));
    }

    @AfterEach
    public void after()
    {
        CloseHelper.quietCloseAll(clientB, clientA, driverB, driverA);
        IoUtil.delete(new File(ROOT_DIR), true);
    }

    @Test
    public void shouldSpinUpAndShutdown()
    {
        assertTimeout(ofSeconds(10), () ->
        {
            launch();

            subscriptionA = clientA.addSubscription(MULTICAST_URI, STREAM_ID);
            subscriptionB = clientB.addSubscription(MULTICAST_URI, STREAM_ID);
            publication = clientA.addPublication(MULTICAST_URI, STREAM_ID);

            while (!subscriptionA.isConnected() || !subscriptionB.isConnected())
            {
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }
        });
    }

    @Test
    public void shouldTimeoutImageWhenBehindForTooLongWithMaxMulticastFlowControlStrategy()
    {
        assertTimeout(ofSeconds(20), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;

            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));
            driverAContext.multicastFlowControlSupplier(new MaxMulticastFlowControlSupplier());

            launch();

            subscriptionA = clientA.addSubscription(MULTICAST_URI, STREAM_ID);
            subscriptionB = clientB.addSubscription(MULTICAST_URI, STREAM_ID);
            publication = clientA.addPublication(MULTICAST_URI, STREAM_ID);

            while (!subscriptionA.isConnected() || !subscriptionB.isConnected())
            {
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }

            for (int i = 0; i < numMessagesToSend; i++)
            {
                while (publication.offer(buffer, 0, buffer.capacity()) < 0L)
                {
                    Thread.yield();
                    SystemTest.checkInterruptedStatus();
                }

                // A keeps up
                final MutableInteger fragmentsRead = new MutableInteger();
                SystemTest.executeUntil(
                    () -> fragmentsRead.get() > 0,
                    (j) ->
                    {
                        fragmentsRead.value += subscriptionA.poll(fragmentHandlerA, 10);
                        Thread.yield();
                    },
                    Integer.MAX_VALUE,
                    TimeUnit.MILLISECONDS.toNanos(500));

                fragmentsRead.set(0);

                // B receives slowly and eventually can't keep up
                if (i % 10 == 0)
                {
                    SystemTest.executeUntil(
                        () -> fragmentsRead.get() > 0,
                        (j) ->
                        {
                            fragmentsRead.value += subscriptionB.poll(fragmentHandlerB, 1);
                            Thread.yield();
                        },
                        Integer.MAX_VALUE,
                        TimeUnit.MILLISECONDS.toNanos(500));
                }
            }

            verify(fragmentHandlerA, times(numMessagesToSend)).onFragment(
                any(DirectBuffer.class),
                anyInt(),
                eq(MESSAGE_LENGTH),
                any(Header.class));

            verify(fragmentHandlerB, atMost(numMessagesToSend - 1)).onFragment(
                any(DirectBuffer.class),
                anyInt(),
                eq(MESSAGE_LENGTH),
                any(Header.class));
        });
    }

    @Test
    public void shouldSlowDownWhenBehindWithMinMulticastFlowControlStrategy()
    {
        assertTimeout(ofSeconds(20), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;
            int numMessagesLeftToSend = numMessagesToSend;
            int numFragmentsFromA = 0;
            int numFragmentsFromB = 0;

            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));
            driverAContext.multicastFlowControlSupplier(new MinMulticastFlowControlSupplier());

            launch();

            subscriptionA = clientA.addSubscription(MULTICAST_URI, STREAM_ID);
            subscriptionB = clientB.addSubscription(MULTICAST_URI, STREAM_ID);
            publication = clientA.addPublication(MULTICAST_URI, STREAM_ID);

            while (!subscriptionA.isConnected() || !subscriptionB.isConnected())
            {
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }

            for (long i = 0; numFragmentsFromA < numMessagesToSend || numFragmentsFromB < numMessagesToSend; i++)
            {
                if (numMessagesLeftToSend > 0)
                {
                    if (publication.offer(buffer, 0, buffer.capacity()) >= 0L)
                    {
                        numMessagesLeftToSend--;
                    }
                }

                Thread.yield();
                SystemTest.checkInterruptedStatus();

                // A keeps up
                numFragmentsFromA += subscriptionA.poll(fragmentHandlerA, 10);

                // B receives slowly
                if ((i % 2) == 0)
                {
                    numFragmentsFromB += subscriptionB.poll(fragmentHandlerB, 1);
                }
            }

            verify(fragmentHandlerA, times(numMessagesToSend)).onFragment(
                any(DirectBuffer.class),
                anyInt(),
                eq(MESSAGE_LENGTH),
                any(Header.class));

            verify(fragmentHandlerB, times(numMessagesToSend)).onFragment(
                any(DirectBuffer.class),
                anyInt(),
                eq(MESSAGE_LENGTH),
                any(Header.class));
        });
    }

    @Test
    public void shouldRemoveDeadReceiverWithMinMulticastFlowControlStrategy()
    {
        assertTimeout(ofSeconds(20), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;
            int numMessagesLeftToSend = numMessagesToSend;
            int numFragmentsFromA = 0;
            int numFragmentsFromB = 0;
            boolean isClosedB = false;

            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));
            driverAContext.multicastFlowControlSupplier(new MinMulticastFlowControlSupplier());

            launch();

            subscriptionA = clientA.addSubscription(MULTICAST_URI, STREAM_ID);
            subscriptionB = clientB.addSubscription(MULTICAST_URI, STREAM_ID);
            publication = clientA.addPublication(MULTICAST_URI, STREAM_ID);

            while (!subscriptionA.isConnected() || !subscriptionB.isConnected())
            {
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }

            while (numFragmentsFromA < numMessagesToSend)
            {
                if (numMessagesLeftToSend > 0)
                {
                    if (publication.offer(buffer, 0, buffer.capacity()) >= 0L)
                    {
                        numMessagesLeftToSend--;
                    }
                }

                // A keeps up
                numFragmentsFromA += subscriptionA.poll(fragmentHandlerA, 10);

                // B receives up to 1/8 of the messages, then stops
                if (numFragmentsFromB < (numMessagesToSend / 8))
                {
                    numFragmentsFromB += subscriptionB.poll(fragmentHandlerB, 10);
                }
                else if (!isClosedB)
                {
                    subscriptionB.close();
                    isClosedB = true;
                }
            }

            verify(fragmentHandlerA, times(numMessagesToSend)).onFragment(
                any(DirectBuffer.class),
                anyInt(),
                eq(MESSAGE_LENGTH),
                any(Header.class));
        });
    }

    @Test
    public void shouldSlowToPreferredWithMulticastFlowControlStrategy()
    {
        TestMediaDriver.notSupportedOnCMediaDriverYet("Preferred multicast flow control strategy not available");

        assertTimeout(ofSeconds(20), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;
            int numMessagesLeftToSend = numMessagesToSend;
            int numFragmentsFromB = 0;

            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));
            driverAContext.multicastFlowControlSupplier(
                (udpChannel, streamId, registrationId) -> new PreferredMulticastFlowControl());
            driverBContext.applicationSpecificFeedback(PreferredMulticastFlowControl.PREFERRED_ASF_BYTES);

            launch();

            subscriptionA = clientA.addSubscription(MULTICAST_URI, STREAM_ID);
            subscriptionB = clientB.addSubscription(MULTICAST_URI, STREAM_ID);
            publication = clientA.addPublication(MULTICAST_URI, STREAM_ID);

            while (!subscriptionA.isConnected() || !subscriptionB.isConnected())
            {
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }

            for (long i = 0; numFragmentsFromB < numMessagesToSend; i++)
            {
                if (numMessagesLeftToSend > 0)
                {
                    final long result = publication.offer(buffer, 0, buffer.capacity());
                    if (result >= 0L)
                    {
                        numMessagesLeftToSend--;
                    }
                    else if (Publication.NOT_CONNECTED == result)
                    {
                        fail("Publication not connected, numMessagesLeftToSend=" + numMessagesLeftToSend);
                    }
                }

                Thread.yield();
                SystemTest.checkInterruptedStatus();

                // A keeps up
                subscriptionA.poll(fragmentHandlerA, 10);

                // B receives slowly
                if ((i % 2) == 0)
                {
                    final int bFragments = subscriptionB.poll(fragmentHandlerB, 1);
                    if (0 == bFragments && !subscriptionB.isConnected())
                    {
                        if (subscriptionB.isClosed())
                        {
                            fail("Subscription B is closed, numFragmentsFromB=" + numFragmentsFromB);
                        }

                        fail("Subscription B not connected, numFragmentsFromB=" + numFragmentsFromB);
                    }

                    numFragmentsFromB += bFragments;
                }
            }

            verify(fragmentHandlerB, times(numMessagesToSend)).onFragment(
                any(DirectBuffer.class),
                anyInt(),
                eq(MESSAGE_LENGTH),
                any(Header.class));
        });
    }

    @Test
    public void shouldRemoveDeadPreferredReceiverWithPreferredMulticastFlowControlStrategy()
    {
        TestMediaDriver.notSupportedOnCMediaDriverYet("Preferred multicast flow control strategy not available");

        assertTimeout(ofSeconds(20), () ->
        {
            final int numMessagesToSend = NUM_MESSAGES_PER_TERM * 3;
            int numMessagesLeftToSend = numMessagesToSend;
            int numFragmentsReadFromA = 0, numFragmentsReadFromB = 0;
            boolean isBClosed = false;

            driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));
            driverAContext.multicastFlowControlSupplier(
                (udpChannel, streamId, registrationId) -> new PreferredMulticastFlowControl());
            driverBContext.applicationSpecificFeedback(PreferredMulticastFlowControl.PREFERRED_ASF_BYTES);

            launch();

            subscriptionA = clientA.addSubscription(MULTICAST_URI, STREAM_ID);
            subscriptionB = clientB.addSubscription(MULTICAST_URI, STREAM_ID);
            publication = clientA.addPublication(MULTICAST_URI, STREAM_ID);

            while (!subscriptionA.isConnected() || !subscriptionB.isConnected())
            {
                Thread.yield();
                SystemTest.checkInterruptedStatus();
            }

            while (numFragmentsReadFromA < numMessagesToSend)
            {
                if (numMessagesLeftToSend > 0)
                {
                    if (publication.offer(buffer, 0, buffer.capacity()) >= 0L)
                    {
                        numMessagesLeftToSend--;
                    }
                }

                // A keeps up
                numFragmentsReadFromA += subscriptionA.poll(fragmentHandlerA, 10);

                // B receives up to 1/8 of the messages, then stops
                if (numFragmentsReadFromB < (numMessagesToSend / 8))
                {
                    numFragmentsReadFromB += subscriptionB.poll(fragmentHandlerB, 10);
                }
                else if (!isBClosed)
                {
                    subscriptionB.close();
                    isBClosed = true;
                }
            }

            verify(fragmentHandlerA, times(numMessagesToSend)).onFragment(
                any(DirectBuffer.class),
                anyInt(),
                eq(MESSAGE_LENGTH),
                any(Header.class));
        });
    }
}
