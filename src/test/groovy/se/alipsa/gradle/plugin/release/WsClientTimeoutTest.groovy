package se.alipsa.gradle.plugin.release

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.*
import static org.junit.jupiter.api.Assertions.*

@Timeout(30)
class WsClientTimeoutTest {
    static class Client extends WsClient {
        Client(long deadline = 2000, long cap = 60000) { super(Duration.ofSeconds(1), deadline, cap) }
        String auth(String username, String password) { 'Bearer test' }
    }

    @ParameterizedTest
    @ValueSource(strings = ['headers', 'body', 'upload', 'trickle', 'small'])
    void stalledRequestsBoundCallerAndCloseConnection(String mode) {
        int expected = Integer.parseInt(System.getProperty('testJavaVersion', '17'))
        println "Timeout proof runtime: JDK ${Runtime.version()}, expected ${expected}"
        assertEquals(expected, Runtime.version().feature())
        Set<Thread> before = Thread.getAllStackTraces().keySet()
        ServerSocket server = new ServerSocket(0)
        CountDownLatch callerDone = new CountDownLatch(1)
        CompletableFuture<Boolean> closed = new CompletableFuture<>()
        Thread peer = Thread.startDaemon {
            try {
                server.accept().withCloseable { socket ->
                    socket.soTimeout = 5000
                    if (mode != 'upload') {
                        readHeaders(socket.inputStream)
                        if (mode in ['body', 'trickle']) {
                            socket.outputStream.write('HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n0123456789'.getBytes('US-ASCII'))
                            socket.outputStream.flush()
                        }
                        if (mode == 'trickle') {
                            while (!callerDone.await(500, TimeUnit.MILLISECONDS)) {
                                try { socket.outputStream.write(120); socket.outputStream.flush() }
                                catch (IOException ignored) { break }
                            }
                        }
                    }
                    assertTrue(callerDone.await(8, TimeUnit.SECONDS))
                    // Drain the upload bytes already buffered before observing EOF/reset.
                    try {
                        byte[] buffer = new byte[65536]
                        while (socket.inputStream.read(buffer) != -1) { }
                        closed.complete(true)
                    } catch (SocketTimeoutException e) { closed.completeExceptionally(e) }
                    catch (IOException ignored) { closed.complete(true) }
                }
            } catch (Throwable e) { closed.completeExceptionally(e) }
        }
        File upload = File.createTempFile('deadline-upload', '.zip')
        try {
            new RandomAccessFile(upload, 'rw').withCloseable { it.setLength(64L * 1024 * 1024) }
            Client client = mode == 'small' ? new Client(1800000, 1000) : new Client()
            String url = "http://127.0.0.1:${server.localPort}/"
            long start = System.nanoTime()
            IOException failure = assertThrows(IOException) {
                if (mode == 'upload') client.postMultipart(url, upload, 'user', 'pass')
                else client.get(url, 'user', 'pass')
            }
            assertTrue(failure instanceof SocketTimeoutException || failure instanceof java.net.http.HttpTimeoutException, failure.toString())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) <= (mode == 'small' ? 6000 : 7000))
            callerDone.countDown()
            assertTrue(closed.get(5, TimeUnit.SECONDS), "Connection not closed for ${mode}")
            peer.join(1000)
            assertTrue(Thread.getAllStackTraces().keySet().findAll { !before.contains(it) && it != peer }.every { it.daemon || !it.alive })
        } finally {
            callerDone.countDown(); server.close(); upload.delete(); peer.join(1000)
        }
    }

    @Test void independentDeadlinesAndHealthyRequest() {
        ServerSocket stalled = new ServerSocket(0)
        List<Socket> peers = Collections.synchronizedList([])
        Thread accept = Thread.startDaemon {
            try {
                2.times {
                    Socket socket = stalled.accept()
                    peers.add(socket)
                    readHeaders(socket.inputStream)
                    socket.outputStream.write('HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n0123456789'.bytes)
                    socket.outputStream.flush()
                }
            } catch (IOException ignored) { }
        }
        ExecutorService workers = Executors.newFixedThreadPool(2)
        try {
            Client client = new Client(500)
            long start = System.nanoTime()
            def results = (1..2).collect { workers.submit({ assertThrows(IOException) { client.get("http://127.0.0.1:${stalled.localPort}/", '', '') } } as Callable) }
            results.each { it.get(6, TimeUnit.SECONDS) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5500)
            ServerSocket healthy = new ServerSocket(0)
            Thread responder = Thread.startDaemon {
                healthy.accept().withCloseable { socket ->
                    readHeaders(socket.inputStream)
                    socket.outputStream.write('HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok'.bytes)
                }
            }
            try { assertEquals('ok', client.get("http://127.0.0.1:${healthy.localPort}/", '', '').body) }
            finally { healthy.close(); responder.join(1000) }
        } finally { stalled.close(); peers.each { it.close() }; workers.shutdownNow(); accept.join(1000) }
    }

    @Test void smallCallCapAndValidation() {
        Client client = new Client(1800000, 1000)
        assertEquals(1000L, client.effectiveDeadlineMillis(false))
        assertEquals(1800000L, client.effectiveDeadlineMillis(true))
        assertEquals(500L, new Client(500, 1000).effectiveDeadlineMillis(false))
        ['httpConnectTimeoutSeconds', 'httpRequestTimeoutSeconds'].each { name ->
            [0, -1, 86401, Integer.MAX_VALUE].each { value ->
                assertTrue(assertThrows(org.gradle.api.GradleException) { TimeoutSettings.validate(name, value) }.message.contains('between 1 and 86400'))
            }
            [1, 86400].each { assertEquals(it, TimeoutSettings.validate(name, it)) }
        }
        def defaults = new WsClient() { String auth(String u, String p) { '' } }
        assertEquals(60000L, defaults.effectiveDeadlineMillis(false))
        assertEquals(java.net.http.HttpClient.Version.HTTP_1_1, defaults.httpClient.version())
        assertEquals(java.net.http.HttpClient.Redirect.NORMAL, defaults.httpClient.followRedirects())
        assertEquals(ProxySelector.default, defaults.httpClient.proxy().orElse(null))
    }

    @Test void redirectAndErrorBodyParity() {
        def server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/redirect') { exchange ->
            exchange.responseHeaders.set('Location', '/ok')
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext('/ok') { exchange ->
            assertEquals('Bearer test', exchange.requestHeaders.getFirst('Authorization'))
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.withCloseable { it.write('ok'.bytes) }
        }
        server.createContext('/error') { exchange ->
            exchange.sendResponseHeaders(422, 8)
            exchange.responseBody.withCloseable { it.write('rejected'.bytes) }
        }
        server.start()
        try {
            def client = new Client()
            String base = "http://127.0.0.1:${server.address.port}"
            assertEquals('ok', client.get(base + '/redirect', '', '').body)
            WsException error = assertThrows(WsException) { client.post(base + '/error', null, '', '') }
            assertEquals(422, error.statusCode)
            assertEquals('rejected', error.body)
        } finally { server.stop(0) }
    }

    static void readHeaders(InputStream input) {
        int state = 0
        byte[] end = '\r\n\r\n'.bytes
        while (state < 4) {
            int value = input.read()
            if (value == -1) throw new EOFException()
            state = value == end[state] ? state + 1 : (value == 13 ? 1 : 0)
        }
    }
    @org.junit.jupiter.api.Test
    void distinguishesConnectTimeoutFromOverallDeadline() {
        def connect = new java.net.http.HttpConnectTimeoutException('connection stalled')
        def failure = WsClient.timeoutFailure(connect, 1800000, 'http://localhost/')
        assertEquals('Connect timed out: http://localhost/', failure.message)
        assertSame(connect, failure.cause)
        def overall = new java.net.http.HttpTimeoutException('request stalled')
        failure = WsClient.timeoutFailure(overall, 1800000, 'http://localhost/')
        assertTrue(failure.message.startsWith('Request exceeded overall deadline of '), failure.message)
        assertSame(overall, failure.cause)
    }

}
