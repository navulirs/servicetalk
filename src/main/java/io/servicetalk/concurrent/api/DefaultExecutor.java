/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.concurrent.api;

import io.servicetalk.concurrent.Cancellable;
import io.servicetalk.concurrent.internal.DefaultThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.Cancellable.IGNORE_CANCEL;
import static io.servicetalk.concurrent.internal.ExecutorUtil.executeOnService;
import static io.servicetalk.concurrent.internal.ExecutorUtil.scheduleForSubscriber;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * An implementation of {@link Executor} that uses an implementation of {@link java.util.concurrent.Executor} to execute tasks.
 */
final class DefaultExecutor implements Executor {

    private static final long DEFAULT_KEEP_ALIVE_TIME_SECONDS = 60;
    /**
     * We do not execute user code (potentially blocking/long running) on the scheduler thread and hence using a single
     * scheduler thread is usually ok. In cases, when it is not, one can always override the executor with a custom scheduler.
     */
    private static final ScheduledExecutorService SINGLE_THREADED_SCHEDULER = newSingleThreadScheduledExecutor(new DefaultThreadFactory());
    /**
     * Schedulers are only used to generate a tick and do not execute any user code. This means they will never run any
     * blocking code and hence it does not matter whether we use the interruptOnCancel as sent by the user upon creation in the scheduler.
     * User code (completion of Completable on tick) will be executed on the configured executor and not the Scheduler thread.
     */
    private static final InternalScheduler GLOBAL_SINGLE_THREADED_SCHEDULER = newSchedulerNoClose(SINGLE_THREADED_SCHEDULER);
    private static final RejectedExecutionHandler DEFAULT_REJECTION_HANDLER = new AbortPolicy();

    private final InternalExecutor executor;
    private final InternalScheduler scheduler;
    private final CompletableProcessor onClose = new CompletableProcessor();

    DefaultExecutor(int coreSize, int maxSize, ThreadFactory threadFactory) {
        this(new ThreadPoolExecutor(coreSize, maxSize, DEFAULT_KEEP_ALIVE_TIME_SECONDS, SECONDS, new SynchronousQueue<>(), threadFactory, DEFAULT_REJECTION_HANDLER));
    }

    DefaultExecutor(java.util.concurrent.Executor jdkExecutor) {
        // Since we run blocking task, we should try interrupt when cancelled.
        this(jdkExecutor, true);
    }

    DefaultExecutor(java.util.concurrent.Executor jdkExecutor, boolean interruptOnCancel) {
        // Since we run blocking task, we should try interrupt when cancelled.
        this(jdkExecutor, GLOBAL_SINGLE_THREADED_SCHEDULER, interruptOnCancel);
    }

    DefaultExecutor(java.util.concurrent.Executor jdkExecutor, ScheduledExecutorService scheduler) {
        // Since we run blocking task, we should try interrupt when cancelled.
        this(jdkExecutor, scheduler, true);
    }

    DefaultExecutor(java.util.concurrent.Executor jdkExecutor, ScheduledExecutorService scheduler, boolean interruptOnCancel) {
        this(jdkExecutor, newScheduler(scheduler, interruptOnCancel), interruptOnCancel);
    }

    private DefaultExecutor(@Nullable java.util.concurrent.Executor jdkExecutor, @Nullable InternalScheduler scheduler, boolean interruptOnCancel) {
        if (jdkExecutor == null) {
            if (scheduler != null) {
                scheduler.run();
            }
            throw new NullPointerException("jdkExecutor");
        } else if (scheduler == null) {
            shutdownExecutor(jdkExecutor);
            throw new NullPointerException("scheduler");
        }

        executor = newInternalExecutor(jdkExecutor, interruptOnCancel);
        this.scheduler = scheduler;
    }

    @Override
    public Cancellable execute(Runnable task) {
        return executor.apply(task);
    }

    @Override
    public Completable schedule(long duration, TimeUnit durationUnit) {
        return scheduler.apply(duration, durationUnit);
    }

    @Override
    public Completable onClose() {
        return onClose;
    }

    @Override
    public Completable closeAsync() {
        return new Completable() {
            @Override
            protected void handleSubscribe(Subscriber subscriber) {
                onClose.subscribe(subscriber);
                try {
                    try {
                        executor.run();
                    } finally {
                        scheduler.run();
                    }
                } catch (Throwable cause) {
                    onClose.onError(cause);
                    return;
                }
                onClose.onComplete();
            }
        };
    }

    /**
     * {@link Runnable} interface will invoke {@link ExecutorService#shutdown()}.
     */
    private interface InternalExecutor extends Function<Runnable, Cancellable>, Runnable {
    }

    /**
     * {@link Runnable} interface will invoke {@link ScheduledExecutorService#shutdown()}.
     */
    public interface InternalScheduler extends BiLongFunction<TimeUnit, Completable>, Runnable {
    }

    private static void shutdownExecutor(java.util.concurrent.Executor jdkExecutor) {
        if (jdkExecutor instanceof ExecutorService) {
            ((ExecutorService) jdkExecutor).shutdown();
        } else if (jdkExecutor instanceof AutoCloseable) {
            try {
                ((AutoCloseable) jdkExecutor).close();
            } catch (Exception e) {
                throw new RuntimeException("unexpected exception while closing executor: " + jdkExecutor, e);
            }
        }
    }

    private static InternalExecutor newInternalExecutor(java.util.concurrent.Executor jdkExecutor, boolean interruptOnCancel) {
        if (jdkExecutor instanceof ExecutorService) {
            return new InternalExecutor() {
                private final ExecutorService service = (ExecutorService) jdkExecutor;

                @Override
                public void run() {
                    service.shutdown();
                }

                @Override
                public Cancellable apply(Runnable runnable) {
                    return executeOnService(service, runnable, interruptOnCancel);
                }
            };
        }
        return new InternalExecutor() {
            @Override
            public void run() {
                shutdownExecutor(jdkExecutor);
            }

            @Override
            public Cancellable apply(Runnable runnable) {
                jdkExecutor.execute(runnable);
                return IGNORE_CANCEL;
            }
        };
    }

    private static InternalScheduler newSchedulerNoClose(ScheduledExecutorService service) {
        return new InternalScheduler() {
            @Override
            public void run() {
            }

            @Override
            public Completable apply(long duration, TimeUnit timeUnit) {
                return scheduleApply(service, true, duration, timeUnit);
            }
        };
    }

    private static InternalScheduler newScheduler(ScheduledExecutorService service, boolean interruptOnCancel) {
        return new InternalScheduler() {
            @Override
            public void run() {
                service.shutdown();
            }

            @Override
            public Completable apply(long duration, TimeUnit timeUnit) {
                return scheduleApply(service, interruptOnCancel, duration, timeUnit);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Completable scheduleApply(ScheduledExecutorService service, boolean interruptOnCancel, long duration, TimeUnit timeUnit) {
        return new Completable() {
            @Override
            protected void handleSubscribe(Subscriber subscriber) {
                scheduleForSubscriber(subscriber, service, interruptOnCancel, duration, timeUnit);
            }
        };
    }
}
