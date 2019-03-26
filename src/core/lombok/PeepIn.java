package lombok;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put on any field to make lombok build a standard static filed name <br>
 * like psf String __THE_FIELD_NAME = "theFieldName"
 * <br>neither static nor final field
 */
@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface PeepIn {

    AccessLevel level() default lombok.AccessLevel.PUBLIC;

    /**
     * customize<br>
     * the prefix for the clazz<br>
     * or<br>
     * the final name of the filed.
     */
    String customer();

}
