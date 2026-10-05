package com.zaika.config;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.zaika.catalog.RestaurantStatus;
import com.zaika.user.Role;

/**
 * The demo catalogue for Kolhapur: cuisines, demo accounts, 12 approved restaurants and one awaiting approval. Every
 * restaurant and person is fictional; the neighbourhoods are real. Loaded by {@link DemoDataSeeder}.
 */
final class DemoData {

	/** Password of every demo account (CLAUDE.md). Dev seed only. */
	static final String PASSWORD = "Demo@1234";

	static final String CITY = "Kolhapur";

	record Account(String email, String name, String phone, Role role) {
	}

	record Dish(String name, BigDecimal price, boolean veg, String description, boolean bestseller, int spiceLevel,
			boolean soldOut) {

		Dish best() {
			return new Dish(name, price, veg, description, true, spiceLevel, soldOut);
		}

		Dish spice(int level) {
			return new Dish(name, price, veg, description, bestseller, level, soldOut);
		}

		Dish unavailable() {
			return new Dish(name, price, veg, description, bestseller, spiceLevel, true);
		}

	}

	record Section(String name, List<Dish> dishes) {

		Section {
			dishes = List.copyOf(dishes);
		}

	}

	/** One restaurant with its owner and menu. Immutable; built fluently by {@link PlaceBuilder} below. */
	record Place(String slug, String name, String tagline, String description, List<String> cuisines, String address,
			String area, String pincode, String phone, BigDecimal costForTwo, BigDecimal minimumOrder,
			BigDecimal deliveryFee, int prepMinutes, LocalTime opensAt, LocalTime closesAt, boolean pureVeg,
			BigDecimal rating, int ratingCount, RestaurantStatus status, Account owner, List<Section> menu) {

		Place {
			cuisines = List.copyOf(cuisines);
			menu = List.copyOf(menu);
		}

	}

	/** Fills in a {@link Place} fluently; lives only while the static list below is built. */
	private static final class PlaceBuilder {

		private final String slug;

		private final String name;

		private final String tagline;

		private final String description;

		private List<String> cuisines;

		private String address;

		private String area;

		private String pincode;

		private String phone;

		private BigDecimal costForTwo;

		private BigDecimal minimumOrder;

		private BigDecimal deliveryFee;

		private int prepMinutes;

		private LocalTime opensAt;

		private LocalTime closesAt;

		private boolean pureVeg;

		private BigDecimal rating = BigDecimal.ZERO;

		private int ratingCount;

		private RestaurantStatus status = RestaurantStatus.APPROVED;

		private Account owner;

		private final List<Section> menu = new ArrayList<>();

		PlaceBuilder(String slug, String name, String tagline, String description) {
			this.slug = slug;
			this.name = name;
			this.tagline = tagline;
			this.description = description;
		}

		PlaceBuilder cuisines(String... names) {
			this.cuisines = List.of(names);
			return this;
		}

		PlaceBuilder at(String address, String area, String pincode, String phone) {
			this.address = address;
			this.area = area;
			this.pincode = pincode;
			this.phone = phone;
			return this;
		}

		/** Cost for two, minimum order and delivery fee in rupees, and the usual preparation time. */
		PlaceBuilder prices(int costForTwo, int minimumOrder, int deliveryFee, int prepMinutes) {
			this.costForTwo = BigDecimal.valueOf(costForTwo);
			this.minimumOrder = BigDecimal.valueOf(minimumOrder);
			this.deliveryFee = BigDecimal.valueOf(deliveryFee);
			this.prepMinutes = prepMinutes;
			return this;
		}

		PlaceBuilder hours(String opensAt, String closesAt) {
			this.opensAt = LocalTime.parse(opensAt);
			this.closesAt = LocalTime.parse(closesAt);
			return this;
		}

		PlaceBuilder pureVeg() {
			this.pureVeg = true;
			return this;
		}

		PlaceBuilder rated(String average, int count) {
			this.rating = new BigDecimal(average);
			this.ratingCount = count;
			return this;
		}

		PlaceBuilder pending() {
			this.status = RestaurantStatus.PENDING;
			return this;
		}

		PlaceBuilder owner(String email, String name, String phone) {
			this.owner = new Account(email, name, phone, Role.RESTAURANT_OWNER);
			return this;
		}

