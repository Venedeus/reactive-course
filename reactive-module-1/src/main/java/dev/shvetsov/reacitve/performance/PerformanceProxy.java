package dev.shvetsov.reacitve.performance;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

@Slf4j
public class PerformanceProxy {

  @SuppressWarnings("unchecked")
  public static <T> T createProxy(T target) {
    try {
      return (T) new ByteBuddy()
          .subclass(target.getClass())
          .method(ElementMatchers.isAnnotatedWith(PerformanceMeter.class))
          .intercept(MethodDelegation.to(new PerformanceInterceptor(target)))
          .make()
          .load(target.getClass().getClassLoader())
          .getLoaded()
          .getDeclaredConstructor()
          .newInstance();
    } catch (Exception ex) {
      throw new RuntimeException("Failed to create proxy");
    }
  }

  public static class PerformanceInterceptor {

    private final Object target;

    public PerformanceInterceptor(Object target) {
      this.target = target;
    }

    @RuntimeType
    public Object intercept(@This Object proxy, @Origin Method method, @AllArguments Object[] args,
        @SuperCall Callable<?> callable) throws Exception {
      if (method.isAnnotationPresent(PerformanceMeter.class)) {
        PerformanceMeter annotation = method.getAnnotation(PerformanceMeter.class);
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(
            OperatingSystemMXBean.class);
        ThreadMXBean threadMXBean = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);

        int requestsCount = annotation.requestCount();
        int requestProcessingTime = annotation.requestProcessingTime();
        long startTime = System.currentTimeMillis();

        try {
          return callable.call();
        } finally {
          long totalTime = System.currentTimeMillis() - startTime;
          long totalIOTime = totalTime - (long) requestProcessingTime * requestsCount;
          log.info("----- Performance summary -----");
          log.info("Requests count: {}", requestsCount);
          log.info("Total processing time [expected sequentially/average]: {} ms / {} ms",
              requestProcessingTime * requestsCount, totalTime);
          log.info("Processing time per a request [expected/calculated average]: {} ms / {} ms",
              requestProcessingTime, totalTime / requestsCount);
          log.info("Throughput: {} RPS", "%.2f".formatted((requestsCount * 1000.0) / totalTime));
          log.info("JVM Process CPU load: {} %",
              "%.2f".formatted(osBean.getProcessCpuLoad() * 100));
          log.info("JVM Process CPU time: {} ms",
              "%.2f".formatted(osBean.getProcessCpuTime() / 1e6));
          log.info("Main thread allocated memory: {} Bytes", "%.2f".formatted(
              threadMXBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) / 1024.0));
        }
      }
      return callable.call();
    }
  }
}
