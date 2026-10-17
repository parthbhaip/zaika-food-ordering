package com.zaika.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.zaika.notification.MailService;

/** Work an admin decision does once its transaction has committed (never for a rolled-back one). */
final class AfterCommit {

	private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

	private AfterCommit() {
	}

	static void run(Runnable action) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}

	/** Sends the mail after commit; a failure is logged ({@code what} names it) and never undoes the decision. */
	static void mail(MailService mail, String to, String subject, String text, String what) {
		run(() -> {
			try {
				mail.send(to, subject, text);
			}
			catch (MailException ex) {
				log.warn("{} could not be sent: {}", what, ex.getMessage());
			}
		});
	}

}
