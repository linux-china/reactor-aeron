package reactor.aeron.pure;

import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SigInt;
import reactor.aeron.Configurations;

/**
 * Pong component of Ping-Pong.
 *
 * <p>Echoes back messages from {@link Ping}.
 *
 * @see Ping
 */
public class Pong {
  private static final int PING_STREAM_ID = Configurations.PING_STREAM_ID;
  private static final int PONG_STREAM_ID = Configurations.PONG_STREAM_ID;
  private static final int FRAME_COUNT_LIMIT = Configurations.FRAGMENT_COUNT_LIMIT;
  private static final String PING_CHANNEL = Configurations.PING_CHANNEL;
  private static final String PONG_CHANNEL = Configurations.PONG_CHANNEL;
  private static final boolean INFO_FLAG = Configurations.INFO_FLAG;
  private static final boolean EMBEDDED_MEDIA_DRIVER = Configurations.EMBEDDED_MEDIA_DRIVER;
  private static final boolean EXCLUSIVE_PUBLICATIONS = Configurations.EXCLUSIVE_PUBLICATIONS;

  private static final IdleStrategy PING_HANDLER_IDLE_STRATEGY = Configurations.idleStrategy();

  /**
   * Main runner.
   *
   * @param args program arguments.
   */
  public static void main(final String[] args) {
    final MediaDriver driver = EMBEDDED_MEDIA_DRIVER ? MediaDriver.launchEmbedded() : null;

    final Aeron.Context ctx = new Aeron.Context();
    if (EMBEDDED_MEDIA_DRIVER) {
      ctx.aeronDirectoryName(driver.aeronDirectoryName());
    }

    if (INFO_FLAG) {
      ctx.availableImageHandler(Configurations::printAvailableImage);
      ctx.unavailableImageHandler(Configurations::printUnavailableImage);
    }

    final IdleStrategy idleStrategy = Configurations.idleStrategy();

    System.out.println("Subscribing Ping at " + PING_CHANNEL + " on stream Id " + PING_STREAM_ID);
    System.out.println("Publishing Pong at " + PONG_CHANNEL + " on stream Id " + PONG_STREAM_ID);
    System.out.println("Using exclusive publications " + EXCLUSIVE_PUBLICATIONS);
    System.out.println(
        "Using ping handler idle strategy "
            + PING_HANDLER_IDLE_STRATEGY.getClass()
            + "("
            + Configurations.IDLE_STRATEGY
            + ")");

    final AtomicBoolean running = new AtomicBoolean(true);
    SigInt.register(() -> running.set(false));

    try (Aeron aeron = Aeron.connect(ctx);
        Subscription subscription = aeron.addSubscription(PING_CHANNEL, PING_STREAM_ID);
        Publication publication =
            EXCLUSIVE_PUBLICATIONS
                ? aeron.addExclusivePublication(PONG_CHANNEL, PONG_STREAM_ID)
                : aeron.addPublication(PONG_CHANNEL, PONG_STREAM_ID)) {
      final FragmentAssembler dataHandler =
          new FragmentAssembler(
              (buffer, offset, length, header) -> pingHandler(publication, buffer, offset, length));

      while (running.get()) {
        idleStrategy.idle(subscription.poll(dataHandler, FRAME_COUNT_LIMIT));
      }

      System.out.println("Shutting down...");
    }

    CloseHelper.quietClose(driver);
  }

  private static void pingHandler(
      final Publication pongPublication,
      final DirectBuffer buffer,
      final int offset,
      final int length) {
    if (pongPublication.offer(buffer, offset, length) > 0L) {
      return;
    }

    PING_HANDLER_IDLE_STRATEGY.reset();

    while (pongPublication.offer(buffer, offset, length) < 0L) {
      PING_HANDLER_IDLE_STRATEGY.idle();
    }
  }
}
