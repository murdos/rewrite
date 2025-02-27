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
package org.openrewrite.java;

import lombok.Getter;
import lombok.Setter;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.internal.grammar.MethodSignatureLexer;
import org.openrewrite.java.internal.grammar.MethodSignatureParser;
import org.openrewrite.java.internal.grammar.MethodSignatureParserBaseVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;

/**
 * This class accepts an AspectJ method pattern and is used to identify methods that match the expression. The
 * format of the method pattern is as follows:
 * <P><P><B>
 * #declaring class# #method name#(#argument list#)
 * </B><P>
 * <li>The declaring class must be fully qualified.</li>
 * <li>A wildcard character, "*", may be used in either the declaring class or method name.</li>
 * <li>The argument list is expressed as a comma-separated list of the argument types</li>
 * <li>".." can be used in the argument list to match zero or more arguments of any type.</li>
 * <P><PRE>
 * EXAMPLES:
 * <p>
 * * *(..)                                 - All method invocations
 * java.util.* *(..)                       - All method invocations to classes belonging to java.util (including sub-packages)
 * java.util.Collections *(..)             - All method invocations on java.util.Collections class
 * java.util.Collections unmodifiable*(..) - All method invocations starting with "unmodifiable" on java.util.Collections
 * java.util.Collections min(..)           - All method invocations for all overloads of "min"
 * java.util.Collections emptyList()       - All method invocations on java.util.Collections.emptyList()
 * my.org.MyClass *(boolean, ..)           - All method invocations where the first arg is a boolean in my.org.MyClass
 * </PRE>
 */
@SuppressWarnings("NotNullFieldNotInitialized")
@Getter
public class MethodMatcher {
    private Pattern targetTypePattern;
    private Pattern methodNamePattern;
    private Pattern argumentPattern;

    /**
     * Whether to match overridden forms of the method on subclasses of {@link #targetTypePattern}.
     */
    private final boolean matchOverrides;

    public MethodMatcher(String signature, @Nullable Boolean matchOverrides) {
        this(signature, Boolean.TRUE.equals(matchOverrides));
    }

    public MethodMatcher(String signature, boolean matchOverrides) {
        this.matchOverrides = matchOverrides;

        MethodSignatureParser parser = new MethodSignatureParser(new CommonTokenStream(new MethodSignatureLexer(
                CharStreams.fromString(signature))));

        new MethodSignatureParserBaseVisitor<Void>() {
            @Override
            public Void visitMethodPattern(MethodSignatureParser.MethodPatternContext ctx) {
                targetTypePattern = Pattern.compile(new TypeVisitor().visitTargetTypePattern(ctx.targetTypePattern()));
                methodNamePattern = Pattern.compile(ctx.simpleNamePattern().children.stream()
                        .map(c -> AspectjUtils.aspectjNameToPattern(c.toString()))
                        .collect(joining("")));
                argumentPattern = Pattern.compile(new FormalParameterVisitor().visitFormalParametersPattern(
                        ctx.formalParametersPattern()));
                return null;
            }
        }.visit(parser.methodPattern());
    }

    public MethodMatcher(J.MethodDeclaration method, boolean matchOverrides) {
        this(methodPattern(method), matchOverrides);
    }

    public MethodMatcher(String signature) {
        this(signature, false);
    }

    public MethodMatcher(J.MethodDeclaration method) {
        this(method, false);
    }

    public boolean matches(@Nullable JavaType type) {
        if (!(type instanceof JavaType.Method)) {
            return false;
        }

        JavaType.Method methodType = (JavaType.Method) type;

        return matchesTargetType(methodType.getDeclaringType()) &&
                methodNamePattern.matcher(methodType.getName()).matches() &&
                methodType.getGenericSignature() != null &&
                argumentPattern.matcher(methodType.getGenericSignature().getParamTypes().stream()
                        .map(MethodMatcher::typePattern)
                        .filter(Objects::nonNull)
                        .collect(joining(","))).matches();
    }

