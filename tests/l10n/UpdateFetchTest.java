package com.jjricks.volt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The automatic update check's real request path (UpdateCheck.fetch and https, gated by UpdateRuns) against a local
 * server that counts the HTTP requests it receives. The JVM's HttpURLConnection does the work; the only stand-in is
 * a URL handler that sends "https://127.0.0.1" over plain HTTP, through a connection that can pause right after
 * the abort hook is registered (before connecting) and right after connect() (before the send decision).
 * Covers each cancellation point: before connecting (Codex's interleaving), during connect, and after the send
 * decision, where a revoke closes the request at once. Android's HTTPS stack is not run here. Listed in
 * tests/l10n/update-checks.sources; tests/run.sh runs it.
 */
public final class UpdateFetchTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long NOW = 1_780_000_000_000L;

    /**
     * Counts connections and HTTP requests; can hold its answer. The client's connect() returns once the kernel has
     * finished the handshake, which can be before this thread's accept() returns and counts it (under load, often):
     * accepted opens only after the count, and handler is that connection's thread, already started, for a test to
     * join. Every handler is also kept until quiet() has seen it end.
     */
    static final class Server implements Runnable {
        final ServerSocket socket;
        final AtomicInteger connections = new AtomicInteger(), requests = new AtomicInteger();
        volatile CountDownLatch answer = new CountDownLatch(0);
        volatile CountDownLatch received = new CountDownLatch(1);
        volatile CountDownLatch accepted = new CountDownLatch(1);
        volatile Thread handler;
        /** Every connection accepted since the server started, and the handlers quiet() has not yet seen end. */
        private int acceptedEver;
        private final List<Thread> handlers = new ArrayList<>();
        Server() throws IOException { socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")); }
        @Override public void run() {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    Thread one = new Thread(() -> serve(client)); one.setDaemon(true);
                    // Started before anyone can see it: joining a thread that hasn't started returns at once.
                    one.start();
                    synchronized (this) { acceptedEver++; handlers.add(one); notifyAll(); }
                    handler = one; connections.incrementAndGet(); accepted.countDown();
                } catch (IOException closed) { return; }
            }
        }
        /**
         * Between cases: every connection a client has opened so far (Pausing.opened) has been accepted, and each
         * one's handler has ended, so nothing from an earlier case can reach the next one's counts or latches.
         */
        void quiet() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            List<Thread> started;
            synchronized (this) {
                while (acceptedEver < Pausing.opened.get()) {
                    long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (left <= 0) break;
                    wait(left);
                }
                check(acceptedEver == Pausing.opened.get(), "every connection a client opened was accepted (" + acceptedEver + " of " + Pausing.opened.get() + ")");
                started = new ArrayList<>(handlers); handlers.clear();
            }
            for (Thread one : started) {
                one.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                check(!one.isAlive(), "every earlier connection was closed");
            }
        }
        private void serve(Socket client) {
            try (Socket c = client) {
                InputStream in = c.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) >= 0) {
                    head.write(b);
                    String sofar = new String(head.toByteArray(), StandardCharsets.US_ASCII);
                    if (sofar.endsWith("\r\n\r\n")) break;
                }
                if (!new String(head.toByteArray(), StandardCharsets.US_ASCII).startsWith("GET ")) return;
                requests.incrementAndGet(); received.countDown();
                answer.await(10, TimeUnit.SECONDS);
                byte[] body = "{}".getBytes(StandardCharsets.US_ASCII);
                OutputStream out = c.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body); out.flush();
            } catch (IOException | InterruptedException gone) {
                // The client closed the connection: nothing to answer.
            }
        }
        /** The requests once every connection so far was accepted and its handler ended: none can still arrive. */
        int settledRequests() throws InterruptedException { quiet(); return requests.get(); }
    }

    /** Where the next connection pauses: right after the hook (before connect), or right after connect. */
    static volatile CountDownLatch atConnect, leaveConnect, afterConnect, leaveAfterConnect;

    /**
     * A real JVM HttpURLConnection over plain HTTP, with the two pause points. Every connection goes through connect()
     * (the manual check's implicit one too), which counts each one it opened (opened) for Server.quiet.
     */
    static final class Pausing extends HttpURLConnection {
        static final AtomicInteger opened = new AtomicInteger();
        private final HttpURLConnection real;
        Pausing(URL url, HttpURLConnection real) { super(url); this.real = real; }
        @Override public void connect() throws IOException {
            if (connected) return;
            pause(atConnect, leaveConnect);
            real.connect(); connected = true; opened.incrementAndGet();
            pause(afterConnect, leaveAfterConnect);
        }
        @Override public void disconnect() { real.disconnect(); }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() throws IOException { connect(); return real.getResponseCode(); }
        @Override public InputStream getInputStream() throws IOException { return real.getInputStream(); }
        @Override public void setRequestProperty(String key, String value) { real.setRequestProperty(key, value); }
        @Override public void setConnectTimeout(int timeout) { real.setConnectTimeout(timeout); }
        @Override public void setReadTimeout(int timeout) { real.setReadTimeout(timeout); }
        @Override public void setInstanceFollowRedirects(boolean follow) { real.setInstanceFollowRedirects(follow); }
        @Override public void setUseCaches(boolean use) { real.setUseCaches(use); }
        private static void pause(CountDownLatch at, CountDownLatch leave) {
            if (at == null) return;
            at.countDown();
            try { if (!leave.await(10, TimeUnit.SECONDS)) throw new AssertionError("a pause was never released"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        }
    }

    /** UpdateRuns' host over the real fetch; the setting and the record are plain fields. */
    static final class Phone implements UpdateRuns.Host {
        final String address;
        volatile boolean on = true;
        volatile int saves, posts, failures;
        Phone(String address) { this.address = address; }
        @Override public boolean enabled() { return on; }
        @Override public long last() { return 0; }
        @Override public String newer(UpdateRuns.Run run) throws Exception {
            try { UpdateCheck.fetch(run, address, "volt-test"); return "9.9.9"; }
            catch (IOException | RuntimeException failed) { failures++; throw failed; }
        }
        @Override public void saveLast(long now) { saves++; }
        @Override public String told() { return ""; }
        @Override public boolean post(String latest) { posts++; return true; }
        @Override public void seen(String latest) {}
    }

    /** The worker's uncaught error, if any: a pause that gave up throws on the worker's thread, not the test's. */
    private static final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    private static Thread worker(Runnable work) {
        Thread worker = new Thread(work);
        worker.setUncaughtExceptionHandler((thread, failure) -> { workerFailure.compareAndSet(null, failure); failure.printStackTrace(); });
        worker.start();
        return worker;
    }
    /** The worker ended within the time allowed, without an error of its own. */
    private static void ended(Thread worker, long millis, String message) throws InterruptedException {
        worker.join(millis);
        check(!worker.isAlive(), message);
        check(workerFailure.get() == null, "the worker failed: " + workerFailure.get());
    }

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> !"https".equals(protocol) ? null : new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) throws IOException {
                URL plain = new URL("http", url.getHost(), url.getPort(), url.getFile());
                return new Pausing(url, (HttpURLConnection) plain.openConnection());
            }
        });
        Server server = new Server();
        Thread accept = new Thread(server); accept.setDaemon(true); accept.start();
        String address = "https://127.0.0.1:" + server.socket.getLocalPort() + "/latest";
        ordinary(server, address); manualUngated(server, address);
        revokeBeforeConnecting(server, address); stopBeforeConnecting(server, address);
        revokeDuringConnect(server, address); revokeAfterSending(server, address);
        server.quiet(); server.socket.close();
        System.out.println("UpdateFetchTest: " + checks + " checks passed (real HttpURLConnection: no request after a revoke or stop before the send decision)");
    }

    private static void reset(Server server) throws InterruptedException {
        server.quiet(); workerFailure.set(null);
        server.connections.set(0); server.requests.set(0); server.answer = new CountDownLatch(0); server.received = new CountDownLatch(1);
        server.accepted = new CountDownLatch(1); server.handler = null;
        atConnect = leaveConnect = afterConnect = leaveAfterConnect = null;
    }

    private static void ordinary(Server server, String address) throws Exception {
        reset(server);
        Phone phone = new Phone(address); UpdateRuns runs = new UpdateRuns(phone);
        UpdateRuns.Run run = runs.start();
        runs.run(run, NOW);
        check(server.settledRequests() == 1 && phone.failures == 0, "an ordinary run sends one request");
        check(phone.saves == 1 && phone.posts == 1 && runs.finish(run), "and saves, posts and finishes");
    }
    /** The manual Check for updates sets no gate and connects as before. */
    private static void manualUngated(Server server, String address) throws Exception {
        reset(server);
        check(UpdateCheck.gate.get() == null, "no gate outside the automatic check");
        check("{}".equals(UpdateCheck.fetcher.get(address, "volt-test")), "the manual fetch reads the reply");
        check(server.settledRequests() == 1, "and sends its one request");
    }
    /** Codex's interleaving: paused right after the abort hook, turned off and revoked, resumed. */
    private static void revokeBeforeConnecting(Server server, String address) throws Exception {
        reset(server);
        atConnect = new CountDownLatch(1); leaveConnect = new CountDownLatch(1);
        Phone phone = new Phone(address); UpdateRuns runs = new UpdateRuns(phone);
        UpdateRuns.Run run = runs.start();
        Thread worker = worker(() -> runs.run(run, NOW));
        check(atConnect.await(10, TimeUnit.SECONDS), "paused right after the hook, before connecting");
        phone.on = false; runs.revoke();
        leaveConnect.countDown(); ended(worker, 10_000, "the worker ends");
        check(server.settledRequests() == 0, "revoked after the hook, before connecting: zero HTTP requests");
        check(phone.saves == 0 && phone.posts == 0 && phone.failures == 1, "the send decision refused it; nothing saved or posted");
    }
    private static void stopBeforeConnecting(Server server, String address) throws Exception {
        reset(server);
        atConnect = new CountDownLatch(1); leaveConnect = new CountDownLatch(1);
        Phone phone = new Phone(address); UpdateRuns runs = new UpdateRuns(phone);
        UpdateRuns.Run run = runs.start();
        Thread worker = worker(() -> runs.run(run, NOW));
        check(atConnect.await(10, TimeUnit.SECONDS), "paused before connecting");
        check(runs.stop(run), "stopped while on: a retry is wanted");
        leaveConnect.countDown(); ended(worker, 10_000, "the worker ends");
        check(server.settledRequests() == 0 && phone.saves == 0 && !runs.finish(run), "stopped before connecting: zero requests");
    }
    /** Revoked while connect() runs (paused right after it opened the socket): the bare connection closes. */
    private static void revokeDuringConnect(Server server, String address) throws Exception {
        reset(server);
        afterConnect = new CountDownLatch(1); leaveAfterConnect = new CountDownLatch(1);
        Phone phone = new Phone(address); UpdateRuns runs = new UpdateRuns(phone);
        UpdateRuns.Run run = runs.start();
        Thread worker = worker(() -> runs.run(run, NOW));
        check(afterConnect.await(10, TimeUnit.SECONDS), "connected, before the send decision");
        // connect() returning means the handshake is done, not that the server's accept() has returned: wait for it.
        // reset() saw every earlier connection accepted and closed, so this one is the worker's.
        check(server.accepted.await(10, TimeUnit.SECONDS) && server.connections.get() == 1, "the socket is open");
        Thread handler = server.handler;
        check(handler != null && handler.getState() != Thread.State.NEW, "the server started that connection's handler");
        phone.on = false; runs.revoke();
        leaveAfterConnect.countDown(); ended(worker, 10_000, "the worker ends");
        // The server's thread for that connection ends only when the client closes it: then nothing more can arrive.
        handler.join(10_000);
        check(!handler.isAlive(), "the bare connection was closed");
        check(server.requests.get() == 0, "revoked during connect: no HTTP request written");
        check(phone.saves == 0 && phone.posts == 0, "nothing saved or posted");
    }
    /** After the send decision the request is on the wire: revoke returns at once and closes it; nothing is kept. */
    private static void revokeAfterSending(Server server, String address) throws Exception {
        reset(server);
        server.answer = new CountDownLatch(1);
        Phone phone = new Phone(address); UpdateRuns runs = new UpdateRuns(phone);
        UpdateRuns.Run run = runs.start();
        Thread worker = worker(() -> runs.run(run, NOW));
        check(server.received.await(10, TimeUnit.SECONDS), "the request reached the server");
        long started = System.nanoTime();
        phone.on = false; runs.revoke();
        long took = (System.nanoTime() - started) / 1_000_000;
        check(took < 2000, "revoke returns without waiting on the held answer (" + took + " ms)");
        ended(worker, 5_000, "the hook closed the connection: the worker ends while the answer is still held");
        server.answer.countDown();
        check(server.settledRequests() == 1, "the one request already sent can't be recalled");
        check(phone.saves == 0 && phone.posts == 0, "its result is discarded: no save, no notice");
    }
}
