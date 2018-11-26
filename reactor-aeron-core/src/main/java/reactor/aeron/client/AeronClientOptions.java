package reactor.aeron.client;

import static java.lang.Boolean.TRUE;

import io.aeron.ChannelUriStringBuilder;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

public final class AeronClientOptions {

  public static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  public static final Duration BACKPRESSURE_TIMEOUT = Duration.ofSeconds(5);
  public static final Duration CONTROL_BACKPRESSURE_TIMEOUT = Duration.ofSeconds(5);
  public static final int SERVER_STREAM_ID = 1;

  private final ChannelUriStringBuilder clientChannel;
  private final ChannelUriStringBuilder serverChannel;
  private final Duration connectTimeout;
  private final Duration backpressureTimeout;
  private final Duration controlBackpressureTimeout;
  private final Duration ackTimeout;
  private final int serverStreamId;

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builder(AeronClientOptions options) {
    Builder builder = new Builder();
    builder.clientChannel = options.clientChannel;
    builder.serverChannel = options.serverChannel;
    builder.connectTimeout = options.connectTimeout;
    builder.backpressureTimeout = options.backpressureTimeout;
    builder.controlBackpressureTimeout = options.controlBackpressureTimeout;
    builder.ackTimeout = options.ackTimeout;
    builder.serverStreamId = options.serverStreamId;
    return builder;
  }

  private AeronClientOptions(Builder builder) {
    this.clientChannel = builder.clientChannel.validate();
    this.serverChannel = builder.serverChannel.validate();
    this.connectTimeout = validate(builder.connectTimeout, "connectTimeout");
    this.backpressureTimeout = validate(builder.backpressureTimeout, "backpressureTimeout");
    this.controlBackpressureTimeout =
        validate(builder.controlBackpressureTimeout, "controlBackpressureTimeout");
    this.ackTimeout = validate(builder.ackTimeout, "ackTimeout");
    this.serverStreamId = validate(builder.serverStreamId, "serverStreamId");
  }

  public String clientChannel() {
    return clientChannel.build();
  }

  public String serverChannel() {
    return serverChannel.build();
  }

  public Duration connectTimeout() {
    return connectTimeout;
  }

  public Duration backpressureTimeout() {
    return backpressureTimeout;
  }

  public Duration controlBackpressureTimeout() {
    return backpressureTimeout;
  }

  public Duration ackTimeout() {
    return ackTimeout;
  }

  public int serverStreamId() {
    return serverStreamId;
  }

  private Duration validate(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.compareTo(Duration.ZERO) <= 0) {
      throw new IllegalArgumentException(name + " > 0 expected, but got: " + name);
    }
    return value;
  }

  private int validate(Integer value, String name) {
    Objects.requireNonNull(value, name);
    if (value <= 0) {
      throw new IllegalArgumentException(name + " > 0 expected, but got: " + name);
    }
    return value;
  }

  public static class Builder {

    private ChannelUriStringBuilder clientChannel =
        new ChannelUriStringBuilder().reliable(TRUE).media("udp").endpoint("localhost:" + 0);
    private ChannelUriStringBuilder serverChannel =
        new ChannelUriStringBuilder().reliable(TRUE).media("udp");
    private Duration connectTimeout = CONNECT_TIMEOUT;
    private Duration backpressureTimeout = BACKPRESSURE_TIMEOUT;
    private Duration controlBackpressureTimeout = CONTROL_BACKPRESSURE_TIMEOUT;
    private Duration ackTimeout = ACK_TIMEOUT;
    private Integer serverStreamId = SERVER_STREAM_ID;

    public Builder clientChannel(ChannelUriStringBuilder clientChannel) {
      this.clientChannel = clientChannel;
      return this;
    }

    public Builder clientChannel(Consumer<ChannelUriStringBuilder> clientChannel) {
      clientChannel.accept(this.clientChannel);
      return this;
    }

    public Builder serverChannel(ChannelUriStringBuilder serverChannel) {
      this.serverChannel = serverChannel;
      return this;
    }

    public Builder serverChannel(Consumer<ChannelUriStringBuilder> serverChannel) {
      serverChannel.accept(this.serverChannel);
      return this;
    }

    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    public Builder backpressureTimeout(Duration backpressureTimeout) {
      this.backpressureTimeout = backpressureTimeout;
      return this;
    }

    public Builder controlBackpressureTimeout(Duration controlBackpressureTimeout) {
      this.controlBackpressureTimeout = controlBackpressureTimeout;
      return this;
    }

    public Builder ackTimeout(Duration ackTimeout) {
      this.ackTimeout = ackTimeout;
      return this;
    }

    public Builder serverStreamId(int serverStreamId) {
      this.serverStreamId = serverStreamId;
      return this;
    }

    public AeronClientOptions build() {
      return new AeronClientOptions(this);
    }
  }
}
