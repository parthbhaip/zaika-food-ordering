package com.zaika.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.common.NotFoundException;

/** Address book rules: one default, the 10-address cap and ownership (someone else's address is "not found"). */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AddressServiceTest {

	@Autowired
	AddressService service;

	@Autowired
	UserRepository users;

	long asha;

	long ravi;

	@BeforeEach
	void createUsers() {
		asha = users.save(new User("Asha", "asha@address-service.zaika.test", "9876543210", "{noop}x", Role.CUSTOMER)).getId();
		ravi = users.save(new User("Ravi", "ravi@address-service.zaika.test", "9876543211", "{noop}x", Role.CUSTOMER)).getId();
	}

	static AddressForm form(AddressLabel label, String house) {
		AddressForm form = new AddressForm();
		form.setLabel(label);
		form.setHouse("  " + house + "  ");
		form.setStreet("Rajarampuri 5th Lane");
		form.setLandmark("   ");
		form.setCity("Kolhapur");
		form.setPincode("416 008");
		return form;
	}

	@Test
	void theFirstAddressBecomesTheDefaultAndFieldsAreCleanedUp() {
		Address home = service.create(asha, form(AddressLabel.HOME, "Flat 4B, Shree Apartments"));
		assertThat(home.isDefaultAddress()).isTrue();
		assertThat(home.getHouse()).isEqualTo("Flat 4B, Shree Apartments");
		assertThat(home.getLandmark()).isNull();
		assertThat(home.getPincode()).isEqualTo("416008");
		AddressForm tabbed = new AddressForm();
		tabbed.setPincode("416\t008 ");
		assertThat(tabbed.getPincode()).isEqualTo("416008");
		assertThat(home.getOneLine()).isEqualTo("Flat 4B, Shree Apartments, Rajarampuri 5th Lane, Kolhapur 416008");

		Address work = service.create(asha, form(AddressLabel.WORK, "Office 12, Station Road"));
		assertThat(work.isDefaultAddress()).isFalse();
		assertThat(service.list(asha)).extracting(Address::getLabel).containsExactly(AddressLabel.HOME, AddressLabel.WORK);
	}

	@Test
	void exactlyOneAddressIsTheDefault() {
		Address home = service.create(asha, form(AddressLabel.HOME, "Flat 4B"));
		AddressForm workForm = form(AddressLabel.WORK, "Office 12");
		workForm.setMakeDefault(true);
		Address work = service.create(asha, workForm);
		assertThat(defaults(asha)).containsExactly(work);

		service.makeDefault(asha, home.getId());
		assertThat(defaults(asha)).containsExactly(home);
		assertThat(service.list(asha).getFirst()).isEqualTo(home);

		AddressForm edit = form(AddressLabel.WORK, "Office 14");
		edit.setMakeDefault(true);
		service.update(asha, work.getId(), edit);
		assertThat(defaults(asha)).containsExactly(work);
		assertThat(work.getHouse()).isEqualTo("Office 14");
	}

	@Test
	void deletingTheDefaultHandsItToTheNewestRemainingAddress() {
		Address home = service.create(asha, form(AddressLabel.HOME, "Flat 4B"));
		service.create(asha, form(AddressLabel.WORK, "Office 12"));
		Address other = service.create(asha, form(AddressLabel.OTHER, "Parents' house"));

		service.delete(asha, home.getId());
		assertThat(service.list(asha)).hasSize(2);
		assertThat(defaults(asha)).containsExactly(other);

		service.delete(asha, other.getId());
		service.delete(asha, service.list(asha).getFirst().getId());
		assertThat(service.list(asha)).isEmpty();
	}

	@Test
	void theAddressBookHoldsAtMostTenAddresses() {
		for (int i = 1; i <= AddressService.MAX_ADDRESSES; i++) {
			assertThat(service.isFull(asha)).isFalse();
			service.create(asha, form(AddressLabel.OTHER, "House " + i));
		}
		assertThat(service.isFull(asha)).isTrue();
		assertThatThrownBy(() -> service.create(asha, form(AddressLabel.OTHER, "House 11")))
			.isInstanceOf(AddressLimitException.class);
		assertThat(service.list(asha)).hasSize(AddressService.MAX_ADDRESSES);
		// the cap is per user
		assertThat(service.isFull(ravi)).isFalse();
	}

	@Test
	void anotherUsersAddressIsNotFoundForEveryOperation() {
		Address ashaHome = service.create(asha, form(AddressLabel.HOME, "Flat 4B"));
		long id = ashaHome.getId();

		assertThatThrownBy(() -> service.get(ravi, id)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.update(ravi, id, form(AddressLabel.WORK, "Taken over")))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.makeDefault(ravi, id)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.delete(ravi, id)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.get(asha, Long.MAX_VALUE)).isInstanceOf(NotFoundException.class);

		assertThat(service.list(ravi)).isEmpty();
		Address unchanged = service.get(asha, id);
		assertThat(unchanged.getHouse()).isEqualTo("Flat 4B");
		assertThat(unchanged.getLabel()).isEqualTo(AddressLabel.HOME);
	}

	private List<Address> defaults(long userId) {
		return service.list(userId).stream().filter(Address::isDefaultAddress).toList();
	}

}
