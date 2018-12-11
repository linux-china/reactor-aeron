package reactor.aeron.server;

import java.nio.ByteBuffer;
import java.util.Optional;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.aeron.AeronEventLoop;
import reactor.aeron.AeronInbound;
import reactor.aeron.AeronResources;
import reactor.aeron.ByteBufferFlux;
import reactor.aeron.DataMessageSubscriber;
import reactor.aeron.MessageSubscription;
import reactor.aeron.MessageType;
import reactor.aeron.OnDisposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.TopicProcessor;

final class AeronServerInbound implements AeronInbound, OnDisposable {

  private final String name;
  private final AeronResources resources;

  private volatile ByteBufferFlux flux;
  private volatile MessageSubscription subscription;

  AeronServerInbound(String name, AeronResources resources) {
    this.name = name;
    this.resources = resources;
  }

  Mono<Void> start(String channel, int streamId, long sessionId, Runnable onCompleteHandler) {
    return Mono.defer(
        () -> {
          ServerDataMessageProcessor messageProcessor =
              new ServerDataMessageProcessor(name, sessionId, onCompleteHandler);

          flux = new ByteBufferFlux(messageProcessor);

          AeronEventLoop eventLoop = resources.nextEventLoop();

          return resources
              .dataSubscription(
                  name,
                  channel,
                  streamId,
                  messageProcessor,
                  eventLoop,
                  null,
                  image -> Optional.ofNullable(onCompleteHandler).ifPresent(Runnable::run))
              .doOnSuccess(
                  result -> {
                    subscription = result;
                    messageProcessor.onSubscription(subscription);
                  })
              .then()
              .log("serverInbound");
        });
  }

  @Override
  public ByteBufferFlux receive() {
    return flux;
  }

  @Override
  public void dispose() {
    if (subscription != null) {
      subscription.dispose();
    }
  }

  @Override
  public Mono<Void> onDispose() {
    return subscription != null ? subscription.onDispose() : Mono.empty();
  }

  @Override
  public boolean isDisposed() {
    return subscription != null && subscription.isDisposed();
  }

  private static class ServerDataMessageProcessor
      implements DataMessageSubscriber, Publisher<ByteBuffer> {

    private static final Logger logger = LoggerFactory.getLogger(ServerDataMessageProcessor.class);

    private final String category;
    private final long sessionId;
    private final Runnable onCompleteHandler;

    private volatile org.reactivestreams.Subscription subscription;
    private volatile Subscriber<? super ByteBuffer> subscriber;

    private ServerDataMessageProcessor(
        String category, long sessionId, Runnable onCompleteHandler) {
      this.category = category;
      this.sessionId = sessionId;
      this.onCompleteHandler = onCompleteHandler;
    }

    @Override
    public void onSubscription(org.reactivestreams.Subscription subscription) {
      this.subscription = subscription;
    }

    @Override
    public void onNext(long sessionId, ByteBuffer buffer) {
      if (logger.isTraceEnabled()) {
        logger.trace(
            "[{}] Received {} for sessionId: {}, buffer: {}",
            category,
            MessageType.NEXT,
            sessionId,
            buffer);
      }

      if (this.sessionId == sessionId) {
        subscriber.onNext(buffer);
      } else {
        logger.error(
            "[{}] Received {} for unexpected sessionId: {}", category, MessageType.NEXT, sessionId);
      }
    }

    @Override
    public void onComplete(long sessionId) {
      if (logger.isTraceEnabled()) {
        logger.trace(
            "[{}] Received {} for sessionId: {}", category, MessageType.COMPLETE, sessionId);
      }

      if (this.sessionId == sessionId) {
        onCompleteHandler.run();
      } else {
        logger.error(
            "[{}] Received {} for unexpected sessionId: {}",
            category,
            MessageType.COMPLETE,
            sessionId);
      }
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
      this.subscriber = subscriber;
      subscriber.onSubscribe(subscription);
    }
  }
}
