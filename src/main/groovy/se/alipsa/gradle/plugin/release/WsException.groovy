package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic

@CompileStatic
class WsException extends IOException {
    final int statusCode
    final String body

    WsException(int statusCode, String body) {
        super("HTTP ${statusCode}: ${body ?: ''}".toString())
        this.statusCode = statusCode
        this.body = body
    }

    WsException(int statusCode, String body, Throwable cause) {
        super("HTTP ${statusCode}: ${body ?: ''}".toString(), cause)
        this.statusCode = statusCode
        this.body = body
    }
}