    public boolean matches(J.MethodDeclaration method, J.ClassDeclaration enclosing) {
        if (enclosing.getType() == null) {
            return false;
        }

        // aspectJUtils does not support matching classes separated by packages.
        // [^.]* is the product of a fully wild cart match for a method. `* foo()`
        return (targetTypePattern.toString().equals("[^.]*") || matchesTargetType(enclosing.getType())) &&
                methodNamePattern.matcher(method.getSimpleName()).matches() &&
                argumentPattern.matcher(method.getParameters().stream()
                        .map(v -> {
                            if (v instanceof J.VariableDeclarations) {
                                J.VariableDeclarations vd = (J.VariableDeclarations) v;
                                if (vd.getTypeAsFullyQualified() != null) {
                                    return vd.getTypeAsFullyQualified();
                                } else {
                                    return vd.getTypeExpression() != null ? vd.getTypeExpression().getType() : null;
                                }
                            } else {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .map(MethodMatcher::typePattern)
                        .filter(Objects::nonNull)
                        .collect(joining(","))).matches();
    }

    public boolean matches(Expression maybeMethod) {
        return maybeMethod instanceof J.MethodInvocation && matches((J.MethodInvocation) maybeMethod);
    }

    public boolean matches(J.MethodInvocation method) {
        if (method.getType() == null || method.getType().getDeclaringType() == null) {
            return false;
        }

        if (method.getType().getResolvedSignature() == null) {
            // no way to verify the parameter list
            return false;
        }

        return matchesTargetType(method.getType().getDeclaringType()) &&
                methodNamePattern.matcher(method.getSimpleName()).matches() &&
                argumentPattern.matcher(method.getType().getResolvedSignature().getParamTypes().stream()
                        .map(MethodMatcher::typePattern)
                        .filter(Objects::nonNull)
                        .collect(joining(","))).matches();
    }

    public boolean matches(J.NewClass constructor) {
        if (constructor.getType() == null || constructor.getConstructorType() == null) {
            return false;
        }

        JavaType.FullyQualified type = TypeUtils.asFullyQualified(constructor.getType());
        assert type != null;
        return matchesTargetType(type) &&
                methodNamePattern.matcher("<constructor>").matches() &&
                constructor.getConstructorType().getResolvedSignature() != null &&
                argumentPattern.matcher(constructor.getConstructorType().getResolvedSignature().getParamTypes().stream()
                        .map(MethodMatcher::typePattern)
                        .filter(Objects::nonNull)
                        .collect(joining(","))).matches();
    }

    boolean matchesTargetType(@Nullable JavaType.FullyQualified type) {
        if (type == null) {
            return false;
        }

        if (targetTypePattern.matcher(type.getFullyQualifiedName()).matches()) {
            return true;
        } else if (type != JavaType.Class.OBJECT && (matchesTargetType(type.getSupertype() == null ? JavaType.Class.OBJECT : type.getSupertype()))) {
            return true;
        }

        if (matchOverrides) {
            if (matchesTargetType(type.getSupertype())) {
                return true;
            }

            for (JavaType.FullyQualified anInterface : type.getInterfaces()) {
                if (matchesTargetType(anInterface)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Evaluate whether this MethodMatcher and the specified FieldAccess are describing the same type or not.
     * Known limitation/bug: MethodMatchers can have patterns/wildcards like "com.*.Bar" instead of something
     * concrete like "com.foo.Bar". This limitation is not desirable or intentional and should be fixed.
     * If a methodMatcher is passed that includes wildcards the result will always be "false"
     *
     * @param fieldAccess A J.FieldAccess that hopefully has the same fully qualified type as this matcher.
     */
    public boolean isFullyQualifiedClassReference(J.FieldAccess fieldAccess) {
        String hopefullyFullyQualifiedMethod = this.getTargetTypePattern().pattern() + "." + this.getMethodNamePattern().pattern();
        return fieldAccess.isFullyQualifiedClassReference(hopefullyFullyQualifiedMethod);
    }

    @Nullable
    private static String typePattern(JavaType type) {
        if (type instanceof JavaType.Primitive) {
            return ((JavaType.Primitive) type).getKeyword();
        } else if (type instanceof JavaType.FullyQualified) {
            return ((JavaType.FullyQualified) type).getFullyQualifiedName();
        } else if (type instanceof JavaType.Array) {
            JavaType elemType = ((JavaType.Array) type).getElemType();
            if (elemType != null) {
                return typePattern(elemType) + "[]";
            }
        }
        return null;
    }

    public static String methodPattern(J.MethodDeclaration method) {
        assert method.getType() != null;

        JavaType.Method.Signature signature = method.getType().getResolvedSignature();
        String signatureStr;
        if (signature == null) {
            signatureStr = "";
        } else {
            StringJoiner joiner = new StringJoiner(",");
            for (JavaType javaType : signature.getParamTypes()) {
                String s = typePattern(javaType);
                if (s != null) {
                    joiner.add(s);
                }
            }
            signatureStr = joiner.toString();
        }

        return method.getType().getDeclaringType().getFullyQualifiedName() + " " +
                method.getSimpleName() + "(" + signatureStr + ")";
    }
}

class TypeVisitor extends MethodSignatureParserBaseVisitor<String> {
    @Override
    public String visitClassNameOrInterface(MethodSignatureParser.ClassNameOrInterfaceContext ctx) {
        StringBuilder classNameBuilder = new StringBuilder();
        for (ParseTree c : ctx.children) {
            classNameBuilder.append(AspectjUtils.aspectjNameToPattern(c.getText()));
        }
        String className = classNameBuilder.toString();

        if (!className.contains(".")) {
            try {
                int arrInit = className.lastIndexOf("\\[");
                Class.forName("java.lang." + (arrInit == -1 ? className : className.substring(0, arrInit)), false, TypeVisitor.class.getClassLoader());
                return "java.lang." + className;
            } catch (ClassNotFoundException ignored) {
            }
        }

        return className;
    }
}

/**
 * The wildcard .. indicates zero or more parameters, so:
 *
 * <code>execution(void m(..))</code>
 * picks out execution join points for void methods named m, of any number of arguments, while
 *
 * <code>execution(void m(.., int))</code>
 * picks out execution join points for void methods named m whose last parameter is of type int.
 */
class FormalParameterVisitor extends MethodSignatureParserBaseVisitor<String> {
    private final List<Argument> arguments = new ArrayList<>();

    @Override
    public String visitTerminal(TerminalNode node) {
        if ("...".equals(node.getText())) {
            ((Argument.FormalType) arguments.get(arguments.size() - 1)).setVariableArgs(true);
        }
        return super.visitTerminal(node);
    }

    @Override
    public String visitDotDot(MethodSignatureParser.DotDotContext ctx) {
        arguments.add(Argument.DOT_DOT);
        return super.visitDotDot(ctx);
    }

    @Override
    public String visitFormalTypePattern(MethodSignatureParser.FormalTypePatternContext ctx) {
        arguments.add(new Argument.FormalType(ctx));
        return super.visitFormalTypePattern(ctx);
    }

    @Override
    public String visitFormalParametersPattern(MethodSignatureParser.FormalParametersPatternContext ctx) {
        super.visitFormalParametersPattern(ctx);

        List<String> argumentPatterns = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            Argument argument = arguments.get(i);

            // Note: the AspectJ grammar doesn't allow for multiple ..'s in one formal parameter pattern
            if (argument == Argument.DOT_DOT) {
                if (arguments.size() == 1) {
                    argumentPatterns.add("(" + argument.getRegex() + ")?");
                } else if (i > 0) {
                    argumentPatterns.add("(," + argument.getRegex() + ")?");
                } else {
                    argumentPatterns.add("(" + argument.getRegex() + ",)?");
                }
            } else { // FormalType
                if (i > 0 && arguments.get(i - 1) != Argument.DOT_DOT) {
                    argumentPatterns.add("," + argument.getRegex());
                } else {
                    argumentPatterns.add(argument.getRegex());
                }
            }
        }

        return String.join("", argumentPatterns).replace("...", "\\[\\]");
    }

    private static abstract class Argument {
        abstract String getRegex();

        private static final Argument DOT_DOT = new Argument() {
            @Override
            String getRegex() {
                return "([^,]+,)*([^,]+)";
            }
        };

        private static class FormalType extends Argument {
            private final MethodSignatureParser.FormalTypePatternContext ctx;

            @Setter
            private boolean variableArgs = false;

            public FormalType(MethodSignatureParser.FormalTypePatternContext ctx) {
                this.ctx = ctx;
            }

            @Override
            String getRegex() {
                String baseType = new TypeVisitor().visitFormalTypePattern(ctx);
                return baseType + (variableArgs ? "\\[\\]" : "");
            }
        }
    }
}

class AspectjUtils {
    private AspectjUtils() {
    }

    /**
     * See https://eclipse.org/aspectj/doc/next/progguide/semantics-pointcuts.html#type-patterns
     * <p>
     * An embedded * in an identifier matches any sequence of characters, but
     * does not match the package (or inner-type) separator ".".
     * <p>
     * The ".." wildcard matches any sequence of characters that start and end with a ".", so it can be used to pick out all
     * types in any subpackage, or all inner types. e.g. <code>within(com.xerox..*)</code> picks out all join points where
     * the code is in any declaration of a type whose name begins with "com.xerox.".
     */
    public static String aspectjNameToPattern(String name) {
        return name
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replaceAll("([^.])*\\.([^.])*", "$1\\.$2")
                .replace("*", "[^.]*")
                .replace("..", "\\.(.+\\.)?");
    }
}
