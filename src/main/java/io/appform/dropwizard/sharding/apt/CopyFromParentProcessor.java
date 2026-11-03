package io.appform.dropwizard.sharding.apt;

import io.appform.dropwizard.sharding.sharding.CopyFromParent;
import io.appform.dropwizard.sharding.sharding.ParentEntity;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time annotation processor that validates {@link CopyFromParent} and
 * {@link ParentEntity} usage:
 * <ul>
 *   <li>{@code @CopyFromParent} without {@code @ParentEntity} anywhere in the enclosing class's
 *       superclass hierarchy → error</li>
 *   <li>{@code @CopyFromParent(field = "x")} where {@code x} does not exist on the parent → error</li>
 *   <li>Type mismatch between the parent field and the annotated child field → error</li>
 *   <li>Parent field annotated with {@code @Transient} (JPA) → error</li>
 *   <li>Parent/child field declared {@code static}, or child field declared {@code final} → error,
 *   since these cannot be safely read/written via {@link java.lang.invoke.MethodHandle}</li>
 * </ul>
 */
@SupportedAnnotationTypes({
        "io.appform.dropwizard.sharding.sharding.CopyFromParent",
        "io.appform.dropwizard.sharding.sharding.ParentEntity"
})
public class CopyFromParentProcessor extends AbstractProcessor {

    private static final String TRANSIENT_ANNOTATION = "javax.persistence.Transient";
    private static final String JAKARTA_TRANSIENT_ANNOTATION = "jakarta.persistence.Transient";

    @Override
    public SourceVersion getSupportedSourceVersion() {
        // This processor is auto-registered via META-INF/services, so it is loaded by javac for
        // every downstream compilation regardless of whether the annotations it processes are
        // actually used. Returning SourceVersion.latestSupported() (instead of pinning to
        // RELEASE_11 via @SupportedSourceVersion) avoids the "-source N, which is more than
        // RELEASE_11" javac warning on newer JDKs, which would otherwise fail downstream builds
        // compiling with -Werror.
        return SourceVersion.latestSupported();
    }

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
        TypeElement annotatedClass = findParentEntityDeclaringClass(childClass);

        // 1. @CopyFromParent without @ParentEntity anywhere up the superclass hierarchy.
        // @CopyFromParent field declared on a subclass is valid
        // as long as some superclass (or the class itself) carries @ParentEntity.
        if (annotatedClass == null) {
            error(childField,
                    "@CopyFromParent on %s.%s requires @ParentEntity on the enclosing class %s "
                            + "or one of its superclasses",
                    childClass.getSimpleName(), childField.getSimpleName(),
                    childClass.getQualifiedName());
            return;
        }

        // Resolve parent class TypeElement
        TypeElement parentClass = resolveParentClass(annotatedClass);
        if (parentClass == null) {
            error(childField,
                    "Could not resolve parent class declared in @ParentEntity on %s",
                    annotatedClass.getQualifiedName());
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

        // 3. Modifier checks: static source/target fields and final target fields cannot be
        // read/written safely via the instance MethodHandles that CopyFromParentUtils builds.
        if (parentField.getModifiers().contains(Modifier.STATIC)) {
            error(childField,
                    "@CopyFromParent(field=\"%s\") on %s.%s: parent field '%s' on %s is static "
                            + "and cannot be copied",
                    parentFieldName, childClass.getSimpleName(),
                    childField.getSimpleName(), parentFieldName,
                    parentClass.getSimpleName());
            return;
        }
        if (childField.getModifiers().contains(Modifier.STATIC)) {
            error(childField,
                    "@CopyFromParent on %s.%s: field is static and cannot be a copy target",
                    childClass.getSimpleName(), childField.getSimpleName());
            return;
        }
        if (childField.getModifiers().contains(Modifier.FINAL)) {
            error(childField,
                    "@CopyFromParent on %s.%s: field is final and cannot be a copy target",
                    childClass.getSimpleName(), childField.getSimpleName());
            return;
        }

        // 4. Type mismatch check
        TypeMirror parentFieldType = parentField.asType();
        TypeMirror childFieldType = childField.asType();
        if (!processingEnv.getTypeUtils().isAssignable(parentFieldType, childFieldType)) {
            error(childField,
                    "@CopyFromParent type mismatch on %s.%s: parent field '%s' is %s, "
                            + "child field is %s",
                    childClass.getSimpleName(), childField.getSimpleName(),
                    parentFieldName, parentFieldType, childFieldType);
        }

        // 5. @Transient check on parent field
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
     * Walks up the superclass hierarchy starting at {@code typeElement}, looking for the class
     * that declares {@code @ParentEntity}
     */
    private TypeElement findParentEntityDeclaringClass(TypeElement typeElement) {
        TypeElement current = typeElement;
        while (current != null) {
            if (current.getAnnotation(ParentEntity.class) != null) {
                return current;
            }
            TypeMirror superclass = current.getSuperclass();
            current = (superclass instanceof DeclaredType)
                    ? (TypeElement) ((DeclaredType) superclass).asElement()
                    : null;
        }
        return null;
    }

    /**
     * Resolves the {@link TypeElement} for the class declared in {@code @ParentEntity(value)}
     * using the {@link javax.lang.model.element.AnnotationMirror} API, which provides
     * compile-time-safe access to annotation values.
     */
    private TypeElement resolveParentClass(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString()
                    .equals(ParentEntity.class.getName())) {
                continue;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                    : mirror.getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("value")) {
                    TypeMirror typeMirror = (TypeMirror) entry.getValue().getValue();
                    if (typeMirror instanceof DeclaredType) {
                        return (TypeElement) ((DeclaredType) typeMirror).asElement();
                    }
                }
            }
        }
        return null;
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