		PlaceBuilder section(String name, Dish... dishes) {
			this.menu.add(new Section(name, List.of(dishes)));
			return this;
		}

		Place build() {
			return new Place(slug, name, tagline, description, cuisines, address, area, pincode, phone, costForTwo,
					minimumOrder, deliveryFee, prepMinutes, opensAt, closesAt, pureVeg, rating, ratingCount, status, owner,
					menu);
		}

	}

	static final List<String> CUISINES = List.of("Kolhapuri", "Malvani", "Maharashtrian", "South Indian", "North Indian",
			"Biryani", "Chinese", "Pizza", "Street Food", "Desserts");

	/** Demo accounts that own no restaurant. The admin is only created when the platform has no admin yet. */
	static final List<Account> ACCOUNTS = List.of(
			new Account("admin@zaika.test", "Zaika Admin", "9876543210", Role.ADMIN),
			new Account("rider1@zaika.test", "Rahul Pawar", "9890011221", Role.DELIVERY_PARTNER),
			new Account("rider2@zaika.test", "Sagar More", "9890011222", Role.DELIVERY_PARTNER),
			new Account("customer@zaika.test", "Riya Shinde", "9890011230", Role.CUSTOMER));

	static final String CUSTOMER_EMAIL = "customer@zaika.test";

