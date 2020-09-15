package com.amazon.kinesis.video.canary.consumer;

import com.amazonaws.kinesisvideo.parser.examples.ContinuousGetMediaWorker;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

@Slf4j
public class Terminate extends ProducerSdkCanaryConsumer implements Runnable {
   public void run() {
   	Integer canaryRunTime = Integer.parseInt(System.getenv("CANARY_DURATION_IN_SECONDS"));
      System.out.println("Run time: " + canaryRunTime);
      try 
		{
   			Thread.sleep(canaryRunTime * 1000);
		} 
		catch(InterruptedException e)
		{
		     // this part is executed when an exception (in this example InterruptedException) occurs
		}
      System.out.println("Running thread");
   	System.exit(0);
   }
}