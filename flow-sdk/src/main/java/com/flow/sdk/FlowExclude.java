package com.flow.sdk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or class to be <strong>excluded</strong> from Flow checkpoint capture.
 *
 * <p>When placed on a <strong>field</strong>: that field is never included when the
 * enclosing object is passed to {@link Flow#checkpoint(String, Object)}.
 *
 * <p>When placed on a <strong>class</strong>: all fields of that class are excluded
 * (the entire object is opaque to Flow). This is useful for sensitive domain types
 * like {@code CreditCard}, {@code SocialSecurityNumber}, etc.
 *
 * <p><strong>PII Safety:</strong> Annotate PII fields once on the domain model and
 * every checkpoint call in the codebase automatically respects the exclusion —
 * no per-call-site discipline required.
 *
 * <pre>{@code
 * public class Cart {
 *     private String cartId;              // captured
 *     private double total;               // captured
 *
 *     @FlowExclude
 *     private String customerEmail;       // NEVER captured
 *
 *     @FlowExclude
 *     private CreditCard paymentMethod;   // NEVER captured
 * }
 * }</pre>
 *
 * @see FlowInclude
 * @see Flow#checkpoint(String, Object)
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface FlowExclude {
}

