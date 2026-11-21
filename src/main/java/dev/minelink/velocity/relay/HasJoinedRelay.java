package dev.minelink.velocity.relay;

import dev.minelink.velocity.BuildConstants;
import dev.minelink.velocity.config.MineLinkConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class HasJoinedRelay implements AutoCloseable {

    public static final int MAX_PROFILE_BYTES = 64 * 1024;
    private static final String USER_AGENT = "minelink/" + BuildConstants.VERSION;
    private static final byte[] EMPTY_BYTES = new byte[0];

    public sealed interface Result permits Result.Match, Result.NoMatch, Result.Error {
        record Match(String upstream, byte[] body) implements Result {}
        record NoMatch() implements Result {}
        record Error(String summary) implements Result {}
    }

    private sealed interface Outcome permits Outcome.Match, Outcome.NoMatch, Outcome.Error {
        record Match(byte[] body) implements Outcome {}
        record NoMatch() implements Outcome {}
        record Error(String reason) implements Outcome {}
    }

    private static IOException bodyTooLarge() {
        return new IOException("200 body exceeds " + MAX_PROFILE_BYTES + " bytes");
    }

    private final ExecutorService httpExecutor;
    private final HttpClient httpClient;
    private final ScheduledThreadPoolExecutor scheduler;

    public HasJoinedRelay() {
        AtomicInteger httpIdx = new AtomicInteger(1);
        this.httpExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "minelink-upstream-" + httpIdx.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(20_000))
            .executor(httpExecutor)
            .build();
        AtomicInteger timerIdx = new AtomicInteger(1);
        this.scheduler = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "minelink-timer-" + timerIdx.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    public CompletableFuture<Result> fanOut(List<Upstream> upstreams, String rawQuery, MineLinkConfig config) {
        if (upstreams.isEmpty()) {
            return CompletableFuture.completedFuture(new Result.Error("no upstreams configured"));
        }
        FanOut fanOut = new FanOut(upstreams, rawQuery, config);
        fanOut.start();
        return fanOut.future;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        httpExecutor.shutdownNow();
    }

    private CompletableFuture<Outcome> query(Upstream upstream, String rawQuery, int timeoutMs) {
        URI uri;
        try {
            uri = URI.create(upstream.hasJoinedUrl() + "?" + rawQuery);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(new Outcome.Error("invalid URI"));
        }

        int boundedTimeoutMs = Math.max(1, timeoutMs);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .GET()
            .timeout(Duration.ofMillis(boundedTimeoutMs))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build();

        CompletableFuture<HttpResponse<byte[]>> responseFuture =
            httpClient.sendAsync(request, boundedBodyHandler());

        AtomicBoolean timedOut = new AtomicBoolean(false);
        // Prior to JDK 26, HttpRequest.Builder.timeout() only covers up to response headers, not body completion.
        ScheduledFuture<?> bodyTimeout = scheduler.schedule(() -> {
            timedOut.set(true);
            responseFuture.cancel(true);
        }, boundedTimeoutMs, TimeUnit.MILLISECONDS);

        CompletableFuture<Outcome> mapped = responseFuture.handle((res, throwable) -> {
            bodyTimeout.cancel(false);
            if (throwable != null) {
                Throwable cause = unwrap(throwable);
                if (cause instanceof CancellationException) {
                    return timedOut.get()
                        ? new Outcome.Error("timeout")
                        : new Outcome.Error("cancelled");
                }
                if (cause instanceof HttpTimeoutException) {
                    return new Outcome.Error("timeout");
                }
                String msg = cause.getMessage();
                return new Outcome.Error(msg == null || msg.isBlank() ? cause.getClass().getSimpleName() : msg);
            }

            int status = res.statusCode();
            if (status == 204) {
                return new Outcome.NoMatch();
            }
            if (status != 200) {
                return new Outcome.Error("status " + status);
            }
            byte[] body = res.body();
            return ProfileValidator.isValid(body)
                ? new Outcome.Match(body)
                : new Outcome.Error("invalid 200 profile body");
        });

        // CompletableFuture.handle() does not propagate cancel() back to HttpClient.sendAsync().
        mapped.whenComplete((ignored, ex) -> {
            bodyTimeout.cancel(false);
            if (mapped.isCancelled()) {
                responseFuture.cancel(true);
            }
        });

        return mapped;
    }

    private static HttpResponse.BodyHandler<byte[]> boundedBodyHandler() {
        return responseInfo -> {
            if (responseInfo.statusCode() != 200) {
                return cancellingEmptySubscriber();
            }
            long contentLength = responseInfo.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength > MAX_PROFILE_BYTES) {
                return failingSubscriber(bodyTooLarge());
            }
            return new BoundedByteArraySubscriber(MAX_PROFILE_BYTES);
        };
    }

    private static HttpResponse.BodySubscriber<byte[]> cancellingEmptySubscriber() {
        return new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> cf = CompletableFuture.completedFuture(EMPTY_BYTES);

            @Override
            public CompletionStage<byte[]> getBody() {
                return cf;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
            }

            @Override
            public void onNext(List<ByteBuffer> items) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        };
    }

    private static HttpResponse.BodySubscriber<byte[]> failingSubscriber(Throwable error) {
        return new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> cf = CompletableFuture.failedFuture(error);

            @Override
            public CompletionStage<byte[]> getBody() {
                return cf;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
            }

            @Override
            public void onNext(List<ByteBuffer> items) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        };
    }

    private static final class BoundedByteArraySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        BoundedByteArraySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buf : items) {
                int remaining = buf.remaining();
                if (out.size() + remaining > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(bodyTooLarge());
                    return;
                }
                byte[] chunk = new byte[remaining];
                buf.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof CompletionException && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    private final class FanOut {
        final List<Upstream> upstreams;
        final String rawQuery;
        final MineLinkConfig config;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        final Map<String, CompletableFuture<Outcome>> inFlight = new LinkedHashMap<>();
        final List<ScheduledFuture<?>> timers = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        int launched = 0;
        int completed = 0;
        boolean done = false;

        FanOut(List<Upstream> upstreams, String rawQuery, MineLinkConfig config) {
            this.upstreams = upstreams;
            this.rawQuery = rawQuery;
            this.config = config;
        }

        void start() {
            synchronized (this) {
                timers.add(scheduler.schedule(this::onDeadline, config.deadlineMs(), TimeUnit.MILLISECONDS));
                launchNextLocked();
            }
        }

        private void onDeadline() {
            Result result;
            List<CompletableFuture<Outcome>> toCancel;
            List<ScheduledFuture<?>> timersToCancel;
            synchronized (this) {
                if (done) {
                    return;
                }
                for (String id : inFlight.keySet()) {
                    errors.add(id + ": deadline");
                }
                for (int i = launched; i < upstreams.size(); i++) {
                    errors.add(upstreams.get(i).id() + ": not reached");
                }
                toCancel = new ArrayList<>(inFlight.values());
                inFlight.clear();
                timersToCancel = new ArrayList<>(timers);
                timers.clear();
                done = true;
                result = new Result.Error(String.join(", ", errors));
            }
            cancelAll(toCancel, timersToCancel);
            future.complete(result);
        }

        private void launchNextLocked() {
            if (done || launched >= upstreams.size()) {
                return;
            }
            Upstream upstream = upstreams.get(launched++);
            CompletableFuture<Outcome> call = query(upstream, rawQuery, config.upstreamTimeoutMs());
            inFlight.put(upstream.id(), call);

            AtomicBoolean advanced = new AtomicBoolean(false);
            Runnable advance = () -> {
                if (advanced.compareAndSet(false, true)) {
                    synchronized (this) {
                        launchNextLocked();
                    }
                }
            };

            call.whenCompleteAsync((outcome, ex) -> {
                Result finished = null;
                List<CompletableFuture<Outcome>> toCancel = List.of();
                List<ScheduledFuture<?>> timersToCancel = List.of();
                boolean shouldAdvance = false;

                synchronized (this) {
                    inFlight.remove(upstream.id());
                    if (done) {
                        return;
                    }
                    completed++;

                    if (outcome instanceof Outcome.Match match) {
                        toCancel = new ArrayList<>(inFlight.values());
                        inFlight.clear();
                        timersToCancel = new ArrayList<>(timers);
                        timers.clear();
                        done = true;
                        finished = new Result.Match(upstream.id(), match.body());
                    } else {
                        if (outcome instanceof Outcome.Error err) {
                            errors.add(upstream.id() + ": " + err.reason());
                        } else if (ex != null) {
                            Throwable cause = unwrap(ex);
                            String msg = cause.getMessage();
                            errors.add(upstream.id() + ": "
                                + (msg == null || msg.isBlank() ? cause.getClass().getSimpleName() : msg));
                        }
                        shouldAdvance = true;
                        if (completed == upstreams.size()) {
                            timersToCancel = new ArrayList<>(timers);
                            timers.clear();
                            done = true;
                            finished = errors.isEmpty()
                                ? new Result.NoMatch()
                                : new Result.Error(String.join(", ", errors));
                        }
                    }
                }

                if (finished != null) {
                    cancelAll(toCancel, timersToCancel);
                    future.complete(finished);
                } else if (shouldAdvance) {
                    advance.run();
                }
            }, httpExecutor);

            if (launched < upstreams.size()) {
                if (config.staggerMs() <= 0) {
                    if (advanced.compareAndSet(false, true)) {
                        launchNextLocked();
                    }
                } else {
                    timers.add(scheduler.schedule(advance, config.staggerMs(), TimeUnit.MILLISECONDS));
                }
            }
        }

        private void cancelAll(
            List<CompletableFuture<Outcome>> calls,
            List<ScheduledFuture<?>> scheduled
        ) {
            for (ScheduledFuture<?> t : scheduled) {
                t.cancel(false);
            }
            for (CompletableFuture<Outcome> c : calls) {
                c.cancel(true);
            }
        }
    }
}
