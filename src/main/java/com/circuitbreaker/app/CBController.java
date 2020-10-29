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
import org.springframework.web.client.RestTemplate;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.vavr.CheckedFunction0;
import io.vavr.CheckedRunnable;
import io.vavr.control.Try;
import reactor.core.publisher.Mono;

@RestController
public class CBController {
	
	
	BackEndProxy bkend = new BackEndProxy();
	
	private static Executor backendExecutor = Executors.newFixedThreadPool(3);
	ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
	
	ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry = 
			  ThreadPoolBulkheadRegistry.ofDefaults();
	
	
	TimeLimiter timeLimiter = TimeLimiter.of(Duration.ofMillis(50));
	Retry retry = Retry.ofDefaults("backend");
	
	TimeLimiterConfig config = TimeLimiterConfig.custom()
			   .cancelRunningFuture(true)
			   .timeoutDuration(Duration.ofMillis(100))
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
	
//	ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
	
	RateLimiterConfig rlconfig = RateLimiterConfig.custom()
			  .limitRefreshPeriod(Duration.ofMillis(50))
			  .limitForPeriod(2)
			  .timeoutDuration(Duration.ofMillis(25))
			  .build();

	// Create registry
	RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(rlconfig);
	
	static int pending_calls = 0;
	static int clear_delay = 0;
	
///////////////////////////////////////////////////////////////////////////
	@GetMapping("/ctest")
	public String callBackend(String name) {
		String result ="not set";
		try {
			String uri;
			RestTemplate restTemplate = new RestTemplate();
			if(name.equalsIgnoreCase("bill")) {
				uri = "http://localhost:8081/bill";	
			}
			else {
				uri = "http://localhost:8082/bob";					
			}
			
			if(name.equalsIgnoreCase("bill") && clear_delay == 1) {
				String result2 = restTemplate.getForObject("http://localhost:8081/reset", String.class);
				clear_delay = 0;
				return "=============Delay Cleared=========================";
			}

//			ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

			Supplier restCall = () -> restTemplate.getForObject(uri, String.class);
			
			CompletableFuture<String> future = Decorators
				    .ofSupplier(() -> restTemplate.getForObject(uri, String.class))
				    .withThreadPoolBulkhead(ThreadPoolBulkhead.ofDefaults("helloBackend"))
				    .withTimeLimiter(timeLimiterRegistry.timeLimiter(name,config), Executors.newSingleThreadScheduledExecutor())
				    .withCircuitBreaker(circuitBreakerRegistry.circuitBreaker(name, circuitBreakerConfig))
//				    .withFallback(TimeoutException.class, (e) -> return e.getmessage())
				    .get().toCompletableFuture();
			
			result = future.get();
		


		} catch (Exception e) {
			System.out.print(e);
			System.out.print("\n");
			if (e.getCause() instanceof TimeoutException) {
				return "Ok we can capture the timeout as well";
			}
			if (e.getCause() instanceof CallNotPermittedException) {
				return "ok we can capture open events";
			}
			String txt = "Fail 2" + String.valueOf(pending_calls);
			if(e.getMessage().contains("OPEN")) {
				txt = "CB Open";
				clear_delay = 1;
			} else {
				txt = "general failure";
				System.out.print(e.getMessage() + "\n");
			}
			return txt;
		}
		
		return result;

	}
	
//////////////////////////////////////////////////////////////////////////
	
	@GetMapping("/rtest")
	public String rateLimitCB(@RequestParam String name) {
		
		String result;
		try {
			RestTemplate restTemplate = new RestTemplate();
			String uri = "http://localhost:8081/" + name;

			Callable<String> restrictedCall = RateLimiter
				    .decorateCallable(rateLimiterRegistry.rateLimiter(name,rlconfig), () -> restTemplate.getForObject(uri, String.class));
			
			Try<String> tryObject = Try.of(restrictedCall::call);
			if(tryObject.isFailure()) {
				if(tryObject.failed().get().getClass().isInstance(TimeoutException.class)) {
					throw new TimeoutException();
				}
			}	    
			result = tryObject.get();
//			String txt = result + " " + String.valueOf(pending_calls);
			return result;
			
		} catch( TimeoutException timeoutException) {
			String txt = "Timeout 2" + String.valueOf(pending_calls);
			return txt;
		} catch (CallNotPermittedException e) {
			String txt = "Open 2" + String.valueOf(pending_calls);
			return txt;
		} catch (Exception e) {
			String txt = "Fail 2" + String.valueOf(pending_calls);
			System.out.print(e.getMessage() + "\n");
			return txt;
		}

		
	}
	
