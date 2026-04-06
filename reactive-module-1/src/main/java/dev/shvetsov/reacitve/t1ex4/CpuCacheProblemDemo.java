package dev.shvetsov.reacitve.t1ex4;

import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CpuCacheProblemDemo {

  private final int threadCount = 4;
  private final int iterations = 100_000_000;

  @Getter
  private static class PaddedCounter {
    private volatile long value = 0;
    private long p1, p2, p3, p4, p5, p6, p7; // Paddings to complete a CPU-cache line
  }

  @Getter
  private static class SimpleCounter {
    private volatile long value = 0;
  }

  public static void main(String[] args) {
    CpuCacheProblemDemo demo = new CpuCacheProblemDemo();
    demo.demoFalseSharing();
    demo.demoCompleteCPUCacheLines();
  }

  public void demoFalseSharing() {
    SimpleCounter[] simpleCounters = new SimpleCounter[threadCount];
    for (int i = 0; i < threadCount; i++) {
      simpleCounters[i] = new SimpleCounter();
    }

    long startTime = System.nanoTime();
    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      final int idx = i;
      threads[i] = new Thread(() -> {
        for (int j = 0; j < iterations; j++) {
          simpleCounters[idx].value++;
        }
      });
      threads[i].start();
    }

    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    long endTime = System.nanoTime();
    log.info("Time with false sharing processing: {}", (endTime - startTime) / 1_000_000 + " ms");
    log.info("{}", Arrays.stream(simpleCounters)
        .map(SimpleCounter::getValue)
        .map(String::valueOf)
        .collect(Collectors.joining(" ")));
  }

  private void demoCompleteCPUCacheLines() {
    PaddedCounter[] paddedCounters = new PaddedCounter[threadCount];
    for (int i = 0; i < threadCount; i++) {
      paddedCounters[i] = new PaddedCounter();
    }

    long startTime = System.nanoTime();
    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      final int idx = i;
      threads[i] = new Thread(() -> {
        for (int j = 0; j < iterations; j++) {
          paddedCounters[idx].value++;
        }
      });
      threads[i].start();
    }

    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    long endTime = System.nanoTime();
    log.info("Time with complete CPU-cache lines processing: {}", (endTime - startTime) / 1_000_000 + " ms");
    log.info("{}", Arrays.stream(paddedCounters)
        .map(PaddedCounter::getValue)
        .map(String::valueOf)
        .collect(Collectors.joining(" ")));
  }
}