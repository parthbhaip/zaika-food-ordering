package com.zaika.delivery;

/** What a delivery partner rides. Bikes and scooters carry a number plate; a bicycle has none. */
public enum VehicleType {

	BIKE("Motorbike", "Fastest across town, takes the longer runs."),
	SCOOTER("Scooter", "Easy in traffic, room for a big order."),
	BICYCLE("Bicycle", "No licence needed, best for short runs.");

	private final String label;

	private final String description;

	VehicleType(String label, String description) {
		this.label = label;
		this.description = description;
	}

	public String getLabel() {
		return label;
	}

	public String getDescription() {
		return description;
	}

	/** Bikes and scooters are registered vehicles with a number plate. */
	public boolean hasNumberPlate() {
		return this != BICYCLE;
	}

}
