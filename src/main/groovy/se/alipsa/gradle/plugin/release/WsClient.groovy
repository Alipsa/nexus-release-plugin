package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic

@CompileStatic
abstract class WsClient {
  static final String APPLICATION_JSON = 'application/json'
  static final String BODY = 'body'
  static final String RESPONSE_CODE = 'responseCode'
  static final String HEADERS = 'headers'

  Map<String, Object> get(String urlString, String username, String password) throws IOException {
    StringBuilder writer = new StringBuilder()
    URL url = new URL(urlString)
    HttpURLConnection conn = (HttpURLConnection) url.openConnection()
    conn.setRequestMethod("GET")
    conn.setRequestProperty("Accept", APPLICATION_JSON)
    conn.setRequestProperty("Authorization", auth(username, password))
    conn.connect()
    int responseCode = conn.getResponseCode()
    var responseHeaders = conn.getHeaderFields()
    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))
    String line
    while ((line = br.readLine()) != null) {
      writer.append(line).append('\n')
    }
    conn.disconnect()
    return [(BODY): writer.toString(), (RESPONSE_CODE): responseCode, (HEADERS): responseHeaders]
  }

  Map<String, Object> post(String urlString, byte[] payload, String username, String password, String contentType=APPLICATION_JSON) throws IOException {
    StringBuilder writer = new StringBuilder()
    URL url = new URL(urlString)
    HttpURLConnection conn = (HttpURLConnection) url.openConnection()
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
    InputStream is = null
    try {
      is = conn.getInputStream()
    } catch (IOException ignored) {
      // no content
    }
    if (is != null) {
      BufferedReader br = new BufferedReader(new InputStreamReader(is))

      String line
      while ((line = br.readLine()) != null) {
        writer.append(line).append('\n')
      }
      is.close()
    }
    conn.disconnect()
    return [(BODY): writer.toString(), (RESPONSE_CODE): responseCode, (HEADERS): responseHeaders]
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

    def responseCode = conn.responseCode
    def responseText = conn.inputStream.text

    return [(RESPONSE_CODE): responseCode, (BODY): responseText, (HEADERS): conn.headerFields]
  }

  abstract String auth(String username, String password)
}
