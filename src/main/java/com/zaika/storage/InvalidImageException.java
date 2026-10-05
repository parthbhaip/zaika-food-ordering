package com.zaika.storage;

/** An upload that is not an acceptable image. The message is written for the person uploading, ready for a form error. */
public class InvalidImageException extends RuntimeException {

	public InvalidImageException(String message) {
		super(message);
	}

}
