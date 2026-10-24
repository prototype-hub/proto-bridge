package com.gaiys.proto;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 本地 SMTP 测试端点；只接收 example.invalid，不提供转发或外部网络连接。 */
final class LocalReviewMailbox implements AutoCloseable {
    record Mail(String recipient, String code) {}

    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final BlockingQueue<Mail> messages = new LinkedBlockingQueue<>();
    private volatile boolean closed;
    private volatile boolean reject;
    private volatile CountDownLatch delivery = new CountDownLatch(0);

    LocalReviewMailbox() {
        try {
            listener = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        workers.submit(this::accept);
    }

    int port() {
        return listener.getLocalPort();
    }

    void reset() {
        delivery.countDown();
        delivery = new CountDownLatch(0);
        reject = false;
        messages.clear();
    }

    void reject(boolean value) {
        reject = value;
    }

    void pauseDelivery() {
        delivery = new CountDownLatch(1);
    }

    void deliver() {
        delivery.countDown();
    }

    Mail receive() throws InterruptedException {
        Mail mail = messages.poll(10, TimeUnit.SECONDS);
        if (mail == null) throw new AssertionError("No local verification mail received");
        return mail;
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                workers.submit(() -> handle(socket));
            } catch (IOException error) {
                if (!closed) throw new UncheckedIOException(error);
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
                var input =
                        new BufferedReader(
                                new InputStreamReader(
                                        socket.getInputStream(), StandardCharsets.UTF_8));
                var output =
                        new PrintWriter(
                                new OutputStreamWriter(
                                        socket.getOutputStream(), StandardCharsets.US_ASCII),
                                true)) {
            socket.setSoTimeout(15000);
            reply(output, "220 localhost disposable mailbox");
            String recipient = null;
            for (String line; (line = input.readLine()) != null; ) {
                String verb = line.split(" ", 2)[0].toUpperCase(java.util.Locale.ROOT);
                switch (verb) {
                    case "EHLO", "HELO", "RSET", "NOOP" -> reply(output, "250 localhost");
                    case "MAIL" -> reply(output, "250 OK");
                    case "RCPT" -> {
                        var match =
                                Pattern.compile("(?i)RCPT TO:\\s*<([^<>\\s]+@example\\.invalid)>")
                                        .matcher(line);
                        recipient =
                                match.matches()
                                        ? match.group(1).toLowerCase(java.util.Locale.ROOT)
                                        : null;
                        reply(
                                output,
                                recipient != null ? "250 OK" : "550 Fixture recipient required");
                    }
                    case "DATA" -> {
                        if (reject || recipient == null) {
                            reply(output, "554 Local fixture rejection");
                            continue;
                        }
                        reply(output, "354 End with a dot");
                        var content = new StringBuilder();
                        while ((line = input.readLine()) != null && !line.equals(".")) {
                            if (content.length() + line.length() > 65536) return;
                            content.append(line.startsWith("..") ? line.substring(1) : line)
                                    .append("\r\n");
                        }
                        MimeMessage mime =
                                new MimeMessage(
                                        Session.getInstance(new Properties()),
                                        new ByteArrayInputStream(
                                                content.toString()
                                                        .getBytes(StandardCharsets.UTF_8)));
                        String text = text(mime);
                        var codes = Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])").matcher(text);
                        if (!codes.find()) {
                            reply(output, "550 Verification code required");
                            continue;
                        }
                        messages.add(new Mail(recipient, codes.group()));
                        if (!delivery.await(15, TimeUnit.SECONDS)) return;
                        reply(output, "250 Stored locally");
                    }
                    case "QUIT" -> {
                        reply(output, "221 Bye");
                        return;
                    }
                    default -> reply(output, "502 Unsupported");
                }
            }
        } catch (Exception error) {
            if (!closed) throw new IllegalStateException("Local SMTP fixture failed", error);
        }
    }

    private static String text(jakarta.mail.Part part) throws Exception {
        if (part.isMimeType("text/plain")) return part.getContent().toString();
        if (part.isMimeType("multipart/*")) {
            var multipart = (jakarta.mail.Multipart) part.getContent();
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++)
                result.append(text(multipart.getBodyPart(i)));
            return result.toString();
        }
        return "";
    }

    private static void reply(PrintWriter output, String line) {
        output.print(line + "\r\n");
        output.flush();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        deliver();
        listener.close();
        workers.shutdownNow();
        try {
            workers.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
