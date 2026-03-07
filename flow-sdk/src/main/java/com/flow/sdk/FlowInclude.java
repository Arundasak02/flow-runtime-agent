package com.flow.sdk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Switches a class to <strong>opt-in mode</strong>: only fields annotated with
 * {@code @FlowInclude} are captured during checkpoint extraction.
 *
 * <p><strong>On a class:</strong> activates opt-in mode. Without any {@code @FlowInclude}
 * fields, the object produces an empty map. Only explicitly marked fields are captured.
 *
 * <p><strong>On a field</strong> (when the class is in opt-in mode): marks this field
 * as safe to capture.
 *
 * <p>This is the inverse of {@link FlowExclude}. Use this when a class has mostly
 * sensitive fields and only a few are safe to expose.
 *
 * <pre>{@code
 * {@literal @}FlowInclude  // class-level: opt-in mode
 * public class UserProfile {
 *
 *     @FlowInclude
 *     private String userId;        // captured — explicitly opted in
 *
 *     @FlowInclude
 *     private String tier;          // captured — explicitly opted in
 *
 *     private String email;         // NOT captured — not opted in
 *     private String phone;         // NOT captured — not opted in
 *     private String ssn;           // NOT captured — not opted in
 * }
 * }</pre>
 *
 * @see FlowExclude
 * @see Flow#checkpoint(String, Object)
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface FlowInclude {
}

