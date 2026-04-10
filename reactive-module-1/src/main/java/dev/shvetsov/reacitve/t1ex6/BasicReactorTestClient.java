package dev.shvetsov.reacitve.t1ex6;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class BasicReactorTestClient {

    private static final String HOST = "localhost";
    private static final int PORT = 8080;
    private static final int NUMBER_OF_CLIENTS = 1000;
    private static final int REQUESTS_PER_CLIENT = 10;
    
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger successfulRequests = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);

    public static void main(String[] args) throws InterruptedException {
        BasicReactorTestClient testClient = new BasicReactorTestClient();
        testClient.runLoadTest();
    }

    public void runLoadTest() throws InterruptedException {
        log.info("Starting load test with {} parallel clients", NUMBER_OF_CLIENTS);
        log.info("Each client will send {} requests", REQUESTS_PER_CLIENT);
        log.info("Total requests: {}", NUMBER_OF_CLIENTS * REQUESTS_PER_CLIENT);
        
        ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_CLIENTS);
        CountDownLatch latch = new CountDownLatch(NUMBER_OF_CLIENTS);

        long startTime = System.currentTimeMillis();
        
        // Запускаем клиентов
        for (int i = 0; i < NUMBER_OF_CLIENTS; i++) {
            final int clientId = i;
            executorService.submit(() -> {
                try {
                    runClient(clientId, latch);
                } catch (Exception e) {
                    log.error("Client {} failed: {}", clientId, e.getMessage());
                    latch.countDown();
                }
            });
        }
        
        // Ждем завершения всех клиентов
        latch.await(60, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);

        // Выводим статистику
        printStats(duration);
    }
    
    private void runClient(int clientId, CountDownLatch latch) {
        activeConnections.incrementAndGet();
        SocketChannel channel = null;
        
        try {
            // Устанавливаем соединение
            channel = SocketChannel.open();
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(HOST, PORT));
            
            log.debug("Client {} connected successfully", clientId);
            
            // Отправляем запросы
            for (int i = 0; i < REQUESTS_PER_CLIENT; i++) {
                long requestStartTime = System.nanoTime();
                
                try {
                    // Выбираем тип запроса
                    String request = getRequestType(i, clientId);
                    
                    // Отправляем запрос
                    String response = sendRequest(channel, request);
                    
                    // Проверяем ответ
                    if (validateResponse(request, response)) {
                        successfulRequests.incrementAndGet();
                        long responseTime = System.nanoTime() - requestStartTime;
                        totalResponseTime.addAndGet(responseTime);
                        
                        log.debug("Client {} request {} succeeded: {} -> {}", 
                                 clientId, i, request.trim(), response.trim());
                    } else {
                        failedRequests.incrementAndGet();
                        log.warn("Client {} request {} failed: {} -> {}", 
                                clientId, i, request.trim(), response.trim());
                    }
                    
                } catch (IOException e) {
                    failedRequests.incrementAndGet();
                    log.error("Client {} request {} error: {}", clientId, i, e.getMessage());
                }
                
                // Небольшая задержка между запросами
                Thread.sleep(10);
            }
            
        } catch (Exception e) {
            log.error("Client {} error: {}", clientId, e.getMessage());
            failedRequests.addAndGet(REQUESTS_PER_CLIENT);
        } finally {
            try {
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException e) {
                log.error("Error closing channel for client {}: {}", clientId, e.getMessage());
            }
            activeConnections.decrementAndGet();
            latch.countDown();
        }
    }
    
    private String sendRequest(SocketChannel channel, String request) throws IOException {
        // Отправляем запрос
        ByteBuffer writeBuffer = ByteBuffer.wrap((request + "\n").getBytes());
        while (writeBuffer.hasRemaining()) {
            channel.write(writeBuffer);
        }
        
        // Читаем ответ
        ByteBuffer readBuffer = ByteBuffer.allocate(8192);
        StringBuilder responseBuilder = new StringBuilder();
        
        boolean hasMore = true;
        while (hasMore) {
            readBuffer.clear();
            int bytesRead = channel.read(readBuffer);
            
            if (bytesRead == -1) {
                throw new IOException("Connection closed by server");
            }
            
            if (bytesRead > 0) {
                readBuffer.flip();
                byte[] data = new byte[readBuffer.remaining()];
                readBuffer.get(data);
                String received = new String(data);
                responseBuilder.append(received);
                
                // Проверяем, получили ли мы полный ответ (с \n)
                if (received.contains("\n")) {
                    hasMore = false;
                }
            }
        }
        
        return responseBuilder.toString();
    }
    
    private String getRequestType(int requestIndex, int clientId) {
        int type = requestIndex % 4;
        switch (type) {
            case 0:
                return "ECHO:Hello from client " + clientId + " request " + requestIndex;
            case 1:
                return "STATS";
            case 2:
                return "CONNECTION_ID";
            default:
                return "Test message " + requestIndex + " from client " + clientId;
        }
    }
    
    private boolean validateResponse(String request, String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        
        String trimmedResponse = response.trim();
        
        if (request.startsWith("ECHO:")) {
            return trimmedResponse.startsWith("ECHO:");
        } else if (request.equals("STATS")) {
            return trimmedResponse.startsWith("STATS:");
        } else if (request.equals("CONNECTION_ID")) {
            return trimmedResponse.startsWith("ID:");
        } else {
            return trimmedResponse.startsWith("PROCESSED:");
        }
    }

    private void printStats(long durationMs) {
        int totalRequests = successfulRequests.get() + failedRequests.get();
        double avgResponseTimeMs = totalResponseTime.get() > 0 ?
                (totalResponseTime.get() / 1_000_000.0) / totalRequests : 0;
        double throughput = (totalRequests * 1000.0) / durationMs;

        log.info("General Statistics:");
        log.info("Total requests: {}", totalRequests);
        log.info("Total time: {} ms", durationMs);
        log.info("Average response time: {} ms", avgResponseTimeMs);
        log.info("Throughput: {} RPS", throughput);
    }

    // Дополнительный метод для постепенного увеличения нагрузки
    public void runGradualLoadTest() throws InterruptedException {
        int[] clientCounts = {10, 50, 100, 250, 500, 750, 1000};

        log.info("Starting gradual load test");

        for (int clients : clientCounts) {
            log.info("\n--- Testing with {} parallel clients ---", clients);

            // Сбрасываем счетчики
            successfulRequests.set(0);
            failedRequests.set(0);
            totalResponseTime.set(0);

            ExecutorService executorService = Executors.newFixedThreadPool(clients);
            CountDownLatch latch = new CountDownLatch(clients);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < clients; i++) {
                final int clientId = i;
                executorService.submit(() -> {
                    try {
                        runClient(clientId, latch);
                    } catch (Exception e) {
                        log.error("Client {} failed: {}", clientId, e.getMessage());
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - startTime;

            int totalRequests = successfulRequests.get() + failedRequests.get();
            double successRate = (successfulRequests.get() * 100.0) / totalRequests;
            double throughput = (totalRequests * 1000.0) / duration;

            log.info("Results for {} clients:", clients);
            log.info("  Success rate: {:.2f}%", successRate);
            log.info("  Throughput: {:.2f} req/sec", throughput);

            executorService.shutdown();
            executorService.awaitTermination(5, TimeUnit.SECONDS);

            // Пауза между тестами
            Thread.sleep(5000);
        }
    }

    // Клиент для проверки конкретного сценария
    public static class SimpleTestClient {
        public static void main(String[] args) throws IOException {
            log.info("Running simple test client...");

            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(HOST, PORT));

            String[] testRequests = {
                "ECHO:Hello Reactor!",
                "STATS",
                "CONNECTION_ID",
                "Test message"
            };

            for (String request : testRequests) {
                log.info("Sending: {}", request);

                ByteBuffer writeBuffer = ByteBuffer.wrap((request + "\n").getBytes());
                while (writeBuffer.hasRemaining()) {
                    channel.write(writeBuffer);
                }

                ByteBuffer readBuffer = ByteBuffer.allocate(8192);
                StringBuilder response = new StringBuilder();

                while (true) {
                    readBuffer.clear();
                    int bytesRead = channel.read(readBuffer);
                    if (bytesRead > 0) {
                        readBuffer.flip();
                        byte[] data = new byte[readBuffer.remaining()];
                        readBuffer.get(data);
                        response.append(new String(data));
                        if (new String(data).contains("\n")) {
                            break;
                        }
                    }
                }

                log.info("Response: {}", response.toString().trim());
              try {
                Thread.sleep(1000);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            }

            channel.close();
            log.info("Simple test completed");
        }
    }
}