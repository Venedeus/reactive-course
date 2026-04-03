package dev.shvetsov.reacitve.performance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class PerformanceSequentialSimulator implements PerformanceSimulator {

  private final String host;
  private final int port;
  private final int requestCount;
  private final String stopSignal;

  public void simulate() {
    for (int i = 0; i <= requestCount; i++) {
      long opsTime = System.currentTimeMillis();
      simulateRequestProcessing(i);
      log.info("The request #{} has been processed: {} ms", i,
          System.currentTimeMillis() - opsTime);
    }
  }

  private void simulateRequestProcessing(int requestId) {
    try (Socket socket = new Socket(host, port);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
    ) {
      if (requestId == requestCount) {
        log.info("Halting the server: [host:{}, port:{}]", host, port);
        out.println(stopSignal);
      }
      out.println("Some request payload");
      log.info("The response #{} - response:\n{}", requestId,
          in.lines().collect(Collectors.joining("\n")));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
