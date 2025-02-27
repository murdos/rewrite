/*
 *  Copyright 2021 the original author or authors.
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  https://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openrewrite.polyglot;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;

public class PolyglotRecipe extends Recipe {

    private final String name;
    private final Value value;

    public PolyglotRecipe(String name, Value value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return value.invokeMember("getDisplayName").asString();
    }

    @Override
    public String getDescription() {
        return value.invokeMember("getDescription").asString();
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new PolyglotVisitor<>(value.invokeMember("getVisitor"), super.getVisitor());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PolyglotRecipe)) {
            return false;
        }
        return ((PolyglotRecipe) o).getName().equals(getName());
    }

    private class DoNextProxy implements ProxyExecutable {
        @Override
        public Object execute(Value... arguments) {
            return doNext(arguments[0].as(Recipe.class));
        }
    }
}
