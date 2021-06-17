/*******************************************************************************
* Copyright (c) 2017 Microsoft Corporation and others.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*     Microsoft Corporation - initial API and implementation
*******************************************************************************/

package com.microsoft.java.debug.plugin.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.microsoft.java.debug.core.Configuration;
import com.microsoft.java.debug.core.adapter.ProtocolServer;

public class JavaDebugServer implements IDebugServer {
    private static final Logger logger = Logger.getLogger(Configuration.LOGGER_NAME);
    private static JavaDebugServer singletonInstance;

    private ServerSocket serverSocket = null;
    private boolean isStarted = false;
    private ExecutorService executor = null;

    private JavaDebugServer() {
        int port = 0;
        InetAddress bindAddr = null;
        String serverAddress = System.getProperty("com.microsoft.java.debug.serverAddress");
        if (serverAddress != null) {
            int portIndex = serverAddress.lastIndexOf(':');
            if (portIndex == -1) {
                logger.log(Level.SEVERE, String.format("Malformed server address \"%s\": missing port", serverAddress));
                return;
            }
            try {
                port = Integer.parseInt(serverAddress.substring(portIndex + 1));
            } catch (NumberFormatException e) {
                logger.log(Level.SEVERE, String.format("Malformed server address \"%s\": %s", serverAddress, e.toString()), e);
                return;
            }

            if (portIndex > 0) {
                try {
                    bindAddr = InetAddress.getByName(serverAddress.substring(0, portIndex));
                } catch (UnknownHostException e) {
                    logger.log(Level.SEVERE, String.format("Invalid server address \"%s\": %s", serverAddress, e.toString()), e);
                    return;
                }
            }
        }
        try {
            this.serverSocket = new ServerSocket(port, 1, bindAddr);
        } catch (IOException e) {
            logger.log(Level.SEVERE, String.format("Failed to create Java Debug Server: %s", e.toString()), e);
        }
    }

    /**
     * Gets the single instance of JavaDebugServer.
     * @return the JavaDebugServer instance
     */
    public static synchronized IDebugServer getInstance() {
        if (singletonInstance == null) {
            singletonInstance = new JavaDebugServer();
        }
        return singletonInstance;
    }

    /**
     * Gets the server port.
     */
    @Override
    public synchronized int getPort() {
        if (this.serverSocket != null) {
            return this.serverSocket.getLocalPort();
        }
        return -1;
    }

    /**
     * Starts the server if it's not started yet.
     */
    @Override
    public synchronized void start() {
        if (this.serverSocket != null && !this.isStarted) {
            this.isStarted = true;
            this.executor = new ThreadPoolExecutor(0, 100, 30L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
            // Execute eventLoop in a new thread.
            new Thread(new Runnable() {

                @Override
                public void run() {
                    while (true) {
                        try {
                            // Allow server socket to service multiple clients at the same time.
                            // When a request comes in, create a connection thread to process it.
                            // Then the server goes back to listen for new connection request.
                            Socket connection = serverSocket.accept();
                            executor.submit(createConnectionTask(connection));
                        } catch (IOException e) {
                            logger.log(Level.SEVERE, String.format("Setup socket connection exception: %s", e.toString()), e);
                            closeServerSocket();
                            // If exception occurs when waiting for new client connection, shut down the connection pool
                            // to make sure no new tasks are accepted. But the previously submitted tasks will continue to run.
                            shutdownConnectionPool(false);
                            return;
                        }
                    }
                }

            }, "Java Debug Server").start();
        }
    }

    @Override
    public synchronized void stop() {
        closeServerSocket();
        shutdownConnectionPool(true);
    }

    private synchronized void closeServerSocket() {
        if (serverSocket != null) {
            try {
                logger.info("Close debugserver socket port " + serverSocket.getLocalPort());
                serverSocket.close();
            } catch (IOException e) {
                logger.log(Level.SEVERE, String.format("Close ServerSocket exception: %s", e.toString()), e);
            }
        }
        serverSocket = null;
    }

    private synchronized void shutdownConnectionPool(boolean now) {
        if (this.executor != null) {
            if (now) {
                this.executor.shutdownNow();
            } else {
                this.executor.shutdown();
            }
        }
    }

    private Runnable createConnectionTask(Socket connection) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    ProtocolServer protocolServer = new ProtocolServer(connection.getInputStream(), connection.getOutputStream(),
                            JdtProviderContextFactory.createProviderContext());
                    // protocol server will dispatch request and send response in a while-loop.
                    protocolServer.run();
                } catch (IOException e) {
                    logger.log(Level.SEVERE, String.format("Socket connection exception: %s", e.toString()), e);
                } finally {
                    logger.info("Debug connection closed");
                }
            }
        };
    }

}
