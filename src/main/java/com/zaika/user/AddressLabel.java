package com.zaika.user;

/** What a saved address is called in the address book and at checkout. */
public enum AddressLabel {

	HOME("Home"), WORK("Work"), OTHER("Other");

	private final String displayName;

	AddressLabel(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}

}
