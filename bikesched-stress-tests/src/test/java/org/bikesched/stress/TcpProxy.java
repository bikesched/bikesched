// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A TCP proxy in front of the database, so a test can break the network between one node and the
 * database without touching the others:
 *
 * <ul>
 *   <li>{@link #hang()} stops forwarding in both directions while every connection stays open, like
 *       a network partition or a failover without a TCP reset: statements neither answer nor fail.
 *       New connections are accepted and hang, too. {@link #heal()} forwards again, including what
 *       was sent in the meantime.
 *   <li>{@link #freeze()} stops forwarding on the connections open now, for good, while new
 *       connections work: a failover without a TCP reset, where pooled connections to the old
 *       primary hang and fresh ones reach the new one.
 *   <li>{@link #cut()} closes every open connection, like a database restart from the client's
 *       view; new connections work right away.
 * </ul>
 *
 * <p>The upstream address is looked up for every new connection, so the proxy keeps working when a
 * restarted database container gets a new port.
 */
final class TcpProxy implements AutoCloseable {

  private final Supplier<InetSocketAddress> upstream;
  private final ServerSocket server;
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final Set<Socket> frozen = ConcurrentHashMap.newKeySet();
  private final Object gate = new Object();
  private boolean hanging;
  private volatile boolean closed;

  /**
   * @param upstream the database's current address
   */
  TcpProxy(Supplier<InetSocketAddress> upstream) {
    this.upstream = upstream;
    try {
      server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Thread.ofVirtual().name("proxy-accept-" + port()).start(this::accept);
  }

  /** The local port to connect to instead of the database's. */
  int port() {
    return server.getLocalPort();
  }

  /** Stops forwarding; connections stay open and hang until {@link #heal()}. */
  void hang() {
    synchronized (gate) {
      hanging = true;
    }
  }

  /** Forwards again after {@link #hang()}. */
  void heal() {
    synchronized (gate) {
      hanging = false;
      gate.notifyAll();
    }
  }

  /** Stops forwarding on the connections open now, for good; new connections work. */
  void freeze() {
    frozen.addAll(sockets);
  }

  /** Closes every open connection. */
  void cut() {
    sockets.forEach(TcpProxy::closeQuietly);
  }

  @Override
  public void close() {
    closed = true;
    closeQuietly(server);
    heal();
    cut();
  }

  private void accept() {
    while (!closed) {
      Socket client;
      try {
        client = server.accept();
      } catch (IOException e) {
        return; // closed
      }
      Thread.ofVirtual().start(() -> connect(client));
    }
  }

  private void connect(Socket client) {
    sockets.add(client);
    Socket database = new Socket();
    sockets.add(database);
    try {
      awaitForwarding(client);
      database.connect(upstream.get(), 5_000);
    } catch (IOException | RuntimeException e) {
      close(client, database);
      return;
    }
    Thread.ofVirtual().start(() -> pump(client, database));
    pump(database, client);
  }

  /** Copies from one socket to the other until either side closes. */
  private void pump(Socket from, Socket to) {
    byte[] buffer = new byte[16 * 1024];
    try {
      InputStream in = from.getInputStream();
      OutputStream out = to.getOutputStream();
      for (int read; (read = in.read(buffer)) >= 0; ) {
        awaitForwarding(from);
        out.write(buffer, 0, read);
        out.flush();
      }
    } catch (IOException e) {
      // a side closed or the connection was cut
    } finally {
      close(from, to);
    }
  }

  private void awaitForwarding(Socket socket) throws IOException {
    synchronized (gate) {
      while ((hanging || frozen.contains(socket)) && !socket.isClosed() && !closed) {
        try {
          gate.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted", e);
        }
      }
    }
  }

  private void close(Socket a, Socket b) {
    closeQuietly(a);
    closeQuietly(b);
    sockets.remove(a);
    sockets.remove(b);
    frozen.remove(a);
    frozen.remove(b);
    synchronized (gate) {
      gate.notifyAll(); // a pump waiting to forward on these sockets
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception e) {
      // already closed
    }
  }
}
