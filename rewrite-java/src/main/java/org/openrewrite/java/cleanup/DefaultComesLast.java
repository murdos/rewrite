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
package org.openrewrite.java.cleanup;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.style.Checkstyle;
import org.openrewrite.java.style.DefaultComesLastStyle;
import org.openrewrite.java.tree.J;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

public class DefaultComesLast extends Recipe {

    @Override
    public String getDisplayName() {
        return "Default comes last";
    }

    @Override
    public String getDescription() {
        return "Ensure the `default` case comes last after all the cases in a switch statement.";
    }

    @Override
    public Set<String> getTags() {
        return Collections.singleton("RSPEC-4524");
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return Duration.ofMinutes(5);
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new DefaultComesLastFromCompilationUnitStyle();
    }

    private static class DefaultComesLastFromCompilationUnitStyle extends JavaIsoVisitor<ExecutionContext> {
        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext executionContext) {
            DefaultComesLastStyle style = cu.getStyle(DefaultComesLastStyle.class);
            if (style == null) {
                style = Checkstyle.defaultComesLast();
            }
            doAfterVisit(new DefaultComesLastVisitor<>(style));
            return cu;
        }
    }

}
