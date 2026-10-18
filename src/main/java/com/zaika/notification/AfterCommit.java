package com.zaika.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Work a change does once its transaction has committed (never for a rolled-back one), such as sending the email
 * about it. Outside a transaction it runs at once.
 */
public final class AfterCommit {

	private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

	private AfterCommit() {
	}

	public static void run(Runnable action) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			action.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}

	/** Sends the mail after commit; a failure is logged ({@code what} names it) and never undoes the change. */
	public static void mail(MailService mail, String to, String subject, String text, String what) {
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
