package dev.shvetsov.reacitve.t1ex1;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PerformanceSimulator {

  public static void main(String[] args) {
    simulateSingleThreadedServer(100);
  }

  public static void simulateSingleThreadedServer(int numberOfRequests) {
    long startTime = System.currentTimeMillis();

    for (int i = 0; i < numberOfRequests; i++) {
      long opsTime = System.currentTimeMillis();
      simulateRequestProcessing(i);
      log.info("The request #{} has been processed: {} ms", i, System.currentTimeMillis() - opsTime);
    }

    long totalTime = System.currentTimeMillis() - startTime;
    log.info("Requests amount: {}", numberOfRequests);
    log.info("Total time: {} ms", totalTime);
    log.info("Average time per a request: {} ms", totalTime / numberOfRequests);
    log.info("Throughput: {} RPS", (numberOfRequests * 1000.0) / totalTime);
  }

  private static void simulateRequestProcessing(int requestId) {
    try (Socket socket = new Socket("localhost", 8080);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
    ) {
      out.println("The request #%d: A payload from PerformanceSimulator".formatted(requestId));
      log.info("The response #{} - response:\n{}", requestId,
          in.lines().collect(Collectors.joining("\n")));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
