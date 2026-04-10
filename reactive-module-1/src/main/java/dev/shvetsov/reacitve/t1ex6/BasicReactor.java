package dev.shvetsov.reacitve.t1ex6;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BasicReactor implements Runnable {

  private final Selector selector;
  private final ServerSocketChannel serverSocket;
  private final Map<SocketChannel, Handler> handlers = new ConcurrentHashMap<>();
  private final AtomicLong connectionCount = new AtomicLong();
  private volatile boolean isRunning = true;

  private final AtomicLong totalBytesRead = new AtomicLong();
  private final AtomicLong totalBytesWritten = new AtomicLong();
  private final AtomicLong totalEventsProcessed = new AtomicLong();

  public BasicReactor(int port) throws IOException {
    this.selector = Selector.open();
    this.serverSocket = ServerSocketChannel.open();
    this.serverSocket.socket().bind(new InetSocketAddress(port));
    this.serverSocket.configureBlocking(false);
    SelectionKey key = serverSocket.register(selector, SelectionKey.OP_ACCEPT);
    key.attach(new Acceptor());
    log.info("Reactor is started on port: {}", port);
  }

  public static void main(String[] args) throws IOException {
    BasicReactor reactor = new BasicReactor(8080);
    Thread reactorThread = new Thread(reactor, "Reactor-Main");
    reactorThread.start();
    Thread statsThread = new Thread(() -> {
      while (true) {
        try {
          Thread.sleep(10000);
          reactor.printStats();
        } catch (InterruptedException e) {
          break;
        }
      }
    });
    statsThread.setDaemon(true);
    statsThread.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      log.info("Stopping the reactor...");
      reactor.stop();
      try {
        reactorThread.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }));
  }

  @Override
  public void run() {
    try {
      while (isRunning && !Thread.currentThread().isInterrupted()) {
        int readyCount = selector.select();
        if (readyCount == 0) {
          continue;
        }
        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        Iterator<SelectionKey> iterator = selectedKeys.iterator();
        while (iterator.hasNext()) {
          SelectionKey key = iterator.next();
          iterator.remove();
          try {
            dispatch(key);
            totalEventsProcessed.incrementAndGet();
          } catch (Exception e) {
            log.error("An error occurred while an event processing: {}", e.getMessage());
            handleError(key, e);
          }
        }
      }
    } catch (IOException e) {
      log.error("Reactor error: {}", e.getMessage());
    }
  }

  private void dispatch(SelectionKey key) {
    Runnable handler = (Runnable) key.attachment();
    if (handler != null) {
      handler.run();
    }
  }

  private void handleError(SelectionKey key, Exception e) {
    try {
      if (key.channel() instanceof SocketChannel) {
        SocketChannel channel = (SocketChannel) key.channel();
        log.error("Closing the connection: {} because of the error: {}", channel.getRemoteAddress(),
            e.getMessage());
        handlers.remove(channel);
      }
      key.cancel();
      key.channel().close();
    } catch (IOException ex) {
      log.error("An error occurred while closing the channel: {}", ex.getMessage());
    }
  }

  private void stop() {
    isRunning = false;
    selector.wakeup();
    try {
      selector.close();
      serverSocket.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private class Acceptor implements Runnable {

    @Override
    public void run() {
      try {
        SocketChannel clientChannel = serverSocket.accept();
        if (clientChannel != null) {
          long connectionId = connectionCount.incrementAndGet();
          log.info("A new connection is established: {} {}", connectionId,
              clientChannel.getRemoteAddress());
          clientChannel.configureBlocking(false);
          clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
          clientChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
          clientChannel.setOption(StandardSocketOptions.SO_RCVBUF, 65536);
          clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, 65536);
          Handler handler = new Handler(clientChannel, connectionId);
          handlers.put(clientChannel, handler);
          SelectionKey key = clientChannel.register(selector, SelectionKey.OP_READ);
          key.attach(handler);
        }
      } catch (IOException e) {
        log.error("An error occurred during setting up the connection: {}", e.getMessage());
      }
    }
  }

  private class Handler implements Runnable {

    private final SocketChannel channel;
    private final long connectionId;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;
    private final StringBuilder messageBuilder;
    private State state;
    private String currentMessage;

    enum State {
      READING,
      PROCESSING,
      WRITING
    }

    Handler(SocketChannel channel, long connectionId) {
      this.channel = channel;
      this.connectionId = connectionId;
      this.readBuffer = ByteBuffer.allocate(8192);
      this.writeBuffer = ByteBuffer.allocate(8192);
      this.messageBuilder = new StringBuilder();
      this.state = State.READING;
    }

    @Override
    public void run() {
      try {
        switch (state) {
          case READING:
            read();
            break;
          case PROCESSING:
            process();
            break;
          case WRITING:
            write();
            break;
        }
      } catch (IOException e) {
        e.printStackTrace();
        log.error("An error occurred in the handler #{}, {}", connectionId, e.getMessage());
        close();
      }
    }

    private void read() throws IOException {
      readBuffer.clear();
      int bytesRead = channel.read(readBuffer);
      if (bytesRead == -1) {
        log.info("Client #{} closed the connection", connectionId);
        close();
        return;
      }
      if (bytesRead > 0) {
        totalBytesRead.addAndGet(bytesRead);
        readBuffer.flip();
        byte[] data = new byte[readBuffer.remaining()];
        readBuffer.get(data);
        String received = new String(data);
        log.info("Client #{}: got {} bytes: {}", connectionId, bytesRead, received.trim());
        messageBuilder.append(received);
        String fullMessage = messageBuilder.toString();
        if (fullMessage.contains("\n")) {
          String[] parts = fullMessage.split("\n", 2);
          currentMessage = parts[0];
          messageBuilder.setLength(0);
          if (parts.length > 1) {
            messageBuilder.append(parts[1]);
          }
          state = State.PROCESSING;
          SelectionKey key = channel.keyFor(selector);
          if (key != null) {
            key.interestOps(0);
            process();
          }
        }
      }
    }

    private void process() {
      log.info("Client #{}: processing the message: {}", connectionId, currentMessage);
      String response = processBusinessLogic(currentMessage);
      writeBuffer.clear();
      writeBuffer.put((response + "\n").getBytes());
      writeBuffer.flip();
      state = State.WRITING;
      try {
        SelectionKey key = channel.keyFor(selector);
        if (key != null) {
          key.interestOps(SelectionKey.OP_WRITE);
          key.attach(this);
          selector.wakeup();
        }
      } catch (Exception e) {
        log.error("An error occurred while turning to the write mode: {}", e.getMessage());
      }
    }

    private void write() throws IOException {
      int bytesWritten = channel.write(writeBuffer);
      totalBytesWritten.addAndGet(bytesWritten);
      log.info("Client #{}: sent {} bytes", connectionId, bytesWritten);
      if (!writeBuffer.hasRemaining()) {
        state = State.READING;
        SelectionKey key = channel.keyFor(selector);
        if (key != null && key.isValid()) {
          key.interestOps(SelectionKey.OP_READ);
          key.attach(this);
          selector.wakeup();
        }
      }
    }

    private String processBusinessLogic(String request) {
      if (request.startsWith("ECHO:")) {
        return "ECHO: " + request.substring(5);
      } else if (request.equals("STATS")) {
        return String.format("STATS: bytes_read=%d, bytes_written=%d, events=%d",
            totalBytesRead.get(), totalBytesWritten.get(), totalEventsProcessed.get());
      } else if (request.equals("CONNECTION_ID")) {
        return "ID: " + connectionId;
      } else {
        return "PROCESSED: " + request;
      }
    }

    private void close() {
      try {
        handlers.remove(channel);
        channel.close();
        log.info("The connection #{} is closed. Active connections remain: {}", connectionId,
            handlers.size());
      } catch (IOException e) {
        log.error("An error occurred while closing the connection : {}", e.getMessage());
      }
    }
  }

  public void printStats() {
    log.info("=== REACTOR STATS ===");
    log.info("Active connections: {}", handlers.size());
    log.info("Total read bytes: {}", totalBytesRead.get());
    log.info("Total written bytes: {}", totalBytesWritten.get());
    log.info("Events processed: {}", totalEventsProcessed.get());
    log.info("=====================");
  }
}
