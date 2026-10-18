package com.zaika.notification;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Dev and test mail ({@code app.mail.log-only=true}): nothing is sent, the message is logged so a developer can open
 * the link in it, e.g. a password reset link. Phone numbers and one-time codes are masked first ({@link #mask}), so
 * they never reach a log. Never active in the mysql profile, so real one-time links never reach production logs.
 */
@Service
@ConditionalOnProperty(name = "app.mail.log-only", havingValue = "true")
public class LoggingMailService implements MailService {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailService.class);

	/** An Indian mobile number, with or without +91 and a space or dash in the middle; not part of a longer token. */
	private static final Pattern PHONE = Pattern
		.compile("(?<![\\w-])(?:\\+91[\\s-]?)?[6-9]\\d{4}[\\s-]?\\d{3}(\\d{2})(?![\\w-])");

	/** "OTP 1234", "code: 482913", "delivery code is 0042". */
	private static final Pattern ONE_TIME_CODE = Pattern
		.compile("(?i)\\b(otp|code)(\\s*(?:is|:)?\\s*)\\d{4,6}(?![\\w-])");

	@Override
	public void send(String to, String subject, String text) {
		log.info("[mail not sent: log-only] to={} subject=\"{}\"\n{}", to, mask(subject), mask(text));
	}

	/** {@code text} with phone numbers cut to their last two digits and one-time codes replaced by asterisks. */
	static String mask(String text) {
		if (text == null) {
			return null;
		}
		String masked = PHONE.matcher(text).replaceAll("******** $1");
		return ONE_TIME_CODE.matcher(masked).replaceAll("$1$2****");
	}

}
