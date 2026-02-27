package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

@CompileStatic
abstract class WsClient {
  static final String APPLICATION_JSON = 'application/json'
  static final String BODY = 'body'
  static final String RESPONSE_CODE = 'responseCode'
  static final String HEADERS = 'headers'
  private static final Logger LOGGER = Logging.getLogger(WsClient)

  Map<String, Object> get(String urlString, String username, String password) throws IOException {
    URL url = new URL(urlString)
    HttpURLConnection conn = (HttpURLConnection) url.openConnection()
    try {
      conn.setRequestMethod("GET")
      conn.setRequestProperty("Accept", APPLICATION_JSON)
      conn.setRequestProperty("Authorization", auth(username, password))
      conn.connect()
      int responseCode = conn.getResponseCode()
      var responseHeaders = conn.getHeaderFields()
      ensureSuccess(responseCode, '', conn)
      String body = readBody(conn.getInputStream())
      return [(BODY): body, (RESPONSE_CODE): responseCode, (HEADERS): responseHeaders]
    } finally {
      conn.disconnect()
    }
  }

  Map<String, Object> post(String urlString, byte[] payload, String username, String password, String contentType=APPLICATION_JSON) throws IOException {
    URL url = new URL(urlString)
    HttpURLConnection conn = (HttpURLConnection) url.openConnection()
    try {
      conn.setRequestMethod('POST')
      conn.setRequestProperty('Content-Type', contentType)
      conn.setRequestProperty("Accept", APPLICATION_JSON)
      conn.setRequestProperty("Authorization", auth(username, password))
      conn.setDoOutput(true)
      conn.connect()
      if (payload) {
        OutputStream os = conn.getOutputStream()
        os.write(payload)
        os.flush()
        os.close()
      }
      int responseCode = conn.getResponseCode()
      var responseHeaders = conn.getHeaderFields()
      ensureSuccess(responseCode, '', conn)
      String body = readBody(conn.getInputStream())
      return [(BODY): body, (RESPONSE_CODE): responseCode, (HEADERS): responseHeaders]
    } finally {
      conn.disconnect()
    }
  }

  Map<String, Object> postMultipart(String urlString, File file, String fieldName = "bundle", String username, String password) throws IOException {
    def boundary = "----WebKitFormBoundary${UUID.randomUUID().toString().replaceAll('-', '')}"
    def lineEnd = "\r\n"
    def twoHyphens = "--"

    def conn = new URL(urlString).openConnection() as HttpURLConnection
    conn.setRequestMethod("POST")
    conn.setDoOutput(true)
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=${boundary}")
    conn.setRequestProperty("Accept", "application/json")
    conn.setRequestProperty("Authorization", auth(username, password))

    try {
      conn.connect()

      def out = new DataOutputStream(conn.outputStream)
      out.writeBytes("${twoHyphens}${boundary}${lineEnd}")
      out.writeBytes("Content-Disposition: form-data; name=\"${fieldName}\"; filename=\"${file.name}\"${lineEnd}")
      out.writeBytes("Content-Type: application/zip${lineEnd}")
      out.writeBytes(lineEnd)
      out.write(file.bytes)
      out.writeBytes(lineEnd)
      out.writeBytes("${twoHyphens}${boundary}--${lineEnd}")
      out.flush()
      out.close()

      int responseCode = conn.responseCode
      ensureSuccess(responseCode, '', conn)
      String body = readBody(conn.inputStream)
      return [(RESPONSE_CODE): responseCode, (BODY): body, (HEADERS): conn.headerFields]
    } finally {
      conn.disconnect()
    }
  }

  private static void ensureSuccess(int responseCode, String body, HttpURLConnection conn) {
    if (responseCode >= 200 && responseCode < 300) {
      return
    }
    String errorBody = readBody(conn.getErrorStream())
    if (!errorBody) {
      errorBody = body
    }
    LOGGER.lifecycle("HTTP call failed with status ${responseCode}")
    if (errorBody) {
      LOGGER.lifecycle(errorBody)
    }
    throw new WsException(responseCode, errorBody)
  }

  private static String readBody(InputStream stream) {
    if (stream == null) {
      return ''
    }
    try (InputStream is = stream; BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
      StringBuilder writer = new StringBuilder()
      String line
      while ((line = br.readLine()) != null) {
        writer.append(line).append('\n')
      }
      return writer.toString()
    }
  }

  abstract String auth(String username, String password)
}
