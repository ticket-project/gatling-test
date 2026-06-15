package com.ticket.gatling.console;

import java.util.concurrent.CountDownLatch;

public class ConsoleApplication {
    public static void main(final String[] args) throws Exception {
        final int port = Integer.parseInt(System.getProperty("consolePort", "9090"));
        final ReportRegistry reportRegistry = new ReportRegistry();
        final LoadTestService loadTestService = new LoadTestService(reportRegistry);
        final ConsoleServer consoleServer = new ConsoleServer(port, loadTestService, reportRegistry);
        consoleServer.start();
        System.out.println("Ticket Gatling Console: http://localhost:" + port);
        new CountDownLatch(1).await();
    }
}
