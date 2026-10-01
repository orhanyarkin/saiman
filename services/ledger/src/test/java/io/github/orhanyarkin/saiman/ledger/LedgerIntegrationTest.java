package io.github.orhanyarkin.saiman.ledger;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

/**
 * The one integration-test setup of the ledger: the full application on a random port with Postgres and Kafka
 * containers. Every test class using exactly this annotation shares one cached context and one pair of containers,
 * so tests must not depend on an empty database: they use fresh payment keys and wallets. {@link FakeChain} stands in
 * for Base Sepolia and {@link FakeSellerCreditNotes} for seller-api, so no test reaches a real RPC or seller.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, RegistryProbe.class, FakeChain.class, FakeSellerCreditNotes.class})
public @interface LedgerIntegrationTest {}
