/*
 * Copyright 2021 the original author or authors.
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
package org.openrewrite.java.cleanup;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;
import java.util.function.Predicate;

@Value
@EqualsAndHashCode(callSuper = true)
public class MissingOverrideAnnotation extends Recipe {
    @Option(displayName = "Ignore `Object` methods",
            description = "When enabled, ignore missing annotations on methods which override methods from the base `java.lang.Object` class such as `equals()`, `hashCode()`, or `toString()`.",
            required = false)
    @Nullable
    Boolean ignoreObjectMethods;

    @Option(displayName = "Ignore methods in anonymous classes",
            description = "When enabled, ignore missing annotations on methods which override methods when the class definition is within an anonymous class.",
            required = false)
    @Nullable
    Boolean ignoreAnonymousClassMethods;

    @Override
    public String getDisplayName() {
        return "Add missing `@Override` to overriding and implementing methods";
    }

    @Override
    public String getDescription() {
        return "Adds `@Override` to methods overriding superclass methods or implementing interface methods. " +
                "Annotating methods improves readability by showing the author's intent to override. " +
                "Additionally, when annotated, the compiler will emit an error when a signature of the overridden method does not match the superclass method.";
    }

    @Override
    public Set<String> getTags() {
        return Collections.singleton("RSPEC-1161");
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MissingOverrideAnnotationVisitor();
    }

    private class MissingOverrideAnnotationVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final AnnotationMatcher OVERRIDE_ANNOTATION = new AnnotationMatcher("@java.lang.Override");

        private Cursor getCursorToParentScope(Cursor cursor) {
            return cursor.dropParentUntil(is -> is instanceof J.NewClass || is instanceof J.ClassDeclaration);
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            // for efficiency, first check for type attribution and whether @Override is present.
            if (method.getType() != null &&
                    method.getType().getDeclaringType() != null &&
                    !method.hasModifier(J.Modifier.Type.Static) &&
                    method.getAllAnnotations().stream().noneMatch(OVERRIDE_ANNOTATION::matches)
            ) {
                if (!(Boolean.TRUE.equals(ignoreAnonymousClassMethods) && getCursorToParentScope(getCursor()).getValue() instanceof J.NewClass)) {
                    JavaType.FullyQualified declaringType = method.getType().getDeclaringType();
                    if (new FindOverriddenAndImplementedMethodDeclarations(method, declaringType).hasAny()) {
                        method = method.withTemplate(
                                JavaTemplate.builder(this::getCursor, "@Override").build(),
                                method.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
                        );
                    }
                }
            }
            return super.visitMethodDeclaration(method, ctx);
        }

        private class FindOverriddenAndImplementedMethodDeclarations {
            private final J.MethodDeclaration methodTarget;
            private final JavaType.FullyQualified declaringType;

            private FindOverriddenAndImplementedMethodDeclarations(J.MethodDeclaration methodTarget, JavaType.FullyQualified declaringType) {
                this.methodTarget = methodTarget;
                this.declaringType = declaringType;
            }

            private boolean hasAny() {
                return hasAny(declaringType);
            }

            private boolean hasAny(@Nullable JavaType.FullyQualified typeToSearch) {
                if (typeToSearch == null || methodTarget.getType() == null ||
                        ((ignoreObjectMethods == null || Boolean.TRUE.equals(ignoreObjectMethods)) && "java.lang.Object".equals(typeToSearch.getFullyQualifiedName()))) {
                    return false;
                }

                // base case: skip visiting the declaringType class methods, only visit the interfaces. otherwise, we add @Override to everything.
                if (!declaringType.getFullyQualifiedName().equals(typeToSearch.getFullyQualifiedName())) {
                    String mPattern = MethodMatcher.methodPattern(methodTarget.withType(methodTarget.getType().withDeclaringType(typeToSearch)));
                    MethodMatcher matcher = new MethodMatcher(mPattern);
                    Predicate<JavaType.Method> filtering;
                    if (typeToSearch.getKind() == JavaType.Class.Kind.Class) {
                        filtering = (m) -> !(m.hasFlags(Flag.Abstract) || m.hasFlags(Flag.Abstract)) && matcher.matches(m);
                    } else {
                        filtering = (m) -> !m.hasFlags(Flag.Static) && matcher.matches(m);
                    }

                    if (typeToSearch.getMethods().stream().anyMatch(filtering)) {
                        return true;
                    }
                }

                if (typeToSearch.getInterfaces().stream().anyMatch(this::hasAny)) {
                    return true;
                }
                if (typeToSearch.getSupertype() != null) {
                    return hasAny(typeToSearch.getSupertype());
                }
                return false;
            }

        }

    }


}
