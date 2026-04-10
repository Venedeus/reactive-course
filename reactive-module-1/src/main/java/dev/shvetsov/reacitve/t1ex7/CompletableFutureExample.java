package dev.shvetsov.reacitve.t1ex7;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CompletableFutureExample {

  public static void main(String[] args) throws ExecutionException, InterruptedException {
    CompletableFuture<String> future = supplyAsync();
    future.thenAccept(log::info);

    log.info("Completed future: {}", completedFuture().get());

    runAsync();

    manualCompletion();

    log.info("thenAsyncExample: {}", thenApplyExample().get());

    thenRunExample();
    log.info("thenRunExample: {}", thenRunExample().get());

    log.info("thenComposeExample: {}", thenComposeExample().get());

    log.info("Processing the order: {}", processOrderThenCompose("order").get());

    log.info("Main thread logging");
    future.join();
  }

  public static CompletableFuture<String> completedFuture() {
    return CompletableFuture.completedFuture("The result");
  }

  public static CompletableFuture<String> supplyAsync() {
    return CompletableFuture.supplyAsync(() -> {
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return "Async result";
    });
  }

  public static CompletableFuture<Void> runAsync() {
    return CompletableFuture.runAsync(() -> log.info("Async processing without results"));
  }

  public static CompletableFuture<String> manualCompletion() {
    CompletableFuture<String> future = new CompletableFuture<>();
    new Thread(() -> {
      try {
        Thread.sleep(1_000);
        future.complete("Manual completion");
      } catch (InterruptedException e) {
        future.completeExceptionally(e);
      }
    }).start();
    return future;
  }

  public static CompletableFuture<String> thenApplyExample() {
    return CompletableFuture.supplyAsync(() -> "hello")
        .thenApply(String::toUpperCase)
        .thenApply(s -> s + " WORLD")
        .thenApply(String::length)
        .thenApply(Object::toString);
  }

  public static CompletableFuture<Void> thenRunExample() {
    return CompletableFuture.supplyAsync(() -> "data")
        .thenRun(() -> log.info("The processing is done")
        );
  }

  public static CompletableFuture<String> thenComposeExample() {
    return CompletableFuture.supplyAsync(() -> "user_id")
        .thenCompose(CompletableFutureExample::fetchUserDetails);
  }

  private static CompletableFuture<String> fetchUserDetails(String userId) {
    return CompletableFuture.supplyAsync(() -> "Details for: " + userId);
  }

  public static CompletableFuture<String> processOrderThenCompose(String request) {
    return CompletableFuture.supplyAsync(
            () -> "\n" + Thread.currentThread().getName() + ": Validating: " + request)
        .thenCompose(result ->
            CompletableFuture.runAsync(() -> log.info("Step 1: Validation")).thenCompose(v ->
                CompletableFuture.supplyAsync(
                    () -> "\n" + Thread.currentThread().getName() + ": Validating the order: "
                        + result))
        )
        .thenCompose(result ->
            CompletableFuture.runAsync(() -> log.info("Step 2: Enrichment")).thenCompose(v ->
                CompletableFuture.supplyAsync(
                    () -> "\n" + Thread.currentThread().getName() + ": Enriching the order: "
                        + result)))
        .thenCompose(result ->
            CompletableFuture.runAsync(() -> log.info("Step 3: Saving")).thenCompose(v ->
                CompletableFuture.supplyAsync(
                    () -> "\n" + Thread.currentThread().getName() + ": Saving the order: "
                        + result)))
        .thenApply(result -> "\n" + Thread.currentThread().getName() + ": Final result: " + result)
        .exceptionally(throwable -> "An error occurred: " + throwable.getMessage());
  }
}
