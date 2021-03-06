package reactor.aeron.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import reactor.aeron.Configurations;
import reactor.aeron.RateReporter;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpServer;

public class ReactorNettyServerTps {

  /**
   * Main runner.
   *
   * @param args program arguments.
   * @throws InterruptedException on timeout.
   */
  public static void main(String[] args) throws InterruptedException {
    System.out.println(
        "message size: "
            + Configurations.MESSAGE_LENGTH
            + ", number of messages: "
            + Configurations.NUMBER_OF_MESSAGES
            + ", address: "
            + Configurations.MDC_ADDRESS
            + ", port: "
            + Configurations.MDC_PORT);

    LoopResources loopResources = LoopResources.create("reactor-netty");

    RateReporter reporter = new RateReporter();

    TcpServer.create()
        .runOn(loopResources)
        .host(Configurations.MDC_ADDRESS)
        .port(Configurations.MDC_PORT)
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.SO_KEEPALIVE, true)
        .option(ChannelOption.SO_REUSEADDR, true)
        .doOnConnection(System.out::println)
        .bootstrap(
            b ->
                BootstrapHandlers.updateConfiguration(
                    b,
                    "channel",
                    (connectionObserver, channel) -> {
                      setupChannel(channel);
                    }))
        .handle(
            (inbound, outbound) ->
                inbound
                    .receive()
                    .retain()
                    .doOnNext(
                        buffer -> {
                          reporter.onMessage(1, buffer.readableBytes());
                          buffer.release();
                        })
                    .then())
        .bind()
        .doOnSuccess(
            server ->
                System.out.println("server has been started successfully on " + server.address()))
        .block()
        .onDispose(loopResources)
        .onDispose(reporter)
        .onDispose()
        .block();
  }

  private static void setupChannel(Channel channel) {
    final int maxFrameLength = 1024 * 1024;
    final int lengthFieldLength = 2;

    ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast(new LengthFieldPrepender(lengthFieldLength));
    pipeline.addLast(
        new LengthFieldBasedFrameDecoder(
            maxFrameLength, 0, lengthFieldLength, 0, lengthFieldLength));
  }
}
