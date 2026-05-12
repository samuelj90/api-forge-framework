package org.apiforgeframework.annotations;

import java.lang.annotation.*;

/**
 * Marks a Spring bean as a custom authentication token provider for a specific
 * egress client. Overrides the default auth strategy declared in {@link ForgeEgressClient}.
 *
 * <pre>{@code
 * @Component
 * @ForgeAuthProvider(forClient = "payment")
 * public class PaymentVaultAuthProvider implements ForgeTokenProvider {
 *     public String getToken() {
 *         return vaultClient.readSecret("payment/api-token");
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ForgeAuthProvider {
    /** The logical client name this provider supplies tokens for. */
    String forClient();
}
