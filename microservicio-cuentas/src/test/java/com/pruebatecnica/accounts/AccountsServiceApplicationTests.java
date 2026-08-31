package com.pruebatecnica.accounts;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: verifica que el contexto de Spring arranca correctamente con
 * el perfil H2 por defecto. Las pruebas funcionales completas
 * viven en clases dedicadas de controller/service.
 */
@SpringBootTest
class AccountsServiceApplicationTests {

	@Test
	void contextLoads() {
	}
}