	@GetMapping("/dotest")
	public String dotest(@RequestParam String name) {
		MyWebClient wc = new MyWebClient();
		Mono<String> res = wc.testCB(name);
		String a = res.toString();
		return "here";
	}
	
	@GetMapping("/rttest")
	public String dotest2(@RequestParam String name) {
		RestTemplate restTemplate = new RestTemplate();
		String uri = "http://localhost:8080/rtest?name=" + name;
		String response
		  = restTemplate.getForObject(uri, String.class);
		return response;
		
	}
	
	@GetMapping("/atest")
	public String atest(@RequestParam String name) { 
		return "not used";
		
/*		
		String result; 
		

		
		try {

			  	Supplier<String> decoratedGetString = Decorators.ofSupplier(getString)
						.withCircuitBreaker(circuitBreakerRegistry.circuitBreaker("name2"))
						.withRetry(retry)
						.decorate();	
				result = decoratedGetString.get();
				return result;
		} catch (Exception e) {
			return "bummer";
		}
*/
  	
	}
	
	private String timelimitedcall(String name) throws TimeoutException {
		String result = "";
//		try {
			String uri;
			RestTemplate restTemplate = new RestTemplate();
			if(name.equalsIgnoreCase("bill")) {
				uri = "http://localhost:8081/bill";	
			}
			else {
				uri = "http://localhost:8082/bob";					
			}
			
			if(name.equalsIgnoreCase("bill") && clear_delay == 1) {
				String result2 = restTemplate.getForObject("http://localhost:8081/reset", String.class);
				clear_delay = 0;
				return "=============Delay Cleared=========================";
			}
			
			Supplier<String> restCallSupplier =  () -> restTemplate.getForObject(uri, String.class);
			TimeLimiter timeLimiterFromReg = timeLimiterRegistry.timeLimiter(name,config);
			CompletableFuture<String> completableFuture;
			completableFuture = CompletableFuture.supplyAsync
					( () -> restTemplate.getForObject(uri, String.class), backendExecutor);
			Callable<String>  timelimitedCall = TimeLimiter.decorateFutureSupplier(timeLimiterFromReg, () -> completableFuture);
			Try<String> tryObject = Try.of(timelimitedCall::call);
			if(tryObject.isFailure()) {
				if(tryObject.failed().get().getClass().isInstance(TimeoutException.class)) {
					throw new TimeoutException();
				}
			}			
			result = tryObject.get();
			return result;
//		} finally {
//			return result;
//		}
/*

		} catch( TimeoutException timeoutException) {
			String txt = "Timeout 2" + String.valueOf(pending_calls);
			return txt;
		} catch (CallNotPermittedException e) {
			String txt = "Open 2" + String.valueOf(pending_calls);
			return txt;
		} catch (Exception e) {
			String txt = "Fail 2" + String.valueOf(pending_calls);
			System.out.print(e.getMessage() + "\n");
			return txt;
		}
*/
			

	}

