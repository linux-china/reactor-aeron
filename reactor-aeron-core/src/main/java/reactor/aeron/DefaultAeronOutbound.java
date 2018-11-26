package reactor.aeron;

import io.aeron.Publication;
import java.nio.ByteBuffer;
import java.time.Duration;
import org.reactivestreams.Publisher;
import reactor.aeron.client.AeronClientOptions;
import reactor.aeron.server.AeronServerOptions;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;

/** Default aeron outbound. */
public final class DefaultAeronOutbound implements Disposable, AeronOutbound {

  private final String category;

  private final AeronResources aeronResources;

  private final String channel;

  private volatile AeronWriteSequencer sequencer;

  private volatile DefaultMessagePublication publication;

  /**
   * Constructor.
   *
   * @param category category
   * @param aeronResources aeronResources
   * @param channel channel
   */
  public DefaultAeronOutbound(String category, AeronResources aeronResources, String channel) {
    this.category = category;
    this.aeronResources = aeronResources;
    this.channel = channel;
  }

  @Override
  public AeronOutbound send(Publisher<? extends ByteBuffer> dataStream) {
    return then(sequencer.add(dataStream));
  }

  @Override
  public Mono<Void> then() {
    return Mono.empty();
  }

  @Override
  public void dispose() {
    if (publication != null && !publication.isDisposed()) {
      publication.dispose();
    }
  }

  public Mono<Void> initialise(long sessionId, int streamId, AeronClientOptions options) {
    return initialise(sessionId, streamId, options.connectTimeout(), options.backpressureTimeout());
  }

  public Mono<Void> initialise(long sessionId, int streamId, AeronServerOptions options) {
    return initialise(sessionId, streamId, options.connectTimeout(), options.backpressureTimeout());
  }

  /**
   * Init method.
   *
   * @param sessionId session id
   * @param streamId stream id
   * @return initialization handle
   */
  private Mono<Void> initialise(
      long sessionId, int streamId, Duration connectTimeout, Duration backpressureTimeout) {
    return Mono.create(
        sink -> {
          Publication aeronPublication =
              aeronResources.publication(category, channel, streamId, "to send data to", sessionId);
          this.publication =
              new DefaultMessagePublication(
                  aeronResources,
                  aeronPublication,
                  category,
                  connectTimeout.toMillis(),
                  backpressureTimeout.toMillis());
          this.sequencer = aeronResources.writeSequencer(category, publication, sessionId);
          createRetryTask(sink, aeronPublication, connectTimeout.toMillis()).schedule();
        });
  }

  private RetryTask createRetryTask(
      MonoSink<Void> sink, Publication aeronPublication, long timeoutMillis) {
    return new RetryTask(
        Schedulers.single(),
        100,
        timeoutMillis,
        () -> {
          if (aeronPublication.isConnected()) {
            sink.success();
            return true;
          }
          return false;
        },
        throwable -> {
          String errMessage =
              String.format(
                  "Publication %s for sending data in not connected during %d millis",
                  publication.asString(), timeoutMillis);
          sink.error(new Exception(errMessage, throwable));
        });
  }

  public MessagePublication getPublication() {
    return publication;
  }
}
