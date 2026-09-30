package com.ticket.gatling.console;

import java.util.concurrent.CountDownLatch;

public class ConsoleApplication {
    public static void main(final String[] args) throws Exception {
        final int port = Integer.parseInt(System.getProperty("consolePort", "9090"));
        final ConsoleServer consoleServer = new ConsoleServer(port, new LoadTestService());
        consoleServer.start();
        System.out.println("Ticket Gatling Console: http://localhost:" + port);
        new CountDownLatch(1).await();
    }
}
