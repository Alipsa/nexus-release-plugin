package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@CompileStatic
abstract class WsClient {
    static final String APPLICATION_JSON = 'application/json'
    static final String BODY = 'body'
    static final String RESPONSE_CODE = 'responseCode'
    static final String HEADERS = 'headers'
    final HttpClient httpClient
    final long requestDeadlineMillis
    final long smallCallCapMillis

    WsClient(int connectSeconds = 30, int requestSeconds = 1800) {
        this(Duration.ofSeconds(TimeoutSettings.validate('httpConnectTimeoutSeconds', connectSeconds)),
            TimeoutSettings.validate('httpRequestTimeoutSeconds', requestSeconds) * 1000L, 60000L)
    }

    // Millisecond deadlines keep the network proof tests fast without changing public settings.
    protected WsClient(Duration connectTimeout, long deadlineMillis, long smallCallCapMillis) {
        this.requestDeadlineMillis = deadlineMillis
        this.smallCallCapMillis = smallCallCapMillis
        HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(connectTimeout)
        ProxySelector proxy = ProxySelector.getDefault()
        if (proxy != null) builder.proxy(proxy)
        httpClient = builder.build()
    }

    long effectiveDeadlineMillis(boolean upload) {
        upload ? requestDeadlineMillis : Math.min(requestDeadlineMillis, smallCallCapMillis)
    }

    Map<String, Object> get(String url, String username, String password) throws IOException {
        send(url, HttpRequest.BodyPublishers.noBody(), 'GET', APPLICATION_JSON, username, password, false)
    }

    Map<String, Object> post(String url, byte[] payload, String username, String password,
                             String contentType = APPLICATION_JSON) throws IOException {
        send(url, HttpRequest.BodyPublishers.ofByteArray(payload ?: new byte[0]), 'POST', contentType, username, password, false)
    }

    Map<String, Object> postMultipart(String url, File file, String fieldName = 'bundle',
                                      String username, String password) throws IOException {
        String boundary = "----WebKitFormBoundary${UUID.randomUUID().toString().replace('-', '')}"
        byte[] prefix = ("--${boundary}\r\n" +
            "Content-Disposition: form-data; name=\"${fieldName}\"; filename=\"${file.name}\"\r\n" +
            "Content-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8)
        byte[] suffix = "\r\n--${boundary}--\r\n".getBytes(StandardCharsets.UTF_8)
        send(url, HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
            HttpRequest.BodyPublishers.ofFile(file.toPath()), HttpRequest.BodyPublishers.ofByteArray(suffix)),
            'POST', "multipart/form-data; boundary=${boundary}", username, password, true)
    }

    private Map<String, Object> send(String url, HttpRequest.BodyPublisher body, String method,
                                     String contentType, String username, String password, boolean upload) throws IOException {
        long deadline = effectiveDeadlineMillis(upload)
        long start = System.nanoTime()
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(deadline))
            .header('Accept', APPLICATION_JSON).header('Content-Type', contentType)
            .header('Authorization', auth(username, password)).method(method, body).build()
        def future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        HttpResponse<String> response
        try {
            long remaining = Math.max(1L, deadline - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
            response = future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (TimeoutException e) {
            future.cancel(true)
            throw new SocketTimeoutException("Request exceeded overall deadline of ${deadline / 1000.0}s: ${url}")
        } catch (ExecutionException e) {
            Throwable cause = e.cause
            if (cause instanceof java.net.http.HttpTimeoutException) {
                throw timeoutFailure((java.net.http.HttpTimeoutException) cause, deadline, url)
            }
            if (cause instanceof IOException) throw (IOException) cause
            throw new IOException('HTTP request failed', cause)
        } catch (InterruptedException e) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw new IOException('HTTP request interrupted', e)
        }
        if (response.request().method() != method) {
            // Report the redirect rather than a target's error (for example a GET's 401).
            HttpResponse<String> redirect = response
            while (redirect.previousResponse().isPresent()) {
                redirect = redirect.previousResponse().get()
                if (redirect.request().method() == method) break
            }
            throw new WsException(redirect.statusCode(),
                "${method} ${url} was redirected to ${response.uri()} and converted to ${response.request().method()}")
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new WsException(response.statusCode(), response.body())
        }
        [(BODY): response.body(), (RESPONSE_CODE): response.statusCode(), (HEADERS): response.headers().map()] as Map<String, Object>
    }

    @PackageScope
    static SocketTimeoutException timeoutFailure(java.net.http.HttpTimeoutException cause, long deadline, String url) {
        String message = cause instanceof java.net.http.HttpConnectTimeoutException ?
            "Connect timed out: ${url}" : "Request exceeded overall deadline of ${deadline / 1000.0}s: ${url}"
        SocketTimeoutException timeout = new SocketTimeoutException(message)
        timeout.initCause(cause)
        timeout
    }

    abstract String auth(String username, String password)
}
