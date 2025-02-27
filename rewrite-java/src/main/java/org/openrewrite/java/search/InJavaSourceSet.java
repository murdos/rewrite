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
package org.openrewrite.java.search;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.J;

@Value
@EqualsAndHashCode(callSuper = true)
public class InJavaSourceSet<P> extends JavaIsoVisitor<P> {
    String sourceSet;

    @Override
    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, P p) {
        return cu.getMarkers().findFirst(JavaSourceSet.class)
                .filter(sourceSet -> !sourceSet.getName().equals(this.sourceSet))
                .map(sourceSet -> cu)
                .orElse(cu.withMarkers(cu.getMarkers().searchResult()));
    }
}
