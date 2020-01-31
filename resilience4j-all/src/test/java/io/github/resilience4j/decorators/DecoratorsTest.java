/*
 *
 *  Copyright 2016 Robert Winkler
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.resilience4j.decorators;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.cache.Cache;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.test.HelloWorldService;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.vavr.CheckedFunction0;
import io.vavr.CheckedFunction1;
import io.vavr.CheckedRunnable;
import io.vavr.control.Try;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

public class DecoratorsTest {

    private boolean state = false;
    private HelloWorldService helloWorldService;

    @Before
    public void setUp() {
        helloWorldService = mock(HelloWorldService.class);
    }

    @Test
    public void testExecuteSupplierInThreadPoolBulkhead() throws ExecutionException, InterruptedException {
        given(helloWorldService.returnHelloWorld()).willReturn("Hello world");
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.ofDefaults("helloBackend");
        CompletionStage<String> completionStage = Decorators
            .ofCompletionStage(bulkhead.decorateSupplier(() -> helloWorldService.returnHelloWorld()))
            .withCircuitBreaker(circuitBreaker)
            .get();

        String value = completionStage.toCompletableFuture().get();

        assertThat(value).isEqualTo("Hello world");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        then(helloWorldService).should(times(1)).returnHelloWorld();
    }

    @Test
    public void shouldThrowTimeoutException() {
        TimeLimiter timeLimiter = TimeLimiter.of("helloBackend", TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofMillis(100)).build());
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.ofDefaults("helloBackend");
        CompletionStage<String> completionStage = Decorators
            .ofCallable(() -> {
                Thread.sleep(1000);
                return "Bla";
            })
            .withThreadPoolBulkhead(bulkhead)
            .withTimeLimiter(timeLimiter, Executors.newSingleThreadScheduledExecutor())
            .withCircuitBreaker(circuitBreaker)
            .get();

        assertThatThrownBy(() -> completionStage.toCompletableFuture().get())
            .hasCauseInstanceOf(TimeoutException.class);

        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    public void shouldRecoverTimeoutException() throws ExecutionException, InterruptedException {
        TimeLimiter timeLimiter = TimeLimiter.of("helloBackend", TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofMillis(100)).build());
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.ofDefaults("helloBackend");
        CompletionStage<String> completionStage = Decorators
            .ofCallable(() -> {
                Thread.sleep(1000);
                return "Bla";
            })
            .withThreadPoolBulkhead(bulkhead)
            .withTimeLimiter(timeLimiter, Executors.newSingleThreadScheduledExecutor())
            .withCircuitBreaker(circuitBreaker)
            .withFallback(TimeoutException.class, (e) -> "Fallback")
            .get();

        String result = completionStage.toCompletableFuture().get();

        assertThat(result).isEqualTo("Fallback");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    public void testDecorateSupplier() {
        given(helloWorldService.returnHelloWorld()).willReturn("Hello world");
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        Supplier<String> decoratedSupplier = Decorators
            .ofSupplier(() -> helloWorldService.returnHelloWorld())
            .withCircuitBreaker(circuitBreaker)
            .withRetry(Retry.ofDefaults("id"))
            .withRateLimiter(RateLimiter.ofDefaults("testName"))
            .withBulkhead(Bulkhead.ofDefaults("testName"))
            .decorate();

        String result = decoratedSupplier.get();

        assertThat(result).isEqualTo("Hello world");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        then(helloWorldService).should(times(1)).returnHelloWorld();
    }

    @Test
    public void testDecorateSupplierWithThreadPoolBulkhead()
        throws ExecutionException, InterruptedException {

        given(helloWorldService.returnHelloWorld()).willReturn("Hello world");
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");

        CompletableFuture<String> future = Decorators
            .ofSupplier(() -> helloWorldService.returnHelloWorld())
            .withThreadPoolBulkhead(ThreadPoolBulkhead.ofDefaults("helloBackend"))
            .withTimeLimiter(TimeLimiter.ofDefaults(), Executors.newSingleThreadScheduledExecutor())
            .withCircuitBreaker(circuitBreaker)
            .get().toCompletableFuture();

        String result = future.get();

        assertThat(result).isEqualTo("Hello world");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        then(helloWorldService).should(times(1)).returnHelloWorld();
    }

    @Test
    public void testDecorateRunnableWithThreadPoolBulkhead()
        throws ExecutionException, InterruptedException {

        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");

        CompletableFuture<Void> future = Decorators
            .ofRunnable(() -> helloWorldService.sayHelloWorld())
            .withThreadPoolBulkhead(ThreadPoolBulkhead.ofDefaults("helloBackend"))
            .withCircuitBreaker(circuitBreaker)
            .get().toCompletableFuture();

        future.get();

        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        then(helloWorldService).should(times(1)).sayHelloWorld();
    }

    @Test
    public void testDecorateCallable() throws Exception {
        given(helloWorldService.returnHelloWorldWithException()).willReturn("Hello world");
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        Callable<String> decoratedCallable = Decorators
            .ofCallable(() -> helloWorldService.returnHelloWorldWithException())
            .withCircuitBreaker(circuitBreaker)
            .withRetry(Retry.ofDefaults("id"))
            .withRateLimiter(RateLimiter.ofDefaults("testName"))
            .withBulkhead(Bulkhead.ofDefaults("testName"))
            .decorate();

        String result = decoratedCallable.call();

        assertThat(result).isEqualTo("Hello world");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        then(helloWorldService).should(times(1)).returnHelloWorldWithException();
    }

    @Test
    public void testDecorateSupplierWithFallback() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        circuitBreaker.transitionToOpenState();

        Supplier<String> decoratedSupplier = Decorators
            .ofSupplier(() -> helloWorldService.returnHelloWorld())
            .withCircuitBreaker(circuitBreaker)
            .withFallback(CallNotPermittedException.class, e -> "Fallback")
            .decorate();

        String result = decoratedSupplier.get();

        assertThat(result).isEqualTo("Fallback");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfNotPermittedCalls()).isEqualTo(1);
        then(helloWorldService).should(never()).returnHelloWorld();
    }

    @Test
    public void testDecorateCheckedSupplier() throws IOException {
        given(helloWorldService.returnHelloWorldWithException()).willReturn("Hello world");
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        CheckedFunction0<String> decoratedSupplier = Decorators
            .ofCheckedSupplier(() -> helloWorldService.returnHelloWorldWithException())
            .withCircuitBreaker(circuitBreaker)
            .withRetry(Retry.ofDefaults("id"))
            .withRateLimiter(RateLimiter.ofDefaults("testName"))
            .withBulkhead(Bulkhead.ofDefaults("testName"))
            .decorate();

        String result = Try.of(decoratedSupplier).get();

        assertThat(result).isEqualTo("Hello world");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        then(helloWorldService).should(times(1)).returnHelloWorldWithException();
    }

    @Test
    public void testDecorateCheckedSupplierWithFallback() throws Throwable {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        circuitBreaker.transitionToOpenState();

        CheckedFunction0<String> checkedSupplier = Decorators
            .ofCheckedSupplier(() -> helloWorldService.returnHelloWorldWithException())
            .withCircuitBreaker(circuitBreaker)
            .withFallback(CallNotPermittedException.class, e -> "Fallback")
            .decorate();

        String result = checkedSupplier.apply();

        assertThat(result).isEqualTo("Fallback");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfNotPermittedCalls()).isEqualTo(1);
        then(helloWorldService).should(never()).returnHelloWorld();
    }

    @Test
    public void testDecorateCallableWithFallback() throws Throwable {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        circuitBreaker.transitionToOpenState();

        Callable<String> callable = Decorators
            .ofCallable(() -> helloWorldService.returnHelloWorldWithException())
            .withCircuitBreaker(circuitBreaker)
            .withFallback(CallNotPermittedException.class, e -> "Fallback")
            .decorate();

        String result = callable.call();

        assertThat(result).isEqualTo("Fallback");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfNotPermittedCalls()).isEqualTo(1);
        then(helloWorldService).should(never()).returnHelloWorld();
    }

    @Test
    public void testDecorateRunnable() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        Runnable decoratedRunnable = Decorators
            .ofRunnable(() -> helloWorldService.sayHelloWorld())
            .withCircuitBreaker(circuitBreaker)
            .withRetry(Retry.ofDefaults("id"))
            .withRateLimiter(RateLimiter.ofDefaults("testName"))
            .withBulkhead(Bulkhead.ofDefaults("testName"))
            .decorate();

        decoratedRunnable.run();

        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        then(helloWorldService).should(times(1)).sayHelloWorld();
    }


    @Test
    public void testDecorateCheckedRunnable() throws IOException {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        CheckedRunnable decoratedRunnable = Decorators
            .ofCheckedRunnable(() -> helloWorldService.sayHelloWorldWithException())
            .withCircuitBreaker(circuitBreaker)
            .withRetry(Retry.ofDefaults("id"))
            .withRateLimiter(RateLimiter.ofDefaults("testName"))
            .withBulkhead(Bulkhead.ofDefaults("testName"))
            .decorate();

        Try.run(decoratedRunnable);

        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        then(helloWorldService).should(times(1)).sayHelloWorldWithException();
    }


    @Test
    public void testDecorateCompletionStage() throws ExecutionException, InterruptedException {
        given(helloWorldService.returnHelloWorld()).willReturn("Hello world");
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        Supplier<CompletionStage<String>> completionStageSupplier =
            () -> CompletableFuture.supplyAsync(helloWorldService::returnHelloWorld);
        CompletionStage<String> completionStage = Decorators
            .ofCompletionStage(completionStageSupplier)
            .withCircuitBreaker(circuitBreaker)
            .withRetry(Retry.ofDefaults("id"), Executors.newSingleThreadScheduledExecutor())
            .withBulkhead(Bulkhead.ofDefaults("testName"))
            .get();

        String value = completionStage.toCompletableFuture().get();

        assertThat(value).isEqualTo("Hello world");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        then(helloWorldService).should(times(1)).returnHelloWorld();
    }

    @Test
    public void testExecuteConsumer() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        Decorators.DecorateConsumer<String> decoratedConsumer =
            Decorators.ofConsumer((String input) -> helloWorldService
                .sayHelloWorldWithName(input))
                .withCircuitBreaker(circuitBreaker)
                .withBulkhead(Bulkhead.ofDefaults("testName"))
                .withRateLimiter(RateLimiter.ofDefaults("testName"));

        decoratedConsumer.accept("test");

        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        then(helloWorldService).should(times(1)).sayHelloWorldWithName("test");
    }

    @Test
    public void testDecorateFunction() {
        given(helloWorldService.returnHelloWorldWithName("Name")).willReturn("Hello world Name");
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        Function<String, String> decoratedFunction = Decorators
            .ofFunction(helloWorldService::returnHelloWorldWithName)
            .withCircuitBreaker(circuitBreaker)
            .withRetry(Retry.ofDefaults("id"))
            .withRateLimiter(RateLimiter.ofDefaults("testName"))
            .withBulkhead(Bulkhead.ofDefaults("testName"))
            .decorate();

        String result = decoratedFunction.apply("Name");

        assertThat(result).isEqualTo("Hello world Name");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    public void testDecorateCheckedFunction() throws IOException {
        given(helloWorldService.returnHelloWorldWithNameWithException("Name"))
            .willReturn("Hello world Name");
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        CheckedFunction1<String, String> decoratedFunction = Decorators
            .ofCheckedFunction(helloWorldService::returnHelloWorldWithNameWithException)
            .withCircuitBreaker(circuitBreaker)
            .withRetry(Retry.ofDefaults("id"))
            .withRateLimiter(RateLimiter.ofDefaults("testName"))
            .withBulkhead(Bulkhead.ofDefaults("testName"))
            .decorate();

        String result = Try.of(() -> decoratedFunction.apply("Name")).get();

        assertThat(result).isEqualTo("Hello world Name");
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    public void testDecoratorBuilderWithRetry() {
        given(helloWorldService.returnHelloWorld()).willThrow(new RuntimeException("BAM!"));
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("helloBackend");
        Supplier<String> decoratedSupplier = Decorators
            .ofSupplier(() -> helloWorldService.returnHelloWorld())
            .withCircuitBreaker(circuitBreaker)
            .withRetry(Retry.ofDefaults("id"))
            .withBulkhead(Bulkhead.ofDefaults("testName"))
            .decorate();

        Try.of(decoratedSupplier::get);

        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(3);
        assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(3);
        then(helloWorldService).should(times(3)).returnHelloWorld();
    }

    @Test
    public void testDecoratorBuilderWithRateLimiter() {
        given(helloWorldService.returnHelloWorld()).willReturn("Hello world");
        RateLimiterConfig config = RateLimiterConfig.custom()
            .timeoutDuration(Duration.ofMillis(100))
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .limitForPeriod(1)
            .build();
        RateLimiter rateLimiter = RateLimiter.of("backendName", config);
        CheckedFunction0<String> restrictedSupplier = Decorators
            .ofCheckedSupplier(() -> helloWorldService.returnHelloWorld())
            .withRateLimiter(rateLimiter)
            .decorate();
        alignTime(rateLimiter);

        Try<String> firstTry = Try.of(restrictedSupplier);
        Try<String> secondTry = Try.of(restrictedSupplier);

        assertThat(firstTry.isSuccess()).isTrue();
        assertThat(secondTry.isFailure()).isTrue();
        assertThat(secondTry.getCause()).isInstanceOf(RequestNotPermitted.class);
        then(helloWorldService).should(times(1)).returnHelloWorld();
    }

    private void alignTime(RateLimiter rateLimiter) {
        RateLimiter.Metrics metrics = rateLimiter.getMetrics();
        while (rateLimiter.acquirePermission()) {
            state = !state;
        }
        // Wait to the start of the next cycle in spin loop
        while (metrics.getAvailablePermissions() == 0) {
            state = !state;
        }
        System.out.println(state);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDecorateCheckedSupplierWithCache() {
        javax.cache.Cache<String, String> cache = mock(javax.cache.Cache.class);
        given(cache.containsKey("testKey")).willReturn(true);
        given(cache.get("testKey")).willReturn("Hello from cache");
        CheckedFunction1<String, String> cachedFunction = Decorators
            .ofCheckedSupplier(() -> "Hello world")
            .withCache(Cache.of(cache))
            .decorate();

        Try<String> value = Try.of(() -> cachedFunction.apply("testKey"));

        assertThat(value).contains("Hello from cache");
    }


    @SuppressWarnings("unchecked")
    @Test
    public void testDecorateSupplierWithCache() {
        javax.cache.Cache<String, String> cache = mock(javax.cache.Cache.class);
        given(cache.containsKey("testKey")).willReturn(true);
        given(cache.get("testKey")).willReturn("Hello from cache");
        Function<String, String> cachedFunction = Decorators
            .ofSupplier(() -> "Hello world")
            .withCache(Cache.of(cache))
            .decorate();

        String value = cachedFunction.apply("testKey");

        assertThat(value).isEqualTo("Hello from cache");
    }

}
