package com.zaika.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.zaika.user.Role;
import com.zaika.user.User;

/** T34: the rider profile's own rules (number plates, review status, online only when approved). */
class DeliveryPartnerProfileTest {

	static final User RIDER = new User("Rahul Pawar", "rahul@riders.zaika.test", "9890011221", "hash",
			Role.DELIVERY_PARTNER);

	@Test
	void numberPlatesAreStoredWithoutSpacesInCapitals() {
		assertThat(DeliveryPartnerProfile.normalizeNumberPlate(" mh 09-ab.1234 ")).isEqualTo("MH09AB1234");
		assertThat(DeliveryPartnerProfile.normalizeNumberPlate("   ")).isNull();
		assertThat(DeliveryPartnerProfile.normalizeNumberPlate(null)).isNull();
		assertThat("MH09AB1234").matches(DeliveryPartnerProfile.NUMBER_PLATE_PATTERN);
		assertThat("MH9A123").matches(DeliveryPartnerProfile.NUMBER_PLATE_PATTERN);
		assertThat("22BH1234AB").matches(DeliveryPartnerProfile.NUMBER_PLATE_PATTERN);
		assertThat("MH09AB12345").doesNotMatch(DeliveryPartnerProfile.NUMBER_PLATE_PATTERN);
		assertThat("1234").doesNotMatch(DeliveryPartnerProfile.NUMBER_PLATE_PATTERN);
	}

	@Test
	void aNewRiderIsPendingAndOffline() {
		DeliveryPartnerProfile p = new DeliveryPartnerProfile(RIDER, VehicleType.SCOOTER, "mh 09 fg 7310", " Shahupuri ");
		assertThat(p.getStatus()).isEqualTo(RiderStatus.PENDING);
		assertThat(p.isOnline()).isFalse();
		assertThat(p.getVehicleNumber()).isEqualTo("MH09FG7310");
		assertThat(p.getPreferredArea()).isEqualTo("Shahupuri");
	}

	@Test
	void aBicycleHasNoNumberAndMotorVehiclesNeedOne() {
		assertThat(new DeliveryPartnerProfile(RIDER, VehicleType.BICYCLE, "MH09AB1234", "Rankala").getVehicleNumber())
			.isNull();
		assertThatThrownBy(() -> new DeliveryPartnerProfile(RIDER, VehicleType.BIKE, " ", "Rankala"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void onlyDeliveryPartnerAccountsGetAProfile() {
		User customer = new User("Riya Shinde", "riya@riders.zaika.test", "9890011230", "hash", Role.CUSTOMER);
		assertThatThrownBy(() -> new DeliveryPartnerProfile(customer, VehicleType.BIKE, "MH09AB1234", "Rankala"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void onlyApprovedRidersGoOnlineAndASuspensionTakesThemOffline() {
		DeliveryPartnerProfile p = new DeliveryPartnerProfile(RIDER, VehicleType.BIKE, "MH09AB1234", "Rankala");
		assertThatThrownBy(() -> p.setOnline(true)).isInstanceOf(IllegalStateException.class);
		p.setOnline(false);

		p.changeStatus(RiderStatus.APPROVED, "ignored");
		assertThat(p.getStatusReason()).isNull();
		p.setOnline(true);
		assertThat(p.isOnline()).isTrue();

		assertThatThrownBy(() -> p.changeStatus(RiderStatus.SUSPENDED, "  ")).isInstanceOf(IllegalArgumentException.class);
		assertThat(p.getStatus()).isEqualTo(RiderStatus.APPROVED);
		p.changeStatus(RiderStatus.SUSPENDED, " Three late deliveries this week. ");
		assertThat(p.getStatusReason()).isEqualTo("Three late deliveries this week.");
		assertThat(p.isOnline()).isFalse();
		assertThatThrownBy(() -> p.setOnline(true)).isInstanceOf(IllegalStateException.class);
	}

}
