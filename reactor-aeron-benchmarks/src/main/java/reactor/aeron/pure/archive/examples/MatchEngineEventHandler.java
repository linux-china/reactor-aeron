package reactor.aeron.pure.archive.examples;

import io.aeron.ChannelUriStringBuilder;
import io.aeron.CommonContext;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.RecordingDescriptorConsumer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MediaDriver.Context;
import io.aeron.driver.ThreadingMode;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.SigInt;
import org.agrona.concurrent.YieldingIdleStrategy;
import reactor.aeron.pure.archive.Utils;

public class MatchEngineEventHandler {

  private static final String REPLAY_URI =
      new ChannelUriStringBuilder()
          .controlEndpoint(MatchEngine.OUTGOING_ENDPOINT)
          .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)
          .reliable(Boolean.TRUE)
          .media(CommonContext.UDP_MEDIA)
          .build();
  private static final int REPLAY_STREAM_ID = 2225;
  private static final int FRAGMENT_LIMIT = 10;

  /**
   * Main runner.
   *
   * @param args program arguments.
   */
  public static void main(String[] args) throws Exception {
    final AtomicBoolean running = new AtomicBoolean(true);
    SigInt.register(() -> running.set(false));

    String aeronDirName = Utils.tmpFileName("aeron");

    try (MediaDriver mediaDriver =
            MediaDriver.launch(
                new Context()
                    .threadingMode(ThreadingMode.SHARED)
                    .spiesSimulateConnection(true)
                    .errorHandler(Throwable::printStackTrace)
                    .aeronDirectoryName(aeronDirName)
                    .dirDeleteOnStart(true));
        AeronArchive aeronArchive =
            AeronArchive.connect(
                new AeronArchive.Context()
                    .controlResponseChannel("aeron:udp?endpoint=localhost:8026")
                    .controlResponseStreamId(18026)
                    .aeronDirectoryName(aeronDirName))) {

      long recordingId =
          findLatestRecording(
              aeronArchive, MatchEngine.OUTGOING_URI, MatchEngine.OUTGOING_STREAM_ID);

      System.out.println("recordingId: " + recordingId);

      long position = aeronArchive.getRecordingPosition(recordingId);
      System.out.println("getRecordingPosition: " + position);
      //      position = AeronArchive.NULL_POSITION;
      position = 0;

      System.out.println("position: " + position);

      Subscription subscription =
          aeronArchive.replay(recordingId, position, Long.MAX_VALUE, REPLAY_URI, REPLAY_STREAM_ID);

      YieldingIdleStrategy idleStrategy = new YieldingIdleStrategy();

      try {
        while (running.get()) {
          int works =
              subscription.poll(
                          (buffer, offset, length, header) -> {
                            final byte[] data = new byte[length - Long.BYTES];
                            buffer.getBytes(offset + Long.BYTES, data);

                            System.out.println(
                                String.format(
                                    "msg{ externalOffset: %s offset: %s, length: %s, body: %s }, header{ pos: %s, offset: %s, type: %s }, channel { stream: %s, session: %s, initialTermId: %s, termId: %s, termOffset: %s, flags: %s }",
                                    buffer.getLong(offset),
                                    offset,
                                    length,
                                    new String(data),
                                    header.position(),
                                    header.offset(),
                                    header.type(),
                                    MatchEngine.OUTGOING_STREAM_ID,
                                    header.sessionId(),
                                    header.initialTermId(),
                                    header.termId(),
                                    header.termOffset(),
                                    header.flags()));
                          },
                          FRAGMENT_LIMIT)
                      > 0
                  ? 1
                  : 0;

          idleStrategy.idle(works);
        }
      } finally {
        aeronArchive.stopReplay(subscription.images().get(0).sessionId());
      }

      Thread.currentThread().join();
    } finally {
      Utils.removeFile(aeronDirName);
    }
  }

  private static long findLatestRecording(
      final AeronArchive archive, String channel, int channelStreamId) {
    final MutableLong lastRecordingId = new MutableLong();

    final RecordingDescriptorConsumer consumer =
        (controlSessionId,
            correlationId,
            recordingId,
            startTimestamp,
            stopTimestamp,
            startPosition,
            stopPosition,
            initialTermId,
            segmentFileLength,
            termBufferLength,
            mtuLength,
            sessionId,
            streamId,
            strippedChannel,
            originalChannel,
            sourceIdentity) -> {
          System.out.println(
              new StringBuilder()
                  .append("controlSessionId: ")
                  .append(controlSessionId)
                  .append(", correlationId: ")
                  .append(correlationId)
                  .append(", recordingId: ")
                  .append(recordingId)
                  .append(", startTimestamp: ")
                  .append(startTimestamp)
                  .append(", stopTimestamp: ")
                  .append(stopTimestamp)
                  .append(", startPosition: ")
                  .append(startPosition)
                  .append(", stopPosition: ")
                  .append(stopPosition)
                  .append(", initialTermId: ")
                  .append(initialTermId)
                  .append(", segmentFileLength: ")
                  .append(segmentFileLength)
                  .append(", termBufferLength: ")
                  .append(termBufferLength)
                  .append(", mtuLength: ")
                  .append(mtuLength)
                  .append(", sessionId: ")
                  .append(sessionId)
                  .append(", streamId: ")
                  .append(streamId)
                  .append(", strippedChannel: ")
                  .append(strippedChannel)
                  .append(", originalChannel: ")
                  .append(originalChannel)
                  .append(", sourceIdentity: ")
                  .append(sourceIdentity));

          lastRecordingId.set(recordingId);
        };

    final long fromRecordingId = 0L;
    final int recordCount = 100;

    final int foundCount =
        archive.listRecordingsForUri(
            fromRecordingId, recordCount, channel, channelStreamId, consumer);

    if (foundCount == 0) {
      throw new IllegalStateException("no recordings found");
    }

    return lastRecordingId.get();
  }
}
