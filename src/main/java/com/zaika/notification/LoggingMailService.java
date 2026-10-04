package com.zaika.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Dev and test mail ({@code app.mail.log-only=true}): nothing is sent, the whole message is logged so a developer can
 * open the link in it, e.g. a password reset link. Never active in the mysql profile, so real one-time links never
 * reach production logs.
 */
@Service
@ConditionalOnProperty(name = "app.mail.log-only", havingValue = "true")
public class LoggingMailService implements MailService {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailService.class);

	@Override
	public void send(String to, String subject, String text) {
		log.info("[mail not sent: log-only] to={} subject=\"{}\"\n{}", to, subject, text);
	}

}
