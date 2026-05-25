package io.appform.dropwizard.sharding.apt;

import io.appform.dropwizard.sharding.sharding.CopyFromParent;
import io.appform.dropwizard.sharding.sharding.ParentEntity;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Set;

/**
 * Compile-time annotation processor that validates {@link CopyFromParent} and
 * {@link ParentEntity} usage:
 * <ul>
 *   <li>{@code @CopyFromParent} without {@code @ParentEntity} on the enclosing class → error</li>
 *   <li>{@code @CopyFromParent(field = "x")} where {@code x} does not exist on the parent → error</li>
 *   <li>Type mismatch between the parent field and the annotated child field → error</li>
 *   <li>Parent field annotated with {@code @Transient} (JPA) → error</li>
 * </ul>
 */
@SupportedAnnotationTypes({
        "io.appform.dropwizard.sharding.sharding.CopyFromParent",
        "io.appform.dropwizard.sharding.sharding.ParentEntity"
})
@SupportedSourceVersion(SourceVersion.RELEASE_11)
public class CopyFromParentProcessor extends AbstractProcessor {

    private static final String TRANSIENT_ANNOTATION = "javax.persistence.Transient";
    private static final String JAKARTA_TRANSIENT_ANNOTATION = "jakarta.persistence.Transient";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(CopyFromParent.class)) {
            if (element instanceof VariableElement) {
                validateField((VariableElement) element);
            }
        }
        // Don't claim the annotations — let other processors see them too
        return false;
    }

    private void validateField(VariableElement childField) {
        TypeElement childClass = (TypeElement) childField.getEnclosingElement();
        ParentEntity parentAnn = childClass.getAnnotation(ParentEntity.class);

        // 1. @CopyFromParent without @ParentEntity
        if (parentAnn == null) {
            error(childField,
                    "@CopyFromParent on %s.%s requires @ParentEntity on the enclosing class %s",
                    childClass.getSimpleName(), childField.getSimpleName(),
                    childClass.getQualifiedName());
            return;
        }

        // Resolve parent class TypeElement
        TypeElement parentClass = resolveParentClass(parentAnn);
        if (parentClass == null) {
            error(childField,
                    "Could not resolve parent class declared in @ParentEntity on %s",
                    childClass.getQualifiedName());
            return;
        }

        // 2. Check parent field exists
        CopyFromParent copyAnn = childField.getAnnotation(CopyFromParent.class);
        String parentFieldName = copyAnn.field();
        VariableElement parentField = findField(parentClass, parentFieldName);

        if (parentField == null) {
            error(childField,
                    "@CopyFromParent(field=\"%s\") on %s.%s: field '%s' not found on parent %s",
                    parentFieldName, childClass.getSimpleName(),
                    childField.getSimpleName(), parentFieldName,
                    parentClass.getQualifiedName());
            return;
        }

        // 3. Type mismatch check
        TypeMirror parentFieldType = parentField.asType();
        TypeMirror childFieldType = childField.asType();
        if (!processingEnv.getTypeUtils().isAssignable(parentFieldType, childFieldType)) {
            error(childField,
                    "@CopyFromParent type mismatch on %s.%s: parent field '%s' is %s, "
                            + "child field is %s",
                    childClass.getSimpleName(), childField.getSimpleName(),
                    parentFieldName, parentFieldType, childFieldType);
        }

        // 4. @Transient check on parent field
        if (hasTransientAnnotation(parentField)) {
            error(childField,
                    "@CopyFromParent(field=\"%s\") on %s.%s: parent field '%s' on %s is "
                            + "marked @Transient and will not be persisted",
                    parentFieldName, childClass.getSimpleName(),
                    childField.getSimpleName(), parentFieldName,
                    parentClass.getSimpleName());
        }
    }

    /**
     * Resolves the {@link TypeElement} for the class declared in {@code @ParentEntity(value)}.
     * Uses the {@link MirroredTypeException} trick because annotation class values are not
     * directly available at compile time.
     */
    private TypeElement resolveParentClass(ParentEntity parentAnn) {
        try {
            // This will throw MirroredTypeException — that's expected
            parentAnn.value();
            return null; // unreachable
        } catch (MirroredTypeException mte) {
            TypeMirror typeMirror = mte.getTypeMirror();
            if (typeMirror instanceof DeclaredType) {
                return (TypeElement) ((DeclaredType) typeMirror).asElement();
            }
            return null;
        }
    }

    /**
     * Finds a field by name on the given class or its superclasses.
     */
    private VariableElement findField(TypeElement classElement, String fieldName) {
        TypeElement current = classElement;
        while (current != null) {
            List<VariableElement> fields = ElementFilter.fieldsIn(current.getEnclosedElements());
            for (VariableElement field : fields) {
                if (field.getSimpleName().contentEquals(fieldName)) {
                    return field;
                }
            }
            // Walk up superclass
            TypeMirror superclass = current.getSuperclass();
            if (superclass instanceof DeclaredType) {
                current = (TypeElement) ((DeclaredType) superclass).asElement();
            } else {
                break;
            }
        }
        return null;
    }

    private boolean hasTransientAnnotation(VariableElement field) {
        return field.getAnnotationMirrors().stream()
                .anyMatch(am -> {
                    String fqn = am.getAnnotationType().toString();
                    return TRANSIENT_ANNOTATION.equals(fqn)
                            || JAKARTA_TRANSIENT_ANNOTATION.equals(fqn);
                });
    }

    private void error(Element element, String format, Object... args) {
        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                String.format(format, args),
                element);
    }
}