	@GetMapping("/btest")
	public String testCBb(@RequestParam String name) {

		String result = "";
		try {
			String uri;
			RestTemplate restTemplate = new RestTemplate();
			if(name.equalsIgnoreCase("bill")) {
				uri = "http://localhost:8081/bill";	
			}
			else {
				uri = "http://localhost:8082/bob";					
			}
			
			if(name.equalsIgnoreCase("bill") && clear_delay == 1) {
				String result2 = restTemplate.getForObject("http://localhost:8081/reset", String.class);
				clear_delay = 0;
				return "=============Delay Cleared=========================";
			}
			
			Supplier<String> restCallSupplier =  () -> restTemplate.getForObject(uri, String.class);
			
			Supplier<String> checkedRestCall = Bulkhead
					.decorateSupplier(bulkhead, restCallSupplier);
			
			CompletableFuture<Supplier<String>> completableFuture;
			completableFuture = CompletableFuture.supplyAsync
					( () -> checkedRestCall, backendExecutor);
			
			Callable<Supplier<String>>  chainedCallable;
			TimeLimiter timeLimiterFromReg = timeLimiterRegistry.timeLimiter(name,config);
			Callable<Supplier<String>>  timelimitedCall = TimeLimiter.decorateFutureSupplier(timeLimiterFromReg, () -> completableFuture);
	//		chainedCallable = CircuitBreaker.decorateCallable(circuitBreakerRegistry.circuitBreaker(name,circuitBreakerConfig), timelimitedCall);
			
	//		Try<Supplier<String>> tryObject = Try.of(chainedCallable::call);
			Try<Supplier<String>> tryObject = Try.of(timelimitedCall::call);
			if(tryObject.isFailure()) {
				if(tryObject.failed().get().getClass().isInstance(TimeoutException.class)) {
					throw new TimeoutException();
				}
			}
			
			String test = tryObject.get().get();
			return test;
			
		} catch( TimeoutException timeoutException) {
			String txt = "Timeout 2" + String.valueOf(pending_calls);
			return txt;
		} catch (CallNotPermittedException e) {
			String txt = "Open 2" + String.valueOf(pending_calls);
			return txt;
		} catch (Exception e) {
			String txt = "Fail 2" + String.valueOf(pending_calls);
			System.out.print(e.getMessage() + "\n");
			return txt;
		}
		

		
		
	}
	
	@GetMapping("/test")
	public String testCB(@RequestParam String name) {
//	public String testCB(String name) {
		
		pending_calls++;
		
		String result = "";
		try {
			String uri;
			RestTemplate restTemplate = new RestTemplate();
			if(name.equalsIgnoreCase("bill")) {
				uri = "http://localhost:8081/bill";	
			}
			else {
				uri = "http://localhost:8082/bob";					
			}
			
			if(name.equalsIgnoreCase("bill") && clear_delay == 1) {
				String result2 = restTemplate.getForObject("http://localhost:8081/reset", String.class);
				clear_delay = 0;
				return "=============Delay Cleared=========================";
			}
			
			CompletableFuture<String> completableFuture;
//			Supplier<String> restCallSupplier =  () -> restTemplate.getForObject(uri, String.class);
			
//			CheckedFunction0<Supplier<String>> checkedRestCall = Bulkhead
//					  .decorateCheckedSupplier(bulkhead, () -> restCallSupplier);
			
			completableFuture = CompletableFuture.supplyAsync
						( () -> restTemplate.getForObject(uri, String.class), backendExecutor);

//			Callable<String> bulkheadFuture = Bulkhead
//					  .decorateCheckedSupplier(bulkhead, () -> completableFuture);	
				
			Callable<String>  chainedCallable;
			TimeLimiter timeLimiterFromReg = timeLimiterRegistry.timeLimiter(name,config);
			Callable<String>  timelimitedCall = TimeLimiter.decorateFutureSupplier(timeLimiterFromReg, () -> completableFuture);
			chainedCallable = CircuitBreaker.decorateCallable(circuitBreakerRegistry.circuitBreaker(name,circuitBreakerConfig), timelimitedCall);
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
			pending_calls = pending_calls -1;
			String txt = result + " " + String.valueOf(pending_calls);
			return result;
			
		} catch( TimeoutException timeoutException) {
			pending_calls = pending_calls -1;
			String txt = "Timeout " + String.valueOf(pending_calls);
			return txt;
		} catch (CallNotPermittedException e) {
			pending_calls = pending_calls -1;
			if(name.equalsIgnoreCase("bob")) {
				clear_delay = 1;
			}
			String txt = "Open " + String.valueOf(pending_calls);
			return txt;
		} catch (Exception e) {
			pending_calls = pending_calls -1;
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
