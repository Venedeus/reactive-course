package dev.shvetsov.reacitve.t1ex5;

import dev.shvetsov.reacitve.performance.PerformanceMeter;
import dev.shvetsov.reacitve.performance.PerformanceParallelSimulator;
import dev.shvetsov.reacitve.performance.PerformanceProxy;
import dev.shvetsov.reacitve.performance.PerformanceSimulator;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NonBlockingNIOServer {

  private static final int REQUEST_COUNT = 1_000;
  private static final int REQUEST_PROCESSING_TIME = 100;
  private static final String STOP_SIGNAL = "halt";
  private static final int BUFFER_SIZE = 1024;
  private static final String HOST = "127.0.0.1";
  private static final int PORT = 8080;

  public static void main(String[] args) throws Exception {
    final NonBlockingNIOServer nonBlockingNIOServer =
        PerformanceProxy.createProxy(new NonBlockingNIOServer());
    nonBlockingNIOServer.demo();
  }

  @PerformanceMeter(requestCount = REQUEST_COUNT, requestProcessingTime = REQUEST_PROCESSING_TIME)
  public void demo() throws Exception {
    Selector selector = Selector.open();
    ServerSocketChannel serverChannel = ServerSocketChannel.open();
    serverChannel.configureBlocking(false);
    serverChannel.socket().bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    log.info("NIO server has starter on port: {}", PORT);
    PerformanceSimulator simulator = new PerformanceParallelSimulator(HOST, PORT, REQUEST_COUNT,
        STOP_SIGNAL);
    Thread simulatorThread = new Thread(simulator::simulate);
    simulatorThread.start();;
    while (true) {
      // БЛОКИРУЮЩИЙ вызов: ждем, пока ХОТЯ БЫ ОДИН канал станет готов
      int readyChannels = selector.select();
      if (readyChannels == 0) {
        continue; // Нет готовых каналов - продолжаем ждать
      }
      // Получаем все ключи, у которых произошли события
      Set<SelectionKey> selectedKeys = selector.selectedKeys();
      Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
      while (keyIterator.hasNext()) {
        SelectionKey key = keyIterator.next();
        keyIterator.remove(); // Удаляем, чтобы не обработать повторно
        // Пропускаем отмененные ключи
        if (!key.isValid()) {
          continue;
        }
        try {
          // Обрабатываем событие в зависимости от его типа
          if (key.isAcceptable()) {
            handleAccept(key, selector);
          } else if (key.isReadable()) {
            handleRead(key);
          } else if (key.isWritable()) {
            handleWrite(key);
          }
        } catch (CancelledKeyException ex) {
          log.warn("The key was cancelled: {}", ex.getMessage());
          closeChannel(key);
        } catch (IOException ex) {
          log.error("IO error while processing key: {}", ex.getMessage(), ex);
          closeChannel(key);
        } catch (RuntimeException ex) {
          log.info("Stopping the server.");
          closeChannel(key);
          return;
        } catch (Exception ex) {
          log.error("Unexpected error: {}", ex.getMessage(), ex);
          closeChannel(key);
        }
      }
    }
  }

  private void handleAccept(SelectionKey key, Selector selector) throws IOException {
    // Получаем серверный канал из ключа
    ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
    // Принимаем соединение (мгновенно, т.к. мы знаем, что оно готово)
    SocketChannel clientChannel = serverChannel.accept();
    if (clientChannel == null) {
      return; // Защита от race condition
    }
    // Переводим клиентский канал в неблокирующий режим
    clientChannel.configureBlocking(false);
    // Регистрируем его с интересом к ЧТЕНИЮ
    SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
    // Создаем буфер для данных клиента и прикрепляем к ключу
    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    // Прикрепляем буфер к ключу, чтобы потом получить его в handleRead
    clientKey.attach(buffer);
    log.info("A new connection is added: {}", clientChannel.getRemoteAddress());
  }

  private void handleRead(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel) key.channel();
    ByteBuffer buffer = (ByteBuffer) key.attachment();  // Получаем буфер
    // Проверка на null (защита)
    if (buffer == null) {
      log.warn("Buffer is null, closing channel");
      closeChannel(key);
      return;
    }
    // Пытаемся прочитать данные в буфер
    int bytesRead = channel.read(buffer);
    // bytesRead == -1: клиент закрыл соединение
    if (bytesRead == -1) {
      log.info("Client closed connection: {}", channel.getRemoteAddress());
      closeChannel(key);
      return;
    }
    // bytesRead == 0: нет данных для чтения (неблокирующий режим)
    if (bytesRead == 0) {
      return;
    }
    // bytesRead > 0: есть данные
    if (bytesRead > 0) {
      log.info("Bytes read: {}", bytesRead);
      // ПОДГОТОВКА К ЧТЕНИЮ: переключаем буфер из режима записи в режим чтения
      buffer.flip();
      // Извлекаем данные из буфера
      byte[] data = new byte[buffer.remaining()];
      buffer.get(data);
      String request = new String(data);
      log.info("A new request received: {}", request);
      if(request.startsWith(STOP_SIGNAL)) {
        throw new RuntimeException("Server stopped");
      }
      // Формируем ответ
      String response = "HTTP/1.1 200 OK\r\n\r\nHello from NIO!";
      ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes());
      // Меняем интерес с ЧТЕНИЯ на ЗАПИСЬ
      key.interestOps(SelectionKey.OP_WRITE);
      key.attach(responseBuffer); // Прикрепляем буфер с ответом
    }
  }

  private void handleWrite(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel) key.channel();
    ByteBuffer buffer = (ByteBuffer) key.attachment();  // Буфер с ответом
    if (buffer == null) {
      log.warn("Buffer is null, closing channel");
      closeChannel(key);
      return;
    }
    try {
      // Отправляем данные клиенту
      channel.write(buffer);
      // Если все данные отправлены (буфер пуст)
      if (!buffer.hasRemaining()) {
        log.info("Response sent successfully to: {}", channel.getRemoteAddress());
        // Принудительное закрытие канала после отправки ответа
        closeChannel(key);
        // Возвращаемся к режиму ЧТЕНИЯ для следующего запроса
        // Применяется в случае управления соединением клиентом
        //        key.interestOps(SelectionKey.OP_READ);
        //        // Создаем НОВЫЙ буфер для следующих данных
        //        ByteBuffer newBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        //        key.attach(newBuffer);
      }
    } catch (IOException ex) {
      log.error("Error writing to channel: {}", ex.getMessage());
      closeChannel(key);
    }
  }

  private void closeChannel(SelectionKey key) {
    try {
      if (key.channel() instanceof SocketChannel) {
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.isOpen()) {
          log.info("Closing channel: {}", channel.getRemoteAddress());
          channel.close();
        }
      }
    } catch (IOException ex) {
      log.error("Error closing channel: {}", ex.getMessage());
    } finally {
      key.cancel();
    }
  }
}