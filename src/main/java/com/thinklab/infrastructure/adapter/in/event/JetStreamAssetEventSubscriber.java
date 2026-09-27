package com.thinklab.infrastructure.adapter.in.event;

import com.thinklab.kit.events.EventsProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import io.nats.client.Connection;
import io.nats.client.ConsumerContext;
import io.nats.client.Message;
import io.nats.client.MessageConsumer;
import io.nats.client.StreamContext;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * The only class in this service that touches {@code io.nats.client.*} directly. Subscribes to
 * {@code thinklab.it-hardware-maintenance.workorder.*} (both {@code repair-started} and
 * {@code repair-completed}) with a single durable pull consumer
 * ({@code it-asset-registry-worker}) and hands every message straight to
 * {@link WorkOrderEventHandler}, which has no NATS imports of its own.
 *
 * <p><b>No dead-letter subject in v1</b> (same posture as notification-dispatch's ADR-024): a message
 * that keeps failing past {@link #MAX_DELIVER} attempts is terminated with {@link Message#term()} -
 * logged, never redelivered, never parked anywhere for later inspection.
 *
 * <p><b>Subscription retry:</b> the stream is created by the kit's {@code NatsStreamInitializer},
 * another startup listener with no ordering guarantee relative to this one, and the broker itself may
 * not be up yet. A failed subscription is therefore retried every {@link #RETRY_DELAY} until it
 * succeeds or the bean is shut down, instead of leaving the Asset/WorkOrder integration off until the
 * next restart.
 */
@Singleton
@Requires(property = "thinklab.events.enabled", value = "true")
public class JetStreamAssetEventSubscriber implements ApplicationEventListener<StartupEvent> {

    private static final Logger log = LoggerFactory.getLogger(JetStreamAssetEventSubscriber.class);
    private static final String CONSUMER_NAME = "it-asset-registry-worker";
    private static final long MAX_DELIVER = 5;
    static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    private final Connection connection;
    private final EventsProperties properties;
    private final WorkOrderEventHandler eventHandler;
    private final TaskScheduler scheduler;
    private volatile MessageConsumer messageConsumer;
    private volatile boolean stopped;

    @Inject
    public JetStreamAssetEventSubscriber(Connection connection, EventsProperties properties, WorkOrderEventHandler eventHandler,
                                          @Named(TaskExecutors.SCHEDULED) TaskScheduler scheduler) {
        this.connection = Objects.requireNonNull(connection, "Infrastructure constraint violated: Connection cannot be null.");
        this.properties = Objects.requireNonNull(properties, "Infrastructure constraint violated: EventsProperties cannot be null.");
        this.eventHandler = Objects.requireNonNull(eventHandler, "Infrastructure constraint violated: WorkOrderEventHandler cannot be null.");
        this.scheduler = Objects.requireNonNull(scheduler, "Infrastructure constraint violated: TaskScheduler cannot be null.");
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        subscribe();
    }

    void subscribe() {
        if (stopped) {
            return;
        }
        try {
            String subjectFilter = properties.getSubjectPrefix() + ".it-hardware-maintenance.workorder.*";
            StreamContext streamContext = connection.getStreamContext(properties.getStreamName());
            ConsumerContext consumerContext = streamContext.createOrUpdateConsumer(ConsumerConfiguration.builder()
                    .durable(CONSUMER_NAME)
                    .filterSubject(subjectFilter)
                    .ackPolicy(AckPolicy.Explicit)
                    .maxDeliver(MAX_DELIVER)
                    .build());
            this.messageConsumer = consumerContext.consume(this::onMessage);
            log.info("[EVENTS] Subscribed durable consumer [{}] to subject [{}]", CONSUMER_NAME, subjectFilter);
        } catch (Exception e) {
            log.warn("[EVENTS] Could not subscribe to the event backbone, retrying in {}s; Asset maintenance status will lag behind WorkOrders until then. Reason: {}",
                    RETRY_DELAY.toSeconds(), e.getMessage());
            scheduler.schedule(RETRY_DELAY, this::subscribe);
        }
    }

    private void onMessage(Message message) {
        String payload = new String(message.getData(), java.nio.charset.StandardCharsets.UTF_8);
        try {
            eventHandler.handle(message.getSubject(), payload).block();
            message.ack();
        } catch (Exception e) {
            if (message.metaData().deliveredCount() >= MAX_DELIVER) {
                log.error("[EVENTS] Message on subject [{}] exhausted {} delivery attempts, terminating it: {}",
                        message.getSubject(), MAX_DELIVER, e.getMessage());
                message.term();
            } else {
                log.warn("[EVENTS] Message handling failed, requesting redelivery: {}", e.getMessage());
                message.nak();
            }
        }
    }

    @PreDestroy
    void shutdown() {
        stopped = true;
        if (messageConsumer != null) {
            messageConsumer.stop();
        }
    }
}
