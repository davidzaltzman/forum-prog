// ForumNotifier.java

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import jakarta.mail.*;
import jakarta.mail.internet.*;

public class ForumNotifier {

    private static final String LAST_MESSAGE_FILE = "last.txt";
    private static final int PAGES_TO_SCAN = 3;

    public static void main(String[] args) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            List<String> allMessages = new ArrayList<>();
            List<String> newMessages;
            int lastPage = getLastPage(client);

            for (int i = lastPage - PAGES_TO_SCAN + 1; i <= lastPage; i++) {
                String url = "https://www.prog.co.il/threads/%D7%A2%D7%93%D7%9B%D7%95%D7%A0%D7%99%D7%9D-%D7%91%D7%9C%D7%91%D7%93.917045/page-" + i;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(url))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 == 3) {
                    String newUrl = response.headers().firstValue("Location").orElse(null);
                    if (newUrl != null) {
                        request = HttpRequest.newBuilder().uri(new URI(newUrl)).GET().build();
                        response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    }
                }

                Document doc = Jsoup.parse(response.body());
                Elements wrappers = doc.select("div.bbWrapper");

                for (Element wrapper : wrappers) {
                    Element quote = wrapper.selectFirst("blockquote.bbCodeBlock--quote");
                    Element replyExpand = wrapper.selectFirst("div.bbCodeBlock-expandLink");
                    boolean hasQuote = quote != null && replyExpand != null;

                    Elements spoilers = wrapper.select("div.bbCodeBlock.bbCodeBlock--spoiler");

                    boolean likelyAd = false;
                    for (Element el : wrapper.select("span[style], img, iframe, script, blockquote blockquote, div.quoteExpand")) {
                        if (el.tagName().equals("span") && "9px".equals(el.attr("style").replaceAll("\\s", "").replace("font-size:", "")) && el.text().contains("קרדיט: הכריש")) {
                            if (!el.parents().select("div.bbWrapper").contains(el.parent())) {
                                likelyAd = true;
                                break;
                            }
                        } else {
                            likelyAd = true;
                            break;
                        }
                    }

                    Elements links = wrapper.select("a");
                    if (!links.isEmpty() && !hasQuote) {
                        likelyAd = true;
                    }
                    if (likelyAd) continue;

                    StringBuilder messageBuilder = new StringBuilder();

                    if (hasQuote) {
                        String quoteAuthor = quote.attr("data-quote");
                        Element quoteContent = quote.selectFirst(".bbCodeBlock-content");
                        String quoteText = quoteContent != null ? quoteContent.text().trim() : "";

                        messageBuilder.append("<div style='border: 1px solid #99d6ff; border-radius: 10px; padding: 10px; margin-bottom: 10px; background: #e6f7ff;'>")
                                .append("🌟 <b>ציטוט מאת</b> ").append(quoteAuthor).append(":<br>")
                                .append("<i>").append(quoteText.replaceAll("\\n", "<br>")).append("</i>")
                                .append("</div>");

                        quote.remove();
                        replyExpand.remove();

                        String replyText = wrapper.text().trim();
                        if (!replyText.isEmpty()) {
                            messageBuilder.append("<div style='border: 1px solid #a9dfbf; border-radius: 10px; padding: 10px; background: #eafaf1;'>")
                                    .append("🗨️ <b>תגובה:</b><br>")
                                    .append(replyText.replaceAll("\\n", "<br>"))
                                    .append("</div>");
                        }
                    } else {
                        for (Element spoiler : spoilers) {
                            spoiler.remove();
                        }

                        String text = wrapper.text().trim();
                        if (!text.isEmpty()) {
                            messageBuilder.append("<div style='border: 1px solid #a9dfbf; border-radius: 10px; padding: 10px; background: #eafaf1;'>")
                                    .append(text.replaceAll("\\n", "<br>"))
                                    .append("</div>");
                        }

                        for (Element spoiler : spoilers) {
                            Element spoilerTitle = spoiler.selectFirst(".bbCodeBlock-title");
                            Element spoilerContent = spoiler.selectFirst(".bbCodeBlock-content");

                            String title = spoilerTitle != null ? spoilerTitle.text().trim() : "ספוילר";
                            String content = spoilerContent != null ? spoilerContent.text().trim() : "";

                            if (!content.isEmpty()) {
                                messageBuilder.append("<div style='margin-top: 10px; background: #f5f5f5; border-left: 5px solid #999; padding: 10px; border-radius: 8px;'>")
                                        .append("<b>").append(title).append(":</b><br>")
                                        .append("<span style='color: #333;'>").append(content.replaceAll("\\n", "<br>")).append("</span>")
                                        .append("</div>");
                            }
                        }
                    }

                    if (messageBuilder.length() > 0) {
                        allMessages.add(messageBuilder.toString());
                    }
                }
            }

            newMessages = getNewMessages(allMessages);

            if (!newMessages.isEmpty()) {
                writeLatestMessages(allMessages);
                sendEmail(newMessages);
            } else {
                sendEmail(Collections.singletonList("<i>אין הודעות חדשות.</i>"));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<String> readPreviousMessages() {
        try {
            return Files.readAllLines(Path.of(LAST_MESSAGE_FILE));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static List<String> getNewMessages(List<String> allMessages) throws IOException {
        List<String> previousMessages = readPreviousMessages();
        List<String> newMessages = new ArrayList<>();
        for (String message : allMessages) {
            String messageId = getMessageId(message);
            if (!previousMessages.contains(messageId)) {
                newMessages.add(message);
            }
        }
        return newMessages;
    }

    private static String getMessageId(String message) {
        return message.hashCode() + "";
    }

    private static void writeLatestMessages(List<String> messages) {
        try {
            List<String> messageIds = new ArrayList<>();
            for (String message : messages) {
                messageIds.add(getMessageId(message));
            }
            Files.write(Path.of(LAST_MESSAGE_FILE), messageIds, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("שגיאה בכתיבת הקובץ: " + e.getMessage());
        }
    }

    private static void sendEmail(List<String> messages) {
        // ✅ שינוי: שורת כתובת, משתמש וסיסמה נמשכים מה־GitHub Secrets
        String to = System.getenv("EMAIL_TO");
        String from = System.getenv("EMAIL_FROM");
        String password = System.getenv("EMAIL_PASSWORD");

        Properties props = new Properties();
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, password);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));

            // שליחה לכמה כתובות
            String[] recipients = to.split(",");
            InternetAddress[] recipientAddresses = new InternetAddress[recipients.length];
            for (int i = 0; i < recipients.length; i++) {
                recipientAddresses[i] = new InternetAddress(recipients[i].trim());
            }

            message.setRecipients(Message.RecipientType.TO, recipientAddresses);
            message.setSubject("\uD83D\uDCEC עדכונים מהפורום פרוג");

            StringBuilder emailBody = new StringBuilder("<html><body style='font-family: Arial; direction: rtl;'>");
            for (String msg : messages) {
                emailBody.append("<div style='border: 1px solid #ccc; border-radius: 10px; padding: 10px; margin-bottom: 15px;'>")
                        .append(msg)
                        .append("</div>");
            }
            emailBody.append("</body></html>");

            message.setContent(emailBody.toString(), "text/html; charset=UTF-8");
            Transport.send(message);
            System.out.println("המייל נשלח בהצלחה!");

        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }

    private static int getLastPage(HttpClient client) throws Exception {
        String url = "https://www.prog.co.il/threads/%D7%A2%D7%93%D7%9B%D7%95%D7%A0%D7%99%D7%9D-%D7%91%D7%9C%D7%91%D7%93.917045/page-9999";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 == 3) {
            String newUrl = response.headers().firstValue("Location").orElse(null);
            if (newUrl != null) {
                String[] parts = newUrl.split("page-");
                return Integer.parseInt(parts[1].split("/")[0]);
            }
        }
        return 1;
    }
}