	static final List<Place> RESTAURANTS = places(
			new PlaceBuilder("rassa-darbar", "Rassa Darbar", "Tambda-pandhra rassa, the Kolhapur way",
					"Fiery red tambda rassa, soothing white pandhra rassa and slow-cooked mutton, served with hot jowar "
							+ "bhakri. A Mahadwar Road favourite for Sunday lunch.")
				.cuisines("Kolhapuri", "Maharashtrian")
				.at("14, Mahadwar Road, near Binkhambi Ganesh Mandir", "Mahadwar Road", "416012", "9823014501")
				.prices(650, 199, 35, 25).hours("11:30", "23:00").rated("4.5", 2140)
				.owner("owner1@zaika.test", "Vikram Patil", "9823100001")
				.section("Thalis",
						nonVeg("Mutton Thali", 420, "Sukka mutton, tambda and pandhra rassa, two bhakris, rice and "
								+ "onion-lemon salad.").best().spice(3),
						nonVeg("Chicken Thali", 360, "Chicken Kolhapuri, both rassas, bhakri, rice and solkadhi.").best()
							.spice(3),
						veg("Veg Kolhapuri Thali", 260, "Mixed veg Kolhapuri, pithla, bhakri, rice, papad and thecha.")
							.spice(2))
				.section("Mutton & Chicken",
						nonVeg("Mutton Sukka", 380, "Bone-in mutton dry-roasted with kanda-lasun masala and coconut.")
							.spice(3),
						nonVeg("Chicken Kolhapuri", 320, "Chicken in a deep red gravy of roasted spices and dry coconut.")
							.spice(3),
						nonVeg("Kheema Masala", 300, "Minced mutton with green peas, cooked down until rich and thick.")
							.spice(2).unavailable(),
						nonVeg("Anda Curry", 180, "Two boiled eggs in an onion-tomato Kolhapuri gravy.").spice(2))
				.section("Rassa & Sides",
						nonVeg("Tambda Rassa", 90, "A bowl of the famous fiery red mutton broth. Ask for a refill.")
							.spice(3),
						nonVeg("Pandhra Rassa", 90, "Mild white mutton broth with coconut milk and whole spices.")
							.spice(1),
						veg("Solkadhi", 60, "Cooling kokum and coconut milk drink, the perfect chaser."))
				.section("Breads & Rice",
						veg("Jowar Bhakri", 30, "Hand-patted sorghum flatbread, straight off the tava."),
						veg("Chapati", 20, "Soft whole-wheat chapati brushed with ghee."),
						veg("Jeera Rice", 140, "Basmati tempered with cumin and ghee.")),

			new PlaceBuilder("panchganga-thali-house", "Panchganga Thali House", "Unlimited-style Maharashtrian thalis, pure veg",
					"Home-style Maharashtrian thalis with seasonal bhajis, fresh puranpoli and shrikhand. Light on oil, "
							+ "big on flavour.")
				.cuisines("Maharashtrian", "North Indian").pureVeg()
				.at("Plot 22, Rajarampuri 6th Lane", "Rajarampuri", "416008", "9823014502")
				.prices(400, 149, 25, 20).hours("11:00", "22:30").rated("4.3", 980)
				.owner("owner2@zaika.test", "Meera Kulkarni", "9823100002")
				.section("Thalis",
						veg("Maharashtrian Thali", 240, "Two bhajis, varan-bhaat, chapatis, koshimbir, papad and a sweet.")
							.best().spice(1),
						veg("Mini Thali", 160, "One bhaji, dal, rice and three chapatis for a quick lunch.").spice(1),
						veg("Puranpoli Thali", 290, "Festive thali with two puranpolis, katachi amti and ghee.").best())
				.section("Main Course",
						veg("Bharli Vangi", 190, "Baby brinjals stuffed with peanut, coconut and goda masala.").spice(2),
						veg("Paneer Butter Masala", 240, "Soft paneer cubes in a buttery tomato-cashew gravy.").spice(1),
						veg("Pithla", 140, "Comforting besan curry with green chillies and garlic.").spice(2),
						veg("Dal Tadka", 150, "Yellow dal finished with a sizzling garlic and cumin tadka.").spice(1)
							.unavailable())
				.section("Breads",
						veg("Butter Roti", 30, "Whole-wheat roti from the tandoor, brushed with butter."),
						veg("Puranpoli", 70, "Sweet chana-jaggery flatbread, served with a spoon of ghee.").best(),
						veg("Jowar Bhakri", 30, "Hand-patted sorghum flatbread."))
				.section("Desserts",
						veg("Shrikhand", 80, "Thick strained yoghurt with saffron, cardamom and pistachio."),
						veg("Gulab Jamun", 70, "Two warm khoya dumplings in rose-cardamom syrup.")),

			new PlaceBuilder("jhanjhanit-misal-ghar", "Jhanjhanit Misal Ghar", "Fiery misal from 6 AM, till the tarri runs out",
					"An early-morning institution in Kasaba Bawada. Sprouted matki, crunchy farsan and a tarri that "
							+ "lives up to the name. Breakfast only.")
				.cuisines("Kolhapuri", "Street Food").pureVeg()
				.at("Shop 2, Main Road, near Bawada Post Office", "Kasaba Bawada", "416006", "9823014503")
				.prices(150, 99, 20, 15).hours("06:00", "10:00").rated("4.6", 3120)
				.owner("owner3@zaika.test", "Santosh Chavan", "9823100003")
				.section("Misal",
						veg("Kolhapuri Misal Pav", 90, "Sprouted matki in fiery tarri, farsan, onion, lemon and two pavs.")
							.best().spice(3),
						veg("Dahi Misal", 100, "Our classic misal cooled down with a scoop of fresh curd.").spice(2),
						veg("Extra Tarri", 30, "One more bowl of the red-hot gravy. You will want it.").spice(3),
						veg("Upvas Misal", 110, "Fasting-friendly misal with sabudana khichdi and peanut amti.")
							.spice(1).unavailable())
				.section("Breakfast",
						veg("Kanda Pohe", 50, "Flattened rice with onion, peanuts, curry leaves and fresh coconut.")
							.spice(1),
						veg("Upma", 50, "Roasted rava with vegetables, ghee and a squeeze of lemon."),
						veg("Sabudana Vada", 70, "Two crisp sago and peanut patties with green chutney.").best().spice(1))
				.section("Chai & More",
						veg("Cutting Chai", 20, "Strong ginger-cardamom tea, half a glass, the way it should be."),
						veg("Masala Taak", 35, "Spiced buttermilk with coriander and roasted cumin."),
						veg("Kolhapuri Coffee", 40, "Milky filter-style coffee, frothed and sweet.")),

			new PlaceBuilder("malvan-tide", "Malvan Tide", "Konkan coast seafood, cooked Malvani style",
					"Surmai, pomfret and prawns brought up from the coast, with kokum-tangy curries, crisp rava fry "
							+ "and fluffy amboli.")
				.cuisines("Malvani")
				.at("3, Tarabai Park Main Road, opposite Dhairyaprasad Hall", "Tarabai Park", "416003", "9823014504")
				.prices(900, 249, 40, 30).hours("12:00", "23:00").rated("4.4", 1580)
				.owner("owner4@zaika.test", "Ganesh Sawant", "9823100004")
				.section("Fish Thalis",
						nonVeg("Surmai Thali", 480, "Surmai fry, fish curry, solkadhi, rice and two chapatis or bhakri.")
							.best().spice(2),
						nonVeg("Bangda Thali", 360, "Mackerel fry and Malvani curry with rice and solkadhi.").spice(2),
						nonVeg("Prawns Thali", 450, "Prawns fry and prawns curry with rice, amboli and solkadhi.")
							.spice(2))
				.section("Fry & Tawa",
						nonVeg("Pomfret Rava Fry", 520, "Whole pomfret in Malvani masala, crusted with semolina.").best()
							.spice(1),
						nonVeg("Bombil Fry", 280, "Bombay duck, butterflied and fried crisp outside, soft inside.")
							.spice(1),
						nonVeg("Prawns Koliwada", 340, "Batter-fried prawns with a chilli-ajwain kick.").spice(2),
						nonVeg("Tisrya Sukka", 300, "Clams tossed with coconut, onion and Malvani masala.").spice(2)
							.unavailable())
				.section("Curries",
						nonVeg("Malvani Chicken Curry", 330, "Chicken in a coconut gravy of 16 roasted spices.").spice(3),
						nonVeg("Kombdi Vade", 360, "Malvani chicken curry with four puffed rice-flour vade.").best()
							.spice(2),
						nonVeg("Prawns Curry", 380, "Juicy prawns in a kokum-tangy coconut curry.").spice(2))
				.section("Sides",
						veg("Amboli", 50, "Two soft, lacy rice-and-urad pancakes from the Konkan."),
						veg("Solkadhi", 60, "Kokum and coconut milk, chilled."),
						veg("Steamed Rice", 90, "Fragrant Konkan ukda rice.")),

			new PlaceBuilder("udupi-leaf", "Udupi Leaf", "Crisp dosas and filter coffee since breakfast",
					"South Indian vegetarian classics: paper-thin dosas, pillowy idlis and sambar simmered fresh every "
							+ "morning.")
				.cuisines("South Indian").pureVeg()
				.at("Ground floor, Kamla Arcade, Station Road", "Shahupuri", "416001", "9823014505")
				.prices(300, 99, 25, 15).hours("07:00", "22:00").rated("4.2", 1430)
				.owner("owner5@zaika.test", "Prakash Shetty", "9823100005")
				.section("Dosas",
						veg("Masala Dosa", 110, "Golden dosa with spiced potato bhaji, sambar and two chutneys.").best()
							.spice(1),
						veg("Mysore Masala Dosa", 130, "Spread with fiery red garlic chutney before the bhaji goes in.")
							.spice(2),
						veg("Rava Onion Dosa", 140, "Lacy semolina dosa with onions, green chillies and cumin.")
							.spice(1),
						veg("Paper Dosa", 150, "Extra-long, extra-thin and shatteringly crisp."),
						veg("Cheese Dosa", 160, "Loaded with grated cheese and a hint of chilli flakes.").unavailable())
				.section("Idli & Vada",
						veg("Idli Sambar", 70, "Two steamed rice cakes with sambar and coconut chutney.").best(),
						veg("Medu Vada", 80, "Two crisp urad dal doughnuts with sambar and chutney.").spice(1),
						veg("Sambar Vada", 85, "Medu vadas soaked in hot sambar.").spice(1))
				.section("Rice",
						veg("Bisi Bele Bath", 150, "Rice, lentils and vegetables with a spiced tamarind masala.")
							.spice(2),
						veg("Curd Rice", 120, "Creamy curd rice tempered with mustard, curry leaves and pomegranate."),
						veg("Lemon Rice", 110, "Tangy rice with peanuts, turmeric and curry leaves.").spice(1))
				.section("Beverages",
						veg("Filter Coffee", 45, "Decoction coffee with frothy hot milk, served in a davara.").best(),
						veg("Badam Milk", 70, "Warm almond milk with saffron and cardamom.")),

			new PlaceBuilder("noor-biryani-kebabs", "Noor Biryani & Kebabs", "Dum biryani and smoky kebabs till 3 AM",
					"Slow-cooked dum biryani sealed with dough, seekh kebabs off the coals and late-night cravings "
							+ "sorted. Open till 3 AM.")
				.cuisines("Biryani", "North Indian")
				.at("Shop 7, Laxmipuri Main Road, near Kapad Market", "Laxmipuri", "416002", "9823014506")
				.prices(700, 199, 35, 30).hours("12:00", "03:00").rated("4.5", 2760)
				.owner("owner6@zaika.test", "Imran Mulla", "9823100006")
				.section("Biryani",
						nonVeg("Chicken Dum Biryani", 280, "Basmati and marinated chicken, dum-cooked with saffron and "
								+ "fried onions.").best().spice(2),
						nonVeg("Mutton Dum Biryani", 360, "Tender mutton pieces layered with long-grain rice and mint.")
							.best().spice(2),
						nonVeg("Egg Biryani", 210, "Masala-fried eggs tucked into fragrant biryani rice.").spice(2),
						veg("Veg Dum Biryani", 220, "Seasonal vegetables and paneer, dum-cooked with whole spices.")
							.spice(1))
				.section("Kebabs",
						nonVeg("Chicken Seekh Kebab", 260, "Minced chicken skewers grilled over charcoal, with mint chutney.")
							.spice(2),
						nonVeg("Tandoori Chicken (Half)", 290, "Yoghurt and Kashmiri chilli marinated, charred in the "
								+ "tandoor.").spice(2),
						nonVeg("Mutton Galouti Kebab", 340, "Melt-in-the-mouth mutton patties on mini paratha.").spice(1)
							.unavailable(),
						veg("Paneer Tikka", 240, "Chunks of paneer, peppers and onion from the tandoor.").spice(1))
				.section("Curries",
						nonVeg("Chicken Korma", 300, "Mild, creamy curry with cashew and kewra.").spice(1),
						nonVeg("Butter Chicken", 320, "Tandoori chicken in a velvety tomato-butter gravy.").spice(1))
				.section("Extras",
						veg("Mirchi ka Salan", 60, "Green chillies in a tangy peanut-sesame gravy.").spice(2),
						veg("Burani Raita", 50, "Garlicky yoghurt raita, made for biryani."),
						veg("Phirni", 90, "Chilled ground-rice pudding with pistachio, set in a clay pot.")),

			new PlaceBuilder("wok-street", "Wok Street", "Indo-Chinese, tossed hot in the wok",
					"Schezwan everything, crisp Manchurian and hakka noodles with real wok smoke. Spice levels you can "
							+ "trust.")
				.cuisines("Chinese")
				.at("11, Nagala Park Road, near Collector Office", "Nagala Park", "416003", "9823014507")
				.prices(500, 149, 30, 20).hours("12:00", "23:30").rated("4.1", 890)
				.owner("owner7@zaika.test", "Aniket Desai", "9823100007")
				.section("Starters",
						nonVeg("Chilli Chicken Dry", 260, "Crisp chicken tossed with peppers, onion and green chilli.")
							.best().spice(2),
						veg("Veg Manchurian Dry", 190, "Vegetable dumplings in a sticky soy-garlic glaze.").spice(2),
						veg("Crispy Honey Potato", 180, "Fried potato fingers glazed with honey, chilli and sesame.")
							.spice(1),
						nonVeg("Dragon Prawns", 340, "Prawns in a fiery red dragon sauce with spring onion.").spice(3)
							.unavailable())
				.section("Soups",
						veg("Veg Manchow Soup", 120, "Peppery soup topped with crunchy fried noodles.").spice(2),
						nonVeg("Hot & Sour Chicken Soup", 140, "Tangy, spicy and loaded with shredded chicken.").spice(2))
				.section("Rice & Noodles",
						veg("Schezwan Fried Rice", 190, "Wok-tossed rice in house-made Schezwan sauce.").spice(3),
						nonVeg("Chicken Hakka Noodles", 220, "Thin noodles with chicken, cabbage and spring onion.").best()
							.spice(1),
						nonVeg("Egg Fried Rice", 180, "Classic fried rice with scrambled egg and greens.").spice(1),
						nonVeg("Triple Schezwan Rice", 280, "Rice, noodles and chicken gravy in one fiery plate.")
							.spice(3))
				.section("Mains",
						nonVeg("Chicken in Black Bean Sauce", 280, "Sliced chicken with peppers in a savoury black bean "
								+ "sauce.").spice(1),
						veg("Paneer Chilli Gravy", 240, "Paneer cubes in a glossy chilli-garlic gravy.").spice(2)),

			new PlaceBuilder("rankala-slice-co", "Rankala Slice Co.", "Wood-fired style pizzas with a local twist",
					"Thin, blistered crusts, a slow-cooked tomato sauce and toppings like Kolhapuri chicken tikka. "
							+ "Two minutes from Rankala lake.")
				.cuisines("Pizza")
				.at("5, Rankala Tower Road, near Rankala Bus Stand", "Rankala", "416012", "9823014508")
				.prices(600, 199, 30, 25).hours("11:00", "23:00").rated("4.0", 640)
				.owner("owner8@zaika.test", "Neha Joshi", "9823100008")
				.section("Pizzas",
						veg("Margherita", 249, "San Marzano-style tomato, mozzarella and fresh basil.").best(),
						veg("Farmhouse", 329, "Mushroom, capsicum, onion, sweet corn and olives."),
						nonVeg("Kolhapuri Chicken Tikka Pizza", 399, "Chicken tikka in Kolhapuri masala with onion and "
								+ "coriander.").best().spice(3),
						veg("Paneer Makhani Pizza", 359, "Makhani sauce, paneer, onion and a drizzle of cream.").spice(1),
						nonVeg("Pepper Chicken Pizza", 379, "Black pepper chicken, red onion and jalapenos.").spice(2),
						veg("Four Cheese Pizza", 399, "Mozzarella, cheddar, parmesan and a little blue cheese.")
							.unavailable())
				.section("Pasta",
						veg("Penne Arrabbiata", 249, "Penne in a garlicky tomato and red chilli sauce.").spice(2),
						nonVeg("Chicken Alfredo Pasta", 299, "Creamy parmesan sauce with grilled chicken."))
				.section("Sides",
						veg("Cheesy Garlic Bread", 149, "Toasted garlic butter bread under melted mozzarella."),
						veg("Peri Peri Fries", 129, "Crisp fries dusted with house peri peri spice.").spice(2))
				.section("Drinks & Dessert",
						veg("Choco Lava Cake", 109, "Warm chocolate cake with a molten centre."),
						veg("Cold Coffee", 129, "Thick, chilled coffee blended with ice cream.")),

			new PlaceBuilder("bhel-bandi", "Bhel Bandi", "Chaat and vada pav from the evening lanes",
					"Tangy bhel, crisp pani puri and buttery pav bhaji, made to order the way Shivaji Peth likes it.")
				.cuisines("Street Food").pureVeg()
				.at("Shop 9, Shivaji Peth Main Road, near Ubha Maruti Chowk", "Shivaji Peth", "416012", "9823014509")
				.prices(200, 99, 20, 15).hours("11:00", "22:30").rated("4.3", 1720)
				.owner("owner9@zaika.test", "Rohit Gaikwad", "9823100009")
				.section("Chaat",
						veg("Bhel Puri", 60, "Puffed rice, sev, onion and three chutneys, tossed fresh.").best().spice(1),
						veg("Sev Puri", 70, "Crisp puris with potato, chutneys and a mountain of sev.").spice(1),
						veg("Pani Puri", 50, "Eight puris with spicy mint water and sweet chutney on the side.").best()
							.spice(2),
						veg("Dahi Puri", 80, "Puris filled with potato, sweet curd and pomegranate.").spice(1),
						veg("Ragda Pattice", 90, "Potato patties under hot white-pea ragda and chutneys.").spice(2))
				.section("Pav Corner",
						veg("Vada Pav", 25, "Spiced potato vada in a soft pav with dry garlic chutney.").best().spice(2),
						veg("Pav Bhaji", 130, "Buttery mashed vegetable bhaji with two toasted pavs.").spice(2),
						veg("Dabeli", 40, "Sweet-spicy potato in pav with peanuts and pomegranate.").spice(2)
							.unavailable())
				.section("Coolers",
						veg("Kokum Sharbat", 40, "Chilled kokum with a pinch of black salt and cumin."),
						veg("Sweet Lassi", 70, "Thick churned curd with a touch of cardamom.")),

			new PlaceBuilder("gulkand-and-co", "Gulkand & Co.", "Ice creams, kulfi and mithai made in small batches",
					"Natural ice creams churned with real fruit, rose gulkand and saffron kulfi, plus the falooda the "
							+ "whole city queues for.")
				.cuisines("Desserts").pureVeg()
				.at("2, Sambhaji Nagar Main Road, near Petrol Pump", "Sambhaji Nagar", "416012", "9823014510")
				.prices(300, 99, 25, 10).hours("11:00", "23:30").rated("4.7", 1090)
				.owner("owner10@zaika.test", "Pooja Mane", "9823100010")
				.section("Ice Creams",
						veg("Gulkand Ice Cream", 90, "Rose petal gulkand folded into fresh cream.").best(),
						veg("Sitaphal Ice Cream", 110, "Custard apple pulp, hand-scooped from the season's best fruit.")
							.best(),
						veg("Alphonso Mango Ice Cream", 120, "Ratnagiri Alphonso pulp churned with milk. Back in summer.")
							.unavailable(),
						veg("Kesar Pista Kulfi", 80, "Slow-reduced milk with saffron and pistachio on a stick."))
				.section("Mithai",
						veg("Kandi Pedha (250 g)", 160, "Caramelised milk pedhas with a grainy, fudgy bite.").best(),
						veg("Ukadiche Modak", 80, "Two steamed rice-flour modaks with coconut-jaggery filling."),
						veg("Basundi", 110, "Thickened sweet milk with charoli and cardamom."))
				.section("Shakes & Faloodas",
						veg("Royal Falooda", 150, "Rose milk, vermicelli, sabja seeds, jelly and a scoop of ice cream.")
							.best(),
						veg("Mango Mastani", 130, "Mango milkshake crowned with ice cream and dry fruits."),
						veg("Thick Chocolate Shake", 140, "Dark chocolate blended with ice cream and milk.")),

			new PlaceBuilder("punjab-tadka-dhaba", "Punjab Tadka Dhaba", "Highway dhaba flavours in the city",
					"Dal makhani simmered overnight, butter chicken from the tandoor and naan with plenty of butter, "
							+ "dhaba-style.")
				.cuisines("North Indian")
				.at("Survey 18, Kawala Naka Chowk, Pune-Bengaluru Highway", "Kawala Naka", "416003", "9823014511")
				.prices(550, 149, 30, 25).hours("11:00", "23:30").rated("3.9", 760)
				.owner("owner11@zaika.test", "Harpreet Singh", "9823100011")
				.section("Tandoor",
						veg("Paneer Tikka", 250, "Paneer, peppers and onion in a smoky tandoori marinade.").spice(1),
						nonVeg("Tandoori Chicken (Half)", 280, "Bone-in chicken charred in the clay oven.").best().spice(2),
						nonVeg("Malai Chicken Tikka", 300, "Chicken in a mild cream, cheese and cardamom marinade.")
							.spice(1))
				.section("Mains",
						veg("Dal Makhani", 220, "Black lentils simmered overnight with butter and cream.").best()
							.spice(1),
						nonVeg("Butter Chicken", 330, "Tandoori chicken in a buttery tomato gravy.").best().spice(1),
						veg("Kadai Paneer", 260, "Paneer with peppers in a coarse kadai masala.").spice(2),
						veg("Sarson da Saag", 230, "Mustard greens with makki di roti. Winter special.").spice(1)
							.unavailable(),
						nonVeg("Rara Mutton", 390, "Mutton pieces cooked in a thick mutton kheema gravy.").spice(2))
				.section("Breads",
						veg("Butter Naan", 50, "Soft leavened bread from the tandoor, loaded with butter."),
						veg("Lachha Paratha", 60, "Flaky, layered whole-wheat paratha."),
						veg("Tandoori Roti", 25, "Whole-wheat roti baked in the tandoor."))
				.section("Rice",
						veg("Jeera Rice", 150, "Basmati with cumin and ghee."),
						veg("Veg Pulao", 180, "Basmati cooked with vegetables and whole spices.")),

			new PlaceBuilder("aajis-kitchen", "Aaji's Kitchen", "Home-style Maharashtrian cooking, like Aaji made it",
					"Everyday Maharashtrian food from a family kitchen: zunka bhakar, usal, kombdi rassa and ukadiche "
							+ "modak on Sundays.")
				.cuisines("Maharashtrian")
				.at("Row House 4, Kadamwadi Road, near Kadamwadi Church", "Kadamwadi", "416003", "9823014512")
				.prices(350, 149, 25, 25).hours("10:30", "22:00").rated("4.4", 520)
				.owner("owner12@zaika.test", "Sunita Bhosale", "9823100012")
				.section("Daily Thali",
						veg("Ghar Thali", 180, "Today's bhaji, varan-bhaat, three chapatis, koshimbir and a sweet.").best()
							.spice(1),
						nonVeg("Kombdi Thali", 260, "Kolhapuri-style chicken rassa with bhakri, rice and salad.").best()
							.spice(2),
						veg("Zunka Bhakar Plate", 120, "Dry besan zunka with jowar bhakri, thecha and onion.").spice(2))
				.section("Bhaji & Usal",
						veg("Matki Usal", 110, "Sprouted moth beans in a goda masala gravy.").spice(2),
						veg("Bharli Bhendi", 130, "Okra stuffed with peanut and coconut masala.").spice(1),
						veg("Batata Bhaji", 90, "Dry potato bhaji with mustard, turmeric and green chilli.").spice(1))
				.section("Non-veg",
						nonVeg("Chicken Sukka", 280, "Dry chicken roasted with coconut and kanda-lasun masala.").spice(3),
						nonVeg("Anda Rassa", 160, "Boiled eggs in a thin, spicy Maharashtrian curry.").spice(2),
						nonVeg("Mutton Rassa", 340, "Weekend special mutton in a fiery rassa.").spice(3).unavailable())
				.section("Sweets",
						veg("Sheera", 60, "Semolina halwa with ghee, saffron and cashews."),
						veg("Ukadiche Modak", 90, "Two steamed modaks with coconut-jaggery filling and ghee.")),

			new PlaceBuilder("brew-lab-cafe", "Brew Lab Cafe", "Small-batch coffee and fresh bakes",
					"Specialty coffee from Chikmagalur estates, brewed by the cup, with cakes baked every morning.")
				.cuisines("Desserts").pureVeg().pending()
				.at("1st floor, Ruikar Trust Building, Ruikar Colony", "Ruikar Colony", "416005", "9823014513")
				.prices(450, 149, 30, 15).hours("09:00", "23:00")
				.owner("owner13@zaika.test", "Kunal Kadam", "9823100013")
				.section("Coffee",
						veg("Cappuccino", 140, "Double shot espresso with silky steamed milk."),
						veg("Cold Brew", 160, "Steeped for 18 hours, smooth and chocolatey.").best(),
						veg("Hazelnut Latte", 170, "Espresso, steamed milk and house hazelnut syrup."),
						veg("Pour Over", 180, "Single-estate Chikmagalur beans, brewed by hand to order."))
				.section("Bakes",
						veg("Banana Walnut Cake", 120, "Moist slice with toasted walnuts."),
						veg("Chocolate Brownie", 110, "Dense, fudgy and served warm.").best(),
						veg("Butter Croissant", 130, "Flaky, all-butter and baked at 9 AM. Usually gone by noon.")
							.unavailable())
				.section("Sandwiches",
						veg("Paneer Pesto Sandwich", 190, "Grilled paneer, basil pesto and roasted peppers on sourdough."),
						veg("Bombay Masala Toast", 150, "Spiced potato, beetroot, onion and green chutney, toasted crisp.")
							.spice(1),
						veg("Mushroom Cheese Melt", 200, "Garlic mushrooms under melted cheddar on multigrain bread.")));

	private DemoData() {
	}

	private static List<Place> places(PlaceBuilder... places) {
		return Arrays.stream(places).map(PlaceBuilder::build).toList();
	}

	private static Dish veg(String name, int price, String description) {
		return new Dish(name, BigDecimal.valueOf(price), true, description, false, 0, false);
	}

	private static Dish nonVeg(String name, int price, String description) {
		return new Dish(name, BigDecimal.valueOf(price), false, description, false, 0, false);
	}

}
