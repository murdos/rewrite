/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.openrewrite.Recipe
import org.openrewrite.java.search.FindTypes
import java.nio.file.Paths

interface ChangePackageTest: JavaRecipeTest {

    override val recipe: Recipe
        get() = ChangePackage("org.openrewrite", "org.openrewrite.test", null)

    companion object {
        private val testClass = """
            package org.openrewrite;
            public class Test extends Exception {
                public static void stat() {}
                public void foo() {}
            }
        """.trimIndent()
    }

    @Test
    fun renamePackageNonRecursive(jp: JavaParser) = assertUnchanged(
        jp,
        recipe = ChangePackage(
            "org.openrewrite",
            "org.openrewrite.test",
            false
        ),
        before = """
            package org.openrewrite.internal;
            class Test {
            }
        """
    )

    @Test
    fun dontAddImportWhenNoChangesWereMade(jp: JavaParser) = assertUnchanged(
        jp,
        before = """
            public class B {}
        """
    )

    @Test
    fun renamePackage(jp: JavaParser) = assertChanged(
        jp,
        before = """
            package org.openrewrite;
            class Test {
            }
        """,
        after = """ 
            package org.openrewrite.test;
            class Test {
            }
        """,
        afterConditions = { cu ->
            assertThat(FindTypes.find(cu, "org.openrewrite.Test")).isEmpty()
            assertThat(cu.sourcePath)
                .isEqualTo(Paths.get("org", "openrewrite", "test", "Test.java"))
        }
    )

