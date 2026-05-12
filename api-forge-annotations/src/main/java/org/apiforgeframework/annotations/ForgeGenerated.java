package org.apiforgeframework.annotations;

import java.lang.annotation.*;

/**
 * Marks all classes and methods emitted by api-forge-maven-plugin.
 *
 * <p>Used by:
 * <ul>
 *   <li>JaCoCo exclusion rules (no generated code in coverage thresholds)</li>
 *   <li>ArchUnit rules (generated code must not import hand-written service impls)</li>
 *   <li>IDE plugins (suppress "generated file" warnings)</li>
 * </ul>
 *
 * <p>This annotation is always placed on generated types alongside the type-specific
 * annotation ({@link ForgeService}, {@link ForgeEgressClient}, etc.).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ForgeGenerated {

    /** The spec file that produced this artifact. */
    String fromSpec();

    /** Plugin version that generated this file — aids debugging after upgrades. */
    String pluginVersion() default "unknown";

    /** ISO-8601 timestamp of generation (populated at build time). */
    String generatedAt() default "";
}
