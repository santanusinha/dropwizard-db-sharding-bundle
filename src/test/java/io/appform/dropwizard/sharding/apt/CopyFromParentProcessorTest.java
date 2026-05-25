package io.appform.dropwizard.sharding.apt;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;

public class CopyFromParentProcessorTest {

    @Test
    public void testValidAnnotation_compiles() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                String.join("\n",
                        "package test;",
                        "public class Parent {",
                        "    private String name;",
                        "    public String getName() { return name; }",
                        "    public void setName(String name) { this.name = name; }",
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
    public void testCopyFromParentWithoutParentEntity_fails() {
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
    public void testFieldNotFoundOnParent_fails() {
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
    public void testTypeMismatch_fails() {
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
    public void testTransientParentField_fails() {
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
    public void testCompatibleTypes_compiles() {
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
    public void testMultipleFields_compiles() {
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
    public void testInheritedParentField_compiles() {
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
    public void testNoAnnotations_compiles() {
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
}
