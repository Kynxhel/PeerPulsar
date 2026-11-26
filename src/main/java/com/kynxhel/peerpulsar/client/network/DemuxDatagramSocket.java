package com.kynxhel.peerpulsar.client.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Virtual {@link DatagramSocket} wrapper backed by an existing punched UDP socket so Kwik QUIC
 * can share the exact same NAT-punched socket without binding a second OS port.
 * Closing this wrapper stops Kwik's receive loop without closing the underlying shared socket.
 */
public final class DemuxDatagramSocket extends DatagramSocket {
    private static final int MAX_QUEUE_SIZE = 2048;
    private static final DatagramPacket POISON = new DatagramPacket(new byte[0], 0);

    private final DatagramSocket delegate;
    private final LinkedBlockingQueue<DatagramPacket> incoming = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile int soTimeoutMs = 0;

    public DemuxDatagramSocket(DatagramSocket delegate) throws SocketException {
        super((SocketAddress) null);
        if (delegate == null) {
            throw new SocketException("Delegate DatagramSocket must not be null");
        }
        this.delegate = delegate;
    }

    public boolean enqueueIncoming(byte[] buf, int offset, int length, SocketAddress sender) {
        if (closed.get() || delegate.isClosed() || buf == null || length < 0 || sender == null) {
            return false;
        }
        byte[] copy = new byte[length];
        System.arraycopy(buf, offset, copy, 0, length);
        DatagramPacket pkt = new DatagramPacket(copy, copy.length, sender);
        if (!incoming.offer(pkt)) {
            incoming.poll();
            return incoming.offer(pkt);
        }
        return true;
    }

    @Override
    public void receive(DatagramPacket p) throws IOException {
        if (closed.get() || delegate.isClosed()) {
            throw new SocketException("Socket closed");
        }
        DatagramPacket next;
        try {
            int timeout = this.soTimeoutMs;
            if (timeout > 0) {
                next = incoming.poll(timeout, TimeUnit.MILLISECONDS);
                if (next == null) {
                    throw new SocketTimeoutException("Receive timed out");
                }
            } else {
                next = incoming.take();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SocketException("Receive interrupted");
        }
        if (next == POISON || closed.get() || delegate.isClosed()) {
            throw new SocketException("Socket closed");
        }
        byte[] dst = p.getData();
        int dstOffset = p.getOffset();
        int maxCopy = Math.max(0, dst.length - dstOffset);
        int copyLen = Math.min(next.getLength(), maxCopy);
        System.arraycopy(next.getData(), next.getOffset(), dst, dstOffset, copyLen);
        p.setLength(copyLen);
        if (next.getSocketAddress() != null) {
            p.setSocketAddress(next.getSocketAddress());
        }
    }

    @Override
    public void send(DatagramPacket p) throws IOException {
        if (closed.get() || delegate.isClosed()) {
            throw new SocketException("Socket closed");
        }
        delegate.send(p);
    }

    @Override
    public synchronized void bind(SocketAddress addr) {
        // Already multiplexed on delegate.
    }

    @Override
    public boolean isBound() {
        return delegate.isBound();
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public boolean isClosed() {
        return closed.get() || delegate.isClosed();
    }

    @Override
    public int getLocalPort() {
        return delegate.getLocalPort();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return delegate.getLocalSocketAddress();
    }

    @Override
    public InetAddress getLocalAddress() {
        return delegate.getLocalAddress();
    }

    @Override
    public InetAddress getInetAddress() {
        return delegate.getInetAddress();
    }

    @Override
    public synchronized void setSoTimeout(int timeout) {
        this.soTimeoutMs = Math.max(0, timeout);
    }

    @Override
    public synchronized int getSoTimeout() {
        return this.soTimeoutMs;
    }

    @Override
    public synchronized int getReceiveBufferSize() throws SocketException {
        return delegate.getReceiveBufferSize();
    }

    @Override
    public synchronized void setReceiveBufferSize(int size) throws SocketException {
        delegate.setReceiveBufferSize(size);
    }

    @Override
    public synchronized int getSendBufferSize() throws SocketException {
        return delegate.getSendBufferSize();
    }

    @Override
    public synchronized void setSendBufferSize(int size) throws SocketException {
        delegate.setSendBufferSize(size);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            incoming.clear();
            incoming.offer(POISON);
            try {
                super.close();
            } catch (Exception ignored) {}
        }
    }
}
