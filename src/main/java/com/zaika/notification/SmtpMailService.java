package com.zaika.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Real mail over SMTP (mysql profile), through the {@link JavaMailSender} Spring Boot builds from {@code spring.mail.*}.
 * Sent on a background thread: the page does not wait for the mail server, and "forgot password" answers just as fast
 * for an unknown email as for a real account. A failure is logged by Spring's async error handler.
 */
@Service
@ConditionalOnProperty(name = "app.mail.log-only", havingValue = "false", matchIfMissing = true)
public class SmtpMailService implements MailService {

	private final JavaMailSender sender;

	private final String from;

	SmtpMailService(JavaMailSender sender, @Value("${app.mail.from}") String from) {
		this.sender = sender;
		this.from = from;
	}

	@Async
	@Override
	public void send(String to, String subject, String text) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(text);
		sender.send(message);
	}

}
