package com.zaika.notification;

/**
 * Outgoing email. {@link LoggingMailService} writes messages to the log (dev and test, {@code app.mail.log-only=true});
 * {@link SmtpMailService} sends them (mysql profile). Callers compose the plain-text message themselves.
 */
public interface MailService {

	/**
	 * Sends a plain-text email. {@link SmtpMailService} returns at once and sends in the background; an
	 * implementation that sends in the caller's thread throws {@link org.springframework.mail.MailException} on failure.
	 */
	void send(String to, String subject, String text);

}
