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
package org.openrewrite.java.style;

import lombok.Value;
import lombok.With;
import org.openrewrite.style.Style;
import org.openrewrite.style.StyleHelper;

@Value
@With
public class EmptyForIteratorPadStyle implements Style {
    /**
     * Track whether empty for loop iterators should have a space or not.
     * When true: for (int i = 0; i < 10; )
     * When false: for(int i = 0; i < 10;)
     */
    Boolean space;

    @Override
    public Style applyDefaults() {
        return StyleHelper.merge(Checkstyle.emptyForIteratorPadStyle(), this);
    }
}
