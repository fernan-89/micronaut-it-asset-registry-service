package com.thinklab.infrastructure.adapter.in.event;

import com.thinklab.kit.events.EventsProperties;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.scheduling.TaskScheduler;
import io.nats.client.Connection;
import io.nats.client.ConsumerContext;
import io.nats.client.Message;
import io.nats.client.MessageConsumer;
import io.nats.client.MessageHandler;
import io.nats.client.StreamContext;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.impl.NatsJetStreamMetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JetStreamAssetEventSubscriberTest {

    @Mock private Connection connection;
    @Mock private StreamContext streamContext;
    @Mock private ConsumerContext consumerContext;
    @Mock private MessageConsumer messageConsumer;
    @Mock private WorkOrderEventHandler eventHandler;
    @Mock private StartupEvent startupEvent;
    @Mock private TaskScheduler scheduler;

    private final EventsProperties properties = new EventsProperties();

    @BeforeEach
    void setUp() {
        properties.setStreamName("THINKLAB_EVENTS");
        properties.setSubjectPrefix("thinklab");
    }

    @Test
    @DisplayName("startup subscribes a durable consumer to the workorder subject wildcard")
    void subscribesOnStartup() throws Exception {
        when(connection.getStreamContext("THINKLAB_EVENTS")).thenReturn(streamContext);
        when(streamContext.createOrUpdateConsumer(any(ConsumerConfiguration.class))).thenReturn(consumerContext);
        when(consumerContext.consume(any(MessageHandler.class))).thenReturn(messageConsumer);

        new JetStreamAssetEventSubscriber(connection, properties, eventHandler, scheduler).onApplicationEvent(startupEvent);

        verify(consumerContext).consume(any(MessageHandler.class));
    }

    @Test
    @DisplayName("a failed subscription at startup is contained and retried until it succeeds")
    void failedSubscriptionIsRetried() throws Exception {
        when(connection.getStreamContext("THINKLAB_EVENTS"))
                .thenThrow(new java.io.IOException("stream not found"))
                .thenReturn(streamContext);
        when(streamContext.createOrUpdateConsumer(any(ConsumerConfiguration.class))).thenReturn(consumerContext);
        when(consumerContext.consume(any(MessageHandler.class))).thenReturn(messageConsumer);
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);

        new JetStreamAssetEventSubscriber(connection, properties, eventHandler, scheduler).onApplicationEvent(startupEvent);

        verify(scheduler).schedule(eq(JetStreamAssetEventSubscriber.RETRY_DELAY), retry.capture());
        verify(consumerContext, never()).consume(any(MessageHandler.class));

        retry.getValue().run();

        verify(consumerContext).consume(any(MessageHandler.class));
        verify(scheduler, times(1)).schedule(any(java.time.Duration.class), any(Runnable.class));
    }

    @Test
    @DisplayName("a pending retry does nothing once the subscriber has been shut down")
    void noRetryAfterShutdown() throws Exception {
        when(connection.getStreamContext("THINKLAB_EVENTS")).thenThrow(new java.io.IOException("broker down"));
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        JetStreamAssetEventSubscriber subscriber = new JetStreamAssetEventSubscriber(connection, properties, eventHandler, scheduler);

        subscriber.onApplicationEvent(startupEvent);
        verify(scheduler).schedule(eq(JetStreamAssetEventSubscriber.RETRY_DELAY), retry.capture());
        subscriber.shutdown();
        retry.getValue().run();

        verify(connection, times(1)).getStreamContext("THINKLAB_EVENTS");
    }

    @Test
    @DisplayName("a successfully handled message is acked")
    void messageHandledSuccessfully() throws Exception {
        MessageHandler handler = subscribeAndCaptureHandler();
        Message message = mock(Message.class);
        when(message.getData()).thenReturn("payload".getBytes());
        when(message.getSubject()).thenReturn("thinklab.it-hardware-maintenance.workorder.repair-started");
        when(eventHandler.handle("thinklab.it-hardware-maintenance.workorder.repair-started", "payload")).thenReturn(reactor.core.publisher.Mono.empty());

        handler.onMessage(message);

        verify(message).ack();
        verify(message, never()).nak();
        verify(message, never()).term();
    }

    @Test
    @DisplayName("a failed message below MaxDeliver is nak'd for redelivery")
    void messageFailureBelowMaxDeliverIsNakd() throws Exception {
        MessageHandler handler = subscribeAndCaptureHandler();
        Message message = mock(Message.class);
        NatsJetStreamMetaData metaData = mock(NatsJetStreamMetaData.class);
        when(message.getData()).thenReturn("payload".getBytes());
        when(message.getSubject()).thenReturn("thinklab.it-hardware-maintenance.workorder.repair-started");
        when(message.metaData()).thenReturn(metaData);
        when(metaData.deliveredCount()).thenReturn(2L);
        when(eventHandler.handle(any(), eq("payload"))).thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("boom")));

        handler.onMessage(message);

        verify(message).nak();
        verify(message, never()).ack();
        verify(message, never()).term();
    }

    @Test
    @DisplayName("a failed message at MaxDeliver is terminated, never redelivered")
    void messageFailureAtMaxDeliverIsTerminated() throws Exception {
        MessageHandler handler = subscribeAndCaptureHandler();
        Message message = mock(Message.class);
        NatsJetStreamMetaData metaData = mock(NatsJetStreamMetaData.class);
        when(message.getData()).thenReturn("payload".getBytes());
        when(message.getSubject()).thenReturn("thinklab.it-hardware-maintenance.workorder.repair-completed");
        when(message.metaData()).thenReturn(metaData);
        when(metaData.deliveredCount()).thenReturn(5L);
        when(eventHandler.handle(any(), eq("payload"))).thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("boom")));

        handler.onMessage(message);

        verify(message).term();
        verify(message, never()).ack();
        verify(message, never()).nak();
    }

    @Test
    @DisplayName("shutdown stops the consumer only if one was created")
    void shutdownStopsConsumerWhenPresent() throws Exception {
        new JetStreamAssetEventSubscriber(connection, properties, eventHandler, scheduler).shutdown();
        verify(messageConsumer, never()).stop();

        when(connection.getStreamContext("THINKLAB_EVENTS")).thenReturn(streamContext);
        when(streamContext.createOrUpdateConsumer(any(ConsumerConfiguration.class))).thenReturn(consumerContext);
        when(consumerContext.consume(any(MessageHandler.class))).thenReturn(messageConsumer);
        JetStreamAssetEventSubscriber subscriber = new JetStreamAssetEventSubscriber(connection, properties, eventHandler, scheduler);
        subscriber.onApplicationEvent(startupEvent);

        subscriber.shutdown();

        verify(messageConsumer).stop();
    }

    @Test
    @DisplayName("mandatory collaborators and the startup event are null-checked")
    void nullGuards() {
        assertThrows(NullPointerException.class, () -> new JetStreamAssetEventSubscriber(null, properties, eventHandler, scheduler));
        assertThrows(NullPointerException.class, () -> new JetStreamAssetEventSubscriber(connection, null, eventHandler, scheduler));
        assertThrows(NullPointerException.class, () -> new JetStreamAssetEventSubscriber(connection, properties, null, scheduler));
        assertThrows(NullPointerException.class, () -> new JetStreamAssetEventSubscriber(connection, properties, eventHandler, null));
        assertThrows(NullPointerException.class,
                () -> new JetStreamAssetEventSubscriber(connection, properties, eventHandler, scheduler).onApplicationEvent(null));
    }

    private MessageHandler subscribeAndCaptureHandler() throws Exception {
        when(connection.getStreamContext("THINKLAB_EVENTS")).thenReturn(streamContext);
        when(streamContext.createOrUpdateConsumer(any(ConsumerConfiguration.class))).thenReturn(consumerContext);
        ArgumentCaptor<MessageHandler> captor = ArgumentCaptor.forClass(MessageHandler.class);
        when(consumerContext.consume(captor.capture())).thenReturn(messageConsumer);

        new JetStreamAssetEventSubscriber(connection, properties, eventHandler, scheduler).onApplicationEvent(startupEvent);

        return captor.getValue();
    }
}
