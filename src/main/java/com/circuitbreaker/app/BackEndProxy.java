package com.circuitbreaker.app;

import java.util.EmptyStackException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BackEndProxy {

	private static int count = 1; 
	private static Boolean Adjust = true;
	
	public String getResult2(String name) {
		return "Howdy doody " + name;
	}
	
	public void clearcount() {
		count = 1;
	}
	

	public String getResult(String name) {
		

		if (name.equalsIgnoreCase("bob")) {
//			System.out.print("its bob calling");
			return "Howdy " + name;
		}
			
		try {
			TimeUnit.MILLISECONDS.sleep(count);
		}
		catch (Exception e) {
			throw new EmptyStackException();
		}
//		if (count < 10) {
//			count++;
//		}
		if(Adjust ) {
			count++;
		}
		if(count > 400) {
			count = 1;
			Adjust = false;
		}
		return "Hello " + name;
	}

	

}
