package reactor.aeron;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.Operators;
import reactor.util.Logger;
import reactor.util.Loggers;

final class AeronWriteSequencer implements Disposable {

  private static final Logger logger = Loggers.getLogger(AeronWriteSequencer.class);

  private static final int PREFETCH = 1;

  private final Publisher<?> dataPublisher;
  private final long sessionId;
  private final MessagePublication publication;

  private final PublisherSender inner;

  private volatile boolean removed;

  private final MonoProcessor<Void> newPromise;

  private final AtomicBoolean completed = new AtomicBoolean(false);

  /**
   * Constructor for templating {@link AeronWriteSequencer} objects.
   *
   * @param sessionId session id
   * @param publication message publication
   */
  AeronWriteSequencer(long sessionId, MessagePublication publication) {
    this.sessionId = sessionId;
    this.publication = Objects.requireNonNull(publication, "message publication must be present");
    // Rest of the fields are nulls
    this.dataPublisher = null;
    this.inner = null;
    this.newPromise = null;
  }

  /**
   * Constructor.
   *
   * @param sessionId sessino id
   * @param publication message publication
   * @param dataPublisher data publisher
   */
  private AeronWriteSequencer(
      long sessionId, MessagePublication publication, Publisher<?> dataPublisher) {
    this.sessionId = sessionId;
    this.publication = publication;
    this.dataPublisher = dataPublisher;
    this.newPromise = MonoProcessor.create();

    this.inner = new PublisherSender(this, publication, sessionId);
  }

  /**
   * Adds a client defined data publisher to {@link AeronWriteSequencer} instance.
   *
   * @param publisher data publisher
   * @return mono handle
   */
  public Mono<Void> write(Publisher<?> publisher) {
    Objects.requireNonNull(publisher, "dataPublisher must be not null");
    return new AeronWriteSequencer(sessionId, publication, publisher).write0();
  }

  private Mono<Void> write0() {
    return Mono.fromRunnable(() -> dataPublisher.subscribe(inner))
        .then(newPromise)
        .takeUntilOther(publication.onDispose().doFinally(s -> dispose()));
  }

  @Override
  public void dispose() {
    if (!removed) {
      removed = true;
      if (inner != null) {
        inner.cancel();
      }
    }
  }

  @Override
  public boolean isDisposed() {
    return removed;
  }

  private void handleError(Throwable th) {
    logger.error("Unexpected exception: ", th);
  }

  static class PublisherSender implements CoreSubscriber<Object>, Subscription {

    static final AtomicReferenceFieldUpdater<PublisherSender, Subscription> MISSED_SUBSCRIPTION =
        AtomicReferenceFieldUpdater.newUpdater(
            PublisherSender.class, Subscription.class, "missedSubscription");

    static final AtomicLongFieldUpdater<PublisherSender> MISSED_REQUESTED =
        AtomicLongFieldUpdater.newUpdater(PublisherSender.class, "missedRequested");

    static final AtomicLongFieldUpdater<PublisherSender> MISSED_PRODUCED =
        AtomicLongFieldUpdater.newUpdater(PublisherSender.class, "missedProduced");

    static final AtomicIntegerFieldUpdater<PublisherSender> WIP =
        AtomicIntegerFieldUpdater.newUpdater(PublisherSender.class, "wip");

    private final AeronWriteSequencer parent;

    private final long sessionId;

    private final MessagePublication publication;
    private volatile Subscription missedSubscription;
    private volatile long missedRequested;
    private volatile long missedProduced;
    private volatile int wip;

    private boolean inactive;

    /** The current outstanding request amount. */
    private long requested;

    private boolean unbounded;
    /** The current subscription which may null if no Subscriptions have been set. */
    private Subscription actual;

    private long produced;

    PublisherSender(AeronWriteSequencer parent, MessagePublication publication, long sessionId) {
      this.parent = parent;
      this.sessionId = sessionId;
      this.publication = publication;
    }

    @Override
    public final void cancel() {
      // full stop
      if (!inactive) {
        inactive = true;

        drain();
      }

      // todo think about it here
      parent.completed.set(true);
    }

    @Override
    public void onComplete() {
      long p = produced;

      produced = 0L;
      produced(p);

      parent.completed.set(true);
    }

