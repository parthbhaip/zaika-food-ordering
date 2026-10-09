package com.zaika.delivery;

import java.util.Locale;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.zaika.common.BaseEntity;
import com.zaika.user.Role;
import com.zaika.user.User;

/**
 * A delivery partner's working details: vehicle, number plate, the area they like to ride in, their review status
 * and whether they are online. One per DELIVERY_PARTNER account. New riders start PENDING and offline; only an
 * APPROVED rider can be online, so approving never puts anyone online and suspending takes them offline.
 */
@Entity
@Table(name = "delivery_partner_profiles")
public class DeliveryPartnerProfile extends BaseEntity {

	/**
	 * An Indian number plate without spaces: state, district, series, number ("MH09AB1234", "MH9A123") or the
	 * Bharat series ("22BH1234AB").
	 */
	public static final String NUMBER_PLATE_PATTERN = "[A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{1,4}|[0-9]{2}BH[0-9]{4}[A-Z]{1,2}";

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false, unique = true)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(name = "vehicle_type", nullable = false, length = 10)
	private VehicleType vehicleType;

	@Pattern(regexp = NUMBER_PLATE_PATTERN)
	@Column(name = "vehicle_number", length = 12)
	private String vehicleNumber;

	@NotBlank
	@Size(max = 60)
	@Column(name = "preferred_area", nullable = false, length = 60)
	private String preferredArea;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private RiderStatus status = RiderStatus.PENDING;

	@Size(max = 500)
	@Column(name = "status_reason", length = 500)
	private String statusReason;

	@Column(nullable = false)
	private boolean online;

	protected DeliveryPartnerProfile() {
	}

	public DeliveryPartnerProfile(User user, VehicleType vehicleType, String vehicleNumber, String preferredArea) {
		Objects.requireNonNull(user, "user");
		if (user.getRole() != Role.DELIVERY_PARTNER) {
			throw new IllegalArgumentException("A rider profile needs a DELIVERY_PARTNER account");
		}
		this.user = user;
		setVehicle(vehicleType, vehicleNumber);
		this.preferredArea = Objects.requireNonNull(preferredArea, "preferredArea").strip();
	}

	/** "mh 09-ab 1234" -> "MH09AB1234"; blank -> {@code null}. */
	public static String normalizeNumberPlate(String raw) {
		if (raw == null) {
			return null;
		}
		String plate = raw.replaceAll("[\\s.-]", "").toUpperCase(Locale.ROOT);
		return plate.isEmpty() ? null : plate;
	}

	/** Sets the vehicle; a bicycle keeps no number, any other vehicle must have one. */
	public void setVehicle(VehicleType type, String number) {
		Objects.requireNonNull(type, "vehicleType");
		String plate = type.hasNumberPlate() ? normalizeNumberPlate(number) : null;
		if (type.hasNumberPlate() && plate == null) {
			throw new IllegalArgumentException(type + " needs a number plate");
		}
		this.vehicleType = type;
		this.vehicleNumber = plate;
	}

	/** Approve, or suspend with a reason, or put back to review. Anything but APPROVED goes offline. */
	public void changeStatus(RiderStatus status, String reason) {
		Objects.requireNonNull(status, "status");
		if (status.needsReason() && (reason == null || reason.isBlank())) {
			throw new IllegalArgumentException(status + " needs a reason");
		}
		this.status = status;
		this.statusReason = status.needsReason() ? reason.strip() : null;
		if (status != RiderStatus.APPROVED) {
			this.online = false;
		}
	}

	/** @throws IllegalStateException when a rider who is not approved tries to go online */
	public void setOnline(boolean online) {
		if (online && status != RiderStatus.APPROVED) {
			throw new IllegalStateException("Only approved riders can go online");
		}
		this.online = online;
	}

	public User getUser() {
		return user;
	}

	public VehicleType getVehicleType() {
		return vehicleType;
	}

	public String getVehicleNumber() {
		return vehicleNumber;
	}

	public String getPreferredArea() {
		return preferredArea;
	}

	public RiderStatus getStatus() {
		return status;
	}

	public String getStatusReason() {
		return statusReason;
	}

	public boolean isOnline() {
		return online;
	}

}
