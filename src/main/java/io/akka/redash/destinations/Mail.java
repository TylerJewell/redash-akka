package io.akka.redash.destinations;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.SSLSocketFactory;

/**
 * Sending one message, over SMTP, with the settings the original reads.
 *
 * <p>Written directly rather than through a mail library because what a port has to
 * reproduce here is a handful of settings and one conversation: connect, optionally
 * upgrade, optionally authenticate, and hand over a message with an HTML part and a plain
 * one. A library would add a dependency and hide the only part that could differ.
 */
public final class Mail {

  /**
   * Everything the original's mail settings carry.
   *
   * @param maxEmails how many messages one connection carries before it is reopened, or
   *     null for no limit. A connection here is opened per message and closed after it, so
   *     the limit is never reached — it is carried so a deployment that sets it is not told
   *     the setting is unknown.
   * @param asciiAttachments whether an attachment's filename is reduced to ASCII. This
   *     rebuild attaches nothing to an alert message, which is what the original attaches,
   *     so the setting has nothing to act on and is carried for the same reason.
   */
  public record Server(String host, int port, boolean useTls, boolean useSsl, String username,
      String password, String defaultSender, Integer maxEmails, boolean asciiAttachments) {

    public boolean isConfigured() {
      return defaultSender != null && !defaultSender.isEmpty();
    }
  }

  private Mail() {}

  /**
   * Send one message. A failure is logged and swallowed, which is what the original does —
   * an alert whose email cannot be delivered must not fail the evaluation that produced it.
   */
  public static String send(Server server, List<String> recipients, String subject, String html,
      String text) {
    if (recipients == null || recipients.isEmpty()) {
      return "No emails given. Skipping send.";
    }
    try (Socket socket = open(server)) {
      var in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
          StandardCharsets.UTF_8));
      var out = socket.getOutputStream();
      expect(in, "220");

      Socket active = socket;
      say(out, "EHLO redash");
      var greeting = readAll(in);
      if (server.useTls() && greeting.contains("STARTTLS")) {
        say(out, "STARTTLS");
        expect(in, "220");
        active = upgrade(socket, server.host(), server.port());
        in = new BufferedReader(new InputStreamReader(active.getInputStream(),
            StandardCharsets.UTF_8));
        out = active.getOutputStream();
        say(out, "EHLO redash");
        readAll(in);
      }

      if (server.username() != null && !server.username().isEmpty()) {
        say(out, "AUTH LOGIN");
        expect(in, "334");
        say(out, Base64.getEncoder().encodeToString(
            server.username().getBytes(StandardCharsets.UTF_8)));
        expect(in, "334");
        say(out, Base64.getEncoder().encodeToString(
            String.valueOf(server.password()).getBytes(StandardCharsets.UTF_8)));
        expect(in, "235");
      }

      say(out, "MAIL FROM:<" + server.defaultSender() + ">");
      expect(in, "250");
      for (String recipient : recipients) {
        say(out, "RCPT TO:<" + recipient.strip() + ">");
        expect(in, "250");
      }
      say(out, "DATA");
      expect(in, "354");
      out.write(message(server.defaultSender(), recipients, subject, html, text)
          .getBytes(StandardCharsets.UTF_8));
      out.write("\r\n.\r\n".getBytes(StandardCharsets.UTF_8));
      out.flush();
      expect(in, "250");
      say(out, "QUIT");
      if (active != socket) {
        active.close();
      }
      return null;
    } catch (IOException | RuntimeException e) {
      return "Mail send error: " + e.getMessage();
    }
  }

  private static Socket open(Server server) throws IOException {
    if (server.useSsl()) {
      return SSLSocketFactory.getDefault().createSocket(server.host(), server.port());
    }
    return new Socket(server.host(), server.port());
  }

  private static Socket upgrade(Socket socket, String host, int port) throws IOException {
    return ((SSLSocketFactory) SSLSocketFactory.getDefault())
        .createSocket(socket, host, port, false);
  }

  private static void say(OutputStream out, String line) throws IOException {
    out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void expect(BufferedReader in, String code) throws IOException {
    var line = readAll(in);
    if (!line.startsWith(code)) {
      throw new IOException("expected " + code + ", got: " + line);
    }
  }

  /** Read one reply, which may be several lines when the code is followed by a hyphen. */
  private static String readAll(BufferedReader in) throws IOException {
    var out = new StringBuilder();
    String line;
    while ((line = in.readLine()) != null) {
      out.append(line).append('\n');
      if (line.length() < 4 || line.charAt(3) != '-') {
        break;
      }
    }
    return out.toString().strip();
  }

  /** A multipart message with the plain part first, which is what a reader expects. */
  static String message(String sender, List<String> recipients, String subject, String html,
      String text) {
    var boundary = "redash-" + Long.toHexString(System.nanoTime());
    var out = new StringBuilder();
    out.append("From: ").append(sender).append("\r\n");
    out.append("To: ").append(String.join(", ", recipients)).append("\r\n");
    out.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
    out.append("MIME-Version: 1.0\r\n");
    out.append("Content-Type: multipart/alternative; boundary=\"").append(boundary)
        .append("\"\r\n\r\n");
    if (text != null) {
      out.append("--").append(boundary).append("\r\n");
      out.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
      out.append(text).append("\r\n");
    }
    if (html != null) {
      out.append("--").append(boundary).append("\r\n");
      out.append("Content-Type: text/html; charset=UTF-8\r\n\r\n");
      out.append(html).append("\r\n");
    }
    out.append("--").append(boundary).append("--");
    return out.toString();
  }

  /** A non-ASCII subject travels base64-encoded, the way any mail header does. */
  static String encodeHeader(String value) {
    if (value.chars().allMatch(c -> c < 128)) {
      return value;
    }
    return "=?UTF-8?B?"
        + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
  }
}