    @Override
    public void onError(Throwable t) {
      long p = produced;

      produced = 0L;
      produced(p);

      //noinspection ConstantConditions
      parent.newPromise.onError(t);
    }

    @Override
    public void onNext(Object t) {
      produced++;

      publication
          .enqueue(MessageType.NEXT, (ByteBuffer) t, sessionId)
          .doOnSuccess(avoid -> request(1L))
          .subscribe(null, this::disposeCurrentDataStream);
    }

    private void disposeCurrentDataStream(Throwable th) {
      cancel();
      //noinspection ConstantConditions
      parent.newPromise.onError(
          new Exception("Failed to publish signal into session: " + sessionId, th));
    }

    @Override
    public void onSubscribe(Subscription s) {
      if (inactive) {
        s.cancel();
        return;
      }

      Objects.requireNonNull(s);

      if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
        actual = s;
        //noinspection AccessStaticViaInstance
        request(parent.PREFETCH);

        long r = requested;

        if (WIP.decrementAndGet(this) != 0) {
          drainLoop();
        }

        if (r != 0L) {
          s.request(r);
        }

        return;
      }

      MISSED_SUBSCRIPTION.set(this, s);
      drain();
    }

    @Override
    public final void request(long n) {
      if (parent.completed.get()) {
        //noinspection ConstantConditions
        parent.newPromise.onComplete();
        return;
      }
      if (Operators.validate(n)) {
        if (unbounded) {
          return;
        }
        if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
          long r = requested;

          if (r != Long.MAX_VALUE) {
            r = Operators.addCap(r, n);
            requested = r;
            if (r == Long.MAX_VALUE) {
              unbounded = true;
            }
          }
          Subscription a = actual;

          if (WIP.decrementAndGet(this) != 0) {
            drainLoop();
          }

          if (a != null) {
            a.request(n);
          }

          return;
        }

        Operators.addCap(MISSED_REQUESTED, this, n);

        drain();
      }
    }

    final void drain() {
      if (WIP.getAndIncrement(this) != 0) {
        return;
      }
      drainLoop();
    }

    final void drainLoop() {
      int missed = 1;

      long requestAmount = 0L;
      Subscription requestTarget = null;

      for (; ; ) {

        Subscription ms = missedSubscription;

        if (ms != null) {
          ms = MISSED_SUBSCRIPTION.getAndSet(this, null);

          if (ms == Operators.cancelledSubscription()) {
            Subscription a = actual;
            if (a != null) {
              a.cancel();
              actual = null;
            }
          }
        }

        long mr = missedRequested;
        if (mr != 0L) {
          mr = MISSED_REQUESTED.getAndSet(this, 0L);
        }

        long mp = missedProduced;
        if (mp != 0L) {
          mp = MISSED_PRODUCED.getAndSet(this, 0L);
        }

        Subscription a = actual;

        if (inactive) {
          if (a != null) {
            a.cancel();
            actual = null;
          }
          if (ms != null) {
            ms.cancel();
          }
        } else {
          long r = requested;
          if (r != Long.MAX_VALUE) {
            long u = Operators.addCap(r, mr);

            if (u != Long.MAX_VALUE) {
              long v = u - mp;
              if (v < 0L) {
                Operators.reportMoreProduced();
                v = 0;
              }
              r = v;
            } else {
              r = u;
            }
            requested = r;
          }

          if (ms != null) {
            actual = ms;
            if (r != 0L) {
              requestAmount = Operators.addCap(requestAmount, r);
              requestTarget = ms;
            }
          } else if (mr != 0L && a != null) {
            requestAmount = Operators.addCap(requestAmount, mr);
            requestTarget = a;
          }
        }

        missed = WIP.addAndGet(this, -missed);
        if (missed == 0) {
          if (requestAmount != 0L) {
            requestTarget.request(requestAmount);
          }
          return;
        }
      }
    }

    final void produced(long n) {
      if (unbounded) {
        return;
      }
      if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
        long r = requested;

        if (r != Long.MAX_VALUE) {
          long u = r - n;
          if (u < 0L) {
            Operators.reportMoreProduced();
            u = 0;
          }
          requested = u;
        } else {
          unbounded = true;
        }

        if (WIP.decrementAndGet(this) == 0) {
          return;
        }

        drainLoop();

        return;
      }

      Operators.addCap(MISSED_PRODUCED, this, n);

      drain();
    }
  }
}
