package dev.shvetsov.reacitve.performance;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class PerformanceParallelSimulator implements PerformanceSimulator {

  private final String host;
  private final int port;
  private final int requestCount;
  private final String stopSignal;

  public void simulate() {
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < requestCount; i++) {
      int[] requestNumber = {i};
      Thread thread = new Thread(() -> {
        long opsTime = System.currentTimeMillis();
        simulateRequestProcessing(requestNumber[0]);
        log.info("The request #{} has been processed: {} ms", requestNumber[0],
            System.currentTimeMillis() - opsTime);
      });
      threads.add(thread);
      thread.start();
    }
    threads.forEach(t -> {
      try {
        t.join();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    });
    halt();
  }

  private void simulateRequestProcessing(int requestId) {
    while (true) {
      try (Socket socket = new Socket(host, port);
          PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
          BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
        out.println("Some request payload");
        log.info("The response #{} - response:\n{}", requestId,
            in.lines().collect(Collectors.joining("\n")));
        break;
      } catch (Exception _) {
        log.error("Retrying to connect for requestId: {}", requestId);
      }
    }
  }

  private void halt() {
    try (Socket socket = new Socket(host, port);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
      log.info("Halting the server: [host:{}, port:{}]", host, port);
      out.println(stopSignal);
    } catch (Exception _) {
    }
  }
}
