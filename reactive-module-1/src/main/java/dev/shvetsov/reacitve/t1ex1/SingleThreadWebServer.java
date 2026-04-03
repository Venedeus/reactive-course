package dev.shvetsov.reacitve.t1ex1;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SingleThreadWebServer {

  public static void main(String[] args) {
    int port = 8080;

    try (ServerSocket serverSocket = new ServerSocket(port)) {
      log.info("Server is started on port: {}", port);
      while (true) {
        // Blocking code: it's waiting for any calls to 8080
        Socket clientSocket = serverSocket.accept();
        log.info("A new connection from: {}", clientSocket.getRemoteSocketAddress());
        handleRequest(clientSocket);
      }
    } catch (IOException ex) {
      log.error(ex.getMessage());
    }
  }

  private static void handleRequest(Socket clientSocket) {
    try (clientSocket;
        BufferedReader in = new BufferedReader(
            new InputStreamReader(clientSocket.getInputStream()));
        PrintWriter out = new PrintWriter(clientSocket.getOutputStream())
    ) {
      String requestLine = in.readLine();
      if (requestLine == null) {
        return;
      }
      log.info("The request: {}", requestLine);
      String response = processRequest(requestLine);
      Thread.sleep(100);
      out.println("HTTP:/1.1 200 OK");
      out.println("Content-Type: text/plain");
      out.println("Content-Length: " + response.length());
      out.println();
      out.println(response);
      out.flush();
    } catch (IOException | InterruptedException ex) {
      log.error(ex.getMessage());
    }
  }

  private static String processRequest(String requestLine) {
    return "Hello from single-threaded server!";
  }
}
