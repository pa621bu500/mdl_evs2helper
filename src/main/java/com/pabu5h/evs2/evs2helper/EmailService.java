package com.pabu5h.evs2.evs2helper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;


@Component
public class EmailService {
    private final Logger logger = Logger.getLogger(EmailService.class.getName());
    @Autowired
    private JavaMailSender mailSender;

//    public EmailService(JavaMailSender mailSender) {
//        this.mailSender = mailSender;
//    }
    public void sendSimpleEmail(String from, String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
        }catch (Exception e){
            logger.info("mailSender error: " + e.getMessage());
        }
    }
}
