package com.zaika.delivery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.user.EmailTakenException;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserService;

/**
 * Rider sign-up. One transaction creates the DELIVERY_PARTNER account and a PENDING,
 * offline profile; an admin approves it before the rider can go online.
 */
@Service
public class RiderOnboardingService {

	private final UserService userService;

	private final DeliveryPartnerProfileRepository profiles;

	RiderOnboardingService(UserService userService, DeliveryPartnerProfileRepository profiles) {
		this.userService = userService;
		this.profiles = profiles;
	}

	/**
	 * Creates the rider's account and profile from a validated form.
	 *
	 * @throws EmailTakenException if the email already has an account (nothing is saved)
	 */
	@Transactional
	public DeliveryPartnerProfile register(RiderSignupForm form) {
		User rider = userService.registerAccount(form.getName(), form.getEmail(), form.getPhone(), form.getPassword(),
				Role.DELIVERY_PARTNER);
		return profiles.save(new DeliveryPartnerProfile(rider, form.getVehicleType(), form.getVehicleNumber(),
				form.getPreferredArea()));
	}

}
