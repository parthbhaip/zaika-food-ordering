package com.zaika.notification;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Mail sender for dev and test ({@code app.mail.log-only=true}): logs recipient and subject instead of sending.
 * Bodies are not logged because they may carry one-time links. Other profiles get Spring Boot's SMTP sender.
 */
@Component
@ConditionalOnProperty(name = "app.mail.log-only", havingValue = "true")
public class LoggingMailSender implements JavaMailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

	private final Session session = Session.getInstance(new Properties());

	@Override
	public MimeMessage createMimeMessage() {
		return new MimeMessage(session);
	}

	@Override
	public MimeMessage createMimeMessage(InputStream contentStream) {
		try {
			return new MimeMessage(session, contentStream);
		}
		catch (MessagingException ex) {
			throw new MailParseException("Could not parse MIME message", ex);
		}
	}

	@Override
	public void send(MimeMessage... mimeMessages) {
		for (MimeMessage message : mimeMessages) {
			try {
				Address[] to = message.getAllRecipients();
				log.info("[mail not sent: log-only] to={} subject=\"{}\"", to == null ? "[]" : Arrays.toString(to),
						message.getSubject());
			}
			catch (MessagingException ex) {
				throw new MailParseException("Could not read MIME message", ex);
			}
		}
	}

	@Override
	public void send(SimpleMailMessage... simpleMessages) {
		for (SimpleMailMessage message : simpleMessages) {
			log.info("[mail not sent: log-only] to={} subject=\"{}\"", Arrays.toString(message.getTo()),
					message.getSubject());
		}
	}

}
