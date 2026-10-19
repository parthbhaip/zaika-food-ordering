package com.zaika.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** JWT_PRIVATE_KEY: PKCS#8 RSA, PEM or bare base64, 2048+ bits; the public half is derived from it. */
class ApiSigningKeysTest {

	static final KeyPair KEYS = ApiSecurityConfig.generateKeys();

	static final String BASE64 = Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded());

	@Test
	void readsBareBase64AndPemAndDerivesThePublicKey() {
		String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(KEYS.getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n";
		for (String configured : new String[] { BASE64, pem, "  " + BASE64 + "\n" }) {
			KeyPair parsed = ApiSecurityConfig.parseKeys(configured);
			assertThat(parsed.getPrivate()).isEqualTo(KEYS.getPrivate());
			assertThat(parsed.getPublic()).isEqualTo(KEYS.getPublic());
		}
	}

	@Test
	void blankMeansAKeyMadeAtStartup() {
		ApiSecurityConfig config = new ApiSecurityConfig();
		KeyPair first = config.apiSigningKeys("");
		assertThat(first.getPublic().getAlgorithm()).isEqualTo("RSA");
		assertThat(config.apiSigningKeys(" ").getPublic()).isNotEqualTo(first.getPublic());
		assertThat(config.apiSigningKeys(BASE64).getPublic()).isEqualTo(KEYS.getPublic());
	}

	@Test
	void badKeysStopStartupWithoutEchoingTheKey() throws Exception {
		for (String bad : new String[] { "not a key", BASE64.substring(0, 200),
				Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()) }) {
			assertThatThrownBy(() -> ApiSecurityConfig.parseKeys(bad)).isInstanceOf(IllegalStateException.class)
				.hasMessageStartingWith("JWT_PRIVATE_KEY is not an RSA private key in PKCS#8")
				.hasNoCause()
				.message().doesNotContain(bad);
		}

		KeyPairGenerator small = KeyPairGenerator.getInstance("RSA");
		small.initialize(1024);
		String weak = Base64.getEncoder().encodeToString(small.generateKeyPair().getPrivate().getEncoded());
		assertThatThrownBy(() -> ApiSecurityConfig.parseKeys(weak))
			.hasMessage("JWT_PRIVATE_KEY must be an RSA key of at least 2048 bits");
	}

}
