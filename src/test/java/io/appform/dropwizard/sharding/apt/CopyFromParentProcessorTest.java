package io.appform.dropwizard.sharding.apt;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyFromParentProcessorTest {

    @Test
    void testValidAnnotation_compiles() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent {",
                        "    private String name;",
                        "    public String getName() { return name; }",
                        "    void setName(String name) { this.name = name; }",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class Child {",
                        "    @CopyFromParent(field = \"name\")",
                        "    private String childName;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).succeeded();
    }

    @Test
    void testCopyFromParentWithoutParentEntity_fails() {
        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "public class Child {",
                        "    @CopyFromParent(field = \"name\")",
                        "    private String childName;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(child);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("requires @ParentEntity on the enclosing class")
                .inFile(child);
    }

    @Test
    void testFieldNotFoundOnParent_fails() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent {",
                        "    private String name;",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class Child {",
                        "    @CopyFromParent(field = \"nonExistent\")",
                        "    private String childField;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("field 'nonExistent' not found on parent")
                .inFile(child);
    }

    @Test
    void testTypeMismatch_fails() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent {",
                        "    private int count;",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class Child {",
                        "    @CopyFromParent(field = \"count\")",
                        "    private String childCount;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("type mismatch on Child.childCount: parent field 'count' is int")
                .inFile(child);
    }

    @Test
    void testTransientParentField_fails() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "import javax.persistence.Transient;",
                        "public class Parent {",
                        "    @Transient",
                        "    private String tempValue;",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class Child {",
                        "    @CopyFromParent(field = \"tempValue\")",
                        "    private String childTemp;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("marked @Transient and will not be persisted")
                .inFile(child);
    }

    @Test
    void testCompatibleTypes_compiles() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent {",
                        "    private int count;",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class Child {",
                        "    @CopyFromParent(field = \"count\")",
                        "    private long childCount;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).succeeded();
    }

    @Test
    void testMultipleFields_compiles() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent {",
                        "    private String name;",
                        "    private int count;",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class Child {",
                        "    @CopyFromParent(field = \"name\")",
                        "    private String childName;",
                        "    @CopyFromParent(field = \"count\")",
                        "    private int childCount;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).succeeded();
    }

    @Test
    void testInheritedParentField_compiles() {
        JavaFileObject grandParent = JavaFileObjects.forSourceString("test.GrandParent",
                String.join("\n",
                        "package test;",
                        "public class GrandParent {",
                        "    private String inheritedField;",
                        "}"));

        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent extends GrandParent {",
                        "    private String ownField;",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class Child {",
                        "    @CopyFromParent(field = \"inheritedField\")",
                        "    private String childField;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(grandParent, parent, child);

        assertThat(compilation).succeeded();
    }

    @Test
    void testNoAnnotations_compiles() {
        JavaFileObject plain = JavaFileObjects.forSourceString("test.Plain",
                String.join("\n",
                        "package test;",
                        "public class Plain {",
                        "    private String name;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(plain);

        assertThat(compilation).succeeded();
    }

    // @ParentEntity declared on an ancestor class, not directly on the class that
    // declares the @CopyFromParent field.
    @Test
    void testParentEntityOnSuperclass_fieldOnSubclass_compiles() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent {",
                        "    private String name;",
                        "}"));

        JavaFileObject baseChild = JavaFileObjects.forSourceString("test.BaseChild",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class BaseChild {",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "public class Child extends BaseChild {",
                        "    @CopyFromParent(field = \"name\")",
                        "    private String childName;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, baseChild, child);

        assertThat(compilation).succeeded();
    }

    @Test
    void testNoParentEntityAnywhereInHierarchy_fails() {
        JavaFileObject baseChild = JavaFileObjects.forSourceString("test.BaseChild",
                String.join("\n",
                        "package test;",
                        "public class BaseChild {",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "public class Child extends BaseChild {",
                        "    @CopyFromParent(field = \"name\")",
                        "    private String childName;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(baseChild, child);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("requires @ParentEntity on the enclosing class")
                .inFile(child);
    }

    // static/final fields cannot be safely read/written via the instance
    @Test
    void testStaticParentField_fails() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent {",
                        "    private static String name;",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class Child {",
                        "    @CopyFromParent(field = \"name\")",
                        "    private String childName;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("parent field 'name' on Parent is static")
                .inFile(child);
    }

    @Test
    void testStaticChildField_fails() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent {",
                        "    private String name;",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class Child {",
                        "    @CopyFromParent(field = \"name\")",
                        "    private static String childName;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("field is static and cannot be a copy target")
                .inFile(child);
    }

    @Test
    void testFinalChildField_fails() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent {",
                        "    private String name;",
                        "}"));

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                String.join("\n",
                        "package test;",
                        "import io.appform.dropwizard.sharding.sharding.CopyFromParent;",
                        "import io.appform.dropwizard.sharding.sharding.ParentEntity;",
                        "@ParentEntity(Parent.class)",
                        "public class Child {",
                        "    @CopyFromParent(field = \"name\")",
                        "    private final String childName = null;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("field is final and cannot be a copy target")
                .inFile(child);
    }

    // the processor must not pin @SupportedSourceVersion to RELEASE_11
    @Test
    void testSupportedSourceVersion_isLatestSupported() {
        CopyFromParentProcessor processor = new CopyFromParentProcessor();
        assertEquals(SourceVersion.latestSupported(), processor.getSupportedSourceVersion());
    }

    @Test
    void testUnrelatedCompilationUnit_noWarningEvenWithoutAnnotationsUsed() {
        // Simulates a downstream module that has this processor on its annotation processor
        // path (via META-INF/services) but never uses @CopyFromParent/@ParentEntity at all.
        JavaFileObject plain = JavaFileObjects.forSourceString("test.Unrelated",
                String.join("\n",
                        "package test;",
                        "public class Unrelated {",
                        "    private String value;",
                        "}"));

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(plain);

        assertThat(compilation).succeededWithoutWarnings();
    }

    // Relying solely on explicit .withProcessors(new CopyFromParentProcessor())
    // exercises the validation logic but never proves that javac actually *discovers* the
    // processor via the packaged META-INF/services/javax.annotation.processing.Processor entry
    // the way a real downstream consumer's build would. com.google.testing.compile.Compiler
    // cannot be used for this: it always calls CompilationTask.setProcessors(...) internally
    // (even with an empty list), which per the javac API disables the default ServiceLoader-based
    // discovery entirely. So this test drives javax.tools.JavaCompiler directly, deliberately
    // never calling setProcessors, with a classpath containing this module's compiled classes
    // and resources (including META-INF/services) - exactly like a downstream build would see it.
    @Test
    void testProcessorIsAutoDiscovered_viaServiceLoader_withoutExplicitInstallation() throws IOException {
        Path sourceDir = Files.createTempDirectory("cfp-src");
        Path outputDir = Files.createTempDirectory("cfp-out");
        try {
            Path packageDir = Files.createDirectories(sourceDir.resolve("test/autodiscovery"));
            Files.writeString(packageDir.resolve("Parent.java"),
                    "package test.autodiscovery;\n"
                            + "public class Parent {\n"
                            + "    private String name;\n"
                            + "}\n");
            Files.writeString(packageDir.resolve("Child.java"),
                    "package test.autodiscovery;\n"
                            + "import io.appform.dropwizard.sharding.sharding.CopyFromParent;\n"
                            + "public class Child {\n"
                            + "    @CopyFromParent(field = \"name\")\n"
                            + "    private String childName;\n"
                            + "}\n");

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fileManager =
                    compiler.getStandardFileManager(diagnostics, null, null)) {
                Iterable<? extends JavaFileObject> compilationUnits =
                        fileManager.getJavaFileObjectsFromPaths(List.of(
                                packageDir.resolve("Parent.java"),
                                packageDir.resolve("Child.java")));

                List<String> options = List.of(
                        "-d", outputDir.toString(),
                        // Same classpath as this test JVM, which includes this module's compiled
                        // classes and META-INF/services/javax.annotation.processing.Processor.
                        "-classpath", System.getProperty("java.class.path"));

                JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fileManager, diagnostics, options, null, compilationUnits);

                // Deliberately not calling task.setProcessors(...) here.
                boolean success = task.call();

                assertFalse(success,
                        "Compilation should have failed: Child.childName lacks @ParentEntity, "
                                + "and CopyFromParentProcessor should have been auto-discovered "
                                + "via META-INF/services to catch it");
                assertTrue(diagnostics.getDiagnostics().stream()
                                .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                                        && d.getMessage(null)
                                                .contains("requires @ParentEntity on the enclosing class")),
                        "Expected CopyFromParentProcessor's validation error to be present; "
                                + "processor was not auto-discovered via ServiceLoader");
            }
        } finally {
            deleteRecursively(sourceDir);
            deleteRecursively(outputDir);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }
}