    @Test
    fun renamePackageRecursive(jp: JavaParser) = assertChanged(
        jp,
        before = """
            package org.openrewrite.internal;
            class Test {
            }
        """,
        after = """ 
            package org.openrewrite.test.internal;
            class Test {
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.sourcePath)
                .isEqualTo(Paths.get("org/openrewrite/test/internal/Test.java"))
            assertThat(FindTypes.find(cu, "org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun renamePackageReferences(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf("""
            package org.openrewrite;
            public class Test {
            }
        """),
        before = """
            import org.openrewrite.*;
            
            class A<T extends org.openrewrite.Test> {
                Test test;
            }
        """,
        after = """ 
            import org.openrewrite.test.*;
            
            class A<T extends org.openrewrite.test.Test> {
                Test test;
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun simpleName(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            import org.openrewrite.Test;

            public class B extends Test {}
        """,
        after = """
            import org.openrewrite.test.Test;

            public class B extends Test {}
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun fullyQualifiedName(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class B extends org.openrewrite.Test {}
        """,
        after = """
            public class B extends org.openrewrite.test.Test {}
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun annotation(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf("""
                package org.openrewrite;
                public @interface A {}
        """),
        before = """
            @org.openrewrite.A public class B {}
        """,

        after = """
            @org.openrewrite.test.A public class B {}
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    // array types and new arrays
    @Test
    fun array(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class B {
               org.openrewrite.Test[] a = new org.openrewrite.Test[0];
            }
        """,
        after = """
            public class B {
               org.openrewrite.test.Test[] a = new org.openrewrite.test.Test[0];
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun classDecl(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass, "public interface I1 {}"),
        before = """
            public class B extends org.openrewrite.Test implements I1 {}
        """,
        after = """
            public class B extends org.openrewrite.test.Test implements I1 {}
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun updatesImplements(jp: JavaParser) = assertChanged(
        dependsOn = arrayOf(
            """
                package org.openrewrite;
                public interface Oi{}
            """
        ),
        before = """
            package org.openrewrite;
            
            public class Mi implements org.openrewrite.Oi {
            }
        """,
        after = """
            package org.openrewrite.test;
            
            public class Mi implements org.openrewrite.test.Oi {
            }
        """
    )

    @Test
    fun moveToSubPackageRemoveImport() = assertChanged(
        recipe = ChangePackage("com.acme.project", "com.acme.product", null),
        dependsOn = arrayOf("""
            package com.acme.product;

            public class RunnableFactory {
                public static Runnable getRunnable() {
                    return null;
                }
            }
        """),
        before = """
            package com.acme.project;

            import com.acme.product.RunnableFactory;
            
            public class StaticImportWorker {
                public void work() {
                    RunnableFactory.getRunnable().run();
                }
            }
        """,
        after = """
            package com.acme.product;
            
            public class StaticImportWorker {
                public void work() {
                    RunnableFactory.getRunnable().run();
                }
            }
        """
    )


    @Test
    fun lambda(jp: JavaParser) = assertChanged(
        dependsOn = arrayOf("""
            package org.openrewrite;
            public interface Procedure {
                void execute();
            }
        """),
        before = """
            import org.openrewrite.Procedure;
            public abstract class Worker {
                void callWorker() {
                    worker(() -> {});
                }
                abstract void worker(Procedure procedure);
            }
        """,
        after = """
            import org.openrewrite.test.Procedure;
            public abstract class Worker {
                void callWorker() {
                    worker(() -> {});
                }
                abstract void worker(Procedure procedure);
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Procedure")).isEmpty()
        }
    )

    @Test
    fun method(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class B {
               public org.openrewrite.Test foo() { return null; }
            }
        """,
        after = """
            public class B {
               public org.openrewrite.test.Test foo() { return null; }
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun methodInvocationTypeParametersAndWildcard(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class B {
               public <T extends org.openrewrite.Test> T generic(T n, List<? super org.openrewrite.Test> in);
               public void test() {
                   org.openrewrite.Test.stat();
                   this.<org.openrewrite.Test>generic(null, null);
               }
            }
        """,
        after = """
            public class B {
               public <T extends org.openrewrite.test.Test> T generic(T n, List<? super org.openrewrite.test.Test> in);
               public void test() {
                   org.openrewrite.test.Test.stat();
                   this.<org.openrewrite.test.Test>generic(null, null);
               }
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun multiCatch(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class B {
               public void test() {
                   try {}
                   catch(org.openrewrite.Test | RuntimeException e) {}
               }
            }
        """,
        after = """
            public class B {
               public void test() {
                   try {}
                   catch(org.openrewrite.test.Test | RuntimeException e) {}
               }
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun multiVariable(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class B {
               org.openrewrite.Test f1, f2;
            }
        """,
        after = """
            public class B {
               org.openrewrite.test.Test f1, f2;
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun assignment(jp: JavaParser) = assertChanged(
        dependsOn = arrayOf(testClass),
        before = """
            public class B {
               org.openrewrite.Test t;
               void method(org.openrewrite.Test param) {
                   t = param;
               }
            }
        """,
        after = """
            public class B {
               org.openrewrite.test.Test t;
               void method(org.openrewrite.test.Test param) {
                   t = param;
               }
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun newClass(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class B {
               org.openrewrite.Test test = new org.openrewrite.Test();
            }
        """,
        after = """
            public class B {
               org.openrewrite.test.Test test = new org.openrewrite.test.Test();
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun parameterizedType(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class B {
               Map<org.openrewrite.Test, org.openrewrite.Test> m;
            }
        """,
        after = """
            public class B {
               Map<org.openrewrite.test.Test, org.openrewrite.test.Test> m;
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun typeCast(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class B {
               org.openrewrite.Test a = (org.openrewrite.Test) null;
            }
        """,
        after = """
            public class B {
               org.openrewrite.test.Test a = (org.openrewrite.test.Test) null;
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun classReference(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class Test {
                Class<?> clazz = org.openrewrite.Test.class;
            }
        """,
        after = """
            public class Test {
                Class<?> clazz = org.openrewrite.test.Test.class;
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun methodSelect(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            public class B {
               org.openrewrite.Test test = null;
               public void getFoo() { test.foo(); }
            }
        """,
        after = """
            public class B {
               org.openrewrite.test.Test test = null;
               public void getFoo() { test.foo(); }
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun staticImport(jp: JavaParser) = assertChanged(
        jp,
        dependsOn = arrayOf(testClass),
        before = """
            import static org.openrewrite.Test.stat;

            public class B {
                public void test() {
                    stat();
                }
            }
        """,
        after = """
            import static org.openrewrite.test.Test.stat;

            public class B {
                public void test() {
                    stat();
                }
            }
        """,
        afterConditions = { cu ->
            assertThat(cu.findType("org.openrewrite.Test")).isEmpty()
        }
    )

    @Test
    fun changeTypeWithInnerClass(jp: JavaParser) = assertChanged(
        recipe = ChangePackage("com.acme.product", "com.acme.product.v2", null),
        dependsOn = arrayOf(
            """
                package com.acme.product;
                
                public class OuterClass {
                    public static class InnerClass {
                
                    }
                }
            """
        ),
        before = """
            package de;
            
            import com.acme.product.OuterClass.InnerClass;
            import com.acme.product.OuterClass;
            
            public class UseInnerClass {
                public String work() {
                    return new InnerClass().toString();
                }
            
                public String work2() {
                    return new OuterClass().toString();
                }
            }
        """,
        after = """
            package de;

            import com.acme.product.v2.OuterClass.InnerClass;
            import com.acme.product.v2.OuterClass;
            
            public class UseInnerClass {
                public String work() {
                    return new InnerClass().toString();
                }
            
                public String work2() {
                    return new OuterClass().toString();
                }
            }
        """
    )

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    @Test
    fun checkValidation() {
        var recipe = ChangePackage(null, null, null)
        var valid = recipe.validate()
        assertThat(valid.isValid).isFalse
        assertThat(valid.failures()).hasSize(2)
        assertThat(valid.failures()[0].property).isEqualTo("newPackageName")
        assertThat(valid.failures()[1].property).isEqualTo("oldPackageName")

        recipe = ChangePackage(null, "java.lang.String", null)
        valid = recipe.validate()
        assertThat(valid.isValid).isFalse
        assertThat(valid.failures()).hasSize(1)
        assertThat(valid.failures()[0].property).isEqualTo("oldPackageName")

        recipe = ChangePackage("java.lang.String", null, null)
        valid = recipe.validate()
        assertThat(valid.isValid).isFalse
        assertThat(valid.failures()).hasSize(1)
        assertThat(valid.failures()[0].property).isEqualTo("newPackageName")
    }
}
