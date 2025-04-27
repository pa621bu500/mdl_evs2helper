package org.pabuff.evs2helper.email;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;

@Component
public class SystemNotifier {
    @Autowired
    private EmailService emailService;
    @Value("${system.notifier.email.from}")
    private String emailFrom;
    @Value("${system.notifier.email.to}")
    private String emailTo;

    public void sendEmail(String subject, String text) {
        emailService.sendSimpleEmail(emailFrom, emailTo, subject, text);
    }
    public void sendEmail(String from, String to, String subject, String text) {
        emailService.sendSimpleEmail(from, to, subject, text);
    }
    public void sendException(String subject, String source, String errorMessage) {
        String text = "Source: " + source + "\n" + "Error Message: " + errorMessage;
        emailService.sendSimpleEmail(emailFrom, emailTo, subject, text);
    }
    public void sendNotice(String subject, String source, Map<String, String> message) {
        String title = message.get("title") == null ? "Message" : message.get("title");
        String text = "Source: " + source + "\n" + title+": " + message.get("message");
        emailService.sendSimpleEmail(emailFrom, emailTo, subject, text);
    }
    public void sendEmailWithAttachment(String senderName, String emailTo, String subject, String text, File attachedFile, boolean isHtml) {
        emailService.sendEmailWithAttachment(emailFrom, senderName, emailTo, subject, text, attachedFile, isHtml);
    }
    public void sendEmailWithAttachmentCC(String senderName,
                                          String emailTo, String[] cc,
                                          String subject, String text, File attachedFile, boolean isHtml) {
        emailService.sendEmailWithAttachmentCC(emailFrom, senderName, emailTo, cc, subject, text, attachedFile, isHtml);
    }
}
