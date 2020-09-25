package com.circuitbreaker.app;

import java.io.IOException;
import java.time.Duration;
import java.util.EmptyStackException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.vavr.control.Try;

@RestController
public class CBController {
	
	
	BackEndProxy bkend = new BackEndProxy();
	
	private static Executor backendExecutor = Executors.newFixedThreadPool(3);
	
	
	
	
	TimeLimiter timeLimiter = TimeLimiter.of(Duration.ofMillis(5));
	Retry retry = Retry.ofDefaults("backend");
	
	TimeLimiterConfig config = TimeLimiterConfig.custom()
			   .cancelRunningFuture(true)
			   .timeoutDuration(Duration.ofMillis(50))
			   .build();

	TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(config);
	
	// Create a custom configuration for a CircuitBreaker
	CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
	  .failureRateThreshold(5)
	  .slowCallRateThreshold(50)
	  .waitDurationInOpenState(Duration.ofMillis(10000))
	  .slowCallDurationThreshold(Duration.ofSeconds(2))
	  .permittedNumberOfCallsInHalfOpenState(3)
	  .minimumNumberOfCalls(2)
	  .slidingWindowType(SlidingWindowType.TIME_BASED)
	  .slidingWindowSize(5)
	  .build();

	CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
	CircuitBreaker circuitBreakerWithDefaultConfig = circuitBreakerRegistry.circuitBreaker("name");
	
	CircuitBreaker circuitBreakerWithCustomConfig = circuitBreakerRegistry.circuitBreaker("bill", circuitBreakerConfig);
	CircuitBreaker circuitBreakerWithCustomConfig2 = circuitBreakerRegistry.circuitBreaker("bob", circuitBreakerConfig);

	Bulkhead bulkhead = Bulkhead.ofDefaults("backendService");
	
	ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
	
	static int pending_calls = 0;
	
	@GetMapping("/test")
	public String testCB(@RequestParam String name) {
		
		pending_calls++;
		
		String result = "";
		try {
		
			CompletableFuture<String> completableFuture = CompletableFuture.supplyAsync
					( () -> bkend.getResult(name), backendExecutor);
				
			Callable<String>  chainedCallable;
			TimeLimiter timeLimiterFromReg = timeLimiterRegistry.timeLimiter(name,config);
			Callable<String>  timelimitedCall = TimeLimiter.decorateFutureSupplier(timeLimiterFromReg, () -> completableFuture);
			chainedCallable = CircuitBreaker.decorateCallable(circuitBreakerRegistry.circuitBreaker(name), timelimitedCall);
/*
 			if(name.equalsIgnoreCase("bill")) {
 				chainedCallable = CircuitBreaker.decorateCallable(circuitBreakerWithCustomConfig, timelimitedCall);
			} else {
				chainedCallable = CircuitBreaker.decorateCallable(circuitBreakerWithDefaultConfig, timelimitedCall);
			}
*/
			
			Try<String> tryObject = Try.of(chainedCallable::call);
			if(tryObject.isFailure()) {
				if(tryObject.failed().get().getClass().isInstance(TimeoutException.class)) {
					throw new TimeoutException();
				}
			}
			
			result = tryObject.get();
			pending_calls--;
			String txt = result + " " + String.valueOf(pending_calls);
			return result;
			
		} catch( TimeoutException timeoutException) {
			pending_calls--;
			String txt = "Timeout " + String.valueOf(pending_calls);
			return txt;
		} catch (CallNotPermittedException e) {
			pending_calls--;
			String txt = "Open " + String.valueOf(pending_calls);
			return txt;
		} catch (Exception e) {
			pending_calls--;
			String txt = "Fail " + String.valueOf(pending_calls);
			return txt;
		}
			
			
	}
	
	@GetMapping("/test2")
	public String testCB2(@RequestParam String name) {
		
		Supplier<String> getString = () ->  bkend.getResult(name);

		
	  	Supplier<String> decoratedGetString = Decorators.ofSupplier(getString)
				.withCircuitBreaker(circuitBreakerRegistry.circuitBreaker("name2"))
				.withRetry(retry)
				.decorate();
	
		
		String result;
/*
		CompletableFuture<String> future = Decorators.ofSupplier(getString)
				.withBulkhead(bulkhead)
			    .withTimeLimiter(timeLimiter, scheduler)
			    .withCircuitBreaker(circuitBreakerWithDefaultConfig)
			    .withRetry(retry)
			    .get().toCompletableFuture();
*/


		try {
			timeLimiter.executeFutureSupplier(
					() -> CompletableFuture.supplyAsync(decoratedGetString));
		} catch(TimeoutException e) {
			
			return "timeout" ;
		} catch(Exception e) {
			return "unknown";
		}
		return "yay";
	
		
/*
		try {
			result = timeLimiter.executeFutureSupplier( () -> decoratedGetString.get());
		} catch(EmptyStackException e) {
			return "my exception";
		} 
		//		.recover(throwable -> "hello from recovery").get();
		//return decoratedGetString.get();
		return result;
*/
	}
	

}
