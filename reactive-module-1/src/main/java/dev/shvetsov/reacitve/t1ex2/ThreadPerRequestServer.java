package dev.shvetsov.reacitve.t1ex2;

import dev.shvetsov.reacitve.performance.PerformanceMeter;
import dev.shvetsov.reacitve.performance.PerformanceParallelSimulator;
import dev.shvetsov.reacitve.performance.PerformanceProxy;
import dev.shvetsov.reacitve.performance.PerformanceSimulator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ThreadPerRequestServer {

  private static final int REQUEST_COUNT = 1_000;
  private static final int REQUEST_PROCESSING_TIME = 100;
  private static final String HOST = "localhost";
  private static final int PORT = 8081;
  private static final String STOP_SIGNAL = "halt";

  public static void main(String[] args) {
    final ThreadPerRequestServer threadPerRequestServer =
        PerformanceProxy.createProxy(new ThreadPerRequestServer());
    threadPerRequestServer.process();
  }

  @PerformanceMeter(requestCount = REQUEST_COUNT, requestProcessingTime = REQUEST_PROCESSING_TIME)
  public void process() {
    PerformanceSimulator simulator = new PerformanceParallelSimulator(HOST, PORT, REQUEST_COUNT,
        STOP_SIGNAL);
    Thread simulatorThread = new Thread(simulator::simulate);

    try (ServerSocket serverSocket = new ServerSocket(PORT)) {
      log.info("Server is started on port: {}", PORT);
      simulatorThread.start();
      while (true) {
        // Blocking code: it's waiting for any calls to 8080
        Socket clientSocket = serverSocket.accept();
        Thread thread = new Thread(() -> {
          log.info("A new connection from: {}", clientSocket.getRemoteSocketAddress());
          try {
            handleRequest(clientSocket);
          } catch (Exception ex) {
            try {
              log.info("Stopping the server");
              serverSocket.close();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        });
        thread.start();
      }
    } catch (Exception ex) {
      log.error(ex.getMessage());
      try {
        simulatorThread.join();
      } catch (InterruptedException e) {
        log.error(ex.getMessage());
      }
    }
  }

  private void handleRequest(Socket clientSocket) throws Exception {
    try (clientSocket;
        BufferedReader in = new BufferedReader(
            new InputStreamReader(clientSocket.getInputStream()));
        PrintWriter out = new PrintWriter(clientSocket.getOutputStream())
    ) {
      String requestLine = in.readLine();
      if (STOP_SIGNAL.equals(requestLine)) {
        throw new Exception("Stopping the server");
      }
      String response = processRequest(requestLine);
      out.println("HTTP:/1.1 200 OK");
      out.println(response);
      out.flush();
    }
  }

  private static String processRequest(String requestLine) {
    try {
      Thread.sleep(REQUEST_PROCESSING_TIME);
    } catch (InterruptedException ex) {
      throw new RuntimeException(ex);
    }
    return requestLine;
  }
}