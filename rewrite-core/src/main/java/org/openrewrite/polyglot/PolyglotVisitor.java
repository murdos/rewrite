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
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.lang.Nullable;

public class PolyglotVisitor<T> extends TreeVisitor<Tree, T> {

    private final Value value;
    private final TreeVisitor<? extends Tree, T> delegate;

    public PolyglotVisitor(Value value, TreeVisitor<? extends Tree, T> delegate) {
        this.value = value;
        this.delegate = delegate;
    }

    @Override
    public @Nullable Tree visit(@Nullable Tree tree, T ctx) {
        value.putMember("super", Value.asValue(delegate));
        Value v = value.invokeMember("visit", tree, ctx);
        return v.as(Tree.class);
    }

}
