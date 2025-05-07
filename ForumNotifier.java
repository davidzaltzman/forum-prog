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

    private static final String LAST_MESSAGE_FILE = "last.txt"; // שם הקובץ שבו נשמרות הודעות שנקראו
    private static final int PAGES_TO_SCAN = 3; // מספר העמודים לסרוק אחריהם להודעות חדשות

    public static void main(String[] args) {
        try {
            // יצירת HttpClient לשליחת בקשות HTTP
            HttpClient client = HttpClient.newHttpClient();
            List<String> allMessages = new ArrayList<>(); // רשימה לאחסון כל ההודעות שנמצאות
            List<String> newMessages; // רשימה לאחסון ההודעות החדשות שנמצאו
            int lastPage = getLastPage(client); // מקבלים את מספר העמוד האחרון

            // לולאה לסרוק את שלושת העמודים האחרונים
            for (int i = lastPage - PAGES_TO_SCAN + 1; i <= lastPage; i++) {
                // יוצרים כתובת URL עבור כל עמוד
                String url = "https://www.prog.co.il/threads/%D7%A2%D7%93%D7%9B%D7%95%D7%A0%D7%99%D7%9D-%D7%91%D7%9C%D7%91%D7%93.917045/page-" + i;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(url)) // מבקשים את הדף
                        .GET()
                        .build();

                // שולחים את הבקשה ומקבלים את התגובה
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                // במקרה של הפניה מחדש (redirect), שולחים בקשה חדשה לכתובת המפנה
                if (response.statusCode() / 100 == 3) {
                    String newUrl = response.headers().firstValue("Location").orElse(null);
                    if (newUrl != null) {
                        request = HttpRequest.newBuilder().uri(new URI(newUrl)).GET().build();
                        response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    }
                }

                // מנתחים את הדף עם Jsoup
                Document doc = Jsoup.parse(response.body());
                Elements wrappers = doc.select("div.bbWrapper"); // מוצאים את כל ההודעות

                // לולאה על כל ההודעות בעמוד
                for (Element wrapper : wrappers) {
                    // מחפשים ציטוטים בתגית <blockquote>
                    Element quote = wrapper.selectFirst("blockquote.bbCodeBlock--quote");
                    Element replyExpand = wrapper.selectFirst("div.bbCodeBlock-expandLink");
                    boolean hasQuote = quote != null && replyExpand != null;

                    // בודקים אם מדובר בפרסומות לפי הגדרת האלמנטים
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

                    // אם יש קישורים ללא ציטוט, סווג כפרסומת
                    Elements links = wrapper.select("a");
                    if (!links.isEmpty() && !hasQuote) {
                        likelyAd = true;
                    }
                    if (likelyAd) continue; // אם מדובר בפרסומת, מקפלים את ההודעה

                    // בניית ההודעה שתישלח במייל
                    StringBuilder messageBuilder = new StringBuilder();

                    // אם יש ציטוט, נוסיף אותו להודעה
                    if (hasQuote) {
                        String quoteAuthor = quote.attr("data-quote");
                        Element quoteContent = quote.selectFirst(".bbCodeBlock-content");
                        String quoteText = quoteContent != null ? quoteContent.text().trim() : "";

                        messageBuilder.append("<div style='border: 1px solid #99d6ff; border-radius: 10px; padding: 10px; margin-bottom: 10px; background: #e6f7ff;'>")
                                .append("🌟 <b>ציטוט מאת</b> ").append(quoteAuthor).append(":<br>")
                                .append("<i>").append(quoteText.replaceAll("\\n", "<br>")).append("</i>")
                                .append("</div>");

                        // מסירים את הציטוט וההרחבה לאחר מכן
                        quote.remove();
                        replyExpand.remove();

                        // בודקים אם יש תגובה לציטוט
                        String replyText = wrapper.text().trim();
                        if (!replyText.isEmpty()) {
                            messageBuilder.append("<div style='border: 1px solid #a9dfbf; border-radius: 10px; padding: 10px; background: #eafaf1;'>")
                                    .append("🗨️ <b>תגובה:</b><br>")
                                    .append(replyText.replaceAll("\\n", "<br>"))
                                    .append("</div>");
                        }
                    } else {
                        // אם אין ציטוט, נוסיף את ההודעה כרגיל
                        for (Element spoiler : spoilers) {
                            spoiler.remove();
                        }

                        String text = wrapper.text().trim();
                        if (!text.isEmpty()) {
                            messageBuilder.append("<div style='border: 1px solid #a9dfbf; border-radius: 10px; padding: 10px; background: #eafaf1;'>")
                                    .append(text.replaceAll("\\n", "<br>"))
                                    .append("</div>");
                        }
                    }

                    // אם יש תוכן בהודעה, נוסיף אותה לרשימה
                    if (messageBuilder.length() > 0) {
                        allMessages.add(messageBuilder.toString());
                    }
                }
            }

            // קבלת ההודעות החדשות
            newMessages = getNewMessages(allMessages);

            // אם יש הודעות חדשות, נשלח אותן במייל
            if (!newMessages.isEmpty()) {
                writeLatestMessages(allMessages); // שמירת ההודעות החדשות בקובץ
                sendEmail(newMessages); // שליחת המייל עם ההודעות החדשות
            } else {
                sendEmail(Collections.singletonList("<i>אין הודעות חדשות.</i>")); // אם אין הודעות חדשות
            }

        } catch (Exception e) {
            e.printStackTrace(); // אם יש שגיאה, נדפיס אותה
        }
    }

    // קריאה להודעות שנשמרו מהפעם הקודמת
    private static List<String> readPreviousMessages() {
        try {
            return Files.readAllLines(Path.of(LAST_MESSAGE_FILE)); // קוראים את ההודעות מקובץ
        } catch (IOException e) {
            return new ArrayList<>(); // אם אין קובץ, מחזירים רשימה ריקה
        }
    }

    // קבלת הודעות חדשות שלא היו בהודעות הקודמות
    private static List<String> getNewMessages(List<String> allMessages) throws IOException {
        List<String> previousMessages = readPreviousMessages(); // קריאת ההודעות הקודמות
        List<String> newMessages = new ArrayList<>(); // רשימה להודעות חדשות
        for (String message : allMessages) {
            String messageId = getMessageId(message); // הוצאת מזהה הודעה
            if (!previousMessages.contains(messageId)) {
                newMessages.add(message); // אם ההודעה לא נמצאת ברשימה הקודמת, היא חדשה
            }
        }
        return newMessages;
    }

    // יצירת מזהה חד־חד ערכי לכל הודעה
    private static String getMessageId(String message) {
        return message.hashCode() + ""; // מזהה על פי ערך ה־hash של ההודעה
    }

    // כתיבת מזהי ההודעות החדשות לקובץ
    private static void writeLatestMessages(List<String> messages) {
        try {
            List<String> messageIds = new ArrayList<>();
            for (String message : messages) {
                messageIds.add(getMessageId(message)); // הוצאת מזהים מההודעות
            }
            Files.write(Path.of(LAST_MESSAGE_FILE), messageIds, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); // שמירתם לקובץ
        } catch (IOException e) {
            System.err.println("שגיאה בכתיבת הקובץ: " + e.getMessage());
        }
    }

    // פונקציה לשליחת המייל
    private static void sendEmail(List<String> messages) {
        // קריאת כתובת מייל, משתמש וסיסמה מ־GitHub Secrets
        String to = System.getenv("EMAIL_TO"); // כתובת המייל אליה יש לשלוח
        String from = System.getenv("EMAIL_FROM"); // כתובת המייל השולחת
        String password = System.getenv("EMAIL_PASSWORD"); // סיסמת המייל השולחת

        // הגדרת פרופרטים לחיבור ל־SMTP
        Properties props = new Properties();
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        // יצירת session עם אישור כניסה למייל
        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, password); // משתמש וסיסמה
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));

            // שליחה לכמה כתובות
            String[] recipients = to.split(","); // מחלקים את הכתובות לפי פסיק
            for (String recipient : recipients) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient.trim())); // שולחים לכל כתובת
            }

            message.setSubject("\uD83D\uDCEC עדכונים מהפורום פרוג");

            // יצירת תוכן המייל עם כל ההודעות החדשות
            StringBuilder emailBody = new StringBuilder("<html><body style='font-family: Arial; direction: rtl;'>");
            for (String msg : messages) {
                emailBody.append("<div style='border: 1px solid #ccc; border-radius: 10px; padding: 10px; margin-bottom: 15px;'>")
                        .append(msg) // מוסיפים את התוכן של כל הודעה
                        .append("</div>");
            }
            emailBody.append("</body></html>");

            message.setContent(emailBody.toString(), "text/html; charset=UTF-8"); // הגדרת התוכן כ־HTML
            Transport.send(message); // שולחים את המייל
            System.out.println("המייל נשלח בהצלחה!");

        } catch (MessagingException e) {
            e.printStackTrace(); // אם יש שגיאה, נדפיס אותה
        }
    }

    // פונקציה לקבלת מספר העמוד האחרון
    private static int getLastPage(HttpClient client) throws Exception {
        String url = "https://www.prog.co.il/threads/%D7%A2%D7%93%D7%9B%D7%95%D7%A0%D7%99%D7%9D-%D7%91%D7%9C%D7%91%D7%93.917045/page-9999"; // URL לעמוד אחרון
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // במקרה של redirect, קבל את הכתובת החדשה
        if (response.statusCode() / 100 == 3) {
            String newUrl = response.headers().firstValue("Location").orElse(null);
            if (newUrl != null) {
                String[] parts = newUrl.split("page-");
                return Integer.parseInt(parts[1].split("/")[0]); // מחזירים את מספר העמוד
            }
        }
        return 1; // אם אין redirect, מח
