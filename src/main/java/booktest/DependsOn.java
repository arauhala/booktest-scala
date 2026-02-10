package booktest;

import java.lang.annotation.*;

/**
 * Annotation to specify test dependencies.
 * Tests with this annotation will wait for the specified tests to complete before running.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DependsOn {
    String[] value();
}
