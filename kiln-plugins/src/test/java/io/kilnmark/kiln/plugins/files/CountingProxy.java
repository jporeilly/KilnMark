package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** A TCP proxy for tests that counts the bytes the server sends, to see how much a read transferred. */
final class CountingProxy implements AutoCloseable {

    private final ServerSocket listener;
    private final String host;
    private final int targetPort;
    private final AtomicLong fromServer = new AtomicLong();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final long pauseMillisPer16k;

    CountingProxy(String host, int targetPort) throws IOException {
        this(host, targetPort, 0);
    }

    /** A proxy that pauses after relaying each 16 KiB from the server, to make transfers slow. */
    CountingProxy(String host, int targetPort, long pauseMillisPer16k) throws IOException {
        this.pauseMillisPer16k = pauseMillisPer16k;
        this.host = host;
        this.targetPort = targetPort;
        this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::accept);
    }

    int port() {
        return listener.getLocalPort();
    }

    long bytesFromServer() {
        return fromServer.get();
    }

    void reset() {
        fromServer.set(0);
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                Socket server = new Socket(host, targetPort);
                sockets.add(client);
                sockets.add(server);
                Thread.ofVirtual().start(() -> pump(client, server, null, 0));
                Thread.ofVirtual().start(() -> pump(server, client, fromServer, pauseMillisPer16k));
            } catch (IOException e) {
                return;
            }
        }
    }

    private static void pump(Socket from, Socket to, AtomicLong counter, long pause) {
        byte[] buf = new byte[16 * 1024];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (counter != null) {
                    counter.addAndGet(n);
                }
                out.write(buf, 0, n);
                if (pause > 0) {
                    Thread.sleep(pause);
                }
            }
        } catch (IOException e) {
            // one side closed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // one side closed
        } finally {
            close(from);
            close(to);
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // closing
        }
    }

    @Override
    public void close() throws IOException {
        listener.close();
        for (Socket s : sockets) {
            close(s);
        }
    }
}
