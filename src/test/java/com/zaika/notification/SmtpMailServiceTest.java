package com.zaika.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** SmtpMailService hands a plain-text message with the configured sender to Spring's JavaMailSender. */
class SmtpMailServiceTest {

	@Test
	void sendsAPlainTextMessageFromTheConfiguredSender() {
		JavaMailSender sender = mock(JavaMailSender.class);
		new SmtpMailService(sender, "Zaika <no-reply@zaika.test>").send("asha@zaika.test", "Reset your Zaika password",
				"Choose a new password here: https://zaika.example.in/reset-password?token=abc");

		ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(sender).send(sent.capture());
		assertThat(sent.getValue().getFrom()).isEqualTo("Zaika <no-reply@zaika.test>");
		assertThat(sent.getValue().getTo()).containsExactly("asha@zaika.test");
		assertThat(sent.getValue().getSubject()).isEqualTo("Reset your Zaika password");
		assertThat(sent.getValue().getText()).endsWith("reset-password?token=abc");
	}

}
