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
                "package test;\n"
                + "public class Parent {\n"
                + "    private String name;\n"
                + "    public String getName() { return name; }\n"
                + "    public void setName(String name) { this.name = name; }\n"
                + "}\n");

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                "package test;\n"
                + "import io.appform.dropwizard.sharding.sharding.CopyFromParent;\n"
                + "import io.appform.dropwizard.sharding.sharding.ParentEntity;\n"
                + "@ParentEntity(Parent.class)\n"
                + "public class Child {\n"
                + "    @CopyFromParent(field = \"name\")\n"
                + "    private String childName;\n"
                + "}\n");

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).succeeded();
    }

    @Test
    public void testCopyFromParentWithoutParentEntity_fails() {
        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                "package test;\n"
                + "import io.appform.dropwizard.sharding.sharding.CopyFromParent;\n"
                + "public class Child {\n"
                + "    @CopyFromParent(field = \"name\")\n"
                + "    private String childName;\n"
                + "}\n");

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(child);

        assertThat(compilation).failed();
        // The error message is:
        // "@CopyFromParent on Child.childName requires @ParentEntity on the enclosing class test.Child"
        assertThat(compilation)
                .hadErrorContaining("requires @ParentEntity on the enclosing class")
                .inFile(child);
    }

    @Test
    public void testFieldNotFoundOnParent_fails() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                "package test;\n"
                + "public class Parent {\n"
                + "    private String name;\n"
                + "}\n");

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                "package test;\n"
                + "import io.appform.dropwizard.sharding.sharding.CopyFromParent;\n"
                + "import io.appform.dropwizard.sharding.sharding.ParentEntity;\n"
                + "@ParentEntity(Parent.class)\n"
                + "public class Child {\n"
                + "    @CopyFromParent(field = \"nonExistent\")\n"
                + "    private String childField;\n"
                + "}\n");

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).failed();
        // The error message is:
        // "@CopyFromParent(field="nonExistent") on Child.childField: field 'nonExistent' not found on parent test.Parent"
        assertThat(compilation)
                .hadErrorContaining("field 'nonExistent' not found on parent")
                .inFile(child);
    }

    @Test
    public void testTypeMismatch_fails() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                "package test;\n"
                + "public class Parent {\n"
                + "    private int count;\n"
                + "}\n");

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                "package test;\n"
                + "import io.appform.dropwizard.sharding.sharding.CopyFromParent;\n"
                + "import io.appform.dropwizard.sharding.sharding.ParentEntity;\n"
                + "@ParentEntity(Parent.class)\n"
                + "public class Child {\n"
                + "    @CopyFromParent(field = \"count\")\n"
                + "    private String childCount;\n"
                + "}\n");

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).failed();
        // The error message is:
        // "@CopyFromParent type mismatch on Child.childCount: parent field 'count' is int, child field is java.lang.String"
        assertThat(compilation)
                .hadErrorContaining("type mismatch on Child.childCount: parent field 'count' is int")
                .inFile(child);
    }

    @Test
    public void testTransientParentField_fails() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                "package test;\n"
                + "import javax.persistence.Transient;\n"
                + "public class Parent {\n"
                + "    @Transient\n"
                + "    private String tempValue;\n"
                + "}\n");

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                "package test;\n"
                + "import io.appform.dropwizard.sharding.sharding.CopyFromParent;\n"
                + "import io.appform.dropwizard.sharding.sharding.ParentEntity;\n"
                + "@ParentEntity(Parent.class)\n"
                + "public class Child {\n"
                + "    @CopyFromParent(field = \"tempValue\")\n"
                + "    private String childTemp;\n"
                + "}\n");

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).failed();
        // The error message is:
        // "@CopyFromParent(field="tempValue") on Child.childTemp: parent field 'tempValue' on Parent is marked @Transient and will not be persisted"
        assertThat(compilation)
                .hadErrorContaining("marked @Transient and will not be persisted")
                .inFile(child);
    }

    @Test
    public void testCompatibleTypes_compiles() {
        // int -> long should be assignable (widening primitive conversion)
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                "package test;\n"
                + "public class Parent {\n"
                + "    private int count;\n"
                + "}\n");

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                "package test;\n"
                + "import io.appform.dropwizard.sharding.sharding.CopyFromParent;\n"
                + "import io.appform.dropwizard.sharding.sharding.ParentEntity;\n"
                + "@ParentEntity(Parent.class)\n"
                + "public class Child {\n"
                + "    @CopyFromParent(field = \"count\")\n"
                + "    private long childCount;\n"
                + "}\n");

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).succeeded();
    }

    @Test
    public void testMultipleFields_compiles() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                "package test;\n"
                + "public class Parent {\n"
                + "    private String name;\n"
                + "    private int count;\n"
                + "}\n");

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                "package test;\n"
                + "import io.appform.dropwizard.sharding.sharding.CopyFromParent;\n"
                + "import io.appform.dropwizard.sharding.sharding.ParentEntity;\n"
                + "@ParentEntity(Parent.class)\n"
                + "public class Child {\n"
                + "    @CopyFromParent(field = \"name\")\n"
                + "    private String childName;\n"
                + "    @CopyFromParent(field = \"count\")\n"
                + "    private int childCount;\n"
                + "}\n");

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(parent, child);

        assertThat(compilation).succeeded();
    }

    @Test
    public void testInheritedParentField_compiles() {
        JavaFileObject grandParent = JavaFileObjects.forSourceString("test.GrandParent",
                "package test;\n"
                + "public class GrandParent {\n"
                + "    private String inheritedField;\n"
                + "}\n");

        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                "package test;\n"
                + "public class Parent extends GrandParent {\n"
                + "    private String ownField;\n"
                + "}\n");

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                "package test;\n"
                + "import io.appform.dropwizard.sharding.sharding.CopyFromParent;\n"
                + "import io.appform.dropwizard.sharding.sharding.ParentEntity;\n"
                + "@ParentEntity(Parent.class)\n"
                + "public class Child {\n"
                + "    @CopyFromParent(field = \"inheritedField\")\n"
                + "    private String childField;\n"
                + "}\n");

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(grandParent, parent, child);

        assertThat(compilation).succeeded();
    }

    @Test
    public void testNoAnnotations_compiles() {
        JavaFileObject plain = JavaFileObjects.forSourceString("test.Plain",
                "package test;\n"
                + "public class Plain {\n"
                + "    private String name;\n"
                + "}\n");

        Compilation compilation = Compiler.javac()
                .withProcessors(new CopyFromParentProcessor())
                .compile(plain);

        assertThat(compilation).succeeded();
    }
}
