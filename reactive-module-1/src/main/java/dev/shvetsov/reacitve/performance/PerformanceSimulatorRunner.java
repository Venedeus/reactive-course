package dev.shvetsov.reacitve.performance;

public class PerformanceSimulatorRunner {

  public static void main(String[] args) {
    PerformanceSimulator simulator =
        new PerformanceParallelSimulator("localhost", 8080, 1000, "halt");
    simulator.simulate();
  }
}
