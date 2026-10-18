package com.zaika.review;

/** A review action that does not fit the current state (not delivered yet, already rated or replied); the message is for the user. */
public class ReviewException extends RuntimeException {

	ReviewException(String message) {
		super(message);
	}

}
